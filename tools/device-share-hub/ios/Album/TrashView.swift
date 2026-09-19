import UIKit

final class TrashViewController: UITableViewController {
    private let library: WorkLibrary
    private var onlineTrashItems: [OnlineWorkLifecycle.Item] = []

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

    private func render() {
        navigationItem.rightBarButtonItem?.isEnabled = totalCount > 0
        if totalCount == 0 {
            let label = UILabel()
            label.text = "回收站是空的\n\n已分享或删除的作品会移动到这里，遵循手机端设置保留。"
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
        if !onlineTrashItems.isEmpty && section == 0 {
            return "💻 在线作品回收站 (\(onlineTrashItems.count))"
        }
        return "📱 手机本地回收站 (\(library.trash.count))"
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        if !onlineTrashItems.isEmpty && section == 0 {
            return onlineTrashItems.count
        }
        return library.trash.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        if !onlineTrashItems.isEmpty && indexPath.section == 0 {
            let item = onlineTrashItems[indexPath.row]
            cell.textLabel?.text = item.title
            cell.textLabel?.font = .boldSystemFont(ofSize: 16)
            cell.detailTextLabel?.text = "💻 电脑在线作品 · [\(item.destination)] · 已使用 \(item.useCount) 次 · 点一下恢复"
            cell.detailTextLabel?.textColor = UIColor(red: 0.15, green: 0.45, blue: 0.85, alpha: 1)
            cell.accessoryType = .disclosureIndicator
            return cell
        }

        let item = library.trash[indexPath.row]
        cell.textLabel?.text = item.name
        cell.textLabel?.font = .boldSystemFont(ofSize: 16)
        cell.detailTextLabel?.text = "已打开分享 \(item.shareCount) 次 · 点一下恢复"
        cell.detailTextLabel?.textColor = AppColors.secondaryText
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        if !onlineTrashItems.isEmpty && indexPath.section == 0 {
            let item = onlineTrashItems[indexPath.row]
            OnlineWorkLifecycle.restoreFromTrash(id: item.id)
            loadData()
            return
        }

        library.restore(library.trash[indexPath.row])
        loadData()
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
                let failure = UIAlertController(title: "清空失败", message: error.localizedDescription,
                                                preferredStyle: .alert)
                failure.addAction(UIAlertAction(title: "知道了", style: .default))
                self.present(failure, animated: true)
            }
        })
        present(alert, animated: true)
    }
}
