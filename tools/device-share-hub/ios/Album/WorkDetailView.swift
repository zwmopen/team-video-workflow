import UIKit
import ImageIO
import QuickLook

final class WorkDetailViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    private let library: WorkLibrary
    private var work: WorkItem
    private var selected = Set<URL>()
    private var attachments: [URL] = []
    private var collection: UICollectionView!
    private let actions = UIStackView()

    init(library: WorkLibrary, work: WorkItem) {
        self.library = library; self.work = work
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppColors.background
        configureUI(); render()
    }

    private func configureUI() {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 8; layout.minimumLineSpacing = 8
        layout.sectionInset = UIEdgeInsets(top: 12, left: 16, bottom: 18, right: 16)
        collection = UICollectionView(frame: .zero, collectionViewLayout: layout)
        collection.backgroundColor = .clear; collection.dataSource = self; collection.delegate = self
        collection.register(WorkImageCell.self, forCellWithReuseIdentifier: "image")
        collection.translatesAutoresizingMaskIntoConstraints = false
        collection.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:))))
        view.addSubview(collection)

        actions.axis = .horizontal; actions.spacing = 8; actions.distribution = .fillEqually
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
            actions.heightAnchor.constraint(equalToConstant: 48)
        ])
    }

    private func render() {
        if let fresh = library.works.first(where: { $0.key == work.key }) { work = fresh }
        selected = Set(selected.filter { work.imageURLs.contains($0) })
        attachments = loadAttachments()
        title = "\(work.name)  ·  \(work.imageURLs.count)"
        collection.reloadData(); renderActions()
    }

    private func renderActions() {
        actions.arrangedSubviews.forEach { actions.removeArrangedSubview($0); $0.removeFromSuperview() }
        if selected.isEmpty {
            let copy = actionButton("复制文案", background: .white, foreground: view.tintColor, action: #selector(copyText))
            let restoreCount = library.imageTrashCount(work)
            if restoreCount > 0 {
                let restore = actionButton("恢复图片 \(restoreCount)", background: .white,
                                           foreground: view.tintColor, action: #selector(restoreImages))
                actions.addArrangedSubview(restore)
            }
            actions.addArrangedSubview(copy)
            return
        }
        actions.addArrangedSubview(iconActionButton(.trash, label: "移到回收站",
                                                     background: UIColor(red: 1, green: 0.92, blue: 0.91, alpha: 1),
                                                     foreground: UIColor(red: 0.74, green: 0.22, blue: 0.2, alpha: 1),
                                                     action: #selector(deleteImages)))
        actions.addArrangedSubview(iconActionButton(.share, label: "分享到其他应用", background: .white,
                                                     foreground: AppColors.text, action: #selector(shareImages)))
        actions.addArrangedSubview(iconActionButton(.plane, label: "传送到其他设备", background: view.tintColor,
                                                     foreground: .white, action: #selector(sendImages)))
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return work.imageURLs.count + attachments.count + 1
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "image", for: indexPath) as! WorkImageCell
        if indexPath.item == 0 { cell.configureText(work.textURL) }
        else if indexPath.item <= work.imageURLs.count {
            let url = work.imageURLs[indexPath.item - 1]
            cell.configureImage(url, selected: selected.contains(url))
        } else {
            cell.configureFile(attachments[indexPath.item - work.imageURLs.count - 1])
        }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if indexPath.item == 0 { copyText(); return }
        if indexPath.item <= work.imageURLs.count {
            let url = work.imageURLs[indexPath.item - 1]
            if selected.isEmpty {
                navigationController?.pushViewController(
                    ImagePreviewController(urls: work.imageURLs, initialIndex: indexPath.item - 1),
                    animated: true)
            }
            else { toggle(url) }
        } else if selected.isEmpty {
            let url = attachments[indexPath.item - work.imageURLs.count - 1]
            navigationController?.pushViewController(FilePreviewController(url: url), animated: true)
        }
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let width = floor((collectionView.bounds.width - 40) / 2)
        if indexPath.item == 0 { return CGSize(width: width, height: 128) }
        if indexPath.item <= work.imageURLs.count { return CGSize(width: width, height: floor(width * 4 / 3)) }
        return CGSize(width: width, height: 128)
    }

    @objc private func longPressed(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, let index = collection.indexPathForItem(at: gesture.location(in: collection)),
              index.item > 0, index.item <= work.imageURLs.count else { return }
        toggle(work.imageURLs[index.item - 1])
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
        UIPasteboard.general.string = (try? String(contentsOf: work.textURL, encoding: .utf8)) ?? ""
        showToast("复制成功")
    }
    @objc private func shareImages() {
        do {
            let controller = UIActivityViewController(activityItems: try library.prepareShare(work, images: Array(selected)),
                                                      applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = view
            present(controller, animated: true)
        } catch { showToast(error.localizedDescription) }
    }
    @objc private func sendImages() {
        navigationController?.pushViewController(TransferViewController(pendingURLs: Array(selected)), animated: true)
    }
    @objc private func deleteImages() {
        let alert = UIAlertController(title: "移除 \(selected.count) 张图片？",
                                      message: "图片进入本作品的图片回收站，保留 7 天。", preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "移到回收站", style: .destructive) { _ in
            do { _ = try self.library.moveImagesToTrash(self.work, images: Array(self.selected)); self.selected.removeAll(); self.render() }
            catch { self.showToast(error.localizedDescription) }
        })
        present(alert, animated: true)
    }
    @objc private func restoreImages() {
        do { let count = try library.restoreAllImages(work); render(); showToast("已恢复 \(count) 张") }
        catch { showToast(error.localizedDescription) }
    }

    private func actionButton(_ title: String, background: UIColor, foreground: UIColor, action: Selector) -> UIButton {
        let button = UIButton(type: .system); button.setTitle(title, for: .normal)
        button.backgroundColor = background; button.setTitleColor(foreground, for: .normal)
        button.titleLabel?.font = .boldSystemFont(ofSize: 15); button.layer.cornerRadius = 15
        button.addTarget(self, action: action, for: .touchUpInside); return button
    }
    private func iconActionButton(_ icon: AlbumToolbarSymbol, label: String, background: UIColor,
                                  foreground: UIColor, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setImage(AlbumToolbarIcon.image(icon, color: foreground), for: .normal)
        button.tintColor = foreground; button.backgroundColor = background
        button.layer.cornerRadius = 15; button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
        return button
    }
    private func showToast(_ text: String) {
        let alert = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        present(alert, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { alert.dismiss(animated: true) }
    }
}

private final class WorkImageCell: UICollectionViewCell {
    private let image = UIImageView(); private let label = UILabel(); private let badge = UILabel()
    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 18; contentView.clipsToBounds = true; contentView.backgroundColor = .white
        image.contentMode = .scaleAspectFill; image.clipsToBounds = true; image.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 4; label.font = .systemFont(ofSize: 13); label.translatesAutoresizingMaskIntoConstraints = false
        badge.text = "✓"; badge.textAlignment = .center; badge.textColor = .white
        badge.layer.cornerRadius = 14; badge.clipsToBounds = true; badge.translatesAutoresizingMaskIntoConstraints = false
        badge.backgroundColor = UIColor(red: 0.15, green: 0.57, blue: 0.37, alpha: 1)
        contentView.addSubview(image); contentView.addSubview(label); contentView.addSubview(badge)
        NSLayoutConstraint.activate([
            image.leadingAnchor.constraint(equalTo: contentView.leadingAnchor), image.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            image.topAnchor.constraint(equalTo: contentView.topAnchor), image.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),
            label.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 14), label.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -14),
            label.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            badge.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -8), badge.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 8),
            badge.widthAnchor.constraint(equalToConstant: 28), badge.heightAnchor.constraint(equalToConstant: 28)
        ])
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func configureText(_ url: URL) {
        image.image = nil; image.isHidden = true; label.isHidden = false; badge.isHidden = true
        contentView.layer.borderWidth = 0
        label.text = "\(url.lastPathComponent)\n\n" + ((try? String(contentsOf: url, encoding: .utf8)) ?? "没有文案")
    }
    func configureImage(_ url: URL, selected: Bool) {
        label.isHidden = true; image.isHidden = false
        image.image = downsampledImage(at: url, maxPixel: 420)
        badge.isHidden = !selected
        contentView.layer.borderWidth = selected ? 4 : 0
        contentView.layer.borderColor = UIColor(red: 0.15, green: 0.57, blue: 0.37, alpha: 1).cgColor
    }
    func configureFile(_ url: URL) {
        image.image = nil; image.isHidden = true; label.isHidden = false; badge.isHidden = true
        contentView.layer.borderWidth = 0
        let ext = url.pathExtension.isEmpty ? "文件" : url.pathExtension.uppercased()
        let bytes = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        let size = bytes > 0 ? ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) : "点按预览"
        label.text = "\(ext)\n\(url.lastPathComponent)\n\(size)"
    }
}

private final class ImagePreviewController: UIViewController, UIScrollViewDelegate {
    private let urls: [URL]
    private let initialIndex: Int
    private let scrollView = UIScrollView()
    private let counter = UILabel()
    private var didSetInitialOffset = false

    init(urls: [URL], initialIndex: Int) {
        self.urls = urls
        self.initialIndex = max(0, min(initialIndex, max(0, urls.count - 1)))
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        scrollView.backgroundColor = .black
        scrollView.isPagingEnabled = true
        scrollView.showsHorizontalScrollIndicator = false
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
        counter.font = .boldSystemFont(ofSize: 13)
        counter.textColor = .white
        counter.textAlignment = .center
        counter.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        counter.layer.cornerRadius = 14
        counter.clipsToBounds = true
        counter.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(counter)
        NSLayoutConstraint.activate([
            counter.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            counter.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            counter.widthAnchor.constraint(greaterThanOrEqualToConstant: 68),
            counter.heightAnchor.constraint(equalToConstant: 28)
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
        counter.text = "(index + 1) / (urls.count)"
        navigationItem.title = "预览"
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
