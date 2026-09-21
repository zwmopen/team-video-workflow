import UIKit

// ==================================================================
// 手机本地回收站（与在线回收站 OnlineRecycleViewController 结构 1:1 对齐）
//   双 Tab      -> 「已删除」/「已标记垃圾」（判据：是否写过垃圾备注）
//   点某一行    -> 恢复（移回作品列表，并撤销垃圾标记）
//   左滑「备注」-> 填写/修改垃圾原因，写入手机本地元数据（对应在线版 quality_tag.json）
//   左滑「复制路径」-> 复制手机作品文件夹的绝对路径
//   右上「清空」-> 彻底清空（本地 + 在线本地镜像）
//
// 「已删除」Tab 下额外镜像展示「💻 在线作品回收站」条目（历史形态，保持不丢能力）；
// 其完整操作（备注 / 复制路径 / 判定垃圾）在在线模式下的「在线回收站」页里。
// ==================================================================
final class TrashViewController: UITableViewController {

    private enum LocalTab: Int {
        case deleted = 0
        case garbage = 1

        var title: String { self == .deleted ? "已删除" : "已标记垃圾" }
    }

    private let library: WorkLibrary
    private let segmented: UISegmentedControl
    private var currentTab: LocalTab = .deleted
    private var onlineTrashItems: [OnlineWorkLifecycle.Item] = []
    private var toastView: UIView?

    init(library: WorkLibrary) {
        self.library = library
        self.segmented = UISegmentedControl(items: [LocalTab.deleted.title, LocalTab.garbage.title])
        super.init(style: AppColors.groupedTableStyle)
        title = "回收站"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()

        segmented.selectedSegmentIndex = LocalTab.deleted.rawValue
        segmented.addTarget(self, action: #selector(tabChanged), for: .valueChanged)
        segmented.autoresizingMask = [.flexibleWidth]

        let header = UIView()
        header.addSubview(segmented)
        tableView.tableHeaderView = header

        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "清空", style: .plain,
                                                            target: self, action: #selector(confirmClear))
        tableView.tableFooterView = UIView()
        loadData()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        loadData()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let header = tableView.tableHeaderView else { return }
        let target = CGSize(width: tableView.bounds.width, height: 56)
        if header.frame.size != target {
            header.frame = CGRect(origin: .zero, size: target)
            segmented.frame = CGRect(x: 16, y: 11, width: max(0, target.width - 32), height: 34)
            tableView.tableHeaderView = header
        }
    }

    private func loadData() {
        onlineTrashItems = OnlineWorkLifecycle.getTrashItems()
        updateSegmentTitles()
        render()
    }

    /// 当前 Tab 对应的手机本地回收站条目（判据与在线版垃圾标记同一套：垃圾备注是否为空）。
    private var localItems: [TrashItem] {
        let wantGarbage = currentTab == .garbage
        return library.trash.filter { $0.isGarbage == wantGarbage }
    }

    /// 在线作品回收站镜像只在「已删除」Tab 展示，避免和在线回收站页重复计数。
    private var showsOnlineSection: Bool {
        return !onlineTrashItems.isEmpty && currentTab == .deleted
    }

    private func isOnlineSection(_ section: Int) -> Bool {
        return showsOnlineSection && section == 0
    }

    private var totalCount: Int {
        return localItems.count + (showsOnlineSection ? onlineTrashItems.count : 0)
    }

    private func updateSegmentTitles() {
        let deleted = library.trash.filter { !$0.isGarbage }.count
        let garbage = library.trash.count - deleted
        segmented.setTitle(deleted > 0 ? "已删除 \(deleted)" : "已删除", forSegmentAt: 0)
        segmented.setTitle(garbage > 0 ? "已标记垃圾 \(garbage)" : "已标记垃圾", forSegmentAt: 1)
    }

    @objc private func tabChanged() {
        currentTab = LocalTab(rawValue: segmented.selectedSegmentIndex) ?? .deleted
        render()
    }

    private func render() {
        navigationItem.rightBarButtonItem?.isEnabled = totalCount > 0
        if totalCount == 0 {
            let label = UILabel()
            label.numberOfLines = 0
            label.textAlignment = .center
            label.textColor = AppColors.secondaryText
            label.text = currentTab == .garbage
                ? "本地「已标记垃圾」暂为空\n\n点卡片或左滑「备注」填写垃圾原因的作品会归到这里，电脑端永久保留不自动清理。"
                : "回收站是空的\n\n已删除的作品会移动到这里；\n左滑可填写垃圾备注或复制文件夹路径。"
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
        tableView.reloadData()
    }

    // MARK: - 列表

    override func numberOfSections(in tableView: UITableView) -> Int {
        return (showsOnlineSection ? 1 : 0) + (localItems.isEmpty ? 0 : 1)
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        if isOnlineSection(section) {
            return "💻 在线作品回收站 (\(onlineTrashItems.count))"
        }
        return currentTab == .garbage
            ? "📱 本地已标记垃圾 (\(localItems.count))"
            : "📱 本地已删除 (\(localItems.count))"
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        if isOnlineSection(section) {
            return "点某一行即恢复；左滑可彻底删除该记录。完整的删除 / 备注 / 复制路径请在在线模式下的「在线回收站」里操作。"
        }
        return "点某一行即恢复（撤销垃圾标记、次数归零）。左滑可填写垃圾备注（写入手机本地元数据）或复制文件夹路径。"
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if isOnlineSection(section) {
            return onlineTrashItems.count
        }
        return localItems.count
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

        let item = localItems[indexPath.row]
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
        library.restore(localItems[indexPath.row])
        loadData()
    }

    /// 与在线回收站的行操作严格对齐：恢复（点行）/ 备注 / 复制路径。
    override func tableView(_ tableView: UITableView,
                            trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        // 在线镜像区：与 Android `onlineTrashCard` 1:1 对齐 —— 除点行恢复外，
        // 还提供「彻底删除」（带确认弹窗）。旧实现此处直接 return nil，属功能缺口。
        if isOnlineSection(indexPath.section) {
            let onlineItem = onlineTrashItems[indexPath.row]
            let purge = UIContextualAction(style: .destructive, title: "彻底删除") { [weak self] _, _, done in
                done(true)
                self?.confirmPurgeOnlineTrash(onlineItem)
            }
            purge.backgroundColor = UIColor(red: 0.72, green: 0.18, blue: 0.16, alpha: 1)
            return UISwipeActionsConfiguration(actions: [purge])
        }
        let item = localItems[indexPath.row]

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

    // MARK: - 在线镜像区：彻底删除

    /// 镜像区「彻底删除」：确认弹窗文案与 Android `onlineTrashCard` 逐字对齐。
    private func confirmPurgeOnlineTrash(_ item: OnlineWorkLifecycle.Item) {
        let alert = UIAlertController(title: "彻底删除",
                                      message: "彻底删除后无法恢复，确定删除？",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "彻底删除", style: .destructive) { [weak self] _ in
            OnlineWorkLifecycle.deletePermanently(id: item.id)
            self?.loadData()
        })
        present(alert, animated: true)
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
