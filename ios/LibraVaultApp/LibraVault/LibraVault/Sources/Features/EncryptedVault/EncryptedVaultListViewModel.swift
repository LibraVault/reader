import Foundation

/// One row in `EncryptedVaultListView`. Swift port of Android's
/// `VaultListItemUiState`.
struct EncryptedVaultListItem: Identifiable, Equatable {
    let id: String
    let displayName: String
    let isUnlocked: Bool
}

/// Lists every registered vault + its current lock state. Swift port of
/// Android's `VaultListViewModel`.
///
/// `refresh()` is meant to be called every time this screen becomes visible
/// again, not just once at first appearance — lock state can drift while
/// this screen isn't front-most (e.g. `VaultForegroundLockObserver` locking
/// everything after the app backgrounds and returns). The view calls this
/// from `.onAppear`, which — unlike `.task`, which only runs once per view
/// identity — fires every time a pushed view is returned to the top of the
/// navigation stack, the SwiftUI-idiomatic equivalent of Android's
/// `ON_RESUME` `DisposableEffect`.
@MainActor
final class EncryptedVaultListViewModel: ObservableObject {

    @Published private(set) var vaults: [EncryptedVaultListItem] = []

    private let sessionManager: VaultSessionManager

    init(sessionManager: VaultSessionManager) {
        self.sessionManager = sessionManager
    }

    func refresh() async {
        // Newest first — matches Android's own list ordering.
        let entries = await sessionManager.listVaults().sorted { $0.createdAtEpochMillis > $1.createdAtEpochMillis }
        var items: [EncryptedVaultListItem] = []
        for entry in entries {
            let isUnlocked = await sessionManager.isUnlocked(entry.id)
            items.append(EncryptedVaultListItem(id: entry.id, displayName: entry.displayName, isUnlocked: isUnlocked))
        }
        vaults = items
    }
}
