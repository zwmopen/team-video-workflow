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
        navigationItem.titleView = nil
        navigationItem.leftBarButtonItem = toolbarItem(
            .folder, label: "切换到文件浏览", action: #selector(openFiles))
        navigationItem.rightBarButtonItems = [
            toolbarItem(.settings, label: "设置", action: #selector(openSettings)),
            toolbarItem(.trash, label: "回收站", action: #selector(openTrash)),
            toolbarItem(.refresh, label: "刷新作品", action: #selector(refreshTapped)),
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
            emptyDetail.text = "只需选择一次。点击作品后会复制 TXT 文案，并把全部图片交给系统分享。"
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
            case WorkCategory.conversion: compactLabel = "转化"
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
        cell.onShare = { [weak self, weak cell] in self?.share(work, source: cell) }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let work = filteredWorks[indexPath.item]
        navigationController?.pushViewController(WorkDetailViewController(library: library, work: work), animated: true)
    }

    private func share(_ work: WorkItem, source: UIView?) {
        do {
            let controller = UIActivityViewController(activityItems: try library.prepareShare(work),
                                                      applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = source
            present(controller, animated: true)
        } catch { showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription) }
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let width = floor((collectionView.bounds.width - 44) / 2)
        return CGSize(width: width, height: 176)
    }

    @objc private func refreshPulled(_ sender: UIRefreshControl) { library.refresh() }
    @objc private func refreshTapped() { library.refresh() }
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
    private let detail = UILabel()
    private let shareButton = UIButton(type: .system)
    var onShare: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 18
        contentView.layer.borderWidth = 1
        icon.font = .systemFont(ofSize: 22)
        count.font = .systemFont(ofSize: 12, weight: .semibold)
        count.textAlignment = .right
        name.font = .boldSystemFont(ofSize: 16)
        name.numberOfLines = 2
        detail.font = .systemFont(ofSize: 12)
        detail.textColor = AppColors.secondaryText
        let top = UIStackView(arrangedSubviews: [icon, count])
        top.axis = .horizontal
        shareButton.setTitle("复制并分享", for: .normal)
        shareButton.titleLabel?.font = .boldSystemFont(ofSize: 14)
        shareButton.backgroundColor = tintColor
        shareButton.setTitleColor(.white, for: .normal)
        shareButton.layer.cornerRadius = 12
        shareButton.heightAnchor.constraint(equalToConstant: 40).isActive = true
        shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [top, name, detail, shareButton])
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 14),
            stack.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -12)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() { super.prepareForReuse(); onShare = nil }

    @objc private func shareTapped() { onShare?() }

    func configure(_ work: WorkItem) {
        let shared = work.shareCount > 0
        icon.text = shared ? "✓" : "▣"
        icon.textColor = shared ? AppColors.secondaryText : tintColor
        count.text = shared ? "×\(work.shareCount)  ·  \(work.imageURLs.count) 图" : "\(work.imageURLs.count) 图"
        name.text = work.name
        detail.text = shared ? "已打开分享 \(work.shareCount) 次" : "点一下复制并分享"
        contentView.backgroundColor = shared ? AppColors.sharedBackground : AppColors.secondaryBackground
        contentView.layer.borderColor = (shared ? AppColors.separator : tintColor.withAlphaComponent(0.22)).cgColor
        name.textColor = shared ? AppColors.secondaryText : AppColors.text
        detail.text = shared ? "已打开分享 \(work.shareCount) 次" : "点白色卡片查看内容"
        shareButton.setTitle(shared ? "再次分享" : "复制并分享", for: .normal)
    }
}
