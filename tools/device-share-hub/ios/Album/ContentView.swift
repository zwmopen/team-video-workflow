import UIKit
import ImageIO

enum LocalDispatchedStore {
    private static var recentSavedAtMs: [String: Double] = [:]

    static func get(workId: String) -> Set<String> {
        let key = "dispatched_vers_\(workId)"
        let arr = UserDefaults.standard.stringArray(forKey: key) ?? []
        return Set(arr)
    }

    static func save(workId: String, version: String) {
        recentSavedAtMs[workId] = Date().timeIntervalSince1970 * 1000
        var set = get(workId: workId)
        set.insert(version)
        let key = "dispatched_vers_\(workId)"
        UserDefaults.standard.set(Array(set), forKey: key)
    }

    static func isRecentlySaved(workId: String, nowMs: Double = Date().timeIntervalSince1970 * 1000) -> Bool {
        guard let ts = recentSavedAtMs[workId] else { return false }
        return (nowMs - ts) < 15000
    }

    static func clear(workId: String) {
        recentSavedAtMs.removeValue(forKey: workId)
        UserDefaults.standard.removeObject(forKey: "dispatched_vers_\(workId)")
        if workId.contains("__link_") {
            let baseId = workId.components(separatedBy: "__link_").first ?? workId
            recentSavedAtMs.removeValue(forKey: baseId)
            UserDefaults.standard.removeObject(forKey: "dispatched_vers_\(baseId)")
        }
    }
}

final class LibraryViewController: UIViewController, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout, UISearchBarDelegate {
    private let library: WorkLibrary
    private let emptyStack = UIStackView()
    private let emptyDetail = UILabel()
    private var collectionView: UICollectionView!
    private var toastView: UILabel?
    private var initialFolderPromptShown = false
    private var selectedCategory = WorkCategory.all
    private var filterScrollView: UIScrollView!
    private var filterStackView: UIStackView!
    private var filterButtons: [String: UIButton] = [:]
    private let onlineStatusLabel = UILabel()

    // MARK: - 在线相册状态
    private var isOnlineMode: Bool = false
    private var onlineWorks: [OnlineWorkEntry] = []
    private var onlineCategories: [OnlineCategoryItem] = []
    private var selectedOnlineCategory: String = "全部"
    private var modeButton: UIButton!
    private var folderItem: UIBarButtonItem?
    private let prefOnlineModeKey = "pref_is_online_mode"
    /// 自动发现失败后按 15/30/60/120 秒退避；成功同步列表后恢复初始间隔。
    private var autoDiscovering = false
    private var lastAutoDiscoverAt: Date = .distantPast
    private var autoDiscoverRetryDelay: TimeInterval = 15
    private let autoDiscoverMaxRetryDelay: TimeInterval = 120

    /// DSH-091 C2：顶部作品搜索框 —— 对齐 Android / 工作台的搜索入口。
    private let workSearchBar = UISearchBar()
    private var searchQuery = ""

    // MARK: - DSH-112 在线相册排序（对齐 Android SORT_MENU，服务端本来就有 ?sort= 参数）
    // 部署目标是 iOS 12，用不了 iOS 14 的 UIMenu / showsMenuAsPrimaryAction，
    // 所以用 UIAlertController 的 actionSheet（iOS 8+，全版本安全）。
    private let onlineSortButton = UIButton(type: .system)
    private static let sortDefaultsKey = "online_sort_key"
    private static let defaultSortKey = "time_desc"
    /// 6 种排序 = 时间 / 名称 / 大小，各两个方向（与 Android SORT_MENU 逐项对齐）
    private let sortMenu: [(key: String, label: String)] = [
        ("time_desc", "时间最新在前（倒序）"),
        ("time_asc",  "时间最早在前（正序）"),
        ("name_asc",  "作品名称（A 到 Z 正序）"),
        ("name_desc", "作品名称（Z 到 A 倒序）"),
        ("size_desc", "作品张数（多图优先）"),
        ("size_asc",  "作品张数（少图优先）"),
    ]
    private lazy var currentSortKey: String = {
        let saved = UserDefaults.standard.string(forKey: LibraryViewController.sortDefaultsKey)
        return (saved?.isEmpty == false) ? saved! : LibraryViewController.defaultSortKey
    }()

    // MARK: - 多选标签筛选（季节标签：春季/夏季/秋季/冬季/四季通用 + 流量标签：精准流量团建/泛流量游戏攻略）
    private let onlineFilterButton = UIButton(type: .system)
    private static let filterSeasonsDefaultsKey = "online_filter_seasons"
    private static let filterFlowTypesDefaultsKey = "online_filter_flow_types"
    static let allSeasonOptions = ["春季", "夏季", "秋季", "冬季", "四季通用"]
    static let allFlowTypeOptions = ["精准流量团建", "泛流量游戏攻略"]
    private lazy var selectedSeasons: Set<String> = {
        let arr = UserDefaults.standard.stringArray(forKey: LibraryViewController.filterSeasonsDefaultsKey) ?? []
        return Set(arr)
    }()
    private lazy var selectedFlowTypes: Set<String> = {
        let arr = UserDefaults.standard.stringArray(forKey: LibraryViewController.filterFlowTypesDefaultsKey) ?? []
        return Set(arr)
    }()

    // MARK: - 视图模式切换与电脑端实时联动（图标 grid / 列表 list / 对比 compare）
    enum GalleryViewMode: String {
        case grid = "grid"
        case list = "list"
        case compare = "compare"

        var shortLabel: String {
            switch self {
            case .grid: return "图标▾"
            case .list: return "列表▾"
            case .compare: return "对比▾"
            }
        }

        var menuTitle: String {
            switch self {
            case .grid: return "🔲 图标视图（双列网格大图）"
            case .list: return "📑 列表视图（单列紧凑清单）"
            case .compare: return "🆚 对比视图（素材 vs 成品并排）"
            }
        }
    }
    private let onlineViewModeButton = UIButton(type: .system)
    private static let viewModeDefaultsKey = "online_view_mode"
    private lazy var currentViewMode: GalleryViewMode = {
        let raw = UserDefaults.standard.string(forKey: LibraryViewController.viewModeDefaultsKey) ?? "grid"
        return GalleryViewMode(rawValue: raw) ?? .grid
    }()

    /// DSH-092 C6：在线作品列表分页上限（对齐 Android `onlinePageLimit`，首屏 30 条）。
    /// 成品库 400+ 套时，一次性渲染会让 `sizeForItemAt` 把 400 份文案全解析一遍 ——
    /// 既卡首屏，也和 Android「加载更多」的观感不一致。
    private var onlinePageLimit = 30
    /// 每次「加载更多」追加的条数（与 Android 同为 30）
    private static let onlinePageStep = 30

    private static func inferLocalWorkSeason(_ text: String) -> String {
        if ["秋", "中秋", "国庆", "红枫", "银杏", "蟹", "晒秋", "柿子", "桂花"].contains(where: { text.contains($0) }) { return "秋季" }
        if ["冬", "滑雪", "温泉", "私汤", "泡汤", "年会", "跨年", "围炉"].contains(where: { text.contains($0) }) { return "冬季" }
        if ["夏", "避暑", "玩水", "漂流", "溯溪", "水枪", "桨板", "皮划艇"].contains(where: { text.contains($0) }) { return "夏季" }
        if ["春", "踏青", "赏花", "樱花", "采茶", "春游"].contains(where: { text.contains($0) }) { return "春季" }
        return "四季通用"
    }

    private static func inferLocalWorkFlowType(_ text: String) -> String {
        if ["游戏", "桌游", "破冰", "冷场", "惩罚"].contains(where: { text.contains($0) }) { return "泛流量游戏攻略" }
        return "精准流量团建"
    }

    private var filteredWorks: [WorkItem] {
        var base = library.works
        if selectedCategory != WorkCategory.all {
            base = base.filter { $0.folderName == selectedCategory }
        }
        if !selectedSeasons.isEmpty {
            base = base.filter { selectedSeasons.contains(Self.inferLocalWorkSeason("\($0.name) \($0.folderName)")) }
        }
        if !selectedFlowTypes.isEmpty {
            base = base.filter { selectedFlowTypes.contains(Self.inferLocalWorkFlowType("\($0.name) \($0.folderName)")) }
        }
        guard !searchQuery.isEmpty else { return base }
        return base.filter { work in
            work.name.localizedCaseInsensitiveContains(searchQuery)
                || work.folderName.localizedCaseInsensitiveContains(searchQuery)
        }
    }

    private func matchesOnlineCategory(work: OnlineWorkEntry, catKey: String) -> Bool {
        if catKey == "全部" || catKey.isEmpty {
            return true
        }
        if catKey == "待首发" {
            return work.useCount == 0
        } else if catKey == "已发1次" || catKey == "已发1" {
            return work.useCount == 1
        } else if catKey == "已发2次" || catKey == "已发2" || catKey == "已用满" {
            return work.useCount >= 2
        }

        let cleanCat = catKey
            .replacingOccurrences(of: "🌕", with: "")
            .replacingOccurrences(of: "🇨🇳", with: "")
            .replacingOccurrences(of: "🎮", with: "")
            .replacingOccurrences(of: "🏷️", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        if cleanCat == "游戏" || cleanCat == "团建游戏" || catKey.contains("游戏") {
            return work.destination.contains("游戏")
        } else {
            return catKey == work.destination || (!cleanCat.isEmpty && cleanCat == work.destination)
        }
    }

    private var filteredOnlineWorks: [OnlineWorkEntry] {
        var base = OnlineWorkLifecycle.filterActiveOnlineWorks(works: onlineWorks)
        if selectedOnlineCategory != "全部" {
            base = base.filter { matchesOnlineCategory(work: $0, catKey: selectedOnlineCategory) }
        }
        if !selectedSeasons.isEmpty {
            base = base.filter { selectedSeasons.contains($0.season) }
        }
        if !selectedFlowTypes.isEmpty {
            base = base.filter { selectedFlowTypes.contains($0.flowType) }
        }
        guard !searchQuery.isEmpty else { return base }
        // ⚠️ OnlineWorkEntry 没有 `name` 成员（只有 title / destination）——
        // 上一版写成 entry.name，CI 直接编译失败。destination 对应本地的「合集名」。
        return base.filter { entry in
            entry.title.localizedCaseInsensitiveContains(searchQuery)
                || entry.destination.localizedCaseInsensitiveContains(searchQuery)
                || entry.season.localizedCaseInsensitiveContains(searchQuery)
                || entry.flowType.localizedCaseInsensitiveContains(searchQuery)
                || entry.tags.contains(where: { $0.localizedCaseInsensitiveContains(searchQuery) })
        }
    }

    /// 实际喂给 collectionView 的在线数据 = 过滤结果的前 `onlinePageLimit` 条。
    private var displayedOnlineWorks: [OnlineWorkEntry] {
        Array(filteredOnlineWorks.prefix(onlinePageLimit))
    }

    /// 还有没有更多可加载（决定要不要渲染「加载更多」页脚）
    private var hasMoreOnline: Bool { filteredOnlineWorks.count > onlinePageLimit }

    init(library: WorkLibrary) {
        self.library = library
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        isOnlineMode = UserDefaults.standard.bool(forKey: prefOnlineModeKey)
        view.backgroundColor = AppColors.background
        configureNavigation()
        configureFilterBar()
        configureCollection()
        configureEmptyView()
        library.onChange = { [weak self] in
            guard let self = self, !self.isOnlineMode else { return }
            self.render()
        }

        // 监听电脑端或其他设备发起的视图切换（图标/列表/对比实时联动）
        OnlineGalleryClient.shared.onRemoteViewStateChanged = { [weak self] state in
            guard let self = self,
                  let remoteMode = GalleryViewMode(rawValue: state.viewMode),
                  remoteMode != self.currentViewMode else { return }
            self.applyViewMode(remoteMode, syncToServer: false, remoteSource: state.updatedBy)
        }

        if isOnlineMode {
            // 【体感加速】先读本地快照进内存（几十毫秒级），再后台拉最新；
            // 这样从本地相册切到在线相册是「秒开」而不是「正在连接」。
            primeOnlineDataFromSnapshot()
            loadOnlineData()
        } else {
            render()
        }
    }

    @objc private func openTransfer() {
        navigationController?.pushViewController(TransferViewController(), animated: true)
    }

    // MARK: - DSH-111 在线相册自动刷新与跨端视图联动
    private var autoRefreshTimer: Timer?
    private var lastUserTouchAt: Date = .distantPast
    private var lastServerFingerprint: String?

    private func startOnlineAutoRefresh() {
        stopOnlineAutoRefresh()
        lastServerFingerprint = nil   // 第一轮只记指纹（viewWillAppear 刚刷过列表）
        autoRefreshTimer = Timer.scheduledTimer(withTimeInterval: 6, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            // 无论是否正在滑动，都可以轻量同步视图状态与指纹（作品列表仅在非交互时重刷）
            self.pollForOnlineChanges()
        }
    }

    private func stopOnlineAutoRefresh() {
        autoRefreshTimer?.invalidate()
        autoRefreshTimer = nil
    }

    /// 现在适不适合自动刷新：在线首屏 + 在前台 + 没在搜索 + 没在滑动 + 没刚动过
    private func canAutoRefreshNow() -> Bool {
        guard isOnlineMode else { return false }
        guard UIApplication.shared.applicationState == .active else { return false }
        if workSearchBar.isFirstResponder { return false }
        if collectionView.isDragging || collectionView.isDecelerating {
            noteUserTouch()
            return false
        }
        if Date().timeIntervalSince(lastUserTouchAt) < 8 { return false }
        return true
    }

    private func noteUserTouch() {
        lastUserTouchAt = Date()
    }

    /// 问一句「变了吗」（同时捎带拉取跨端 viewState 同步），不变就什么都不做
    private func pollForOnlineChanges() {
        guard UIApplication.shared.applicationState == .active else { return }
        OnlineGalleryClient.shared.fetchServerFingerprint { [weak self] result in
            guard let self = self else { return }
            guard case .success(let fingerprint) = result else {
                // 电脑关机、换网段或旧地址失效时，定时指纹探测也负责触发受退避控制的重发现。
                if self.canAutoRefreshNow() { self.tryAutoDiscoverPc() }
                return
            }
            guard self.canAutoRefreshNow() else { return }                 // 请求期间用户开始操作了
            let first = self.lastServerFingerprint == nil
            let same = fingerprint == self.lastServerFingerprint
            self.lastServerFingerprint = fingerprint
            if first || same { return }
            self.loadOnlineData(silent: true)
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if isOnlineMode {
            loadOnlineData(silent: true)
            startOnlineAutoRefresh()   // DSH-111：前台期间周期探测，有新作品自动出现
        } else {
            render()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopOnlineAutoRefresh()        // DSH-111：离开页面立刻停表，不在后台空转耗电
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !isOnlineMode {
            showInitialFolderPromptIfNeeded()
        }
    }

    private func configureNavigation() {
        navigationItem.backBarButtonItem = UIBarButtonItem(title: "返回", style: .plain, target: nil, action: nil)
        navigationItem.titleView = nil

        modeButton = UIButton(type: .system)
        modeButton.frame = CGRect(x: 0, y: 0, width: 34, height: 34)
        modeButton.layer.cornerRadius = 11
        modeButton.imageView?.contentMode = .scaleAspectFit
        modeButton.addTarget(self, action: #selector(toggleMode), for: .touchUpInside)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(configureServerUrl))
        modeButton.addGestureRecognizer(longPress)
        updateModeButtonStyle()

        // 顶栏右侧：与安卓严格一致，从左到右依次为
        // 传送文件 → 来源模式(手机本地/电脑在线) → 刷新作品 → 回收站 → 设置
        // 这里用 UIStackView 显式排布，而不是 rightBarButtonItems 数组 ——
        // 后者「数组顺序 ↔ 屏幕左右顺序」的语义容易搞反，无法在本机验证 iOS 渲染，
        // 用 StackView 可以确保与安卓逐像素对齐。
        modeButton.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            modeButton.widthAnchor.constraint(equalToConstant: 34),
            modeButton.heightAnchor.constraint(equalToConstant: 34)
        ])
        let rightRow = UIStackView(arrangedSubviews: [
            toolbarButton(.plane, label: "传送文件", action: #selector(openTransfer)),
            modeButton,
            toolbarButton(.refresh, label: "刷新作品", action: #selector(refreshCurrent)),
            toolbarButton(.trash, label: "回收站", action: #selector(openTrash)),
            toolbarButton(.settings, label: "设置", action: #selector(openSettings))
        ])
        rightRow.axis = .horizontal
        rightRow.spacing = 8
        rightRow.alignment = .center
        rightRow.distribution = .fill

        let folderItem = toolbarItem(.folder, label: "切换到文件浏览", action: #selector(openFiles))
        self.folderItem = folderItem
        navigationItem.leftBarButtonItem = folderItem
        navigationItem.rightBarButtonItem = UIBarButtonItem(customView: rightRow)
        updateFolderItemVisibility()
    }

    /// DSH-097：folderItem 双模式可见 —— 本地模式进本地文件分发，在线模式进回收站（_已发送1次 + _垃圾作品 = DSH-095 统一入口）。
    private func updateFolderItemVisibility() {
        folderItem?.customView?.isHidden = false
        folderItem?.accessibilityLabel = isOnlineMode
            ? "在线模式：打开电脑端回收站（_已发送1次 + _垃圾作品）"
            : "本地模式：打开本地文件浏览"
    }

    private func toolbarButton(_ symbol: AlbumToolbarSymbol, label: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.translatesAutoresizingMaskIntoConstraints = false
        // DSH-097：顶栏按钮与安卓 ImageButton 42dp 半圆对齐 —— 34pt → 38pt、cornerRadius 11 → 19、浅绿实色 RGB(226,244,236)、图标 RGB(15,135,88) 深绿。
        button.backgroundColor = UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
        button.layer.cornerRadius = 19
        button.setImage(AlbumToolbarIcon.image(symbol, color: UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)), for: .normal)
        button.imageView?.contentMode = .scaleAspectFit
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(equalToConstant: 38),
            button.heightAnchor.constraint(equalToConstant: 38)
        ])
        return button
    }

    private func updateModeButtonStyle() {
        // 与安卓严格一致：contentDescription 既说「当前状态」也说「点击会发生什么」
        if !isOnlineMode {
            let fg = UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)
            let bg = UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
            modeButton.backgroundColor = bg
            modeButton.setImage(AlbumToolbarIcon.image(.phone, color: fg), for: .normal)
            modeButton.accessibilityLabel = "当前：手机本地作品 (点击切换到电脑在线)"
        } else {
            let fg = UIColor(red: 2/255, green: 132/255, blue: 199/255, alpha: 1)
            let bg = UIColor(red: 224/255, green: 242/255, blue: 254/255, alpha: 1)
            modeButton.backgroundColor = bg
            modeButton.setImage(AlbumToolbarIcon.image(.computer, color: fg), for: .normal)
            modeButton.accessibilityLabel = "当前：电脑在线作品 (点击切换到手机本地)"
        }
    }

    @objc private func toggleMode() {
        isOnlineMode.toggle()
        UserDefaults.standard.set(isOnlineMode, forKey: prefOnlineModeKey)
        updateModeButtonStyle()
        updateFolderItemVisibility()
        if isOnlineMode {
            showToast("已切换到：💻 电脑在线相册")
            loadOnlineData()
        } else {
            showToast("已切换到：📱 手机本地相册")
            render()
        }
    }

    /// 与安卓一致：顶栏「刷新作品」按钮 —— 在线模式触发在线刷新，本地模式触发本地重扫。
    @objc private func refreshCurrent() {
        if isOnlineMode {
            showToast("正在刷新电脑作品…")
            loadOnlineData(silent: false)
        } else {
            showToast("正在刷新作品")
            library.refresh(showConfirmation: true)
        }
    }

    @objc private func configureServerUrl() {
        let currentUrl = OnlineGalleryClient.shared.resolveBaseUrl()
        let alert = UIAlertController(title: "电脑在线相册服务设置",
                                      message: "当前连接服务器：\n\(currentUrl)\n默认端口 45835",
                                      preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = "如: http://192.168.1.27:45835"
            tf.text = currentUrl
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "探测测试", style: .default) { [weak self] _ in
            guard let self = self else { return }
            let url = alert.textFields?.first?.text ?? ""
            OnlineGalleryClient.shared.setManualBaseUrl(url)
            OnlineGalleryClient.shared.checkConnection { ok in
                self.showToast(ok ? "✅ 连接电脑相册服务成功" : "❌ 无法连接，请确认电脑端口 45835 是否开启")
            }
        })
        alert.addAction(UIAlertAction(title: "保存并刷新", style: .default) { [weak self] _ in
            guard let self = self else { return }
            let url = alert.textFields?.first?.text ?? ""
            // 手工指定通道：不会被后续的自动发现悄悄改掉
            OnlineGalleryClient.shared.setManualBaseUrl(url)
            if self.isOnlineMode { self.loadOnlineData() }
        })
        present(alert, animated: true)
    }

    private func toolbarItem(_ symbol: AlbumToolbarSymbol, label: String, action: Selector) -> UIBarButtonItem {
        let button = UIButton(type: .system)
        button.frame = CGRect(x: 0, y: 0, width: 38, height: 38)
        // DSH-097：与 toolbarButton 同设计 —— 38pt 半圆 + 浅绿实色 + 深绿图标。
        button.backgroundColor = UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
        button.layer.cornerRadius = 19
        button.setImage(AlbumToolbarIcon.image(symbol, color: UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)), for: .normal)
        button.imageView?.contentMode = .scaleAspectFit
        button.accessibilityLabel = label
        button.addTarget(self, action: action, for: .touchUpInside)
        NSLayoutConstraint.activate([
            button.widthAnchor.constraint(equalToConstant: 38),
            button.heightAnchor.constraint(equalToConstant: 38)
        ])
        return UIBarButtonItem(customView: button)
    }

    private func configureFilterBar() {
        onlineStatusLabel.translatesAutoresizingMaskIntoConstraints = false
        onlineStatusLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        onlineStatusLabel.textColor = UIColor(red: 0.08, green: 0.45, blue: 0.30, alpha: 1)
        onlineStatusLabel.backgroundColor = UIColor(red: 231/255, green: 239/255, blue: 233/255, alpha: 1)
        onlineStatusLabel.layer.cornerRadius = 6
        onlineStatusLabel.clipsToBounds = true
        onlineStatusLabel.textAlignment = .center
        onlineStatusLabel.text = "🟢 已同步电脑在线作品 · 共 \(onlineWorks.count) 套"
        view.addSubview(onlineStatusLabel)

        filterScrollView = UIScrollView()
        filterScrollView.translatesAutoresizingMaskIntoConstraints = false
        filterScrollView.showsHorizontalScrollIndicator = false
        filterScrollView.alwaysBounceHorizontal = false
        filterScrollView.backgroundColor = AppColors.secondaryBackground
        filterScrollView.layer.cornerRadius = 10
        view.addSubview(filterScrollView)

        filterStackView = UIStackView()
        filterStackView.translatesAutoresizingMaskIntoConstraints = false
        filterStackView.axis = .horizontal
        filterStackView.spacing = 4
        filterStackView.alignment = .center
        filterStackView.distribution = .fill
        filterStackView.isLayoutMarginsRelativeArrangement = true
        filterStackView.layoutMargins = UIEdgeInsets(top: 3, left: 4, bottom: 3, right: 4)
        filterScrollView.addSubview(filterStackView)

        NSLayoutConstraint.activate([
            onlineStatusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            onlineStatusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            onlineStatusLabel.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 4),
            onlineStatusLabel.heightAnchor.constraint(equalToConstant: 24),

            filterScrollView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            filterScrollView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            filterScrollView.topAnchor.constraint(equalTo: onlineStatusLabel.bottomAnchor, constant: 5),
            filterScrollView.heightAnchor.constraint(equalToConstant: 36),

            filterStackView.leadingAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.leadingAnchor),
            filterStackView.trailingAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.trailingAnchor),
            filterStackView.topAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.topAnchor),
            filterStackView.bottomAnchor.constraint(equalTo: filterScrollView.contentLayoutGuide.bottomAnchor),
            filterStackView.heightAnchor.constraint(equalTo: filterScrollView.frameLayoutGuide.heightAnchor)
        ])
    }

    private func updateOnlineStatusHint() {
        if isOnlineMode {
            onlineStatusLabel.text = "🟢 已同步电脑在线作品 · 共 \(onlineWorks.count) 套"
            onlineStatusLabel.textColor = UIColor(red: 0.08, green: 0.45, blue: 0.30, alpha: 1)
            onlineStatusLabel.backgroundColor = UIColor(red: 231/255, green: 239/255, blue: 233/255, alpha: 1)
        } else {
            onlineStatusLabel.text = "📱 手机本地作品 · 共 \(library.works.count) 套"
            onlineStatusLabel.textColor = UIColor(red: 0.15, green: 0.35, blue: 0.55, alpha: 1)
            onlineStatusLabel.backgroundColor = UIColor(red: 235/255, green: 243/255, blue: 250/255, alpha: 1)
        }
    }

    private final class CategoryFilterButton: UIButton {
        var folderKey: String = ""
        var displayLabel: String = ""
        var isPinned: Bool = false
    }

    // ==================== DSH-131: 分类 Tab 智能频次排序与长按 📌 钉住 (iOS) ====================
    private static let keyPinnedCategories = "online_pinned_categories"
    private static let keyCategoryUsagePrefix = "online_cat_usage_"

    private func getPinnedOnlineCategories() -> [String] {
        return UserDefaults.standard.stringArray(forKey: Self.keyPinnedCategories) ?? []
    }

    private func savePinnedOnlineCategories(_ list: [String]) {
        UserDefaults.standard.set(list, forKey: Self.keyPinnedCategories)
    }

    private func getOnlineCategoryUsage(key: String) -> Int {
        return UserDefaults.standard.integer(forKey: Self.keyCategoryUsagePrefix + key)
    }

    private func incrementOnlineCategoryUsage(key: String) {
        guard !key.isEmpty, key != "全部" else { return }
        let cur = getOnlineCategoryUsage(key: key)
        UserDefaults.standard.set(cur + 1, forKey: Self.keyCategoryUsagePrefix + key)
    }

    private func togglePinnedOnlineCategory(key: String, display: String) {
        guard !key.isEmpty, key != "全部" else { return }
        var pinned = getPinnedOnlineCategories()
        if pinned.contains(key) {
            pinned.removeAll { $0 == key }
            showToast("已取消【\(display)】固定")
        } else {
            pinned.append(key)
            showToast("📌 已将【\(display)】固定在前排最前")
        }
        savePinnedOnlineCategories(pinned)
        updateOnlineFilterTitles()
        if let btn = filterButtons[key] {
            let rect = btn.convert(btn.bounds, to: filterScrollView)
            filterScrollView.scrollRectToVisible(rect.insetBy(dx: -20, dy: 0), animated: true)
        }
    }

    @objc private func categoryFilterLongPressed(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, let button = gesture.view as? CategoryFilterButton else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        togglePinnedOnlineCategory(key: button.folderKey, display: button.displayLabel)
    }

    @objc private func filterButtonTapped(_ sender: CategoryFilterButton) {
        if isOnlineMode {
            guard selectedOnlineCategory != sender.folderKey else { return }
            selectedOnlineCategory = sender.folderKey
            incrementOnlineCategoryUsage(key: sender.folderKey)
            resetOnlinePaging()
            let pinnedList = getPinnedOnlineCategories()
            for (key, btn) in filterButtons {
                let isPin = pinnedList.contains(key)
                applyFilterButtonStyle(btn, isSelected: key == selectedOnlineCategory, isPinned: isPin)
            }
            renderOnlineUI()
        } else {
            guard selectedCategory != sender.folderKey else { return }
            selectedCategory = sender.folderKey
            for (key, btn) in filterButtons {
                applyFilterButtonStyle(btn, isSelected: key == selectedCategory, isPinned: false)
            }
            render()
        }
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
        collectionView.register(LoadMoreFooterView.self,
                               forSupplementaryViewOfKind: UICollectionView.elementKindSectionFooter,
                               withReuseIdentifier: "LoadMoreFooter")
        let refresh = UIRefreshControl()
        refresh.addTarget(self, action: #selector(refreshPulled(_:)), for: .valueChanged)
        collectionView.refreshControl = refresh
        workSearchBar.translatesAutoresizingMaskIntoConstraints = false
        workSearchBar.delegate = self
        workSearchBar.placeholder = "搜索作品名"
        workSearchBar.searchBarStyle = .minimal
        workSearchBar.autocapitalizationType = .none
        view.addSubview(workSearchBar)
        view.addSubview(collectionView)
        // 视图切换按钮（图标 / 列表 / 对比，与电脑端实时联动）
        onlineViewModeButton.translatesAutoresizingMaskIntoConstraints = false
        onlineViewModeButton.titleLabel?.font = UIFont.systemFont(ofSize: 11.5, weight: .semibold)
        onlineViewModeButton.titleLabel?.adjustsFontSizeToFitWidth = true
        onlineViewModeButton.layer.cornerRadius = 8
        onlineViewModeButton.accessibilityLabel = "视图切换（图标/列表/对比）"
        onlineViewModeButton.addTarget(self, action: #selector(viewModeButtonTapped(_:)), for: .touchUpInside)
        let vmLongPress = UILongPressGestureRecognizer(target: self, action: #selector(viewModeButtonLongPressed(_:)))
        vmLongPress.minimumPressDuration = 0.35
        onlineViewModeButton.addGestureRecognizer(vmLongPress)
        view.addSubview(onlineViewModeButton)
        updateViewModeButtonStyle()

        // 多选标签筛选按钮（放在视图按钮与排序按钮之间）
        onlineFilterButton.translatesAutoresizingMaskIntoConstraints = false
        onlineFilterButton.titleLabel?.font = UIFont.systemFont(ofSize: 11.5, weight: .semibold)
        onlineFilterButton.titleLabel?.adjustsFontSizeToFitWidth = true
        onlineFilterButton.layer.cornerRadius = 8
        onlineFilterButton.accessibilityLabel = "多选标签筛选"
        onlineFilterButton.addTarget(self, action: #selector(onlineTagFilterButtonTapped(_:)), for: .touchUpInside)
        view.addSubview(onlineFilterButton)
        updateFilterButtonStyle()

        // DSH-112：排序按钮放在搜索框最右侧（与 Android「搜索框右侧排序按钮」同一位置）
        onlineSortButton.translatesAutoresizingMaskIntoConstraints = false
        onlineSortButton.titleLabel?.font = UIFont.systemFont(ofSize: 11.5, weight: .semibold)
        onlineSortButton.titleLabel?.adjustsFontSizeToFitWidth = true
        onlineSortButton.layer.cornerRadius = 8
        onlineSortButton.accessibilityLabel = "排序方式"
        onlineSortButton.addTarget(self, action: #selector(sortButtonTapped(_:)), for: .touchUpInside)
        view.addSubview(onlineSortButton)
        updateSortButtonStyle()

        NSLayoutConstraint.activate([
            workSearchBar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 6),
            // 搜索栏右侧并排：视图切换(48) | 筛选(48) | 排序(48)
            workSearchBar.trailingAnchor.constraint(equalTo: onlineViewModeButton.leadingAnchor, constant: -2),
            workSearchBar.topAnchor.constraint(equalTo: filterScrollView.bottomAnchor, constant: 2),
            workSearchBar.heightAnchor.constraint(equalToConstant: 44),

            onlineViewModeButton.trailingAnchor.constraint(equalTo: onlineFilterButton.leadingAnchor, constant: -4),
            onlineViewModeButton.centerYAnchor.constraint(equalTo: workSearchBar.centerYAnchor),
            onlineViewModeButton.widthAnchor.constraint(equalToConstant: 48),
            onlineViewModeButton.heightAnchor.constraint(equalToConstant: 30),

            onlineFilterButton.trailingAnchor.constraint(equalTo: onlineSortButton.leadingAnchor, constant: -4),
            onlineFilterButton.centerYAnchor.constraint(equalTo: workSearchBar.centerYAnchor),
            onlineFilterButton.widthAnchor.constraint(equalToConstant: 48),
            onlineFilterButton.heightAnchor.constraint(equalToConstant: 30),

            onlineSortButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -6),
            onlineSortButton.centerYAnchor.constraint(equalTo: workSearchBar.centerYAnchor),
            onlineSortButton.widthAnchor.constraint(equalToConstant: 48),
            onlineSortButton.heightAnchor.constraint(equalToConstant: 30),

            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.topAnchor.constraint(equalTo: workSearchBar.bottomAnchor, constant: 4),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    // MARK: - 视图模式切换与电脑端实时联动（图标 grid / 列表 list / 对比 compare）

    private func updateViewModeButtonStyle() {
        onlineViewModeButton.setTitle("视图▾", for: .normal)
        switch currentViewMode {
        case .grid:
            let fg = UIColor(red: 2/255, green: 132/255, blue: 199/255, alpha: 1)
            let bg = UIColor(red: 224/255, green: 242/255, blue: 254/255, alpha: 1)
            onlineViewModeButton.backgroundColor = bg
            onlineViewModeButton.setTitleColor(fg, for: .normal)
        case .list:
            let fg = UIColor(red: 124/255, green: 58/255, blue: 237/255, alpha: 1)
            let bg = UIColor(red: 237/255, green: 233/255, blue: 254/255, alpha: 1)
            onlineViewModeButton.backgroundColor = bg
            onlineViewModeButton.setTitleColor(fg, for: .normal)
        case .compare:
            let fg = UIColor.white
            let bg = UIColor(red: 217/255, green: 119/255, blue: 6/255, alpha: 1)
            onlineViewModeButton.backgroundColor = bg
            onlineViewModeButton.setTitleColor(fg, for: .normal)
        }
    }

    @objc private func viewModeButtonTapped(_ sender: UIButton) {
        let sheet = UIAlertController(
            title: "切换视图模式（与电脑端实时联动）",
            message: "选择视图模式后，手机端与电脑端成品库将同步切换；对比视图可逐页同框查看「素材 vs 成品」",
            preferredStyle: .actionSheet
        )
        let allModes: [GalleryViewMode] = [.grid, .list, .compare]
        for mode in allModes {
            let title = (mode == currentViewMode) ? "✓ " + mode.menuTitle : mode.menuTitle
            sheet.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                self?.applyViewMode(mode, syncToServer: true, remoteSource: nil)
            })
        }
        sheet.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let pop = sheet.popoverPresentationController {
            pop.sourceView = onlineViewModeButton
            pop.sourceRect = onlineViewModeButton.bounds
        }
        present(sheet, animated: true)
    }

    @objc private func viewModeButtonLongPressed(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        let nextMode: GalleryViewMode
        switch currentViewMode {
        case .grid: nextMode = .list
        case .list: nextMode = .compare
        case .compare: nextMode = .grid
        }
        applyViewMode(nextMode, syncToServer: true, remoteSource: nil)
    }

    private func applyViewMode(_ mode: GalleryViewMode, syncToServer: Bool, remoteSource: String?) {
        currentViewMode = mode
        UserDefaults.standard.set(mode.rawValue, forKey: Self.viewModeDefaultsKey)
        updateViewModeButtonStyle()
        collectionView.collectionViewLayout.invalidateLayout()
        collectionView.reloadData()

        if syncToServer {
            OnlineGalleryClient.shared.pushViewState(viewMode: mode.rawValue)
            showToast("已切换：\(mode.shortLabel)视图（已联动电脑端）")
        } else if let src = remoteSource, !src.isEmpty {
            showToast("🔗 已跟随 \(src) 切换为：\(mode.shortLabel)视图")
        } else {
            showToast("🔗 已联动切换为：\(mode.shortLabel)视图")
        }
    }

    // MARK: - 多选标签筛选（季节标签 + 流量标签）

    private func updateFilterButtonStyle() {
        let activeCount = selectedSeasons.count + selectedFlowTypes.count
        let primaryGreen = UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)
        let lightGreenBg = UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
        if activeCount > 0 {
            onlineFilterButton.setTitle("筛选(\(activeCount))", for: .normal)
            onlineFilterButton.backgroundColor = primaryGreen
            onlineFilterButton.setTitleColor(.white, for: .normal)
        } else {
            onlineFilterButton.setTitle("筛选▾", for: .normal)
            onlineFilterButton.backgroundColor = lightGreenBg
            onlineFilterButton.setTitleColor(primaryGreen, for: .normal)
        }
    }

    private func updateSortButtonStyle() {
        onlineSortButton.setTitle("排序▾", for: .normal)
        let isCustom = (currentSortKey != "time_desc")
        if isCustom {
            let fg = UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)
            let bg = UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)
            onlineSortButton.backgroundColor = bg
            onlineSortButton.setTitleColor(fg, for: .normal)
        } else {
            onlineSortButton.backgroundColor = UIColor(red: 240/255, green: 242/255, blue: 241/255, alpha: 1)
            onlineSortButton.setTitleColor(UIColor(red: 60/255, green: 65/255, blue: 63/255, alpha: 1), for: .normal)
        }
    }

    @objc private func onlineTagFilterButtonTapped(_ sender: UIButton) {
        let activeWorks = OnlineWorkLifecycle.filterActiveOnlineWorks(works: onlineWorks)
        var seasonCounts: [String: Int] = [:]
        var flowCounts: [String: Int] = [:]
        if isOnlineMode {
            for w in activeWorks {
                seasonCounts[w.season, default: 0] += 1
                flowCounts[w.flowType, default: 0] += 1
            }
        } else {
            for w in library.works {
                let s = Self.inferLocalWorkSeason("\(w.name) \(w.folderName)")
                let f = Self.inferLocalWorkFlowType("\(w.name) \(w.folderName)")
                seasonCounts[s, default: 0] += 1
                flowCounts[f, default: 0] += 1
            }
        }

        let sheetVC = OnlineFilterSheetViewController(
            seasonOptions: Self.allSeasonOptions,
            flowTypeOptions: Self.allFlowTypeOptions,
            seasonCounts: seasonCounts,
            flowCounts: flowCounts,
            selectedSeasons: selectedSeasons,
            selectedFlowTypes: selectedFlowTypes
        )
        sheetVC.onApply = { [weak self] newSeasons, newFlows in
            guard let self = self else { return }
            self.selectedSeasons = newSeasons
            self.selectedFlowTypes = newFlows
            UserDefaults.standard.set(Array(newSeasons), forKey: Self.filterSeasonsDefaultsKey)
            UserDefaults.standard.set(Array(newFlows), forKey: Self.filterFlowTypesDefaultsKey)
            self.updateFilterButtonStyle()
            self.resetOnlinePaging()
            if self.isOnlineMode {
                self.renderOnlineUI()
            } else {
                self.render()
            }
            let totalSelected = newSeasons.count + newFlows.count
            if totalSelected == 0 {
                self.showToast("已重置标签筛选，显示全部作品")
            } else {
                let count = self.isOnlineMode ? self.filteredOnlineWorks.count : self.filteredWorks.count
                self.showToast("筛选已生效：匹配 \(count) 套作品")
            }
        }
        sheetVC.modalPresentationStyle = .overFullScreen
        sheetVC.modalTransitionStyle = .crossDissolve
        present(sheetVC, animated: true)
    }

    // MARK: - DSH-112 在线相册排序（对齐 Android SORT_MENU）

    private func sortKeyLabel(_ key: String) -> String {
        switch key {
        case "time_desc": return "最新▾"
        case "time_asc":  return "最早▾"
        case "name_asc":  return "名称A▾"
        case "name_desc": return "名称Z▾"
        case "size_desc": return "多图▾"
        case "size_asc":  return "少图▾"
        default: return "最新▾"
        }
    }

    @objc private func sortButtonTapped(_ sender: UIButton) {
        let sheet = UIAlertController(title: "排序方式", message: nil, preferredStyle: .actionSheet)
        for pair in sortMenu {
            // 当前选中的那项打勾 —— 用户口径「选中后有个状态显示就行」
            let title = (pair.key == currentSortKey) ? "✓ " + pair.label : pair.label
            sheet.addAction(UIAlertAction(title: title, style: .default) { [weak self] _ in
                self?.applySortKey(pair.key)
            })
        }
        sheet.addAction(UIAlertAction(title: "取消", style: .cancel))
        // ⚠️ iPad 上 actionSheet 不设 popover 会直接崩溃（UIDevice 通用防护）
        if let pop = sheet.popoverPresentationController {
            pop.sourceView = onlineSortButton
            pop.sourceRect = onlineSortButton.bounds
        }
        present(sheet, animated: true)
    }

    private func sortOnlineWorksLocally(_ list: inout [OnlineWorkEntry], sortKey: String) {
        let now = Date().timeIntervalSince1970 * 1000
        list.sort { a, b in
            // 一级规则（DSH-135）：已使用的作品在到期前统一置顶在货架顶部
            let aUsed = (a.expireAtMs > now && a.useCount > 0)
            let bUsed = (b.expireAtMs > now && b.useCount > 0)
            if aUsed && !bUsed { return true }
            if !aUsed && bUsed { return false }
            if aUsed && bUsed {
                let aTime = a.firstSharedAtMs > 0 ? a.firstSharedAtMs : a.updatedAt
                let bTime = b.firstSharedAtMs > 0 ? b.firstSharedAtMs : b.updatedAt
                if aTime != bTime { return aTime > bTime }
            }
            // 二级规则（DSH-109）：按指定的 sortKey 排序（与 Android / 服务端 SORT_KEYS 统一）
            switch sortKey {
            case "time_desc":
                return a.updatedAt > b.updatedAt
            case "time_asc":
                return a.updatedAt < b.updatedAt
            case "name_asc":
                return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedAscending
            case "name_desc":
                return a.title.localizedCaseInsensitiveCompare(b.title) == .orderedDescending
            case "size_desc":
                return a.imageCount > b.imageCount
            case "size_asc":
                return a.imageCount < b.imageCount
            default:
                return a.updatedAt > b.updatedAt
            }
        }
    }

    private func applySortKey(_ key: String) {
        currentSortKey = key
        UserDefaults.standard.set(key, forKey: LibraryViewController.sortDefaultsKey)
        updateSortButtonStyle()
        if isOnlineMode {
            sortOnlineWorksLocally(&onlineWorks, sortKey: key)
            renderOnlineUI()
            loadOnlineData(silent: true)
        } else {
            render()
        }
    }

    // MARK: - DSH-092 C6 在线列表分页（对齐 Android「加载更多作品」）

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        referenceSizeForFooterInSection section: Int) -> CGSize {
        guard isOnlineMode, hasMoreOnline else { return .zero }
        return CGSize(width: collectionView.bounds.width, height: 56)
    }

    func collectionView(_ collectionView: UICollectionView,
                        viewForSupplementaryElementOfKind kind: String,
                        at indexPath: IndexPath) -> UICollectionReusableView {
        let view = collectionView.dequeueReusableSupplementaryView(
            ofKind: kind, withReuseIdentifier: "LoadMoreFooter", for: indexPath)
        if kind == UICollectionView.elementKindSectionFooter,
           let footer = view as? LoadMoreFooterView {
            let shown = min(onlinePageLimit, filteredOnlineWorks.count)
            footer.configure(shown: shown, total: filteredOnlineWorks.count)
            footer.onTap = { [weak self] in
                guard let self = self else { return }
                self.onlinePageLimit += LibraryViewController.onlinePageStep
                self.collectionView.reloadData()
            }
        }
        return view
    }

    /// 分类切换 / 搜索词变化 / 重新拉数据 —— 都要回到首屏 30 条，
    /// 否则「翻到第 5 页再切分类」会直接显示第 5 页的 30 条。
    private func resetOnlinePaging() {
        onlinePageLimit = LibraryViewController.onlinePageStep
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

    // MARK: - 在线数据加载与渲染

    /// 【体感加速】开机 / 回到前台时，用上次成功的快照先把 UI 填上。
    /// 失败 / 过期 / 解析失败一律静默 —— 后台的 `loadOnlineData()` 仍然会拉一次最新，
    /// 快照只负责「不让用户看到空白的等待」。
    private func primeOnlineDataFromSnapshot() {
        guard let worksData = OnlineListCache.loadWorks() else { return }
        var parsed: [OnlineWorkEntry] = []
        do {
            let json = try JSONSerialization.jsonObject(with: worksData) as? [String: Any] ?? [:]
            if let arr = json["works"] as? [[String: Any]] {
                for w in arr {
                    if let entry = OnlineWorkEntry.from(dict: w) {
                        parsed.append(entry)
                    }
                }
            }
        } catch {
            // 快照损坏：丢掉，下次成功请求会重写。
            NSLog("[ContentView] 在线列表快照解析失败，已丢弃: %@",
                  String(describing: error))
            OnlineListCache.clear()
            return
        }
        if parsed.isEmpty { return }

        // 已经有更新的数据就别用旧快照盖掉（例如 viewWillAppear 里刚刷完）
        if !self.onlineWorks.isEmpty { return }

        var sortedParsed = parsed
        self.sortOnlineWorksLocally(&sortedParsed, sortKey: currentSortKey)
        self.onlineWorks = sortedParsed
        if let catsData = OnlineListCache.loadCategories() {
            if let json = try? JSONSerialization.jsonObject(with: catsData) as? [String: Any],
               let arr = json["categories"] as? [[String: Any]] {
                var cats: [OnlineCategoryItem] = []
                for item in arr {
                    if let n = item["name"] as? String, let c = item["count"] as? Int {
                        cats.append(OnlineCategoryItem(name: n, count: c))
                    }
                }
                if !cats.isEmpty { self.onlineCategories = cats }
            }
        }
        self.renderOnlineUI()
    }

    /// 与安卓 0.8.41 对齐（2026-09-20 体感加速三件套）：
    /// ① 分类与列表**并行**发出 —— 之前是串行，多一个网络往返；
    /// ② 已有数据时失败**不清屏、不弹错**，只发一条轻量 toast，保留快照；
    /// ③ 完全没有数据（首次冷启动 / 快照丢失）才走「错误 + auto-discover」老路径。
    ///
    /// 【DSH-081 / 0.8.23】判定「有没有数据」的时机被修正为**回包时刻**（见下方注释），
    /// 且无数据时**先 toast + 自愈、自愈失败才弹窗**，消除电脑换网段后的假弹窗。
    /// 【0.8.24 补漏】失败即自愈，**与有没有缓存数据无关** —— 有数据只是改用软提示。
    private func loadOnlineData(silent: Bool = false) {
        let group = DispatchGroup()
        var catResult: Result<OnlineCategoriesResult, Error>? = nil
        var workResult: Result<[OnlineWorkEntry], Error>? = nil

        group.enter()
        OnlineGalleryClient.shared.fetchCategories { result in
            catResult = result
            group.leave()
        }
        group.enter()
        OnlineGalleryClient.shared.fetchWorks(sortKey: currentSortKey) { result in
            workResult = result
            group.leave()
        }

        group.notify(queue: .main) { [weak self] in
            guard let self = self else { return }
            self.collectionView.refreshControl?.endRefreshing()

            if case .success(let catData) = catResult {
                self.onlineCategories = catData.categories
            }

            switch workResult {
            case .success(let works)?:
                self.autoDiscoverRetryDelay = 15
                // DSH-135 & DSH-137: 本地状态合并（State Merge）与生命周期未到期作品统一置顶
                let now = Date().timeIntervalSince1970 * 1000
                var mergedWorks: [OnlineWorkEntry] = []
                for w in works {
                    var localVers = LocalDispatchedStore.get(workId: w.id)
                    let recentlyClicked = LocalDispatchedStore.isRecentlySaved(workId: w.id, nowMs: now)
                    if w.useCount == 0 && w.dispatchedVersions.isEmpty && w.dispatchedTo.isEmpty && !recentlyClicked {
                        if !localVers.isEmpty {
                            LocalDispatchedStore.clear(workId: w.id)
                            localVers = []
                        }
                        OnlineWorkLifecycle.deletePermanently(id: w.id)
                    }
                    var mergedVers = w.dispatchedVersions
                    var mergedDisp = w.dispatchedTo
                    for lv in localVers {
                        if !mergedVers.contains(lv) { mergedVers.append(lv) }
                        let tag = "iPhone(\(lv))"
                        if !mergedDisp.contains(tag) { mergedDisp.append(tag) }
                    }
                    let rec = OnlineWorkLifecycle.getRecord(id: w.id)
                    let usedCount = max(w.useCount, rec?.useCount ?? 0, mergedVers.count)
                    var expireAt = w.expireAtMs
                    var firstShared = w.firstSharedAtMs
                    if firstShared <= 0, let r = rec, r.firstSharedAtMs > 0 {
                        firstShared = r.firstSharedAtMs
                    }
                    if expireAt <= 0 && firstShared > 0 {
                        expireAt = firstShared + 3600000
                    }
                    let updated = OnlineWorkEntry(
                        id: w.id, title: w.title, destination: w.destination, stage: w.stage,
                        useCount: usedCount, maxUses: w.maxUses, used: w.used || usedCount > 0,
                        remainingUses: max(0, w.maxUses - usedCount),
                        statusLabel: usedCount >= 2 ? "已发送" : (usedCount > 0 ? "已发1次" : w.statusLabel),
                        images: w.images, imageCount: w.imageCount, copyText: w.copyText,
                        hasCopyText: w.hasCopyText, dispatchedTo: mergedDisp,
                        updatedAt: w.updatedAt, garbage: w.garbage, garbageRemark: w.garbageRemark,
                        path: w.path, firstSharedAtMs: firstShared, expireAtMs: expireAt,
                        originDevice: w.originDevice, dispatchedVersions: mergedVers,
                        season: w.season, flowType: w.flowType, tags: w.tags,
                        sourceImages: w.sourceImages, sourceNames: w.sourceNames,
                        hasSourceCompare: w.hasSourceCompare, maxSimilarity: w.maxSimilarity,
                        similarityTag: w.similarityTag
                    )
                    mergedWorks.append(updated)
                }

                let activeUsed = mergedWorks.filter { $0.expireAtMs > now }
                    .sorted { $0.firstSharedAtMs > $1.firstSharedAtMs }
                let remaining = mergedWorks.filter { $0.expireAtMs <= now }
                var finalWorks = activeUsed + remaining
                self.sortOnlineWorksLocally(&finalWorks, sortKey: self.currentSortKey)
                self.onlineWorks = finalWorks
                self.renderOnlineUI()
            case .failure(let err)?:
                // 【DSH-081】「算不算已经有数据」必须在**回包这一刻**采样，不能在发起请求前采样。
                // 冷启动时本地快照 `loadOnlineSnapshot()` 是**异步后到**的：请求发出瞬间
                // onlineWorks 还是空的，快照随后才填上并渲染出列表。旧写法把判定提前到了
                // 请求前，于是出现「列表已经好好显示着 401 套作品，却弹出阻塞式
                // 拉取在线相册失败」——2026-09-21 真机复现（iPhone 12 / iOS 0.8.22）。
                let msg = err.localizedDescription.isEmpty ? "网络超时" : err.localizedDescription
                if !self.onlineWorks.isEmpty {
                    // 有快照 / 旧数据：保留用户已经看到的列表，只做软提示 —— 与安卓「失败不清屏」一致。
                    //
                    // ⚠️ 【0.8.24 补漏，真机实测踩到】**自愈与「数据在不在」无关**：
                    // 数据只决定「用 toast 还是用弹窗」，**绝不能**用它决定「要不要去找新地址」。
                    // 只把 hadData 的采样时机改对、却不在这里补上 tryAutoDiscoverPc()，会导致
                    // 「有快照的用户」在电脑换网段后**永远停在旧地址**（旧版是靠竞态把有数据
                    // 误判成无数据、顺带走了一次自愈才好的）——
                    // 等于把「看得见的弹窗」换成了「看得见的旧列表 + 静默卡死」，反而更糟。
                    // 实测证据：把 customPcServerUrl 写成旧网段 192.168.0.107 后冷启动，
                    // 屏幕无弹窗、列表照旧，30 秒后缓存地址**纹丝不动**。
                    self.showToast("⚠️ 刷新失败：\(msg)（已保留上次内容）")
                    self.renderOnlineUI()
                    self.tryAutoDiscoverPc()
                } else {
                    // 完全没有数据（首次冷启动或快照损坏）：先走**非阻塞**提示并立刻自愈，
                    // 只有自愈也失败才升级为阻塞弹窗 —— 电脑换网段/IP 这一常见场景从此不再弹错。
                    self.renderOnlineUI()
                    if silent {
                        // 静默刷新（viewWillAppear / 定时器）：连 toast 都不发，但自愈照跑。
                        self.tryAutoDiscoverPc()
                    } else {
                        self.showToast("⚠️ 正在搜索电脑在线相册…")
                        self.tryAutoDiscoverPc(alertOnFailure: "拉取在线相册失败：\(msg)")
                    }
                }
            case .none:
                break
            }
        }
    }

    /// 连接失败时自动在局域网搜索电脑在线相册服务。
    /// 失败后按 15/30/60/120 秒退避重试，不会永久放弃，也不会短间隔重复扫描。
    ///
    /// 两段式：**先快轨再慢轨**。
    /// ① `probeBeacon`：广播探测电脑信标端口（UDP 45832），电脑收到立刻单播回它**当前**的地址，
    ///    通常 1 秒内命中 —— 电脑换网段/IP 后也能秒级跟上，不再让用户长时间看「拉取失败」；
    /// ② `discover`：整段 /24 单播扫描兜底（20 秒预算），只在快轨没回应时才跑。
    ///
    /// - Parameter alertOnFailure: 非 nil 时表示「这次自愈是用户可见失败的兜底」：
    ///   自愈最终也没找到电脑、或自愈压根没跑起来（冷却/在途），才弹阻塞弹窗。
    private func tryAutoDiscoverPc(alertOnFailure: String? = nil) {
        guard isOnlineMode, UIApplication.shared.applicationState == .active else { return }
        // 自愈没能真正开跑（上一次还在跑 / 退避窗口内）时，不能把错误吞掉 —— 直接如实告知。
        if autoDiscovering {
            if let message = alertOnFailure { showError(message) }
            return
        }
        if Date().timeIntervalSince(lastAutoDiscoverAt) < autoDiscoverRetryDelay {
            if let message = alertOnFailure { showError(message) }
            return
        }
        lastAutoDiscoverAt = Date()
        autoDiscovering = true
        LanDiscovery.shared.probeBeacon { [weak self] probed in
            guard let self = self else { return }
            if let url = probed {
                self.finishAutoDiscover(url: url, viaBeacon: true, alertOnFailure: alertOnFailure)
                return
            }
            LanDiscovery.shared.discover { [weak self] found in
                guard let self = self else { return }
                self.finishAutoDiscover(url: found, viaBeacon: false, alertOnFailure: alertOnFailure)
            }
        }
    }

    private func finishAutoDiscover(url: String?, viaBeacon: Bool, alertOnFailure: String? = nil) {
        autoDiscovering = false
        guard isOnlineMode else { return }
        lastAutoDiscoverAt = Date()
        guard let url = url else {
            // 从失败完成时开始计时，避免 20 秒网段扫描结束后立刻再启动一轮。
            autoDiscoverRetryDelay = min(autoDiscoverRetryDelay * 2, autoDiscoverMaxRetryDelay)
            // 只有「自愈也彻底失败」才允许弹阻塞弹窗；普通场景一律用轻量 toast。
            if let message = alertOnFailure {
                showError(message + "\n暂未搜索到局域网内的电脑在线相册，请确认手机与电脑在同一 Wi-Fi。")
            } else {
                showToast("暂未搜索到电脑在线相册，可稍后再试")
            }
            return
        }
        let currentResolved = OnlineGalleryClient.shared.resolveBaseUrl()
        let cleanCurrent = currentResolved.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")).lowercased()
        let cleanUrl = url.trimmingCharacters(in: CharacterSet(charactersIn: "/ ")).lowercased()
        let isNewHost = (cleanUrl != cleanCurrent && !cleanCurrent.contains(cleanUrl) && !cleanUrl.contains(cleanCurrent))
        // 关键防护：如果已经定位到相同电脑地址，静默刷新即可，绝不频繁弹「已定位电脑相册服务」Toast 刷屏！
        if isNewHost && !cleanCurrent.isEmpty && !cleanCurrent.contains("127.0.0.1") {
            showToast(viaBeacon ? "✅ 已定位电脑相册服务 \(url)" : "✅ 已自动发现电脑相册服务 \(url)")
        }
        OnlineGalleryClient.shared.setCustomBaseUrl(url)
        loadOnlineData(silent: true)
    }

    private func renderOnlineUI() {
        guard isViewLoaded, isOnlineMode else { return }
        updateOnlineFilterTitles()
        collectionView.reloadData()
        let items = filteredOnlineWorks
        emptyStack.isHidden = !items.isEmpty
        collectionView.isHidden = items.isEmpty
        if items.isEmpty {
            emptyDetail.text = "电脑在线相册没有匹配作品。\n可下拉刷新或长按左上角电脑图标检查连接。"
        }
    }

    private func updateOnlineFilterTitles() {
        for subview in filterStackView.arrangedSubviews {
            filterStackView.removeArrangedSubview(subview)
            subview.removeFromSuperview()
        }
        filterButtons.removeAll()

        let activeWorks = OnlineWorkLifecycle.filterActiveOnlineWorks(works: onlineWorks)
        var counts: [String: Int] = [:]
        for w in activeWorks {
            counts[w.destination, default: 0] += 1
        }

        // 1. 全部 (永久第一位)
        let allTitle = "全部 \(activeWorks.count)"
        let allBtn = createFilterButton(key: "全部", display: "全部", fullTitle: allTitle,
                                        isSelected: selectedOnlineCategory == "全部", isPinned: false)
        filterStackView.addArrangedSubview(allBtn)
        filterButtons["全部"] = allBtn

        // 2. 智能四阶排序 (DSH-131)
        let pinnedList = getPinnedOnlineCategories()
        var rawDict: [String: (display: String, count: Int, isPinned: Bool, score: Int)] = [:]
        for cat in onlineCategories {
            if cat.count <= 0 || cat.name == "全部" { continue }
            let cnt = counts[cat.name] ?? cat.count
            let disp = Self.formatFolderLabel(cat.name)
            let isPinned = pinnedList.contains(cat.name)
            let score = getOnlineCategoryUsage(key: cat.name)
            rawDict[cat.name] = (disp, cnt, isPinned, score)
        }

        var sortedKeys: [String] = []
        // 阶段 1: 📌 钉住项
        for pinKey in pinnedList {
            if rawDict[pinKey] != nil {
                sortedKeys.append(pinKey)
            }
        }
        // 阶段 2: 🔥 高频项 (score > 0)
        let frequentKeys = rawDict.keys
            .filter { !pinnedList.contains($0) && (rawDict[$0]?.score ?? 0) > 0 }
            .sorted { (rawDict[$0]?.score ?? 0) > (rawDict[$1]?.score ?? 0) }
        sortedKeys.append(contentsOf: frequentKeys)
        // 阶段 3: 常规未常用项
        for cat in onlineCategories {
            if !sortedKeys.contains(cat.name) && rawDict[cat.name] != nil {
                sortedKeys.append(cat.name)
            }
        }

        // 依次渲染排好序的按钮
        for key in sortedKeys {
            guard let item = rawDict[key] else { continue }
            let isPinned = item.isPinned
            let titlePrefix = isPinned ? "📌 " : ""
            let fullTitle = "\(titlePrefix)\(item.display) \(item.count)"
            let isSelected = selectedOnlineCategory == key
            let btn = createFilterButton(key: key, display: item.display, fullTitle: fullTitle,
                                         isSelected: isSelected, isPinned: isPinned)
            filterStackView.addArrangedSubview(btn)
            filterButtons[key] = btn
        }
        updateOnlineStatusHint()
    }

    // MARK: - 本地模式渲染
    private func render() {
        guard isViewLoaded, !isOnlineMode else { return }
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
            emptyDetail.text = "当前合集没有作品。切回“全部”可查看所有内容。"
        } else {
            emptyDetail.text = library.scanSummary ?? "没有找到同时包含图片和 TXT 的作品文件夹。"
        }
        if let error = library.errorMessage { showError(error) }
        if let message = library.message { showToast(message) }
    }

    static func formatFolderLabel(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == WorkCategory.all {
            return "全部"
        }
        if trimmed.count > 5 {
            let prefix = String(trimmed.prefix(5))
            return prefix + "..."
        }
        return trimmed
    }

    private func updateFilterTitles() {
        var folderCounts: [String: Int] = [:]
        var orderedFolders: [String] = []
        for work in library.works {
            let folder = work.folderName.trimmingCharacters(in: .whitespacesAndNewlines)
            if !folder.isEmpty {
                if folderCounts[folder] == nil {
                    orderedFolders.append(folder)
                }
                folderCounts[folder, default: 0] += 1
            }
        }

        if selectedCategory != WorkCategory.all && folderCounts[selectedCategory] == nil {
            selectedCategory = WorkCategory.all
        }

        for subview in filterStackView.arrangedSubviews {
            filterStackView.removeArrangedSubview(subview)
            subview.removeFromSuperview()
        }
        filterButtons.removeAll()

        // 1. 全部
        let allButton = createFilterButton(key: WorkCategory.all, display: "全部", fullTitle: "全部 \(library.works.count)", isSelected: selectedCategory == WorkCategory.all)
        filterStackView.addArrangedSubview(allButton)
        filterButtons[WorkCategory.all] = allButton

        // 2. Dynamic folders
        for folder in orderedFolders {
            let count = folderCounts[folder] ?? 0
            let formatted = Self.formatFolderLabel(folder)
            let fullTitle = "\(formatted) \(count)"
            let isSelected = folder == selectedCategory
            let button = createFilterButton(key: folder, display: formatted, fullTitle: fullTitle, isSelected: isSelected)
            filterStackView.addArrangedSubview(button)
            filterButtons[folder] = button
        }
        filterScrollView.accessibilityLabel = "作品合集分类"
        updateOnlineStatusHint()
    }

    private func createFilterButton(key: String, display: String, fullTitle: String, isSelected: Bool, isPinned: Bool = false) -> UIButton {
        let button = CategoryFilterButton(type: .system)
        button.folderKey = key
        button.displayLabel = display
        button.isPinned = isPinned
        button.translatesAutoresizingMaskIntoConstraints = false
        button.setTitle(fullTitle, for: .normal)
        button.contentEdgeInsets = UIEdgeInsets(top: 6, left: 12, bottom: 6, right: 12)
        applyFilterButtonStyle(button, isSelected: isSelected, isPinned: isPinned)
        button.addTarget(self, action: #selector(filterButtonTapped(_:)), for: .touchUpInside)

        if key != "全部" && key != WorkCategory.all {
            let lp = UILongPressGestureRecognizer(target: self, action: #selector(categoryFilterLongPressed(_:)))
            lp.minimumPressDuration = 0.4
            button.addGestureRecognizer(lp)
        }
        return button
    }

    private func applyFilterButtonStyle(_ button: UIButton, isSelected: Bool, isPinned: Bool = false) {
        if isSelected {
            button.backgroundColor = AppColors.background
            button.setTitleColor(AppColors.text, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 12, weight: .bold)
            button.layer.cornerRadius = 8
            button.layer.shadowColor = UIColor.black.cgColor
            button.layer.shadowOpacity = 0.08
            button.layer.shadowOffset = CGSize(width: 0, height: 1)
            button.layer.shadowRadius = 2
            button.layer.borderWidth = 0
        } else if isPinned {
            button.backgroundColor = UIColor(red: 0.94, green: 0.97, blue: 0.95, alpha: 1.0)
            button.setTitleColor(UIColor(red: 0.06, green: 0.48, blue: 0.32, alpha: 1.0), for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 12, weight: .medium)
            button.layer.cornerRadius = 8
            button.layer.borderWidth = 1
            button.layer.borderColor = UIColor(red: 0.70, green: 0.88, blue: 0.78, alpha: 1.0).cgColor
            button.layer.shadowOpacity = 0
        } else {
            button.backgroundColor = .clear
            button.setTitleColor(AppColors.secondaryText, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 12, weight: .regular)
            button.layer.shadowOpacity = 0
            button.layer.borderWidth = 0
        }
    }

    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return isOnlineMode ? displayedOnlineWorks.count : filteredWorks.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "WorkCell", for: indexPath) as! WorkCell
        if isOnlineMode {
            let entry = displayedOnlineWorks[indexPath.item]
            cell.configureOnline(entry, viewMode: currentViewMode)
            cell.onOnlineShare = { [weak self, weak cell] item in
                self?.shareOnline(entry, item: item, source: cell)
            }
            cell.onOnlinePreview = { [weak self] index, img in
                guard let self = self else { return }
                if self.currentViewMode == .compare {
                    self.openOnlineComparePreview(entry: entry, initialIndex: index)
                } else {
                    self.openOnlinePreview(entry: entry, initialIndex: index, initialImage: img)
                }
            }
            cell.onOnlineComparePreview = { [weak self] index in
                self?.openOnlineComparePreview(entry: entry, initialIndex: index)
            }
            cell.onOnlineDelete = { [weak self] in
                self?.confirmDeleteOnline(entry)
            }
            cell.onOnlineReset = { [weak self] in
                self?.confirmResetOnline(entry)
            }
            cell.onOnlineCopyPath = { [weak self] in
                self?.copyOnlineWorkPath(entry)
            }
            cell.onCopyPreview = { [weak self, weak cell] item in
                self?.presentCopyPreview(item, workId: entry.id) {
                    self?.shareOnline(entry, item: item, source: cell)
                }
            }
            return cell
        }

        let work = filteredWorks[indexPath.item]
        cell.configure(work, viewMode: currentViewMode)
        cell.onShare = { [weak self, weak cell] item in self?.share(work, item: item, source: cell) }
        cell.onCopyPreview = { [weak self, weak cell] item in
            self?.presentCopyPreview(item) {
                self?.share(work, item: item, source: cell)
            }
        }
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
        cell.onReset = { [weak self] in self?.confirmResetWork(work) }
        cell.onDelete = { [weak self] in self?.confirmMoveToTrash(work) }
        cell.onCopyPath = { [weak self] in self?.copyLocalWorkPath(work) }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if isOnlineMode {
            let entry = displayedOnlineWorks[indexPath.item]
            if currentViewMode == .compare && entry.hasSourceCompare {
                openOnlineComparePreview(entry: entry, initialIndex: 0)
            } else {
                let cell = collectionView.cellForItem(at: indexPath) as? WorkCell
                openOnlinePreview(entry: entry, initialIndex: 0, initialImage: cell?.firstThumbnailImage)
            }
        } else {
            let work = filteredWorks[indexPath.item]
            navigationController?.pushViewController(WorkDetailViewController(library: library, work: work), animated: true)
        }
    }

    // MARK: - 在线分享与流转
    /// 在线作品分享。
    ///
    /// 三处历史缺陷：
    /// 1. 文案取「被点的那一条」（2026-09-21 修复）——多版本文案（`<<<COPY_FORMAT:MULTI>>>`）里
    ///    11 个版本有 10 个 `platform` 都是 `.general`，按 platform 回查会永远拿到第一条；
    /// 2. 图片**全部拉取**后再唤起分享（2026-09-21 修复），与 Android `handleOnlineWorkUse` 对齐。
    ///    旧实现只 `entry.images.first`，导致跳到小红书/抖音后只有一张图；
    /// 3. 分享载体改为**文件 URL**（2026-09-22 修复，DSH-090）——旧实现把 `[UIImage]` 直接交给
    ///    `UIActivityViewController`，小红书/抖音的 share extension 会把 N 张图读成同一张
    ///    （实测 9 张全变 1 张，而预览正常）。现与本地相册 `prepareShare` 同传 `NSURL`。
    private func optimisticMarkOnlineWorkUsedAndTop(workId: String, versionLabel: String, destination: String) {
        // 1. 累计该分类的使用热度（让分类 Tab 智能靠前）
        incrementOnlineCategoryUsage(key: destination)

        guard let index = onlineWorks.firstIndex(where: { $0.id == workId }) else { return }
        let old = onlineWorks[index]
        OnlineWorkLifecycle.markUsed(work: old)

        var newDispatched = old.dispatchedTo
        let tag = "iPhone(\(versionLabel))"
        if !newDispatched.contains(tag) { newDispatched.append(tag) }
        var newVers = old.dispatchedVersions
        if !newVers.contains(versionLabel) { newVers.append(versionLabel) }

        let now = Date().timeIntervalSince1970 * 1000
        let newCount = old.useCount + 1
        let remaining = max(0, old.remainingUses - 1)
        let firstShared = old.firstSharedAtMs > 0 ? old.firstSharedAtMs : now
        let expireAt = old.expireAtMs > 0 ? old.expireAtMs : (firstShared + 3600000)

        let updated = OnlineWorkEntry(
            id: old.id, title: old.title, destination: old.destination, stage: old.stage,
            useCount: newCount, maxUses: old.maxUses, used: true, remainingUses: remaining,
            statusLabel: newCount >= 2 ? "已发送" : "已发1次",
            images: old.images, imageCount: old.imageCount, copyText: old.copyText,
            hasCopyText: old.hasCopyText, dispatchedTo: newDispatched,
            updatedAt: now, garbage: old.garbage, garbageRemark: old.garbageRemark,
            path: old.path, firstSharedAtMs: firstShared, expireAtMs: expireAt,
            originDevice: old.originDevice.isEmpty ? UIDevice.current.name : old.originDevice,
            dispatchedVersions: newVers,
            season: old.season, flowType: old.flowType, tags: old.tags,
            sourceImages: old.sourceImages, sourceNames: old.sourceNames,
            hasSourceCompare: old.hasSourceCompare, maxSimilarity: old.maxSimilarity,
            similarityTag: old.similarityTag
        )

        // DSH-130-A: 0ms 内存置顶
        onlineWorks.remove(at: index)
        onlineWorks.insert(updated, at: 0)

        // 立即刷新列表并平滑回顶
        collectionView.reloadData()
        if !displayedOnlineWorks.isEmpty {
            collectionView.scrollToItem(at: IndexPath(item: 0, section: 0), at: .top, animated: true)
        }
        updateOnlineFilterTitles()
    }

    private func shareOnline(_ entry: OnlineWorkEntry, item: AvailableCopyPlatform, source: UIView?) {
        let textToCopy = item.copyText.trimmingCharacters(in: .whitespacesAndNewlines)
        // 【深度防御】与 Android `handleOnlineWorkUse` 1:1 对齐：判据是「实质字数 < 30」，
        // 不是 `isEmpty` —— 骨架文案（标记齐全但正文全是填写说明）也拦得住。
        guard !PlatformCopyParser.isCopySubstanceMissing(item.copyText) else {
            showError("⚠️ 该作品文案缺失（空壳作品），已阻止分发")
            return
        }
        UIPasteboard.general.string = textToCopy
        showToast("已复制：\(item.buttonLabel)")

        // ==================== DSH-130 & DSH-137: 本地持久化与 0ms 乐观 UI 更新与就地置顶 ====================
        LocalDispatchedStore.save(workId: entry.id, version: item.buttonLabel)
        optimisticMarkOnlineWorkUsedAndTop(workId: entry.id, versionLabel: item.buttonLabel, destination: entry.destination)

        // ==================== DSH-143: 0 秒立即向服务端同步使用记录（彻底切断对系统分享回调的依赖） ====================
        OnlineGalleryClient.shared.recordUse(
            workId: entry.id,
            platform: item.buttonLabel,
            retentionDurationMs: 3600000,
            versionTag: item.buttonLabel
        ) { [weak self] result in
            DispatchQueue.main.async {
                if result.ok {
                    self?.loadOnlineData(silent: true)
                }
            }
        }
        OnlineWorkLifecycle.markUsed(work: entry)

        guard !entry.images.isEmpty else {
            showToast("已复制 \(item.buttonLabel)（无图片作品）")
            return
        }

        // DSH-136: 极速检测本地是否已 100% 缓存所有图片，若是则 0 弹窗秒呼系统分享
        let allCached = OnlineGalleryClient.shared.areAllWorkImagesCachedLocally(entry)
        var progress: UIAlertController? = nil
        if !allCached {
            let total = entry.images.count
            let alert = UIAlertController(title: "正在从电脑同步原图到手机…",
                                          message: "准备连接电脑拉取 \(total) 张原图…",
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "取消", style: .cancel))
            progress = alert
            present(alert, animated: true)
        }

        // DSH-102：传 workId 给服务端，避免 image_name_index 同名冲突导致图错位
        downloadAllImages(paths: entry.images, workId: entry.id, onProgress: { [weak progress] done, count, name in
            progress?.message = "正在下载：\(name)（\(done)/\(count) 张）"
        }, completion: { [weak self] urls in
            guard let self = self else { return }
            let launchShare = {
                guard !urls.isEmpty else {
                    self.showError("图片加载失败，无法拉起分享；文案已在剪贴板")
                    return
                }
                if !allCached {
                    self.showToast("✅ 已准备 \(urls.count) 张原图，正在唤起分享…")
                }
                // DSH-090：与本地相册 prepareShare 同机制 —— 传文件 URL（as NSURL）
                let activity = UIActivityViewController(activityItems: urls.map { $0 as NSURL },
                                                        applicationActivities: nil)
                activity.popoverPresentationController?.sourceView = source
                activity.completionWithItemsHandler = { [weak self] _, completed, _, _ in
                    // 分享面板关闭后清理临时文件
                    if let dir = urls.first?.deletingLastPathComponent() {
                        try? FileManager.default.removeItem(at: dir)
                    }
                    guard let self = self else { return }
                    if completed {
                        self.showToast("🚀 分享完成")
                    }
                    self.loadOnlineData(silent: true)
                }
                self.present(activity, animated: true)
            }

            if let prg = progress {
                prg.dismiss(animated: true, completion: launchShare)
            } else {
                launchShare()
            }
        })
    }

    /// 按电脑端给出的顺序**依次**拉取全部原图并落盘为临时文件（`loadImage` 的回调保证在主线程）。
    ///
    /// DSH-090：返回 `[URL]` 而非 `[UIImage]` —— 与本地相册 `WorkLibrary.prepareShare`
    /// （`selected.map { $0 as NSURL }`）及 Android `launchOnlineShare`
    /// （`ACTION_SEND_MULTIPLE` + `Uri`）同机制。
    /// 把 `[UIImage]` 直接交给 `UIActivityViewController` 时，小红书/抖音的 share extension
    /// 会把 N 张图读成同一张（实测 9 张全变 1 张），改传文件 URL 后不再串图。
    private func downloadAllImages(paths: [String],
                                   workId: String,
                                   onProgress: @escaping (Int, Int, String) -> Void,
                                   completion: @escaping ([URL]) -> Void) {
        var collected = [URL?](repeating: nil, count: paths.count)
        let total = paths.count
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("online-share-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        func step(_ index: Int) {
            guard index < total else {
                completion(collected.compactMap { $0 })
                return
            }
            let path = paths[index]
            let src = (path as NSString).lastPathComponent
            onProgress(index + 1, total, src)
            OnlineGalleryClient.shared.loadImage(path: path, workId: workId, isThumbnail: false) { image in
                defer { step(index + 1) }
                guard let image = image else { return }
                // 序号前缀保证分享顺序与电脑端一致，且不会因重名互相覆盖
                let safeName = NSString(format: "%02d_%@", index + 1,
                                        src.isEmpty ? "image.jpg" : src) as String
                let fileURL = dir.appendingPathComponent(safeName)
                let ext = (src as NSString).pathExtension.lowercased()
                let data = (ext == "png") ? image.pngData() : image.jpegData(compressionQuality: 0.95)
                guard let payload = data else { return }
                do {
                    try payload.write(to: fileURL)
                } catch {
                    // 【DSH-118】原本是 `try?`：写盘失败（磁盘满 / 目录只读 /
                    // 文件名非法）时被静默吞掉，而下面仍会把这个 URL 记进 collected
                    // ⇒ 后续分享指向一个**根本不存在**的文件，用户只看到「分享失败」，
                    // 完全查不到是哪一步没写成。这里必须留痕并跳过。
                    print("[Share] 写盘失败 \(fileURL.lastPathComponent)：\(error)")
                    return
                }
                collected[index] = fileURL
            }
        }

        step(0)
    }

    private func openOnlinePreview(entry: OnlineWorkEntry, initialIndex: Int, initialImage: UIImage? = nil) {
        guard !entry.images.isEmpty else { return }
        let vc = OnlineImagePreviewController(entry: entry, initialIndex: initialIndex, initialImage: initialImage)
        vc.modalPresentationStyle = .fullScreen
        vc.modalTransitionStyle = .crossDissolve
        vc.onImageDeleted = { [weak self] workId, remainingImages in
            guard let self = self else { return }
            self.handleOnlineImageDeleted(workId: workId, remainingImages: remainingImages)
        }
        present(vc, animated: true)
    }

    private func openOnlineComparePreview(entry: OnlineWorkEntry, initialIndex: Int) {
        guard !entry.images.isEmpty else { return }
        let vc = OnlineComparePreviewController(entry: entry, initialIndex: initialIndex)
        vc.modalPresentationStyle = .fullScreen
        vc.modalTransitionStyle = .crossDissolve
        present(vc, animated: true)
    }

    private func handleOnlineImageDeleted(workId: String, remainingImages: [String]) {
        for (i, w) in onlineWorks.enumerated() {
            if w.id == workId {
                onlineWorks[i] = OnlineWorkEntry(
                    id: w.id,
                    title: w.title,
                    destination: w.destination,
                    stage: w.stage,
                    useCount: w.useCount,
                    maxUses: w.maxUses,
                    used: w.used,
                    remainingUses: w.remainingUses,
                    statusLabel: w.statusLabel,
                    images: remainingImages,
                    imageCount: remainingImages.count,
                    copyText: w.copyText,
                    hasCopyText: w.hasCopyText,
                    dispatchedTo: w.dispatchedTo,
                    updatedAt: w.updatedAt,
                    garbage: w.garbage,
                    garbageRemark: w.garbageRemark,
                    path: w.path,
                    firstSharedAtMs: w.firstSharedAtMs,
                    expireAtMs: w.expireAtMs,
                    originDevice: w.originDevice,
                    dispatchedVersions: w.dispatchedVersions,
                    season: w.season,
                    flowType: w.flowType,
                    tags: w.tags,
                    sourceImages: w.sourceImages,
                    sourceNames: w.sourceNames,
                    hasSourceCompare: w.hasSourceCompare,
                    maxSimilarity: w.maxSimilarity,
                    similarityTag: w.similarityTag
                )
                break
            }
        }
        DispatchQueue.main.async { [weak self] in
            self?.collectionView.reloadData()
        }
    }

    /// 「重置」（在线）：与 Android `confirmResetOnlineWork` 交互 1:1 对齐。
    private func confirmResetOnline(_ entry: OnlineWorkEntry) {
        let alert = UIAlertController(title: "重置使用状态",
                                      message: "是否重置该电脑在线作品为待首发状态？",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "重置", style: .default) { [weak self] _ in
            OnlineGalleryClient.shared.resetWork(workId: entry.id) { ok, _ in
                DispatchQueue.main.async {
                    if ok {
                        LocalDispatchedStore.clear(workId: entry.id)
                        OnlineWorkLifecycle.deletePermanently(id: entry.id)
                        if entry.id.contains("__link_") {
                            let baseId = entry.id.components(separatedBy: "__link_").first ?? entry.id
                            OnlineWorkLifecycle.deletePermanently(id: baseId)
                        }
                        self?.showToast("已重置为待首发状态")
                        self?.loadOnlineData(silent: true)
                    } else {
                        self?.showError("重置失败，请稍后重试")
                    }
                }
            }
        })
        present(alert, animated: true)
    }

    private func confirmDeleteOnline(_ entry: OnlineWorkEntry) {
        let alert = UIAlertController(title: "删除未发送作品？",
                                      message: "《\(entry.title)》\n\n手机端：移入回收站（右上角垃圾箱可随时恢复）\n电脑端：移入垃圾样本库并在元数据标记为垃圾（全渠道硬拦截）\n\n选择「备注并删除」可先填写垃圾原因备注。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "备注并删除", style: .default) { [weak self] _ in
            self?.promptRemarkThenDeleteOnline(entry)
        })
        alert.addAction(UIAlertAction(title: "删除", style: .destructive) { [weak self] _ in
            self?.performDeleteOnline(entry, remark: nil)
        })
        present(alert, animated: true)
    }

    private func promptRemarkThenDeleteOnline(_ entry: OnlineWorkEntry) {
        let alert = UIAlertController(title: "垃圾备注（随作品写入元数据）",
                                      message: "例如：文案公文味重 / 图片 AI 味浓 / 选题不合适",
                                      preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = "填写垃圾原因备注"
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "确认删除", style: .destructive) { [weak self] _ in
            let remark = alert.textFields?.first?.text ?? ""
            self?.performDeleteOnline(entry, remark: remark)
        })
        present(alert, animated: true)
    }

    private func performDeleteOnline(_ entry: OnlineWorkEntry, remark: String?) {
        // 1. 电脑端移入垃圾样本库并标记垃圾元数据
        OnlineGalleryClient.shared.deleteWork(workId: entry.id, remark: remark)
        // 2. 手机端移入本地回收站记录
        OnlineWorkLifecycle.moveToTrash(work: entry)
        let trimmed = (remark ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        showToast(trimmed.isEmpty ? "已删除：手机回收站 + 电脑垃圾样本库"
                                  : "已删除并备注：手机回收站 + 电脑垃圾样本库")
        renderOnlineUI()
    }

    private func share(_ work: WorkItem, item: AvailableCopyPlatform, source: UIView?) {
        do {
            let controller = UIActivityViewController(activityItems: try library.prepareShare(
                work, images: work.imageURLs, platform: item.platform, copyText: item.copyText),
                                                      applicationActivities: nil)
            controller.popoverPresentationController?.sourceView = source
            present(controller, animated: true)
        } catch {
            render()
            showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        let width = floor(collectionView.bounds.width - 32)
        // DSH-091 C3：卡片高度随「平台按钮换行后的行数」及「当前视图模式(grid/list/compare)」自适应。
        let inner = width - 25
        var extraDetailLines = 0
        let labels: [String]
        if isOnlineMode {
            guard indexPath.item < displayedOnlineWorks.count else {
                return CGSize(width: width, height: WorkCell.cardBaseHeight)
            }
            let entry = displayedOnlineWorks[indexPath.item]
            labels = PlatformCopyParser.isCopySubstanceMissing(entry.copyText)
                ? [WorkCell.copyMissingTitle]
                : PlatformCopyParser.parseAvailablePlatforms(entry.copyText).map { $0.buttonLabel }
            if currentViewMode != .list {
                if !entry.dispatchedTo.isEmpty {
                    extraDetailLines += 1
                    if entry.dispatchedTo.count > 1 {
                        extraDetailLines += 1
                    }
                }
                if entry.garbage {
                    extraDetailLines += 1
                }
                if currentViewMode == .compare {
                    extraDetailLines += 1
                }
            }
        } else {
            guard indexPath.item < filteredWorks.count else {
                return CGSize(width: width, height: WorkCell.cardBaseHeight)
            }
            let work = filteredWorks[indexPath.item]
            labels = CopyParserCache.platforms(for: work.textURL).map { $0.buttonLabel }
            if currentViewMode != .list && work.shareCount > 0 { extraDetailLines = 1 }
        }
        let rows = platformRowCount(labels: labels, width: inner)
        let baseH: CGFloat
        switch currentViewMode {
        case .grid:
            baseH = WorkCell.cardBaseHeight
        case .list:
            baseH = WorkCell.listBaseHeight
        case .compare:
            if isOnlineMode, indexPath.item < displayedOnlineWorks.count, !displayedOnlineWorks[indexPath.item].hasSourceCompare {
                baseH = WorkCell.cardBaseHeight
            } else {
                baseH = WorkCell.compareBaseHeight
            }
        }
        let height = baseH
            + CGFloat(max(rows, 1) - 1) * (WorkCell.platformRowHeight + WorkCell.platformSpacing)
            + CGFloat(extraDetailLines) * WorkCell.cardDetailLineStep
        return CGSize(width: width, height: height)
    }

    /// 平台按钮在给定宽度下会排成几行 —— 与 `PlatformFlowView.measure` 同一套数学，
    /// 保证「预估的卡片高度」与「实际渲染出来的行数」一致（不一致就会裁切或大片留白）。
    private func platformRowCount(labels: [String], width: CGFloat) -> Int {
        guard !labels.isEmpty, width > 0 else { return 1 }
        let font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        var x: CGFloat = 0
        var rows = 1
        for label in labels {
            let w = max(min((label as NSString).size(withAttributes: [.font: font]).width
                            + WorkCell.platformButtonPadding, width), 1)
            if x > 0, x + WorkCell.platformSpacing + w > width {
                rows += 1
                x = w
            } else {
                x = (x == 0 ? w : x + WorkCell.platformSpacing + w)
            }
        }
        return rows
    }

    /// DSH-091 C1：长按平台按钮 → 预览该版本文案。
    /// **零副作用**：不写剪贴板、不调用 recordUse、不移动作品 —— 与点按分享严格区分。
    /// DSH-092 C5：`onUse` = 「前往使用」，等价于 Android 弹窗的 PositiveButton
    /// （复制 + 唤起分享）。长按本身仍是零副作用，只有点这个按钮才算一次使用。
    private func presentCopyPreview(_ item: AvailableCopyPlatform, workId: String? = nil, onUse: @escaping () -> Void) {
        let vc = CopyPreviewViewController(title: item.buttonLabel, text: item.copyText, workId: workId, onUse: onUse)
        let nav = UINavigationController(rootViewController: vc)
        nav.modalPresentationStyle = .pageSheet
        present(nav, animated: true)
    }

    /// DSH-091 C2：搜索框输入回调 —— 本地 / 在线两条列表共用同一个 `searchQuery`。
    func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) {
        noteUserTouch()   // DSH-111：正在打字搜索，先别自动刷新
        searchQuery = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        resetOnlinePaging()
        if isOnlineMode { renderOnlineUI() } else { render() }
    }

    func searchBarSearchButtonClicked(_ searchBar: UISearchBar) {
        searchBar.resignFirstResponder()
    }

    func searchBarCancelButtonClicked(_ searchBar: UISearchBar) {
        searchQuery = ""
        searchBar.text = ""
        searchBar.resignFirstResponder()
        if isOnlineMode { renderOnlineUI() } else { render() }
    }

    private func confirmResetWork(_ work: WorkItem) {
        let alert = UIAlertController(title: "重置作品状态？",
                                      message: "将清空分享计数、取消自动删除排期，并移回普通列表排序。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "重置", style: .default) { [weak self] _ in
            guard let self = self else { return }
            do {
                try self.library.resetShare(work)
                self.render()
            } catch {
                self.showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
        })
        present(alert, animated: true)
    }

    private func confirmMoveToTrash(_ work: WorkItem) {
        let alert = UIAlertController(title: "移到回收站？",
                                      message: "《\(work.name)》\n\n作品会从当前列表消失，并移动到“相册回收站”；分享次数会保留。\n\n选择「备注并删除」可先填写垃圾原因备注（随作品写入手机本地元数据）。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "备注并删除", style: .default) { [weak self] _ in
            self?.promptRemarkThenMoveToTrash(work)
        })
        alert.addAction(UIAlertAction(title: "移到回收站", style: .destructive) { [weak self] _ in
            self?.performMoveToTrash(work, remark: nil)
        })
        present(alert, animated: true)
    }

    /// 「备注并删除」（本地）：与在线 `promptRemarkThenDeleteOnline` 交互 1:1 对齐。
    private func promptRemarkThenMoveToTrash(_ work: WorkItem) {
        let alert = UIAlertController(title: "垃圾备注（随作品写入元数据）",
                                      message: "例如：文案公文味重 / 图片 AI 味浓 / 选题不合适",
                                      preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = "填写垃圾原因备注"
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "确认删除", style: .destructive) { [weak self] _ in
            let remark = alert.textFields?.first?.text ?? ""
            self?.performMoveToTrash(work, remark: remark)
        })
        present(alert, animated: true)
    }

    private func performMoveToTrash(_ work: WorkItem, remark: String?) {
        let trimmed = (remark ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try library.moveWorkToTrash(work, remark: remark)
            showToast(trimmed.isEmpty ? "已移到回收站" : "已移到回收站并备注")
            render()
        } catch {
            showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
        }
    }

    /// 「复制路径」（本地）：复制手机上该作品文件夹的绝对路径。
    private func copyLocalWorkPath(_ work: WorkItem) {
        copyFolderPath(work.folderURL.path, origin: "手机本地作品")
    }

    /// 「复制路径」（在线）：复制电脑成品库中该作品文件夹的绝对路径。
    private func copyOnlineWorkPath(_ entry: OnlineWorkEntry) {
        copyFolderPath(entry.path, origin: "电脑在线作品")
    }

    private func copyFolderPath(_ path: String, origin: String) {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            showToast("⚠️ 该作品没有可复制的文件夹路径")
            return
        }
        UIPasteboard.general.string = trimmed
        showToast("📋 已复制\(origin)文件夹路径")
    }

    @objc private func refreshPulled(_ sender: UIRefreshControl) {
        if isOnlineMode {
            loadOnlineData()
        } else {
            CopyParserCache.clear()
            ThumbnailLoader.shared.clearCache()
            library.refresh()
        }
    }

    @objc private func emptyAction() {
        if isOnlineMode {
            loadOnlineData()
        } else {
            library.supportsExternalFolderSelection ? presentFolderPicker() : presentImportPicker()
        }
    }

    @objc private func openTrash() {
        // DSH-094：统一回收站入口（与 Android 严格对齐）。本地/在线模式都进 TrashViewController
        // （同一屏：本地回收站条目 + OnlineWorkLifecycle 生命周期追踪的标记删除记录）；
        // 电脑端回收站（_已发送1次 + _垃圾作品）通过 TrashView 顶部的「💻 打开电脑端回收站」按钮仍可达。
        navigationController?.pushViewController(TrashViewController(library: library), animated: true)
    }

    @objc private func openSettings() {
        navigationController?.pushViewController(SettingsViewController(library: library), animated: true)
    }

    @objc private func openFiles() {
        // DSH-097：folderItem 双模式分发 —— 本地进 LibraryFilesViewController，在线进回收站（DSH-095）。
        if isOnlineMode {
            openTrash()
            return
        }
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

enum AlbumToolbarSymbol { case plane, share, refresh, trash, settings, folder, phone, computer }

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
        case .phone:
            let body = UIBezierPath(roundedRect: CGRect(x: 5, y: 2, width: 13, height: 19), cornerRadius: 2.8)
            body.lineWidth = 1.6
            body.stroke()
            let screen = UIBezierPath(roundedRect: CGRect(x: 6.8, y: 4.8, width: 9.4, height: 12.5), cornerRadius: 1)
            screen.lineWidth = 1.0
            screen.stroke()
            let home = UIBezierPath(ovalIn: CGRect(x: 10.6, y: 18.2, width: 1.8, height: 1.8))
            home.fill()
        case .computer:
            let monitor = UIBezierPath(roundedRect: CGRect(x: 2.5, y: 3, width: 18, height: 12.5), cornerRadius: 2)
            monitor.lineWidth = 1.6
            monitor.stroke()
            let innerScreen = UIBezierPath(rect: CGRect(x: 4.5, y: 5, width: 14, height: 8.5))
            innerScreen.lineWidth = 1.0
            innerScreen.stroke()
            let stand = UIBezierPath()
            stand.move(to: CGPoint(x: 11.5, y: 15.5))
            stand.addLine(to: CGPoint(x: 11.5, y: 18.5))
            stand.lineWidth = 1.8
            stand.stroke()
            let base = UIBezierPath(roundedRect: CGRect(x: 7.5, y: 18.5, width: 8, height: 1.8), cornerRadius: 0.9)
            base.fill()
        }
        return UIGraphicsGetImageFromCurrentImageContext() ?? UIImage()
    }
}

private final class ThumbnailLoader {
    static let shared = ThumbnailLoader()
    private let cache = NSCache<NSURL, UIImage>()
    private let queue = DispatchQueue(label: "com.zwm.album.thumbnailLoader", qos: .userInitiated, attributes: .concurrent)

    private init() {
        cache.countLimit = 400
        cache.totalCostLimit = 80 * 1024 * 1024
    }

    func loadThumbnail(at url: URL, maxPixel: CGFloat = 200, completion: @escaping (UIImage?) -> Void) {
        let key = url as NSURL
        if let cached = cache.object(forKey: key) {
            completion(cached)
            return
        }

        queue.async {
            let options = [kCGImageSourceShouldCache: false] as CFDictionary
            guard let source = CGImageSourceCreateWithURL(url as CFURL, options) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }
            let thumbnailOptions = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxPixel,
                kCGImageSourceShouldCacheImmediately: true
            ] as CFDictionary

            if let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions) {
                let image = UIImage(cgImage: cgImage)
                let cost = Int(maxPixel * maxPixel * 4)
                self.cache.setObject(image, forKey: key, cost: cost)
                DispatchQueue.main.async { completion(image) }
            } else {
                DispatchQueue.main.async { completion(nil) }
            }
        }
    }

    func clearCache() {
        cache.removeAllObjects()
    }
}

private final class ThumbnailButton: UIButton {
    let imageViewWidget = UIImageView()
    var currentURL: URL?
    var currentOnlinePath: String?
    private var widthConstraint: NSLayoutConstraint?
    private var heightConstraint: NSLayoutConstraint?

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageViewWidget.contentMode = .scaleAspectFill
        imageViewWidget.clipsToBounds = true
        imageViewWidget.backgroundColor = AppColors.sharedBackground
        imageViewWidget.translatesAutoresizingMaskIntoConstraints = false
        addSubview(imageViewWidget)
        translatesAutoresizingMaskIntoConstraints = false
        layer.cornerRadius = 10
        layer.borderWidth = 1
        layer.borderColor = AppColors.separator.cgColor
        clipsToBounds = true
        let wc = widthAnchor.constraint(equalToConstant: 84)
        let hc = heightAnchor.constraint(equalToConstant: 112)
        widthConstraint = wc
        heightConstraint = hc
        NSLayoutConstraint.activate([
            imageViewWidget.leadingAnchor.constraint(equalTo: leadingAnchor),
            imageViewWidget.trailingAnchor.constraint(equalTo: trailingAnchor),
            imageViewWidget.topAnchor.constraint(equalTo: topAnchor),
            imageViewWidget.bottomAnchor.constraint(equalTo: bottomAnchor),
            wc,
            hc
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func setDimensions(width: CGFloat, height: CGFloat) {
        widthConstraint?.constant = width
        heightConstraint?.constant = height
        layer.cornerRadius = width <= 48 ? 6 : 8
    }

    func setSize(_ side: CGFloat) {
        setDimensions(width: side, height: side)
    }

    func load(url: URL) {
        currentURL = url
        currentOnlinePath = nil
        imageViewWidget.image = nil
        ThumbnailLoader.shared.loadThumbnail(at: url, maxPixel: 200) { [weak self] image in
            guard let self = self, self.currentURL == url else { return }
            self.imageViewWidget.image = image
        }
    }

    func loadOnline(path: String, workId: String? = nil) {
        currentOnlinePath = path
        currentURL = nil
        imageViewWidget.image = nil
        OnlineGalleryClient.shared.loadImage(path: path, workId: workId, isThumbnail: true, maxPixel: 200) { [weak self] image in
            guard let self = self, self.currentOnlinePath == path else { return }
            self.imageViewWidget.image = image
        }
    }
}

/// 对比视图专用双图卡片（左：原素材 / 右：AI 成品）
private final class ComparePairThumbView: UIControl {
    private let headerLabel = UILabel()
    private let leftImageView = UIImageView()
    private let rightImageView = UIImageView()
    private let leftBadge = UILabel()
    private let rightBadge = UILabel()
    private let emptySourceLabel = UILabel()

    var currentWorkId: String?
    var currentSourcePath: String?
    var currentOutputPath: String?

    override init(frame: CGRect) {
        super.init(frame: frame)
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = UIColor(red: 0.95, green: 0.97, blue: 0.96, alpha: 1)
        layer.cornerRadius = 10
        layer.borderWidth = 1
        layer.borderColor = UIColor(red: 0.15, green: 0.55, blue: 0.40, alpha: 0.35).cgColor
        clipsToBounds = true

        headerLabel.font = .boldSystemFont(ofSize: 10.5)
        headerLabel.textColor = UIColor(red: 0.08, green: 0.45, blue: 0.30, alpha: 1)
        headerLabel.textAlignment = .center
        headerLabel.translatesAutoresizingMaskIntoConstraints = false
        addSubview(headerLabel)

        for iv in [leftImageView, rightImageView] {
            iv.contentMode = .scaleAspectFill
            iv.clipsToBounds = true
            iv.layer.cornerRadius = 6
            iv.backgroundColor = AppColors.sharedBackground
            iv.isUserInteractionEnabled = false
            iv.translatesAutoresizingMaskIntoConstraints = false
            addSubview(iv)
        }

        emptySourceLabel.text = "无原图"
        emptySourceLabel.font = .systemFont(ofSize: 10, weight: .medium)
        emptySourceLabel.textColor = AppColors.secondaryText
        emptySourceLabel.textAlignment = .center
        emptySourceLabel.translatesAutoresizingMaskIntoConstraints = false
        leftImageView.addSubview(emptySourceLabel)

        leftBadge.text = "素材"
        leftBadge.font = .systemFont(ofSize: 9.5, weight: .bold)
        leftBadge.textColor = .white
        leftBadge.backgroundColor = UIColor.black.withAlphaComponent(0.62)
        leftBadge.textAlignment = .center
        leftBadge.layer.cornerRadius = 4
        leftBadge.clipsToBounds = true
        leftBadge.translatesAutoresizingMaskIntoConstraints = false
        leftImageView.addSubview(leftBadge)

        rightBadge.text = "成品"
        rightBadge.font = .systemFont(ofSize: 9.5, weight: .bold)
        rightBadge.textColor = .white
        rightBadge.backgroundColor = UIColor(red: 0.06, green: 0.52, blue: 0.34, alpha: 0.85)
        rightBadge.textAlignment = .center
        rightBadge.layer.cornerRadius = 4
        rightBadge.clipsToBounds = true
        rightBadge.translatesAutoresizingMaskIntoConstraints = false
        rightImageView.addSubview(rightBadge)

        NSLayoutConstraint.activate([
            widthAnchor.constraint(equalToConstant: 148),
            heightAnchor.constraint(equalToConstant: 112),

            headerLabel.topAnchor.constraint(equalTo: topAnchor, constant: 3),
            headerLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 4),
            headerLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -4),
            headerLabel.heightAnchor.constraint(equalToConstant: 16),

            leftImageView.topAnchor.constraint(equalTo: headerLabel.bottomAnchor, constant: 2),
            leftImageView.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 5),
            leftImageView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -5),
            leftImageView.widthAnchor.constraint(equalToConstant: 66),

            rightImageView.topAnchor.constraint(equalTo: headerLabel.bottomAnchor, constant: 2),
            rightImageView.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -5),
            rightImageView.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -5),
            rightImageView.widthAnchor.constraint(equalToConstant: 66),

            emptySourceLabel.centerXAnchor.constraint(equalTo: leftImageView.centerXAnchor),
            emptySourceLabel.centerYAnchor.constraint(equalTo: leftImageView.centerYAnchor),

            leftBadge.leadingAnchor.constraint(equalTo: leftImageView.leadingAnchor, constant: 3),
            leftBadge.bottomAnchor.constraint(equalTo: leftImageView.bottomAnchor, constant: -3),
            leftBadge.widthAnchor.constraint(equalToConstant: 36),
            leftBadge.heightAnchor.constraint(equalToConstant: 15),

            rightBadge.trailingAnchor.constraint(equalTo: rightImageView.trailingAnchor, constant: -3),
            rightBadge.bottomAnchor.constraint(equalTo: rightImageView.bottomAnchor, constant: -3),
            rightBadge.widthAnchor.constraint(equalToConstant: 36),
            rightBadge.heightAnchor.constraint(equalToConstant: 15)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(pageIndex: Int, workId: String, sourcePath: String, outputPath: String) {
        currentWorkId = workId
        currentSourcePath = sourcePath
        currentOutputPath = outputPath
        leftImageView.image = nil
        rightImageView.image = nil

        headerLabel.text = "P\(pageIndex + 1) 素材 ➔ 成品"
        if sourcePath.isEmpty {
            emptySourceLabel.isHidden = false
            emptySourceLabel.text = "无素材原片"
        } else {
            emptySourceLabel.isHidden = true
            OnlineGalleryClient.shared.loadImage(path: sourcePath, workId: workId, isThumbnail: true, maxPixel: 220) { [weak self] img in
                guard let self = self, self.currentWorkId == workId, self.currentSourcePath == sourcePath else { return }
                self.leftImageView.image = img
                self.emptySourceLabel.isHidden = (img != nil)
                if img == nil {
                    self.emptySourceLabel.text = "原图未命中"
                }
            }
        }

        OnlineGalleryClient.shared.loadImage(path: outputPath, workId: workId, isThumbnail: true, maxPixel: 220) { [weak self] img in
            guard let self = self, self.currentWorkId == workId, self.currentOutputPath == outputPath else { return }
            self.rightImageView.image = img
        }
    }
}

private enum CopyParserCache {
    private static var cache: [URL: [AvailableCopyPlatform]] = [:]
    private static let lock = NSLock()

    static func platforms(for url: URL) -> [AvailableCopyPlatform] {
        lock.lock()
        if let cached = cache[url] {
            lock.unlock()
            return cached
        }
        lock.unlock()

        let text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        let parsed = PlatformCopyParser.parseAvailablePlatforms(text)
        lock.lock()
        cache[url] = parsed
        lock.unlock()
        return parsed
    }

    static func clear() {
        lock.lock()
        cache.removeAll()
        lock.unlock()
    }
}

/// DSH-091 C1：长按平台按钮弹出的文案预览。
/// DSH-092 C5：补齐 Android AlertDialog 的**三个出口** —— 关闭 / 复制全文 / 前往使用。
/// 只复制（复制全文）仍然零副作用；「前往使用」才会复制 + 唤起分享 + 计使用次数。
private final class CopyPreviewViewController: UIViewController, UITextViewDelegate {
    private var bodyText: String
    private let versionLabel: String
    private let workId: String?
    private let subtitle = UILabel()
    private let textView = UITextView()
    private let saveButton = UIButton(type: .system)
    /// DSH-092 C5：「前往使用」—— 由外部注入，保持本类不知道分享细节。
    var onUse: (() -> Void)?

    init(title: String, text: String, workId: String? = nil, onUse: (() -> Void)? = nil) {
        self.bodyText = text
        self.versionLabel = title
        self.workId = workId
        self.onUse = onUse
        super.init(nibName: nil, bundle: nil)
        self.title = title
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    /// 与 Android 同一口径：`copyText.length()`（UTF-16 码元数），不是 Swift 的 `count`（字形数）。
    private var charCount: Int { (textView.text ?? bodyText).utf16.count }

    override func viewDidLoad() {
        super.viewDidLoad()
        // ⚠️ `.systemBackground` 要 iOS 13+，项目 deploymentTarget 更低 ⇒ 用 AppColors 的兜底版本
        view.backgroundColor = AppColors.background

        let isOnline = (workId != nil && !workId!.isEmpty)
        let tipSuffix = isOnline ? " · ✏️ 可直接编辑" : ""
        subtitle.text = "【\(versionLabel)】 共 \(charCount) 字\(tipSuffix)"
        subtitle.font = .systemFont(ofSize: 12)
        subtitle.textColor = UIColor(red: 0.06, green: 0.59, blue: 0.39, alpha: 1)
        subtitle.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(subtitle)

        textView.font = .systemFont(ofSize: 15)
        textView.isEditable = isOnline
        // Android 是 `setTextIsSelectable(true)` —— iOS 对应允许选中拷贝。
        textView.isSelectable = true
        textView.text = bodyText
        textView.delegate = self
        textView.layer.cornerRadius = 8
        textView.backgroundColor = isOnline ? UIColor(white: 0.96, alpha: 1.0) : .clear
        textView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(textView)

        saveButton.setTitle("💾 保存修改到电脑真源", for: .normal)
        saveButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        saveButton.setTitleColor(.white, for: .normal)
        saveButton.backgroundColor = UIColor(red: 0.06, green: 0.59, blue: 0.39, alpha: 1)
        saveButton.layer.cornerRadius = 8
        saveButton.addTarget(self, action: #selector(saveTapped), for: .touchUpInside)
        saveButton.translatesAutoresizingMaskIntoConstraints = false

        let copyButton = UIButton(type: .system)
        copyButton.setTitle("复制全文", for: .normal)
        copyButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        copyButton.addTarget(self, action: #selector(copyAllTapped), for: .touchUpInside)
        let useButton = UIButton(type: .system)
        useButton.setTitle("前往使用", for: .normal)
        useButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        useButton.addTarget(self, action: #selector(useTapped), for: .touchUpInside)
        let bar = UIStackView(arrangedSubviews: [copyButton, useButton])
        bar.axis = .horizontal
        bar.distribution = .fillEqually
        bar.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(bar)

        var constraints = [
            subtitle.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            subtitle.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            subtitle.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 10),

            textView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            textView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            textView.topAnchor.constraint(equalTo: subtitle.bottomAnchor, constant: 8),

            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            bar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -10),
            bar.heightAnchor.constraint(equalToConstant: 44)
        ]

        if isOnline {
            view.addSubview(saveButton)
            constraints.append(contentsOf: [
                textView.bottomAnchor.constraint(equalTo: saveButton.topAnchor, constant: -10),
                saveButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
                saveButton.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
                saveButton.bottomAnchor.constraint(equalTo: bar.topAnchor, constant: -8),
                saveButton.heightAnchor.constraint(equalToConstant: 40)
            ])
        } else {
            constraints.append(textView.bottomAnchor.constraint(equalTo: bar.topAnchor, constant: -8))
        }

        NSLayoutConstraint.activate(constraints)
        navigationItem.rightBarButtonItem = UIBarButtonItem(barButtonSystemItem: .done,
                                                           target: self,
                                                           action: #selector(closeTapped))
    }

    func textViewDidChange(_ textView: UITextView) {
        bodyText = textView.text
        let isOnline = (workId != nil && !workId!.isEmpty)
        let tipSuffix = isOnline ? " · ✏️ 可直接编辑" : ""
        subtitle.text = "【\(versionLabel)】 共 \(charCount) 字\(tipSuffix)"
    }

    @objc private func closeTapped() { dismiss(animated: true) }

    /// DSH-138: 保存修改到电脑真源文案.txt
    @objc private func saveTapped() {
        guard let wid = workId, !wid.isEmpty else { return }
        let currentText = textView.text ?? ""
        if currentText.trimmingCharacters(in: .whitespacesAndNewlines).count < 30 {
            showToast("⚠️ 文案内容过少（需>=30字），为防误删已拦截保存")
            return
        }
        saveButton.isEnabled = false
        saveButton.setTitle("⏳ 正在保存至电脑...", for: .normal)
        OnlineGalleryClient.shared.updateCopy(workId: wid, updatedCopy: currentText, versionTag: versionLabel) { [weak self] ok, msg in
            guard let self = self else { return }
            self.saveButton.isEnabled = true
            self.saveButton.setTitle("💾 保存修改到电脑真源", for: .normal)
            self.showToast(ok ? "✅ " + msg : "❌ " + msg)
        }
    }

    /// 复制全文：**不分享、不计使用次数**（Android `setNeutralButton` 同语义）。
    @objc private func copyAllTapped() {
        let textToCopy = textView.text ?? bodyText
        UIPasteboard.general.string = textToCopy
        showToast("已复制 \(versionLabel) 全文 (\(textToCopy.utf16.count)字)")
    }

    private func showToast(_ message: String) {
        let toast = UILabel()
        toast.text = message
        toast.backgroundColor = UIColor(white: 0.1, alpha: 0.85)
        toast.textColor = .white
        toast.font = .systemFont(ofSize: 13)
        toast.textAlignment = .center
        toast.layer.cornerRadius = 8
        toast.clipsToBounds = true
        toast.alpha = 0
        toast.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(toast)
        NSLayoutConstraint.activate([
            toast.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            toast.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -70),
            toast.heightAnchor.constraint(equalToConstant: 36),
            toast.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 16),
            toast.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -16)
        ])
        UIView.animate(withDuration: 0.2, animations: { toast.alpha = 1 }, completion: { _ in
            UIView.animate(withDuration: 0.3, delay: 1.2, animations: { toast.alpha = 0 },
                           completion: { _ in toast.removeFromSuperview() })
        })
    }

    /// 前往使用：复制 + 关闭 + 交回外层唤起分享（有副作用，与长按预览的只读语义严格分开）。
    @objc private func useTapped() {
        UIPasteboard.general.string = textView.text ?? bodyText
        let handler = onUse
        dismiss(animated: true) { handler?() }
    }
}

/// DSH-092 C6：在线列表底部的「加载更多作品 (已显示 X / N 套)」。
/// 文案与 Android `MainActivity:3575` 逐字对齐。
private final class LoadMoreFooterView: UICollectionReusableView {
    private let button = UIButton(type: .system)
    var onTap: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        button.titleLabel?.font = .systemFont(ofSize: 13)
        button.setTitleColor(UIColor(red: 0.06, green: 0.53, blue: 0.35, alpha: 1), for: .normal)
        button.layer.cornerRadius = 14
        button.layer.borderWidth = 1
        button.layer.borderColor = UIColor(red: 0.78, green: 0.90, blue: 0.84, alpha: 1).cgColor
        button.addTarget(self, action: #selector(tapped), for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        addSubview(button)
        NSLayoutConstraint.activate([
            button.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            button.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -16),
            button.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            button.heightAnchor.constraint(equalToConstant: 44)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func configure(shown: Int, total: Int) {
        button.setTitle("加载更多作品 (已显示 \(shown) / \(total) 套)", for: .normal)
    }

    @objc private func tapped() { onTap?() }
}

/// DSH-091 C3：平台按钮的**换行容器** —— 对齐 Android `FlowLayout`（横向排满自动折行）。
/// iOS 之前是「单行横向滚动」，V4.5 的 11 个版本要左右滑才看得全；Android 用 FlowLayout
/// 直接换行全展开。本类把 `FlowLayout.onMeasure/onLayout` 的数学原样搬过来。
private final class PlatformFlowView: UIView {
    /// 对齐 Android `FlowLayout.setHorizontalSpacing(dp(8))`
    var horizontalSpacing: CGFloat = 8
    /// 对齐 Android `FlowLayout.setVerticalSpacing(dp(8))`
    var verticalSpacing: CGFloat = 8
    /// 按钮固定行高（对齐 Android `compactButton` 的 dp(36)）
    var rowHeight: CGFloat = 36

    /// 兼容 `UIStackView` 的调用面 —— `WorkCell` 里的增删代码一行都不用改。
    var arrangedSubviews: [UIView] { subviews }

    func addArrangedSubview(_ view: UIView) {
        addSubview(view)
        setNeedsLayout()
        invalidateIntrinsicContentSize()
    }

    func removeArrangedSubview(_ view: UIView) { view.removeFromSuperview() }

    /// 折行试算 + 居中布局：`apply = true` 时同时写 frame。返回所需总高度。
    /// DSH-097：每行累计可见子视图宽度 → 行末用 (width - lineWidth) / 2 当 lineXOffset，
    /// 整行 view 加上 lineXOffset 实现居中（与 Android FlowLayout.onLayout 中的 rowXCenter 同语义）。
    private func measure(width: CGFloat, apply: Bool) -> CGFloat {
        var x: CGFloat = 0
        var y: CGFloat = 0
        var lineHeight: CGFloat = 0
        var lineWidth: CGFloat = 0          // 当前行已用宽度（含 horizontalSpacing）
        var lineRow: [(view: UIView, xInLine: CGFloat, width: CGFloat)] = []  // 待居中的本行视图
        var totalHeight: CGFloat = 0
        for view in subviews where !view.isHidden {
            let w = max(min(view.intrinsicContentSize.width, width), 1)
            // 换行：先结算上一行 lineXOffset，再开新行
            if x > 0, x + horizontalSpacing + w > width {
                if apply {
                    let lineXOffset = max((width - (lineWidth - horizontalSpacing)) / 2, 0)
                    for item in lineRow {
                        item.view.frame = CGRect(x: lineXOffset + item.xInLine,
                                                 y: y,
                                                 width: item.width,
                                                 height: rowHeight)
                    }
                }
                totalHeight += lineHeight + verticalSpacing
                x = 0
                y = totalHeight
                lineHeight = 0
                lineWidth = 0
                lineRow.removeAll(keepingCapacity: true)
            }
            if apply {
                lineRow.append((view, x, w))
            }
            x += w + horizontalSpacing
            lineWidth += w + horizontalSpacing
            lineHeight = max(lineHeight, rowHeight)
        }
        // 末行同样要居中
        if apply, !lineRow.isEmpty {
            let lineXOffset = max((width - (lineWidth - horizontalSpacing)) / 2, 0)
            for item in lineRow {
                item.view.frame = CGRect(x: lineXOffset + item.xInLine,
                                         y: y,
                                         width: item.width,
                                         height: rowHeight)
            }
        }
        return totalHeight + lineHeight
    }

    /// `intrinsicContentSize` 可能在宽度确定之前被问到 —— 逐级回退，避免算成 1 行。
    private var fallbackWidth: CGFloat {
        if bounds.width > 0 { return bounds.width }
        if let s = superview?.bounds.width, s > 0 { return s }
        return UIScreen.main.bounds.width - 44
    }

    private var effectiveWidth: CGFloat { bounds.width > 0 ? bounds.width : fallbackWidth }

    override func layoutSubviews() {
        super.layoutSubviews()
        _ = measure(width: effectiveWidth, apply: true)
    }

    override var intrinsicContentSize: CGSize {
        let w = effectiveWidth
        return CGSize(width: w, height: measure(width: w, apply: false))
    }

    /// `UIStackView` 排布时走这个入口 —— 必须按「给到的宽度」算，否则高度永远按 1 行算。
    override func systemLayoutSizeFitting(
        _ targetSize: CGSize,
        withHorizontalFittingPriority horizontalFittingPriority: UILayoutPriority,
        verticalFittingPriority: UILayoutPriority) -> CGSize {
        let w = targetSize.width > 0 ? targetSize.width : fallbackWidth
        return CGSize(width: w, height: measure(width: w, apply: false))
    }
}

private final class WorkCell: UICollectionViewCell {
    private let icon = UILabel()
    private let count = UILabel()
    private let name = UILabel()
    private let previewScroll = UIScrollView()
    private let previewStack = UIStackView()
    private let detail = UILabel()
    private let resetButton = UIButton(type: .system)
    private let deleteButton = UIButton(type: .system)
    /// 「复制路径」：紧跟在「删除」之后，复制该作品文件夹的绝对路径
    private let copyPathButton = UIButton(type: .system)
    /// 平台按钮区（可横向滚动）。
    /// 按钮**数量由解析结果决定**：V4.5 多版本文案（`<<<COPY_FORMAT:MULTI>>>`）会解析出
    /// 11 个版本，因此不能像旧版那样写死三个按钮 —— 与 Android 的动态渲染对齐。
    /// 平台按钮区（**换行全展开**，DSH-091 C3 对齐 Android FlowLayout）。
    private let platformRow = PlatformFlowView()
    /// 操作行：重置 / 删除 / 复制路径。数量固定，不随平台数变化。
    private let actionRow = UIStackView()
    private let platformContainer = UIStackView()
    /// 当前卡片上每个平台按钮对应的解析结果，下标 = `button.tag`。
    /// 分享时必须用它取「被点的这一条」的正文：MULTI 格式下 11 个版本里有 10 个
    /// `platform` 都是 `.general`，若按 platform 回查会全部命中第一条。
    private var platformItems: [AvailableCopyPlatform] = []
    /// 平台按钮左右 `contentEdgeInsets` 之和（15 + 15）—— 与 `rebuildPlatformButtons` 保持一致，
    /// `sizeForItemAt` 预估行数时要用到，两边必须同源。
    static let platformButtonPadding: CGFloat = 30
    /// 平台按钮间距（对齐 Android `FlowLayout` 的 dp(8)）
    static let platformSpacing: CGFloat = 8
    /// 平台按钮行高（对齐 Android `compactButton` 的 dp(36)）
    static let platformRowHeight: CGFloat = 36
    /// 卡片基准高度（平台按钮 1 行时）。多行时按 `platformRowHeight + platformSpacing` 递增。
    static let cardBaseHeight: CGFloat = 264
    /// 列表视图卡片基准高度（紧凑小缩略图 + 隐藏底部重置/删除操作行）
    static let listBaseHeight: CGFloat = 148
    /// 对比视图卡片基准高度（双图并排对比区 116pt + 底部操作行）
    static let compareBaseHeight: CGFloat = 268
    /// 元信息行每多一行（⇢ ✓ 平台明细）增加的高度
    static let cardDetailLineStep: CGFloat = 14
    /// 空壳作品占位按钮文案（单一真源：`makeCopyMissingButton` 与 `sizeForItemAt` 共用）
    static let copyMissingTitle = "⚠️ 文案缺失（空壳作品，不可分发）"
    /// 本卡片当前渲染的是「在线作品」还是「手机本地作品」——决定平台按钮走哪个回调。
    private var isOnlineCard = false
    private var currentViewMode: LibraryViewController.GalleryViewMode = .grid
    private var previewScrollHeightConstraint: NSLayoutConstraint?

    /// 本地作品：回调直接携带「被点的那一条」（按钮文案 + 正文），不再只传平台。
    var onShare: ((AvailableCopyPlatform) -> Void)?
    var onPreview: ((Int) -> Void)?
    /// DSH-091：长按平台按钮 → 预览该版本文案（零副作用：不复制、不计使用次数）。
    /// 对齐 Android `MainActivity` 平台按钮的 `setOnLongClickListener`。—— 供 C1 契约识别。
    var onCopyPreview: ((AvailableCopyPlatform) -> Void)?
    /// 长按手势识别器标记名（供源码级契约 `platformLongPress` 识别）
    private static let platformLongPressName = "platformLongPress"
    var onReset: (() -> Void)?
    var onDelete: (() -> Void)?
    /// 本地作品：复制手机上的作品文件夹路径
    var onCopyPath: (() -> Void)?

    /// 在线作品：同上，携带「被点的那一条」。
    var onOnlineShare: ((AvailableCopyPlatform) -> Void)?
    var onOnlinePreview: ((Int, UIImage?) -> Void)?
    /// 在线作品对比视图：点击任意对比卡打开全屏同框对比预览
    var onOnlineComparePreview: ((Int) -> Void)?
    var onOnlineDelete: (() -> Void)?
    var onOnlineReset: (() -> Void)?
    /// 在线作品：复制电脑成品库里的作品文件夹路径
    var onOnlineCopyPath: (() -> Void)?

    var firstThumbnailImage: UIImage? {
        (previewStack.arrangedSubviews.first as? ThumbnailButton)?.imageViewWidget.image
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.layer.cornerRadius = 16
        contentView.layer.borderWidth = 1
        name.font = .boldSystemFont(ofSize: 14.5)
        name.numberOfLines = 1
        name.isUserInteractionEnabled = true
        let nameTap = UITapGestureRecognizer(target: self, action: #selector(cardHeaderTapped))
        name.addGestureRecognizer(nameTap)

        detail.font = .systemFont(ofSize: 11.5)
        detail.textColor = AppColors.secondaryText
        detail.numberOfLines = 0
        detail.isUserInteractionEnabled = true
        let detailTap = UITapGestureRecognizer(target: self, action: #selector(cardHeaderTapped))
        detail.addGestureRecognizer(detailTap)
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
        configureResetButton()
        configureDeleteButton()
        configureCopyPathButton()

        // DSH-091 C3：换行参数逐项对齐 Android `FlowLayout`
        // （horizontalSpacing = verticalSpacing = dp(8)，按钮高 dp(36)）。
        platformRow.horizontalSpacing = WorkCell.platformSpacing
        platformRow.verticalSpacing = WorkCell.platformSpacing
        platformRow.rowHeight = WorkCell.platformRowHeight
        platformRow.translatesAutoresizingMaskIntoConstraints = false

        actionRow.axis = .horizontal
        actionRow.spacing = 6
        actionRow.alignment = .fill
        actionRow.distribution = .fillEqually
        platformContainer.axis = .vertical
        platformContainer.spacing = 6
        platformContainer.addArrangedSubview(platformRow)
        platformContainer.addArrangedSubview(actionRow)
        actionRow.translatesAutoresizingMaskIntoConstraints = false
        actionRow.heightAnchor.constraint(equalToConstant: 36).isActive = true
        actionRow.setContentCompressionResistancePriority(.required, for: .vertical)
        platformContainer.setContentCompressionResistancePriority(.required, for: .vertical)

        let stack = UIStackView(arrangedSubviews: [name, previewScroll, detail, platformContainer])
        stack.axis = .vertical
        stack.spacing = 5
        stack.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(stack)
        let pshc = previewScroll.heightAnchor.constraint(equalToConstant: 116)
        previewScrollHeightConstraint = pshc
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 10),
            pshc,
            // 平台区高度由 `PlatformFlowView.intrinsicContentSize` 决定（换行后自动增高），
            // 不再写死 36 —— 卡片高度在 `sizeForItemAt` 里按行数同步补偿。
            stack.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -10)
        ])
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() {
        super.prepareForReuse()
        onShare = nil
        onPreview = nil
        onReset = nil
        onDelete = nil
        onOnlineShare = nil
        onOnlinePreview = nil
        onOnlineComparePreview = nil
        onOnlineDelete = nil
        onOnlineReset = nil
        for view in previewStack.arrangedSubviews {
            if let tb = view as? ThumbnailButton {
                tb.currentURL = nil
                tb.currentOnlinePath = nil
                tb.imageViewWidget.image = nil
            } else if let cp = view as? ComparePairThumbView {
                cp.currentWorkId = nil
                cp.currentSourcePath = nil
                cp.currentOutputPath = nil
            }
        }
    }

    private func applyViewModeLayout(_ viewMode: LibraryViewController.GalleryViewMode, hasSourceCompare: Bool = true) {
        currentViewMode = viewMode
        switch viewMode {
        case .grid:
            previewScrollHeightConstraint?.constant = 116
            actionRow.isHidden = false
        case .list:
            previewScrollHeightConstraint?.constant = 44
            actionRow.isHidden = true
        case .compare:
            previewScrollHeightConstraint?.constant = 116
            actionRow.isHidden = false
        }
    }

    /// DSH-093 C12：自动清理倒计时 —— 文案与 Android `deleteCountdown` 逐字一致：
    /// 「N 分钟后自动删除」/「N 小时后自动删除」/「N 小时 M 分钟后自动删除」/「即将自动删除」。
    /// 没设清理计划就返回空串（调用方据此整行不追加，不留空行）。
    static func deleteCountdownText(deleteScheduledAtMs: Double?) -> String {
        guard let ms = deleteScheduledAtMs, ms > 0 else { return "" }
        let remaining = ms - Date().timeIntervalSince1970 * 1000
        if remaining <= 0 { return "即将自动删除" }
        let minutes = max(1, Int((remaining + 59_999.0) / 60_000.0))
        if minutes < 60 { return "\(minutes) 分钟后自动删除" }
        let hours = minutes / 60
        let rest = minutes % 60
        return rest == 0 ? "\(hours) 小时后自动删除"
            : "\(hours) 小时 \(rest) 分钟后自动删除"
    }

    /// 卡片日期后缀：与 Android `extractTimestampBadge` 同源同格式（`MM-dd HH:mm`），
    /// 从作品 id/title 的 `yyyyMMdd_HHmmss` 前缀解析；解析不到返回空串（调用方省略）。
    static func cardDateSuffix(_ rawID: String, _ rawTitle: String) -> String {
        let pattern = "^(\\d{4})(\\d{2})(\\d{2})_(\\d{2})(\\d{2})"
        guard let re = try? NSRegularExpression(pattern: pattern) else { return "" }
        for raw in [rawID, rawTitle] where !raw.isEmpty {
            let full = NSRange(raw.startIndex..<raw.endIndex, in: raw)
            guard let m = re.firstMatch(in: raw, range: full), m.numberOfRanges >= 6 else { continue }
            func part(_ i: Int) -> String {
                guard let r = Range(m.range(at: i), in: raw) else { return "" }
                return String(raw[r])
            }
            return part(2) + "-" + part(3) + " " + part(4) + ":" + part(5)
        }
        return ""
    }

    func configureOnline(_ entry: OnlineWorkEntry, viewMode: LibraryViewController.GalleryViewMode = .grid) {
        isOnlineCard = true
        applyViewModeLayout(viewMode, hasSourceCompare: entry.hasSourceCompare)
        contentView.backgroundColor = AppColors.secondaryBackground
        contentView.layer.borderColor = (viewMode == .compare)
            ? UIColor(red: 0.08, green: 0.56, blue: 0.36, alpha: 0.42).cgColor
            : UIColor(red: 0.15, green: 0.45, blue: 0.88, alpha: 0.25).cgColor

        name.text = "[\(entry.destination)] \(entry.title)"
        let record = OnlineWorkLifecycle.getRecord(id: entry.id)
        let usedCount = max(entry.useCount, record?.useCount ?? 0)
        // 日期后缀：与 Android `extractTimestampBadge` 同源同格式（MM-dd HH:mm），
        // 取作品 id/title 的 `yyyyMMdd_HHmmss` 前缀；取不到就省略（不显示占位）。
        let onlineDate = WorkCell.cardDateSuffix(entry.id, entry.title)
        let onlineDatePart = onlineDate.isEmpty ? "" : " · " + onlineDate

        // DSH-135: 统一生命周期倒计时药丸展示
        let now = Date().timeIntervalSince1970 * 1000
        var expireAt = entry.expireAtMs
        var firstShared = entry.firstSharedAtMs
        if firstShared <= 0, let r = record, r.firstSharedAtMs > 0 {
            firstShared = r.firstSharedAtMs
        }
        if expireAt <= 0 && firstShared > 0 {
            expireAt = firstShared + 3600000
        }

        let flowShort = entry.flowType.contains("游戏") ? "泛流量游戏" : "精准团建"
        var onlineDetail = "💻 \(entry.season)·\(flowShort) · \(entry.imageCount) 图 · "
        if expireAt > now {
            let remainMin = max(1, Int((expireAt - now) / 60000))
            let dev = entry.originDevice.isEmpty ? "" : " (\(entry.originDevice))"
            onlineDetail += "已使用\(usedCount)次\(dev) · 剩\(remainMin)分钟入回收站" + onlineDatePart
        } else if expireAt > 0 && usedCount > 0 {
            let dev = entry.originDevice.isEmpty ? "" : " (\(entry.originDevice))"
            onlineDetail += "已到期\(dev) · 正在移入回收站…" + onlineDatePart
        } else {
            onlineDetail += (usedCount > 0 ? "已使用 \(usedCount) 次" : "未使用") + onlineDatePart
        }

        if viewMode == .compare {
            if entry.maxSimilarity > 0 {
                let pct = Int(round(entry.maxSimilarity * 100))
                let simBadge = entry.similarityTag.isEmpty ? "原图相似度 \(pct)%" : "\(entry.similarityTag)(\(pct)%)"
                onlineDetail += " · 🆚 \(simBadge)"
            } else if entry.hasSourceCompare {
                onlineDetail += " · 🆚 点击卡片同框对比"
            } else {
                onlineDetail += " · 纯AI直出作品"
            }
        }

        // DSH-093 & DSH-142：分发去向精简展示，彻底防止文字超长挤压按钮
        if !entry.dispatchedTo.isEmpty {
            let compactRec = WorkCell.formatCompactDispatchedRecords(entry.dispatchedTo)
            if !compactRec.isEmpty {
                onlineDetail += "\n记录：" + compactRec
            }
        }
        // DSH-093 C11：垃圾备注。称呼统一叫「垃圾备注：」
        if entry.garbage {
            onlineDetail += " · 🗑️ 垃圾样本\n垃圾备注：" + (entry.garbageRemark.isEmpty ? "（未填写）" : entry.garbageRemark)
        }
        detail.text = onlineDetail
        detail.textColor = usedCount > 0
            ? UIColor(red: 0.15, green: 0.45, blue: 0.88, alpha: 1)
            : AppColors.secondaryText

        if viewMode == .compare {
            renderOnlineComparePreviews(entry)
        } else {
            // DSH-102：传 workId 给 renderOnlinePreviews → 缩略图取图用 id+file 双键
            renderOnlinePreviews(entry.images, workId: entry.id, thumbSide: viewMode == .list ? 44 : 84)
        }
        configureOnlineButtons(entry)
    }

    /// DSH-142: 精简分发记录展示，避免冗长文字挤压底部按钮
    static func formatCompactDispatchedRecords(_ records: [String]) -> String {
        guard !records.isEmpty else { return "" }
        var compactList: [String] = []
        for r in records {
            let trimmed = r.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            var dev = trimmed
            if let parenIdx = dev.range(of: "(") {
                dev = String(dev[..<parenIdx.lowerBound]).trimmingCharacters(in: .whitespaces)
            }
            if dev.count > 10 {
                dev = String(dev.prefix(10))
            }
            let ver = PlatformCopyParser.extractVersionFromRecord(trimmed)
            var timeStr = ""
            if let atIdx = trimmed.range(of: "@") {
                let afterAt = String(trimmed[atIdx.upperBound...]).trimmingCharacters(in: .whitespaces)
                let parts = afterAt.components(separatedBy: " ")
                if parts.count >= 2 {
                    let hm = parts[1].prefix(5)
                    timeStr = String(hm)
                }
            }
            var item = dev
            if !ver.isEmpty {
                item += "·" + ver
            }
            if !timeStr.isEmpty {
                item += " " + timeStr
            }
            if !compactList.contains(item) {
                compactList.append(item)
            }
        }
        if compactList.isEmpty { return "" }
        if compactList.count <= 2 {
            return compactList.joined(separator: " | ")
        }
        return compactList.prefix(2).joined(separator: " | ") + " 等\(compactList.count)条"
    }

    private func renderOnlinePreviews(_ paths: [String], workId: String, thumbSide: CGFloat = 84) {
        for v in previewStack.arrangedSubviews where !(v is ThumbnailButton) {
            previewStack.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
        let currentViews = previewStack.arrangedSubviews.compactMap { $0 as? ThumbnailButton }
        if currentViews.count > paths.count {
            for v in currentViews[paths.count...] {
                previewStack.removeArrangedSubview(v)
                v.removeFromSuperview()
            }
        }
        for (index, path) in paths.enumerated() {
            let button: ThumbnailButton
            if index < currentViews.count {
                button = currentViews[index]
            } else {
                button = ThumbnailButton(type: .custom)
                button.addTarget(self, action: #selector(onlineThumbnailTapped(_:)), for: .touchUpInside)
                previewStack.addArrangedSubview(button)
            }
            if thumbSide <= 44 {
                button.setDimensions(width: 44, height: 44)
            } else {
                button.setDimensions(width: 84, height: 112)
            }
            button.tag = index
            // DSH-102：loadOnline 传入 workId，让 loadImage 走 ?id+?file 双键
            // 封面（第 1 张）立刻秒级上屏，后续图片错峰微延迟加载，杜绝打满 URLSession
            if index == 0 {
                button.loadOnline(path: path, workId: workId)
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(index) * 0.04) { [weak button] in
                    button?.loadOnline(path: path, workId: workId)
                }
            }
        }
    }

    private func renderOnlineComparePreviews(_ entry: OnlineWorkEntry) {
        for v in previewStack.arrangedSubviews where !(v is ComparePairThumbView) {
            previewStack.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
        let currentViews = previewStack.arrangedSubviews.compactMap { $0 as? ComparePairThumbView }
        let paths = entry.images
        if currentViews.count > paths.count {
            for v in currentViews[paths.count...] {
                previewStack.removeArrangedSubview(v)
                v.removeFromSuperview()
            }
        }
        for (index, outputPath) in paths.enumerated() {
            let pairView: ComparePairThumbView
            if index < currentViews.count {
                pairView = currentViews[index]
            } else {
                pairView = ComparePairThumbView()
                pairView.addTarget(self, action: #selector(onlineComparePairTapped(_:)), for: .touchUpInside)
                previewStack.addArrangedSubview(pairView)
            }
            pairView.tag = index
            let sourcePath = index < entry.sourceImages.count ? entry.sourceImages[index] : ""
            pairView.configure(pageIndex: index, workId: entry.id, sourcePath: sourcePath, outputPath: outputPath)
        }
    }

    @objc private func cardHeaderTapped() {
        if isOnlineCard {
            if currentViewMode == .compare {
                onOnlineComparePreview?(0)
            } else {
                onOnlinePreview?(0, firstThumbnailImage)
            }
        } else {
            onPreview?(0)
        }
    }

    @objc private func onlineThumbnailTapped(_ sender: ThumbnailButton) {
        onOnlinePreview?(sender.tag, sender.imageViewWidget.image)
    }

    @objc private func onlineComparePairTapped(_ sender: ComparePairThumbView) {
        onOnlineComparePreview?(sender.tag)
    }

    private func configureOnlineButtons(_ entry: OnlineWorkEntry) {
        let platforms = PlatformCopyParser.parseAvailablePlatforms(entry.copyText)
        let copyMissing = PlatformCopyParser.isCopySubstanceMissing(entry.copyText)
        // DSH-137 & DSH-142: 多来源持久化与模糊别名精准打勾 ✓ 判定
        let localDispatched = LocalDispatchedStore.get(workId: entry.id)
        let isDispatched: (Int) -> Bool = { idx in
            guard idx >= 0 && idx < platforms.count else { return false }
            let item = platforms[idx]
            return PlatformCopyParser.isPlatformOrVersionDispatched(
                buttonLabel: item.buttonLabel,
                platformCode: item.platform.code,
                localDispatched: localDispatched,
                dispatchedVersions: entry.dispatchedVersions,
                dispatchedTo: entry.dispatchedTo
            )
        }
        rebuildPlatformButtons(copyMissing ? [] : platforms, isOptimistic: isDispatched,
                               action: #selector(platformButtonTapped(_:)))
        if copyMissing { platformRow.addArrangedSubview(makeCopyMissingButton()) }
        rebuildActionRow()
        // DSH-108：用户口径「按钮应该换成 重置、复制、删除 这 3 个」+「三按钮都放底部」
        actionRow.addArrangedSubview(resetButton)
        actionRow.addArrangedSubview(copyPathButton)
        actionRow.addArrangedSubview(deleteButton)

        resetButton.removeTarget(nil, action: nil, for: .allEvents)
        deleteButton.removeTarget(nil, action: nil, for: .allEvents)
        copyPathButton.removeTarget(nil, action: nil, for: .allEvents)

        resetButton.addTarget(self, action: #selector(onlineResetTapped), for: .touchUpInside)
        copyPathButton.addTarget(self, action: #selector(onlineCopyPathTapped), for: .touchUpInside)
        deleteButton.addTarget(self, action: #selector(onlineDeleteTapped), for: .touchUpInside)
    }

    /// 空壳作品占位按钮：文案与 Android `onlineWorkCard` 的不可点击占位**逐字一致**，
    /// 保证两端「有按钮 / 没按钮」的观感也一致。
    private func makeCopyMissingButton() -> UIButton {
        let missing = UIButton(type: .system)
        missing.setTitle(WorkCell.copyMissingTitle, for: .normal)
        missing.titleLabel?.font = .systemFont(ofSize: 13, weight: .semibold)
        missing.setTitleColor(.systemRed, for: .normal)
        missing.isEnabled = false
        missing.alpha = 0.55
        missing.backgroundColor = AppColors.secondaryBackground
        missing.layer.cornerRadius = 8
        missing.contentEdgeInsets = UIEdgeInsets(top: 5, left: 15, bottom: 5, right: 15)
        missing.translatesAutoresizingMaskIntoConstraints = false
        missing.heightAnchor.constraint(equalToConstant: 36).isActive = true
        missing.accessibilityLabel = "该在线作品文案缺失，已禁止分发"
        return missing
    }

    /// 按解析结果**动态**创建平台按钮，数量不限（V4.5 多版本文案为 11 个）。
    private func rebuildPlatformButtons(_ platforms: [AvailableCopyPlatform],
                                        isOptimistic: (Int) -> Bool,
                                        action: Selector) {
        platformRow.arrangedSubviews.forEach {
            platformRow.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }
        platformItems = platforms
        for (index, item) in platforms.enumerated() {
            let button = UIButton(type: .system)
            let isDispatched = isOptimistic(index)
            let title = isDispatched ? ("✓ " + item.buttonLabel) : item.buttonLabel
            button.setTitle(title, for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 13, weight: isDispatched ? .bold : .semibold)
            button.layer.cornerRadius = 8
            button.contentEdgeInsets = UIEdgeInsets(top: 5, left: 15, bottom: 5, right: 15)
            button.translatesAutoresizingMaskIntoConstraints = false
            button.heightAnchor.constraint(equalToConstant: 36).isActive = true
            button.tag = index
            button.accessibilityLabel = item.buttonLabel
            applyPlatformStyle(button, isOptimistic: isDispatched)
            button.addTarget(self, action: action, for: .touchUpInside)
            // DSH-091 C1：长按 = 预览文案（零副作用），与 Android 对齐。
            let lp = UILongPressGestureRecognizer(target: self,
                                                  action: #selector(platformLongPress(_:)))
            lp.name = WorkCell.platformLongPressName
            lp.minimumPressDuration = 0.35
            lp.cancelsTouchesInView = true
            button.addGestureRecognizer(lp)
            platformRow.addArrangedSubview(button)
        }
    }

    /// DSH-091 C1：长按平台按钮 → 把「这一条」交给外层预览。
    /// 用 `button.tag` 定位，MULTI 下 11 个版本里有多个同名 platform，不能按名字回查。
    @objc private func platformLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, let button = gesture.view as? UIButton else { return }
        let index = button.tag
        guard index >= 0, index < platformItems.count else { return }
        onCopyPreview?(platformItems[index])
    }

    private func rebuildActionRow() {
        actionRow.arrangedSubviews.forEach {
            actionRow.removeArrangedSubview($0)
            $0.removeFromSuperview()
        }
    }

    /// 平台按钮统一入口：下标定位到「被点的那一条」，本地/在线由 `isOnlineCard` 分流。
    @objc private func platformButtonTapped(_ sender: UIButton) {
        guard sender.tag >= 0, sender.tag < platformItems.count else { return }
        let item = platformItems[sender.tag]
        if isOnlineCard {
            onOnlineShare?(item)
        } else {
            onShare?(item)
        }
    }

    @objc private func onlineResetTapped() { onOnlineReset?() }
    @objc private func onlineDeleteTapped() { onOnlineDelete?() }
    @objc private func onlineCopyPathTapped() { onOnlineCopyPath?() }

    func configure(_ work: WorkItem, viewMode: LibraryViewController.GalleryViewMode = .grid) {
        isOnlineCard = false
        applyViewModeLayout(viewMode)
        contentView.backgroundColor = AppColors.secondaryBackground
        contentView.layer.borderColor = AppColors.separator.cgColor

        name.text = work.name
        // DSH-093 C9：计数口径对齐 Android —— 用「小红书 + 抖音」之和，不是 shareCount。
        // 因为下面一行明细就是这两个数，总数必须等于明细之和；用 shareCount 会出现
        // 「已使用 3 次 / ✓ 小红书 1 · 抖音 1」这种自相矛盾的显示。
        let localUsed = work.xhsShareCount + work.douyinShareCount
        var localDetail = "📱 手机本地 · \(work.imageURLs.count) 图 · "
            + (localUsed > 0 ? "已使用 \(localUsed) 次" : "未使用")
        let localDate = WorkCell.cardDateSuffix(work.name, work.name)
        if !localDate.isEmpty { localDetail += " · " + localDate }
        // DSH-091 C4：与 Android 对齐 —— 本地作品已使用时追加各平台次数明细。
        if localUsed > 0 {
            localDetail += "\n✓ 小红书 \(work.xhsShareCount) · 抖音 \(work.douyinShareCount)"
            // DSH-093 C12：自动清理倒计时，文案与 Android `deleteCountdown` 逐字一致。
            let countdown = WorkCell.deleteCountdownText(deleteScheduledAtMs: work.deleteScheduledAtMs)
            if !countdown.isEmpty { localDetail += "\n" + countdown }
        }
        detail.text = localDetail
        detail.textColor = AppColors.secondaryText

        renderPreviews(work.imageURLs, thumbSide: viewMode == .list ? 44 : 84)
        configureButtons(work)
    }

    private func renderPreviews(_ urls: [URL], thumbSide: CGFloat = 84) {
        for v in previewStack.arrangedSubviews where !(v is ThumbnailButton) {
            previewStack.removeArrangedSubview(v)
            v.removeFromSuperview()
        }
        let currentViews = previewStack.arrangedSubviews.compactMap { $0 as? ThumbnailButton }
        if currentViews.count > urls.count {
            for v in currentViews[urls.count...] {
                previewStack.removeArrangedSubview(v)
                v.removeFromSuperview()
            }
        }

        for (index, url) in urls.enumerated() {
            let button: ThumbnailButton
            if index < currentViews.count {
                button = currentViews[index]
            } else {
                button = ThumbnailButton(type: .custom)
                button.addTarget(self, action: #selector(thumbnailTapped(_:)), for: .touchUpInside)
                previewStack.addArrangedSubview(button)
            }
            if thumbSide <= 44 {
                button.setDimensions(width: 44, height: 44)
            } else {
                button.setDimensions(width: 84, height: 112)
            }
            button.tag = index
            button.load(url: url)
        }
    }

    @objc private func thumbnailTapped(_ sender: ThumbnailButton) {
        onPreview?(sender.tag)
    }

    private func configureButtons(_ work: WorkItem) {
        let platforms = CopyParserCache.platforms(for: work.textURL)
        let count = platforms.count
        // 沿用旧版「已分享过就置灰」的语义，按按钮下标映射：
        // 只有 1 个按钮时看整体分享次数；2 个按钮时第 2 个看小红书是否已发；
        // 3 个及以上时第 3 个看抖音次数；新增的版本按钮不做置灰。
        rebuildPlatformButtons(platforms, isOptimistic: { index in
            switch index {
            case 0:
                return count <= 1 ? work.shareCount > 0 : work.xhsShareCount > 0
            case 1:
                return count == 2 ? (work.shareCount > 0 && work.xhsShareCount == 0) : false
            case 2:
                return count >= 3 ? work.douyinShareCount > 0 : false
            default:
                return false
            }
        }, action: #selector(platformButtonTapped(_:)))

        rebuildActionRow()
        // DSH-108：底部三按钮顺序统一 = 重置 → 复制 → 删除（与 Android 对齐，用户口径）
        actionRow.addArrangedSubview(resetButton)
        actionRow.addArrangedSubview(copyPathButton)
        actionRow.addArrangedSubview(deleteButton)

        resetButton.removeTarget(nil, action: nil, for: .allEvents)
        deleteButton.removeTarget(nil, action: nil, for: .allEvents)
        copyPathButton.removeTarget(nil, action: nil, for: .allEvents)

        resetButton.addTarget(self, action: #selector(resetTapped), for: .touchUpInside)
        copyPathButton.addTarget(self, action: #selector(copyPathTapped), for: .touchUpInside)
        deleteButton.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
    }

    private func configureResetButton() {
        // DSH-097：撤销 DSH-096 高对比（橙底橙字），与 Android 行动行「重置/删除/复制路径」统一浅灰。
        resetButton.setTitle("重置状态", for: .normal)
        resetButton.setTitleColor(UIColor(red: 0.32, green: 0.36, blue: 0.34, alpha: 1), for: .normal)
        resetButton.backgroundColor = UIColor(red: 0.93, green: 0.94, blue: 0.93, alpha: 1)
        resetButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .medium)
        resetButton.layer.cornerRadius = 8
        resetButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
        resetButton.translatesAutoresizingMaskIntoConstraints = false
        resetButton.heightAnchor.constraint(equalToConstant: 36).isActive = true
    }

    private func configureDeleteButton() {
        // DSH-097：撤销 DSH-096 高对比（红底红字），与 Android 行动行「重置/删除/复制路径」统一浅灰。
        deleteButton.setTitle("删除", for: .normal)
        deleteButton.setTitleColor(UIColor(red: 0.32, green: 0.36, blue: 0.34, alpha: 1), for: .normal)
        deleteButton.backgroundColor = UIColor(red: 0.93, green: 0.94, blue: 0.93, alpha: 1)
        deleteButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .medium)
        deleteButton.layer.cornerRadius = 8
        deleteButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
        deleteButton.translatesAutoresizingMaskIntoConstraints = false
        deleteButton.heightAnchor.constraint(equalToConstant: 36).isActive = true
    }

    /// 与 Android 的「重置/删除/复制路径」行动行同色系（拟态灰底灰字）。
    private func configureCopyPathButton() {
        // DSH-097：保持浅灰（与重置/删除同色），与 Android 行动行三件套统一。
        copyPathButton.setTitle("复制路径", for: .normal)
        copyPathButton.setTitleColor(UIColor(red: 0.32, green: 0.36, blue: 0.34, alpha: 1), for: .normal)
        copyPathButton.backgroundColor = UIColor(red: 0.93, green: 0.94, blue: 0.93, alpha: 1)
        copyPathButton.titleLabel?.font = .systemFont(ofSize: 13, weight: .medium)
        copyPathButton.layer.cornerRadius = 8
        copyPathButton.contentEdgeInsets = UIEdgeInsets(top: 5, left: 8, bottom: 5, right: 8)
        copyPathButton.translatesAutoresizingMaskIntoConstraints = false
        copyPathButton.heightAnchor.constraint(equalToConstant: 36).isActive = true
        copyPathButton.accessibilityLabel = "复制作品文件夹路径"
    }

    private func applyPlatformStyle(_ button: UIButton, isOptimistic: Bool) {
        if isOptimistic {
            // 已分发状态：翡翠绿深绿 + 浅绿高亮底，带清爽边框
            button.setTitleColor(UIColor(red: 0.06, green: 0.48, blue: 0.32, alpha: 1), for: .normal)
            button.backgroundColor = UIColor(red: 0.85, green: 0.96, blue: 0.90, alpha: 1)
            button.layer.borderWidth = 1
            button.layer.borderColor = UIColor(red: 0.55, green: 0.85, blue: 0.68, alpha: 1).cgColor
        } else {
            button.setTitleColor(UIColor(red: 0.12, green: 0.52, blue: 0.32, alpha: 1), for: .normal)
            button.backgroundColor = UIColor(red: 0.92, green: 0.97, blue: 0.94, alpha: 1)
            button.layer.borderWidth = 0
        }
    }

    @objc private func resetTapped() { onReset?() }
    @objc private func deleteTapped() { onDelete?() }
    @objc private func copyPathTapped() { onCopyPath?() }
}

final class OnlineImagePreviewController: UIViewController, UIScrollViewDelegate {
    private var entry: OnlineWorkEntry
    private var currentIndex: Int
    private var initialImage: UIImage?
    private let scrollView = UIScrollView()
    private let counterLabel = UILabel()
    private let imageView = UIImageView()

    /// DSH-141: 删除单图后的外部联动回调 (workId, remainingImages)
    var onImageDeleted: ((String, [String]) -> Void)?

    // 右上角垃圾箱单图删除按钮（DSH-141）
    private let trashBtn = UIButton(type: .system)

    // 右上角原画加载状态药丸（对齐 Android）
    private let statusBadge = UIControl()
    private let badgeStack = UIStackView()
    private let badgeSpinner: UIActivityIndicatorView = {
        if #available(iOS 13.0, *) {
            return UIActivityIndicatorView(style: .medium)
        } else {
            return UIActivityIndicatorView(style: .white)
        }
    }()
    private let badgeLabel = UILabel()

    // 请求竞态防乱序标识
    private var currentRequestId = UUID().uuidString
    // 静默预加载防乱序与生命周期标识
    private var currentPrefetchId = UUID().uuidString

    init(entry: OnlineWorkEntry, initialIndex: Int, initialImage: UIImage? = nil) {
        self.entry = entry
        self.currentIndex = initialIndex
        self.initialImage = initialImage
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupScrollView()
        setupUI()
        setupGestures()

        // 【0ms 极速秒开】：优先展示传入的卡片封面或快速缓存图片，杜绝黑屏等待
        if let initImg = initialImage {
            self.imageView.image = initImg
        }
        loadCurrent(isInitial: true)
    }

    private func setupScrollView() {
        scrollView.frame = view.bounds
        scrollView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scrollView.delegate = self
        scrollView.maximumZoomScale = 3.5
        scrollView.minimumZoomScale = 1.0
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        view.addSubview(scrollView)

        imageView.frame = scrollView.bounds
        imageView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        scrollView.addSubview(imageView)
    }

    private func setupUI() {
        // 底部张数计数
        counterLabel.textAlignment = .center
        counterLabel.textColor = .white
        counterLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        counterLabel.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        counterLabel.layer.cornerRadius = 14
        counterLabel.clipsToBounds = true
        counterLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(counterLabel)

        // 右上角关闭按钮
        let closeBtn = UIButton(type: .system)
        closeBtn.setTitle("✕", for: .normal)
        closeBtn.setTitleColor(.white, for: .normal)
        closeBtn.titleLabel?.font = .systemFont(ofSize: 20, weight: .medium)
        closeBtn.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        closeBtn.layer.cornerRadius = 18
        closeBtn.clipsToBounds = true
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.addTarget(self, action: #selector(close), for: .touchUpInside)
        view.addSubview(closeBtn)

        // DSH-141: 右上角垃圾箱单图删除按钮
        trashBtn.setTitle("🗑️", for: .normal)
        trashBtn.titleLabel?.font = .systemFont(ofSize: 16)
        trashBtn.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        trashBtn.layer.cornerRadius = 18
        trashBtn.clipsToBounds = true
        trashBtn.translatesAutoresizingMaskIntoConstraints = false
        trashBtn.addTarget(self, action: #selector(trashBtnTapped), for: .touchUpInside)
        trashBtn.accessibilityLabel = "删除当前图片"
        view.addSubview(trashBtn)

        // 右上角原画加载状态药丸（对齐 Android）
        statusBadge.backgroundColor = UIColor.black.withAlphaComponent(0.55)
        statusBadge.layer.cornerRadius = 16
        statusBadge.clipsToBounds = true
        statusBadge.translatesAutoresizingMaskIntoConstraints = false
        statusBadge.addTarget(self, action: #selector(retryLoadOriginal), for: .touchUpInside)
        view.addSubview(statusBadge)

        badgeStack.axis = .horizontal
        badgeStack.alignment = .center
        badgeStack.spacing = 6
        badgeStack.isUserInteractionEnabled = false
        badgeStack.translatesAutoresizingMaskIntoConstraints = false
        statusBadge.addSubview(badgeStack)

        badgeSpinner.color = .white
        badgeSpinner.hidesWhenStopped = true
        badgeSpinner.transform = CGAffineTransform(scaleX: 0.7, y: 0.7)

        badgeLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        badgeLabel.textColor = .white

        badgeStack.addArrangedSubview(badgeSpinner)
        badgeStack.addArrangedSubview(badgeLabel)

        NSLayoutConstraint.activate([
            closeBtn.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
            closeBtn.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            closeBtn.widthAnchor.constraint(equalToConstant: 36),
            closeBtn.heightAnchor.constraint(equalToConstant: 36),

            trashBtn.centerYAnchor.constraint(equalTo: closeBtn.centerYAnchor),
            trashBtn.trailingAnchor.constraint(equalTo: closeBtn.leadingAnchor, constant: -10),
            trashBtn.widthAnchor.constraint(equalToConstant: 36),
            trashBtn.heightAnchor.constraint(equalToConstant: 36),

            statusBadge.centerYAnchor.constraint(equalTo: closeBtn.centerYAnchor),
            statusBadge.trailingAnchor.constraint(equalTo: trashBtn.leadingAnchor, constant: -10),
            statusBadge.heightAnchor.constraint(equalToConstant: 32),

            badgeStack.leadingAnchor.constraint(equalTo: statusBadge.leadingAnchor, constant: 10),
            badgeStack.trailingAnchor.constraint(equalTo: statusBadge.trailingAnchor, constant: -10),
            badgeStack.centerYAnchor.constraint(equalTo: statusBadge.centerYAnchor),

            counterLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            counterLabel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
            counterLabel.widthAnchor.constraint(equalToConstant: 90),
            counterLabel.heightAnchor.constraint(equalToConstant: 28)
        ])
    }

    private func setupGestures() {
        let swipeLeft = UISwipeGestureRecognizer(target: self, action: #selector(nextImage))
        swipeLeft.direction = .left
        view.addGestureRecognizer(swipeLeft)

        let swipeRight = UISwipeGestureRecognizer(target: self, action: #selector(prevImage))
        swipeRight.direction = .right
        view.addGestureRecognizer(swipeRight)

        // 双击放大/还原
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        view.addGestureRecognizer(doubleTap)
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        if scrollView.zoomScale > 1.0 {
            scrollView.setZoomScale(1.0, animated: true)
        } else {
            let point = gesture.location(in: imageView)
            let zoomRect = zoomRectForScale(scale: 2.5, center: point)
            scrollView.zoom(to: zoomRect, animated: true)
        }
    }

    private func zoomRectForScale(scale: CGFloat, center: CGPoint) -> CGRect {
        var zoomRect = CGRect.zero
        zoomRect.size.height = imageView.frame.size.height / scale
        zoomRect.size.width  = imageView.frame.size.width  / scale
        zoomRect.origin.x    = center.x - (zoomRect.size.width  / 2.0)
        zoomRect.origin.y    = center.y - (zoomRect.size.height / 2.0)
        return zoomRect
    }

    @objc private func close() {
        currentPrefetchId = ""
        dismiss(animated: true)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        currentPrefetchId = ""
    }

    deinit {
        currentPrefetchId = ""
    }

    @objc private func nextImage() {
        if currentIndex < entry.images.count - 1 {
            currentIndex += 1
            loadCurrent()
        }
    }

    @objc private func prevImage() {
        if currentIndex > 0 {
            currentIndex -= 1
            loadCurrent()
        }
    }

    @objc private func retryLoadOriginal() {
        loadCurrent(forceReloadOriginal: true)
    }

    // MARK: - DSH-141: 删除单张图片
    @objc private func trashBtnTapped() {
        if entry.images.count <= 1 {
            let alert = UIAlertController(
                title: "无法删除单张",
                message: "作品至少需保留 1 张图片。如需整套下架，请直接在相册卡片底部点击「删除」按钮。",
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: "我知道了", style: .default, handler: nil))
            present(alert, animated: true, completion: nil)
            return
        }

        guard currentIndex >= 0 && currentIndex < entry.images.count else { return }
        let targetImage = entry.images[currentIndex]

        let alert = UIAlertController(
            title: "删除单张图片",
            message: "确定要删除第 \(currentIndex + 1) 张图片（\(targetImage)）吗？\n\n图片将安全备份至垃圾样本库，作品其余图片仍将保留。",
            preferredStyle: .actionSheet
        )
        alert.addAction(UIAlertAction(title: "删除单图", style: .destructive, handler: { [weak self] _ in
            guard let self = self else { return }
            self.performDeleteCurrentImage(targetImage)
        }))
        alert.addAction(UIAlertAction(title: "取消", style: .cancel, handler: nil))

        if let popover = alert.popoverPresentationController {
            popover.sourceView = trashBtn
            popover.sourceRect = trashBtn.bounds
            popover.permittedArrowDirections = [.up, .down]
        }
        present(alert, animated: true, completion: nil)
    }

    private func performDeleteCurrentImage(_ imageName: String) {
        let wid = entry.id
        badgeLabel.text = "🗑️ 正在删除…"
        OnlineGalleryClient.shared.deleteOnlineImage(workId: wid, image: imageName) { [weak self] ok, msg, remaining in
            guard let self = self else { return }
            if ok {
                self.showToast("已删除: \(imageName)")
                var newImages = self.entry.images
                newImages.removeAll { $0 == imageName }
                if !remaining.isEmpty {
                    newImages = remaining
                }
                self.entry = OnlineWorkEntry(
                    id: self.entry.id,
                    title: self.entry.title,
                    destination: self.entry.destination,
                    stage: self.entry.stage,
                    useCount: self.entry.useCount,
                    maxUses: self.entry.maxUses,
                    used: self.entry.used,
                    remainingUses: self.entry.remainingUses,
                    statusLabel: self.entry.statusLabel,
                    images: newImages,
                    imageCount: newImages.count,
                    copyText: self.entry.copyText,
                    hasCopyText: self.entry.hasCopyText,
                    dispatchedTo: self.entry.dispatchedTo,
                    updatedAt: self.entry.updatedAt,
                    garbage: self.entry.garbage,
                    garbageRemark: self.entry.garbageRemark,
                    path: self.entry.path,
                    firstSharedAtMs: self.entry.firstSharedAtMs,
                    expireAtMs: self.entry.expireAtMs,
                    originDevice: self.entry.originDevice,
                    dispatchedVersions: self.entry.dispatchedVersions,
                    season: self.entry.season,
                    flowType: self.entry.flowType,
                    tags: self.entry.tags,
                    sourceImages: self.entry.sourceImages,
                    sourceNames: self.entry.sourceNames,
                    hasSourceCompare: self.entry.hasSourceCompare,
                    maxSimilarity: self.entry.maxSimilarity,
                    similarityTag: self.entry.similarityTag
                )
                self.onImageDeleted?(wid, newImages)

                if self.entry.images.isEmpty {
                    self.close()
                } else {
                    if self.currentIndex >= self.entry.images.count {
                        self.currentIndex = max(0, self.entry.images.count - 1)
                    }
                    self.imageView.image = nil
                    self.loadCurrent(isInitial: false)
                }
            } else {
                let errAlert = UIAlertController(title: "删除失败", message: msg.isEmpty ? "网络错误" : msg, preferredStyle: .alert)
                errAlert.addAction(UIAlertAction(title: "确定", style: .default, handler: nil))
                self.present(errAlert, animated: true, completion: nil)
            }
        }
    }

    private func showToast(_ text: String) {
        let toast = UILabel()
        toast.text = text
        toast.backgroundColor = UIColor(white: 0.1, alpha: 0.85)
        toast.textColor = .white
        toast.font = .systemFont(ofSize: 13, weight: .semibold)
        toast.textAlignment = .center
        toast.layer.cornerRadius = 14
        toast.clipsToBounds = true
        toast.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(toast)
        NSLayoutConstraint.activate([
            toast.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            toast.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 60),
            toast.heightAnchor.constraint(equalToConstant: 32),
            toast.widthAnchor.constraint(greaterThanOrEqualToConstant: 120)
        ])
        UIView.animate(withDuration: 0.3, delay: 1.5, options: .curveEaseOut, animations: {
            toast.alpha = 0
        }, completion: { _ in
            toast.removeFromSuperview()
        })
    }

    private func updateBadge(status: ImageLoadStatus) {
        switch status {
        case .loading:
            badgeSpinner.startAnimating()
            badgeLabel.text = "⏳ 正在加载原图..."
            badgeLabel.textColor = UIColor(red: 1.0, green: 0.88, blue: 0.45, alpha: 1.0)
            statusBadge.backgroundColor = UIColor(red: 0.28, green: 0.18, blue: 0.05, alpha: 0.75)
        case .cachedFull, .loadedFull:
            badgeSpinner.stopAnimating()
            badgeLabel.text = "✅ 100% 原图"
            badgeLabel.textColor = UIColor(red: 0.35, green: 0.95, blue: 0.55, alpha: 1.0)
            statusBadge.backgroundColor = UIColor(red: 0.06, green: 0.28, blue: 0.14, alpha: 0.75)
        case .failed:
            badgeSpinner.stopAnimating()
            badgeLabel.text = "⚠️ 缩略图 (点此重试)"
            badgeLabel.textColor = UIColor(white: 0.85, alpha: 1.0)
            statusBadge.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        }
    }

    private enum ImageLoadStatus {
        case loading
        case cachedFull
        case loadedFull
        case failed
    }

    private func loadCurrent(forceReloadOriginal: Bool = false, isInitial: Bool = false) {
        guard currentIndex >= 0, currentIndex < entry.images.count else { return }
        scrollView.setZoomScale(1.0, animated: false)
        counterLabel.text = "\(currentIndex + 1) / \(entry.images.count)"

        let path = entry.images[currentIndex]
        let requestId = UUID().uuidString
        self.currentRequestId = requestId

        // 尝试从内存/磁盘同步获取缩略图或原图（有图则直接呈现，杜绝黑屏）
        if let fastThumb = OnlineGalleryClient.shared.getFastCachedImage(path: path, workId: entry.id, isThumbnail: true) {
            self.imageView.image = fastThumb
        } else if !isInitial && self.imageView.image == nil {
            self.imageView.image = nil
        }

        // 1. 异步缩略图补充（若同步未命中且当前无图）
        if self.imageView.image == nil {
            OnlineGalleryClient.shared.loadImage(path: path, workId: entry.id, isThumbnail: true) { [weak self] thumbImg in
                guard let self = self, self.currentRequestId == requestId else { return }
                if let thumb = thumbImg, self.imageView.image == nil {
                    self.imageView.image = thumb
                }
            }
        }

        // 2. 检查高清原图是否已在内存/本地磁盘命中
        let isFullCached = !forceReloadOriginal && OnlineGalleryClient.shared.hasFullImageCached(path: path, workId: entry.id)
        if isFullCached {
            if let fullSync = OnlineGalleryClient.shared.getFastCachedImage(path: path, workId: entry.id, isThumbnail: false) {
                self.imageView.image = fullSync
                updateBadge(status: .cachedFull)
            } else {
                updateBadge(status: .loading)
            }
        } else {
            updateBadge(status: .loading)
        }

        // 3. 异步拉取 100% 原始画质（与 Android 端两阶段原画逻辑 1:1 对齐）
        OnlineGalleryClient.shared.loadImage(path: path, workId: entry.id, isThumbnail: false) { [weak self] fullImg in
            guard let self = self, self.currentRequestId == requestId else { return }
            if let full = fullImg {
                UIView.transition(with: self.imageView, duration: 0.2, options: .transitionCrossDissolve, animations: {
                    self.imageView.image = full
                }, completion: nil)
                self.updateBadge(status: .loadedFull)
            } else {
                if !isFullCached {
                    self.updateBadge(status: .failed)
                }
            }
        }

        // 4. 【同作品大图后台并发静默预加载】
        prefetchRemainingImages(for: entry, startingAt: currentIndex)
    }

    /// 同作品大图后台并发静默预加载：
    /// 打开大图或切换图片时，自动遍历除当前图外的所有图片。
    /// 先预加载所有剩余图片的缩略图（isThumbnail: true），0秒充盈磁盘与内存缓存；
    /// 紧接着在后台按滑动可能顺序（currentIndex+1, currentIndex-1, currentIndex+2...）逐张预加载 100% 高清原图（isThumbnail: false）；
    /// 保证用户向左/向右滑动翻页时直接 0 延迟秒开！
    private func prefetchRemainingImages(for entry: OnlineWorkEntry, startingAt currentIndex: Int) {
        guard entry.images.count > 1 else { return }
        let prefetchId = UUID().uuidString
        self.currentPrefetchId = prefetchId

        var orderedIndices: [Int] = []
        var step = 1
        while orderedIndices.count < entry.images.count - 1 {
            let next = currentIndex + step
            if next >= 0 && next < entry.images.count && next != currentIndex && !orderedIndices.contains(next) {
                orderedIndices.append(next)
            }
            let prev = currentIndex - step
            if prev >= 0 && prev < entry.images.count && prev != currentIndex && !orderedIndices.contains(prev) {
                orderedIndices.append(prev)
            }
            step += 1
            if step > entry.images.count { break }
        }

        // 1. 优先并发预加载所有剩余图片的缩略图（isThumbnail: true），0秒充盈磁盘与内存缓存
        for idx in orderedIndices {
            let path = entry.images[idx]
            OnlineGalleryClient.shared.loadImage(path: path, workId: entry.id, isThumbnail: true) { _ in }
        }

        // 2. 紧接着在后台按滑动可能顺序逐张预加载 100% 高清原图（isThumbnail: false）
        prefetchNextFullImage(for: entry, orderedIndices: orderedIndices, pointer: 0, prefetchId: prefetchId)
    }

    private func prefetchNextFullImage(for entry: OnlineWorkEntry, orderedIndices: [Int], pointer: Int, prefetchId: String) {
        guard pointer < orderedIndices.count else { return }
        guard self.currentPrefetchId == prefetchId else { return }

        let idx = orderedIndices[pointer]
        let path = entry.images[idx]

        // 检查原图是否已在磁盘/内存中缓存，若已就绪直接递归检查下一张
        if OnlineGalleryClient.shared.hasFullImageCached(path: path, workId: entry.id) {
            self.prefetchNextFullImage(for: entry, orderedIndices: orderedIndices, pointer: pointer + 1, prefetchId: prefetchId)
            return
        }

        OnlineGalleryClient.shared.loadImage(path: path, workId: entry.id, isThumbnail: false) { [weak self] _ in
            guard let self = self, self.currentPrefetchId == prefetchId else { return }
            self.prefetchNextFullImage(for: entry, orderedIndices: orderedIndices, pointer: pointer + 1, prefetchId: prefetchId)
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        return imageView
    }
}

// MARK: - 多选标签筛选半屏面板（季节标签 + 流量标签，支持同时多选，iOS 12+ 全兼容）

final class OnlineFilterSheetViewController: UIViewController {
    private let seasonOptions: [String]
    private let flowTypeOptions: [String]
    private let seasonCounts: [String: Int]
    private let flowCounts: [String: Int]
    private var selectedSeasons: Set<String>
    private var selectedFlowTypes: Set<String>

    var onApply: ((Set<String>, Set<String>) -> Void)?

    private let cardView = UIView()
    private var seasonButtons: [String: UIButton] = [:]
    private var flowButtons: [String: UIButton] = [:]
    private let confirmButton = UIButton(type: .system)

    private let primaryGreen = UIColor(red: 15/255, green: 135/255, blue: 88/255, alpha: 1)
    private let lightGreenBg = UIColor(red: 226/255, green: 244/255, blue: 236/255, alpha: 1)

    init(
        seasonOptions: [String],
        flowTypeOptions: [String],
        seasonCounts: [String: Int],
        flowCounts: [String: Int],
        selectedSeasons: Set<String>,
        selectedFlowTypes: Set<String>
    ) {
        self.seasonOptions = seasonOptions
        self.flowTypeOptions = flowTypeOptions
        self.seasonCounts = seasonCounts
        self.flowCounts = flowCounts
        self.selectedSeasons = selectedSeasons
        self.selectedFlowTypes = selectedFlowTypes
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.45)

        let bgTap = UITapGestureRecognizer(target: self, action: #selector(dismissCancel))
        let backdrop = UIView()
        backdrop.translatesAutoresizingMaskIntoConstraints = false
        backdrop.addGestureRecognizer(bgTap)
        view.addSubview(backdrop)

        cardView.translatesAutoresizingMaskIntoConstraints = false
        cardView.backgroundColor = AppColors.background
        cardView.layer.cornerRadius = 18
        if #available(iOS 11.0, *) {
            cardView.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        }
        view.addSubview(cardView)

        // 顶部栏：重置 | 标题 | 关闭
        let resetBtn = UIButton(type: .system)
        resetBtn.translatesAutoresizingMaskIntoConstraints = false
        resetBtn.setTitle("重置清空", for: .normal)
        resetBtn.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        resetBtn.setTitleColor(.systemRed, for: .normal)
        resetBtn.addTarget(self, action: #selector(resetTapped), for: .touchUpInside)

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = "作品标签筛选（支持多选）"
        titleLabel.font = UIFont.boldSystemFont(ofSize: 16)
        titleLabel.textAlignment = .center

        let closeBtn = UIButton(type: .system)
        closeBtn.translatesAutoresizingMaskIntoConstraints = false
        closeBtn.setTitle("取消", for: .normal)
        closeBtn.titleLabel?.font = UIFont.systemFont(ofSize: 14)
        closeBtn.setTitleColor(AppColors.secondaryText, for: .normal)
        closeBtn.addTarget(self, action: #selector(dismissCancel), for: .touchUpInside)

        let headerRow = UIStackView(arrangedSubviews: [resetBtn, titleLabel, closeBtn])
        headerRow.translatesAutoresizingMaskIntoConstraints = false
        headerRow.axis = .horizontal
        headerRow.alignment = .center
        headerRow.distribution = .equalCentering
        cardView.addSubview(headerRow)

        // 季节分栏标题
        let seasonTitle = UILabel()
        seasonTitle.translatesAutoresizingMaskIntoConstraints = false
        seasonTitle.text = "1. 季节标签（可多选，不选默认显示全部季节）"
        seasonTitle.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        seasonTitle.textColor = AppColors.secondaryText

        // 季节按钮两行网格
        let seasonRow1 = UIStackView()
        seasonRow1.axis = .horizontal
        seasonRow1.spacing = 8
        seasonRow1.distribution = .fillEqually

        let seasonRow2 = UIStackView()
        seasonRow2.axis = .horizontal
        seasonRow2.spacing = 8
        seasonRow2.distribution = .fillEqually

        for (idx, opt) in seasonOptions.enumerated() {
            let count = seasonCounts[opt] ?? 0
            let btn = makePillButton(title: "\(opt) (\(count))", key: opt, action: #selector(seasonOptionTapped(_:)))
            seasonButtons[opt] = btn
            if idx < 3 {
                seasonRow1.addArrangedSubview(btn)
            } else {
                seasonRow2.addArrangedSubview(btn)
            }
        }

        // 流量分栏标题
        let flowTitle = UILabel()
        flowTitle.translatesAutoresizingMaskIntoConstraints = false
        flowTitle.text = "2. 流量标签（可多选，不选默认显示全部类型）"
        flowTitle.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        flowTitle.textColor = AppColors.secondaryText

        let flowRow = UIStackView()
        flowRow.axis = .horizontal
        flowRow.spacing = 10
        flowRow.distribution = .fillEqually
        for opt in flowTypeOptions {
            let count = flowCounts[opt] ?? 0
            let btn = makePillButton(title: "\(opt) (\(count))", key: opt, action: #selector(flowOptionTapped(_:)))
            flowButtons[opt] = btn
            flowRow.addArrangedSubview(btn)
        }

        confirmButton.translatesAutoresizingMaskIntoConstraints = false
        confirmButton.backgroundColor = primaryGreen
        confirmButton.setTitleColor(.white, for: .normal)
        confirmButton.titleLabel?.font = UIFont.boldSystemFont(ofSize: 16)
        confirmButton.layer.cornerRadius = 12
        confirmButton.addTarget(self, action: #selector(confirmTapped), for: .touchUpInside)

        let bodyStack = UIStackView(arrangedSubviews: [
            seasonTitle,
            seasonRow1,
            seasonRow2,
            flowTitle,
            flowRow,
            confirmButton
        ])
        bodyStack.translatesAutoresizingMaskIntoConstraints = false
        bodyStack.axis = .vertical
        bodyStack.spacing = 12
        cardView.addSubview(bodyStack)

        NSLayoutConstraint.activate([
            backdrop.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            backdrop.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            backdrop.topAnchor.constraint(equalTo: view.topAnchor),
            backdrop.bottomAnchor.constraint(equalTo: cardView.topAnchor),

            cardView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            cardView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            cardView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            headerRow.leadingAnchor.constraint(equalTo: cardView.leadingAnchor, constant: 16),
            headerRow.trailingAnchor.constraint(equalTo: cardView.trailingAnchor, constant: -16),
            headerRow.topAnchor.constraint(equalTo: cardView.topAnchor, constant: 14),
            headerRow.heightAnchor.constraint(equalToConstant: 32),

            bodyStack.leadingAnchor.constraint(equalTo: cardView.leadingAnchor, constant: 16),
            bodyStack.trailingAnchor.constraint(equalTo: cardView.trailingAnchor, constant: -16),
            bodyStack.topAnchor.constraint(equalTo: headerRow.bottomAnchor, constant: 12),
            bodyStack.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),

            seasonRow1.heightAnchor.constraint(equalToConstant: 38),
            seasonRow2.heightAnchor.constraint(equalToConstant: 38),
            flowRow.heightAnchor.constraint(equalToConstant: 40),
            confirmButton.heightAnchor.constraint(equalToConstant: 46)
        ])

        refreshAllPillStyles()
    }

    private func makePillButton(title: String, key: String, action: Selector) -> UIButton {
        let btn = UIButton(type: .system)
        btn.translatesAutoresizingMaskIntoConstraints = false
        btn.accessibilityIdentifier = key
        btn.setTitle(title, for: .normal)
        btn.titleLabel?.font = UIFont.systemFont(ofSize: 13, weight: .medium)
        btn.titleLabel?.adjustsFontSizeToFitWidth = true
        btn.layer.cornerRadius = 10
        btn.layer.borderWidth = 1
        btn.addTarget(self, action: action, for: .touchUpInside)
        return btn
    }

    private func refreshAllPillStyles() {
        for (opt, btn) in seasonButtons {
            let count = seasonCounts[opt] ?? 0
            let isSel = selectedSeasons.contains(opt)
            applyPillStyle(btn, title: (isSel ? "✓ " : "") + "\(opt) (\(count))", isSelected: isSel)
        }
        for (opt, btn) in flowButtons {
            let count = flowCounts[opt] ?? 0
            let isSel = selectedFlowTypes.contains(opt)
            applyPillStyle(btn, title: (isSel ? "✓ " : "") + "\(opt) (\(count))", isSelected: isSel)
        }
        let totalSel = selectedSeasons.count + selectedFlowTypes.count
        if totalSel == 0 {
            confirmButton.setTitle("确定（显示全部作品）", for: .normal)
        } else {
            confirmButton.setTitle("确定应用（已选 \(totalSel) 项标签）", for: .normal)
        }
    }

    private func applyPillStyle(_ btn: UIButton, title: String, isSelected: Bool) {
        btn.setTitle(title, for: .normal)
        if isSelected {
            btn.backgroundColor = primaryGreen
            btn.setTitleColor(.white, for: .normal)
            btn.layer.borderColor = primaryGreen.cgColor
        } else {
            btn.backgroundColor = AppColors.secondaryBackground
            btn.setTitleColor(UIColor.labelCompatible, for: .normal)
            btn.layer.borderColor = UIColor.lightGray.withAlphaComponent(0.35).cgColor
        }
    }

    @objc private func seasonOptionTapped(_ sender: UIButton) {
        guard let key = sender.accessibilityIdentifier else { return }
        if selectedSeasons.contains(key) {
            selectedSeasons.remove(key)
        } else {
            selectedSeasons.insert(key)
        }
        refreshAllPillStyles()
    }

    @objc private func flowOptionTapped(_ sender: UIButton) {
        guard let key = sender.accessibilityIdentifier else { return }
        if selectedFlowTypes.contains(key) {
            selectedFlowTypes.remove(key)
        } else {
            selectedFlowTypes.insert(key)
        }
        refreshAllPillStyles()
    }

    @objc private func resetTapped() {
        selectedSeasons.removeAll()
        selectedFlowTypes.removeAll()
        refreshAllPillStyles()
    }

    @objc private func confirmTapped() {
        let s = selectedSeasons
        let f = selectedFlowTypes
        dismiss(animated: true) { [weak self] in
            self?.onApply?(s, f)
        }
    }

    @objc private func dismissCancel() {
        dismiss(animated: true)
    }
}

private extension UIColor {
    static var labelCompatible: UIColor {
        if #available(iOS 13.0, *) {
            return .label
        }
        return .darkText
    }
}

// MARK: - 全屏原素材 vs AI 成品同框对比预览控制器（支持左右/上下同框切换、逐页翻页）

final class OnlineComparePreviewController: UIViewController, UIScrollViewDelegate {
    private let entry: OnlineWorkEntry
    private var currentIndex: Int

    private let topBar = UIView()
    private let titleLabel = UILabel()
    private let pageLabel = UILabel()
    private let hintLabel = UILabel()
    private let similarityBadge = UILabel()
    private let layoutModeButton = UIButton(type: .system)
    private let closeButton = UIButton(type: .system)

    private let containerStack = UIStackView()
    private let leftContainer = UIView()
    private let rightContainer = UIView()
    private let leftImageView = UIImageView()
    private let rightImageView = UIImageView()
    private let leftBadge = UILabel()
    private let rightBadge = UILabel()
    private let leftProgressLabel = UILabel()
    private let rightProgressLabel = UILabel()
    private let emptyLeftLabel = UILabel()

    private let bottomBar = UIView()
    private let prevButton = UIButton(type: .system)
    private let nextButton = UIButton(type: .system)

    /// 对比排布模式：水平左右并排 vs 垂直上下堆叠
    private var isHorizontalLayout: Bool = false

    init(entry: OnlineWorkEntry, initialIndex: Int = 0) {
        self.entry = entry
        self.currentIndex = max(0, min(initialIndex, max(0, entry.images.count - 1)))
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupUI()
        setupGestures()
        loadPage(currentIndex)
    }

    private func setupUI() {
        // 顶部导航栏
        topBar.translatesAutoresizingMaskIntoConstraints = false
        topBar.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        view.addSubview(topBar)

        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = "对比视图 · \(entry.title)"
        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        titleLabel.lineBreakMode = .byTruncatingMiddle
        topBar.addSubview(titleLabel)

        similarityBadge.translatesAutoresizingMaskIntoConstraints = false
        if entry.maxSimilarity > 0 {
            let pct = Int(round(entry.maxSimilarity * 100))
            similarityBadge.text = "🛡️ 相似度 \(pct)%"
        } else {
            similarityBadge.text = "对比视图"
        }
        similarityBadge.font = .systemFont(ofSize: 12, weight: .semibold)
        similarityBadge.textColor = UIColor(red: 0.35, green: 0.95, blue: 0.55, alpha: 1.0)
        similarityBadge.backgroundColor = UIColor(red: 0.06, green: 0.35, blue: 0.18, alpha: 0.8)
        similarityBadge.textAlignment = .center
        similarityBadge.layer.cornerRadius = 8
        similarityBadge.clipsToBounds = true
        topBar.addSubview(similarityBadge)

        layoutModeButton.translatesAutoresizingMaskIntoConstraints = false
        layoutModeButton.setTitle("↕️ 上下同框", for: .normal)
        layoutModeButton.titleLabel?.font = .systemFont(ofSize: 12, weight: .semibold)
        layoutModeButton.setTitleColor(.white, for: .normal)
        layoutModeButton.backgroundColor = UIColor(white: 0.22, alpha: 0.8)
        layoutModeButton.layer.cornerRadius = 8
        layoutModeButton.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
        layoutModeButton.addTarget(self, action: #selector(toggleLayoutMode), for: .touchUpInside)
        topBar.addSubview(layoutModeButton)

        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.setTitle("✕", for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 16, weight: .bold)
        closeButton.backgroundColor = UIColor(white: 0.22, alpha: 0.8)
        closeButton.layer.cornerRadius = 16
        closeButton.contentEdgeInsets = .zero
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        topBar.addSubview(closeButton)

        // 底部控制栏
        bottomBar.translatesAutoresizingMaskIntoConstraints = false
        bottomBar.backgroundColor = UIColor.black.withAlphaComponent(0.65)
        view.addSubview(bottomBar)

        pageLabel.translatesAutoresizingMaskIntoConstraints = false
        pageLabel.textAlignment = .center
        pageLabel.textColor = .white
        pageLabel.font = .systemFont(ofSize: 13, weight: .bold)
        bottomBar.addSubview(pageLabel)

        prevButton.translatesAutoresizingMaskIntoConstraints = false
        prevButton.setTitle("‹", for: .normal)
        prevButton.setTitleColor(.white, for: .normal)
        prevButton.titleLabel?.font = .systemFont(ofSize: 22, weight: .bold)
        prevButton.backgroundColor = UIColor(white: 0.22, alpha: 0.8)
        prevButton.layer.cornerRadius = 16
        prevButton.addTarget(self, action: #selector(prevPage), for: .touchUpInside)
        bottomBar.addSubview(prevButton)

        nextButton.translatesAutoresizingMaskIntoConstraints = false
        nextButton.setTitle("›", for: .normal)
        nextButton.setTitleColor(.white, for: .normal)
        nextButton.titleLabel?.font = .systemFont(ofSize: 22, weight: .bold)
        nextButton.backgroundColor = UIColor(white: 0.22, alpha: 0.8)
        nextButton.layer.cornerRadius = 16
        nextButton.addTarget(self, action: #selector(nextPage), for: .touchUpInside)
        bottomBar.addSubview(nextButton)

        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        hintLabel.text = "左右轻扫或点击 ‹ › 翻页"
        hintLabel.textColor = UIColor(white: 0.65, alpha: 1.0)
        hintLabel.font = .systemFont(ofSize: 11, weight: .regular)
        hintLabel.textAlignment = .center
        bottomBar.addSubview(hintLabel)

        // 中间主对比区域
        containerStack.translatesAutoresizingMaskIntoConstraints = false
        containerStack.axis = .vertical
        containerStack.spacing = 8
        containerStack.distribution = .fillEqually
        containerStack.alignment = .fill
        view.addSubview(containerStack)

        setupImageViewContainer(
            container: leftContainer, imageView: leftImageView,
            badge: leftBadge, badgeText: "素材", badgeBg: UIColor.black.withAlphaComponent(0.72),
            progressLabel: leftProgressLabel
        )
        emptyLeftLabel.text = "该页未关联素材\n或原图已删除"
        emptyLeftLabel.textColor = .lightGray
        emptyLeftLabel.font = .systemFont(ofSize: 13)
        emptyLeftLabel.textAlignment = .center
        emptyLeftLabel.numberOfLines = 2
        emptyLeftLabel.translatesAutoresizingMaskIntoConstraints = false
        leftContainer.addSubview(emptyLeftLabel)
        NSLayoutConstraint.activate([
            emptyLeftLabel.centerXAnchor.constraint(equalTo: leftContainer.centerXAnchor),
            emptyLeftLabel.centerYAnchor.constraint(equalTo: leftContainer.centerYAnchor)
        ])

        setupImageViewContainer(
            container: rightContainer, imageView: rightImageView,
            badge: rightBadge, badgeText: "成品", badgeBg: UIColor(red: 0.06, green: 0.52, blue: 0.34, alpha: 0.88),
            progressLabel: rightProgressLabel
        )

        containerStack.addArrangedSubview(leftContainer)
        containerStack.addArrangedSubview(rightContainer)

        NSLayoutConstraint.activate([
            topBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            topBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            topBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            topBar.heightAnchor.constraint(equalToConstant: 44),

            closeButton.trailingAnchor.constraint(equalTo: topBar.trailingAnchor, constant: -12),
            closeButton.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            closeButton.widthAnchor.constraint(equalToConstant: 32),
            closeButton.heightAnchor.constraint(equalToConstant: 32),

            layoutModeButton.trailingAnchor.constraint(equalTo: closeButton.leadingAnchor, constant: -8),
            layoutModeButton.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            layoutModeButton.heightAnchor.constraint(equalToConstant: 32),

            similarityBadge.trailingAnchor.constraint(equalTo: layoutModeButton.leadingAnchor, constant: -8),
            similarityBadge.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),
            similarityBadge.widthAnchor.constraint(greaterThanOrEqualToConstant: 72),
            similarityBadge.heightAnchor.constraint(equalToConstant: 32),

            titleLabel.leadingAnchor.constraint(equalTo: topBar.leadingAnchor, constant: 14),
            titleLabel.trailingAnchor.constraint(equalTo: similarityBadge.leadingAnchor, constant: -8),
            titleLabel.centerYAnchor.constraint(equalTo: topBar.centerYAnchor),

            bottomBar.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),
            bottomBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomBar.heightAnchor.constraint(equalToConstant: 52),

            pageLabel.centerXAnchor.constraint(equalTo: bottomBar.centerXAnchor),
            pageLabel.topAnchor.constraint(equalTo: bottomBar.topAnchor, constant: 5),

            prevButton.trailingAnchor.constraint(equalTo: pageLabel.leadingAnchor, constant: -16),
            prevButton.centerYAnchor.constraint(equalTo: pageLabel.centerYAnchor),
            prevButton.widthAnchor.constraint(equalToConstant: 32),
            prevButton.heightAnchor.constraint(equalToConstant: 32),

            nextButton.leadingAnchor.constraint(equalTo: pageLabel.trailingAnchor, constant: 16),
            nextButton.centerYAnchor.constraint(equalTo: pageLabel.centerYAnchor),
            nextButton.widthAnchor.constraint(equalToConstant: 32),
            nextButton.heightAnchor.constraint(equalToConstant: 32),

            hintLabel.centerXAnchor.constraint(equalTo: bottomBar.centerXAnchor),
            hintLabel.topAnchor.constraint(equalTo: pageLabel.bottomAnchor, constant: 3),

            containerStack.topAnchor.constraint(equalTo: topBar.bottomAnchor, constant: 6),
            containerStack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 8),
            containerStack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            containerStack.bottomAnchor.constraint(equalTo: bottomBar.topAnchor, constant: -6)
        ])
    }

    private func setupImageViewContainer(
        container: UIView, imageView: UIImageView,
        badge: UILabel, badgeText: String, badgeBg: UIColor,
        progressLabel: UILabel
    ) {
        container.translatesAutoresizingMaskIntoConstraints = false
        container.backgroundColor = UIColor(white: 0.08, alpha: 1)
        container.layer.cornerRadius = 10
        container.layer.borderWidth = 0
        container.clipsToBounds = true

        imageView.translatesAutoresizingMaskIntoConstraints = false
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        container.addSubview(imageView)

        badge.translatesAutoresizingMaskIntoConstraints = false
        badge.text = badgeText
        badge.font = .systemFont(ofSize: 10.5, weight: .bold)
        badge.textColor = .white
        badge.backgroundColor = badgeBg
        badge.textAlignment = .center
        badge.layer.cornerRadius = 4
        badge.clipsToBounds = true
        container.addSubview(badge)

        progressLabel.translatesAutoresizingMaskIntoConstraints = false
        progressLabel.font = .systemFont(ofSize: 10, weight: .medium)
        progressLabel.textColor = UIColor(white: 0.9, alpha: 1)
        progressLabel.backgroundColor = UIColor.black.withAlphaComponent(0.68)
        progressLabel.textAlignment = .center
        progressLabel.layer.cornerRadius = 4
        progressLabel.clipsToBounds = true
        progressLabel.isHidden = true
        container.addSubview(progressLabel)

        NSLayoutConstraint.activate([
            imageView.topAnchor.constraint(equalTo: container.topAnchor),
            imageView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            imageView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            imageView.bottomAnchor.constraint(equalTo: container.bottomAnchor),

            badge.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -8),
            badge.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            badge.heightAnchor.constraint(equalToConstant: 22),
            badge.widthAnchor.constraint(equalToConstant: 38),

            progressLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -8),
            progressLabel.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8),
            progressLabel.heightAnchor.constraint(equalToConstant: 20),
            progressLabel.widthAnchor.constraint(greaterThanOrEqualToConstant: 58)
        ])
    }

    private func setupGestures() {
        let swipeLeft = UISwipeGestureRecognizer(target: self, action: #selector(nextPage))
        swipeLeft.direction = .left
        view.addGestureRecognizer(swipeLeft)

        let swipeRight = UISwipeGestureRecognizer(target: self, action: #selector(prevPage))
        swipeRight.direction = .right
        view.addGestureRecognizer(swipeRight)
    }

    private func loadPage(_ pageIndex: Int) {
        guard pageIndex >= 0 && pageIndex < entry.images.count else { return }
        currentIndex = pageIndex
        pageLabel.text = "P\(pageIndex + 1) / \(entry.images.count)"

        prevButton.isEnabled = (currentIndex > 0)
        prevButton.alpha = (currentIndex > 0) ? 1.0 : 0.35
        nextButton.isEnabled = (currentIndex < entry.images.count - 1)
        nextButton.alpha = (currentIndex < entry.images.count - 1) ? 1.0 : 0.35

        let outputPath = entry.images[pageIndex]
        let sourcePath = pageIndex < entry.sourceImages.count ? entry.sourceImages[pageIndex] : ""

        // 1. 成品图：优先缩略图秒开占位（0ms 防黑屏）
        if let fastCachedOut = OnlineGalleryClient.shared.getFastCachedImage(path: outputPath, workId: entry.id, isThumbnail: true) {
            rightImageView.image = fastCachedOut
        } else {
            OnlineGalleryClient.shared.loadImage(path: outputPath, workId: entry.id, isThumbnail: true, maxPixel: 300) { [weak self] thumb in
                guard let self = self, self.currentIndex == pageIndex else { return }
                if self.rightImageView.image == nil {
                    self.rightImageView.image = thumb
                }
            }
        }

        // 异步加载成品 100% 高清原图（带百分比进度）
        rightProgressLabel.isHidden = false
        rightProgressLabel.text = "原图加载中…"
        OnlineGalleryClient.shared.loadImageWithProgress(
            path: outputPath, workId: entry.id, isThumbnail: false,
            onProgress: { [weak self] pct in
                guard let self = self, self.currentIndex == pageIndex else { return }
                self.rightProgressLabel.isHidden = false
                self.rightProgressLabel.text = "原图 \(pct)%"
            },
            completion: { [weak self] img in
                guard let self = self, self.currentIndex == pageIndex else { return }
                if let img = img {
                    self.rightImageView.image = img
                    self.rightProgressLabel.text = "原图已就绪"
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                        guard let self = self, self.currentIndex == pageIndex else { return }
                        self.rightProgressLabel.isHidden = true
                    }
                } else {
                    self.rightProgressLabel.text = "加载失败"
                }
            }
        )

        // 2. 原素材图：优先缩略图秒开占位（0ms 防黑屏）
        if sourcePath.isEmpty {
            leftImageView.image = nil
            emptyLeftLabel.isHidden = false
            leftProgressLabel.isHidden = true
        } else {
            emptyLeftLabel.isHidden = true
            if let fastCachedSrc = OnlineGalleryClient.shared.getFastCachedImage(path: sourcePath, workId: entry.id, isThumbnail: true) {
                leftImageView.image = fastCachedSrc
            } else {
                OnlineGalleryClient.shared.loadImage(path: sourcePath, workId: entry.id, isThumbnail: true, maxPixel: 300) { [weak self] thumb in
                    guard let self = self, self.currentIndex == pageIndex else { return }
                    if self.leftImageView.image == nil {
                        self.leftImageView.image = thumb
                    }
                }
            }

            // 异步加载原素材 100% 高清原图（带百分比进度）
            leftProgressLabel.isHidden = false
            leftProgressLabel.text = "素材加载中…"
            OnlineGalleryClient.shared.loadImageWithProgress(
                path: sourcePath, workId: entry.id, isThumbnail: false,
                onProgress: { [weak self] pct in
                    guard let self = self, self.currentIndex == pageIndex else { return }
                    self.leftProgressLabel.isHidden = false
                    self.leftProgressLabel.text = "素材 \(pct)%"
                },
                completion: { [weak self] img in
                    guard let self = self, self.currentIndex == pageIndex else { return }
                    if let img = img {
                        self.leftImageView.image = img
                        self.emptyLeftLabel.isHidden = true
                        self.leftProgressLabel.text = "素材已就绪"
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
                            guard let self = self, self.currentIndex == pageIndex else { return }
                            self.leftProgressLabel.isHidden = true
                        }
                    } else if self.leftImageView.image == nil {
                        self.emptyLeftLabel.isHidden = false
                        self.leftProgressLabel.text = "加载失败"
                    }
                }
            )
        }

        // 3. 静默预加载同作品相邻前后页面（pageIndex+1, pageIndex-1, pageIndex+2）
        let prefetchIndices = [pageIndex + 1, pageIndex - 1, pageIndex + 2]
        for pIdx in prefetchIndices {
            guard pIdx >= 0 && pIdx < entry.images.count else { continue }
            let pOut = entry.images[pIdx]
            OnlineGalleryClient.shared.loadImage(path: pOut, workId: entry.id, isThumbnail: false) { _ in }
            if pIdx < entry.sourceImages.count {
                let pSrc = entry.sourceImages[pIdx]
                if !pSrc.isEmpty {
                    OnlineGalleryClient.shared.loadImage(path: pSrc, workId: entry.id, isThumbnail: false) { _ in }
                }
            }
        }
    }

    @objc private func toggleLayoutMode() {
        isHorizontalLayout.toggle()
        containerStack.axis = isHorizontalLayout ? .horizontal : .vertical
        containerStack.alignment = isHorizontalLayout ? .center : .fill
        layoutModeButton.setTitle(isHorizontalLayout ? "↔️ 左右并排" : "↕️ 上下同框", for: .normal)
    }

    @objc private func prevPage() {
        if currentIndex > 0 {
            loadPage(currentIndex - 1)
        }
    }

    @objc private func nextPage() {
        if currentIndex < entry.images.count - 1 {
            loadPage(currentIndex + 1)
        }
    }

    @objc private func closeTapped() {
        dismiss(animated: true)
    }
}
