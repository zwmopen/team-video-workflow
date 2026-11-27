import UIKit
import AudioToolbox

final class SettingsViewController: UITableViewController {
    private let library: WorkLibrary
    private let liveQuickMenu = LiveQuickMenu()
    private var engineStatus: OnlineGalleryClient.LiveEngineStatusSnapshot = .offline

    init(library: WorkLibrary) {
        self.library = library
        super.init(style: AppColors.groupedTableStyle)
        title = "设置"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "Cell")
        refreshLiveEngineStatus(force: false)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshLiveEngineStatus(force: false)
    }

    private func refreshLiveEngineStatus(force: Bool) {
        OnlineGalleryClient.shared.fetchLiveEngineStatus(forceRefresh: force) { [weak self] snap in
            self?.engineStatus = snap
            self?.tableView.reloadData()
        }
    }

    private func formattedEngineSummary() -> String {
        switch LiveEnginePreference.preferred {
        case .localOnly:
            return "仅本地硬件无损渲染"
        case .flowFirst:
            guard engineStatus.reachable else { return "Google Flow · 电脑离线将自动切本地" }
            return engineStatus.flowReady ? "Google Flow · 已就绪（\(engineStatus.flowCredits) 积分）" : "Google Flow · 暂不可用（将走本地）"
        case .zhipuFirst:
            guard engineStatus.reachable else { return "智谱免费优先 · 电脑离线将自动切本地" }
            return engineStatus.zhipuReady ? "智谱 CogVideoX-Flash · 已就绪" : "智谱优先 · 待配置免费 Key"
        }
    }

    override func numberOfSections(in tableView: UITableView) -> Int { return 6 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return [8, 2, 2, 2, 2, 5][section]
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return ["设备", "作品文件夹", "自动整理", "提醒", "外观", "软件"][section]
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 0 { return "电脑复制文案时自动同步至手机剪贴板；默认180秒自动清空，不留输入法永久记录。" }
        if section == 1 { return "当前文件夹就是作品与普通文件的唯一真实来源。" }
        if section == 4 { return "可手动切换浅色或深色模式，默认跟随 iOS 系统设置。" }
        if section == 5 { return "素材与记录只保存在所选文件夹，不上传服务器，也不自动读取截图或系统剪切板。" }
        return nil
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let usesSubtitle = indexPath.section == 3 || indexPath.section == 5 || (indexPath.section == 0 && indexPath.row >= 3)
        let cell = UITableViewCell(style: usesSubtitle ? .subtitle : .value1, reuseIdentifier: nil)
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.numberOfLines = 0
        cell.detailTextLabel?.font = .systemFont(ofSize: 12)
        cell.detailTextLabel?.textColor = AppColors.secondaryText
        cell.selectionStyle = .none
        switch (indexPath.section, indexPath.row) {
        case (0, 0):
            cell.textLabel?.text = "手机名称"
            cell.detailTextLabel?.text = DeviceIdentity.name
        case (0, 1):
            cell.textLabel?.text = "修改手机名称"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (0, 2):
            cell.textLabel?.text = "局域网接收"
            cell.detailTextLabel?.text = library.networkStatus
        case (0, 3):
            cell.textLabel?.text = "跨设备剪贴板同步"
            cell.detailTextLabel?.text = "电脑复制文案自动同步至手机剪切板，长按粘贴；阅后即焚防残留"
            let toggle = UISwitch()
            toggle.isOn = ClipboardSyncPreferences.isEnabled
            toggle.addTarget(self, action: #selector(clipboardSyncSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (0, 4):
            cell.textLabel?.text = "分享时静图自动转实况"
            cell.detailTextLabel?.text = "当前效果：\(AutoLivePreferences.motionMode.title) · 点文字切换效果"
            cell.selectionStyle = .default
            let toggle = UISwitch()
            toggle.isOn = AutoLivePreferences.isEnabled
            toggle.addTarget(self, action: #selector(autoLiveSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (0, 5):
            cell.textLabel?.text = "跨网传输策略"
            cell.detailTextLabel?.text = RemoteRelayProfile.transportStrategy.title
            cell.selectionStyle = .default
        case (0, 6):
            cell.textLabel?.text = "流量与 R2 统计"
            cell.detailTextLabel?.text = RemoteRelayProfile.load() != nil ? "已授权云端中转 · 点击查看配额与流量" : "查看本机与 R2 免费额度估算"
            cell.selectionStyle = .default
        case (0, 7):
            cell.textLabel?.text = "处理引擎偏好"
            cell.detailTextLabel?.text = formattedEngineSummary()
            cell.selectionStyle = .default
        case (1, 0):
            cell.textLabel?.text = "当前文件夹"
            cell.detailTextLabel?.text = library.rootDescription
        case (1, 1):
            cell.textLabel?.text = "选择作品文件夹"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (2, 0):
            cell.textLabel?.text = "自动移入回收站"
            cell.detailTextLabel?.text = cleanupTimingText(CleanupPreferences.moveHours)
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (2, 1):
            cell.textLabel?.text = "自动彻底删除"
            cell.detailTextLabel?.text = cleanupTimingText(CleanupPreferences.deleteHours)
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (3, 0):
            cell.textLabel?.text = "声音通知"
            cell.detailTextLabel?.text = "收到文件时显示通知并响铃"
            let toggle = UISwitch()
            toggle.isOn = NotificationPreferences.notificationsEnabled
            toggle.addTarget(self, action: #selector(notificationSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (3, 1):
            cell.textLabel?.text = "震动提醒"
            cell.detailTextLabel?.text = "开始、完成或失败时震动"
            let toggle = UISwitch()
            toggle.isOn = NotificationPreferences.vibrationEnabled
            toggle.addTarget(self, action: #selector(vibrationSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (4, 0):
            cell.textLabel?.text = "主题风格"
            cell.detailTextLabel?.text = AppThemePreferences.currentStyle.title
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (4, 1):
            cell.textLabel?.text = "外观风格"
            cell.detailTextLabel?.text = AppThemePreferences.currentMode.title
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (5, 0):
            cell.textLabel?.text = "名称"
            cell.detailTextLabel?.text = "相册"
        case (5, 1):
            cell.textLabel?.text = "版本"
            cell.detailTextLabel?.text = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.5.3"
        case (5, 2):
            cell.textLabel?.text = "检查版本更新"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (5, 3):
            cell.textLabel?.text = "软件说明"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        default:
            cell.textLabel?.text = "复制诊断信息"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if indexPath.section == 0 && indexPath.row == 1 {
            promptForDeviceName()
        } else if indexPath.section == 0 && indexPath.row == 4 {
            if let cell = tableView.cellForRow(at: indexPath) { showAutoLiveModeChoices(anchor: cell) }
        } else if indexPath.section == 0 && indexPath.row == 5 {
            let anchor = tableView.cellForRow(at: indexPath) ?? tableView
            showTransportStrategyChoices(anchor: anchor)
        } else if indexPath.section == 0 && indexPath.row == 6 {
            showTransferStatsAlert()
        } else if indexPath.section == 0 && indexPath.row == 7 {
            if let cell = tableView.cellForRow(at: indexPath) {
                showLiveEngineChoices(anchor: cell)
            }
        } else if indexPath.section == 1 && indexPath.row == 1 {
            if library.supportsExternalFolderSelection {
                guard #available(iOS 13.0, *) else { return }
                let picker = FolderPickerController()
                picker.onPick = { [weak self] url in
                    self?.library.selectFolder(url)
                    self?.tableView.reloadData()
                }
                present(picker, animated: true)
            } else {
                let picker = ManagedFolderPickerViewController(library: library)
                picker.onPick = { [weak self] in self?.tableView.reloadData() }
                present(UINavigationController(rootViewController: picker), animated: true)
            }
        } else if indexPath.section == 2 {
            let anchor = tableView.cellForRow(at: indexPath) ?? tableView
            showCleanupChoices(editingMove: indexPath.row == 0, anchor: anchor)
        } else if indexPath.section == 4 && indexPath.row == 0 {
            let anchor = tableView.cellForRow(at: indexPath) ?? tableView
            showThemeStyleChoices(anchor: anchor)
        } else if indexPath.section == 4 {
            let anchor = tableView.cellForRow(at: indexPath) ?? tableView
            showThemeModeChoices(anchor: anchor)
        } else if indexPath.section == 5 && indexPath.row == 2 {
            AlbumUpdateChecker.check(from: self)
        } else if indexPath.section == 5 && indexPath.row == 3 {
            showAbout()
        } else if indexPath.section == 5 && indexPath.row == 4 {
            library.copyDiagnostics()
            let alert = UIAlertController(title: nil, message: "诊断信息已复制", preferredStyle: .alert)
            present(alert, animated: true)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { alert.dismiss(animated: true) }
        }
    }

    private func showTransportStrategyChoices(anchor: UIView) {
        let strategies = RemoteTransportStrategy.allCases
        let current = RemoteRelayProfile.transportStrategy
        liveQuickMenu.show(
            anchor: anchor,
            labels: strategies.map { $0.title },
            selected: strategies.firstIndex(of: current) ?? 0
        ) { [weak self] idx in
            RemoteRelayProfile.transportStrategy = strategies[idx]
            OnlineGalleryClient.shared.invalidateBaseUrl()
            self?.tableView.reloadData()
        }
    }

    private func showLiveEngineChoices(anchor: UIView) {
        let st = engineStatus
        let zhipuLabel = st.reachable
            ? (st.zhipuReady ? "智谱 CogVideoX-Flash（免费 AI · 已就绪）" : "智谱 CogVideoX-Flash（免费 AI · 待配 Key）")
            : "智谱 CogVideoX-Flash（免费 AI · 连电脑可用）"
        let localLabel = "仅手机本地无损渲染（0 流量 · 极速）"
        let flowLabel = st.reachable
            ? (st.flowReady ? "Google Flow 电脑联动（已就绪 · \(st.flowCredits) 积分）" : "Google Flow 电脑联动（未就绪）")
            : "Google Flow 电脑联动（连电脑可用）"
        let labels = [zhipuLabel, localLabel, flowLabel, "配置电脑端智谱免费 API Key…"]
        let options: [LiveEnginePreference] = [.zhipuFirst, .localOnly, .flowFirst]
        let selected = options.firstIndex(of: LiveEnginePreference.preferred) ?? 0
        liveQuickMenu.show(anchor: anchor, labels: labels, selected: selected) { [weak self] idx in
            guard let self = self else { return }
            if idx == 3 {
                self.promptForZhipuApiKey()
                return
            }
            LiveEnginePreference.preferred = options[idx]
            self.tableView.reloadData()
            self.refreshLiveEngineStatus(force: true)
        }
    }

    private func promptForZhipuApiKey() {
        let alert = UIAlertController(
            title: "配置电脑端智谱免费 API Key",
            message: engineStatus.zhipuReady
                ? "当前电脑已配置 Key（\(engineStatus.zhipuMasked)）；留空保存可清除。"
                : "前往 bigmodel.cn 免费注册并创建 API Key，保存后直接写入电脑本机，手机端无需重装。",
            preferredStyle: .alert
        )
        alert.addTextField { field in
            field.placeholder = "粘贴智谱 API Key（cogvideox-flash 免费）"
            field.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "保存到电脑", style: .default) { [weak self, weak alert] _ in
            let key = alert?.textFields?.first?.text ?? ""
            OnlineGalleryClient.shared.saveZhipuApiKey(key) { [weak self] res in
                guard let self = self else { return }
                switch res {
                case .success(let snap):
                    self.engineStatus = snap
                    self.tableView.reloadData()
                case .failure(let err):
                    let errAlert = UIAlertController(title: "保存失败", message: "请先确认手机与电脑已连通（\(err.localizedDescription)）", preferredStyle: .alert)
                    errAlert.addAction(UIAlertAction(title: "知道了", style: .default))
                    self.present(errAlert, animated: true)
                }
            }
        })
        present(alert, animated: true)
    }

    private func showTransferStatsAlert() {
        let enrolled = RemoteRelayProfile.load() != nil
        let initialMsg = enrolled
            ? "正在向 Cloudflare 工作区拉取本月用量与 R2 额度估算…"
            : "当前尚未获取云端工作区凭证（与电脑同一 Wi-Fi 后将自动授权）。\n\n提示：Cloudflare R2 出站下载流量免费，临时中转文件在接收确认后会自动删除。"
        let alert = UIAlertController(title: "传输流量与 R2 统计", message: initialMsg, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "确定", style: .default))
        present(alert, animated: true)

        guard enrolled else { return }
        DispatchQueue.global(qos: .utility).async {
            var summary = "拉取云端统计失败（当前可能离线）。"
            if let session = try? RemoteRelayClient.ensureSession(),
               let stats = try? RemoteRelayClient.fetchStats(session) {
                let today = stats["today"] as? [String: Any] ?? [:]
                let month = stats["month"] as? [String: Any] ?? [:]
                let r2 = stats["r2"] as? [String: Any] ?? [:]
                let quotas = r2["quotas"] as? [String: Any] ?? [:]
                let fmt: (Any?) -> String = { raw in
                    let b = (raw as? NSNumber)?.doubleValue ?? 0
                    if b < 1024 { return "\(Int(b)) B" }
                    if b < 1048576 { return String(format: "%.1f KB", b / 1024.0) }
                    if b < 1073741824 { return String(format: "%.1f MB", b / 1048576.0) }
                    return String(format: "%.2f GB", b / 1073741824.0)
                }
                let sPct = ((quotas["storageUsageRatio"] as? NSNumber)?.doubleValue ?? 0) * 100.0
                let aPct = ((quotas["classAUsageRatio"] as? NSNumber)?.doubleValue ?? 0) * 100.0
                let bPct = ((quotas["classBUsageRatio"] as? NSNumber)?.doubleValue ?? 0) * 100.0
                let usd = (r2["estimatedCostUsd"] as? NSNumber)?.doubleValue ?? 0
                let note = (r2["billingNote"] as? String) ?? "预估费用仅供参考，实际账单以 Cloudflare 控制台为准"
                summary = String(
                    format: "今日团队流量：%@（R2 %@ / LAN %@）\n本月总流量：%@\nR2 当前驻留：%@（%d 个对象）\n免费额度占用：存储 %.2f%% · A类 %.2f%% · B类 %.2f%%\n预估超额费用：$%.4f USD（R2 出站下载流量免费）\n%@",
                    fmt(today["bytes"]), fmt(today["r2Bytes"]), fmt(today["lanBytes"]),
                    fmt(month["bytes"]),
                    fmt(r2["activeBytes"]), (r2["activeObjects"] as? Int) ?? 0,
                    sPct, aPct, bPct, usd, note
                )
            }
            DispatchQueue.main.async {
                alert.message = summary
            }
        }
    }

    private func showThemeStyleChoices(anchor: UIView) {
        let options: [(AppThemeStyle, String)] = [
            (.classic, "经典纯色"),
            (.flclashGlass, "通透玻璃（推荐）")
        ]
        let selected = options.firstIndex { $0.0 == AppThemePreferences.currentStyle } ?? 1
        liveQuickMenu.show(anchor: anchor, labels: options.map { $0.1 }, selected: selected) { [weak self] idx in
            AppThemePreferences.currentStyle = options[idx].0
            self?.tableView.reloadData()
        }
    }

    private func showThemeModeChoices(anchor: UIView) {
        let options: [(AppThemeMode, String)] = [
            (.system, "跟随系统"),
            (.light, "浅色模式"),
            (.dark, "深色模式")
        ]
        let selected = options.firstIndex { $0.0 == AppThemePreferences.currentMode } ?? 0
        liveQuickMenu.show(anchor: anchor, labels: options.map { $0.1 }, selected: selected) { [weak self] idx in
            AppThemePreferences.currentMode = options[idx].0
            self?.tableView.reloadData()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        liveQuickMenu.dismiss()
        super.viewWillDisappear(animated)
    }

    private func showAutoLiveModeChoices(anchor: UIView) {
        let modes = AutoLiveMotionMode.allCases
        let labels = modes.map { $0.title } + ["动态视频画质…", "微动幅度与时长…", "跳过已有实况："+(AutoLivePreferences.skipExistingLive ? "开" : "关")]
        liveQuickMenu.show(anchor: anchor, labels: labels, selected: modes.firstIndex(of: AutoLivePreferences.motionMode) ?? 0) { [weak self] which in
            guard let self = self else { return }
            if which < modes.count {
                AutoLivePreferences.motionMode = modes[which]
                OnlineGalleryClient.shared.syncLiveMotionMode(modes[which].rawValue)
                self.tableView.reloadData()
            } else if which == modes.count {
                self.showAutoLiveQualityTierChoices(anchor: anchor)
            } else if which == modes.count + 1 {
                self.showAutoLiveStrengthChoices(anchor: anchor)
            } else {
                AutoLivePreferences.skipExistingLive.toggle()
                self.tableView.reloadData()
            }
        }
    }

    private func showAutoLiveQualityTierChoices(anchor: UIView) {
        let tiers = LiveQualityTier.allCases
        let cur = AutoLivePreferences.qualityTier
        liveQuickMenu.show(
            anchor: anchor,
            labels: tiers.map { "\($0.title)（\($0.shortLabel)）" },
            selected: tiers.firstIndex(of: cur) ?? 0
        ) { [weak self] idx in
            AutoLivePreferences.qualityTier = tiers[idx]
            self?.tableView.reloadData()
        }
    }

    private func showAutoLiveStrengthChoices(anchor: UIView) {
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
        liveQuickMenu.show(anchor: anchor, labels: options.map { $0.0 }, selected: selected) { [weak self] idx in
            AutoLivePreferences.motionRatio = options[idx].1
            AutoLivePreferences.motionDuration = options[idx].2
            self?.tableView.reloadData()
        }
    }

    @objc private func clipboardSyncSwitchChanged(_ sender: UISwitch) {
        ClipboardSyncPreferences.isEnabled = sender.isOn
    }

    @objc private func autoLiveSwitchChanged(_ sender: UISwitch) {
        AutoLivePreferences.isEnabled = sender.isOn
    }

    @objc private func notificationSwitchChanged(_ sender: UISwitch) {
        TransferNotifications.shared.setNotificationsEnabled(sender.isOn) { [weak self, weak sender] granted in
            guard !granted else { return }
            sender?.setOn(false, animated: true)
            let alert = UIAlertController(title: "通知没有打开",
                                          message: "系统没有允许通知。需要时可到 iPhone 设置中为“相册”打开通知。",
                                          preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "知道了", style: .default))
            self?.present(alert, animated: true)
        }
    }

    @objc private func vibrationSwitchChanged(_ sender: UISwitch) {
        NotificationPreferences.vibrationEnabled = sender.isOn
        if sender.isOn { AudioServicesPlaySystemSound(kSystemSoundID_Vibrate) }
    }

    private func showAbout() {
        let message = "把分散在电脑和手机里的素材，整理成随手可用的作品库。\n\n核心场景\n从电脑拖入文件、ZIP 或整个文件夹，手机自动接收并保留原目录结构；含图片和 TXT 的目录会被识别为作品，普通文件也可继续传送、预览和分享。\n\n作品工作流\n点一次“复制并分享”会立即记录一次，文案只在这次主动操作中交给系统剪切板，图片交给系统分享面板。自动移入回收站和彻底删除默认均为手动，可在设置中选择处理时间。\n\n跨设备传送\nWindows、Android 和 iPhone 在同一 Wi-Fi 下自动发现，传送过程显示进度并核对完整性。\n\n系统分享\n其他应用可从系统分享面板选择“相册”并发送给在线设备。普通文件按真实文件夹存放。\n\n设计思路\n内容优先、操作尽量少、状态一眼可见。目录授权、作品记录、分享次数和回收站保存在本机；坚果云只属于电脑端。"
        let alert = UIAlertController(title: "相册 · 作品与文件中控", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }

    private func showCleanupChoices(editingMove: Bool, anchor: UIView) {
        let current = editingMove ? CleanupPreferences.moveHours : CleanupPreferences.deleteHours
        let presets: [(Int, String)] = [
            (CleanupPreferences.manual, "手动"),
            (0, "立刻"),
            (1, "1 小时后"),
            (3, "3 小时后"),
            (6, "6 小时后"),
            (24, "24 小时后")
        ]
        let labels = presets.map { $0.1 } + ["自定义…"]
        let selected = presets.firstIndex { $0.0 == current } ?? -1
        liveQuickMenu.show(anchor: anchor, labels: labels, selected: selected) { [weak self] idx in
            guard let self = self else { return }
            if idx == presets.count {
                self.showCustomCleanupHours(editingMove: editingMove, current: current)
            } else {
                self.saveCleanupChoice(presets[idx].0, editingMove: editingMove)
            }
        }
    }

    private func showCustomCleanupHours(editingMove: Bool, current: Int) {
        let alert = UIAlertController(title: "自定义小时数",
                                      message: "请输入 0～720 的整数；0 表示立刻。",
                                      preferredStyle: .alert)
        alert.addTextField { field in
            field.text = "\(current >= 0 ? current : 1)"
            field.keyboardType = .numberPad
            field.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "保存", style: .default) { [weak self, weak alert] _ in
            guard let text = alert?.textFields?.first?.text, let value = Int(text) else { return }
            self?.saveCleanupChoice(value, editingMove: editingMove)
        })
        present(alert, animated: true)
    }

    private func saveCleanupChoice(_ value: Int, editingMove: Bool) {
        let move = editingMove ? value : CleanupPreferences.moveHours
        let currentDelete = CleanupPreferences.deleteHours
        let delete = editingMove
            ? (value >= 0 && currentDelete >= 0 ? max(value, currentDelete) : currentDelete)
            : value
        guard CleanupPreferences.save(moveHours: move, deleteHours: delete) else {
            let failed = UIAlertController(title: "没有保存",
                                           message: "请选择“手动”或输入 0～720；彻底删除不能早于移入回收站。",
                                           preferredStyle: .alert)
            failed.addAction(UIAlertAction(title: "知道了", style: .default))
            present(failed, animated: true)
            return
        }
        library.refresh(showConfirmation: false)
        tableView.reloadData()
    }

    private func cleanupTimingText(_ hours: Int) -> String {
        if hours == CleanupPreferences.manual { return "手动" }
        return hours == 0 ? "立刻" : "\(hours) 小时后"
    }

    private func promptForDeviceName() {
        let alert = UIAlertController(title: "修改手机名称",
                                      message: "保存后，电脑端会在下一次刷新时显示这个名称。",
                                      preferredStyle: .alert)
        alert.addTextField { field in
            field.text = DeviceIdentity.name
            field.placeholder = "例如：发布机 1"
            field.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "保存", style: .default) { [weak self, weak alert] _ in
            do {
                try DeviceIdentity.saveName(alert?.textFields?.first?.text ?? "")
                self?.tableView.reloadData()
                let done = UIAlertController(title: nil, message: "名称已保存，电脑端刷新后可见", preferredStyle: .alert)
                self?.present(done, animated: true)
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { done.dismiss(animated: true) }
            } catch {
                let failed = UIAlertController(title: "没有保存", message: error.localizedDescription,
                                               preferredStyle: .alert)
                failed.addAction(UIAlertAction(title: "知道了", style: .default))
                self?.present(failed, animated: true)
            }
        })
        present(alert, animated: true)
    }
}
