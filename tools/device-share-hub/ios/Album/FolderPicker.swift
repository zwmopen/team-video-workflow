import UIKit

@available(iOS 13.0, *)
final class FolderPickerController: UIDocumentPickerViewController, UIDocumentPickerDelegate {
    var onPick: ((URL) -> Void)?

    init() {
        super.init(documentTypes: ["public.folder"], in: .open)
        delegate = self
        allowsMultipleSelection = false
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if let url = urls.first { onPick?(url) }
    }
}

final class ImportPickerController: UIDocumentPickerViewController, UIDocumentPickerDelegate {
    var onPick: (([URL]) -> Void)?

    init() {
        super.init(documentTypes: ["public.data", "public.archive", "public.image", "public.text"],
                   in: .import)
        delegate = self
        allowsMultipleSelection = true
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        if !urls.isEmpty { onPick?(urls) }
    }
}
