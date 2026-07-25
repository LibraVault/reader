import SwiftUI

@main
struct LibraVaultApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
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
