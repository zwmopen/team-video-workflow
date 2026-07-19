import Foundation
import UserNotifications
import AudioToolbox

final class TransferNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = TransferNotifications()

    func configure() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
    }

    func setNotificationsEnabled(_ enabled: Bool, completion: @escaping (Bool) -> Void) {
        guard enabled else {
            NotificationPreferences.notificationsEnabled = false
            completion(true)
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, _ in
            NotificationPreferences.notificationsEnabled = granted
            DispatchQueue.main.async { completion(granted) }
        }
    }

    func show(_ title: String, body: String, id: String) {
        if NotificationPreferences.vibrationEnabled {
            DispatchQueue.main.async { AudioServicesPlaySystemSound(kSystemSoundID_Vibrate) }
        }
        guard NotificationPreferences.notificationsEnabled else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: id, content: content, trigger: nil), withCompletionHandler: nil)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.alert, .sound])
    }
}
