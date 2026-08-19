import Foundation

/// 4-step wizard: name → PIN → confirm PIN → recovery key. Swift port of
/// Android's `CreateVaultViewModel`, adapted to a plain `ObservableObject`
/// (this codebase has no per-screen Hilt-`ViewModel` equivalent — see
/// `AppState`'s doc comment — but a global `AppState` is the wrong home for
/// open/lock lifecycle state, so this is a genuinely separate, screen-scoped
/// `ObservableObject`, constructed fresh per presentation of
/// `CreateEncryptedVaultView`).
@MainActor
final class CreateEncryptedVaultViewModel: ObservableObject {

    enum Step: Equatable {
        case name
        case pin
        case confirmPin
        case recoveryKey
    }

    /// Minimum credential length. Bumped to `hardwareUnavailableMinLength`
    /// after a `.hardwareUnavailable` result — see `HardwareKeyWrapError
    /// .secureEnclaveUnavailable`'s doc comment: without a Secure Enclave, a
    /// short PIN has no rate-limiting hardware behind it and is not
    /// defensible, so the retry must require a real passphrase instead of
    /// silently accepting the same short PIN again.
    static let defaultMinLength = 4
    static let hardwareUnavailableMinLength = 8

    @Published var step: Step = .name
    @Published var displayName = ""
    @Published var pin = ""
    @Published var confirmPin = ""
    @Published var errorMessage: String?
    @Published var isCreating = false
    @Published var recoveryKeyDisplay: String?
    @Published var hasConfirmedSaved = false
    @Published private(set) var createdVaultId: String?
    @Published private(set) var minCredentialLength = CreateEncryptedVaultViewModel.defaultMinLength

    private let sessionManager: VaultSessionManager

    init(sessionManager: VaultSessionManager) {
        self.sessionManager = sessionManager
    }

    func proceedFromName() {
        let trimmed = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            errorMessage = "Enter a name for this vault."
            return
        }
        displayName = trimmed
        errorMessage = nil
        step = .pin
    }

    func proceedFromPin() {
        guard pin.count >= minCredentialLength else {
            errorMessage = "Must be at least \(minCredentialLength) characters."
            return
        }
        errorMessage = nil
        step = .confirmPin
    }

    /// Validates the confirmation matches, then creates the vault. On
    /// success, advances to `.recoveryKey`. On `.hardwareUnavailable`, steps
    /// back to `.pin` with a bumped `minCredentialLength` rather than
    /// retrying silently with the same short PIN.
    func proceedFromConfirmPin() async {
        guard confirmPin == pin else {
            errorMessage = "PINs don't match."
            confirmPin = ""
            return
        }
        errorMessage = nil
        isCreating = true
        defer { isCreating = false }

        var pinBytes = Array(pin.utf8)
        defer { pinBytes.secureZero() }

        do {
            let result = try await sessionManager.createVault(displayName: displayName, pin: pinBytes)
            switch result {
            case .success(let id, let recoveryKey):
                var recoveryKeyCopy = recoveryKey
                createdVaultId = id
                recoveryKeyDisplay = RecoveryKeyFormat.toDisplayString(recoveryKeyCopy)
                recoveryKeyCopy.secureZero()
                pin = ""
                confirmPin = ""
                step = .recoveryKey
            case .hardwareUnavailable:
                minCredentialLength = Self.hardwareUnavailableMinLength
                errorMessage = "This device has no Secure Enclave. Please choose a longer passphrase (at least \(Self.hardwareUnavailableMinLength) characters)."
                pin = ""
                confirmPin = ""
                step = .pin
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Called only from the recovery-key step's "Done" button, gated in the
    /// view on `hasConfirmedSaved` — no path out of this wizard skips
    /// acknowledgment. Clears `recoveryKeyDisplay` so it can never be
    /// re-displayed by this view model instance again.
    func finish() -> String? {
        let id = createdVaultId
        recoveryKeyDisplay = nil
        return id
    }
}
