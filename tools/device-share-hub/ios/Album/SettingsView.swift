import UIKit

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

    override func numberOfSections(in tableView: UITableView) -> Int { return 4 }
    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return [library.supportsExternalFolderSelection ? 3 : 2, 3, 1, 3][section]
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return ["作品文件夹", "工作方式", "隐私", "软件"][section]
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .value1, reuseIdentifier: nil)
        cell.textLabel?.numberOfLines = 0
        cell.detailTextLabel?.numberOfLines = 0
        cell.selectionStyle = .none
        switch (indexPath.section, indexPath.row) {
        case (0, 0):
            cell.textLabel?.text = "当前文件夹"
            cell.detailTextLabel?.text = library.rootDescription
        case (0, 1):
            cell.textLabel?.text = library.supportsExternalFolderSelection ? "重新选择文件夹" : "重新扫描"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (0, 2):
            cell.textLabel?.text = "使用“我的 iPhone/相册”"
            cell.textLabel?.textColor = view.tintColor
            cell.selectionStyle = .default
        case (1, 0): cell.textLabel?.text = "点击作品后自动复制 TXT 文案"
        case (1, 1): cell.textLabel?.text = "全部图片进入 iOS 系统分享"
        case (1, 2): cell.textLabel?.text = "次日移入回收站，保留 7 天"
        case (2, 0):
            cell.textLabel?.text = "素材与记录只保存在所选文件夹，不上传服务器，也不修改图片拍摄信息。"
            cell.textLabel?.textColor = AppColors.secondaryText
        case (3, 0):
            cell.textLabel?.text = "名称"
            cell.detailTextLabel?.text = "相册"
        case (3, 1):
            cell.textLabel?.text = "版本"
            cell.detailTextLabel?.text = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.2.0"
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
            if library.supportsExternalFolderSelection {
                guard #available(iOS 13.0, *) else { return }
                let picker = FolderPickerController()
                picker.onPick = { [weak self] url in
                    self?.library.selectFolder(url)
                    self?.tableView.reloadData()
                }
                present(picker, animated: true)
            } else {
                library.refresh()
                tableView.reloadData()
            }
        } else if indexPath.section == 0 && indexPath.row == 2 {
            library.useManagedFolder()
            tableView.reloadData()
        } else if indexPath.section == 3 && indexPath.row == 2 {
            library.copyDiagnostics()
            let alert = UIAlertController(title: nil, message: "诊断信息已复制", preferredStyle: .alert)
            present(alert, animated: true)
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { alert.dismiss(animated: true) }
        }
    }
}
