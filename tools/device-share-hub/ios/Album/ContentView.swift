import UIKit
import ImageIO

final class LibraryViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private let library: WorkLibrary
    private let emptyStack = UIStackView()
    private let emptyDetail = UILabel()
    private var collectionView: UICollectionView!
    private var toastView: UILabel?
    private var initialFolderPromptShown = false
    private var selectedCategory = WorkCategory.all
    private var filterScrollView: UIScrollView!
    private var filterStackView: UIStackView!
    private var filterButtons: [String: UIButton] = [:]

    // MARK: - 在线相册状态
    private var isOnlineMode: Bool = false
    private var onlineWorks: [OnlineWorkEntry] = []
    private var onlineCategories: [OnlineCategoryItem] = []
    private var selectedOnlineCategory: String = "全部"
    private var modeButton: UIButton!
    private var folderItem: UIBarButtonItem?
    private let prefOnlineModeKey = "pref_is_online_mode"
    /// 自动发现节流：用「15 秒冷却」代替「一次性开关」，失败后可反复重试
    private var autoDiscovering = false
    private var lastAutoDiscoverAt: Date = .distantPast

    private var filteredWorks: [WorkItem] {
        guard selectedCategory != WorkCategory.all else { return library.works }
        return library.works.filter { $0.folderName == selectedCategory }
    }

    private var filteredOnlineWorks: [OnlineWorkEntry] {
        let active = OnlineWorkLifecycle.filterActiveOnlineWorks(works: onlineWorks)
        guard selectedOnlineCategory != "全部" else { return active }
        return active.filter { $0.destination == selectedOnlineCategory }
    }

    init(library: WorkLibrary) {
        self.library = library
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        isOnlineMode = UserDefaults.standard.bool(forKey: prefOnlineModeKey)
        view.backgroundColor = AppColors.background
        configureNavigation()
        configureFilterBar()
        configureCollection()
        configureEmptyView()
        library.onChange = { [weak self] in
            guard let self = self, !self.isOnlineMode else { return }
            self.render()
        }

        if isOnlineMode {
            loadOnlineData()
        } else {
            render()
        }
    }

    @objc private func openTransfer() {
        navigationController?.pushViewController(TransferViewController(), animated: true)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if isOnlineMode {
            loadOnlineData(silent: true)
        } else {
            render()
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !isOnlineMode {
            showInitialFolderPromptIfNeeded()
        }
    }

    private func configureNavigation() {
        navigationItem.backBarButtonItem = UIBarButtonItem(title: "返回", style: .plain, target: nil, action: nil)
        navigationItem.titleView = nil

        modeButton = UIButton(type: .system)
        modeButton.frame = CGRect(x: 0, y: 0, width: 34, height: 34)
        modeButton.layer.cornerRadius = 11
        modeButton.imageView?.contentMode = .scaleAspectFit
        modeButton.addTarget(self, action: #selector(toggleMode), for: .touchUpInside)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(configureServerUrl))
        modeButton.addGestureRecognizer(longPress)
        updateModeButtonStyle()

        // 顶栏右侧：与安卓严格一致，从左到右依次为
        // 传送文件 → 来源模式(手机本地/电脑在线) → 回收站 → 设置
        // 这里用 UIStackView 显式排布，而不是 rightBarButtonItems 数组 ——
        // 后者「数组顺序 ↔ 屏幕左右顺序」的语义容易搞反，无法在本机验证 iOS 渲染，
        // 用 StackView 可以确保与安卓逐像素对齐。
        modeButton.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            modeButton.widthAnchor.constraint(equalToConstant: 34),
            modeButton.heightAnchor.constraint(equalToConstant: 34)
        ])
        let rightRow = UIStackView(arrangedSubviews: [
            toolbarButton(.plane, label: "传送文件", action: #selector(openTransfer)),
            modeButton,
            toolbarButton(.trash, label: "回收站", action: #selector(openTrash)),
            toolbarButton(.settings, label: "设置", action: #selector(openSettings))
        ])
        rightRow.axis = .horizontal
        rightRow.spacing = 8
        rightRow.alignment = .center
        rightRow.distribution = .fill

        let folderItem = toolbarItem(.folder, label: "切换到文件浏览", action: #selector(openFiles))
        self.folderItem = folderItem
        navigationItem.leftBarButtonItem = folderItem
        navigationItem.rightBarButtonItem = UIBarButtonItem(customView: rightRow)
        updateFolderItemVisibility()
    }

    /// 与安卓一致：在线相册模式下隐藏「文件浏览」入口
    private func updateFolderItemVisibility() {
        folderItem?.customView?.isHidden = isOnlineMode
    }

    private func toolbarButton(_ symbol: AlbumToolbarSymbol, label: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.backgroundColor = view.tintColor.withAlphaComponent(0.11)
        button.layer.cornerRadius = 11
        button.setImage(AlbumToolbarIcon.image(symbol, color: view.tintColor), for: .normal)
        button.imageView?.contentMode = .scaleAspectFit
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(equalToConstant: 34),
            button.heightAnchor.constraint(equalToConstant: 34)
        ])
        return button
    }

    private func updateModeButtonStyle() {
        if !isOnlineMode {
            let fg = UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)
            let bg = UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
            modeButton.backgroundColor = bg
            modeButton.setImage(AlbumToolbarIcon.image(.phone, color: fg), for: .normal)
            modeButton.accessibilityLabel = "当前为手机本地作品，点击切换到电脑在线"
        } else {
            let fg = UIColor(red: 2/255, green: 132/255, blue: 199/255, alpha: 1)
            let bg = UIColor(red: 224/255, green: 242/255, blue: 254/255, alpha: 1)
            modeButton.backgroundColor = bg
            modeButton.setImage(AlbumToolbarIcon.image(.computer, color: fg), for: .normal)
            modeButton.accessibilityLabel = "当前为电脑在线作品，点击切换到手机本地"
        }
    }

    @objc private func toggleMode() {
        isOnlineMode.toggle()
        UserDefaults.standard.set(isOnlineMode, forKey: prefOnlineModeKey)
        updateModeButtonStyle()
        updateFolderItemVisibility()
        if isOnlineMode {
            showToast("已切换到：💻 电脑在线相册")
            loadOnlineData()
        } else {
            showToast("已切换到：📱 手机本地相册")
            render()
        }
    }

    @objc private func configureServerUrl() {
        let currentUrl = OnlineGalleryClient.shared.resolveBaseUrl()
        let alert = UIAlertController(title: "电脑在线相册服务设置",
                                      message: "当前连接服务器：\n\(currentUrl)\n默认端口 45835",
                                      preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = "如: http://192.168.1.27:45835"
            tf.text = currentUrl
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "探测测试", style: .default) { [weak self] _ in
            guard let self = self else { return }
            let url = alert.textFields?.first?.text ?? ""
            OnlineGalleryClient.shared.setManualBaseUrl(url)
            OnlineGalleryClient.shared.checkConnection { ok in
                self.showToast(ok ? "✅ 连接电脑相册服务成功" : "❌ 无法连接，请确认电脑端口 45835 是否开启")
            }
        })
        alert.addAction(UIAlertAction(title: "保存并刷新", style: .default) { [weak self] _ in
            guard let self = self else { return }
            let url = alert.textFields?.first?.text ?? ""
            // 手工指定通道：不会被后续的自动发现悄悄改掉
            OnlineGalleryClient.shared.setManualBaseUrl(url)
            if self.isOnlineMode { self.loadOnlineData() }
        })
        present(alert, animated: true)
    }

    private func toolbarItem(_ symbol: AlbumToolbarSymbol, label: String, action: Selector) -> UIBarButtonItem {
        let button = UIButton(type: .system)
        button.frame = CGRect(x: 0, y: 0, width: 34, height: 34)
        button.backgroundColor = view.tintColor.withAlphaComponent(0.11)
        button.layer.cornerRadius = 11
        button.setImage(AlbumToolbarIcon.image(symbol, color: view.tintColor), for: .normal)
        button.imageView?.contentMode = .scaleAspectFit
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(equalToConstant: 34),
            button.heightAnchor.constraint(equalToConstant: 34)
        ])
        return UIBarButtonItem(customView: button)
    }

    private func configureFilterBar() {
        filterScrollView = UIScrollView()
        filterScrollView.translatesAutoresizingMaskIntoConstraints = false
        filterScrollView.showsHorizontalScrollIndicator = false
        filterScrollView.alwaysBounceHorizontal = false
        filterScrollView.backgroundColor = AppColors.secondaryBackground
        filterScrollView.layer.cornerRadius = 10
        view.addSubview(filterScrollView)

        filterStackView = UIStackView()
        filterStackView.translatesAutoresizingMaskIntoConstraints = false
        filterStackView.axis = .horizontal
        filterStackView.spacing = 4
        filterStackView.alignment = .center
        filterStackView.distribution = .fill
        filterStackView.isLayoutMarginsRelativeArrangement = true
        filterStackView.layoutMargins = UIEdgeInsets(top: 3, left: 4, bottom: 3, right: 4)
        filterScrollView.addSubview(filterStackView)

        NSLayoutConstraint.activate([
            filterScrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            filterScrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            filterScrollView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 6),
            filterScrollView.heightAnchor.constraint(equalToConstant: 36),

            filterStackView.leadingAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.leadingAnchor),
            filterStackView.trailingAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.trailingAnchor),
            filterStackView.topAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.topAnchor),
            filterStackView.bottomAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.bottomAnchor),
            filterStackView.heightAnchor.constraint(equalTo: filterScrollView.frameLayoutGuide.heightAnchor)
        ])
    }

    private final class CategoryFilterButton: UIButton {
        var folderKey: String = ""
        var displayLabel: String = ""
    }

    @objc private func filterButtonTapped(_ sender: CategoryFilterButton) {
        if isOnlineMode {
            guard selectedOnlineCategory != sender.folderKey else { return }
            selectedOnlineCategory = sender.folderKey
            for (key, btn) in filterButtons {
                applyFilterButtonStyle(btn, isSelected: key == selectedOnlineCategory)
            }
            renderOnlineUI()
        } else {
            guard selectedCategory != sender.folderKey else { return }
            selectedCategory = sender.folderKey
            for (key, btn) in filterButtons {
                applyFilterButtonStyle(btn, isSelected: key == selectedCategory)
            }
            render()
        }
    }

    private func configureCollection() {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 12
        layout.minimumLineSpacing = 12
        layout.sectionInset = UIEdgeInsets(top: 16, left: 16, bottom: 24, right: 16)
        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        collectionView.backgroundColor = AppColors.background
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(WorkCell.self, forCellWithReuseIdentifier: "WorkCell")
        let refresh = UIRefreshControl()
        refresh.addTarget(self, action: #selector(refreshPulled(_:)), for: .valueChanged)
        collectionView.refreshControl = refresh
        view.addSubview(collectionView)
        NSLayoutConstraint.activate([
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.topAnchor.constraint(equalTo: filterScrollView.bottomAnchor, constant: 8),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    private func configureEmptyView() {
        let icon = UILabel()
        icon.text = "▣"
        icon.font = .systemFont(ofSize: 52, weight: .medium)
        icon.textColor = view.tintColor
        let heading = UILabel()
        heading.text = library.supportsExternalFolderSelection ? "选择作品总文件夹" : "导入作品素材"
        heading.font = .boldSystemFont(ofSize: 22)
        heading.textAlignment = .center
        emptyDetail.numberOfLines = 0
        emptyDetail.textAlignment = .center
        emptyDetail.textColor = AppColors.secondaryText
        let button = UIButton(type: .system)
        button.setTitle(library.supportsExternalFolderSelection ? "选择文件夹" : "导入文件或 ZIP", for: .normal)
        button.titleLabel?.font = .boldSystemFont(ofSize: 17)
        button.backgroundColor = view.tintColor
        button.setTitleColor(.white, for: .normal)
        button.layer.cornerRadius = 12
        button.contentEdgeInsets = UIEdgeInsets(top: 12, left: 24, bottom: 12, right: 24)
        button.addTarget(self, action: #selector(emptyAction), for: .touchUpInside)
        emptyStack.axis = .vertical
        emptyStack.spacing = 16
        emptyStack.alignment = .center
        [icon, heading, emptyDetail, button].forEach(emptyStack.addArrangedSubview)
        emptyStack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(emptyStack)
        NSLayoutConstraint.activate([
            emptyStack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            emptyStack.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -30),
            emptyStack.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 28),
            emptyStack.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -28),
            emptyDetail.widthAnchor.constraint(lessThanOrEqualToConstant: 320)
        ])
    }

    // MARK: - 在线数据加载与渲染
    private func loadOnlineData(silent: Bool = false) {
        if !silent {
            // 可做轻量 loading 提示
        }
        OnlineGalleryClient.shared.fetchCategories { [weak self] catResult in
            guard let self = self else { return }
            if case .success(let catData) = catResult {
                self.onlineCategories = catData.categories
            }
            OnlineGalleryClient.shared.fetchWorks { [weak self] workResult in
                guard let self = self else { return }
                self.collectionView.refreshControl?.endRefreshing()
                switch workResult {
                case .success(let works):
                    self.onlineWorks = works
                    self.renderOnlineUI()
                case .failure(let err):
                    if !silent { self.showError("拉取在线相册失败：\(err.localizedDescription)\n正在自动搜索局域网内的电脑在线相册…") }
                    self.renderOnlineUI()
                    self.tryAutoDiscoverPc()
                }
            }
        }
    }

    /// 连接失败时自动在局域网搜索电脑在线相册服务。
    /// 带 15 秒冷却，可反复重试 —— 不会像旧逻辑那样一次失败就永久放弃。
    private func tryAutoDiscoverPc() {
        if autoDiscovering { return }
        if Date().timeIntervalSince(lastAutoDiscoverAt) < 15 { return }
        lastAutoDiscoverAt = Date()
        autoDiscovering = true
        LanDiscovery.shared.discover { [weak self] found in
            guard let self = self else { return }
            self.autoDiscovering = false
            guard self.isOnlineMode else { return }
            if let url = found {
                self.showToast("✅ 已自动发现电脑相册服务 \(url)")
                self.loadOnlineData(silent: false)
            } else {
                self.showToast("暂未搜索到电脑在线相册，可稍后再试")
            }
        }
    }

    private func renderOnlineUI() {
        guard isViewLoaded, isOnlineMode else { return }
        updateOnlineFilterTitles()
        collectionView.reloadData()
        let items = filteredOnlineWorks
        emptyStack.isHidden = !items.isEmpty
        collectionView.isHidden = items.isEmpty
        if items.isEmpty {
            emptyDetail.text = "电脑在线相册没有匹配作品。\n可下拉刷新或长按左上角电脑图标检查连接。"
        }
    }

    private func updateOnlineFilterTitles() {
        for subview in filterStackView.arrangedSubviews {
            filterStackView.removeArrangedSubview(subview)
            subview.removeFromSuperview()
        }
        filterButtons.removeAll()

        let activeWorks = OnlineWorkLifecycle.filterActiveOnlineWorks(works: onlineWorks)
        var counts: [String: Int] = [:]
        for w in activeWorks {
            counts[w.destination, default: 0] += 1
        }

        // 1. 全部
        let allTitle = "全部 \(activeWorks.count)"
        let allBtn = createFilterButton(key: "全部", display: "全部", fullTitle: allTitle, isSelected: selectedOnlineCategory == "全部")
        filterStackView.addArrangedSubview(allBtn)
        filterButtons["全部"] = allBtn

        // 2. 目的地下拉分类
        for cat in onlineCategories {
            let cnt = counts[cat.name] ?? cat.count
            let disp = Self.formatFolderLabel(cat.name)
            let fullTitle = "\(disp) \(cnt)"
            let isSelected = selectedOnlineCategory == cat.name
            let btn = createFilterButton(key: cat.name, display: disp, fullTitle: fullTitle, isSelected: isSelected)
            filterStackView.addArrangedSubview(btn)
            filterButtons[cat.name] = btn
        }
    }

    // MARK: - 本地模式渲染
    private func render() {
        guard isViewLoaded, !isOnlineMode else { return }
        updateFilterTitles()
        collectionView.reloadData()
        collectionView.refreshControl?.endRefreshing()
        let noFolder = library.folderName == nil
        let hasFiltered = !filteredWorks.isEmpty
        emptyStack.isHidden = hasFiltered || (!noFolder && library.scanSummary == nil)
        collectionView.isHidden = !hasFiltered
        if noFolder {
            emptyDetail.text = "只需选择一次。点击作品卡片上的平台按钮，会复制对应文案并把全部图片交给系统分享。"
        } else if selectedCategory != WorkCategory.all && !library.works.isEmpty {
            emptyDetail.text = "当前合集没有作品。切回“全部”可查看所有内容。"
        } else {
            emptyDetail.text = library.scanSummary ?? "没有找到同时包含图片和 TXT 的作品文件夹。"
        }
        if let error = library.errorMessage { showError(error) }
        if let message = library.message { showToast(message) }
    }

    static func formatFolderLabel(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == WorkCategory.all {
            return "全部"
        }
        if trimmed.count > 5 {
            let prefix = String(trimmed.prefix(5))
            return prefix + "..."
        }
        return trimmed
    }

    private func updateFilterTitles() {
        var folderCounts: [String: Int] = [:]
        var orderedFolders: [String] = []
        for work in library.works {
            let folder = work.folderName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !folder.isEmpty {
                if folderCounts[folder] == nil {
                    orderedFolders.append(folder)
                }
                folderCounts[folder, default: 0] += 1
            }
        }

        if selectedCategory != WorkCategory.all && folderCounts[selectedCategory] == nil {
            selectedCategory = WorkCategory.all
        }

        for subview in filterStackView.arrangedSubviews {
            filterStackView.removeArrangedSubview(subview)
            subview.removeFromSuperview()
        }
        filterButtons.removeAll()

        // 1. 全部
        let allButton = createFilterButton(key: WorkCategory.all, display: "全部", fullTitle: "全部 \(library.works.count)", isSelected: selectedCategory == WorkCategory.all)
        filterStackView.addArrangedSubview(allButton)
        filterButtons[WorkCategory.all] = allButton

        // 2. Dynamic folders
        for folder in orderedFolders {
            let count = folderCounts[folder] ?? 0
            let formatted = Self.formatFolderLabel(folder)
            let fullTitle = "\(formatted) \(count)"
            let isSelected = folder == selectedCategory
            let button = createFilterButton(key: folder, display: formatted, fullTitle: fullTitle, isSelected: isSelected)
            filterStackView.addArrangedSubview(button)
            filterButtons[folder] = button
        }
        filterScrollView.accessibilityLabel = "作品合集分类"
    }

    private func createFilterButton(key: String, display: String, fullTitle: String, isSelected: Bool) -> UIButton {
        let button = CategoryFilterButton(type: .system)
        button.folderKey = key
        button.displayLabel = display
        button.translatesAutoresizingMaskIntoConstraints = false
        button.setTitle(fullTitle, for: .normal)
        button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        applyFilterButtonStyle(button, isSelected: isSelected)
        button.addTarget(self, action: #selector(filterButtonTapped(_:)), for: .touchUpInside)
        return button
    }

    private func applyFilterButtonStyle(_ button: UIButton, isSelected: Bool) {
        if isSelected {
            button.backgroundColor = AppColors.background
            button.setTitleColor(AppColors.text, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 12, weight: .bold)
            button.layer.cornerRadius = 8
            button.layer.shadowColor = UIColor.black.cgColor
            button.layer.shadowOpacity = 0.08
            button.layer.shadowOffset = CGSize(width: 0, height: 1)
            button.layer.shadowRadius = 2
        } else {
            button.backgroundColor = .clear
            button.setTitleColor(AppColors.secondaryText, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 12, weight: .regular)
            button.layer.shadowOpacity = 0
        }
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return isOnlineMode ? filteredOnlineWorks.count : filteredWorks.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "WorkCell", for: indexPath) as! WorkCell
        if isOnlineMode {
            let entry = filteredOnlineWorks[indexPath.item]
            cell.configureOnline(entry)
            cell.onOnlineShare = { [weak self, weak cell] platform in
                self?.shareOnline(entry, platform: platform, source: cell)
            }
            cell.onOnlinePreview = { [weak self] index in
                self?.openOnlinePreview(entry: entry, initialIndex: index)
            }
            cell.onOnlineDelete = { [weak self] in
                self?.confirmDeleteOnline(entry)
            }
            cell.onOnlineCopyPath = { [weak self] in
                self?.copyOnlineWorkPath(entry)
            }
            return cell
        }

        let work = filteredWorks[indexPath.item]
        cell.configure(work)
        cell.onShare = { [weak self, weak cell] platform in self?.share(work, platform: platform, source: cell) }
        cell.onPreview = { [weak self] index in
            guard let self = self else { return }
            let preview = ImagePreviewController(workName: work.name, urls: work.imageURLs, initialIndex: index) { [weak self] targetURL in
                guard let self = self else { return "作品已关闭" }
                do {
                    try FileManager.default.removeItem(at: targetURL)
                    self.render()
                    return nil
                } catch {
                    return error.localizedDescription
                }
            }
            preview.modalPresentationStyle = .fullScreen
            preview.modalTransitionStyle = .crossDissolve
            self.present(preview, animated: true)
        }
        cell.onReset = { [weak self] in self?.confirmResetWork(work) }
        cell.onDelete = { [weak self] in self?.confirmMoveToTrash(work) }
        cell.onCopyPath = { [weak self] in self?.copyLocalWorkPath(work) }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if isOnlineMode {
            let entry = filteredOnlineWorks[indexPath.item]
            openOnlinePreview(entry: entry, initialIndex: 0)
        } else {
            let work = filteredWorks[indexPath.item]
            navigationController?.pushViewController(WorkDetailViewController(library: library, work: work), animated: true)
        }
    }

    // MARK: - 在线分享与流转
    private func shareOnline(_ entry: OnlineWorkEntry, platform: String, source: UIView?) {
        // 复制对应平台文案
        if !entry.copyText.isEmpty {
            let available = PlatformCopyParser.parseAvailablePlatforms(entry.copyText)
            let matching = available.first(where: { $0.platform.rawValue == platform }) ?? available.first
            let textToCopy = matching?.copyText ?? entry.copyText
            UIPasteboard.general.string = textToCopy
            showToast("已复制：\(matching?.buttonLabel ?? "文案")")
        }

        // 异步下载第一张或全部图片供分享
        guard let firstImg = entry.images.first else {
            showError("该在线作品没有图片可供分享。")
            return
        }

        OnlineGalleryClient.shared.loadImage(path: firstImg, isThumbnail: false) { [weak self] image in
            guard let self = self, let img = image else {
                self?.showError("图片加载失败，无法拉起分享")
                return
            }
            let activity = UIActivityViewController(activityItems: [img], applicationActivities: nil)
            activity.popoverPresentationController?.sourceView = source
            activity.completionWithItemsHandler = { [weak self] _, completed, _, _ in
                if completed {
                    // 1. 通知电脑端物理归档移动至 _已发送1次
                    OnlineGalleryClient.shared.recordUse(workId: entry.id, platform: platform)
                    // 2. 本地记录生命周期打标
                    OnlineWorkLifecycle.markUsed(work: entry)
                    self?.showToast("🚀 分享完成，电脑端已自动归档")
                    self?.loadOnlineData(silent: true)
                }
            }
            self.present(activity, animated: true)
        }
    }

    private func openOnlinePreview(entry: OnlineWorkEntry, initialIndex: Int) {
        guard !entry.images.isEmpty else { return }
        let vc = OnlineImagePreviewController(entry: entry, initialIndex: initialIndex)
        vc.modalPresentationStyle = .fullScreen
        vc.modalTransitionStyle = .crossDissolve
        present(vc, animated: true)
    }

    private func confirmDeleteOnline(_ entry: OnlineWorkEntry) {
        let alert = UIAlertController(title: "删除未发送作品？",
                                      message: "《\(entry.title)》\n\n手机端：移入回收站（右上角垃圾箱可随时恢复）\n电脑端：移入垃圾样本库并在元数据标记为垃圾（全渠道硬拦截）\n\n选择「备注并删除」可先填写垃圾原因备注。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "备注并删除", style: .default) { [weak self] _ in
            self?.promptRemarkThenDeleteOnline(entry)
        })
        alert.addAction(UIAlertAction(title: "删除", style: .destructive) { [weak self] _ in
            self?.performDeleteOnline(entry, remark: nil)
        })
        present(alert, animated: true)
    }

    private func promptRemarkThenDeleteOnline(_ entry: OnlineWorkEntry) {
        let alert = UIAlertController(title: "垃圾备注（随作品写入元数据）",
                                      message: "例如：文案公文味重 / 图片 AI 味浓 / 选题不合适",
                                      preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = "填写垃圾原因备注"
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "确认删除", style: .destructive) { [weak self] _ in
            let remark = alert.textFields?.first?.text ?? ""
            self?.performDeleteOnline(entry, remark: remark)
        })
        present(alert, animated: true)
    }

    private func performDeleteOnline(_ entry: OnlineWorkEntry, remark: String?) {
        // 1. 电脑端移入垃圾样本库并标记垃圾元数据
        OnlineGalleryClient.shared.deleteWork(workId: entry.id, remark: remark)
        // 2. 手机端移入本地回收站记录
        OnlineWorkLifecycle.moveToTrash(work: entry)
        let trimmed = (remark ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        showToast(trimmed.isEmpty ? "已删除：手机回收站 + 电脑垃圾样本库"
                                  : "已删除并备注：手机回收站 + 电脑垃圾样本库")
        renderOnlineUI()
    }

    private func share(_ work: WorkItem, platform: CopyPlatform, source: UIView?) {
        do {
            let controller = UIActivityViewController(activityItems: try library.prepareShare(
                work, images: work.imageURLs, platform: platform),
                                                      applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = source
            present(controller, animated: true)
        } catch {
            render()
            showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let width = floor(collectionView.bounds.width - 32)
        return CGSize(width: width, height: 172)
    }

    private func confirmResetWork(_ work: WorkItem) {
        let alert = UIAlertController(title: "重置作品状态？",
                                      message: "将清空分享计数、取消自动删除排期，并移回普通列表排序。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "重置", style: .default) { [weak self] _ in
            guard let self = self else { return }
            do {
                try self.library.resetShare(work)
                self.render()
            } catch {
                self.showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
        })
        present(alert, animated: true)
    }

    private func confirmMoveToTrash(_ work: WorkItem) {
        let alert = UIAlertController(title: "移到回收站？",
                                      message: "作品会从当前列表消失，并移动到“相册回收站”；分享次数会保留。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "移到回收站", style: .destructive) { [weak self] _ in
            guard let self = self else { return }
            do {
                try self.library.moveWorkToTrash(work)
                self.render()
            } catch {
                self.showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
        })
        present(alert, animated: true)
    }

    /// 「复制路径」（本地）：复制手机上该作品文件夹的绝对路径。
    private func copyLocalWorkPath(_ work: WorkItem) {
        copyFolderPath(work.folderURL.path, origin: "手机本地作品")
    }

    /// 「复制路径」（在线）：复制电脑成品库中该作品文件夹的绝对路径。
    private func copyOnlineWorkPath(_ entry: OnlineWorkEntry) {
        copyFolderPath(entry.path, origin: "电脑在线作品")
    }

    private func copyFolderPath(_ path: String, origin: String) {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            showToast("⚠️ 该作品没有可复制的文件夹路径")
            return
        }
        UIPasteboard.general.string = trimmed
        showToast("📋 已复制\(origin)文件夹路径")
    }

    @objc private func refreshPulled(_ sender: UIRefreshControl) {
        if isOnlineMode {
            loadOnlineData()
        } else {
            CopyParserCache.clear()
            ThumbnailLoader.shared.clearCache()
            library.refresh()
        }
    }

    @objc private func emptyAction() {
        if isOnlineMode {
            loadOnlineData()
        } else {
            library.supportsExternalFolderSelection ? presentFolderPicker() : presentImportPicker()
        }
    }

    @objc private func openTrash() {
        // 与 Android 严格对齐：在线模式下的「回收站」= 电脑端在线回收站（已使用 / 已标记垃圾），
        // 不再错进手机本地回收站；本地回收站在在线回收站页左侧「📱 本地回收站」里仍然可达。
        if isOnlineMode {
            navigationController?.pushViewController(OnlineRecycleViewController(library: library), animated: true)
        } else {
            navigationController?.pushViewController(TrashViewController(library: library), animated: true)
        }
    }

    @objc private func openSettings() {
        navigationController?.pushViewController(SettingsViewController(library: library), animated: true)
    }

    @objc private func openFiles() {
        guard let root = library.receivingRootURL else {
            showError("请先在设置中选择作品文件夹。")
            return
        }
        navigationController?.pushViewController(
            LibraryFilesViewController(rootURL: root, currentURL: root), animated: true)
    }

    private func presentFolderPicker() {
        guard #available(iOS 13.0, *) else { return }
        let picker = FolderPickerController()
        picker.onPick = { [weak self] url in self?.library.selectFolder(url) }
        present(picker, animated: true)
    }

    private func presentImportPicker() {
        let picker = ImportPickerController()
        picker.onPick = { [weak self] urls in self?.library.importItems(urls) }
        present(picker, animated: true)
    }

    private func showInitialFolderPromptIfNeeded() {
        guard !initialFolderPromptShown, library.supportsExternalFolderSelection,
              library.folderName == nil, presentedViewController == nil else { return }
        initialFolderPromptShown = true
        let alert = UIAlertController(title: "先选择作品文件夹",
                                      message: "只需设置一次。相册会递归识别里面包含图片和 TXT 的作品文件夹。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "稍后", style: .cancel))
        alert.addAction(UIAlertAction(title: "选择文件夹", style: .default) { [weak self] _ in
            self?.presentFolderPicker()
        })
        present(alert, animated: true)
    }

    private func showError(_ text: String) {
        guard presentedViewController == nil else { return }
        library.consumeError()
        let alert = UIAlertController(title: "操作没有完成", message: text, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }

    private func showToast(_ text: String) {
        library.consumeMessage()
        toastView?.removeFromSuperview()
        let label = UILabel()
        label.text = text
        label.font = .boldSystemFont(ofSize: 14)
        label.textColor = .white
        label.backgroundColor = UIColor.black.withAlphaComponent(0.82)
        label.textAlignment = .center
        label.layer.cornerRadius = 18
        label.clipsToBounds = true
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            label.heightAnchor.constraint(equalToConstant: 36),
            label.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -40),
            label.widthAnchor.constraint(greaterThanOrEqualToConstant: 120)
        ])
        toastView = label
        UIView.animate(withDuration: 0.25, delay: 2, options: [], animations: { label.alpha = 0 }) {
            _ in label.removeFromSuperview()
        }
    }
}

enum AlbumToolbarSymbol { case plane, share, refresh, trash, settings, folder, phone, computer }

enum AlbumToolbarIcon {
    static func image(_ symbol: AlbumToolbarSymbol, color: UIColor) -> UIImage {
        let size = CGSize(width: 23, height: 23)
        UIGraphicsBeginImageContextWithOptions(size, false, 0)
        defer { UIGraphicsEndImageContext() }
        color.setStroke()
        color.setFill()
        let path = UIBezierPath()
        path.lineWidth = 1.8
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        switch symbol {
        case .plane:
            path.move(to: CGPoint(x: 2.5, y: 3.5)); path.addLine(to: CGPoint(x: 21, y: 11.5))
            path.addLine(to: CGPoint(x: 2.5, y: 19.5)); path.addLine(to: CGPoint(x: 6.1, y: 11.5))
            path.close(); path.move(to: CGPoint(x: 6.1, y: 11.5)); path.addLine(to: CGPoint(x: 16.8, y: 11.5))
            path.stroke()
        case .share:
            path.move(to: CGPoint(x: 8.2, y: 10.2)); path.addLine(to: CGPoint(x: 15.4, y: 6.6))
            path.move(to: CGPoint(x: 8.2, y: 12.8)); path.addLine(to: CGPoint(x: 15.4, y: 16.4))
            path.move(to: CGPoint(x: 18.2, y: 7.9)); path.addLine(to: CGPoint(x: 18.2, y: 15.1))
            path.stroke()
            for center in [CGPoint(x: 5.2, y: 11.5), CGPoint(x: 18.2, y: 5.2), CGPoint(x: 18.2, y: 17.8)] {
                let dot = UIBezierPath(ovalIn: CGRect(x: center.x - 2.7, y: center.y - 2.7, width: 5.4, height: 5.4))
                dot.fill()
            }
        case .refresh:
            path.addArc(withCenter: CGPoint(x: 11.5, y: 11.5), radius: 7.2,
                        startAngle: -.pi * 0.10, endAngle: .pi * 0.90, clockwise: true)
            path.move(to: CGPoint(x: 4.3, y: 11.5))
            path.addArc(withCenter: CGPoint(x: 11.5, y: 11.5), radius: 7.2,
                        startAngle: .pi * 0.90, endAngle: .pi * 1.90, clockwise: true)
            path.move(to: CGPoint(x: 17.0, y: 4.6)); path.addLine(to: CGPoint(x: 18.9, y: 7.6))
            path.addLine(to: CGPoint(x: 15.4, y: 7.2))
            path.move(to: CGPoint(x: 6.0, y: 18.4)); path.addLine(to: CGPoint(x: 4.1, y: 15.4))
            path.addLine(to: CGPoint(x: 7.6, y: 15.8)); path.stroke()
        case .trash:
            path.move(to: CGPoint(x: 5.8, y: 7)); path.addLine(to: CGPoint(x: 17.2, y: 7))
            path.move(to: CGPoint(x: 8.5, y: 4.2)); path.addLine(to: CGPoint(x: 14.5, y: 4.2))
            path.move(to: CGPoint(x: 7.2, y: 7)); path.addLine(to: CGPoint(x: 8, y: 19))
            path.addLine(to: CGPoint(x: 15, y: 19)); path.addLine(to: CGPoint(x: 15.8, y: 7))
            path.move(to: CGPoint(x: 10, y: 10)); path.addLine(to: CGPoint(x: 10.3, y: 16))
            path.move(to: CGPoint(x: 13, y: 10)); path.addLine(to: CGPoint(x: 12.7, y: 16)); path.stroke()
        case .settings:
            path.addArc(withCenter: CGPoint(x: 11.5, y: 11.5), radius: 3.2,
                        startAngle: 0, endAngle: .pi * 2, clockwise: true)
            path.addArc(withCenter: CGPoint(x: 11.5, y: 11.5), radius: 6.2,
                        startAngle: 0, endAngle: .pi * 2, clockwise: true)
            for index in 0..<8 {
                let angle = CGFloat(index) * .pi / 4
                path.move(to: CGPoint(x: 11.5 + cos(angle) * 6.2, y: 11.5 + sin(angle) * 6.2))
                path.addLine(to: CGPoint(x: 11.5 + cos(angle) * 8.2, y: 11.5 + sin(angle) * 8.2))
            }
            path.stroke()
        case .folder:
            path.move(to: CGPoint(x: 2.5, y: 7.2)); path.addLine(to: CGPoint(x: 8.8, y: 7.2))
            path.addLine(to: CGPoint(x: 10.7, y: 9.2)); path.addLine(to: CGPoint(x: 20.5, y: 9.2))
            path.addLine(to: CGPoint(x: 19.2, y: 18.6)); path.addLine(to: CGPoint(x: 3.8, y: 18.6))
            path.close(); path.stroke()
        case .phone:
            let body = UIBezierPath(roundedRect: CGRect(x: 5, y: 2, width: 13, height: 19), cornerRadius: 2.8)
            body.lineWidth = 1.6
            body.stroke()
            let screen = UIBezierPath(roundedRect: CGRect(x: 6.8, y: 4.8, width: 9.4, height: 12.5), cornerRadius: 1)
            screen.lineWidth = 1.0
            screen.stroke()
            let home = UIBezierPath(ovalIn: CGRect(x: 10.6, y: 18.2, width: 1.8, height: 1.8))
            home.fill()
        case .computer:
            let monitor = UIBezierPath(roundedRect: CGRect(x: 2.5, y: 3, width: 18, height: 12.5), cornerRadius: 2)
            monitor.lineWidth = 1.6
            monitor.stroke()
            let innerScreen = UIBezierPath(rect: CGRect(x: 4.5, y: 5, width: 14, height: 8.5))
            innerScreen.lineWidth = 1.0
            innerScreen.stroke()
            let stand = UIBezierPath()
            stand.move(to: CGPoint(x: 11.5, y: 15.5))
            stand.addLine(to: CGPoint(x: 11.5, y: 18.5))
            stand.lineWidth = 1.8
            stand.stroke()
            let base = UIBezierPath(roundedRect: CGRect(x: 7.5, y: 18.5, width: 8, height: 1.8), cornerRadius: 0.9)
            base.fill()
        }
        return UIGraphicsGetImageFromCurrentImageContext() ?? UIImage()
    }
}

private final class ThumbnailLoader {
    static let shared = ThumbnailLoader()
    private let cache = NSCache<NSURL, UIImage>()
    private let queue = DispatchQueue(label: "com.zwm.album.thumbnailLoader", qos: .userInitiated, attributes: .concurrent)

    private init() {
        cache.countLimit = 400
        cache.totalCostLimit = 80 * 1024 * 1024
    }

    func loadThumbnail(at url: URL, maxPixel: CGFloat = 200, completion: @escaping (UIImage?) -> Void) {
        let key = url as NSURL
        if let cached = cache.object(forKey: key) {
            completion(cached)
            return
        }

        queue.async {
            let options = [kCGImageSourceShouldCache: false] as CFDictionary
            guard let source = CGImageSourceCreateWithURL(url as CFURL, options) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            let thumbnailOptions = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                kCGImageSourceShouldCacheImmediately: true
            ] as CFDictionary

            if let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) {
                let image = UIImage(cgImage: cgImage)
                let cost = Int(maxPixel * maxPixel * 4)
                self.cache.setObject(image, forKey: key, cost: cost)
                DispatchQueue.main.async { completion(image) }
            } else {
                DispatchQueue.main.async { completion(nil) }
            }
        }
    }

    func clearCache() {
        cache.removeAllObjects()
    }
}

private final class ThumbnailButton: UIButton {
    let imageViewWidget = UIImageView()
    var currentURL: URL?
    var currentOnlinePath: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageViewWidget.contentMode = .scaleAspectFill
        imageViewWidget.clipsToBounds = true
        imageViewWidget.backgroundColor = AppColors.sharedBackground
        imageViewWidget.translatesAutoresizingMaskIntoConstraints = false
        addSubview(imageViewWidget)
        translatesAutoresizingMaskIntoConstraints = false
        layer.cornerRadius = 10
        layer.borderWidth = 1
        layer.borderColor = AppColors.separator.cgColor
        clipsToBounds = true
        NSLayoutConstraint.activate([
            imageViewWidget.leadingAnchor.constraint(equalTo: leadingAnchor),
            imageViewWidget.trailingAnchor.constraint(equalTo: trailingAnchor),
            imageViewWidget.topAnchor.constraint(equalTo: topAnchor),
            imageViewWidget.bottomAnchor.constraint(equalTo: bottomAnchor),
            widthAnchor.constraint(equalToConstant: 64),
            heightAnchor.constraint(equalToConstant: 64)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func load(url: URL) {
        currentURL = url
        currentOnlinePath = nil
        imageViewWidget.image = nil
        ThumbnailLoader.shared.loadThumbnail(at: url, maxPixel: 200) { [weak self] image in
            guard let self = self, self.currentURL == url else { return }
            self.imageViewWidget.image = image
        }
    }

    func loadOnline(path: String) {
        currentOnlinePath = path
        currentURL = nil
        imageViewWidget.image = nil
        OnlineGalleryClient.shared.loadImage(path: path, isThumbnail: true, maxPixel: 200) { [weak self] image in
            guard let self = self, self.currentOnlinePath == path else { return }
            self.imageViewWidget.image = image
        }
    }
}

private enum CopyParserCache {
    private static var cache: [URL: [AvailableCopyPlatform]] = [:]
    private static let lock = NSLock()

    static func platforms(for url: URL) -> [AvailableCopyPlatform] {
        lock.lock()
        if let cached = cache[url] {
            lock.unlock()
            return cached
        }
        lock.unlock()

        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        let parsed = PlatformCopyParser.parseAvailablePlatforms(text)
        lock.lock()
        cache[url] = parsed
        lock.unlock()
        return parsed
    }

    static func clear() {
        lock.lock()
        cache.removeAll()
        lock.unlock()
    }
}

private final class WorkCell: UICollectionViewCell {
    private let icon = UILabel()
    private let count = UILabel()
    private let name = UILabel()
    private let previewScroll = UIScrollView()
    private let previewStack = UIStackView()
    private let detail = UILabel()
    private let xhsButton = UIButton(type: .system)
    private let xhs2Button = UIButton(type: .system)
    private let douyinButton = UIButton(type: .system)
    private let resetButton = UIButton(type: .system)
    private let deleteButton = UIButton(type: .system)
    /// 「复制路径」：紧跟在「删除」之后，复制该作品文件夹的绝对路径
    private let copyPathButton = UIButton(type: .system)
    private let platformContainer = UIStackView()
    private let platformRow1 = UIStackView()
    private let platformRow2 = UIStackView()

    var onShare: ((CopyPlatform) -> Void)?
    var onPreview: ((Int) -> Void)?
    var onReset: (() -> Void)?
    var onDelete: (() -> Void)?
    /// 本地作品：复制手机上的作品文件夹路径
    var onCopyPath: (() -> Void)?

    var onOnlineShare: ((String) -> Void)?
    var onOnlinePreview: ((Int) -> Void)?
    var onOnlineDelete: (() -> Void)?
    /// 在线作品：复制电脑成品库里的作品文件夹路径
    var onOnlineCopyPath: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 16
        contentView.layer.borderWidth = 1
        name.font = .boldSystemFont(ofSize: 14.5)
        name.numberOfLines = 1
        detail.font = .systemFont(ofSize: 11.5)
        detail.textColor = AppColors.secondaryText
        previewScroll.showsHorizontalScrollIndicator = false
        previewScroll.alwaysBounceHorizontal = false
        previewScroll.accessibilityLabel = "作品缩略图，可横向查看全部图片"
        previewStack.axis = .horizontal
        previewStack.spacing = 6
        previewStack.alignment = .center
        previewStack.translatesAutoresizingMaskIntoConstraints = false
        previewScroll.addSubview(previewStack)
        NSLayoutConstraint.activate([
            previewStack.leadingAnchor.constraint(equalTo: previewScroll.contentLayoutGuide.leadingAnchor),
            previewStack.trailingAnchor.constraint(equalTo: previewScroll.contentLayoutGuide.trailingAnchor),
            previewStack.topAnchor.constraint(equalTo: previewScroll.contentLayoutGuide.topAnchor),
            previewStack.bottomAnchor.constraint(equalTo: previewScroll.contentLayoutGuide.bottomAnchor),
            previewStack.heightAnchor.constraint(equalTo: previewScroll.frameLayoutGuide.heightAnchor)
        ])
        configurePlatformButton(xhsButton, title: "发布", platform: .xhs)
        configurePlatformButton(xhs2Button, title: "大纲方案版", platform: .xhs2)
        configurePlatformButton(douyinButton, title: "规避营销版", platform: .douyin)
        configureResetButton()
        configureDeleteButton()
        configureCopyPathButton()
        platformRow1.axis = .horizontal
        platformRow1.spacing = 6
        platformRow1.alignment = .fill
        platformRow1.distribution = .fillEqually
        platformRow2.axis = .horizontal
        platformRow2.spacing = 6
        platformRow2.alignment = .fill
        platformRow2.distribution = .fillEqually
        platformContainer.axis = .vertical
        platformContainer.spacing = 6
        platformContainer.addArrangedSubview(platformRow1)
        platformContainer.addArrangedSubview(platformRow2)
        let stack = UIStackView(arrangedSubviews: [name, previewScroll, detail, platformContainer])
        stack.axis = .vertical
        stack.spacing = 5
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
            previewScroll.heightAnchor.constraint(equalToConstant: 64),
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() {
        super.prepareForReuse()
        onShare = nil
        onPreview = nil
        onReset = nil
        onDelete = nil
        onOnlineShare = nil
        onOnlinePreview = nil
        onOnlineDelete = nil
        for view in previewStack.arrangedSubviews {
            if let tb = view as? ThumbnailButton {
                tb.currentURL = nil
                tb.currentOnlinePath = nil
                tb.imageViewWidget.image = nil
            }
        }
    }

    func configureOnline(_ entry: OnlineWorkEntry) {
        contentView.backgroundColor = AppColors.secondaryBackground
        contentView.layer.borderColor = UIColor(red: 0.15, green: 0.45, blue: 0.88, alpha: 0.25).cgColor

        name.text = "[\(entry.destination)] \(entry.title)"
        let record = OnlineWorkLifecycle.getRecord(id: entry.id)
        let usedCount = record?.useCount ?? entry.useCount
        if usedCount > 0 {
            detail.text = "💻 电脑在线 · \(entry.imageCount) 图 · 已使用 \(usedCount) 次"
            detail.textColor = UIColor(red: 0.15, green: 0.45, blue: 0.88, alpha: 1)
        } else {
            detail.text = "💻 电脑在线 · \(entry.imageCount) 图 · 未使用"
            detail.textColor = AppColors.secondaryText
        }

        renderOnlinePreviews(entry.images)
        configureOnlineButtons(entry)
    }

    private func renderOnlinePreviews(_ paths: [String]) {
        let currentViews = previewStack.arrangedSubviews.compactMap { $0 as? ThumbnailButton }
        if currentViews.count > paths.count {
            for v in currentViews[paths.count...] {
                previewStack.removeArrangedSubview(v)
                v.removeFromSuperview()
            }
        }
        for (index, path) in paths.enumerated() {
            let button: ThumbnailButton
            if index < currentViews.count {
                button = currentViews[index]
            } else {
                button = ThumbnailButton(type: .custom)
                button.addTarget(self, action: #selector(onlineThumbnailTapped(_:)), for: .touchUpInside)
                previewStack.addArrangedSubview(button)
            }
            button.tag = index
            button.loadOnline(path: path)
        }
    }

    @objc private func onlineThumbnailTapped(_ sender: ThumbnailButton) {
        onOnlinePreview?(sender.tag)
    }

    private func configureOnlineButtons(_ entry: OnlineWorkEntry) {
        platformRow1.arrangedSubviews.forEach { platformRow1.removeArrangedSubview($0); $0.removeFromSuperview() }
        platformRow2.arrangedSubviews.forEach { platformRow2.removeArrangedSubview($0); $0.removeFromSuperview() }

        let platforms = PlatformCopyParser.parseAvailablePlatforms(entry.copyText)
        let count = platforms.count

        if count <= 1 {
            let p1 = platforms.first?.buttonLabel ?? "小红书"
            xhsButton.setTitle(p1, for: .normal)
            applyPlatformStyle(xhsButton, isOptimistic: false)
            platformRow1.addArrangedSubview(xhsButton)
            platformRow1.addArrangedSubview(deleteButton)
            platformRow1.addArrangedSubview(copyPathButton)
            platformRow2.isHidden = true
        } else if count == 2 {
            xhsButton.setTitle(platforms[0].buttonLabel, for: .normal)
            xhs2Button.setTitle(platforms[1].buttonLabel, for: .normal)
            applyPlatformStyle(xhsButton, isOptimistic: false)
            applyPlatformStyle(xhs2Button, isOptimistic: false)
            platformRow1.addArrangedSubview(xhsButton)
            platformRow1.addArrangedSubview(xhs2Button)
            platformRow2.addArrangedSubview(deleteButton)
            platformRow2.addArrangedSubview(copyPathButton)
            platformRow2.isHidden = false
        } else {
            xhsButton.setTitle(platforms[0].buttonLabel, for: .normal)
            xhs2Button.setTitle(platforms[1].buttonLabel, for: .normal)
            douyinButton.setTitle(platforms[2].buttonLabel, for: .normal)
            applyPlatformStyle(xhsButton, isOptimistic: false)
            applyPlatformStyle(xhs2Button, isOptimistic: false)
            applyPlatformStyle(douyinButton, isOptimistic: false)
            platformRow1.addArrangedSubview(xhsButton)
            platformRow1.addArrangedSubview(xhs2Button)
            platformRow2.addArrangedSubview(douyinButton)
            platformRow2.addArrangedSubview(deleteButton)
            platformRow2.addArrangedSubview(copyPathButton)
            platformRow2.isHidden = false
        }

        xhsButton.removeTarget(nil, action: nil, for: .allEvents)
        xhs2Button.removeTarget(nil, action: nil, for: .allEvents)
        douyinButton.removeTarget(nil, action: nil, for: .allEvents)
        deleteButton.removeTarget(nil, action: nil, for: .allEvents)
        copyPathButton.removeTarget(nil, action: nil, for: .allEvents)

        xhsButton.addTarget(self, action: #selector(onlineXhsTapped), for: .touchUpInside)
        xhs2Button.addTarget(self, action: #selector(onlineXhs2Tapped), for: .touchUpInside)
        douyinButton.addTarget(self, action: #selector(onlineDouyinTapped), for: .touchUpInside)
        deleteButton.addTarget(self, action: #selector(onlineDeleteTapped), for: .touchUpInside)
        copyPathButton.addTarget(self, action: #selector(onlineCopyPathTapped), for: .touchUpInside)
    }

    @objc private func onlineXhsTapped() { onOnlineShare?("xhs") }
    @objc private func onlineXhs2Tapped() { onOnlineShare?("xhs2") }
    @objc private func onlineDouyinTapped() { onOnlineShare?("douyin") }
    @objc private func onlineDeleteTapped() { onOnlineDelete?() }
    @objc private func onlineCopyPathTapped() { onOnlineCopyPath?() }

    func configure(_ work: WorkItem) {
        contentView.backgroundColor = AppColors.secondaryBackground
        contentView.layer.borderColor = AppColors.separator.cgColor

        name.text = work.name
        detail.text = "\(work.imageURLs.count) 图"
        detail.textColor = AppColors.secondaryText

        renderPreviews(work.imageURLs)
        configureButtons(work)
    }

    private func renderPreviews(_ urls: [URL]) {
        let currentViews = previewStack.arrangedSubviews.compactMap { $0 as? ThumbnailButton }
        if currentViews.count > urls.count {
            for v in currentViews[urls.count...] {
                previewStack.removeArrangedSubview(v)
                v.removeFromSuperview()
            }
        }

        for (index, url) in urls.enumerated() {
            let button: ThumbnailButton
            if index < currentViews.count {
                button = currentViews[index]
            } else {
                button = ThumbnailButton(type: .custom)
                button.addTarget(self, action: #selector(thumbnailTapped(_:)), for: .touchUpInside)
                previewStack.addArrangedSubview(button)
            }
            button.tag = index
            button.load(url: url)
        }
    }

    @objc private func thumbnailTapped(_ sender: ThumbnailButton) {
        onPreview?(sender.tag)
    }

    private func configureButtons(_ work: WorkItem) {
        platformRow1.arrangedSubviews.forEach { platformRow1.removeArrangedSubview($0); $0.removeFromSuperview() }
        platformRow2.arrangedSubviews.forEach { platformRow2.removeArrangedSubview($0); $0.removeFromSuperview() }

        let platforms = CopyParserCache.platforms(for: work.textURL)
        let count = platforms.count

        if count <= 1 {
            let p1 = platforms.first?.buttonLabel ?? "发布"
            xhsButton.setTitle(p1, for: .normal)
            applyPlatformStyle(xhsButton, isOptimistic: work.shareCount > 0)
            platformRow1.addArrangedSubview(xhsButton)
            if work.shareCount > 0 { platformRow1.addArrangedSubview(resetButton) }
            platformRow1.addArrangedSubview(deleteButton)
            platformRow1.addArrangedSubview(copyPathButton)
            platformRow2.isHidden = true
        } else if count == 2 {
            xhsButton.setTitle(platforms[0].buttonLabel, for: .normal)
            xhs2Button.setTitle(platforms[1].buttonLabel, for: .normal)
            applyPlatformStyle(xhsButton, isOptimistic: work.xhsShareCount > 0)
            applyPlatformStyle(xhs2Button, isOptimistic: work.shareCount > 0 && work.xhsShareCount == 0)
            platformRow1.addArrangedSubview(xhsButton)
            platformRow1.addArrangedSubview(xhs2Button)
            if work.shareCount > 0 { platformRow2.addArrangedSubview(resetButton) }
            platformRow2.addArrangedSubview(deleteButton)
            platformRow2.addArrangedSubview(copyPathButton)
            platformRow2.isHidden = false
        } else {
            xhsButton.setTitle(platforms[0].buttonLabel, for: .normal)
            xhs2Button.setTitle(platforms[1].buttonLabel, for: .normal)
            douyinButton.setTitle(platforms[2].buttonLabel, for: .normal)
            applyPlatformStyle(xhsButton, isOptimistic: work.xhsShareCount > 0)
            applyPlatformStyle(xhs2Button, isOptimistic: false)
            applyPlatformStyle(douyinButton, isOptimistic: work.douyinShareCount > 0)
            platformRow1.addArrangedSubview(xhsButton)
            platformRow1.addArrangedSubview(xhs2Button)
            platformRow2.addArrangedSubview(douyinButton)
            if work.shareCount > 0 { platformRow2.addArrangedSubview(resetButton) }
            platformRow2.addArrangedSubview(deleteButton)
            platformRow2.addArrangedSubview(copyPathButton)
            platformRow2.isHidden = false
        }

        xhsButton.removeTarget(nil, action: nil, for: .allEvents)
        xhs2Button.removeTarget(nil, action: nil, for: .allEvents)
        douyinButton.removeTarget(nil, action: nil, for: .allEvents)
        resetButton.removeTarget(nil, action: nil, for: .allEvents)
        deleteButton.removeTarget(nil, action: nil, for: .allEvents)
        copyPathButton.removeTarget(nil, action: nil, for: .allEvents)

        xhsButton.addTarget(self, action: #selector(xhsTapped), for: .touchUpInside)
        xhs2Button.addTarget(self, action: #selector(xhs2Tapped), for: .touchUpInside)
        douyinButton.addTarget(self, action: #selector(douyinTapped), for: .touchUpInside)
        resetButton.addTarget(self, action: #selector(resetTapped), for: .touchUpInside)
        deleteButton.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
        copyPathButton.addTarget(self, action: #selector(copyPathTapped), for: .touchUpInside)
    }

    private func configurePlatformButton(_ button: UIButton, title: String, platform: CopyPlatform) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 12.5, weight: .semibold)
        button.layer.cornerRadius = 8
        button.contentEdgeInsets = UIEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.heightAnchor.constraint(equalToConstant: 28).isActive = true
    }

    private func configureResetButton() {
        resetButton.setTitle("重置", for: .normal)
        resetButton.setTitleColor(UIColor(red: 0.85, green: 0.55, blue: 0.1, alpha: 1), for: .normal)
        resetButton.backgroundColor = UIColor(red: 1, green: 0.96, blue: 0.88, alpha: 1)
        resetButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .medium)
        resetButton.layer.cornerRadius = 8
        resetButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
        resetButton.translatesAutoresizingMaskIntoConstraints = false
        resetButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
    }

    private func configureDeleteButton() {
        deleteButton.setTitle("删除", for: .normal)
        deleteButton.setTitleColor(UIColor(red: 0.8, green: 0.25, blue: 0.25, alpha: 1), for: .normal)
        deleteButton.backgroundColor = UIColor(red: 1, green: 0.92, blue: 0.92, alpha: 1)
        deleteButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .medium)
        deleteButton.layer.cornerRadius = 8
        deleteButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
        deleteButton.translatesAutoresizingMaskIntoConstraints = false
        deleteButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
    }

    /// 与 Android 的「复制路径」按钮同色系（拟态灰底灰字），紧跟「删除」之后。
    private func configureCopyPathButton() {
        copyPathButton.setTitle("复制路径", for: .normal)
        copyPathButton.setTitleColor(UIColor(red: 0.32, green: 0.36, blue: 0.34, alpha: 1), for: .normal)
        copyPathButton.backgroundColor = UIColor(red: 0.93, green: 0.94, blue: 0.93, alpha: 1)
        copyPathButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .medium)
        copyPathButton.layer.cornerRadius = 8
        copyPathButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
        copyPathButton.translatesAutoresizingMaskIntoConstraints = false
        copyPathButton.heightAnchor.constraint(equalToConstant: 28).isActive = true
        copyPathButton.accessibilityLabel = "复制作品文件夹路径"
    }

    private func applyPlatformStyle(_ button: UIButton, isOptimistic: Bool) {
        if isOptimistic {
            button.setTitleColor(AppColors.secondaryText, for: .normal)
            button.backgroundColor = AppColors.separator.withAlphaComponent(0.3)
        } else {
            button.setTitleColor(UIColor(red: 0.12, green: 0.52, blue: 0.32, alpha: 1), for: .normal)
            button.backgroundColor = UIColor(red: 0.9, green: 0.97, blue: 0.93, alpha: 1)
        }
    }

    @objc private func xhsTapped() { onShare?(.xhs) }
    @objc private func xhs2Tapped() { onShare?(.xhs2) }
    @objc private func douyinTapped() { onShare?(.douyin) }
    @objc private func resetTapped() { onReset?() }
    @objc private func deleteTapped() { onDelete?() }
    @objc private func copyPathTapped() { onCopyPath?() }
}

final class OnlineImagePreviewController: UIViewController, UIScrollViewDelegate {
    private let entry: OnlineWorkEntry
    private var currentIndex: Int
    private let scrollView = UIScrollView()
    private let counterLabel = UILabel()
    private let imageView = UIImageView()

    init(entry: OnlineWorkEntry, initialIndex: Int) {
        self.entry = entry
        self.currentIndex = initialIndex
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        scrollView.frame = view.bounds
        scrollView.delegate = self
        scrollView.maximumZoomScale = 3.0
        scrollView.minimumZoomScale = 1.0
        view.addSubview(scrollView)

        imageView.frame = scrollView.bounds
        imageView.contentMode = .scaleAspectFit
        scrollView.addSubview(imageView)

        counterLabel.textAlignment = .center
        counterLabel.textColor = .white
        counterLabel.font = .boldSystemFont(ofSize: 14)
        counterLabel.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        counterLabel.layer.cornerRadius = 14
        counterLabel.clipsToBounds = true
        counterLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(counterLabel)

        let closeBtn = UIButton(type: .system)
        closeBtn.setTitle("✕", for: .normal)
        closeBtn.setTitleColor(.white, for: .normal)
        closeBtn.titleLabel?.font = .systemFont(ofSize: 22, weight: .medium)
        closeBtn.backgroundColor = UIColor.black.withAlphaComponent(0.5)
        closeBtn.layer.cornerRadius = 18
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.addTarget(self, action: #selector(close), for: .touchUpInside)
        view.addSubview(closeBtn)

        NSLayoutConstraint.activate([
            closeBtn.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            closeBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            closeBtn.widthAnchor.constraint(equalToConstant: 36),
            closeBtn.heightAnchor.constraint(equalToConstant: 36),

            counterLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            counterLabel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
            counterLabel.widthAnchor.constraint(equalToConstant: 100),
            counterLabel.heightAnchor.constraint(equalToConstant: 28)
        ])

        let swipeLeft = UISwipeGestureRecognizer(target: self, action: #selector(nextImage))
        swipeLeft.direction = .left
        view.addGestureRecognizer(swipeLeft)

        let swipeRight = UISwipeGestureRecognizer(target: self, action: #selector(prevImage))
        swipeRight.direction = .right
        view.addGestureRecognizer(swipeRight)

        loadCurrent()
    }

    @objc private func close() {
        dismiss(animated: true)
    }

    @objc private func nextImage() {
        if currentIndex < entry.images.count - 1 {
            currentIndex += 1
            loadCurrent()
        }
    }

    @objc private func prevImage() {
        if currentIndex > 0 {
            currentIndex -= 1
            loadCurrent()
        }
    }

    private func loadCurrent() {
        guard currentIndex >= 0, currentIndex < entry.images.count else { return }
        counterLabel.text = "\(currentIndex + 1) / \(entry.images.count)"
        let path = entry.images[currentIndex]
        OnlineGalleryClient.shared.loadImage(path: path, isThumbnail: false) { [weak self] img in
            self?.imageView.image = img
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        return imageView
    }
}
