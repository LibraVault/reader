import SwiftUI

@main
struct LibraVaultApp: App {
    @StateObject private var appState: AppState
    @StateObject private var billingManager: StoreKitBillingManager
    @StateObject private var encryptedVaultRuntime = EncryptedVaultRuntime()

    /// Custom init (rather than each `@StateObject`'s usual bare `= Type()` default)
    /// so `appState` and `billingManager` share the exact same
    /// `StoreKitBillingManager` instance — `SettingsView` reads purchase state via
    /// `@EnvironmentObject var billingManager` for its own view-update subscription,
    /// while `AppState.isSupporter` mirrors that same instance's `$isSupporter`
    /// publisher (see `AppState.init`). Two independently-constructed managers would
    /// each run their own StoreKit product load/entitlement refresh and silently
    /// diverge from one another.
    init() {
        LibraVaultNavigationBarAppearance.apply()

        let billing = StoreKitBillingManager()
        _billingManager = StateObject(wrappedValue: billing)
        _appState = StateObject(wrappedValue: AppState(billingManager: billing))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(appState)
                .environmentObject(billingManager)
                .environmentObject(encryptedVaultRuntime)
                // Fires when the OS hands LibraVault a file via "Open In"/"Copy to
                // LibraVault" from another app's share sheet (see Info.plist's
                // CFBundleDocumentTypes) — the single entry point for every format
                // LibraryFileScanner knows how to read, whether the app was already
                // running or just launched to handle this.
                .onOpenURL { url in
                    Task { await appState.importSharedFile(url: url) }
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
                    // (#294) Pushes the Reader for a file shared in from another app
                    // once importSharedFile finishes — see AppState.pendingImportedBook's
                    // doc comment. `item:` clears the binding to nil (and so this
                    // destination) automatically when the pushed ReaderView is popped,
                    // same as tapping a book normally.
                    .navigationDestination(item: pendingImportedBookBinding) { book in
                        ReaderView(book: book)
                    }
            }
            MiniPlayerBar(onTap: { appState.shouldNavigateToPlayer = true })
        }
        .animation(.easeInOut(duration: 0.2), value: appState.nowPlayingBook?.id)
        // Lives at the root rather than on any one screen because appState.error can
        // be set from outside the currently-visible screen entirely — e.g.
        // importSharedFile firing from LibraVaultApp's `.onOpenURL` while the user is
        // looking at Settings or the Reader, not the Library. Without this, every
        // AppState.error assignment (addVault's storageAccessDenied included — this
        // was already true before importSharedFile existed) failed completely
        // silently: nothing anywhere read the property.
        .alert("Error", isPresented: errorAlertBinding) {
            Button("OK", role: .cancel) { appState.clearError() }
        } message: {
            Text(appState.error?.errorDescription ?? "")
        }
    }

    private var navigateToPlayerBinding: Binding<Bool> {
        Binding(get: { appState.shouldNavigateToPlayer }, set: { appState.shouldNavigateToPlayer = $0 })
    }

    private var pendingImportedBookBinding: Binding<BookItem?> {
        Binding(get: { appState.pendingImportedBook }, set: { appState.pendingImportedBook = $0 })
    }

    private var errorAlertBinding: Binding<Bool> {
        Binding(
            get: { appState.error != nil },
            set: { isPresented in if !isPresented { appState.clearError() } }
        )
    }
}
