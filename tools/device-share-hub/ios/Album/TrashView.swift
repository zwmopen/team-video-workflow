import UIKit

// ==================================================================
// 手机本地回收站（与在线回收站 OnlineRecycleViewController 行操作 1:1 对齐）
//   点某一行   -> 恢复（移回作品列表，并撤销垃圾标记）
//   左滑「备注」-> 填写/修改垃圾原因，写入手机本地元数据（对应在线版 quality_tag.json）
//   左滑「复制路径」-> 复制手机作品文件夹的绝对路径
//   右上「清空」  -> 彻底清空（本地 + 在线本地镜像）
//
// 页面里同时镜像展示「💻 在线作品回收站」条目（历史形态，保持不丢能力）；
// 其完整操作（备注 / 复制路径 / 判定垃圾）在在线模式下的「在线回收站」页里。
// ==================================================================
final class TrashViewController: UITableViewController {
    private let library: WorkLibrary
    private var onlineTrashItems: [OnlineWorkLifecycle.Item] = []
    private var toastView: UIView?

    init(library: WorkLibrary) {
        self.library = library
        super.init(style: AppColors.groupedTableStyle)
        title = "回收站"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "清空", style: .plain,
                                                            target: self, action: #selector(confirmClear))
        tableView.tableFooterView = UIView()
        loadData()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        loadData()
    }

    private func loadData() {
        onlineTrashItems = OnlineWorkLifecycle.getTrashItems()
        render()
    }

    private var totalCount: Int {
        return library.trash.count + onlineTrashItems.count
    }

    /// section 0 是否为「💻 在线作品回收站」区；与 numberOfSections 保持同一判据。
    private func isOnlineSection(_ section: Int) -> Bool {
        return !onlineTrashItems.isEmpty && section == 0
    }

    private func render() {
        navigationItem.rightBarButtonItem?.isEnabled = totalCount > 0
        if totalCount == 0 {
            let label = UILabel()
            label.text = "回收站是空的\n\n已删除的作品会移动到这里；\n左滑可填写垃圾备注或复制文件夹路径。"
            label.numberOfLines = 0
            label.textAlignment = .center
            label.textColor = AppColors.secondaryText
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
        tableView.reloadData()
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        return (onlineTrashItems.isEmpty ? 0 : 1) + (library.trash.isEmpty ? 0 : 1)
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        if isOnlineSection(section) {
            return "💻 在线作品回收站 (\(onlineTrashItems.count))"
        }
        return "📱 手机本地回收站 (\(library.trash.count))"
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if isOnlineSection(section) {
            return "点某一行即恢复。完整的删除 / 备注 / 复制路径请在在线模式下的「在线回收站」里操作。"
        }
        return "点某一行即恢复（撤销垃圾标记、次数归零）。左滑可填写垃圾备注（写入手机本地元数据）或复制文件夹路径。"
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if isOnlineSection(section) {
            return onlineTrashItems.count
        }
        return library.trash.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        if isOnlineSection(indexPath.section) {
            let item = onlineTrashItems[indexPath.row]
            cell.textLabel?.text = item.title
            cell.textLabel?.font = .boldSystemFont(ofSize: 16)
            cell.detailTextLabel?.text = "💻 电脑在线作品 · [\(item.destination)] · 已使用 \(item.useCount) 次 · 点一下恢复"
            cell.detailTextLabel?.textColor = UIColor(red: 0.15, green: 0.45, blue: 0.85, alpha: 1)
            cell.detailTextLabel?.numberOfLines = 0
            cell.accessoryType = .disclosureIndicator
            return cell
        }

        let item = library.trash[indexPath.row]
        cell.textLabel?.text = item.name
        cell.textLabel?.font = .boldSystemFont(ofSize: 16)
        var detail = "已打开分享 \(item.shareCount) 次"
        if item.isGarbage {
            detail += " · 🗑️ 垃圾样本\n备注：\(item.garbageRemark ?? "（未填写）")"
            cell.detailTextLabel?.textColor = UIColor(red: 0.66, green: 0.24, blue: 0.20, alpha: 1)
        } else {
            cell.detailTextLabel?.textColor = AppColors.secondaryText
        }
        detail += "\n点一下恢复 → 回到作品列表"
        cell.detailTextLabel?.text = detail
        cell.detailTextLabel?.numberOfLines = 0
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if isOnlineSection(indexPath.section) {
            let item = onlineTrashItems[indexPath.row]
            OnlineWorkLifecycle.restoreFromTrash(id: item.id)
            loadData()
            return
        }
        library.restore(library.trash[indexPath.row])
        loadData()
    }

    /// 与在线回收站的行操作严格对齐：恢复（点行）/ 备注 / 复制路径。
    override func tableView(_ tableView: UITableView,
                            trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        guard !isOnlineSection(indexPath.section) else { return nil }
        let item = library.trash[indexPath.row]

        let remark = UIContextualAction(style: .normal, title: item.isGarbage ? "改备注" : "备注") { [weak self] _, _, done in
            done(true)
            self?.promptRemark(item)
        }
        remark.backgroundColor = UIColor(red: 0.20, green: 0.45, blue: 0.62, alpha: 1)

        let copyPath = UIContextualAction(style: .normal, title: "复制路径") { [weak self] _, _, done in
            done(true)
            self?.copyFolderPath(item)
        }
        copyPath.backgroundColor = UIColor(red: 0.42, green: 0.47, blue: 0.44, alpha: 1)

        return UISwipeActionsConfiguration(actions: [remark, copyPath])
    }

    // MARK: - 备注 / 复制路径

    private func promptRemark(_ item: TrashItem) {
        let alert = UIAlertController(title: "垃圾备注（写入手机本地元数据）",
                                      message: "例如：文案公文味重 / 图片 AI 味浓 / 选题不合适",
                                      preferredStyle: .alert)
        alert.addTextField { tf in
            tf.text = item.garbageRemark
            tf.placeholder = "垃圾原因"
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "保存备注", style: .default) { [weak self] _ in
            guard let self = self else { return }
            let remark = (alert.textFields?.first?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            do {
                try self.library.updateTrashRemark(item, remark: remark)
                self.showToast(remark.isEmpty ? "已清除垃圾备注" : "✅ 已写入垃圾备注")
            } catch {
                self.showError((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
            self.loadData()
        })
        present(alert, animated: true)
    }

    private func copyFolderPath(_ item: TrashItem) {
        let path = item.folderURL.path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty else {
            showToast("⚠️ 该作品没有可复制的文件夹路径")
            return
        }
        UIPasteboard.general.string = path
        showToast("📋 已复制手机作品文件夹路径")
    }

    @objc private func confirmClear() {
        let alert = UIAlertController(title: "清空回收站？", message: "将从手机回收站中彻底清除，无法恢复。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "清空", style: .destructive) { [weak self] _ in
            guard let self = self else { return }
            do {
                try self.library.clearTrash()
                OnlineWorkLifecycle.clearTrash()
                self.loadData()
            } catch {
                self.showError(error.localizedDescription)
            }
        })
        present(alert, animated: true)
    }

    // MARK: - 反馈

    private func showError(_ text: String) {
        let alert = UIAlertController(title: "操作失败", message: text, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }

    private func showToast(_ text: String) {
        toastView?.removeFromSuperview()
        let label = UILabel()
        label.text = text
        label.font = .boldSystemFont(ofSize: 14)
        label.textColor = .white
        label.backgroundColor = UIColor.black.withAlphaComponent(0.82)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.layer.cornerRadius = 18
        label.clipsToBounds = true
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 8),
            label.heightAnchor.constraint(greaterThanOrEqualToConstant: 36),
            label.widthAnchor.constraint(lessThanOrEqualTo: view.widthAnchor, constant: -40),
            label.widthAnchor.constraint(greaterThanOrEqualToConstant: 120)
        ])
        toastView = label
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.4) { [weak self] in
            self?.toastView?.removeFromSuperview()
            self?.toastView = nil
        }
    }
}
