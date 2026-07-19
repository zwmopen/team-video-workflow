import UIKit

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    let library = WorkLibrary()
    private lazy var incomingTransfer = IncomingTransferService(library: library)

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        library.start()
        TransferNotifications.shared.configure()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.tintColor = UIColor(red: 0.06, green: 0.61, blue: 0.39, alpha: 1)
        let main = LibraryViewController(library: library)
        window.rootViewController = UINavigationController(rootViewController: main)
        window.makeKeyAndVisible()
        self.window = window
        if !Self.isRunningTests { incomingTransfer.start() }
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        if !Self.isRunningTests { incomingTransfer.start() }
        library.refresh(showConfirmation: false)
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        if !Self.isRunningTests { incomingTransfer.stop() }
    }

    private static var isRunningTests: Bool {
        return ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }
}
