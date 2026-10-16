import Foundation

enum ClipboardSyncPreferences {
    private static let clipboardSyncKey = "album.clipboardSyncEnabled.v1"

    static var isEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: clipboardSyncKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: clipboardSyncKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: clipboardSyncKey)
        }
    }
}
