import UIKit

final class LibraryViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private let library: WorkLibrary
    private let titleText = UILabel()
    private let badge = UILabel()
    private let emptyStack = UIStackView()
    private let emptyDetail = UILabel()
    private var collectionView: UICollectionView!
    private var toastView: UILabel?

    init(library: WorkLibrary) {
        self.library = library
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppColors.background
        configureNavigation()
        configureCollection()
        configureEmptyView()
        library.onChange = { [weak self] in self?.render() }
        render()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        render()
    }

    private func configureNavigation() {
        titleText.text = "作品"
        titleText.font = .boldSystemFont(ofSize: 17)
        badge.font = .boldSystemFont(ofSize: 12)
        badge.textColor = view.tintColor
        badge.textAlignment = .center
        badge.backgroundColor = view.tintColor.withAlphaComponent(0.12)
        badge.layer.cornerRadius = 13
        badge.clipsToBounds = true
        badge.widthAnchor.constraint(greaterThanOrEqualToConstant: 30).isActive = true
        badge.heightAnchor.constraint(equalToConstant: 26).isActive = true
        let stack = UIStackView(arrangedSubviews: [titleText, badge])
        stack.axis = .horizontal
        stack.spacing = 8
        stack.alignment = .center
        navigationItem.titleView = stack
        navigationItem.rightBarButtonItems = [
            UIBarButtonItem(title: "⚙︎", style: .plain, target: self, action: #selector(openSettings)),
            UIBarButtonItem(title: "♻︎", style: .plain, target: self, action: #selector(openTrash))
        ]
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
            collectionView.topAnchor.constraint(equalTo: view.topAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    private func configureEmptyView() {
        let icon = UILabel()
        icon.text = "▣"
        icon.font = .systemFont(ofSize: 52, weight: .medium)
        icon.textColor = view.tintColor
        let heading = UILabel()
        heading.text = library.supportsExternalFolderSelection ? "选择作品总文件夹" : "把作品放入相册文件夹"
        heading.font = .boldSystemFont(ofSize: 22)
        heading.textAlignment = .center
        emptyDetail.numberOfLines = 0
        emptyDetail.textAlignment = .center
        emptyDetail.textColor = AppColors.secondaryText
        let button = UIButton(type: .system)
        button.setTitle(library.supportsExternalFolderSelection ? "选择文件夹" : "刷新", for: .normal)
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
        badge.text = " \(library.works.count) "
        collectionView.reloadData()
        collectionView.refreshControl?.endRefreshing()
        let noFolder = library.folderName == nil
        emptyStack.isHidden = !library.works.isEmpty || (!noFolder && library.scanSummary == nil)
        collectionView.isHidden = library.works.isEmpty
        if noFolder {
            emptyDetail.text = "只需选择一次。点击作品后会复制 TXT 文案，并把全部图片交给系统分享。"
        } else {
            emptyDetail.text = library.scanSummary ?? "没有找到同时包含图片和 TXT 的作品文件夹。"
        }
        if let error = library.errorMessage { showError(error) }
        if let message = library.message { showToast(message) }
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return library.works.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "WorkCell", for: indexPath) as! WorkCell
        cell.configure(library.works[indexPath.item])
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let work = library.works[indexPath.item]
        do {
            let controller = UIActivityViewController(activityItems: try library.prepareShare(work),
                                                      applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = collectionView.cellForItem(at: indexPath)
            present(controller, animated: true)
        } catch { showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription) }
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let width = floor((collectionView.bounds.width - 44) / 2)
        return CGSize(width: width, height: 132)
    }

    @objc private func refreshPulled(_ sender: UIRefreshControl) { library.refresh() }
    @objc private func emptyAction() {
        library.supportsExternalFolderSelection ? presentFolderPicker() : library.refresh()
    }
    @objc private func openTrash() {
        navigationController?.pushViewController(TrashViewController(library: library), animated: true)
    }
    @objc private func openSettings() {
        navigationController?.pushViewController(SettingsViewController(library: library), animated: true)
    }

    private func presentFolderPicker() {
        guard #available(iOS 13.0, *) else { return }
        let picker = FolderPickerController()
        picker.onPick = { [weak self] url in self?.library.selectFolder(url) }
        present(picker, animated: true)
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

private final class WorkCell: UICollectionViewCell {
    private let icon = UILabel()
    private let count = UILabel()
    private let name = UILabel()
    private let detail = UILabel()

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
        let stack = UIStackView(arrangedSubviews: [top, name, detail])
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
    }
}
