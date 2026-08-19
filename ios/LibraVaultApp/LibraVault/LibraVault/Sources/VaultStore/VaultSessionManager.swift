import Foundation

/// Outcome of `VaultSessionManager.createVault`. Mirrors Android's
/// `CreateVaultResult` sealed class.
enum CreateVaultResult: Equatable {
    /// `recoveryKey` — show this to the user exactly once, see `VaultStore.create`.
    case success(id: String, recoveryKey: Data)
    /// No Secure Enclave on this device — a 4-digit PIN isn't defensible
    /// here. The caller should require a longer passphrase instead of
    /// retrying with the same PIN, or refuse to create the vault.
    case hardwareUnavailable
}

/// App-wide entry point for Encrypted Vaults: owns the `VaultRegistry`
/// (which vaults exist) plus one `VaultStore` instance per vault the user
/// has touched this session (locked or unlocked). Swift port of Android's
/// `VaultSessionManager`.
///
/// An `actor`, not a class wrapping a manual lock: actor isolation gives the
/// same "single-writer boundary across every vault operation" guarantee
/// Android gets from wrapping every method in one shared `Mutex`, enforced
/// by the compiler rather than by convention — simpler than a per-vault lock
/// map, and cheap here: these are all user-tap-driven, one-at-a-time
/// operations, not a hot path, matching `VaultStore`'s own doc comment,
/// which expects exactly this kind of single-writer wrapper.
///
/// Deliberately does NOT itself observe app-lifecycle notifications — see
/// `VaultForegroundLockObserver`, a separate type specifically so this
/// actor's own tests never need to simulate `UIApplication` notifications to
/// exercise its actual vault logic.
actor VaultSessionManager {

    private let rootDir: URL
    private let keyWrapFactory: HardwareKeyWrapFactory
    private var stores: [String: VaultStore] = [:]

    init(rootDir: URL, keyWrapFactory: HardwareKeyWrapFactory) {
        self.rootDir = rootDir
        self.keyWrapFactory = keyWrapFactory
    }

    func listVaults() -> [VaultRegistryEntry] {
        VaultRegistry.list(baseDir: rootDir)
    }

    func isUnlocked(_ id: String) -> Bool {
        stores[id]?.isUnlocked ?? false
    }

    /// Creates a brand-new vault, registers it, and leaves it unlocked.
    func createVault(displayName: String, pin: [UInt8]) throws -> CreateVaultResult {
        let id = UUID().uuidString
        let store = storeFor(id: id)
        do {
            let recoveryKey = try store.create(pin: pin)
            try VaultRegistry.add(
                baseDir: rootDir,
                entry: VaultRegistryEntry(id: id, displayName: displayName, createdAtEpochMillis: Int64(Date().timeIntervalSince1970 * 1000))
            )
            return .success(id: id, recoveryKey: recoveryKey)
        } catch HardwareKeyWrapError.secureEnclaveUnavailable {
            // Nothing was registered yet, and VaultStore.create's own catch
            // block already deleted the half-created vault directory - just
            // drop the in-memory reference so a retry starts clean.
            stores.removeValue(forKey: id)
            return .hardwareUnavailable
        }
    }

    func unlockWithPin(id: String, pin: [UInt8]) throws -> UnlockOutcome {
        try storeFor(id: id).unlockWithPin(pin)
    }

    func unlockWithRecoveryKey(id: String, recoveryKey: Data) throws -> UnlockOutcome {
        try storeFor(id: id).unlockWithRecoveryKey(recoveryKey)
    }

    func lock(_ id: String) {
        stores[id]?.lock()
    }

    /// Locks every vault touched this session — called on every
    /// `willResignActive` transition by `VaultForegroundLockObserver`.
    func lockAll() {
        stores.values.forEach { $0.lock() }
    }

    /// The unlocked `VaultStore` for `id`, for callers that already checked
    /// `isUnlocked`.
    func requireUnlocked(_ id: String) -> VaultStore {
        guard let store = stores[id], store.isUnlocked else {
            preconditionFailure("Vault \(id) is not unlocked")
        }
        return store
    }

    private func storeFor(id: String) -> VaultStore {
        if let existing = stores[id] { return existing }
        let store = VaultStore(
            vaultDir: VaultRegistry.vaultDir(baseDir: rootDir, id: id),
            keystoreKeyAlias: keystoreAlias(for: id),
            keyWrapFactory: keyWrapFactory
        )
        stores[id] = store
        return store
    }

    private func keystoreAlias(for id: String) -> String { "libravault_vault_\(id)" }
}
