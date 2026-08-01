import UIKit
import AudioToolbox

final class SettingsViewController: UITableViewController {
    private let library: WorkLibrary

    init(library: WorkLibrary) {
        self.library = library
        super.init(style: AppColors.groupedTableStyle)
        title = "设置"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "Cell")
    }

    override func numberOfSections(in tableView: UITableView) -> Int { return 7 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return [3, 2, 2, 2, 4, 1, 5][section]
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return ["设备", "作品文件夹", "自动整理", "提醒", "剪切板与截图", "隐私", "软件"][section]
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if section == 1 { return "当前文件夹就是作品与普通文件的唯一真实来源。" }
        if section == 4 { return "iPhone 只会在相册位于前台时读取剪切板和识别截图；iOS 不允许第三方 App 显示跨应用悬浮球。" }
        if section == 5 { return "素材与记录只保存在所选文件夹，不上传服务器，也不修改图片拍摄信息。" }
        return nil
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let usesSubtitle = indexPath.section == 3 || indexPath.section == 4 || indexPath.section == 5
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
            cell.textLabel?.text = "共享剪切板"
            cell.detailTextLabel?.text = "进入相册时读取最新内容，并同步到同组在线设备"
            let toggle = UISwitch()
            toggle.isOn = UserDefaults.standard.object(forKey: "album.clipboardSyncEnabled") as? Bool ?? true
            toggle.addTarget(self, action: #selector(clipboardSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (4, 1):
            cell.textLabel?.text = "自动识别新截图"
            cell.detailTextLabel?.text = "回到相册后识别刚刚保存的系统截图"
            let toggle = UISwitch()
            toggle.isOn = ScreenshotMonitor.detectionEnabled
            toggle.addTarget(self, action: #selector(screenshotDetectionSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (4, 2):
            cell.textLabel?.text = "自动发送到主设备"
            cell.detailTextLabel?.text = "主设备在线时直接发送；关闭后逐张询问"
            let toggle = UISwitch()
            toggle.isOn = ScreenshotMonitor.autoSendEnabled
            toggle.addTarget(self, action: #selector(screenshotAutoSendSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (4, 3):
            cell.textLabel?.text = "截图主设备"
            cell.detailTextLabel?.text = ScreenshotMonitor.mainDeviceName
            cell.textLabel?.textColor = view.tintColor
            cell.accessoryType = .disclosureIndicator
            cell.selectionStyle = .default
        case (5, 0):
            cell.textLabel?.text = "允许作为主设备接收"
            cell.detailTextLabel?.text = "允许其他设备自动发送截图"
            let toggle = UISwitch()
            toggle.isOn = UserDefaults.standard.object(
                forKey: "album.screenshotReceiveEnabled") as? Bool ?? true
            toggle.addTarget(self, action: #selector(
                screenshotReceiveSwitchChanged(_:)), for: .valueChanged)
            cell.accessoryView = toggle
        case (6, 0):
            cell.textLabel?.text = "名称"
            cell.detailTextLabel?.text = "相册"
        case (6, 1):
            cell.textLabel?.text = "版本"
            cell.detailTextLabel?.text = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.5.3"
        case (6, 2):
            cell.textLabel?.text = "检查版本更新"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (6, 3):
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
            showCleanupChoices(editingMove: indexPath.row == 0)
        } else if indexPath.section == 4 && indexPath.row == 3 {
            showMainDeviceChoices()
        } else if indexPath.section == 6 && indexPath.row == 2 {
            AlbumUpdateChecker.check(from: self)
        } else if indexPath.section == 6 && indexPath.row == 3 {
            showAbout()
        } else if indexPath.section == 6 && indexPath.row == 4 {
            library.copyDiagnostics()
            let alert = UIAlertController(title: nil, message: "诊断信息已复制", preferredStyle: .alert)
            present(alert, animated: true)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { alert.dismiss(animated: true) }
        }
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

    @objc private func screenshotReceiveSwitchChanged(_ sender: UISwitch) {
        UserDefaults.standard.set(sender.isOn, forKey: "album.screenshotReceiveEnabled")
    }

    @objc private func clipboardSwitchChanged(_ sender: UISwitch) {
        UserDefaults.standard.set(sender.isOn, forKey: "album.clipboardSyncEnabled")
        if sender.isOn { ClipboardBridge.shared.start() }
        else { ClipboardBridge.shared.stop() }
    }

    @objc private func screenshotDetectionSwitchChanged(_ sender: UISwitch) {
        ScreenshotMonitor.setDetectionEnabled(sender.isOn) { [weak self, weak sender] granted in
            guard granted else {
                sender?.setOn(false, animated: true)
                let alert = UIAlertController(title: "无法识别截图",
                                              message: "请在 iPhone 设置中允许“相册”读取照片。",
                                              preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: "知道了", style: .default))
                self?.present(alert, animated: true)
                return
            }
            self?.tableView.reloadData()
        }
    }

    @objc private func screenshotAutoSendSwitchChanged(_ sender: UISwitch) {
        ScreenshotMonitor.autoSendEnabled = sender.isOn
    }

    private func showMainDeviceChoices() {
        let peers = PeerDirectory.shared.peers()
        let alert = UIAlertController(title: "截图主设备",
                                      message: peers.isEmpty ? "暂未发现在线设备，请先在目标设备上打开相册或电脑中控。" : "截图优先发送到这台设备。",
                                      preferredStyle: .actionSheet)
        alert.addAction(UIAlertAction(title: "不设置", style: .default) { [weak self] _ in
            ScreenshotMonitor.setMainDevice(nil)
            self?.tableView.reloadData()
        })
        peers.forEach { peer in
            let name = peer.name.isEmpty ? peer.model : peer.name
            alert.addAction(UIAlertAction(title: name, style: .default) { [weak self] _ in
                ScreenshotMonitor.setMainDevice(peer)
                self?.tableView.reloadData()
            })
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let popover = alert.popoverPresentationController {
            popover.sourceView = view
            popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 1, height: 1)
        }
        present(alert, animated: true)
    }

    private func showAbout() {
        let message = "把分散在电脑和手机里的素材，整理成随手可用的作品库。\n\n核心场景\n从电脑拖入文件、ZIP 或整个文件夹，手机自动接收并保留原目录结构；含图片和 TXT 的目录会被识别为作品，普通文件也可继续传送、预览和分享。\n\n作品工作流\n点一次“复制并分享”会立即记录一次，文案进入剪切板，图片交给系统分享面板。默认 1 小时后进入回收站并彻底删除，时间可自行调整。\n\n跨设备传送\nWindows、Android 和 iPhone 在同一 Wi-Fi 下自动发现，传送过程显示进度并核对完整性。\n\n共享剪切板与截图\n同组在线设备同步最新剪切内容；iPhone 回到相册后可识别新截图，并询问或自动发送到主设备。受 iOS 系统限制，不提供跨应用悬浮球和后台永久读取。\n\n系统分享\n其他应用可从系统分享面板选择“相册”并发送给在线设备。普通文件按真实文件夹存放。\n\n设计思路\n内容优先、操作尽量少、状态一眼可见。目录授权、作品记录、分享次数和回收站保存在本机；坚果云只属于电脑端。"
        let alert = UIAlertController(title: "相册 · 作品与文件中控", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }

    private func showCleanupChoices(editingMove: Bool) {
        let current = editingMove ? CleanupPreferences.moveHours : CleanupPreferences.deleteHours
        let title = editingMove ? "自动移入回收站" : "自动彻底删除"
        let alert = UIAlertController(title: title, message: "选择后立即保存", preferredStyle: .actionSheet)
        [(0, "立刻"), (1, "1 小时后"), (3, "3 小时后"),
         (6, "6 小时后"), (24, "24 小时后")].forEach { preset in
            let (hours, label) = preset
            alert.addAction(UIAlertAction(title: label + (hours == current ? "  ✓" : ""),
                                          style: .default) { [weak self] _ in
                self?.saveCleanupChoice(hours, editingMove: editingMove)
            })
        }
        alert.addAction(UIAlertAction(title: "自定义…", style: .default) { [weak self] _ in
            self?.showCustomCleanupHours(editingMove: editingMove, current: current)
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        if let popover = alert.popoverPresentationController {
            popover.sourceView = view
            popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.maxY - 20, width: 1, height: 1)
        }
        present(alert, animated: true)
    }

    private func showCustomCleanupHours(editingMove: Bool, current: Int) {
        let alert = UIAlertController(title: "自定义小时数",
                                      message: "请输入 0～720 的整数；0 表示立刻。",
                                      preferredStyle: .alert)
        alert.addTextField { field in
            field.text = "\(current)"
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
        let delete = editingMove ? max(value, CleanupPreferences.deleteHours) : value
        guard CleanupPreferences.save(moveHours: move, deleteHours: delete) else {
            let failed = UIAlertController(title: "没有保存",
                                           message: "请输入 0～720；彻底删除不能早于移入回收站。",
                                           preferredStyle: .alert)
            failed.addAction(UIAlertAction(title: "知道了", style: .default))
            present(failed, animated: true)
            return
        }
        library.refresh(showConfirmation: false)
        tableView.reloadData()
    }

    private func cleanupTimingText(_ hours: Int) -> String {
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
