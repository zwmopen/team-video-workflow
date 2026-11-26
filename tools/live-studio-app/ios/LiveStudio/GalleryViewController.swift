import Photos
import PhotosUI
import UIKit

final class GalleryViewController: UIViewController,
    UICollectionViewDataSource,
    UICollectionViewDelegateFlowLayout,
    PHPickerViewControllerDelegate {

    private let imageManager = PHCachingImageManager()
    private var assets: [PHAsset] = []
    private var selectedIds: [String] = []
    private var activeMode: MotionPreset {
        get {
            let raw = UserDefaults.standard.string(forKey: "livestudio.mode") ?? MotionPreset.naturalHold.rawValue
            return MotionPreset(rawValue: raw) ?? .naturalHold
        }
        set {
            UserDefaults.standard.set(newValue.rawValue, forKey: "livestudio.mode")
            updateEffectLabel()
        }
    }
    private var activeRatio: Double {
        get {
            let v = UserDefaults.standard.double(forKey: "livestudio.ratio")
            return v > 0 ? MotionPreset.clampRatio(v) : MotionPreset.defaultRatio
        }
        set {
            UserDefaults.standard.set(MotionPreset.clampRatio(newValue), forKey: "livestudio.ratio")
            updateEffectLabel()
        }
    }
    private var activeDuration: Double {
        get {
            let v = UserDefaults.standard.double(forKey: "livestudio.duration")
            return v > 0 ? MotionPreset.clampDuration(v) : MotionPreset.defaultDuration
        }
        set {
            UserDefaults.standard.set(MotionPreset.clampDuration(newValue), forKey: "livestudio.duration")
            updateEffectLabel()
        }
    }

    private let effectButton = UIButton(type: .system)
    private let statusLabel = UILabel()
    private let saveButton = UIButton(type: .system)
    private let shareButton = UIButton(type: .system)
    private lazy var collectionView: UICollectionView = {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 3
        layout.minimumLineSpacing = 3
        let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
        cv.backgroundColor = .systemBackground
        cv.dataSource = self
        cv.delegate = self
        cv.register(PhotoCell.self, forCellWithReuseIdentifier: "PhotoCell")
        return cv
    }()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "实况工坊"
        view.backgroundColor = .systemBackground
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: "系统选图",
            style: .plain,
            target: self,
            action: #selector(openSystemPicker)
        )
        setupLayout()
        updateEffectLabel()
        updateBottomBar()
        loadGallery()
    }

    private func setupLayout() {
        effectButton.translatesAutoresizingMaskIntoConstraints = false
        effectButton.contentHorizontalAlignment = .left
        effectButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .medium)
        effectButton.backgroundColor = .secondarySystemBackground
        effectButton.layer.cornerRadius = 10
        effectButton.contentEdgeInsets = UIEdgeInsets(top: 10, left: 12, bottom: 10, right: 12)
        effectButton.addTarget(self, action: #selector(showEffectPicker), for: .touchUpInside)

        let bottomBar = UIView()
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.backgroundColor = .secondarySystemBackground

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabel

        saveButton.translatesAutoresizingMaskIntoConstraints = false
        saveButton.setTitle("存为实况", for: .normal)
        saveButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        saveButton.backgroundColor = .tertiarySystemFill
        saveButton.setTitleColor(.label, for: .normal)
        saveButton.layer.cornerRadius = 10
        saveButton.addTarget(self, action: #selector(saveOnlyTapped), for: .touchUpInside)

        shareButton.translatesAutoresizingMaskIntoConstraints = false
        shareButton.setTitle("转实况并分享", for: .normal)
        shareButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        shareButton.backgroundColor = .systemGreen
        shareButton.setTitleColor(.white, for: .normal)
        shareButton.layer.cornerRadius = 10
        shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)

        collectionView.translatesAutoresizingMaskIntoConstraints = false

        bottomBar.addSubview(statusLabel)
        bottomBar.addSubview(saveButton)
        bottomBar.addSubview(shareButton)
        view.addSubview(effectButton)
        view.addSubview(collectionView)
        view.addSubview(bottomBar)

        NSLayoutConstraint.activate([
            effectButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 6),
            effectButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            effectButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),

            collectionView.topAnchor.constraint(equalTo: effectButton.bottomAnchor, constant: 8),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor),

            bottomBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            statusLabel.topAnchor.constraint(equalTo: bottomBar.topAnchor, constant: 8),
            statusLabel.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 14),
            statusLabel.trailingAnchor.constraint(equalTo: bottomBar.trailingAnchor, constant: -14),

            saveButton.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 8),
            saveButton.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 14),
            saveButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -10),
            saveButton.heightAnchor.constraint(equalToConstant: 44),
            saveButton.widthAnchor.constraint(equalTo: shareButton.widthAnchor, multiplier: 0.65),

            shareButton.topAnchor.constraint(equalTo: saveButton.topAnchor),
            shareButton.leadingAnchor.constraint(equalTo: saveButton.trailingAnchor, constant: 10),
            shareButton.trailingAnchor.constraint(equalTo: bottomBar.trailingAnchor, constant: -14),
            shareButton.bottomAnchor.constraint(equalTo: saveButton.bottomAnchor),
            shareButton.heightAnchor.constraint(equalToConstant: 44)
        ])
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

    private func updateEffectLabel() {
        let pct = (activeRatio - 1.0) * 100.0
        effectButton.setTitle(String(format: "效果：%@ · %.1f%% · %.1f秒（点此切换）", activeMode.title, pct, activeDuration), for: .normal)
    }

    private func updateBottomBar() {
        let count = selectedIds.count
        statusLabel.text = count == 0
            ? "点击上方照片多选，手机本地离线生成原生实况照片"
            : "已选 \(count) 张照片"
        saveButton.isEnabled = count > 0
        shareButton.isEnabled = count > 0
        saveButton.alpha = count > 0 ? 1 : 0.45
        shareButton.alpha = count > 0 ? 1 : 0.45
        shareButton.setTitle(count > 0 ? "转实况并分享 (\(count))" : "转实况并分享", for: .normal)
    }

    @objc private func showEffectPicker() {
        let alert = UIAlertController(title: "选择实况动态效果", message: nil, preferredStyle: .actionSheet)
        for mode in MotionPreset.allCases {
            let mark = (mode == activeMode) ? "  ✓" : ""
            alert.addAction(UIAlertAction(title: mode.title + mark, style: .default) { [weak self] _ in
                self?.activeMode = mode
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func openSystemPicker() {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 18
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        present(picker, animated: true)
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard !results.isEmpty else { return }
        var imgs: [UIImage] = []
        let group = DispatchGroup()
        for r in results where r.itemProvider.canLoadObject(ofClass: UIImage.self) {
            group.enter()
            r.itemProvider.loadObject(ofClass: UIImage.self) { obj, _ in
                if let img = obj as? UIImage { imgs.append(img) }
                group.leave()
            }
        }
        group.notify(queue: .main) { [weak self] in
            if !imgs.isEmpty {
                self?.convertImages(imgs, shareAfter: true)
            }
        }
    }

    @objc private func saveOnlyTapped() {
        convertSelectedAssets(shareAfter: false)
    }

    @objc private func shareTapped() {
        convertSelectedAssets(shareAfter: true)
    }

    private func convertSelectedAssets(shareAfter: Bool) {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        guard !targets.isEmpty else { return }
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.isNetworkAccessAllowed = true
        opts.isSynchronous = false

        var images: [UIImage] = []
        let group = DispatchGroup()
        for asset in targets {
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
        group.notify(queue: .main) { [weak self] in
            self?.convertImages(images, shareAfter: shareAfter)
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
                    self.selectedIds.removeAll()
                    self.updateBottomBar()
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
        cell.configure(index: idx.map { $0 + 1 })
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

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let id = assets[indexPath.item].localIdentifier
        if let idx = selectedIds.firstIndex(of: id) {
            selectedIds.remove(at: idx)
        } else if selectedIds.count < 18 {
            selectedIds.append(id)
        }
        updateBottomBar()
        collectionView.reloadItems(at: [indexPath])
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let side = floor((collectionView.bounds.width - 6) / 3.0)
        return CGSize(width: side, height: side)
    }
}

private final class PhotoCell: UICollectionViewCell {
    let imageView = UIImageView()
    private let badge = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(imageView)

        badge.translatesAutoresizingMaskIntoConstraints = false
        badge.backgroundColor = .systemGreen
        badge.textColor = .white
        badge.font = .systemFont(ofSize: 12, weight: .bold)
        badge.textAlignment = .center
        badge.layer.cornerRadius = 12
        badge.clipsToBounds = true
        contentView.addSubview(badge)

        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: contentView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            badge.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            badge.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -6),
            badge.widthAnchor.constraint(equalToConstant: 24),
            badge.heightAnchor.constraint(equalToConstant: 24)
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func configure(index: Int?) {
        if let idx = index {
            badge.isHidden = false
            badge.text = "\(idx)"
            contentView.layer.borderWidth = 2.5
            contentView.layer.borderColor = UIColor.systemGreen.cgColor
        } else {
            badge.isHidden = true
            contentView.layer.borderWidth = 0
        }
    }
}
