import UIKit

// ==================================================================
// 在线回收站（与 Android 严格对齐）
//   已使用     -> 电脑端「_已发送1次（微信公众号可发）」
//   已标记垃圾 -> 电脑端「_垃圾作品（后续参考分析）」，永久保留、绝不自动清理
//
// 「恢复」两个 Tab 通用：移回「已发送0次」+ 次数归零 + 撤销垃圾标记。
// 顶栏与 Android 顶栏顺序一致：左侧返回作品（导航栏自带）→ 右侧「刷新」。
// 「📱 本地回收站」入口放在左侧，对应 Android 页面底部的同名入口，
// 保证在线模式下依然进得去手机本地回收站。
// ==================================================================
final class OnlineRecycleViewController: UITableViewController {

    private enum RecycleTab: Int {
        case sent = 0
        case garbage = 1

        var key: String { self == .sent ? "sent" : "garbage" }
        var title: String { self == .sent ? "已使用" : "已标记垃圾" }
    }

    private let library: WorkLibrary
    private let segmented: UISegmentedControl
    private var currentTab: RecycleTab = .sent
    private var works: [OnlineWorkEntry] = []
    private var sentCount = 0
    private var garbageCount = 0
    private var toastView: UIView?

    init(library: WorkLibrary) {
        self.library = library
        self.segmented = UISegmentedControl(items: [RecycleTab.sent.title, RecycleTab.garbage.title])
        super.init(style: AppColors.groupedTableStyle)
        title = "在线回收站"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()

        segmented.selectedSegmentIndex = RecycleTab.sent.rawValue
        segmented.addTarget(self, action: #selector(tabChanged), for: .valueChanged)
        segmented.autoresizingMask = [.flexibleWidth]

        let header = UIView()
        header.addSubview(segmented)
        tableView.tableHeaderView = header

        navigationItem.rightBarButtonItem = UIBarButtonItem(title: "刷新", style: .plain,
                                                            target: self, action: #selector(refreshTapped))
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: "📱 本地回收站", style: .plain,
                                                           target: self, action: #selector(openLocalTrash))

        let refresh = UIRefreshControl()
        refresh.addTarget(self, action: #selector(loadData), for: .valueChanged)
        refreshControl = refresh

        tableView.tableFooterView = UIView()
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

    // MARK: - 数据

    @objc private func loadData() {
        let tab = currentTab
        OnlineGalleryClient.shared.fetchRecycle(tab: tab.key) { [weak self] result in
            guard let self = self else { return }
            self.refreshControl?.endRefreshing()
            switch result {
            case .success(let res):
                // 两个 Tab 的角标数字来自同一份 counts，和各自列表 total 严格一致
                self.sentCount = res.sentCount
                self.garbageCount = res.garbageCount
                self.updateSegmentTitles()
                guard res.tab == self.currentTab.key else { return } // 期间切了 Tab，丢弃过期结果
                self.works = res.works
                self.render()
            case .failure(let error):
                self.works = []
                self.render()
                self.showToast("读取在线回收站失败：\(error.localizedDescription)")
            }
        }
    }

    private func updateSegmentTitles() {
        segmented.setTitle(sentCount > 0 ? "已使用 \(sentCount)" : "已使用", forSegmentAt: 0)
        segmented.setTitle(garbageCount > 0 ? "已标记垃圾 \(garbageCount)" : "已标记垃圾", forSegmentAt: 1)
    }

    @objc private func tabChanged() {
        currentTab = RecycleTab(rawValue: segmented.selectedSegmentIndex) ?? .sent
        works = []
        render()
        loadData()
    }

    @objc private func refreshTapped() {
        loadData()
    }

    @objc private func openLocalTrash() {
        navigationController?.pushViewController(TrashViewController(library: library), animated: true)
    }

    private func render() {
        if works.isEmpty {
            let label = UILabel()
            label.numberOfLines = 0
            label.textAlignment = .center
            label.textColor = AppColors.secondaryText
            label.text = currentTab == .garbage
                ? "电脑端「_垃圾作品」暂为空\n\n手机端判定删除的作品会永久保留在这里，供后续参考分析。"
                : "电脑端「_已发送1次」暂为空\n\n点过平台按钮的作品会自动进入这里。"
            tableView.backgroundView = label
        } else {
            tableView.backgroundView = nil
        }
        tableView.reloadData()
    }

    // MARK: - 列表

    override func numberOfSections(in tableView: UITableView) -> Int { return 1 }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return works.count
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return "\(currentTab.title)（\(works.count)）· 电脑在线真源"
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        let tail = currentTab == .garbage
            ? "左滑可填写垃圾备注。垃圾样本库电脑端永久保留，不会自动清理。"
            : "左滑可判定为垃圾并移入垃圾样本库。"
        return "点某一行即「恢复」：移回电脑端「已发送0次」并归零次数、撤销垃圾标记。\(tail)"
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: nil)
        let work = works[indexPath.row]
        cell.textLabel?.text = work.title
        cell.textLabel?.font = .boldSystemFont(ofSize: 16)

        var detail = "💻 电脑真源 · \(work.imageCount) 张图片"
        if currentTab == .garbage {
            let remark = work.garbageRemark
            detail += " · 🗑️ 垃圾样本\n备注：\(remark.isEmpty ? "（未填写）" : remark)"
            cell.detailTextLabel?.textColor = UIColor(red: 0.66, green: 0.24, blue: 0.20, alpha: 1)
        } else {
            detail += " · 已使用 \(max(1, work.useCount)) 次"
            if !work.dispatchedTo.isEmpty {
                detail += "\n记录：\(work.dispatchedTo.joined(separator: "、"))"
            }
            cell.detailTextLabel?.textColor = UIColor(red: 0.70, green: 0.33, blue: 0.08, alpha: 1)
        }
        detail += "\n点一下恢复 → 回到「已发送0次」"
        cell.detailTextLabel?.text = detail
        cell.detailTextLabel?.numberOfLines = 0
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        confirmRestore(works[indexPath.row])
    }

    override func tableView(_ tableView: UITableView,
                            trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
        let work = works[indexPath.row]
        if currentTab == .garbage {
            let remark = UIContextualAction(style: .normal, title: "备注") { [weak self] _, _, done in
                done(true)
                self?.promptRemark(work)
            }
            remark.backgroundColor = UIColor(red: 0.20, green: 0.45, blue: 0.62, alpha: 1)
            return UISwipeActionsConfiguration(actions: [remark])
        }
        let delete = UIContextualAction(style: .destructive, title: "删除") { [weak self] _, _, done in
            done(true)
            self?.confirmDelete(work)
        }
        return UISwipeActionsConfiguration(actions: [delete])
    }

    // MARK: - 恢复 / 删除 / 备注

    private func confirmRestore(_ work: OnlineWorkEntry) {
        let tail = currentTab == .garbage ? "，并撤销垃圾标记。" : "，使用次数归零。"
        let alert = UIAlertController(
            title: "恢复作品",
            message: "将把该作品移回电脑端「已发送0次（抖音小红书可发）」\(tail)\n\n恢复后它会重新出现在在线相册里。",
            preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "恢复", style: .default) { [weak self] _ in
            guard let self = self else { return }
            OnlineGalleryClient.shared.restoreWork(workId: work.id) { ok, msg in
                self.showToast(ok ? "♻️ 已恢复：\(msg.isEmpty ? "回到已发送0次" : msg)" : "恢复失败：\(msg)")
                if ok { self.loadData() }
            }
        })
        present(alert, animated: true)
    }

    private func confirmDelete(_ work: OnlineWorkEntry) {
        let alert = UIAlertController(
            title: "删除该作品？",
            message: "手机端：移入本地回收站\n电脑端：移入垃圾样本库并在元数据标记为垃圾（全渠道硬拦截），永久保留不自动清理。",
            preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "备注并删除", style: .default) { [weak self] _ in
            self?.promptRemarkThenDelete(work)
        })
        alert.addAction(UIAlertAction(title: "删除", style: .destructive) { [weak self] _ in
            self?.performDelete(work, remark: nil)
        })
        present(alert, animated: true)
    }

    private func promptRemark(_ work: OnlineWorkEntry) {
        let alert = UIAlertController(title: "垃圾备注（写入作品元数据）",
                                      message: "例如：文案公文味重 / 图片 AI 味浓 / 选题不合适",
                                      preferredStyle: .alert)
        alert.addTextField { tf in
            tf.text = work.garbageRemark
            tf.placeholder = "垃圾原因"
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "保存备注", style: .default) { [weak self] _ in
            guard let self = self else { return }
            let remark = (alert.textFields?.first?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            OnlineGalleryClient.shared.remarkGarbage(workId: work.id, remark: remark) { ok, msg in
                self.showToast(ok ? "✅ 已写入垃圾备注" : "写入失败：\(msg)")
                if ok { self.loadData() }
            }
        })
        present(alert, animated: true)
    }

    private func promptRemarkThenDelete(_ work: OnlineWorkEntry) {
        let alert = UIAlertController(title: "垃圾备注（随作品写入元数据）", message: nil, preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = "例如：文案公文味重 / 图片 AI 味浓"
            tf.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "确认删除", style: .destructive) { [weak self] _ in
            let remark = (alert.textFields?.first?.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            self?.performDelete(work, remark: remark.isEmpty ? nil : remark)
        })
        present(alert, animated: true)
    }

    /// 与 Android 完全一致：手机端记入本地回收站 + 电脑端移入垃圾样本库并写死垃圾标记。
    private func performDelete(_ work: OnlineWorkEntry, remark: String?) {
        _ = OnlineWorkLifecycle.moveToTrash(work: work)
        OnlineGalleryClient.shared.deleteWork(workId: work.id, remark: remark) { _, _ in }
        showToast(remark == nil ? "🗑️ 已删除：手机回收站 + 电脑垃圾样本库"
                                : "🗑️ 已删除并备注：手机回收站 + 电脑垃圾样本库")
        loadData()
    }

    // MARK: - Toast

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
