import Photos
import UIKit

/// Native Phone Gallery mode embedded inside Album (iOS 12.0+ safe).
final class PhoneGalleryViewController: UIViewController,
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

    private let headerBar = UIView()
    private let cancelSelButton = UIButton(type: .system)
    private let statusChipLabel = UILabel()
    private let effectButton = UIButton(type: .system)
    private let multiSelectButton = UIButton(type: .system)

    private let permissionCard = UIView()
    private let bottomBar = UIView()
    private let deleteButton = UIButton(type: .system)
    private let rawButton = UIButton(type: .system)
    private let saveButton = UIButton(type: .system)
    private let shareButton = UIButton(type: .system)
    private var collectionBottomToView: NSLayoutConstraint!
    private var collectionBottomToBar: NSLayoutConstraint!

    private lazy var collectionView: UICollectionView = {
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 2
        layout.minimumLineSpacing = 2
        let cv = UICollectionView(frame: .zero, collectionViewLayout: layout)
        cv.backgroundColor = AppColors.background
        cv.dataSource = self
        cv.delegate = self
        cv.register(GalleryThumbCell.self, forCellWithReuseIdentifier: "GalleryThumbCell")
        return cv
    }()

    deinit {
        dragAutoScrollLink?.invalidate()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppColors.background
        setupLayout()

        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
        longPress.minimumPressDuration = 0.30
        longPress.allowableMovement = 14
        collectionView.addGestureRecognizer(longPress)

        panSelectGesture = UIPanGestureRecognizer(target: self, action: #selector(handleSelectionPan(_:)))
        panSelectGesture.delegate = self
        collectionView.addGestureRecognizer(panSelectGesture)

        updateChrome()
    }

    func onShown() {
        updateEffectButtonTitle()
        loadGallery()
    }

    func refreshGallery() {
        updateEffectButtonTitle()
        loadGallery()
    }

    private func setupLayout() {
        headerBar.translatesAutoresizingMaskIntoConstraints = false
        cancelSelButton.translatesAutoresizingMaskIntoConstraints = false
        cancelSelButton.setTitle("取消", for: .normal)
        cancelSelButton.setTitleColor(AppColors.brandLightText, for: .normal)
        cancelSelButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        cancelSelButton.addTarget(self, action: #selector(exitSelectionTapped), for: .touchUpInside)

        statusChipLabel.translatesAutoresizingMaskIntoConstraints = false
        statusChipLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        statusChipLabel.textColor = AppColors.text
        statusChipLabel.text = "本机相册"

        effectButton.translatesAutoresizingMaskIntoConstraints = false
        effectButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .semibold)
        effectButton.setTitleColor(AppColors.text, for: .normal)
        effectButton.backgroundColor = AppColors.secondaryBackground
        effectButton.layer.cornerRadius = 13
        effectButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 10, bottom: 5, right: 10)
        effectButton.addTarget(self, action: #selector(showEffectPicker), for: .touchUpInside)
        updateEffectButtonTitle()

        multiSelectButton.translatesAutoresizingMaskIntoConstraints = false
        multiSelectButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .semibold)
        multiSelectButton.setTitleColor(AppColors.brandLightText, for: .normal)
        multiSelectButton.backgroundColor = AppColors.brandLightBg
        multiSelectButton.layer.cornerRadius = 13
        multiSelectButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 10, bottom: 5, right: 10)
        multiSelectButton.addTarget(self, action: #selector(rightCtrlTapped), for: .touchUpInside)

        headerBar.addSubview(cancelSelButton)
        headerBar.addSubview(statusChipLabel)
        headerBar.addSubview(effectButton)
        headerBar.addSubview(multiSelectButton)

        // Permission Card
        permissionCard.translatesAutoresizingMaskIntoConstraints = false
        permissionCard.backgroundColor = AppColors.secondaryBackground
        permissionCard.layer.cornerRadius = 16
        permissionCard.isHidden = true

        let permTitle = UILabel()
        permTitle.translatesAutoresizingMaskIntoConstraints = false
        permTitle.text = "授权读取本机相册"
        permTitle.font = .systemFont(ofSize: 16, weight: .bold)
        permTitle.textColor = AppColors.text

        let permDesc = UILabel()
        permDesc.translatesAutoresizingMaskIntoConstraints = false
        permDesc.text = "在「团队工作相册」内直接浏览本机照片：点开看大图、删图、长按/滑动多选，手机本地 1440P 实时渲染原生动态实况并分享。"
        permDesc.font = .systemFont(ofSize: 13)
        permDesc.textColor = AppColors.secondaryText
        permDesc.numberOfLines = 0

        let grantBtn = UIButton(type: .system)
        grantBtn.translatesAutoresizingMaskIntoConstraints = false
        grantBtn.setTitle("允许读取本机相册", for: .normal)
        grantBtn.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        grantBtn.backgroundColor = AppColors.brandLightText
        grantBtn.setTitleColor(.white, for: .normal)
        grantBtn.layer.cornerRadius = 12
        grantBtn.addTarget(self, action: #selector(requestAccessTapped), for: .touchUpInside)

        permissionCard.addSubview(permTitle)
        permissionCard.addSubview(permDesc)
        permissionCard.addSubview(grantBtn)

        // Sticky Bottom Bar
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.backgroundColor = AppColors.secondaryBackground
        bottomBar.isHidden = true

        deleteButton.translatesAutoresizingMaskIntoConstraints = false
        deleteButton.setTitle("删除", for: .normal)
        deleteButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        deleteButton.backgroundColor = AppColors.sharedBackground
        deleteButton.setTitleColor(.systemRed, for: .normal)
        deleteButton.layer.cornerRadius = 10
        deleteButton.addTarget(self, action: #selector(deleteSelectedTapped), for: .touchUpInside)

        rawButton.translatesAutoresizingMaskIntoConstraints = false
        rawButton.setTitle("原图", for: .normal)
        rawButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        rawButton.backgroundColor = AppColors.sharedBackground
        rawButton.setTitleColor(AppColors.text, for: .normal)
        rawButton.layer.cornerRadius = 10
        rawButton.addTarget(self, action: #selector(shareRawTapped), for: .touchUpInside)

        saveButton.translatesAutoresizingMaskIntoConstraints = false
        saveButton.setTitle("存实况", for: .normal)
        saveButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        saveButton.backgroundColor = AppColors.sharedBackground
        saveButton.setTitleColor(AppColors.text, for: .normal)
        saveButton.layer.cornerRadius = 10
        saveButton.addTarget(self, action: #selector(saveOnlyTapped), for: .touchUpInside)

        shareButton.translatesAutoresizingMaskIntoConstraints = false
        shareButton.setTitle("分享实况", for: .normal)
        shareButton.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        shareButton.backgroundColor = AppColors.brandLightText
        shareButton.setTitleColor(.white, for: .normal)
        shareButton.layer.cornerRadius = 10
        shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)

        collectionView.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.addSubview(deleteButton)
        bottomBar.addSubview(rawButton)
        bottomBar.addSubview(saveButton)
        bottomBar.addSubview(shareButton)

        view.addSubview(headerBar)
        view.addSubview(collectionView)
        view.addSubview(permissionCard)
        view.addSubview(bottomBar)

        collectionBottomToView = collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        collectionBottomToBar = collectionView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor)

        NSLayoutConstraint.activate([
            headerBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 4),
            headerBar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            headerBar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            headerBar.heightAnchor.constraint(equalToConstant: 36),

            cancelSelButton.leadingAnchor.constraint(equalTo: headerBar.leadingAnchor),
            cancelSelButton.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            statusChipLabel.leadingAnchor.constraint(equalTo: cancelSelButton.trailingAnchor, constant: 6),
            statusChipLabel.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            multiSelectButton.trailingAnchor.constraint(equalTo: headerBar.trailingAnchor),
            multiSelectButton.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            effectButton.trailingAnchor.constraint(equalTo: multiSelectButton.leadingAnchor, constant: -6),
            effectButton.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            permissionCard.topAnchor.constraint(equalTo: headerBar.bottomAnchor, constant: 16),
            permissionCard.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            permissionCard.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),

            permTitle.topAnchor.constraint(equalTo: permissionCard.topAnchor, constant: 18),
            permTitle.leadingAnchor.constraint(equalTo: permissionCard.leadingAnchor, constant: 16),
            permTitle.trailingAnchor.constraint(equalTo: permissionCard.trailingAnchor, constant: -16),

            permDesc.topAnchor.constraint(equalTo: permTitle.bottomAnchor, constant: 8),
            permDesc.leadingAnchor.constraint(equalTo: permTitle.leadingAnchor),
            permDesc.trailingAnchor.constraint(equalTo: permTitle.trailingAnchor),

            grantBtn.topAnchor.constraint(equalTo: permDesc.bottomAnchor, constant: 14),
            grantBtn.leadingAnchor.constraint(equalTo: permTitle.leadingAnchor),
            grantBtn.trailingAnchor.constraint(equalTo: permTitle.trailingAnchor),
            grantBtn.bottomAnchor.constraint(equalTo: permissionCard.bottomAnchor, constant: -18),
            grantBtn.heightAnchor.constraint(equalToConstant: 44),

            collectionView.topAnchor.constraint(equalTo: headerBar.bottomAnchor, constant: 4),
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionBottomToView,

            bottomBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomBar.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            deleteButton.topAnchor.constraint(equalTo: bottomBar.topAnchor, constant: 10),
            deleteButton.leadingAnchor.constraint(equalTo: bottomBar.leadingAnchor, constant: 10),
            deleteButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -10),
            deleteButton.heightAnchor.constraint(equalToConstant: 44),
            deleteButton.widthAnchor.constraint(equalTo: rawButton.widthAnchor),

            rawButton.topAnchor.constraint(equalTo: deleteButton.topAnchor),
            rawButton.leadingAnchor.constraint(equalTo: deleteButton.trailingAnchor, constant: 6),
            rawButton.bottomAnchor.constraint(equalTo: deleteButton.bottomAnchor),
            rawButton.heightAnchor.constraint(equalToConstant: 44),
            rawButton.widthAnchor.constraint(equalTo: saveButton.widthAnchor, multiplier: 0.85),

            saveButton.topAnchor.constraint(equalTo: deleteButton.topAnchor),
            saveButton.leadingAnchor.constraint(equalTo: rawButton.trailingAnchor, constant: 6),
            saveButton.bottomAnchor.constraint(equalTo: deleteButton.bottomAnchor),
            saveButton.heightAnchor.constraint(equalToConstant: 44),
            saveButton.widthAnchor.constraint(equalTo: shareButton.widthAnchor, multiplier: 0.72),

            shareButton.topAnchor.constraint(equalTo: deleteButton.topAnchor),
            shareButton.leadingAnchor.constraint(equalTo: saveButton.trailingAnchor, constant: 6),
            shareButton.trailingAnchor.constraint(equalTo: bottomBar.trailingAnchor, constant: -10),
            shareButton.bottomAnchor.constraint(equalTo: deleteButton.bottomAnchor),
            shareButton.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    private func updateEffectButtonTitle() {
        let title = AutoLivePreferences.motionMode.title
        let short = title.components(separatedBy: "（").first ?? title
        effectButton.setTitle("效果：\(short)", for: .normal)
    }

    private func updateChrome() {
        if selectionMode {
            let count = selectedIds.count
            cancelSelButton.isHidden = false
            statusChipLabel.text = "已选 \(count) 张"
            let cap = min(18, assets.count)
            multiSelectButton.setTitle((count >= cap && cap > 0) ? "取消全选" : "全选", for: .normal)
            bottomBar.isHidden = false
            collectionBottomToView.isActive = false
            collectionBottomToBar.isActive = true
            let enabled = count > 0
            [deleteButton, rawButton, saveButton, shareButton].forEach {
                $0.isEnabled = enabled
                $0.alpha = enabled ? 1.0 : 0.45
            }
            shareButton.setTitle(count > 0 ? "分享实况 (\(count))" : "分享实况", for: .normal)
        } else {
            cancelSelButton.isHidden = true
            statusChipLabel.text = assets.isEmpty ? "手机相册" : "手机相册 · \(assets.count) 张"
            multiSelectButton.setTitle("多选", for: .normal)
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

    @objc private func rightCtrlTapped() {
        if selectionMode {
            let cap = min(18, assets.count)
            if selectedIds.count >= cap && cap > 0 {
                selectedIds.removeAll()
            } else {
                selectedIds = Array(assets.prefix(cap)).map { $0.localIdentifier }
            }
        } else if !assets.isEmpty {
            selectionMode = true
        }
        updateChrome()
        collectionView.reloadData()
    }

    // MARK: - Swipe-to-Multiselect (long-press drag + horizontal pan + edge auto-scroll)
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
            if #available(iOS 10.0, *) { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
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
            if #available(iOS 10.0, *) { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
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
        dragPointInViewport = CGPoint(x: pt.x - collectionView.contentOffset.x, y: pt.y - collectionView.contentOffset.y)
        collectionView.isScrollEnabled = false
        applyDragRange(to: startIdx)
    }

    private func updateDragSelect(pointInCollection pt: CGPoint) {
        guard dragStartIndex != nil else { return }
        dragPointInViewport = CGPoint(x: pt.x - collectionView.contentOffset.x, y: pt.y - collectionView.contentOffset.y)
        if let ip = indexPathNearPoint(pt) { applyDragRange(to: ip.item) }
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
            delta = -max(3, 14 * min(1, (edge - dragPointInViewport.y) / edge))
        } else if dragPointInViewport.y > (h - edge) {
            delta = max(3, 14 * min(1, (dragPointInViewport.y - (h - edge)) / edge))
        }
        guard delta != 0 else { return }
        let minOffsetY: CGFloat
        let maxOffsetY: CGFloat
        if #available(iOS 11.0, *) {
            minOffsetY = -collectionView.adjustedContentInset.top
            maxOffsetY = max(minOffsetY, collectionView.contentSize.height - h + collectionView.adjustedContentInset.bottom)
        } else {
            minOffsetY = 0
            maxOffsetY = max(0, collectionView.contentSize.height - h)
        }
        let nextY = min(maxOffsetY, max(minOffsetY, collectionView.contentOffset.y + delta))
        if abs(nextY - collectionView.contentOffset.y) > 0.2 {
            collectionView.contentOffset.y = nextY
            let contentPt = CGPoint(x: dragPointInViewport.x + collectionView.contentOffset.x, y: dragPointInViewport.y + collectionView.contentOffset.y)
            if let ip = indexPathNearPoint(contentPt) { applyDragRange(to: ip.item) }
        }
    }

    private func indexPathNearPoint(_ rawPt: CGPoint) -> IndexPath? {
        let b = collectionView.bounds
        let x = min(max(rawPt.x, b.minX + 4), max(b.minX + 4, b.maxX - 4))
        let y = min(max(rawPt.y, b.minY + 4), max(b.minY + 4, b.maxY - 4))
        let clamped = CGPoint(x: x, y: y)
        if let ip = collectionView.indexPathForItem(at: clamped) { return ip }
        for (dx, dy) in [(-4 as CGFloat, 0 as CGFloat), (4, 0), (0, -4), (0, 4)] {
            if let ip = collectionView.indexPathForItem(at: CGPoint(x: clamped.x + dx, y: clamped.y + dy)) { return ip }
        }
        return nil
    }

    private func applyDragRange(to endIdx: Int) {
        guard let startIdx = dragStartIndex, endIdx >= 0, endIdx < assets.count, dragLastIndex != endIdx else { return }
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
        for case let cell as GalleryThumbCell in collectionView.visibleCells {
            guard let ip = collectionView.indexPath(for: cell), ip.item < assets.count else { continue }
            let id = assets[ip.item].localIdentifier
            let isLive = (assets[ip.item].mediaSubtypes.rawValue & PHAssetMediaSubtype.photoLive.rawValue) != 0
            cell.configure(index: selectedIds.firstIndex(of: id).map { $0 + 1 }, isLive: isLive)
        }
    }

    private func endDragSelect() {
        dragAutoScrollLink?.invalidate()
        dragAutoScrollLink = nil
        dragStartIndex = nil
        dragLastIndex = nil
        dragBaselineIds.removeAll()
        collectionView.isScrollEnabled = true
        if selectedIds.isEmpty { selectionMode = false }
        updateChrome()
        collectionView.reloadData()
    }

    // MARK: - Permissions & Fetching
    @objc private func requestAccessTapped() {
        if #available(iOS 14, *) {
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { [weak self] status in
                DispatchQueue.main.async {
                    if status == .authorized || status == .limited {
                        self?.showAuthorizedGallery()
                    }
                }
            }
        } else {
            PHPhotoLibrary.requestAuthorization { [weak self] status in
                DispatchQueue.main.async {
                    if status == .authorized {
                        self?.showAuthorizedGallery()
                    }
                }
            }
        }
    }

    private func loadGallery() {
        let authorized: Bool
        if #available(iOS 14, *) {
            let s = PHPhotoLibrary.authorizationStatus(for: .readWrite)
            if s == .notDetermined {
                permissionCard.isHidden = false
                collectionView.isHidden = true
                return
            }
            authorized = (s == .authorized || s == .limited)
        } else {
            let s = PHPhotoLibrary.authorizationStatus()
            if s == .notDetermined {
                permissionCard.isHidden = false
                collectionView.isHidden = true
                return
            }
            authorized = (s == .authorized)
        }
        permissionCard.isHidden = authorized
        collectionView.isHidden = !authorized
        if authorized {
            fetchPhotoAssets()
        }
    }

    private func showAuthorizedGallery() {
        permissionCard.isHidden = true
        collectionView.isHidden = false
        fetchPhotoAssets()
    }

    private func fetchPhotoAssets() {
        let opts = PHFetchOptions()
        opts.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        opts.fetchLimit = 500
        let res = PHAsset.fetchAssets(with: .image, options: opts)
        var list: [PHAsset] = []
        res.enumerateObjects { asset, _, _ in list.append(asset) }
        self.assets = list
        self.updateChrome()
        self.collectionView.reloadData()
    }

    // MARK: - Effects & Actions
    @objc private func showEffectPicker() {
        let pct = (AutoLivePreferences.motionRatio - 1.0) * 100.0
        let alert = UIAlertController(
            title: "实况效果（与工作区共用）",
            message: String(format: "当前幅度 %.1f%% · %.1f 秒", pct, AutoLivePreferences.motionDuration),
            preferredStyle: .actionSheet
        )
        let cur = AutoLivePreferences.motionMode
        for mode in AutoLiveMotionMode.allCases {
            alert.addAction(UIAlertAction(title: mode.title + (mode == cur ? "  ✓" : ""), style: .default) { [weak self] _ in
                AutoLivePreferences.motionMode = mode
                self?.updateEffectButtonTitle()
            })
        }
        alert.addAction(UIAlertAction(title: "调整微动幅度与时长…", style: .default) { [weak self] _ in
            self?.showStrengthPicker()
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let pop = alert.popoverPresentationController {
            pop.sourceView = effectButton
            pop.sourceRect = effectButton.bounds
        }
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
            let isCur = abs(AutoLivePreferences.motionRatio - ratio) < 0.002
            alert.addAction(UIAlertAction(title: title + (isCur ? "  ✓" : ""), style: .default) { _ in
                AutoLivePreferences.motionRatio = ratio
                AutoLivePreferences.motionDuration = dur
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let pop = alert.popoverPresentationController {
            pop.sourceView = effectButton
            pop.sourceRect = effectButton.bounds
        }
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

    @objc private func shareRawTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        requestImages(targets) { [weak self] images in
            guard let self = self, !images.isEmpty else { return }
            let ac = UIActivityViewController(activityItems: images, applicationActivities: nil)
            ac.popoverPresentationController?.sourceView = self.rawButton
            self.present(ac, animated: true)
        }
    }

    @objc private func saveOnlyTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        convertSelectedAssets(targets, shareAfter: false)
    }

    @objc private func shareTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        convertSelectedAssets(targets, shareAfter: true)
    }

    private func requestImages(_ targets: [PHAsset], completion: @escaping ([UIImage]) -> Void) {
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.isNetworkAccessAllowed = true
        opts.isSynchronous = false
        var list: [(Int, UIImage)] = []
        let lock = NSLock()
        let group = DispatchGroup()
        for (idx, asset) in targets.enumerated() {
            group.enter()
            imageManager.requestImage(
                for: asset,
                targetSize: CGSize(width: 2400, height: 2400),
                contentMode: .aspectFit,
                options: opts
            ) { img, _ in
                if let img = img {
                    lock.lock()
                    list.append((idx, img))
                    lock.unlock()
                }
                group.leave()
            }
        }
        group.notify(queue: .main) {
            completion(list.sorted { $0.0 < $1.0 }.map { $0.1 })
        }
    }

    private func convertSelectedAssets(_ targets: [PHAsset], shareAfter: Bool) {
        guard !targets.isEmpty else { return }
        let skip = AutoLivePreferences.skipExistingLive
        let isLive: (PHAsset) -> Bool = { ($0.mediaSubtypes.rawValue & PHAssetMediaSubtype.photoLive.rawValue) != 0 }
        let staticTargets = skip ? targets.filter { !isLive($0) } : targets
        let liveTargets = skip ? targets.filter { isLive($0) } : []

        if staticTargets.isEmpty && !liveTargets.isEmpty && !shareAfter {
            let a = UIAlertController(title: "无需重复转换", message: "所选照片已是实况照片。", preferredStyle: .alert)
            a.addAction(UIAlertAction(title: "好的", style: .default))
            present(a, animated: true)
            return
        }

        let fetchTargets = staticTargets.isEmpty ? liveTargets : staticTargets
        requestImages(fetchTargets) { [weak self] images in
            guard let self = self, !images.isEmpty else { return }
            self.convertImages(images, shareAfter: shareAfter)
        }
    }

    private func convertImages(_ images: [UIImage], shareAfter: Bool) {
        let mode = AutoLivePreferences.motionMode
        let ratio = AutoLivePreferences.motionRatio
        let dur = AutoLivePreferences.motionDuration
        let loading = UIAlertController(title: "正在手机本地 1440P 生成实况", message: "请稍候…", preferredStyle: .alert)
        present(loading, animated: true)

        DispatchQueue.global(qos: .userInitiated).async {
            var pairs: [LivePhotoPair] = []
            var firstErr: Error?
            for img in images {
                do {
                    let pair = try OnDeviceLivePhotoEngine.shared.convertAndSaveToPhotos(
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
                        ac.popoverPresentationController?.sourceView = self.shareButton
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

    // MARK: - UICollectionView
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return assets.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "GalleryThumbCell", for: indexPath) as! GalleryThumbCell
        let asset = assets[indexPath.item]
        let idx = selectedIds.firstIndex(of: asset.localIdentifier)
        let isLive = (asset.mediaSubtypes.rawValue & PHAssetMediaSubtype.photoLive.rawValue) != 0
        cell.configure(index: idx.map { $0 + 1 }, isLive: isLive)
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
        let preview = GalleryPhotoPreviewVC(asset: asset, imageManager: imageManager)
        preview.onAction = { [weak self] action in
            guard let self = self else { return }
            switch action {
            case .delete:
                self.deleteAssets([asset]) {}
            case .shareRaw:
                self.requestImages([asset]) { imgs in
                    guard let img = imgs.first else { return }
                    let ac = UIActivityViewController(activityItems: [img], applicationActivities: nil)
                    ac.popoverPresentationController?.sourceView = self.view
                    self.present(ac, animated: true)
                }
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

private enum GalleryPreviewAction {
    case delete, shareRaw, saveLive, shareLive
}

private final class GalleryPhotoPreviewVC: UIViewController {
    private let asset: PHAsset
    private let imageManager: PHCachingImageManager
    var onAction: ((GalleryPreviewAction) -> Void)?

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
        bar.spacing = 8
        bar.distribution = .fillEqually
        bar.translatesAutoresizingMaskIntoConstraints = false

        let delBtn = makeBarButton("删除", bg: UIColor(white: 0.2, alpha: 1), fg: .systemRed)
        delBtn.addTarget(self, action: #selector(delTapped), for: .touchUpInside)
        let rawBtn = makeBarButton("原图", bg: UIColor(white: 0.2, alpha: 1), fg: .white)
        rawBtn.addTarget(self, action: #selector(rawTapped), for: .touchUpInside)
        let saveBtn = makeBarButton("存实况", bg: UIColor(white: 0.2, alpha: 1), fg: .white)
        saveBtn.addTarget(self, action: #selector(saveTapped), for: .touchUpInside)
        let shareBtn = makeBarButton("分享实况", bg: AppColors.brandLightText, fg: .white)
        shareBtn.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        [delBtn, rawBtn, saveBtn, shareBtn].forEach { bar.addArrangedSubview($0) }
        view.addSubview(bar)

        NSLayoutConstraint.activate([
            closeBtn.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            closeBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),

            iv.topAnchor.constraint(equalTo: closeBtn.bottomAnchor, constant: 8),
            iv.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            iv.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            iv.bottomAnchor.constraint(equalTo: bar.topAnchor, constant: -12),

            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
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
        b.titleLabel?.font = .systemFont(ofSize: 14, weight: .semibold)
        b.layer.cornerRadius = 10
        return b
    }

    @objc private func closeTapped() { dismiss(animated: true) }
    @objc private func delTapped() { dismiss(animated: true) { self.onAction?(.delete) } }
    @objc private func rawTapped() { dismiss(animated: true) { self.onAction?(.shareRaw) } }
    @objc private func saveTapped() { dismiss(animated: true) { self.onAction?(.saveLive) } }
    @objc private func shareTapped() { dismiss(animated: true) { self.onAction?(.shareLive) } }
}

private final class GalleryThumbCell: UICollectionViewCell {
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
            badgeBtn.backgroundColor = AppColors.brandLightText
            badgeBtn.layer.borderWidth = 0
            contentView.layer.borderWidth = 2.5
            contentView.layer.borderColor = AppColors.brandLightText.cgColor
        } else {
            badgeBtn.setTitle("", for: .normal)
            badgeBtn.backgroundColor = UIColor.black.withAlphaComponent(0.22)
            badgeBtn.layer.borderWidth = 1.2
            badgeBtn.layer.borderColor = UIColor.white.withAlphaComponent(0.85).cgColor
            contentView.layer.borderWidth = 0
        }
    }
}
