import SwiftUI

/// Shared PIN/passphrase field — label + show/hide toggle — used by both
/// `CreateEncryptedVaultView` and `UnlockEncryptedVaultView`. Extracted into
/// its own type rather than duplicated across both, matching Android's
/// `PinField.kt` (itself extracted after an accessibility fix had to be
/// applied twice while it was duplicated).
struct PinField: View {
    let label: String
    @Binding var text: String
    @State private var isRevealed = false

    var body: some View {
        HStack {
            Group {
                if isRevealed {
                    TextField(label, text: $text)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } else {
                    SecureField(label, text: $text)
                }
            }
            .keyboardType(.asciiCapable)

            Button {
                isRevealed.toggle()
            } label: {
                Image(systemName: isRevealed ? "eye.slash" : "eye")
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }
            .accessibilityLabel(isRevealed ? "Hide \(label.lowercased())" : "Show \(label.lowercased())")
        }
        .padding(LibraVaultSpacing.md)
        .background(LibraVaultColor.surfaceVariant, in: RoundedRectangle(cornerRadius: LibraVaultRadius.card))
    }
}
