import SwiftUI

/// PIN or recovery-key unlock, pushed from `EncryptedVaultListView`.
/// `onUnlocked` fires once `viewModel.didUnlock` flips — the view observes
/// that rather than trusting a synchronous return from the submit action,
/// since unlock itself is `async`. Swift port of Android's
/// `UnlockVaultScreen`.
struct UnlockEncryptedVaultView: View {
    @StateObject private var viewModel: UnlockEncryptedVaultViewModel
    @Environment(\.dismiss) private var dismiss
    let onUnlocked: (String) -> Void

    init(vaultId: String, displayName: String, sessionManager: VaultSessionManager, onUnlocked: @escaping (String) -> Void) {
        _viewModel = StateObject(wrappedValue: UnlockEncryptedVaultViewModel(
            vaultId: vaultId, displayName: displayName, sessionManager: sessionManager
        ))
        self.onUnlocked = onUnlocked
    }

    var body: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.lg) {
            Text(viewModel.displayName)
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onBackground)

            switch viewModel.mode {
            case .pin:
                pinContent
            case .recoveryKey:
                recoveryKeyContent
            }

            if let errorMessage = viewModel.errorMessage {
                Text(errorMessage)
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(.red)
            }

            Spacer()
            modeSwitchLink
        }
        .padding(LibraVaultSpacing.lg)
        .navigationTitle("Unlock Vault")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: viewModel.didUnlock) { _, unlocked in
            // Same ordering as CreateEncryptedVaultView's "Done" button —
            // onUnlocked first, then dismiss this screen off the stack.
            if unlocked {
                onUnlocked(viewModel.vaultId)
                dismiss()
            }
        }
    }

    private var pinContent: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.md) {
            PinField(label: "PIN", text: $viewModel.pin)

            // The whole "countdown vs. Unlock button" decision lives inside
            // this TimelineView, not just the countdown text — otherwise
            // once the throttle expires there is nothing left to trigger a
            // SwiftUI re-render, and the Unlock button would never
            // reappear on its own (only some *unrelated* @Published change
            // would happen to bring it back). Ticking every 250ms matches
            // Android's own `LaunchedEffect` + `delay(250)` polling cadence.
            TimelineView(.periodic(from: .now, by: 0.25)) { _ in
                if let remaining = viewModel.currentRemainingDelay() {
                    Text("Try again in \(Int(remaining.rounded(.up)))s")
                        .font(LibraVaultTypography.bodySmall)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                        .frame(maxWidth: .infinity)
                } else if viewModel.isUnlocking {
                    ProgressView()
                } else {
                    Button("Unlock") {
                        Task { await viewModel.unlockWithPinSubmitted() }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(LibraVaultColor.primary)
                    .frame(maxWidth: .infinity)
                    .disabled(viewModel.pin.isEmpty)
                }
            }
        }
    }

    private var recoveryKeyContent: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.md) {
            Text("Enter your recovery key")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            TextField("XXXX-XXXX-...", text: $viewModel.recoveryKeyText)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .font(.system(.body, design: .monospaced))

            if viewModel.isUnlocking {
                ProgressView()
            } else {
                Button("Unlock") {
                    Task { await viewModel.unlockWithRecoveryKeySubmitted() }
                }
                .buttonStyle(.borderedProminent)
                .tint(LibraVaultColor.primary)
                .frame(maxWidth: .infinity)
                .disabled(viewModel.recoveryKeyText.isEmpty)
            }
        }
        // Unconditional, independent of #204 — entering a recovery key is as
        // sensitive as displaying one.
        .secureVaultScreen()
    }

    @ViewBuilder
    private var modeSwitchLink: some View {
        switch viewModel.mode {
        case .pin:
            Button("Use recovery key instead") { viewModel.switchToRecoveryKey() }
                .font(LibraVaultTypography.bodySmall)
        case .recoveryKey:
            // Hidden, not just disabled, once the Secure Enclave key is
            // confirmed gone — there is no PIN path back for this vault.
            if !viewModel.keystoreKeyLost {
                Button("Use PIN instead") { viewModel.switchToPin() }
                    .font(LibraVaultTypography.bodySmall)
            }
        }
    }
}
