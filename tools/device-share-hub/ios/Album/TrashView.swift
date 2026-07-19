import UIKit

final class TrashViewController: UITableViewController {
    private let library: WorkLibrary

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
        render()
    }

    private func render() {
        navigationItem.rightBarButtonItem?.isEnabled = !library.trash.isEmpty
        if library.trash.isEmpty {
            let label = UILabel()
            label.text = "回收站是空的\n\n上一天已分享的作品会移动到这里，保留 7 天。"
            label.numberOfLines = 0
            label.textAlignment = .center
            label.textColor = AppColors.secondaryText
            tableView.backgroundView = label
        } else { tableView.backgroundView = nil }
        tableView.reloadData()
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return library.trash.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let item = library.trash[indexPath.row]
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        cell.textLabel?.text = item.name
        cell.textLabel?.font = .boldSystemFont(ofSize: 16)
        cell.detailTextLabel?.text = "已打开分享 \(item.shareCount) 次 · 点一下恢复"
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        library.restore(library.trash[indexPath.row])
        render()
    }

    @objc private func confirmClear() {
        let alert = UIAlertController(title: "清空回收站？", message: "会从手机实际文件夹中永久删除，无法恢复。",
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "清空", style: .destructive) { [weak self] _ in
            do { try self?.library.clearTrash(); self?.render() }
            catch {
                let failure = UIAlertController(title: "清空失败", message: error.localizedDescription,
                                                preferredStyle: .alert)
                failure.addAction(UIAlertAction(title: "知道了", style: .default))
                self?.present(failure, animated: true)
            }
        })
        present(alert, animated: true)
    }
}
