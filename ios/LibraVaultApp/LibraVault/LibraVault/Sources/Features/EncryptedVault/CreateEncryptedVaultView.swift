import SwiftUI

/// 4-step create-vault wizard, pushed from `EncryptedVaultListView`.
/// `onCreated` fires only from the recovery-key step's "Done" button once
/// `hasConfirmedSaved` is checked — no path skips acknowledging the
/// recovery key was saved. Swift port of Android's `CreateVaultScreen`.
struct CreateEncryptedVaultView: View {
    @StateObject private var viewModel: CreateEncryptedVaultViewModel
    @Environment(\.dismiss) private var dismiss
    let onCreated: (String) -> Void

    init(sessionManager: VaultSessionManager, onCreated: @escaping (String) -> Void) {
        _viewModel = StateObject(wrappedValue: CreateEncryptedVaultViewModel(sessionManager: sessionManager))
        self.onCreated = onCreated
    }

    var body: some View {
        VStack(spacing: LibraVaultSpacing.xl) {
            switch viewModel.step {
            case .name:
                nameStep
            case .pin:
                pinStep
            case .confirmPin:
                confirmPinStep
            case .recoveryKey:
                recoveryKeyStep
            }
        }
        .padding(LibraVaultSpacing.lg)
        .navigationTitle("New Vault")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(viewModel.step == .recoveryKey)
    }

    private var nameStep: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.lg) {
            Text("Name this vault")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onBackground)
            TextField("Vault name", text: $viewModel.displayName)
                .textFieldStyle(.roundedBorder)
            errorText
            Spacer()
            Button("Next") { viewModel.proceedFromName() }
                .buttonStyle(.borderedProminent)
                .tint(LibraVaultColor.primary)
                .frame(maxWidth: .infinity)
        }
    }

    private var pinStep: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.lg) {
            Text("Choose a PIN")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onBackground)
            Text("At least \(viewModel.minCredentialLength) characters. This unlocks \"\(viewModel.displayName)\" going forward.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            PinField(label: "PIN", text: $viewModel.pin)
            errorText
            Spacer()
            Button("Next") { viewModel.proceedFromPin() }
                .buttonStyle(.borderedProminent)
                .tint(LibraVaultColor.primary)
                .frame(maxWidth: .infinity)
                .disabled(viewModel.pin.isEmpty)
        }
    }

    private var confirmPinStep: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.lg) {
            Text("Confirm your PIN")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onBackground)
            PinField(label: "Confirm PIN", text: $viewModel.confirmPin)
            errorText
            Spacer()
            if viewModel.isCreating {
                ProgressView()
                    .frame(maxWidth: .infinity)
            } else {
                Button("Create Vault") {
                    Task { await viewModel.proceedFromConfirmPin() }
                }
                .buttonStyle(.borderedProminent)
                .tint(LibraVaultColor.primary)
                .frame(maxWidth: .infinity)
                .disabled(viewModel.confirmPin.isEmpty)
            }
        }
    }

    private var recoveryKeyStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LibraVaultSpacing.lg) {
                Text("Save your recovery key")
                    .font(LibraVaultTypography.headlineSmall)
                    .foregroundStyle(LibraVaultColor.onBackground)
                Text("This is the only time this key is shown. If you forget your PIN, or this device loses its secure hardware, this key is the only way back into this vault.")
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)

                if let recoveryKeyDisplay = viewModel.recoveryKeyDisplay {
                    VStack(spacing: LibraVaultSpacing.md) {
                        if let qrImage = RecoveryKeyQRCode.image(for: recoveryKeyDisplay) {
                            qrImage
                                .interpolation(.none)
                                .resizable()
                                .frame(width: 180, height: 180)
                        }
                        Text(recoveryKeyDisplay)
                            .font(.system(.body, design: .monospaced))
                            .multilineTextAlignment(.center)
                            .textSelection(.enabled)
                            .padding(LibraVaultSpacing.md)
                            .frame(maxWidth: .infinity)
                            .background(LibraVaultColor.surfaceVariant, in: RoundedRectangle(cornerRadius: LibraVaultRadius.card))
                    }
                    .frame(maxWidth: .infinity)
                }

                Toggle(isOn: $viewModel.hasConfirmedSaved) {
                    Text("I've saved this recovery key somewhere safe")
                }
                .tint(LibraVaultColor.primary)

                Button("Done") {
                    if let id = viewModel.finish() {
                        // onCreated first (sets activeVaultId on the parent
                        // List, whose own navigationDestination(isPresented:)
                        // owns the push), then dismiss this wizard off the
                        // stack — net effect: List -> Contents, not
                        // List -> Create -> Contents, matching Android's
                        // popUpTo(VaultList.route).
                        onCreated(id)
                        dismiss()
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(LibraVaultColor.primary)
                .frame(maxWidth: .infinity)
                .disabled(!viewModel.hasConfirmedSaved)
            }
            .padding(LibraVaultSpacing.lg)
        }
        // Unconditional, independent of #204 — see SecureScreenModifier's doc comment.
        .secureVaultScreen()
    }

    @ViewBuilder
    private var errorText: some View {
        if let errorMessage = viewModel.errorMessage {
            Text(errorMessage)
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(.red)
        }
    }
}
