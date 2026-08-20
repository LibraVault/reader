import SwiftUI

/// An unlocked vault's contents — browse only for now; import lands in a
/// follow-up PR (see `EncryptedVaultContentsViewModel`'s doc comment).
/// Pushed from `EncryptedVaultListView` once a vault is created/unlocked.
struct EncryptedVaultContentsView: View {
    @StateObject private var viewModel: EncryptedVaultContentsViewModel
    @Environment(\.dismiss) private var dismiss

    init(vaultId: String, sessionManager: VaultSessionManager) {
        _viewModel = StateObject(wrappedValue: EncryptedVaultContentsViewModel(vaultId: vaultId, sessionManager: sessionManager))
    }

    var body: some View {
        Group {
            if viewModel.entries.isEmpty {
                emptyState
            } else {
                List(viewModel.entries, id: \.fileId) { entry in
                    VStack(alignment: .leading, spacing: LibraVaultSpacing.xs) {
                        Text(entry.title)
                            .foregroundStyle(LibraVaultColor.onSurface)
                        if let author = entry.author {
                            Text(author)
                                .font(LibraVaultTypography.bodySmall)
                                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        .navigationTitle("Vault")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("Lock") {
                    // dismiss() itself happens in onChange(of: isLocked) below
                    // once viewModel.lock() flips it, so this doesn't call it twice.
                    Task { await viewModel.lock() }
                }
            }
        }
        .task { await viewModel.refresh() }
        .onChange(of: viewModel.isLocked) { _, isLocked in
            if isLocked { dismiss() }
        }
    }

    private var emptyState: some View {
        VStack(spacing: LibraVaultSpacing.md) {
            Image(systemName: "lock.shield")
                .font(.system(size: 40))
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            Text("This vault is empty")
                .font(LibraVaultTypography.titleMedium)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
