import UIKit

final class ManagedFolderPickerViewController: UITableViewController {
    private let library: WorkLibrary
    private let choices: [ManagedFolderChoice]
    var onPick: (() -> Void)?

    init(library: WorkLibrary) {
        self.library = library
        self.choices = library.managedFolderChoices()
        super.init(style: AppColors.groupedTableStyle)
        title = "选择作品文件夹"
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel, target: self, action: #selector(cancel))
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "Folder")
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return choices.count
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return "从相册接收目录中选择。电脑或其他手机传来的文件夹也会显示在这里。"
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: "Folder")
        let choice = choices[indexPath.row]
        cell.textLabel?.text = choice.title
        cell.textLabel?.numberOfLines = 2
        cell.detailTextLabel?.text = indexPath.row == 0 ? "扫描全部文件夹" : "只扫描这个文件夹及其子目录"
        cell.accessoryType = choice.url.standardizedFileURL == library.receivingRootURL?.standardizedFileURL
            ? .checkmark : .disclosureIndicator
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        library.selectManagedFolder(choices[indexPath.row].url)
        dismiss(animated: true) { [weak self] in self?.onPick?() }
    }

    @objc private func cancel() { dismiss(animated: true) }
}
