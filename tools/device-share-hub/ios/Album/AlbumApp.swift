import UIKit

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    let library = WorkLibrary()

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.tintColor = UIColor(red: 0.06, green: 0.61, blue: 0.39, alpha: 1)
        let main = LibraryViewController(library: library)
        window.rootViewController = UINavigationController(rootViewController: main)
        window.makeKeyAndVisible()
        self.window = window
        library.start()
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        library.refresh(showConfirmation: false)
    }
}
