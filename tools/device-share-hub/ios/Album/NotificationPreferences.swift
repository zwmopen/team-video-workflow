import Foundation

enum NotificationPreferences {
    private static let notificationsKey = "album.notificationsEnabled.v1"
    private static let vibrationKey = "album.vibrationEnabled.v1"

    static var notificationsEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: notificationsKey) }
        set { UserDefaults.standard.set(newValue, forKey: notificationsKey) }
    }

    static var vibrationEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: vibrationKey) }
        set { UserDefaults.standard.set(newValue, forKey: vibrationKey) }
    }
}
