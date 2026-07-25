import SwiftUI

@main
struct LibraVaultApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            LibraryView()
                .environmentObject(appState)
        }
    }
}
