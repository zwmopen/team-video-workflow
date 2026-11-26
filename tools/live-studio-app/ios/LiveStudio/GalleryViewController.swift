import Photos
import UIKit

final class GalleryViewController: UIViewController,
    UICollectionViewDataSource,
    UICollectionViewDelegateFlowLayout {

    private let imageManager = PHCachingImageManager()
    private var assets: [PHAsset] = []
    private var selectedIds: [String] = []
    private var selectionMode = false

    private var activeMode: MotionPreset {
        get {
            let raw = UserDefaults.standard.string(forKey: "livestudio.mode") ?? MotionPreset.naturalHold.rawValue
            return MotionPreset(rawValue: raw) ?? .naturalHold
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "livestudio.mode") }
    }
    private var activeRatio: Double {
        get {
            let v = UserDefaults.standard.double(forKey: "livestudio.ratio")
            return v > 0 ? MotionPreset.clampRatio(v) : MotionPreset.defaultRatio
        }
        set { UserDefaults.standard.set(MotionPreset.clampRatio(newValue), forKey: "livestudio.ratio") }
    }
    private var activeDuration: Double {
        get {
            let v = UserDefaults.standard.double(forKey: "livestudio.duration")
            return v > 0 ? MotionPreset.clampDuration(v) : MotionPreset.defaultDuration
        }
        set { UserDefaults.standard.set(MotionPreset.clampDuration(newValue), forKey: "livestudio.duration") }
    }
    private var skipExistingLive: Bool {
        get {
            if UserDefaults.standard.object(forKey: "livestudio.skipExistingLive") == nil { return true }
            return UserDefaults.standard.bool(forKey: "livestudio.skipExistingLive")
        }
        set { UserDefaults.standard.set(newValue, forKey: "livestudio.skipExistingLive") }
    }

    private let bottomBar = UIView()
    private let deleteButton = UIButton(type: .system)
    private let saveButton = UIButton(type: .system)
    private let shareButton = UIButton(type: .system)
    private var collectionBottomToView: NSLayoutConstraint!
    private var collectionBottomToBar: NSLayoutConstraint!

    private lazy var collectionView: UICollectionView = {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 2
        layout.minimumLineSpacing = 2
        let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
        cv.backgroundColor = .systemBackground
        cv.dataSource = self
        cv.delegate = self
        cv.register(PhotoCell.self, forCellWithReuseIdentifier: "PhotoCell")
        return cv
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        setupLayout()
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.35
        collectionView.addGestureRecognizer(longPress)
        updateChrome()
        loadGallery()
    }

    private func setupLayout() {
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.backgroundColor = .secondarySystemBackground
        bottomBar.isHidden = true

        deleteButton.translatesAutoresizingMaskIntoConstraints = false
        deleteButton.setTitle("删除", for: .normal)
        deleteButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        deleteButton.backgroundColor = .tertiarySystemFill
        deleteButton.setTitleColor(.systemRed, for: .normal)
        deleteButton.layer.cornerRadius = 10
        deleteButton.addTarget(self, action: #selector(deleteSelectedTapped), for: .touchUpInside)

        saveButton.translatesAutoresizingMaskIntoConstraints = false
        saveButton.setTitle("存实况", for: .normal)
        saveButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        saveButton.backgroundColor = .tertiarySystemFill
        saveButton.setTitleColor(.label, for: .normal)
        saveButton.layer.cornerRadius = 10
        saveButton.addTarget(self, action: #selector(saveOnlyTapped), for: .touchUpInside)

        shareButton.translatesAutoresizingMaskIntoConstraints = false
        shareButton.setTitle("分享实况", for: .normal)
        shareButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        shareButton.backgroundColor = .systemGreen
        shareButton.setTitleColor(.white, for: .normal)
        shareButton.layer.cornerRadius = 10
        shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)

        collectionView.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.addSubview(deleteButton)
        bottomBar.addSubview(saveButton)
        bottomBar.addSubview(shareButton)
        view.addSubview(collectionView)
        view.addSubview(bottomBar)

        collectionBottomToView = collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        collectionBottomToBar = collectionView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor)

        NSLayoutConstraint.activate([
            collectionView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionBottomToView,

            bottomBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            deleteButton.topAnchor.constraint(equalTo: bottomBar.topAnchor, constant: 10),
            deleteButton.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 12),
            deleteButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -10),
            deleteButton.heightAnchor.constraint(equalToConstant: 44),
            deleteButton.widthAnchor.constraint(equalTo: saveButton.widthAnchor, multiplier: 0.85),

            saveButton.topAnchor.constraint(equalTo: deleteButton.topAnchor),
            saveButton.leadingAnchor.constraint(equalTo: deleteButton.trailingAnchor, constant: 8),
            saveButton.bottomAnchor.constraint(equalTo: deleteButton.bottomAnchor),
            saveButton.heightAnchor.constraint(equalToConstant: 44),
            saveButton.widthAnchor.constraint(equalTo: shareButton.widthAnchor, multiplier: 0.72),

            shareButton.topAnchor.constraint(equalTo: deleteButton.topAnchor),
            shareButton.leadingAnchor.constraint(equalTo: saveButton.trailingAnchor, constant: 8),
            shareButton.trailingAnchor.constraint(equalTo: bottomBar.trailingAnchor, constant: -12),
            shareButton.bottomAnchor.constraint(equalTo: deleteButton.bottomAnchor),
            shareButton.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    private func updateChrome() {
        if selectionMode {
            let count = selectedIds.count
            title = "已选 \(count) 张"
            navigationItem.leftBarButtonItem = UIBarButtonItem(
                title: "取消",
                style: .plain,
                target: self,
                action: #selector(exitSelectionTapped)
            )
            let cap = min(18, assets.count)
            navigationItem.rightBarButtonItem = UIBarButtonItem(
                title: (count >= cap && cap > 0) ? "取消全选" : "全选",
                style: .plain,
                target: self,
                action: #selector(toggleSelectAllTapped)
            )
            bottomBar.isHidden = false
            collectionBottomToView.isActive = false
            collectionBottomToBar.isActive = true
            let enabled = count > 0
            [deleteButton, saveButton, shareButton].forEach {
                $0.isEnabled = enabled
                $0.alpha = enabled ? 1.0 : 0.45
            }
            shareButton.setTitle(count > 0 ? "分享实况 (\(count))" : "分享实况", for: .normal)
        } else {
            title = "实况工坊"
            navigationItem.leftBarButtonItem = nil
            navigationItem.rightBarButtonItem = UIBarButtonItem(
                title: "设置",
                style: .plain,
                target: self,
                action: #selector(showSettings)
            )
            bottomBar.isHidden = true
            collectionBottomToBar.isActive = false
            collectionBottomToView.isActive = true
        }
    }

    @objc private func exitSelectionTapped() {
        selectionMode = false
        selectedIds.removeAll()
        updateChrome()
        collectionView.reloadData()
    }

    @objc private func toggleSelectAllTapped() {
        let cap = min(18, assets.count)
        if selectedIds.count >= cap && cap > 0 {
            selectedIds.removeAll()
        } else {
            selectedIds = Array(assets.prefix(cap)).map { $0.localIdentifier }
        }
        updateChrome()
        collectionView.reloadData()
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        let pt = gesture.location(in: collectionView)
        guard let indexPath = collectionView.indexPathForItem(at: pt) else { return }
        let id = assets[indexPath.item].localIdentifier
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        selectionMode = true
        if !selectedIds.contains(id), selectedIds.count < 18 {
            selectedIds.append(id)
        }
        updateChrome()
        collectionView.reloadData()
    }

    private func loadGallery() {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if status == .notDetermined {
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] newStatus in
                DispatchQueue.main.async {
                    if newStatus == .authorized || newStatus == .limited {
                        self?.fetchPhotoAssets()
                    }
                }
            }
        } else if status == .authorized || status == .limited {
            fetchPhotoAssets()
        }
    }

    private func fetchPhotoAssets() {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.fetchLimit = 500
        let res = PHAsset.fetchAssets(with: .image, options: opts)
        var list: [PHAsset] = []
        res.enumerateObjects { asset, _, _ in list.append(asset) }
        self.assets = list
        self.collectionView.reloadData()
    }

    @objc private func showSettings() {
        let pct = (activeRatio - 1.0) * 100.0
        let sheet = UIAlertController(title: "设置", message: nil, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: "实况动态效果：\(activeMode.title)", style: .default) { [weak self] _ in
            self?.showEffectPicker()
        })
        sheet.addAction(UIAlertAction(title: String(format: "微动幅度与时长：%.1f%% · %.1f 秒", pct, activeDuration), style: .default) { [weak self] _ in
            self?.showStrengthPicker()
        })
        sheet.addAction(UIAlertAction(title: "跳过已是实况的照片：\(skipExistingLive ? "已开启" : "已关闭")", style: .default) { [weak self] _ in
            guard let self = self else { return }
            self.skipExistingLive = !self.skipExistingLive
        })
        sheet.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(sheet, animated: true)
    }

    private func showEffectPicker() {
        let alert = UIAlertController(title: "实况动态效果", message: nil, preferredStyle: .actionSheet)
        for mode in MotionPreset.allCases {
            let mark = (mode == activeMode) ? "  ✓" : ""
            alert.addAction(UIAlertAction(title: mode.title + mark, style: .default) { [weak self] _ in
                self?.activeMode = mode
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(alert, animated: true)
    }

    private func showStrengthPicker() {
        let options: [(String, Double, Double)] = [
            ("极微自然（1.0% · 1.3秒）", 1.010, 1.3),
            ("标准推荐（1.8% · 1.6秒）", 1.018, 1.6),
            ("轻柔明显（3.2% · 2.2秒）", 1.032, 2.2)
        ]
        let alert = UIAlertController(title: "微动幅度与时长", message: nil, preferredStyle: .actionSheet)
        for (title, ratio, dur) in options {
            let isCur = abs(activeRatio - ratio) < 0.002
            alert.addAction(UIAlertAction(title: title + (isCur ? "  ✓" : ""), style: .default) { [weak self] _ in
                self?.activeRatio = ratio
                self?.activeDuration = dur
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func deleteSelectedTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        deleteAssets(targets) { [weak self] in
            self?.exitSelectionTapped()
        }
    }

    private func deleteAssets(_ targets: [PHAsset], completion: @escaping () -> Void) {
        guard !targets.isEmpty else { return }
        PHPhotoLibrary.shared().performChanges({
            PHAssetChangeRequest.deleteAssets(targets as NSArray)
        }, completionHandler: { [weak self] success, _ in
            DispatchQueue.main.async {
                if success {
                    completion()
                    self?.fetchPhotoAssets()
                }
            }
        })
    }

    @objc private func saveOnlyTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        convertSelectedAssets(targets, shareAfter: false)
    }

    @objc private func shareTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        convertSelectedAssets(targets, shareAfter: true)
    }

    private func convertSelectedAssets(_ targets: [PHAsset], shareAfter: Bool) {
        guard !targets.isEmpty else { return }
        let skip = skipExistingLive
        let liveTargets = skip ? targets.filter { $0.mediaSubtypes.contains(.photoLive) } : []
        let staticTargets = skip ? targets.filter { !$0.mediaSubtypes.contains(.photoLive) } : targets

        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.isNetworkAccessAllowed = true
        opts.isSynchronous = false

        var images: [UIImage] = []
        let group = DispatchGroup()
        for asset in staticTargets {
            group.enter()
            imageManager.requestImage(
                for: asset,
                targetSize: CGSize(width: 2400, height: 2400),
                contentMode: .aspectFit,
                options: opts
            ) { img, _ in
                if let img = img { images.append(img) }
                group.leave()
            }
        }
        if staticTargets.isEmpty && !liveTargets.isEmpty && shareAfter {
            for asset in liveTargets {
                group.enter()
                imageManager.requestImage(
                    for: asset,
                    targetSize: CGSize(width: 2400, height: 2400),
                    contentMode: .aspectFit,
                    options: opts
                ) { img, _ in
                    if let img = img { images.append(img) }
                    group.leave()
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self = self else { return }
            if staticTargets.isEmpty && !liveTargets.isEmpty && !shareAfter {
                let a = UIAlertController(title: "无需重复转换", message: "所选照片已是实况照片。", preferredStyle: .alert)
                a.addAction(UIAlertAction(title: "好的", style: .default))
                self.present(a, animated: true)
                return
            }
            self.convertImages(images, shareAfter: shareAfter)
        }
    }

    private func convertImages(_ images: [UIImage], shareAfter: Bool) {
        guard !images.isEmpty else { return }
        let mode = activeMode
        let ratio = activeRatio
        let dur = activeDuration
        let loading = UIAlertController(title: "正在手机本地生成实况", message: "请稍候…", preferredStyle: .alert)
        present(loading, animated: true)

        DispatchQueue.global(qos: .userInitiated).async {
            var pairs: [LivePhotoPair] = []
            var firstErr: Error?
            for img in images {
                do {
                    let pair = try LivePhotoEngine.shared.convertAndSaveToPhotos(
                        image: img,
                        preset: mode,
                        ratio: ratio,
                        duration: dur
                    )
                    pairs.append(pair)
                } catch {
                    firstErr = error
                    break
                }
            }
            DispatchQueue.main.async { [weak self] in
                loading.dismiss(animated: true) {
                    guard let self = self else { return }
                    if let err = firstErr {
                        let a = UIAlertController(title: "转换失败", message: err.localizedDescription, preferredStyle: .alert)
                        a.addAction(UIAlertAction(title: "知道了", style: .default))
                        self.present(a, animated: true)
                        return
                    }
                    self.exitSelectionTapped()
                    self.fetchPhotoAssets()
                    if shareAfter {
                        let urls = pairs.flatMap { [$0.imageURL, $0.videoURL] }
                        let ac = UIActivityViewController(activityItems: urls, applicationActivities: nil)
                        self.present(ac, animated: true)
                    } else {
                        let a = UIAlertController(title: "已保存到相册", message: "共 \(pairs.count) 张实况照片已写入系统相册。", preferredStyle: .alert)
                        a.addAction(UIAlertAction(title: "好的", style: .default))
                        self.present(a, animated: true)
                    }
                }
            }
        }
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return assets.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "PhotoCell", for: indexPath) as! PhotoCell
        let asset = assets[indexPath.item]
        let idx = selectedIds.firstIndex(of: asset.localIdentifier)
        cell.configure(index: idx.map { $0 + 1 }, isLive: asset.mediaSubtypes.contains(.photoLive))
        cell.onBadgeTap = { [weak self] in
            self?.toggleItem(at: indexPath)
        }
        imageManager.requestImage(
            for: asset,
            targetSize: CGSize(width: 280, height: 280),
            contentMode: .aspectFill,
            options: nil
        ) { img, _ in
            cell.imageView.image = img
        }
        return cell
    }

    private func toggleItem(at indexPath: IndexPath) {
        guard indexPath.item < assets.count else { return }
        let id = assets[indexPath.item].localIdentifier
        selectionMode = true
        if let idx = selectedIds.firstIndex(of: id) {
            selectedIds.remove(at: idx)
            if selectedIds.isEmpty { selectionMode = false }
        } else if selectedIds.count < 18 {
            selectedIds.append(id)
        }
        updateChrome()
        collectionView.reloadData()
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if selectionMode {
            toggleItem(at: indexPath)
            return
        }
        let asset = assets[indexPath.item]
        let preview = PhotoPreviewViewController(asset: asset, imageManager: imageManager)
        preview.onAction = { [weak self] action in
            guard let self = self else { return }
            switch action {
            case .delete:
                self.deleteAssets([asset]) {}
            case .saveLive:
                self.convertSelectedAssets([asset], shareAfter: false)
            case .shareLive:
                self.convertSelectedAssets([asset], shareAfter: true)
            }
        }
        preview.modalPresentationStyle = .fullScreen
        present(preview, animated: true)
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let side = floor((collectionView.bounds.width - 4) / 3.0)
        return CGSize(width: side, height: side)
    }
}

private enum PreviewAction {
    case delete, saveLive, shareLive
}

private final class PhotoPreviewViewController: UIViewController {
    private let asset: PHAsset
    private let imageManager: PHCachingImageManager
    var onAction: ((PreviewAction) -> Void)?

    init(asset: PHAsset, imageManager: PHCachingImageManager) {
        self.asset = asset
        self.imageManager = imageManager
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        let iv = UIImageView()
        iv.contentMode = .scaleAspectFit
        iv.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(iv)

        let closeBtn = UIButton(type: .system)
        closeBtn.setTitle("关闭", for: .normal)
        closeBtn.setTitleColor(.white, for: .normal)
        closeBtn.titleLabel?.font = .systemFont(ofSize: 16, weight: .semibold)
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        view.addSubview(closeBtn)

        let bar = UIStackView()
        bar.axis = .horizontal
        bar.spacing = 10
        bar.distribution = .fillEqually
        bar.translatesAutoresizingMaskIntoConstraints = false

        let delBtn = makeBarButton("删除", bg: UIColor(white: 0.2, alpha: 1), fg: .systemRed)
        delBtn.addTarget(self, action: #selector(delTapped), for: .touchUpInside)
        let saveBtn = makeBarButton("存实况", bg: UIColor(white: 0.2, alpha: 1), fg: .white)
        saveBtn.addTarget(self, action: #selector(saveTapped), for: .touchUpInside)
        let shareBtn = makeBarButton("分享实况", bg: .systemGreen, fg: .white)
        shareBtn.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        [delBtn, saveBtn, shareBtn].forEach { bar.addArrangedSubview($0) }
        view.addSubview(bar)

        NSLayoutConstraint.activate([
            closeBtn.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            closeBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),

            iv.topAnchor.constraint(equalTo: closeBtn.bottomAnchor, constant: 8),
            iv.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            iv.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            iv.bottomAnchor.constraint(equalTo: bar.topAnchor, constant: -12),

            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 14),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),
            bar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            bar.heightAnchor.constraint(equalToConstant: 46)
        ])

        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.isNetworkAccessAllowed = true
        imageManager.requestImage(
            for: asset,
            targetSize: CGSize(width: 2000, height: 2000),
            contentMode: .aspectFit,
            options: opts
        ) { img, _ in
            iv.image = img
        }
    }

    private func makeBarButton(_ title: String, bg: UIColor, fg: UIColor) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.backgroundColor = bg
        b.setTitleColor(fg, for: .normal)
        b.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        b.layer.cornerRadius = 10
        return b
    }

    @objc private func closeTapped() { dismiss(animated: true) }
    @objc private func delTapped() { dismiss(animated: true) { self.onAction?(.delete) } }
    @objc private func saveTapped() { dismiss(animated: true) { self.onAction?(.saveLive) } }
    @objc private func shareTapped() { dismiss(animated: true) { self.onAction?(.shareLive) } }
}

private final class PhotoCell: UICollectionViewCell {
    let imageView = UIImageView()
    private let badgeBtn = UIButton(type: .custom)
    private let liveLabel = UILabel()
    var onBadgeTap: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(imageView)

        badgeBtn.translatesAutoresizingMaskIntoConstraints = false
        badgeBtn.titleLabel?.font = .systemFont(ofSize: 12, weight: .bold)
        badgeBtn.layer.cornerRadius = 12
        badgeBtn.clipsToBounds = true
        badgeBtn.addTarget(self, action: #selector(badgeTapped), for: .touchUpInside)
        contentView.addSubview(badgeBtn)

        liveLabel.translatesAutoresizingMaskIntoConstraints = false
        liveLabel.text = " LIVE "
        liveLabel.font = .systemFont(ofSize: 9, weight: .bold)
        liveLabel.textColor = .white
        liveLabel.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        liveLabel.layer.cornerRadius = 4
        liveLabel.clipsToBounds = true
        contentView.addSubview(liveLabel)

        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            badgeBtn.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 5),
            badgeBtn.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -5),
            badgeBtn.widthAnchor.constraint(equalToConstant: 24),
            badgeBtn.heightAnchor.constraint(equalToConstant: 24),

            liveLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 5),
            liveLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5)
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    @objc private func badgeTapped() { onBadgeTap?() }

    func configure(index: Int?, isLive: Bool) {
        liveLabel.isHidden = !isLive
        if let idx = index {
            badgeBtn.setTitle("\(idx)", for: .normal)
            badgeBtn.setTitleColor(.white, for: .normal)
            badgeBtn.backgroundColor = .systemGreen
            badgeBtn.layer.borderWidth = 0
            contentView.layer.borderWidth = 2.5
            contentView.layer.borderColor = UIColor.systemGreen.cgColor
        } else {
            badgeBtn.setTitle("", for: .normal)
            badgeBtn.backgroundColor = UIColor.black.withAlphaComponent(0.22)
            badgeBtn.layer.borderWidth = 1.2
            badgeBtn.layer.borderColor = UIColor.white.withAlphaComponent(0.85).cgColor
            contentView.layer.borderWidth = 0
        }
    }
}
