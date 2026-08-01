import UIKit

final class LibraryFilesViewController: UITableViewController, UIDocumentInteractionControllerDelegate {
    private let rootURL: URL
    private let currentURL: URL
    private var entries: [URL] = []
    private var documentController: UIDocumentInteractionController?

    init(rootURL: URL, currentURL: URL) {
        self.rootURL = rootURL
        self.currentURL = currentURL
        super.init(style: .plain)
        title = currentURL == rootURL ? "文件" : currentURL.lastPathComponent
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppColors.background
        tableView.backgroundColor = AppColors.background
        tableView.rowHeight = 62
        tableView.tableFooterView = UIView()
        let refresh = UIRefreshControl()
        refresh.addTarget(self, action: #selector(refreshFiles), for: .valueChanged)
        tableView.refreshControl = refresh
        refreshFiles()
    }

    @objc private func refreshFiles() {
        entries = ((try? FileManager.default.contentsOfDirectory(
            at: currentURL,
            includingPropertiesForKeys: [.isDirectoryKey, .isHiddenKey, .fileSizeKey],
            options: [.skipsPackageDescendants])) ?? [])
            .filter { url in
                let values = try? url.resourceValues(forKeys: [.isHiddenKey])
                return !url.lastPathComponent.hasPrefix(".") && values?.isHidden != true
            }
            .sorted { left, right in
                let leftFolder = (try? left.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
                let rightFolder = (try? right.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true
                if leftFolder != rightFolder { return leftFolder }
                return left.lastPathComponent.localizedStandardCompare(right.lastPathComponent) == .orderedAscending
            }
        tableView.reloadData()
        tableView.refreshControl?.endRefreshing()
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return entries.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "FileCell")
            ?? UITableViewCell(style: .subtitle, reuseIdentifier: "FileCell")
        let url = entries[indexPath.row]
        let values = try? url.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
        let isFolder = values?.isDirectory == true
        cell.textLabel?.text = url.lastPathComponent
        cell.textLabel?.font = .systemFont(ofSize: 16, weight: isFolder ? .semibold : .regular)
        cell.detailTextLabel?.text = isFolder ? "文件夹" : ByteCountFormatter.string(
            fromByteCount: Int64(values?.fileSize ?? 0), countStyle: .file)
        cell.detailTextLabel?.textColor = AppColors.secondaryText
        cell.imageView?.image = AlbumToolbarIcon.image(isFolder ? .folder : .share, color: view.tintColor)
        cell.accessoryType = isFolder ? .disclosureIndicator : .none
        cell.backgroundColor = AppColors.background
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let url = entries[indexPath.row]
        if (try? url.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true {
            navigationController?.pushViewController(
                LibraryFilesViewController(rootURL: rootURL, currentURL: url), animated: true)
            return
        }
        let controller = UIDocumentInteractionController(url: url)
        controller.delegate = self
        documentController = controller
        if !controller.presentPreview(animated: true) {
            controller.presentOptionsMenu(from: view.bounds, in: view, animated: true)
        }
    }

    func documentInteractionControllerViewControllerForPreview(
        _ controller: UIDocumentInteractionController) -> UIViewController { return self }
}
