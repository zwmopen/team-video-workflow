import UIKit
import ImageIO
import QuickLook

final class WorkDetailViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout, UIGestureRecognizerDelegate {
    private let library: WorkLibrary
    private var work: WorkItem
    private var selected = Set<URL>()
    private var attachments: [URL] = []
    private var collection: UICollectionView!
    private let actions = UIStackView()
    private var toastLabel: UILabel?
    private var suppressNextSelection = false
    private let initialImageIndex: Int?
    private var hasPresentedInitialPreview = false

    init(library: WorkLibrary, work: WorkItem, initialImageIndex: Int? = nil) {
        self.library = library
        self.work = work
        self.initialImageIndex = initialImageIndex
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppColors.background
        configureNavigation()
        configureUI()
        render()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !hasPresentedInitialPreview, let index = initialImageIndex, index >= 0, index < work.imageURLs.count {
            hasPresentedInitialPreview = true
            openPreview(initialIndex: index)
        }
    }

    private func configureNavigation() {
        navigationItem.backBarButtonItem = UIBarButtonItem(title: "返回", style: .plain, target: nil, action: nil)
    }

    private func configureUI() {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 8
        layout.minimumLineSpacing = 10
        layout.sectionInset = UIEdgeInsets(top: 12, left: 16, bottom: 20, right: 16)
        
        collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collection.backgroundColor = .clear
        collection.dataSource = self
        collection.delegate = self
        collection.allowsMultipleSelection = true
        collection.register(WorkTextCardCell.self, forCellWithReuseIdentifier: "textCard")
        collection.register(WorkImageCell.self, forCellWithReuseIdentifier: "image")
        collection.register(WorkFileCell.self, forCellWithReuseIdentifier: "file")
        collection.register(WorkTrashRestoreCell.self, forCellWithReuseIdentifier: "trashRestore")
        collection.translatesAutoresizingMaskIntoConstraints = false

        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:)))
        longPress.minimumPressDuration = 0.35
        longPress.cancelsTouchesInView = false
        longPress.delegate = self
        collection.addGestureRecognizer(longPress)
        view.addSubview(collection)

        actions.axis = .horizontal
        actions.spacing = 8
        actions.distribution = .fillEqually
        actions.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(actions)

        NSLayoutConstraint.activate([
            collection.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            collection.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collection.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collection.bottomAnchor.constraint(equalTo: actions.topAnchor, constant: -8),
            actions.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            actions.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            actions.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -10),
            actions.heightAnchor.constraint(equalToConstant: 50)
        ])
    }

    private func render() {
        if let fresh = library.works.first(where: { $0.key == work.key }) { work = fresh }
        selected = Set(selected.filter { work.imageURLs.contains($0) })
        attachments = loadAttachments()
        title = "\(work.name)  ·  \(work.imageURLs.count) 图"
        collection.reloadData()
        renderActions()
    }

    private func renderActions() {
        actions.arrangedSubviews.forEach { actions.removeArrangedSubview($0); $0.removeFromSuperview() }
        if selected.isEmpty {
            // 未选图状态：呈现大号【🚀 一键直接发布】主按钮
            let publishButton = UIButton(type: .system)
            publishButton.setTitle("🚀 一键直接发布 (复制文案 + 分享全部)", for: .normal)
            publishButton.titleLabel?.font = .boldSystemFont(ofSize: 16)
            publishButton.backgroundColor = UIColor(red: 0.15, green: 0.57, blue: 0.37, alpha: 1)
            publishButton.setTitleColor(.white, for: .normal)
            publishButton.layer.cornerRadius = 16
            publishButton.addTarget(self, action: #selector(publishAllImages), for: .touchUpInside)
            actions.addArrangedSubview(publishButton)
            return
        }

        // 多选状态：呈现【移到回收站】、【🚀 直接发布 (所选 N 张)】、【传送其他设备】
        let deleteBtn = iconActionButton(.trash, label: "移到回收站",
                                         background: UIColor(red: 1, green: 0.92, blue: 0.91, alpha: 1),
                                         foreground: UIColor(red: 0.74, green: 0.22, blue: 0.2, alpha: 1),
                                         action: #selector(deleteImages))

        let publishBtn = actionButton("🚀 直接发布 (\(selected.count)张)",
                                      background: UIColor(red: 0.15, green: 0.57, blue: 0.37, alpha: 1),
                                      foreground: .white,
                                      action: #selector(publishSelectedImages))

        let sendBtn = iconActionButton(.plane, label: "传送其他设备",
                                       background: UIColor(red: 0.94, green: 0.96, blue: 0.95, alpha: 1),
                                       foreground: UIColor(red: 0.21, green: 0.34, blue: 0.28, alpha: 1),
                                       action: #selector(sendImages))

        actions.addArrangedSubview(deleteBtn)
        actions.addArrangedSubview(publishBtn)
        actions.addArrangedSubview(sendBtn)
    }

    private var trashCount: Int { library.imageTrashCount(work) }
    private var hasTrashRow: Bool { trashCount > 0 }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        var count = 1 + work.imageURLs.count + attachments.count
        if hasTrashRow { count += 1 }
        return count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        if indexPath.item == 0 {
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "textCard", for: indexPath) as! WorkTextCardCell
            cell.configure(work.textURL)
            return cell
        }
        
        let imageIndex = indexPath.item - 1
        if imageIndex < work.imageURLs.count {
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "image", for: indexPath) as! WorkImageCell
            let url = work.imageURLs[imageIndex]
            cell.configureImage(url, selected: selected.contains(url))
            return cell
        }

        let attachIndex = imageIndex - work.imageURLs.count
        if attachIndex < attachments.count {
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "file", for: indexPath) as! WorkFileCell
            cell.configureFile(attachments[attachIndex])
            return cell
        }

        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "trashRestore", for: indexPath) as! WorkTrashRestoreCell
        cell.configure(trashCount)
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if suppressNextSelection {
            suppressNextSelection = false
            return
        }
        if indexPath.item == 0 {
            copyText()
            return
        }
        
        let imageIndex = indexPath.item - 1
        if imageIndex < work.imageURLs.count {
            let url = work.imageURLs[imageIndex]
            if selected.isEmpty {
                openPreview(initialIndex: imageIndex)
            } else {
                toggle(url)
            }
            return
        }

        let attachIndex = imageIndex - work.imageURLs.count
        if attachIndex < attachments.count {
            let url = attachments[attachIndex]
            navigationController?.pushViewController(FilePreviewController(url: url), animated: true)
            return
        }

        restoreImages()
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let fullWidth = collectionView.bounds.width - 32
        if indexPath.item == 0 {
            return CGSize(width: fullWidth, height: 110)
        }
        let imageIndex = indexPath.item - 1
        if imageIndex < work.imageURLs.count {
            let halfWidth = floor((collectionView.bounds.width - 40) / 2)
            return CGSize(width: halfWidth, height: floor(halfWidth * 4 / 3))
        }
        return CGSize(width: fullWidth, height: 52)
    }

    @objc private func longPressed(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        guard let index = collection.indexPathForItem(at: gesture.location(in: collection)),
              index.item > 0, (index.item - 1) < work.imageURLs.count else { return }
        suppressNextSelection = true
        toggle(work.imageURLs[index.item - 1])
        DispatchQueue.main.async { [weak self] in
            self?.suppressNextSelection = false
        }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        true
    }

    private func openPreview(initialIndex: Int) {
        let preview = ImagePreviewController(workName: work.name, urls: work.imageURLs, initialIndex: initialIndex) { [weak self] targetURL in
            guard let self = self else { return "作品已关闭，无法删除图片。" }
            return self.deletePreviewImage(targetURL)
        }
        preview.modalPresentationStyle = .fullScreen
        preview.modalTransitionStyle = .crossDissolve
        present(preview, animated: true)
    }

    private func toggle(_ url: URL) {
        if selected.contains(url) { selected.remove(url) } else { selected.insert(url) }
        render()
    }

    private func loadAttachments() -> [URL] {
        let imageSet = Set(work.imageURLs.map { $0.standardizedFileURL })
        let text = work.textURL.standardizedFileURL
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .isHiddenKey]
        let children = (try? FileManager.default.contentsOfDirectory(at: work.folderURL,
                                                                      includingPropertiesForKeys: Array(keys),
                                                                      options: [])) ?? []
        return children.filter { url in
            let values = try? url.resourceValues(forKeys: keys)
            guard values?.isRegularFile == true, values?.isHidden != true else { return false }
            let normalized = url.standardizedFileURL
            return normalized != text && !imageSet.contains(normalized)
        }.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending }
    }

    @objc private func copyText() {
        let content = (try? String(contentsOf: work.textURL, encoding: .utf8)) ?? ""
        UIPasteboard.general.string = content
        if #available(iOS 10.0, *) {
            let feedback = UIImpactFeedbackGenerator(style: .medium)
            feedback.impactOccurred()
        }
        showToast("✅ 文案已复制 (共 \(content.count) 字)")
    }

    @objc private func publishAllImages() {
        copyText()
        if work.shareCount > 0 {
            let alert = UIAlertController(title: "该作品已发布/分享 \(work.shareCount) 次",
                                          message: "继续操作会再次记录分享。确认是要发到另一个平台或重新发布吗？",
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "取消", style: .cancel))
            alert.addAction(UIAlertAction(title: "继续发布全部", style: .default) { [weak self] _ in
                self?.executeShare(images: self?.work.imageURLs ?? [])
            })
            present(alert, animated: true)
            return
        }
        executeShare(images: work.imageURLs)
    }

    @objc private func publishSelectedImages() {
        copyText()
        let imagesToShare = Array(selected)
        if work.shareCount > 0 {
            let alert = UIAlertController(title: "该作品已发布/分享 \(work.shareCount) 次",
                                          message: "继续操作会再次记录分享。确认继续发布所选 \(imagesToShare.count) 张图片吗？",
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "取消", style: .cancel))
            alert.addAction(UIAlertAction(title: "继续发布", style: .default) { [weak self] _ in
                self?.executeShare(images: imagesToShare)
            })
            present(alert, animated: true)
            return
        }
        executeShare(images: imagesToShare)
    }

    private func executeShare(images: [URL]) {
        do {
            let items = try library.prepareShare(work, images: images)
            let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = actions
            present(controller, animated: true)
        } catch {
            showToast(error.localizedDescription)
        }
    }

    @objc private func sendImages() {
        navigationController?.pushViewController(TransferViewController(pendingURLs: Array(selected)), animated: true)
    }

    @objc private func deleteImages() {
        let alert = UIAlertController(title: "移除 \(selected.count) 张图片？",
                                      message: "图片进入本作品的图片回收站，保留 7 天。", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "移到回收站", style: .destructive) { [weak self] _ in
            guard let self = self else { return }
            do {
                _ = try self.library.moveImagesToTrash(self.work, images: Array(self.selected))
                self.selected.removeAll()
                self.render()
            } catch {
                self.showToast(error.localizedDescription)
            }
        })
        present(alert, animated: true)
    }

    private func deletePreviewImage(_ url: URL) -> String? {
        do {
            _ = try library.moveImagesToTrash(work, images: [url])
            selected.remove(url)
            render()
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    @objc private func restoreImages() {
        do {
            let count = try library.restoreAllImages(work)
            render()
            showToast("✅ 已恢复 \(count) 张图片")
        } catch {
            showToast(error.localizedDescription)
        }
    }

    private func actionButton(_ title: String, background: UIColor, foreground: UIColor, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.backgroundColor = background
        button.setTitleColor(foreground, for: .normal)
        button.titleLabel?.font = .boldSystemFont(ofSize: 15)
        button.layer.cornerRadius = 16
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    private func iconActionButton(_ icon: AlbumToolbarSymbol, label: String, background: UIColor,
                                  foreground: UIColor, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(AlbumToolbarIcon.image(icon, color: foreground), for: .normal)
        button.tintColor = foreground
        button.backgroundColor = background
        button.layer.cornerRadius = 16
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }

    private func showToast(_ text: String) {
        toastLabel?.removeFromSuperview()
        let label = UILabel()
        label.text = text
        label.font = .boldSystemFont(ofSize: 14)
        label.textColor = .white
        label.backgroundColor = UIColor.black.withAlphaComponent(0.85)
        label.textAlignment = .center
        label.layer.cornerRadius = 18
        label.clipsToBounds = true
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            label.heightAnchor.constraint(equalToConstant: 38),
            label.widthAnchor.constraint(greaterThanOrEqualToConstant: 160),
            label.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -40)
        ])
        toastLabel = label
        UIView.animate(withDuration: 0.3, delay: 1.8, options: [], animations: { label.alpha = 0 }) { _ in
            label.removeFromSuperview()
        }
    }
}

// MARK: - Custom Cells

private final class WorkTextCardCell: UICollectionViewCell {
    private let titleLabel = UILabel()
    private let hintLabel = UILabel()
    private let bodyLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 18
        contentView.clipsToBounds = true
        contentView.backgroundColor = .white
        contentView.layer.borderWidth = 1
        contentView.layer.borderColor = UIColor(red: 0.90, green: 0.93, blue: 0.91, alpha: 1).cgColor

        titleLabel.font = .boldSystemFont(ofSize: 15)
        titleLabel.textColor = UIColor(red: 0.21, green: 0.41, blue: 0.32, alpha: 1)
        titleLabel.text = "📝 文案.txt"

        hintLabel.font = .systemFont(ofSize: 12, weight: .medium)
        hintLabel.textColor = UIColor(red: 0.39, green: 0.55, blue: 0.47, alpha: 1)
        hintLabel.text = "(点按一键复制)"

        bodyLabel.font = .systemFont(ofSize: 13)
        bodyLabel.textColor = UIColor(red: 0.36, green: 0.35, blue: 0.33, alpha: 1)
        bodyLabel.numberOfLines = 3

        let headerStack = UIStackView(arrangedSubviews: [titleLabel, hintLabel])
        headerStack.axis = .horizontal
        headerStack.spacing = 6
        headerStack.alignment = .center

        let mainStack = UIStackView(arrangedSubviews: [headerStack, bodyLabel])
        mainStack.axis = .vertical
        mainStack.spacing = 6
        mainStack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(mainStack)

        NSLayoutConstraint.activate([
            mainStack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14),
            mainStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
            mainStack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 12),
            mainStack.bottomAnchor.constraint(lessThanOrEqualTo: contentView.bottomAnchor, constant: -12)
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ url: URL) {
        let content = ((try? String(contentsOf: url, encoding: .utf8)) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        bodyLabel.text = content.isEmpty ? "没有文案内容" : content
    }
}

private final class WorkImageCell: UICollectionViewCell {
    private let image = UIImageView()
    private let badge = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 18
        contentView.clipsToBounds = true
        contentView.backgroundColor = .white
        image.contentMode = .scaleAspectFill
        image.clipsToBounds = true
        image.translatesAutoresizingMaskIntoConstraints = false

        badge.text = "✓"
        badge.textAlignment = .center
        badge.textColor = .white
        badge.font = .boldSystemFont(ofSize: 16)
        badge.layer.cornerRadius = 15
        badge.clipsToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false
        badge.backgroundColor = UIColor(red: 0.15, green: 0.57, blue: 0.37, alpha: 1)

        contentView.addSubview(image)
        contentView.addSubview(badge)
        NSLayoutConstraint.activate([
            image.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            image.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            image.topAnchor.constraint(equalTo: contentView.topAnchor),
            image.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            badge.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -8),
            badge.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            badge.widthAnchor.constraint(equalToConstant: 30),
            badge.heightAnchor.constraint(equalToConstant: 30)
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configureImage(_ url: URL, selected: Bool) {
        image.image = downsampledImage(at: url, maxPixel: 480)
        badge.isHidden = !selected
        contentView.layer.borderWidth = selected ? 4 : 0
        contentView.layer.borderColor = UIColor(red: 0.15, green: 0.57, blue: 0.37, alpha: 1).cgColor
    }
}

private final class WorkFileCell: UICollectionViewCell {
    private let typeBadge = UILabel()
    private let nameLabel = UILabel()
    private let sizeLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 14
        contentView.clipsToBounds = true
        contentView.backgroundColor = .white

        typeBadge.font = .boldSystemFont(ofSize: 12)
        typeBadge.textAlignment = .center
        typeBadge.textColor = UIColor(red: 0.21, green: 0.41, blue: 0.32, alpha: 1)
        typeBadge.backgroundColor = UIColor(red: 0.90, green: 0.94, blue: 0.91, alpha: 1)
        typeBadge.layer.cornerRadius = 10
        typeBadge.clipsToBounds = true
        typeBadge.translatesAutoresizingMaskIntoConstraints = false

        nameLabel.font = .boldSystemFont(ofSize: 14)
        nameLabel.textColor = AppColors.text
        nameLabel.numberOfLines = 1

        sizeLabel.font = .systemFont(ofSize: 12)
        sizeLabel.textColor = .gray

        let infoStack = UIStackView(arrangedSubviews: [nameLabel, sizeLabel])
        infoStack.axis = .vertical
        infoStack.spacing = 2

        let rowStack = UIStackView(arrangedSubviews: [typeBadge, infoStack])
        rowStack.axis = .horizontal
        rowStack.spacing = 12
        rowStack.alignment = .center
        rowStack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(rowStack)

        NSLayoutConstraint.activate([
            typeBadge.widthAnchor.constraint(equalToConstant: 44),
            typeBadge.heightAnchor.constraint(equalToConstant: 36),
            rowStack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            rowStack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
            rowStack.centerYAnchor.constraint(equalTo: contentView.centerYAnchor)
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configureFile(_ url: URL) {
        let ext = url.pathExtension.isEmpty ? "文件" : url.pathExtension.uppercased()
        typeBadge.text = ext
        nameLabel.text = url.lastPathComponent
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        sizeLabel.text = bytes > 0 ? ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) : "点按预览"
    }
}

private final class WorkTrashRestoreCell: UICollectionViewCell {
    private let titleLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 14
        contentView.clipsToBounds = true
        contentView.backgroundColor = .white
        contentView.layer.borderWidth = 1
        contentView.layer.borderColor = UIColor(red: 0.85, green: 0.90, blue: 0.87, alpha: 1).cgColor

        titleLabel.font = .boldSystemFont(ofSize: 14)
        titleLabel.textColor = UIColor(red: 0.28, green: 0.41, blue: 0.34, alpha: 1)
        titleLabel.textAlignment = .center
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(titleLabel)

        NSLayoutConstraint.activate([
            titleLabel.centerXAnchor.constraint(equalTo: contentView.centerXAnchor),
            titleLabel.centerYAnchor.constraint(equalTo: contentView.centerYAnchor)
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(_ count: Int) {
        titleLabel.text = "♻️ 图片回收站 · \(count) 张 · 点此全部恢复"
    }
}

private final class ImagePreviewController: UIViewController, UIScrollViewDelegate {
    private let workName: String
    private let urls: [URL]
    private let initialIndex: Int
    private let onDelete: (URL) -> String?
    private let scrollView = UIScrollView()
    private let counter = UILabel()
    private let previousButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)
    private var didSetInitialOffset = false

    init(workName: String, urls: [URL], initialIndex: Int, onDelete: @escaping (URL) -> String?) {
        self.workName = workName
        self.urls = urls
        self.initialIndex = max(0, min(initialIndex, max(0, urls.count - 1)))
        self.onDelete = onDelete
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override var prefersStatusBarHidden: Bool { false }
    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        scrollView.backgroundColor = .black
        scrollView.isPagingEnabled = true
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.delegate = self
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)
        NSLayoutConstraint.activate([
            scrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])

        let longestSide = max(UIScreen.main.bounds.width, UIScreen.main.bounds.height) * UIScreen.main.scale
        for url in urls {
            let image = UIImageView(image: downsampledImage(at: url, maxPixel: max(1200, longestSide)))
            image.contentMode = .scaleAspectFit
            image.backgroundColor = .black
            image.isAccessibilityElement = true
            image.accessibilityLabel = url.lastPathComponent
            scrollView.addSubview(image)
        }

        // Top Navigation Header
        let header = UIStackView()
        header.axis = .horizontal
        header.alignment = .center
        header.spacing = 12
        header.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(header)

        let closeButton = UIButton(type: .system)
        closeButton.setTitle("✕", for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 17, weight: .bold)
        closeButton.backgroundColor = UIColor(white: 0.15, alpha: 0.75)
        closeButton.layer.cornerRadius = 21
        closeButton.clipsToBounds = true
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.widthAnchor.constraint(equalToConstant: 42).isActive = true
        closeButton.heightAnchor.constraint(equalToConstant: 42).isActive = true
        header.addArrangedSubview(closeButton)

        let titleLabel = UILabel()
        titleLabel.text = workName
        titleLabel.textColor = UIColor(white: 0.85, alpha: 1)
        titleLabel.font = .systemFont(ofSize: 13, weight: .medium)
        titleLabel.numberOfLines = 1
        titleLabel.lineBreakMode = .byTruncatingTail
        header.addArrangedSubview(titleLabel)

        // Bottom Controls with Index Counter Badge & Nav Buttons
        let footer = UIStackView()
        footer.axis = .horizontal
        footer.alignment = .center
        footer.distribution = .equalSpacing
        footer.spacing = 12
        footer.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(footer)

        previousButton.setTitle("‹ 上一张", for: .normal)
        previousButton.setTitleColor(.white, for: .normal)
        previousButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        previousButton.backgroundColor = UIColor(white: 0.18, alpha: 0.8)
        previousButton.layer.cornerRadius = 14
        previousButton.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
        previousButton.addTarget(self, action: #selector(previousTapped), for: .touchUpInside)
        footer.addArrangedSubview(previousButton)

        counter.font = .boldSystemFont(ofSize: 13.5)
        counter.textColor = .white
        counter.textAlignment = .center
        counter.backgroundColor = UIColor(white: 0.12, alpha: 0.85)
        counter.layer.cornerRadius = 16
        counter.clipsToBounds = true
        counter.translatesAutoresizingMaskIntoConstraints = false
        counter.widthAnchor.constraint(greaterThanOrEqualToConstant: 80).isActive = true
        counter.heightAnchor.constraint(equalToConstant: 32).isActive = true
        footer.addArrangedSubview(counter)

        nextButton.setTitle("下一张 ›", for: .normal)
        nextButton.setTitleColor(.white, for: .normal)
        nextButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        nextButton.backgroundColor = UIColor(red: 0.15, green: 0.57, blue: 0.37, alpha: 1)
        nextButton.layer.cornerRadius = 14
        nextButton.contentEdgeInsets = UIEdgeInsets(top: 8, left: 14, bottom: 8, right: 14)
        nextButton.addTarget(self, action: #selector(nextTapped), for: .touchUpInside)
        footer.addArrangedSubview(nextButton)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 10),
            header.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            header.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),

            footer.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            footer.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            footer.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16)
        ])

        updateCounter()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let pageWidth = scrollView.bounds.width
        let pageHeight = scrollView.bounds.height
        guard pageWidth > 0, pageHeight > 0 else { return }
        for (index, subview) in scrollView.subviews.enumerated() {
            subview.frame = CGRect(x: pageWidth * CGFloat(index), y: 0,
                                   width: pageWidth, height: pageHeight)
        }
        scrollView.contentSize = CGSize(width: pageWidth * CGFloat(urls.count), height: pageHeight)
        if !didSetInitialOffset {
            didSetInitialOffset = true
            scrollView.setContentOffset(CGPoint(x: pageWidth * CGFloat(initialIndex), y: 0), animated: false)
            updateCounter()
        }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) { updateCounter() }

    private func updateCounter() {
        guard !urls.isEmpty else { return }
        let width = max(scrollView.bounds.width, 1)
        let index = max(0, min(urls.count - 1, Int(round(scrollView.contentOffset.x / width))))
        counter.text = "\(index + 1) / \(urls.count)"
        previousButton.isEnabled = index > 0
        nextButton.isEnabled = index < urls.count - 1
        previousButton.alpha = previousButton.isEnabled ? 1.0 : 0.4
        nextButton.alpha = nextButton.isEnabled ? 1.0 : 0.4
    }

    @objc private func closeTapped() {
        dismiss(animated: true)
    }

    @objc private func previousTapped() {
        let width = max(scrollView.bounds.width, 1)
        let index = max(0, min(urls.count - 1, Int(round(scrollView.contentOffset.x / width))))
        if index > 0 {
            scrollView.setContentOffset(CGPoint(x: width * CGFloat(index - 1), y: 0), animated: true)
        }
    }

    @objc private func nextTapped() {
        let width = max(scrollView.bounds.width, 1)
        let index = max(0, min(urls.count - 1, Int(round(scrollView.contentOffset.x / width))))
        if index + 1 < urls.count {
            scrollView.setContentOffset(CGPoint(x: width * CGFloat(index + 1), y: 0), animated: true)
        }
    }
}


private final class FilePreviewController: QLPreviewController, QLPreviewControllerDataSource {
    private let url: URL
    init(url: URL) {
        self.url = url
        super.init(nibName: nil, bundle: nil)
        dataSource = self
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func numberOfPreviewItems(in controller: QLPreviewController) -> Int { return 1 }
    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        return url as NSURL
    }
}

private func downsampledImage(at url: URL, maxPixel: CGFloat) -> UIImage? {
    let options = [kCGImageSourceShouldCache: false] as CFDictionary
    guard let source = CGImageSourceCreateWithURL(url as CFURL, options) else { return nil }
    let thumbnailOptions = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxPixel,
        kCGImageSourceShouldCacheImmediately: true
    ] as CFDictionary
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) else { return nil }
    return UIImage(cgImage: image)
}
