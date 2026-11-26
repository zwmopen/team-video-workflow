import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        let win = UIWindow(frame: UIScreen.main.bounds)
        let nav = UINavigationController(rootViewController: GalleryViewController())
        win.rootViewController = nav
        win.makeKeyAndVisible()
        self.window = win
        return true
    }
}
