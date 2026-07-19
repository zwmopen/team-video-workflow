import UIKit
import MobileCoreServices

final class TransferViewController: UIViewController, UITableViewDataSource, UITableViewDelegate, UIDocumentPickerDelegate {
    private let table = UITableView(frame: .zero, style: .plain)
    private let status = UILabel()
    private let progress = UIProgressView(progressViewStyle: .default)
    private var peers: [TransferPeer] = []
    private var selectedID: String?
    private var timer: Timer?
    private let sender = OutgoingTransferClient()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "传送"
        view.backgroundColor = AppColors.background
        configureUI()
        NotificationCenter.default.addObserver(self, selector: #selector(reloadPeers), name: .transferPeersChanged, object: nil)
        reloadPeers()
        timer = Timer.scheduledTimer(timeInterval: 2, target: self, selector: #selector(reloadPeers), userInfo: nil, repeats: true)
    }

    deinit { timer?.invalidate(); NotificationCenter.default.removeObserver(self) }

    private func configureUI() {
        let hint = UILabel()
        hint.text = "先选设备，再选文件或文件夹\n两台设备需在同一 Wi‑Fi"
        hint.numberOfLines = 0
        hint.textAlignment = .center
        hint.textColor = AppColors.secondaryText
        hint.font = .systemFont(ofSize: 14)
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)

        table.translatesAutoresizingMaskIntoConstraints = false
        table.backgroundColor = .clear
        table.separatorStyle = .none
        table.dataSource = self
        table.delegate = self
        table.rowHeight = 70
        view.addSubview(table)

        let fileButton = actionButton("选择文件", selector: #selector(pickFiles))
        let folderButton = actionButton("选择文件夹", selector: #selector(pickFolder))
        let actions = UIStackView(arrangedSubviews: [fileButton, folderButton])
        actions.axis = .horizontal; actions.spacing = 10; actions.distribution = .fillEqually
        actions.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(actions)

        progress.translatesAutoresizingMaskIntoConstraints = false
        progress.isHidden = true
        view.addSubview(progress)
        status.text = "正在发现附近设备…"
        status.textAlignment = .center
        status.numberOfLines = 2
        status.font = .systemFont(ofSize: 13)
        status.textColor = UIColor(red: 0.28, green: 0.43, blue: 0.34, alpha: 1)
        status.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(status)

        NSLayoutConstraint.activate([
            hint.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 14),
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            table.topAnchor.constraint(equalTo: hint.bottomAnchor, constant: 12),
            table.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            table.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            table.bottomAnchor.constraint(equalTo: actions.topAnchor, constant: -12),
            actions.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 18),
            actions.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -18),
            actions.heightAnchor.constraint(equalToConstant: 50),
            progress.topAnchor.constraint(equalTo: actions.bottomAnchor, constant: 14),
            progress.leadingAnchor.constraint(equalTo: actions.leadingAnchor),
            progress.trailingAnchor.constraint(equalTo: actions.trailingAnchor),
            status.topAnchor.constraint(equalTo: progress.bottomAnchor, constant: 9),
            status.leadingAnchor.constraint(equalTo: actions.leadingAnchor),
            status.trailingAnchor.constraint(equalTo: actions.trailingAnchor),
            status.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -12)
        ])
    }

    private func actionButton(_ title: String, selector: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.setTitleColor(.white, for: .normal)
        button.titleLabel?.font = .boldSystemFont(ofSize: 16)
        button.backgroundColor = view.tintColor
        button.layer.cornerRadius = 14
        button.addTarget(self, action: selector, for: .touchUpInside)
        return button
    }

    @objc private func reloadPeers() {
        peers = PeerDirectory.shared.peers()
        if let selected = selectedID, !peers.contains(where: { $0.id == selected }) { selectedID = nil }
        table.reloadData()
        if peers.isEmpty { status.text = "暂未发现设备，请在另一台设备上打开“相册”或电脑中控" }
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int { return peers.count }
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let identifier = "PeerCell"
        let cell = tableView.dequeueReusableCell(withIdentifier: identifier) ?? UITableViewCell(style: .subtitle, reuseIdentifier: identifier)
        let peer = peers[indexPath.row]
        cell.textLabel?.text = peer.name.isEmpty ? peer.model : peer.name
        cell.detailTextLabel?.text = peer.model
        cell.textLabel?.font = .boldSystemFont(ofSize: 16)
        cell.backgroundColor = peer.id == selectedID ? view.tintColor.withAlphaComponent(0.14) : .white
        cell.layer.cornerRadius = 15
        cell.layer.masksToBounds = true
        cell.accessoryType = peer.id == selectedID ? .checkmark : .none
        return cell
    }
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        selectedID = peers[indexPath.row].id
        status.text = "已选择“\(peers[indexPath.row].name)”"
        table.reloadData()
    }

    @objc private func pickFiles() {
        guard selectedPeer() != nil else { showMessage("请先选择接收设备"); return }
        let picker = UIDocumentPickerViewController(documentTypes: ["public.data", "public.content"], in: .open)
        picker.delegate = self
        picker.allowsMultipleSelection = true
        present(picker, animated: true)
    }

    @objc private func pickFolder() {
        guard selectedPeer() != nil else { showMessage("请先选择接收设备"); return }
        guard #available(iOS 13.0, *) else { showMessage("这台 iPhone 可选择文件；文件夹请先在“文件”中压缩后选择 ZIP") ; return }
        let picker = FolderPickerController()
        picker.onPick = { [weak self] url in self?.prepareFolder(url) }
        present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard !urls.isEmpty else { return }
        do {
            let items = try urls.map(copyForSending)
            send(items)
        } catch { showMessage("读取失败：\(error.localizedDescription)") }
    }

    @available(iOS 13.0, *)
    private func prepareFolder(_ folder: URL) {
        guard selectedPeer() != nil else { return }
        status.text = "正在整理文件夹…"; progress.isHidden = false; progress.progress = 0
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let output = FileManager.default.temporaryDirectory.appendingPathComponent("album-folder-\(UUID().uuidString).zip")
                try StoredZipWriter.create(folder: folder, at: output)
                let item = OutgoingItem(url: output, name: output.lastPathComponent, mime: "application/zip", temporary: true)
                DispatchQueue.main.async { self.send([item]) }
            } catch { DispatchQueue.main.async { self.showMessage("整理失败：\(error.localizedDescription)") } }
        }
    }

    private func copyForSending(_ source: URL) throws -> OutgoingItem {
        let accessed = source.startAccessingSecurityScopedResource()
        defer { if accessed { source.stopAccessingSecurityScopedResource() } }
        let name = source.lastPathComponent.isEmpty ? "文件" : source.lastPathComponent
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent("send-\(UUID().uuidString)-\(name)")
        try FileManager.default.copyItem(at: source, to: destination)
        return OutgoingItem(url: destination, name: name, mime: mime(name), temporary: true)
    }

    private func send(_ items: [OutgoingItem]) {
        guard let peer = selectedPeer() else { return }
        progress.isHidden = false; progress.progress = 0
        sender.send(items, to: peer, progress: { [weak self] value, message in
            self?.progress.progress = Float(value) / 100; self?.status.text = message
        }, completion: { [weak self] result in
            if case .failure(let error) = result { self?.showMessage("传送失败：\(error.localizedDescription)") }
        })
    }

    private func selectedPeer() -> TransferPeer? {
        guard let id = selectedID else { return nil }
        return peers.first { $0.id == id }
    }
    private func mime(_ name: String) -> String {
        let ext = (name as NSString).pathExtension as CFString
        guard let uti = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, ext, nil)?.takeRetainedValue(),
              let type = UTTypeCopyPreferredTagWithClass(uti, kUTTagClassMIMEType)?.takeRetainedValue() else {
            return "application/octet-stream"
        }
        return type as String
    }
    private func showMessage(_ text: String) {
        status.text = text
        let alert = UIAlertController(title: "传送", message: text, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "知道了", style: .default))
        present(alert, animated: true)
    }
}
