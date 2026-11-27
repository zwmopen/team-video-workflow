import Photos
import UIKit

final class GalleryViewController: UIViewController,
    UICollectionViewDataSource,
    UICollectionViewDelegateFlowLayout,
    UIGestureRecognizerDelegate {

    private let imageManager = PHCachingImageManager()
    private var assets: [PHAsset] = []
    private var selectedIds: [String] = []
    private var selectionMode = false

    private var dragStartIndex: Int?
    private var dragLastIndex: Int?
    private var dragSelectTarget = true
    private var dragBaselineIds: [String] = []
    private var dragAutoScrollLink: CADisplayLink?
    private var dragPointInViewport: CGPoint = .zero
    private var panSelectGesture: UIPanGestureRecognizer!

    deinit {
        dragAutoScrollLink?.invalidate()
    }

    private var activeMode: MotionPreset {
        get {
            let raw = UserDefaults.standard.string(forKey: "livestudio.mode") ?? AutoLiveMotionMode.naturalHold.rawValue
            return AutoLiveMotionMode(rawValue: raw) ?? .naturalHold
        }
        set {
            let previous = activeMode
            UserDefaults.standard.set(newValue.rawValue, forKey: "livestudio.mode")
            if previous != newValue {
                if newValue == .pushDrift { activeRatio = 1.116; activeDuration = 2.0 }
                else if previous == .pushDrift { activeRatio = MotionPreset.defaultRatio; activeDuration = MotionPreset.defaultDuration }
            }
        }
    }
    private var activeTier: LiveQualityTier {
        get {
            let raw = UserDefaults.standard.string(forKey: "livestudio.tier") ?? LiveQualityTier.hd.rawValue
            return LiveQualityTier(rawValue: raw) ?? .hd
        }
        set { UserDefaults.standard.set(newValue.rawValue, forKey: "livestudio.tier") }
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

    private let liveQuickMenu = LiveQuickMenu()
    private let liveModeButton = UIButton(type: .system)
    private lazy var liveModeItem = UIBarButtonItem(customView: liveModeButton)
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
        liveModeButton.setTitle("LIVE ▾", for: .normal)
        liveModeButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        liveModeButton.backgroundColor = .tertiarySystemFill
        liveModeButton.layer.cornerRadius = 14
        liveModeButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 9, bottom: 5, right: 9)
        liveModeButton.accessibilityLabel = "实况效果，点击或长按滑动选择"
        liveModeButton.addTarget(self, action: #selector(showEffectPicker), for: .touchUpInside)
        let liveHold = UILongPressGestureRecognizer(target: self, action: #selector(liveModeHeld(_:)))
        liveHold.minimumPressDuration = 0.35
        liveModeButton.addGestureRecognizer(liveHold)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.30
        longPress.allowableMovement = 14
        collectionView.addGestureRecognizer(longPress)

        panSelectGesture = UIPanGestureRecognizer(target: self, action: #selector(handleSelectionPan(_:)))
        panSelectGesture.delegate = self
        collectionView.addGestureRecognizer(panSelectGesture)

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
            if let action = navigationItem.rightBarButtonItem { navigationItem.rightBarButtonItems = [action, liveModeItem] }
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
            if let action = navigationItem.rightBarButtonItem { navigationItem.rightBarButtonItems = [action, liveModeItem] }
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

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === panSelectGesture {
            guard selectionMode else { return false }
            let vel = panSelectGesture.velocity(in: collectionView)
            return abs(vel.x) > abs(vel.y) * 1.15
        }
        return true
    }

    @objc private func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        let pt = gesture.location(in: collectionView)
        switch gesture.state {
        case .began:
            guard let indexPath = indexPathNearPoint(pt) else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            beginDragSelect(at: indexPath.item, pointInCollection: pt)
        case .changed:
            updateDragSelect(pointInCollection: pt)
        case .ended, .cancelled, .failed:
            endDragSelect()
        default:
            break
        }
    }

    @objc private func handleSelectionPan(_ gesture: UIPanGestureRecognizer) {
        let pt = gesture.location(in: collectionView)
        switch gesture.state {
        case .began:
            guard let indexPath = indexPathNearPoint(pt) else { return }
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            beginDragSelect(at: indexPath.item, pointInCollection: pt)
        case .changed:
            updateDragSelect(pointInCollection: pt)
        case .ended, .cancelled, .failed:
            endDragSelect()
        default:
            break
        }
    }

    private func beginDragSelect(at startIdx: Int, pointInCollection pt: CGPoint) {
        guard startIdx >= 0 && startIdx < assets.count else { return }
        selectionMode = true
        let id = assets[startIdx].localIdentifier
        dragSelectTarget = !selectedIds.contains(id)
        dragStartIndex = startIdx
        dragLastIndex = nil
        dragBaselineIds = selectedIds
        dragPointInViewport = CGPoint(
            x: pt.x - collectionView.contentOffset.x,
            y: pt.y - collectionView.contentOffset.y
        )
        collectionView.isScrollEnabled = false
        applyDragRange(to: startIdx)
    }

    private func updateDragSelect(pointInCollection pt: CGPoint) {
        guard dragStartIndex != nil else { return }
        dragPointInViewport = CGPoint(
            x: pt.x - collectionView.contentOffset.x,
            y: pt.y - collectionView.contentOffset.y
        )
        if let indexPath = indexPathNearPoint(pt) {
            applyDragRange(to: indexPath.item)
        }
        updateAutoScrollState()
    }

    private func updateAutoScrollState() {
        let edge: CGFloat = 56
        let h = collectionView.bounds.height
        let needScroll = dragPointInViewport.y < edge || dragPointInViewport.y > (h - edge)
        if needScroll && dragAutoScrollLink == nil {
            let link = CADisplayLink(target: self, selector: #selector(handleAutoScrollTick))
            link.add(to: .main, forMode: .common)
            dragAutoScrollLink = link
        } else if !needScroll, let link = dragAutoScrollLink {
            link.invalidate()
            dragAutoScrollLink = nil
        }
    }

    @objc private func handleAutoScrollTick() {
        guard dragStartIndex != nil else {
            dragAutoScrollLink?.invalidate()
            dragAutoScrollLink = nil
            return
        }
        let edge: CGFloat = 56
        let h = collectionView.bounds.height
        var delta: CGFloat = 0
        if dragPointInViewport.y < edge {
            let factor = min(1, (edge - dragPointInViewport.y) / edge)
            delta = -max(3, 14 * factor)
        } else if dragPointInViewport.y > (h - edge) {
            let factor = min(1, (dragPointInViewport.y - (h - edge)) / edge)
            delta = max(3, 14 * factor)
        }
        guard delta != 0 else { return }
        let minOffsetY = -collectionView.adjustedContentInset.top
        let maxOffsetY = max(minOffsetY, collectionView.contentSize.height - h + collectionView.adjustedContentInset.bottom)
        let nextY = min(maxOffsetY, max(minOffsetY, collectionView.contentOffset.y + delta))
        if abs(nextY - collectionView.contentOffset.y) > 0.2 {
            collectionView.contentOffset.y = nextY
            let contentPt = CGPoint(
                x: dragPointInViewport.x + collectionView.contentOffset.x,
                y: dragPointInViewport.y + collectionView.contentOffset.y
            )
            if let indexPath = indexPathNearPoint(contentPt) {
                applyDragRange(to: indexPath.item)
            }
        }
    }

    private func indexPathNearPoint(_ rawPt: CGPoint) -> IndexPath? {
        let b = collectionView.bounds
        let x = min(max(rawPt.x, b.minX + 4), max(b.minX + 4, b.maxX - 4))
        let y = min(max(rawPt.y, b.minY + 4), max(b.minY + 4, b.maxY - 4))
        let clamped = CGPoint(x: x, y: y)
        if let ip = collectionView.indexPathForItem(at: clamped) { return ip }
        for (dx, dy) in [(-4 as CGFloat, 0 as CGFloat), (4, 0), (0, -4), (0, 4)] {
            if let ip = collectionView.indexPathForItem(at: CGPoint(x: clamped.x + dx, y: clamped.y + dy)) {
                return ip
            }
        }
        return nil
    }

    private func applyDragRange(to endIdx: Int) {
        guard let startIdx = dragStartIndex,
              endIdx >= 0, endIdx < assets.count,
              dragLastIndex != endIdx else { return }
        dragLastIndex = endIdx
        selectedIds = dragBaselineIds
        if dragSelectTarget {
            let step = endIdx >= startIdx ? 1 : -1
            var i = startIdx
            while true {
                let id = assets[i].localIdentifier
                if !selectedIds.contains(id) {
                    if selectedIds.count >= 18 { break }
                    selectedIds.append(id)
                }
                if i == endIdx { break }
                i += step
            }
        } else {
            let lo = min(startIdx, endIdx)
            let hi = max(startIdx, endIdx)
            let dropSet = Set((lo...hi).map { assets[$0].localIdentifier })
            selectedIds.removeAll { dropSet.contains($0) }
        }
        updateChrome()
        refreshVisibleSelectionCells()
    }

    private func refreshVisibleSelectionCells() {
        for case let cell as PhotoCell in collectionView.visibleCells {
            guard let ip = collectionView.indexPath(for: cell), ip.item < assets.count else { continue }
            let id = assets[ip.item].localIdentifier
            let idx = selectedIds.firstIndex(of: id)
            cell.configure(index: idx.map { $0 + 1 }, isLive: assets[ip.item].mediaSubtypes.contains(.photoLive))
        }
    }

    private func endDragSelect() {
        dragAutoScrollLink?.invalidate()
        dragAutoScrollLink = nil
        dragStartIndex = nil
        dragLastIndex = nil
        dragBaselineIds.removeAll()
        collectionView.isScrollEnabled = true
        if selectedIds.isEmpty {
            selectionMode = false
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
        let sheet = UIAlertController(title: "设置", message: "静态封面图保持 JPEG/HEIC 原画无损直出", preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: "实况动态效果：\(activeMode.title)", style: .default) { [weak self] _ in
            self?.showEffectPicker()
        })
        sheet.addAction(UIAlertAction(title: "动态视频画质：\(activeTier.shortLabel)", style: .default) { [weak self] _ in
            self?.showQualityTierPicker()
        })
        sheet.addAction(UIAlertAction(title: String(format: "微动幅度与时长：%.1f%% · %.1f 秒", pct, activeDuration), style: .default) { [weak self] _ in
            self?.showStrengthPicker()
        })
        sheet.addAction(UIAlertAction(title: "跳过已是实况的照片：\(skipExistingLive ? "已开启" : "已关闭")", style: .default) { [weak self] _ in
            guard let self = self else { return }
            self.skipExistingLive = !self.skipExistingLive
        })
        sheet.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let pop = sheet.popoverPresentationController {
            pop.barButtonItem = navigationItem.rightBarButtonItem
        }
        present(sheet, animated: true)
    }

    private func showQualityTierPicker() {
        let alert = UIAlertController(
            title: "动态视频画质档位",
            message: "静态封面图保持原图无损；仅控制动态视频渲染规格。",
            preferredStyle: .actionSheet
        )
        for tier in LiveQualityTier.allCases {
            let mark = (tier == activeTier) ? "  ✓" : ""
            alert.addAction(UIAlertAction(title: "\(tier.title)（\(tier.shortLabel)）\(mark)", style: .default) { [weak self] _ in
                self?.activeTier = tier
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let pop = alert.popoverPresentationController {
            pop.barButtonItem = navigationItem.rightBarButtonItem
        }
        present(alert, animated: true)
    }

    @objc private func showEffectPicker() {
        let modes = MotionPreset.allCases
        liveQuickMenu.show(anchor: liveModeButton, labels: modes.map { $0.title },
                           selected: modes.firstIndex(of: activeMode) ?? 0) { [weak self] i in
            self?.activeMode = modes[i]
        }
    }

    @objc private func liveModeHeld(_ gesture: UILongPressGestureRecognizer) {
        let modes = MotionPreset.allCases
        liveQuickMenu.handle(gesture, labels: modes.map { $0.title },
                             selected: modes.firstIndex(of: activeMode) ?? 0) { [weak self] i in
            self?.activeMode = modes[i]
        }
    }

    private func showStrengthPicker() {
        let options: [(String, Double, Double)] = activeMode == .pushDrift ? [
            ("柔和（5.8% · 2.0秒）", 1.058, 2.0),
            ("参考原版（11.6% · 2.0秒）", 1.116, 2.0),
            ("明显（12.0% · 2.2秒）", 1.120, 2.2)
        ] : [
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

        if staticTargets.isEmpty && !liveTargets.isEmpty && !shareAfter {
            let a = UIAlertController(title: "无需重复转换", message: "所选照片已是实况照片。", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "好的", style: .default))
            present(a, animated: true)
            return
        }

        let workTargets = staticTargets.isEmpty ? liveTargets : staticTargets
        let mode = activeMode
        let tier = activeTier
        let ratio = activeRatio
        let dur = activeDuration
        let loading = UIAlertController(title: "正在手机本地生成实况", message: "静态原画无损 · \(tier.shortLabel)…", preferredStyle: .alert)
        present(loading, animated: true)

        requestRawDatas(for: workTargets) { [weak self] resolved, failedDataAssets in
            DispatchQueue.global(qos: .userInitiated).async {
                var results: [LiveConvertResult] = []
                var failedAssets: [PHAsset] = failedDataAssets
                var firstErr: Error?
                for (asset, data) in resolved {
                    do {
                        let res = try OnDeviceLivePhotoEngine.shared.convertAndSaveDataToPhotos(
                            sourceData: data,
                            preset: mode,
                            qualityTier: tier,
                            ratio: ratio,
                            duration: dur
                        )
                        results.append(res)
                    } catch {
                        failedAssets.append(asset)
                        if firstErr == nil { firstErr = error }
                    }
                }
                DispatchQueue.main.async { [weak self] in
                    loading.dismiss(animated: true) {
                        guard let self = self else { return }
                        if !results.isEmpty {
                            self.fetchPhotoAssets()
                        }
                        if !failedAssets.isEmpty {
                            let msg = "成功 \(results.count) 张，失败 \(failedAssets.count) 张"
                                + (firstErr != nil ? "\n原因：\(firstErr!.localizedDescription)" : "")
                            let a = UIAlertController(title: "部分照片转换失败", message: msg, preferredStyle: .alert)
                            a.addAction(UIAlertAction(title: "重试失败的 \(failedAssets.count) 张", style: .default) { _ in
                                self.convertSelectedAssets(failedAssets, shareAfter: shareAfter)
                            })
                            if shareAfter && !results.isEmpty {
                                a.addAction(UIAlertAction(title: "先分享已成功 \(results.count) 张", style: .default) { _ in
                                    let urls = results.flatMap { [$0.pair.imageURL, $0.pair.videoURL] }
                                    let ac = UIActivityViewController(activityItems: urls, applicationActivities: nil)
                                    self.present(ac, animated: true)
                                })
                            }
                            a.addAction(UIAlertAction(title: "取消", style: .cancel))
                            self.present(a, animated: true)
                            return
                        }
                        self.exitSelectionTapped()
                        if shareAfter {
                            let urls = results.flatMap { [$0.pair.imageURL, $0.pair.videoURL] }
                            let ac = UIActivityViewController(activityItems: urls, applicationActivities: nil)
                            self.present(ac, animated: true)
                        } else {
                            let losslessCount = results.filter { $0.coverMode == .losslessCopy }.count
                            let covLabel = losslessCount == results.count ? "静态原画无损" : "高质量保留"
                            let effTier = results.last?.effectiveTier.shortLabel ?? tier.shortLabel
                            let a = UIAlertController(
                                title: "已保存到相册",
                                message: "共 \(results.count) 张实况已写入系统相册（\(covLabel) · \(effTier)）。",
                                preferredStyle: .alert
                            )
                            a.addAction(UIAlertAction(title: "好的", style: .default))
                            self.present(a, animated: true)
                        }
                    }
                }
            }
        }
    }

    private func requestRawDatas(
        for targets: [PHAsset],
        completion: @escaping ([(PHAsset, Data)], [PHAsset]) -> Void
    ) {
        var pairs: [(Int, PHAsset, Data)] = []
        var failed: [PHAsset] = []
        let lock = NSLock()
        let group = DispatchGroup()
        for (idx, asset) in targets.enumerated() {
            group.enter()
            OnDeviceLivePhotoEngine.shared.requestOriginalPhotoData(for: asset) { data in
                lock.lock()
                if let d = data, !d.isEmpty {
                    pairs.append((idx, asset, d))
                } else {
                    failed.append(asset)
                }
                lock.unlock()
                group.leave()
            }
        }
        group.notify(queue: .main) {
            let sorted = pairs.sorted { $0.0 < $1.0 }.map { ($0.1, $0.2) }
            completion(sorted, failed)
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
