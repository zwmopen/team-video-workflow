import UIKit

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?
    let library = WorkLibrary()
    private lazy var incomingTransfer = IncomingTransferService(library: library)
    private var cleanupTimer: Timer?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        CleanupPreferences.registerDefaults()
        library.start()
        // DSH-192：在线图磁盘缓存淘汰必须在启动就跑一次。
        // 实测 0.8.119 装上后 App 确实启动过（容器内 com.apple.dyld 22:22 被写过），
        // 但缓存目录一直是 884MB —— 因为 OnlineGalleryClient.shared 是懒加载的，
        // 只有用户点进「在线」才会 init()，淘汰逻辑也就跟着永远不执行。
        // 把「只写不删」的兜底挪到启动链路，不再依赖用户走哪个页签。
        OnlineGalleryClient.shared.trimDiskCacheIfNeeded()
        TransferNotifications.shared.configure()
        let window = UIWindow(frame: UIScreen.main.bounds)
        window.tintColor = UIColor(red: 0.06, green: 0.61, blue: 0.39, alpha: 1)
        AppThemePreferences.applyTheme(to: window)
        let main = LibraryViewController(library: library)
        window.rootViewController = UINavigationController(rootViewController: main)
        window.makeKeyAndVisible()
        self.window = window
        if !Self.isRunningTests { incomingTransfer.start() }
        return true
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        application.isIdleTimerDisabled = true
        if !Self.isRunningTests { incomingTransfer.start() }
        library.refresh(showConfirmation: false)
        cleanupTimer?.invalidate()
        cleanupTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            self?.library.refresh(showConfirmation: false)
        }
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        application.isIdleTimerDisabled = false
        if !Self.isRunningTests { incomingTransfer.stop() }
        cleanupTimer?.invalidate()
        cleanupTimer = nil
    }

    private static var isRunningTests: Bool {
        return ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

}
