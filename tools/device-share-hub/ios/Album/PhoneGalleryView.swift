import Photos
import UIKit

/// Native Phone Gallery mode embedded inside Album (iOS 12.0+ safe).
final class PhoneGalleryViewController: UIViewController,
    UICollectionViewDataSource,
    UICollectionViewDelegateFlowLayout,
    UIGestureRecognizerDelegate {

    private static let maxShareLiveCount = 18

    private let imageManager = PHCachingImageManager()
    private var assets: [PHAsset] = []
    private var selectedIds: [String] = []
    private var selectedCollection: PHAssetCollection?
    private var selectedCollectionTitle: String = "全部照片"
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
    private let bucketButton = UIButton(type: .system)
    private let statusChipLabel = UILabel()
    private let liveStatusButton = UIButton(type: .system)
    private let multiSelectButton = UIButton(type: .system)
    private let liveQuickMenu = LiveQuickMenu()

    private let scrollDatePill = UILabel()
    private var hideDatePillWorkItem: DispatchWorkItem?

    private let permissionCard = UIView()
    private let bottomDock = SlidingGlassBar(dockMode: true)

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
        longPress.minimumPressDuration = 0.26
        longPress.allowableMovement = 18
        collectionView.addGestureRecognizer(longPress)

        panSelectGesture = UIPanGestureRecognizer(target: self, action: #selector(handleSelectionPan(_:)))
        panSelectGesture.delegate = self
        collectionView.addGestureRecognizer(panSelectGesture)

        updateChrome()
    }

    func onShown() {
        refreshAppearance()
        loadGallery()
    }

    @objc private func pullToRefreshGallery() {
        refreshGallery()
        collectionView.refreshControl?.endRefreshing()
    }

    func refreshGallery() {
        refreshAppearance()
        loadGallery()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        refreshAppearance()
    }

    func refreshAppearance() {
        updateLiveButtonTitle()
        let isDark: Bool
        if #available(iOS 12.0, *) {
            isDark = traitCollection.userInterfaceStyle == .dark
        } else {
            isDark = false
        }
        bottomDock.applyAppearance(
            darkMode: isDark,
            glassStyle: AppThemePreferences.isFlClashGlass,
            accentColor: AppColors.brandLightText
        )
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
        statusChipLabel.text = ""
        statusChipLabel.isHidden = true

        bucketButton.translatesAutoresizingMaskIntoConstraints = false
        bucketButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .semibold)
        bucketButton.setTitleColor(AppColors.text, for: .normal)
        bucketButton.backgroundColor = AppColors.secondaryBackground
        bucketButton.layer.cornerRadius = 13
        bucketButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 10, bottom: 5, right: 10)
        bucketButton.setTitle("全部照片 ▾", for: .normal)
        bucketButton.addTarget(self, action: #selector(showBucketPicker), for: .touchUpInside)

        liveStatusButton.translatesAutoresizingMaskIntoConstraints = false
        liveStatusButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .semibold)
        liveStatusButton.layer.cornerRadius = 13
        liveStatusButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 10, bottom: 5, right: 10)
        liveStatusButton.accessibilityLabel = "实况直出开关，点击切换开关，长按滑选效果"
        liveStatusButton.addTarget(self, action: #selector(liveStatusTapped), for: .touchUpInside)
        let liveHold = UILongPressGestureRecognizer(target: self, action: #selector(liveStatusLongPressed(_:)))
        liveHold.minimumPressDuration = 0.35
        liveStatusButton.addGestureRecognizer(liveHold)
        updateLiveButtonTitle()

        multiSelectButton.translatesAutoresizingMaskIntoConstraints = false
        multiSelectButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .semibold)
        multiSelectButton.setTitleColor(AppColors.brandLightText, for: .normal)
        multiSelectButton.backgroundColor = AppColors.brandLightBg
        multiSelectButton.layer.cornerRadius = 13
        multiSelectButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 10, bottom: 5, right: 10)
        multiSelectButton.addTarget(self, action: #selector(rightCtrlTapped), for: .touchUpInside)

        scrollDatePill.translatesAutoresizingMaskIntoConstraints = false
        scrollDatePill.font = .systemFont(ofSize: 12, weight: .bold)
        scrollDatePill.textColor = .white
        scrollDatePill.backgroundColor = UIColor(red: 24/255, green: 26/255, blue: 31/255, alpha: 0.82)
        scrollDatePill.layer.cornerRadius = 12
        scrollDatePill.clipsToBounds = true
        scrollDatePill.textAlignment = .center
        scrollDatePill.alpha = 0
        scrollDatePill.isHidden = true

        headerBar.addSubview(cancelSelButton)
        headerBar.addSubview(bucketButton)
        headerBar.addSubview(statusChipLabel)
        headerBar.addSubview(liveStatusButton)
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

        // Floating Elliptical Translucent Glass Dock (FlClash style)
        bottomDock.translatesAutoresizingMaskIntoConstraints = false
        bottomDock.setSegments([
            SlidingGlassBar.Segment(title: "删除", accessibilityLabel: "删除选中照片", isDestructive: true),
            SlidingGlassBar.Segment(title: "保存实况", accessibilityLabel: "保存实况到相册"),
            SlidingGlassBar.Segment(title: "原图", accessibilityLabel: "分享原图"),
            SlidingGlassBar.Segment(title: "分享实况", accessibilityLabel: "转换并分享实况", isAccent: true)
        ], selected: 3)
        bottomDock.onSelect = { [weak self] idx, _ in
            guard let self = self, !self.selectedIds.isEmpty else { return }
            switch idx {
            case 0: self.deleteSelectedTapped()
            case 1: self.saveOnlyTapped()
            case 2: self.shareRawTapped()
            case 3: self.shareTapped()
            default: break
            }
        }
        bottomDock.isHidden = true

        let refreshControl = UIRefreshControl()
        refreshControl.addTarget(self, action: #selector(pullToRefreshGallery), for: .valueChanged)
        collectionView.refreshControl = refreshControl
        collectionView.alwaysBounceVertical = true
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(headerBar)
        view.addSubview(collectionView)
        view.addSubview(scrollDatePill)
        view.addSubview(permissionCard)
        view.addSubview(bottomDock)

        NSLayoutConstraint.activate([
            headerBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 4),
            headerBar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            headerBar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            headerBar.heightAnchor.constraint(equalToConstant: 36),

            cancelSelButton.leadingAnchor.constraint(equalTo: headerBar.leadingAnchor),
            cancelSelButton.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            bucketButton.leadingAnchor.constraint(equalTo: headerBar.leadingAnchor),
            bucketButton.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),
            bucketButton.trailingAnchor.constraint(lessThanOrEqualTo: liveStatusButton.leadingAnchor, constant: -6),

            statusChipLabel.leadingAnchor.constraint(equalTo: cancelSelButton.trailingAnchor, constant: 8),
            statusChipLabel.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            multiSelectButton.trailingAnchor.constraint(equalTo: headerBar.trailingAnchor),
            multiSelectButton.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            liveStatusButton.trailingAnchor.constraint(equalTo: multiSelectButton.leadingAnchor, constant: -6),
            liveStatusButton.centerYAnchor.constraint(equalTo: headerBar.centerYAnchor),

            scrollDatePill.topAnchor.constraint(equalTo: collectionView.topAnchor, constant: 8),
            scrollDatePill.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            scrollDatePill.heightAnchor.constraint(equalToConstant: 24),
            scrollDatePill.widthAnchor.constraint(greaterThanOrEqualToConstant: 64),

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
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            bottomDock.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 14),
            bottomDock.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),
            bottomDock.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            bottomDock.heightAnchor.constraint(equalToConstant: 52)
        ])
    }

    private func updateLiveButtonTitle() {
        if AutoLivePreferences.isEnabled {
            liveStatusButton.setTitle("● LIVE 开", for: .normal)
            liveStatusButton.setTitleColor(AppColors.brandLightText, for: .normal)
            liveStatusButton.backgroundColor = AppColors.brandLightBg
        } else {
            liveStatusButton.setTitle("LIVE 关", for: .normal)
            liveStatusButton.setTitleColor(AppColors.secondaryText, for: .normal)
            liveStatusButton.backgroundColor = AppColors.secondaryBackground
        }
    }

    private func updateChrome() {
        let isDark: Bool
        if #available(iOS 12.0, *) {
            isDark = traitCollection.userInterfaceStyle == .dark
        } else {
            isDark = false
        }
        bottomDock.applyAppearance(
            darkMode: isDark,
            glassStyle: AppThemePreferences.isFlClashGlass,
            accentColor: AppColors.brandLightText
        )
        if selectionMode {
            let count = selectedIds.count
            cancelSelButton.isHidden = false
            bucketButton.isHidden = true
            statusChipLabel.isHidden = false
            statusChipLabel.text = "已选 \(count) 张"
            multiSelectButton.setTitle((count >= assets.count && !assets.isEmpty) ? "取消全选" : "全选", for: .normal)
            bottomDock.isHidden = false
            collectionView.contentInset.bottom = 84
            collectionView.scrollIndicatorInsets.bottom = 84
            let enabled = count > 0
            for idx in 0..<4 { bottomDock.setSegmentEnabled(idx, enabled: enabled) }
        } else {
            cancelSelButton.isHidden = true
            bucketButton.isHidden = false
            statusChipLabel.isHidden = true
            let countSuffix = assets.isEmpty ? "" : " (\(assets.count))"
            bucketButton.setTitle("\(selectedCollectionTitle)\(countSuffix) ▾", for: .normal)
            multiSelectButton.setTitle("多选", for: .normal)
            bottomDock.isHidden = true
            collectionView.contentInset.bottom = 8
            collectionView.scrollIndicatorInsets.bottom = 8
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
            if selectedIds.count >= assets.count && !assets.isEmpty {
                selectedIds.removeAll()
            } else {
                selectedIds = assets.map { $0.localIdentifier }
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
            return abs(vel.x) > abs(vel.y) * 0.55
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
        opts.fetchLimit = 800
        let res: PHFetchResult<PHAsset>
        if let col = selectedCollection {
            opts.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
            res = PHAsset.fetchAssets(in: col, options: opts)
        } else {
            res = PHAsset.fetchAssets(with: .image, options: opts)
        }
        var list: [PHAsset] = []
        res.enumerateObjects { asset, _, _ in list.append(asset) }
        self.assets = list
        self.updateChrome()
        self.collectionView.reloadData()
    }

    @objc private func showBucketPicker() {
        guard !selectionMode else { return }
        let countOpts = PHFetchOptions()
        countOpts.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        let allCount = PHAsset.fetchAssets(with: .image, options: nil).count

        var choices: [(title: String, col: PHAssetCollection?, count: Int)] = [
            ("全部照片", nil, allCount)
        ]
        let smartSubtypes: [PHAssetCollectionSubtype] = [
            .smartAlbumUserLibrary,
            .smartAlbumFavorites,
            .smartAlbumLivePhotos,
            .smartAlbumScreenshots,
            .smartAlbumSelfPortraits
        ]
        for st in smartSubtypes {
            let fetched = PHAssetCollection.fetchAssetCollections(with: .smartAlbum, subtype: st, options: nil)
            fetched.enumerateObjects { col, _, _ in
                let cnt = PHAsset.fetchAssets(in: col, options: countOpts).count
                if cnt > 0, let name = col.localizedTitle, !name.isEmpty {
                    if !choices.contains(where: { $0.col?.localIdentifier == col.localIdentifier }) {
                        choices.append((name, col, cnt))
                    }
                }
            }
        }
        let userAlbums = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: nil)
        userAlbums.enumerateObjects { col, _, _ in
            let cnt = PHAsset.fetchAssets(in: col, options: countOpts).count
            if cnt > 0, let name = col.localizedTitle, !name.isEmpty {
                choices.append((name, col, cnt))
            }
        }

        let alert = UIAlertController(title: "选择手机相册", message: nil, preferredStyle: .actionSheet)
        for item in choices {
            let isCurrent = (item.col == nil && selectedCollection == nil)
                || (item.col != nil && item.col?.localIdentifier == selectedCollection?.localIdentifier)
            let label = "\(item.title)（\(item.count)）" + (isCurrent ? "  ✓" : "")
            alert.addAction(UIAlertAction(title: label, style: .default) { [weak self] _ in
                guard let self = self else { return }
                self.selectedCollection = item.col
                self.selectedCollectionTitle = item.title
                self.fetchPhotoAssets()
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let pop = alert.popoverPresentationController {
            pop.sourceView = bucketButton
            pop.sourceRect = bucketButton.bounds
        }
        present(alert, animated: true)
    }

    // MARK: - Effects & Actions
    override func viewWillDisappear(_ animated: Bool) {
        liveQuickMenu.dismiss()
        super.viewWillDisappear(animated)
    }

    @objc private func liveStatusTapped() {
        AutoLivePreferences.isEnabled.toggle()
        updateLiveButtonTitle()
    }

    @objc private func liveStatusLongPressed(_ gesture: UILongPressGestureRecognizer) {
        let modes = AutoLiveMotionMode.allCases
        let curTier = AutoLivePreferences.qualityTier
        let labels = modes.map { $0.title } + ["动态视频画质：\(curTier.shortLabel)…", "调整微动幅度与时长…", "关闭实况直出"]
        let selected = AutoLivePreferences.isEnabled ? (modes.firstIndex(of: AutoLivePreferences.motionMode) ?? 0) : (modes.count + 2)
        liveQuickMenu.handle(gesture, labels: labels, selected: selected) { [weak self] which in
            guard let self = self else { return }
            if which < modes.count {
                AutoLivePreferences.motionMode = modes[which]
                AutoLivePreferences.isEnabled = true
                self.updateLiveButtonTitle()
            } else if which == modes.count {
                self.showQualityTierPicker()
            } else if which == modes.count + 1 {
                self.showStrengthPicker()
            } else {
                AutoLivePreferences.isEnabled = false
                self.updateLiveButtonTitle()
            }
        }
    }

    private func showQualityTierPicker() {
        let tiers = LiveQualityTier.allCases
        let cur = AutoLivePreferences.qualityTier
        let labels = tiers.map { "\($0.title)（\($0.shortLabel)）" }
        liveQuickMenu.show(anchor: liveStatusButton, labels: labels, selected: tiers.firstIndex(of: cur) ?? 0) { idx in
            AutoLivePreferences.qualityTier = tiers[idx]
        }
    }

    private func showStrengthPicker() {
        let options: [(String, Double, Double)] = AutoLivePreferences.motionMode == .pushDrift ? [
            ("柔和（5.8% · 2.0秒）", 1.058, 2.0),
            ("参考原版（11.6% · 2.0秒）", 1.116, 2.0),
            ("明显（12.0% · 2.2秒）", 1.120, 2.2)
        ] : [
            ("极微自然（1.0% · 1.3秒）", 1.010, 1.3),
            ("标准推荐（1.8% · 1.6秒）", 1.018, 1.6),
            ("轻柔明显（3.2% · 2.2秒）", 1.032, 2.2)
        ]
        let selected = options.firstIndex { abs(AutoLivePreferences.motionRatio - $0.1) < 0.002 } ?? 1
        liveQuickMenu.show(anchor: liveStatusButton, labels: options.map { $0.0 }, selected: selected) { idx in
            AutoLivePreferences.motionRatio = options[idx].1
            AutoLivePreferences.motionDuration = options[idx].2
        }
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

    private func confirmWithinShareLimit(_ targets: [PHAsset], actionTitle: String, proceed: @escaping ([PHAsset]) -> Void) {
        guard !targets.isEmpty else { return }
        if targets.count <= Self.maxShareLiveCount {
            proceed(targets)
            return
        }
        let alert = UIAlertController(
            title: "单次\(actionTitle)上限 \(Self.maxShareLiveCount) 张",
            message: "当前已选 \(targets.count) 张（批量删除无张数限制）。\(actionTitle)建议单次最多 \(Self.maxShareLiveCount) 张，是否只处理前 \(Self.maxShareLiveCount) 张？",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "仅处理前 \(Self.maxShareLiveCount) 张", style: .default) { _ in
            proceed(Array(targets.prefix(Self.maxShareLiveCount)))
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(alert, animated: true)
    }

    @objc private func shareRawTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        confirmWithinShareLimit(targets, actionTitle: "分享原图") { [weak self] chosen in
            self?.requestImages(chosen) { [weak self] images in
                guard let self = self, !images.isEmpty else { return }
                let ac = UIActivityViewController(activityItems: images, applicationActivities: nil)
                ac.popoverPresentationController?.sourceView = self.bottomDock
                self.present(ac, animated: true)
            }
        }
    }

    @objc private func saveOnlyTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        confirmWithinShareLimit(targets, actionTitle: "保存实况") { [weak self] chosen in
            self?.convertSelectedAssets(chosen, shareAfter: false)
        }
    }

    @objc private func shareTapped() {
        let targets = assets.filter { selectedIds.contains($0.localIdentifier) }
        confirmWithinShareLimit(targets, actionTitle: "分享实况") { [weak self] chosen in
            self?.convertSelectedAssets(chosen, shareAfter: true)
        }
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
        let mode = AutoLivePreferences.motionMode
        let tier = AutoLivePreferences.qualityTier
        let ratio = AutoLivePreferences.motionRatio
        let dur = AutoLivePreferences.motionDuration
        let loading = UIAlertController(
            title: "正在手机本地生成实况",
            message: "静态原画无损 · \(tier.shortLabel)…",
            preferredStyle: .alert
        )
        present(loading, animated: true)

        requestRawDatas(for: fetchTargets) { [weak self] resolved, failedDataAssets in
            DispatchQueue.global(qos: .userInitiated).async {
                let enginePref = LiveEnginePreference.preferred
                var usePcAi = false
                if enginePref != .localOnly {
                    let st = OnlineGalleryClient.shared.probeLiveEngineStatusSync(forceRefresh: false)
                    if enginePref == .flowFirst && st.reachable && st.flowReady {
                        usePcAi = true
                    } else if enginePref == .zhipuFirst && st.reachable && st.zhipuReady {
                        usePcAi = true
                    }
                }
                var results: [LiveConvertResult] = []
                var failedAssets: [PHAsset] = failedDataAssets
                var firstErr: Error?
                for (idx, pair) in resolved.enumerated() {
                    let asset = pair.0
                    let data = pair.1
                    do {
                        var res: LiveConvertResult?
                        if usePcAi,
                           let mp4 = OnlineGalleryClient.shared.requestPcAiLiveConvertSync(
                               imageData: data,
                               fileName: "photo_\(idx + 1).jpg",
                               preference: enginePref,
                               mode: mode.rawValue,
                               duration: dur
                           ) {
                            res = try OnDeviceLivePhotoEngine.shared.convertWithExternalVideoToPhotos(
                                sourceData: data,
                                mp4Data: mp4
                            )
                        }
                        if res == nil {
                            res = try OnDeviceLivePhotoEngine.shared.convertAndSaveDataToPhotos(
                                sourceData: data,
                                preset: mode,
                                qualityTier: tier,
                                ratio: ratio,
                                duration: dur
                            )
                        }
                        if let done = res {
                            results.append(done)
                        }
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
                                    ac.popoverPresentationController?.sourceView = self.bottomDock
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
                            ac.popoverPresentationController?.sourceView = self.bottomDock
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
        } else {
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
        let preview = GalleryPhotoPreviewVC(assets: assets, initialIndex: indexPath.item, imageManager: imageManager)
        preview.onLongPressSelect = { [weak self] activeAsset in
            guard let self = self else { return }
            self.selectionMode = true
            let id = activeAsset.localIdentifier
            if !self.selectedIds.contains(id) {
                self.selectedIds.append(id)
            }
            self.updateChrome()
            self.collectionView.reloadData()
        }
        preview.onAction = { [weak self] action, activeAsset in
            guard let self = self else { return }
            switch action {
            case .delete:
                self.deleteAssets([activeAsset]) {}
            case .shareRaw:
                self.requestImages([activeAsset]) { imgs in
                    guard let img = imgs.first else { return }
                    let ac = UIActivityViewController(activityItems: [img], applicationActivities: nil)
                    ac.popoverPresentationController?.sourceView = self.view
                    self.present(ac, animated: true)
                }
            case .saveLive:
                self.convertSelectedAssets([activeAsset], shareAfter: false)
            case .shareLive:
                self.convertSelectedAssets([activeAsset], shareAfter: true)
            }
        }
        preview.modalPresentationStyle = .fullScreen
        present(preview, animated: true)
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        let side = floor((collectionView.bounds.width - 4) / 3.0)
        return CGSize(width: side, height: side)
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        guard scrollView === collectionView, (scrollView.isDragging || scrollView.isDecelerating) else { return }
        let sorted = collectionView.indexPathsForVisibleItems.sorted { $0.item < $1.item }
        guard let first = sorted.first, first.item < assets.count else { return }
        let dateStr = formatGalleryDate(assets[first.item].creationDate)
        guard !dateStr.isEmpty else { return }
        hideDatePillWorkItem?.cancel()
        scrollDatePill.text = "  \(dateStr)  "
        if scrollDatePill.isHidden || scrollDatePill.alpha < 0.95 {
            scrollDatePill.isHidden = false
            UIView.animate(withDuration: 0.12) { self.scrollDatePill.alpha = 1 }
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        scheduleHideDatePill()
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate { scheduleHideDatePill() }
    }

    private func scheduleHideDatePill() {
        hideDatePillWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            UIView.animate(withDuration: 0.2, animations: {
                self.scrollDatePill.alpha = 0
            }) { _ in
                if self.scrollDatePill.alpha < 0.05 {
                    self.scrollDatePill.isHidden = true
                }
            }
        }
        hideDatePillWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.85, execute: item)
    }
}

private func formatGalleryDate(_ date: Date?) -> String {
    guard let date = date else { return "" }
    let cal = Calendar.current
    if cal.isDateInToday(date) { return "今天" }
    if cal.isDateInYesterday(date) { return "昨天" }
    let df = DateFormatter()
    df.locale = Locale(identifier: "zh_CN")
    let sameYear = cal.component(.year, from: date) == cal.component(.year, from: Date())
    df.dateFormat = sameYear ? "M月d日" : "yyyy年M月d日"
    return df.string(from: date)
}

private enum GalleryPreviewAction {
    case delete, shareRaw, saveLive, shareLive
}

private final class GalleryPhotoPreviewVC: UIViewController,
    UICollectionViewDataSource,
    UICollectionViewDelegateFlowLayout,
    UIGestureRecognizerDelegate {

    private let assets: [PHAsset]
    private var currentIndex: Int
    private let imageManager: PHCachingImageManager
    var onAction: ((GalleryPreviewAction, PHAsset) -> Void)?
    var onLongPressSelect: ((PHAsset) -> Void)?

    private let counterLabel = UILabel()
    private let liveToggleBtn = UIButton(type: .system)
    private var userToggledLive = false
    private var livePreviewOn = false
    private var liveDisplayLink: CADisplayLink?
    private var liveAnimStart: CFTimeInterval = 0
    private var didScrollInitial = false

    private var pagerView: UICollectionView!
    private var bottomBar: UIStackView!
    private var dismissPanGesture: UIPanGestureRecognizer!

    init(assets: [PHAsset], initialIndex: Int, imageManager: PHCachingImageManager) {
        self.assets = assets
        self.currentIndex = max(0, min(initialIndex, max(0, assets.count - 1)))
        self.imageManager = imageManager
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError() }

    deinit {
        liveDisplayLink?.invalidate()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        counterLabel.translatesAutoresizingMaskIntoConstraints = false
        counterLabel.font = .systemFont(ofSize: 14, weight: .bold)
        counterLabel.textColor = .white

        liveToggleBtn.translatesAutoresizingMaskIntoConstraints = false
        liveToggleBtn.titleLabel?.font = .systemFont(ofSize: 11, weight: .bold)
        liveToggleBtn.layer.cornerRadius = 12
        liveToggleBtn.clipsToBounds = true
        liveToggleBtn.contentEdgeInsets = UIEdgeInsets(top: 5, left: 10, bottom: 5, right: 10)
        liveToggleBtn.addTarget(self, action: #selector(liveToggleTapped), for: .touchUpInside)

        let closeBtn = UIButton(type: .system)
        closeBtn.setTitle("✕", for: .normal)
        closeBtn.setTitleColor(.white, for: .normal)
        closeBtn.titleLabel?.font = .systemFont(ofSize: 18, weight: .bold)
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)

        view.addSubview(counterLabel)
        view.addSubview(liveToggleBtn)
        view.addSubview(closeBtn)

        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .horizontal
        layout.minimumLineSpacing = 0
        layout.minimumInteritemSpacing = 0
        pagerView = UICollectionView(frame: .zero, collectionViewLayout: layout)
        pagerView.translatesAutoresizingMaskIntoConstraints = false
        pagerView.backgroundColor = .black
        pagerView.isPagingEnabled = true
        pagerView.showsHorizontalScrollIndicator = false
        pagerView.dataSource = self
        pagerView.delegate = self
        pagerView.register(GalleryPreviewPageCell.self, forCellWithReuseIdentifier: "PreviewPage")
        view.addSubview(pagerView)

        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(handlePreviewLongPress(_:)))
        longPress.minimumPressDuration = 0.42
        pagerView.addGestureRecognizer(longPress)

        dismissPanGesture = UIPanGestureRecognizer(target: self, action: #selector(handleDismissPan(_:)))
        dismissPanGesture.delegate = self
        view.addGestureRecognizer(dismissPanGesture)

        bottomBar = UIStackView()
        bottomBar.axis = .horizontal
        bottomBar.spacing = 8
        bottomBar.distribution = .fillEqually
        bottomBar.translatesAutoresizingMaskIntoConstraints = false

        let delBtn = makeBarButton("删除", bg: UIColor(white: 0.2, alpha: 1), fg: .systemRed)
        delBtn.addTarget(self, action: #selector(delTapped), for: .touchUpInside)
        let rawBtn = makeBarButton("原图", bg: UIColor(white: 0.2, alpha: 1), fg: .white)
        rawBtn.addTarget(self, action: #selector(rawTapped), for: .touchUpInside)
        let saveBtn = makeBarButton("存实况", bg: UIColor(white: 0.2, alpha: 1), fg: .white)
        saveBtn.addTarget(self, action: #selector(saveTapped), for: .touchUpInside)
        let shareBtn = makeBarButton("分享实况", bg: AppColors.brandLightText, fg: .white)
        shareBtn.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        [delBtn, rawBtn, saveBtn, shareBtn].forEach { bottomBar.addArrangedSubview($0) }
        view.addSubview(bottomBar)

        NSLayoutConstraint.activate([
            counterLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            counterLabel.centerYAnchor.constraint(equalTo: closeBtn.centerYAnchor),
            counterLabel.trailingAnchor.constraint(lessThanOrEqualTo: liveToggleBtn.leadingAnchor, constant: -8),

            liveToggleBtn.trailingAnchor.constraint(equalTo: closeBtn.leadingAnchor, constant: -10),
            liveToggleBtn.centerYAnchor.constraint(equalTo: closeBtn.centerYAnchor),

            closeBtn.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 6),
            closeBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -14),
            closeBtn.widthAnchor.constraint(greaterThanOrEqualToConstant: 32),

            pagerView.topAnchor.constraint(equalTo: closeBtn.bottomAnchor, constant: 6),
            pagerView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            pagerView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            pagerView.bottomAnchor.constraint(equalTo: bottomBar.topAnchor, constant: -10),

            bottomBar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 12),
            bottomBar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -12),
            bottomBar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12),
            bottomBar.heightAnchor.constraint(equalToConstant: 46)
        ])

        syncHeaderAndLiveState()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if !didScrollInitial && currentIndex < assets.count && pagerView.bounds.width > 0 {
            didScrollInitial = true
            let ip = IndexPath(item: currentIndex, section: 0)
            pagerView.scrollToItem(at: ip, at: .centeredHorizontally, animated: false)
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopLiveAnimation()
    }

    private func activeAsset() -> PHAsset? {
        guard currentIndex >= 0 && currentIndex < assets.count else { return nil }
        return assets[currentIndex]
    }

    private func syncHeaderAndLiveState() {
        guard let asset = activeAsset() else { return }
        let dStr = formatGalleryDate(asset.creationDate)
        counterLabel.text = dStr.isEmpty
            ? "\(currentIndex + 1) / \(assets.count)"
            : "\(currentIndex + 1) / \(assets.count) · \(dStr)"
        if !userToggledLive {
            let isLiveAsset = (asset.mediaSubtypes.rawValue & PHAssetMediaSubtype.photoLive.rawValue) != 0
            livePreviewOn = isLiveAsset || AutoLivePreferences.isEnabled
        }
        updateLiveMotionPlayback()
    }

    @objc private func liveToggleTapped() {
        userToggledLive = true
        livePreviewOn.toggle()
        updateLiveMotionPlayback()
    }

    private func updateLiveMotionPlayback() {
        if livePreviewOn {
            liveToggleBtn.setTitle("● LIVE 动图", for: .normal)
            liveToggleBtn.setTitleColor(UIColor(red: 167/255, green: 243/255, blue: 208/255, alpha: 1), for: .normal)
            liveToggleBtn.backgroundColor = UIColor(red: 6/255, green: 95/255, blue: 70/255, alpha: 0.75)
            startLiveAnimation()
        } else {
            liveToggleBtn.setTitle("LIVE 静图", for: .normal)
            liveToggleBtn.setTitleColor(UIColor(white: 0.85, alpha: 1), for: .normal)
            liveToggleBtn.backgroundColor = UIColor(white: 0.25, alpha: 0.7)
            stopLiveAnimation()
        }
    }

    private func startLiveAnimation() {
        stopLiveAnimation()
        liveAnimStart = CACurrentMediaTime()
        let link = CADisplayLink(target: self, selector: #selector(handleLiveTick))
        link.add(to: .main, forMode: .common)
        liveDisplayLink = link
    }

    private func stopLiveAnimation() {
        liveDisplayLink?.invalidate()
        liveDisplayLink = nil
        for case let cell as GalleryPreviewPageCell in pagerView.visibleCells {
            cell.clearLiveTransform()
        }
    }

    @objc private func handleLiveTick() {
        let dur = max(1.0, AutoLivePreferences.motionDuration)
        let elapsed = CACurrentMediaTime() - liveAnimStart
        let cycle = elapsed.truncatingRemainder(dividingBy: dur * 2.0) / dur
        let progress = cycle <= 1.0 ? cycle : (2.0 - cycle)
        let frame = Int(round(progress * 59.0))
        let baseRatio = AutoLivePreferences.motionRatio
        let previewRatio = baseRatio < 1.028 ? 1.028 : baseRatio
        let tf = AutoLivePreferences.motionMode.computeFrame(ratio: previewRatio, frameIndex: frame, totalFrames: 60)
        if let cell = pagerView.cellForItem(at: IndexPath(item: currentIndex, section: 0)) as? GalleryPreviewPageCell {
            cell.applyLiveTransform(zoom: CGFloat(tf.zoom), offsetX: CGFloat(tf.offsetX), offsetY: CGFloat(tf.offsetY))
        }
    }

    // MARK: - Pager & Swipe Dismiss
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return assets.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "PreviewPage", for: indexPath) as! GalleryPreviewPageCell
        let asset = assets[indexPath.item]
        cell.bind(asset: asset, imageManager: imageManager)
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        return collectionView.bounds.size
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        guard scrollView === pagerView, pagerView.bounds.width > 0 else { return }
        let idx = Int(round(pagerView.contentOffset.x / pagerView.bounds.width))
        let clamped = max(0, min(idx, max(0, assets.count - 1)))
        if clamped != currentIndex {
            currentIndex = clamped
            syncHeaderAndLiveState()
        }
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        if gestureRecognizer === dismissPanGesture {
            if let cell = pagerView.cellForItem(at: IndexPath(item: currentIndex, section: 0)) as? GalleryPreviewPageCell,
               cell.isZoomed {
                return false
            }
            let vel = dismissPanGesture.velocity(in: view)
            return vel.y > abs(vel.x) * 1.25 && vel.y > 0
        }
        return true
    }

    @objc private func handleDismissPan(_ gesture: UIPanGestureRecognizer) {
        let trans = gesture.translation(in: view)
        let vel = gesture.velocity(in: view)
        switch gesture.state {
        case .changed:
            let dy = max(0, trans.y)
            pagerView.transform = CGAffineTransform(translationX: 0, y: dy)
            view.backgroundColor = UIColor.black.withAlphaComponent(max(0.25, 1.0 - dy / 340.0))
        case .ended, .cancelled:
            if trans.y > 100 || vel.y > 600 {
                stopLiveAnimation()
                dismiss(animated: true)
            } else {
                UIView.animate(withDuration: 0.2) {
                    self.pagerView.transform = .identity
                    self.view.backgroundColor = .black
                }
            }
        default:
            break
        }
    }

    @objc private func handlePreviewLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, let asset = activeAsset() else { return }
        if let cell = pagerView.cellForItem(at: IndexPath(item: currentIndex, section: 0)) as? GalleryPreviewPageCell, cell.isZoomed {
            return
        }
        if #available(iOS 10.0, *) { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        stopLiveAnimation()
        dismiss(animated: true) { [weak self] in
            self?.onLongPressSelect?(asset)
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

    @objc private func closeTapped() { stopLiveAnimation(); dismiss(animated: true) }
    @objc private func delTapped() {
        guard let asset = activeAsset() else { return }
        stopLiveAnimation()
        dismiss(animated: true) { self.onAction?(.delete, asset) }
    }
    @objc private func rawTapped() {
        guard let asset = activeAsset() else { return }
        stopLiveAnimation()
        dismiss(animated: true) { self.onAction?(.shareRaw, asset) }
    }
    @objc private func saveTapped() {
        guard let asset = activeAsset() else { return }
        stopLiveAnimation()
        dismiss(animated: true) { self.onAction?(.saveLive, asset) }
    }
    @objc private func shareTapped() {
        guard let asset = activeAsset() else { return }
        stopLiveAnimation()
        dismiss(animated: true) { self.onAction?(.shareLive, asset) }
    }
}

private final class GalleryPreviewPageCell: UICollectionViewCell, UIScrollViewDelegate {
    private let scrollView = UIScrollView()
    private let imageView = UIImageView()
    private var boundAssetId: String = ""

    var isZoomed: Bool { scrollView.zoomScale > 1.03 }

    override init(frame: CGRect) {
        super.init(frame: frame)
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.minimumZoomScale = 1.0
        scrollView.maximumZoomScale = 4.0
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        scrollView.delegate = self

        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true

        contentView.addSubview(scrollView)
        scrollView.addSubview(imageView)

        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: contentView.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor),

            imageView.topAnchor.constraint(equalTo: scrollView.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: scrollView.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: scrollView.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: scrollView.bottomAnchor),
            imageView.widthAnchor.constraint(equalTo: scrollView.widthAnchor),
            imageView.heightAnchor.constraint(equalTo: scrollView.heightAnchor)
        ])

        let dbl = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        dbl.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(dbl)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func prepareForReuse() {
        super.prepareForReuse()
        boundAssetId = ""
        imageView.transform = .identity
        scrollView.setZoomScale(1.0, animated: false)
        imageView.image = nil
    }

    func bind(asset: PHAsset, imageManager: PHCachingImageManager) {
        boundAssetId = asset.localIdentifier
        imageView.transform = .identity
        scrollView.setZoomScale(1.0, animated: false)
        let targetId = asset.localIdentifier
        let opts = PHImageRequestOptions()
        opts.deliveryMode = .highQualityFormat
        opts.isNetworkAccessAllowed = true
        imageManager.requestImage(
            for: asset,
            targetSize: CGSize(width: 2000, height: 2000),
            contentMode: .aspectFit,
            options: opts
        ) { [weak self] img, _ in
            guard let self = self, self.boundAssetId == targetId else { return }
            self.imageView.image = img
        }
    }

    func applyLiveTransform(zoom: CGFloat, offsetX: CGFloat, offsetY: CGFloat) {
        guard !isZoomed else { return }
        let tx = (0.5 - offsetX) * contentView.bounds.width * max(0, zoom - 1.0)
        let ty = (0.5 - offsetY) * contentView.bounds.height * max(0, zoom - 1.0)
        imageView.transform = CGAffineTransform(translationX: tx, y: ty).scaledBy(x: zoom, y: zoom)
    }

    func clearLiveTransform() {
        imageView.transform = .identity
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        return imageView
    }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        if scrollView.zoomScale > 1.02 {
            imageView.transform = .identity
        }
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        if scrollView.zoomScale > 1.05 {
            scrollView.setZoomScale(1.0, animated: true)
        } else {
            let pt = gesture.location(in: imageView)
            let w = scrollView.bounds.width / 2.5
            let h = scrollView.bounds.height / 2.5
            scrollView.zoom(to: CGRect(x: pt.x - w * 0.5, y: pt.y - h * 0.5, width: w, height: h), animated: true)
        }
    }
}

private final class GalleryThumbCell: UICollectionViewCell {
    let imageView = UIImageView()
    private let badgeHitBtn = UIButton(type: .custom)
    private let badgeLabel = UILabel()
    private let liveLabel = UILabel()
    var onBadgeTap: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(imageView)

        badgeHitBtn.translatesAutoresizingMaskIntoConstraints = false
        badgeHitBtn.backgroundColor = .clear
        badgeHitBtn.accessibilityLabel = "选中照片"
        badgeHitBtn.addTarget(self, action: #selector(badgeTapped), for: .touchUpInside)

        badgeLabel.translatesAutoresizingMaskIntoConstraints = false
        badgeLabel.font = .systemFont(ofSize: 11.5, weight: .bold)
        badgeLabel.textAlignment = .center
        badgeLabel.layer.cornerRadius = 11.5
        badgeLabel.clipsToBounds = true
        badgeLabel.isUserInteractionEnabled = false
        badgeHitBtn.addSubview(badgeLabel)
        contentView.addSubview(badgeHitBtn)

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

            badgeHitBtn.topAnchor.constraint(equalTo: contentView.topAnchor),
            badgeHitBtn.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
            badgeHitBtn.widthAnchor.constraint(equalToConstant: 44),
            badgeHitBtn.heightAnchor.constraint(equalToConstant: 44),

            badgeLabel.topAnchor.constraint(equalTo: badgeHitBtn.topAnchor, constant: 5),
            badgeLabel.trailingAnchor.constraint(equalTo: badgeHitBtn.trailingAnchor, constant: -5),
            badgeLabel.widthAnchor.constraint(equalToConstant: 23),
            badgeLabel.heightAnchor.constraint(equalToConstant: 23),

            liveLabel.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 5),
            liveLabel.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -5)
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    @objc private func badgeTapped() { onBadgeTap?() }

    func configure(index: Int?, isLive: Bool) {
        liveLabel.isHidden = !isLive
        if let idx = index {
            badgeLabel.text = "\(idx)"
            badgeLabel.textColor = .white
            badgeLabel.backgroundColor = AppColors.brandLightText
            badgeLabel.layer.borderWidth = 0
            contentView.layer.borderWidth = 2.5
            contentView.layer.borderColor = AppColors.brandLightText.cgColor
        } else {
            badgeLabel.text = ""
            badgeLabel.backgroundColor = UIColor.black.withAlphaComponent(0.22)
            badgeLabel.layer.borderWidth = 1.2
            badgeLabel.layer.borderColor = UIColor.white.withAlphaComponent(0.85).cgColor
            contentView.layer.borderWidth = 0
        }
    }
}
