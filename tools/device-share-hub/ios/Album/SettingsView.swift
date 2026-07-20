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

    override func numberOfSections(in tableView: UITableView) -> Int { return 6 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return [3, library.supportsExternalFolderSelection ? 4 : 3, 3, 2, 1, 5][section]
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return ["设备", "作品文件夹", "工作方式", "提醒", "隐私", "软件"][section]
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.numberOfLines = 0
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
        case (1, 2):
            cell.textLabel?.text = "导入文件或 ZIP"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (1, 3):
            cell.textLabel?.text = "使用“我的 iPhone/相册”"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (2, 0): cell.textLabel?.text = "点击作品后自动复制 TXT 文案"
        case (2, 1): cell.textLabel?.text = "全部图片进入 iOS 系统分享"
        case (2, 2): cell.textLabel?.text = "次日移入回收站，保留 7 天"
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
            cell.textLabel?.text = "素材与记录只保存在所选文件夹，不上传服务器，也不修改图片拍摄信息。"
            cell.textLabel?.textColor = AppColors.secondaryText
        case (5, 0):
            cell.textLabel?.text = "名称"
            cell.detailTextLabel?.text = "相册"
        case (5, 1):
            cell.textLabel?.text = "版本"
            cell.detailTextLabel?.text = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.4.6"
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
        } else if indexPath.section == 1 && indexPath.row == 2 {
            let picker = ImportPickerController()
            picker.onPick = { [weak self] urls in
                self?.library.importItems(urls)
                self?.tableView.reloadData()
            }
            present(picker, animated: true)
        } else if indexPath.section == 1 && indexPath.row == 3 {
            library.useManagedFolder()
            tableView.reloadData()
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
        let message = "相册用于管理作品、复制 TXT 文案并调用 iOS 系统分享，也能在同一 Wi‑Fi 下与 Windows、安卓和苹果设备互传文件。\n\n素材与记录只保存在你选择的文件夹；不自动发布、不模拟点击、不修改图片拍摄信息。分享过的作品次日进入回收站，并保留 7 天。"
        let alert = UIAlertController(title: "关于相册", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
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
