import Foundation

enum AutoLivePreferences {
    private static let autoLiveKey = "album.autoLiveOnShareEnabled.v1"

    static var isEnabled: Bool {
        get {
            if UserDefaults.standard.object(forKey: autoLiveKey) == nil {
                return false
            }
            return UserDefaults.standard.bool(forKey: autoLiveKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: autoLiveKey)
        }
    }
}
