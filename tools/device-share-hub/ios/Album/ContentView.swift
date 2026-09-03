import UIKit

final class LibraryViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private let library: WorkLibrary
    private let emptyStack = UIStackView()
    private let emptyDetail = UILabel()
    private var collectionView: UICollectionView!
    private var toastView: UILabel?
    private var initialFolderPromptShown = false
    private var selectedCategory = WorkCategory.all
    private var filterBar: UISegmentedControl!

    private var filteredWorks: [WorkItem] {
        guard selectedCategory != WorkCategory.all else { return library.works }
        return library.works.filter { $0.category == selectedCategory }
    }

    init(library: WorkLibrary) {
        self.library = library
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppColors.background
        configureNavigation()
        configureFilterBar()
        configureCollection()
        configureEmptyView()
        library.onChange = { [weak self] in self?.render() }
        render()
    }

    @objc private func openTransfer() {
        navigationController?.pushViewController(TransferViewController(), animated: true)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        render()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        showInitialFolderPromptIfNeeded()
    }

    private func configureNavigation() {
        // 首页没有文字标题时，系统会把二级页面的返回按钮显示成默认的 “Back”。
        // 明确指定中文，确保设置页等页面始终显示“返回”。
        navigationItem.backBarButtonItem = UIBarButtonItem(title: "返回", style: .plain, target: nil, action: nil)
        navigationItem.titleView = nil
        navigationItem.leftBarButtonItem = toolbarItem(
            .folder, label: "切换到文件浏览", action: #selector(openFiles))
        navigationItem.rightBarButtonItems = [
            toolbarItem(.settings, label: "设置", action: #selector(openSettings)),
            toolbarItem(.trash, label: "回收站", action: #selector(openTrash)),
            toolbarItem(.plane, label: "传送文件", action: #selector(openTransfer))
        ]
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
        filterBar = UISegmentedControl(items: WorkCategory.filters.map { $0.label })
        filterBar.selectedSegmentIndex = 0
        filterBar.translatesAutoresizingMaskIntoConstraints = false
        filterBar.backgroundColor = AppColors.secondaryBackground
        filterBar.layer.cornerRadius = 10
        filterBar.setTitleTextAttributes([.font: UIFont.systemFont(ofSize: 12, weight: .semibold)],
                                         for: .normal)
        if #available(iOS 13.0, *) { filterBar.selectedSegmentTintColor = .white }
        else { filterBar.tintColor = view.tintColor }
        filterBar.addTarget(self, action: #selector(filterChanged(_:)), for: .valueChanged)
        view.addSubview(filterBar)
        NSLayoutConstraint.activate([
            filterBar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            filterBar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            filterBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 6),
            filterBar.heightAnchor.constraint(equalToConstant: 36)
        ])
    }

    @objc private func filterChanged(_ sender: UISegmentedControl) {
        let index = sender.selectedSegmentIndex
        selectedCategory = index >= 0 && index < WorkCategory.filters.count
            ? WorkCategory.filters[index].id : WorkCategory.all
        render()
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
            collectionView.topAnchor.constraint(equalTo: filterBar.bottomAnchor, constant: 8),
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

    private func render() {
        guard isViewLoaded else { return }
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
            emptyDetail.text = "当前分类没有作品。切回“全部”可查看所有内容。"
        } else {
            emptyDetail.text = library.scanSummary ?? "没有找到同时包含图片和 TXT 的作品文件夹。"
        }
        if let error = library.errorMessage { showError(error) }
        if let message = library.message { showToast(message) }
    }

    private func updateFilterTitles() {
        for (index, filter) in WorkCategory.filters.enumerated() {
            let count = filter.id == WorkCategory.all
                ? library.works.count
                : library.works.filter { $0.category == filter.id }.count
            let compactLabel: String
            switch filter.id {
            case WorkCategory.conversion: compactLabel = "精准流量"
            case WorkCategory.traffic: compactLabel = "泛流量"
            default: compactLabel = filter.label
            }
            filterBar.setTitle("\(compactLabel) \(count)", forSegmentAt: index)
        }
        filterBar.accessibilityLabel = "作品分类"
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return filteredWorks.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "WorkCell", for: indexPath) as! WorkCell
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
        cell.onDelete = { [weak self] in self?.confirmMoveToTrash(work) }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let work = filteredWorks[indexPath.item]
        navigationController?.pushViewController(WorkDetailViewController(library: library, work: work), animated: true)
    }

    private func share(_ work: WorkItem, platform: CopyPlatform, source: UIView?) {
        do {
            let controller = UIActivityViewController(activityItems: try library.prepareShare(
                work, images: work.imageURLs, platform: platform),
                                                      applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = source
            present(controller, animated: true)
        } catch {
            // The cell applies an optimistic gray state on tap. Re-render if preparing
            // the share failed so the button reflects the persisted count again.
            render()
            showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let width = floor(collectionView.bounds.width - 32)
        return CGSize(width: width, height: 172)
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

    @objc private func refreshPulled(_ sender: UIRefreshControl) { library.refresh() }
    @objc private func emptyAction() {
        library.supportsExternalFolderSelection ? presentFolderPicker() : presentImportPicker()
    }
    @objc private func openTrash() {
        navigationController?.pushViewController(TrashViewController(library: library), animated: true)
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

enum AlbumToolbarSymbol { case plane, share, refresh, trash, settings, folder }

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
        }
        return UIGraphicsGetImageFromCurrentImageContext() ?? UIImage()
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
    private let deleteButton = UIButton(type: .system)
    private let platformRow = UIStackView()
    var onShare: ((CopyPlatform) -> Void)?
    var onPreview: ((Int) -> Void)?
    var onDelete: (() -> Void)?

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
        configurePlatformButton(douyinButton, title: "发抖音", platform: .douyin)
        configureDeleteButton()
        platformRow.axis = .horizontal
        platformRow.spacing = 6
        platformRow.alignment = .fill
        platformRow.distribution = .fillEqually
        let stack = UIStackView(arrangedSubviews: [name, previewScroll, detail, platformRow])
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

    override func prepareForReuse() { super.prepareForReuse(); onShare = nil; onPreview = nil; onDelete = nil }

    private func renderPreviews(_ urls: [URL]) {
        previewStack.arrangedSubviews.forEach { view in
            previewStack.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        for (index, url) in urls.enumerated() {
            let thumbnail = UIButton(type: .custom)
            let imageView = UIImageView(image: UIImage(contentsOfFile: url.path))
            imageView.contentMode = .scaleAspectFill
            imageView.clipsToBounds = true
            imageView.backgroundColor = AppColors.sharedBackground
            imageView.translatesAutoresizingMaskIntoConstraints = false
            thumbnail.addSubview(imageView)
            thumbnail.translatesAutoresizingMaskIntoConstraints = false
            thumbnail.layer.cornerRadius = 10
            thumbnail.layer.borderWidth = 1
            thumbnail.layer.borderColor = AppColors.separator.cgColor
            thumbnail.clipsToBounds = true
            thumbnail.accessibilityLabel = "预览第 \(index + 1) 张图片"
            thumbnail.tag = index
            thumbnail.addTarget(self, action: #selector(thumbnailTapped(_:)), for: .touchUpInside)
            NSLayoutConstraint.activate([
                imageView.leadingAnchor.constraint(equalTo: thumbnail.leadingAnchor),
                imageView.trailingAnchor.constraint(equalTo: thumbnail.trailingAnchor),
                imageView.topAnchor.constraint(equalTo: thumbnail.topAnchor),
                imageView.bottomAnchor.constraint(equalTo: thumbnail.bottomAnchor),
                thumbnail.widthAnchor.constraint(equalToConstant: 64),
                thumbnail.heightAnchor.constraint(equalToConstant: 64)
            ])
            previewStack.addArrangedSubview(thumbnail)
        }
    }

    private func configurePlatformButton(_ button: UIButton, title: String, platform: CopyPlatform) {
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .boldSystemFont(ofSize: 11)
        button.titleLabel?.adjustsFontSizeToFitWidth = true
        button.titleLabel?.minimumScaleFactor = 0.75
        button.layer.cornerRadius = 10
        button.contentEdgeInsets = UIEdgeInsets(top: 0, left: 6, bottom: 0, right: 6)
        button.heightAnchor.constraint(equalToConstant: 38).isActive = true
        button.accessibilityLabel = platform.shortLabel
        applyPlatformButtonState(button, clicked: false)
        let action: Selector
        switch platform {
        case .xhs: action = #selector(xhsTapped)
        case .xhs2: action = #selector(xhs2Tapped)
        case .douyin: action = #selector(douyinTapped)
        }
        button.addTarget(self, action: action, for: .touchUpInside)
    }

    private func configureDeleteButton() {
        deleteButton.setTitle("删除", for: .normal)
        deleteButton.titleLabel?.font = .boldSystemFont(ofSize: 11)
        deleteButton.setTitleColor(UIColor(red: 0.74, green: 0.22, blue: 0.20, alpha: 1), for: .normal)
        deleteButton.backgroundColor = .white
        deleteButton.layer.cornerRadius = 10
        deleteButton.layer.borderWidth = 1
        deleteButton.layer.borderColor = UIColor(red: 0.89, green: 0.67, blue: 0.64, alpha: 1).cgColor
        deleteButton.contentEdgeInsets = UIEdgeInsets(top: 0, left: 6, bottom: 0, right: 6)
        deleteButton.heightAnchor.constraint(equalToConstant: 38).isActive = true
        deleteButton.accessibilityLabel = "删除作品，移到回收站"
        deleteButton.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
    }

    private func applyPlatformButtonState(_ button: UIButton, clicked: Bool) {
        button.backgroundColor = clicked ? AppColors.sharedBackground : tintColor
        button.setTitleColor(clicked ? AppColors.secondaryText : .white, for: .normal)
        button.layer.borderWidth = clicked ? 1 : 0
        button.layer.borderColor = clicked ? AppColors.separator.cgColor : UIColor.clear.cgColor
    }

    private func markPlatformButtonClicked(_ platform: CopyPlatform) {
        switch platform {
        case .xhs: applyPlatformButtonState(xhsButton, clicked: true)
        case .xhs2: applyPlatformButtonState(xhs2Button, clicked: true)
        case .douyin: applyPlatformButtonState(douyinButton, clicked: true)
        }
    }

    @objc private func thumbnailTapped(_ sender: UIButton) { onPreview?(sender.tag) }
    @objc private func xhsTapped() {
        markPlatformButtonClicked(.xhs)
        onShare?(.xhs)
    }
    @objc private func xhs2Tapped() {
        markPlatformButtonClicked(.xhs2)
        onShare?(.xhs2)
    }
    @objc private func douyinTapped() {
        markPlatformButtonClicked(.douyin)
        onShare?(.douyin)
    }
    @objc private func deleteTapped() { onDelete?() }

    private func updatePlatformRow(_ work: WorkItem) {
        platformRow.arrangedSubviews.forEach { view in
            platformRow.removeArrangedSubview(view)
            view.removeFromSuperview()
        }
        let text = (try? String(contentsOf: work.textURL, encoding: .utf8)) ?? ""
        let available = PlatformCopyParser.parseAvailablePlatforms(text)
        for item in available {
            switch item.platform {
            case .douyin:
                douyinButton.setTitle(item.buttonLabel, for: .normal)
                applyPlatformButtonState(douyinButton, clicked: work.douyinShareCount > 0)
                douyinButton.accessibilityValue = "已点击 \(work.douyinShareCount) 次"
                platformRow.addArrangedSubview(douyinButton)
            case .xhs:
                xhsButton.setTitle(item.buttonLabel, for: .normal)
                applyPlatformButtonState(xhsButton, clicked: work.xhsShareCount > 0)
                xhsButton.accessibilityValue = "已点击 \(work.xhsShareCount) 次"
                platformRow.addArrangedSubview(xhsButton)
            case .xhs2:
                xhs2Button.setTitle(item.buttonLabel, for: .normal)
                applyPlatformButtonState(xhs2Button, clicked: work.xhsShareCount > 0)
                xhs2Button.accessibilityValue = "已点击 \(work.xhsShareCount) 次"
                platformRow.addArrangedSubview(xhs2Button)
            }
        }
        platformRow.addArrangedSubview(deleteButton)
    }

    func configure(_ work: WorkItem) {
        let shared = work.used
        icon.text = shared ? "✓" : "▣"
        icon.textColor = shared ? AppColors.secondaryText : tintColor
        count.text = shared ? "×\(work.shareCount)  ·  \(work.imageURLs.count) 图" : "\(work.imageURLs.count) 图"
        name.text = work.name
        detail.text = shared ? "小红书 \(work.xhsShareCount) · 抖音 \(work.douyinShareCount)" : "选择平台后复制文案并分享图片"
        contentView.backgroundColor = shared ? AppColors.sharedBackground : AppColors.secondaryBackground
        contentView.layer.borderColor = (shared ? AppColors.separator : tintColor.withAlphaComponent(0.22)).cgColor
        name.textColor = shared ? AppColors.secondaryText : AppColors.text
        renderPreviews(work.imageURLs)
        detail.text = shared ? "首次使用后按清理设置自动回收" : "点白色卡片查看内容"
        updatePlatformRow(work)
    }
}
