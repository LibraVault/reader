import SwiftUI

/// Entry point for Encrypted Vaults: lists every registered vault, lets the
/// user create a new one, and routes into unlock (locked) or straight into
/// contents (already unlocked this session). Swift port of Android's
/// `VaultListScreen`.
///
/// Tapping/creating/unlocking a vault all converge on the same
/// `activeVaultId` state driving `.navigationDestination(isPresented:)` to
/// push `EncryptedVaultContentsView` — `CreateEncryptedVaultView` and
/// `UnlockEncryptedVaultView` both set this and pop themselves via
/// `@Environment(\.dismiss)` on success, so the net effect matches Android's
/// `popUpTo(VaultList.route)`: Back from Contents returns here, not to the
/// wizard/unlock screen that led to it.
struct EncryptedVaultListView: View {
    // A plain stored property, not `@EnvironmentObject`: this view's
    // `@StateObject viewModel` needs `runtime.sessionManager` inside `init`,
    // before SwiftUI has resolved any `@EnvironmentObject` from the
    // environment — constructor injection sidesteps that ordering problem
    // entirely, matching every other vault screen's explicit
    // `sessionManager` parameter (`CreateEncryptedVaultView`,
    // `UnlockEncryptedVaultView`, `EncryptedVaultContentsView`).
    private let runtime: EncryptedVaultRuntime
    @StateObject private var viewModel: EncryptedVaultListViewModel
    @State private var activeVaultId: String?
    @State private var showingExplainer = !EncryptedVaultListView.hasShownExplainer

    init(runtime: EncryptedVaultRuntime) {
        self.runtime = runtime
        _viewModel = StateObject(wrappedValue: EncryptedVaultListViewModel(sessionManager: runtime.sessionManager))
    }

    var body: some View {
        List {
            ForEach(viewModel.vaults) { vault in
                row(for: vault)
            }

            NavigationLink {
                CreateEncryptedVaultView(sessionManager: runtime.sessionManager, onCreated: navigateToContentsAndDismiss)
            } label: {
                Label("New Vault", systemImage: "plus.circle")
            }
            .foregroundStyle(LibraVaultColor.primary)
        }
        .navigationTitle("Encrypted Vaults")
        .onAppear {
            Task { await viewModel.refresh() }
        }
        // `.navigationDestination(item:)` needs `Identifiable`, which bare
        // `String` isn't — `isPresented:` derived from `activeVaultId`'s
        // nil-ness does the same job without a wrapper type, matching
        // RootView's own `errorAlertBinding`/`navigateToPlayerBinding` shape.
        .navigationDestination(isPresented: Binding(
            get: { activeVaultId != nil },
            set: { isPresented in if !isPresented { activeVaultId = nil } }
        )) {
            if let activeVaultId {
                EncryptedVaultContentsView(vaultId: activeVaultId, sessionManager: runtime.sessionManager)
            }
        }
        .alert("Vaults vs. Folders", isPresented: $showingExplainer) {
            Button("Got it", role: .cancel) {
                EncryptedVaultListView.hasShownExplainer = true
            }
        } message: {
            Text("Folders (in Settings) just point at files already on your device. Vaults encrypt copies of your files behind a PIN — a real second, separate place, not just a filter on your existing library.")
        }
    }

    @ViewBuilder
    private func row(for vault: EncryptedVaultListItem) -> some View {
        if vault.isUnlocked {
            Button {
                activeVaultId = vault.id
            } label: {
                rowLabel(for: vault)
            }
        } else {
            NavigationLink {
                UnlockEncryptedVaultView(
                    vaultId: vault.id,
                    displayName: vault.displayName,
                    sessionManager: runtime.sessionManager,
                    onUnlocked: navigateToContentsAndDismiss
                )
            } label: {
                rowLabel(for: vault)
            }
        }
    }

    private func rowLabel(for vault: EncryptedVaultListItem) -> some View {
        HStack {
            Image(systemName: vault.isUnlocked ? "lock.open.fill" : "lock.fill")
                .foregroundStyle(vault.isUnlocked ? LibraVaultColor.secondary : LibraVaultColor.onSurfaceVariant)
            Text(vault.displayName)
                .foregroundStyle(LibraVaultColor.onSurface)
        }
    }

    /// Passed as `onCreated`/`onUnlocked` to the child wizard/unlock screens.
    /// Setting `activeVaultId` here (on *this* view, the one whose
    /// `.navigationDestination(item:)` owns the push) is what makes the net
    /// navigation land on Contents regardless of which child screen called
    /// it — the child pops itself via its own `dismiss()`, this view pushes
    /// Contents on top of what's left.
    private func navigateToContentsAndDismiss(_ vaultId: String) {
        activeVaultId = vaultId
    }

    /// One-time "Folder vs Vault" explainer — doubly important on iOS given
    /// #323's rename: existing users already know Settings' "Folders" as
    /// (until #323) "Vaults," so this screen introducing a *different*
    /// "Vault" concept is exactly the confusion Android's own explainer
    /// exists to head off.
    private static var hasShownExplainer: Bool {
        get { UserDefaults.standard.bool(forKey: "xyz.libravault.encryptedVaultExplainerShown") }
        set { UserDefaults.standard.set(newValue, forKey: "xyz.libravault.encryptedVaultExplainerShown") }
    }
}
