import SwiftUI
import UniformTypeIdentifiers

/// An unlocked vault's contents: browse, import, lock. Pushed from
/// `EncryptedVaultListView` once a vault is created/unlocked.
struct EncryptedVaultContentsView: View {
    @StateObject private var viewModel: EncryptedVaultContentsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var isPickingFiles = false
    // Read once per screen appearance, not live-observed — matches
    // Android's own `remember { VaultScreenSecurityPreference.isEnabled(context) }`
    // (VaultContentsScreen.kt): toggling the setting in Settings takes
    // effect the next time a vault content screen opens, not retroactively
    // on one already on screen.
    @State private var screenSecurityEnabled = VaultScreenSecurityPreference.isEnabled()

    private let vaultId: String
    private let sessionManager: VaultSessionManager

    init(vaultId: String, sessionManager: VaultSessionManager) {
        self.vaultId = vaultId
        self.sessionManager = sessionManager
        _viewModel = StateObject(wrappedValue: EncryptedVaultContentsViewModel(vaultId: vaultId, sessionManager: sessionManager))
    }

    var body: some View {
        VStack(spacing: 0) {
            // Matches CreateEncryptedVaultView/UnlockEncryptedVaultView's own
            // inline error banner — a `refresh()` failure (e.g. after an
            // import) must never silently fall back to `emptyState` with no
            // explanation. See #417.
            if let errorMessage = Self.errorBannerText(errorMessage: viewModel.errorMessage) {
                Text(errorMessage)
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(LibraVaultSpacing.md)
            }
            Group {
                if viewModel.entries.isEmpty {
                    emptyState
                } else {
                    List(viewModel.entries, id: \.fileId) { entry in
                        NavigationLink {
                            destination(for: entry)
                        } label: {
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
            }
        }
        .navigationTitle("Vault")
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                Button {
                    isPickingFiles = true
                } label: {
                    Image(systemName: "plus.circle")
                }
                .accessibilityLabel("Import files")
            }
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
        // #204: even just the list of titles/authors here is content someone
        // encrypted a vault specifically to hide, matching Android's own
        // reasoning for applying this to VaultContentsScreen (not just the
        // reader/player) — see that screen's doc comment. Screen-recording
        // detection auto-locks (`viewModel.lock()` already drives the
        // `onChange(of: viewModel.isLocked)` dismiss above); app-switcher
        // snapshot hiding needs no reaction, it's the overlay itself.
        .vaultContentSecurity(enabled: screenSecurityEnabled) {
            Task { await viewModel.lock() }
        }
        // .item, not a narrower UTType list: LibraVault reads several
        // unrelated formats (EPUB/PDF/Markdown/several audio codecs) with no
        // single UTType covering all of them — matches Folder scanning's own
        // extension-based (not UTType-based) format detection in
        // LibraryFileScanner, which is what actually decides what's
        // importable, not this picker's filter.
        .fileImporter(isPresented: $isPickingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result {
                Task { await viewModel.importFiles(urls: urls) }
            }
        }
        .sheet(isPresented: importSheetBinding) {
            ImportProgressSheet(items: viewModel.importItems, isImporting: viewModel.isImporting) {
                viewModel.clearImportItems()
            }
        }
    }

    /// EPUB/PDF open through `VaultReaderView`, audio through
    /// `VaultPlayerView` — see #203. An unsupported format (nothing this
    /// build's import picker would have accepted, so unreachable in
    /// practice) still needs a body; `VaultReaderView` itself surfaces that
    /// as its own `.error` state rather than crashing here.
    @ViewBuilder
    private func destination(for entry: VaultManifestEntry) -> some View {
        if VaultContentFormat.isAudio(entry.format) {
            VaultPlayerView(vaultId: vaultId, fileId: entry.fileId, sessionManager: sessionManager)
        } else {
            VaultReaderView(vaultId: vaultId, fileId: entry.fileId, sessionManager: sessionManager)
        }
    }

    private var importSheetBinding: Binding<Bool> {
        Binding(
            get: { !viewModel.importItems.isEmpty },
            set: { isPresented in if !isPresented { viewModel.clearImportItems() } }
        )
    }

    /// The exact banner text `body` renders above the list/empty-state for a given
    /// `viewModel.errorMessage` — pulled out into a static, independently testable
    /// function (mirrors `ReaderView.dispatchChapterPagination`'s pattern from #411
    /// round 2) so #417's view-layer fix has *some* test coverage that lives with
    /// the view itself, not only with `EncryptedVaultContentsViewModel`. This repo
    /// has no ViewInspector/snapshot UI-testing infrastructure, so no test here can
    /// prove SwiftUI's `body` actually renders this value on screen — `body`'s own
    /// use of it is kept to the single one-line call above, so that unverifiable
    /// surface is as small as it can be made without adding new test
    /// infrastructure; verified by manual read, same as the rest of this PR on a
    /// Linux runner with no Xcode.
    static func errorBannerText(errorMessage: String?) -> String? {
        errorMessage
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

/// Per-item import progress, shown as a modal sheet — mirrors Android's
/// `ImportProgressSheet` (`VaultContentsScreen.kt`). Dismiss is blocked
/// (no visible close action) while `isImporting` is true, so a batch can't
/// be walked away from mid-import without realizing it's still running;
/// once finished, "Done" clears the batch and dismisses.
// Not `private`: `errorDisplay(for:)` below needs to be reachable from
// LibraVaultTests via `@testable import` (see that function's doc comment for why).
struct ImportProgressSheet: View {
    let items: [ImportItem]
    let isImporting: Bool
    let onDone: () -> Void

    var body: some View {
        NavigationStack {
            List(items) { item in
                HStack {
                    Text(item.displayName)
                        .foregroundStyle(LibraVaultColor.onSurface)
                    Spacer()
                    statusView(for: item.status)
                }
            }
            .navigationTitle("Importing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done", action: onDone)
                        .disabled(isImporting)
                }
            }
        }
        .interactiveDismissDisabled(isImporting)
    }

    @ViewBuilder
    private func statusView(for status: ImportItemStatus) -> some View {
        switch status {
        case .pending:
            Text("Waiting")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
        case .importing:
            ProgressView()
        case .done:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(LibraVaultColor.secondary)
        case .error:
            if let display = Self.errorDisplay(for: status) {
                HStack(spacing: LibraVaultSpacing.xs) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                    Text(display.text)
                        .font(LibraVaultTypography.bodySmall)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.trailing)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(display.accessibilityLabel)
            }
        }
    }

    /// The exact `(visible text, accessibility label)` pair `statusView(for:)` renders
    /// for a `.error` item — extracted for the same reason as
    /// `EncryptedVaultContentsView.errorBannerText` above (#417 QA round 1 gap): it
    /// pulls the piece of the fix a test can actually call out of `body`'s
    /// `@ViewBuilder` closure, which can't be unit-tested directly in this repo.
    /// Returns `nil` for any non-`.error` status.
    static func errorDisplay(for status: ImportItemStatus) -> (text: String, accessibilityLabel: String)? {
        guard case .error(let message) = status else { return nil }
        return (text: message, accessibilityLabel: "Import failed: \(message)")
    }
}
