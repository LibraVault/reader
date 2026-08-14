import SwiftUI

@main
struct LibraVaultApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                // Fires when the OS hands LibraVault a file via "Open In"/"Copy to
                // LibraVault" from another app's share sheet (see Info.plist's
                // CFBundleDocumentTypes) — the single entry point for every format
                // LibraryFileScanner knows how to read, whether the app was already
                // running or just launched to handle this.
                .onOpenURL { url in
                    appState.importSharedFile(url: url)
                }
        }
    }
}

/// Owns the single NavigationStack (see the Phase 1 rule: only the root owns a
/// stack, everything else is a pushed destination) and overlays MiniPlayerBar
/// outside it, so the bar persists across every pushed screen — Library, Reader,
/// Settings, Player itself — the same way Android's Scaffold bottomBar does,
/// instead of disappearing the moment something else gets pushed.
struct RootView: View {
    @EnvironmentObject var appState: AppState

    var body: some View {
        ZStack(alignment: .bottom) {
            NavigationStack {
                LibraryView()
                    .navigationDestination(isPresented: navigateToPlayerBinding) {
                        PlayerView()
                    }
            }
            MiniPlayerBar(onTap: { appState.shouldNavigateToPlayer = true })
        }
        .animation(.easeInOut(duration: 0.2), value: appState.nowPlayingBook?.id)
    }

    private var navigateToPlayerBinding: Binding<Bool> {
        Binding(get: { appState.shouldNavigateToPlayer }, set: { appState.shouldNavigateToPlayer = $0 })
    }
}
