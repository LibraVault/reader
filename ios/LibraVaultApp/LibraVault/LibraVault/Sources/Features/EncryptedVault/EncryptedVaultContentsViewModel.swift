import Foundation

/// Lists an unlocked vault's manifest entries and owns locking it again.
/// Import (#202's remaining acceptance criterion) lands in a follow-up PR —
/// this is deliberately scoped to browse + lock only, so the auth flow
/// (create/unlock/list) can land and be reviewed on its own first.
@MainActor
final class EncryptedVaultContentsViewModel: ObservableObject {

    let vaultId: String

    @Published private(set) var entries: [VaultManifestEntry] = []
    @Published private(set) var isLocked = false
    @Published var errorMessage: String?

    private let sessionManager: VaultSessionManager

    init(vaultId: String, sessionManager: VaultSessionManager) {
        self.vaultId = vaultId
        self.sessionManager = sessionManager
    }

    func refresh() async {
        guard await sessionManager.isUnlocked(vaultId) else {
            isLocked = true
            return
        }
        do {
            entries = try await sessionManager.requireUnlocked(vaultId).listEntries()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func lock() async {
        await sessionManager.lock(vaultId)
        isLocked = true
    }
}
