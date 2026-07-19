import SwiftUI

@main
struct AlbumApp: App {
    @StateObject private var library = WorkLibrary()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(library)
                .tint(Color(red: 0.06, green: 0.61, blue: 0.39))
                .task { await library.start() }
        }
    }
}
