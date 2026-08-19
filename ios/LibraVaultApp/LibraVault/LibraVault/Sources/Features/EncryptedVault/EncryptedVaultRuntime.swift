import Foundation

/// The app's single production `VaultSessionManager` + `VaultForegroundLockObserver`
/// pair, held for the process's lifetime. #200/#201/#304 shipped the
/// `VaultStore`/`VaultSessionManager` library types, but nothing constructed
/// a real instance anywhere in the app — this is that missing wiring,
/// analogous to `LibraVaultApp`'s `@StateObject private var appState`.
///
/// `ObservableObject` purely so it can ride `.environmentObject` the same
/// way `AppState` does — nothing on it is actually `@Published`.
/// `sessionManager` is an `actor` that view models talk to directly
/// (`await runtime.sessionManager.foo()`), and `lockObserver` exists purely
/// for its side effect (locking every vault on `willResignActive`), never
/// read, so nothing here ever triggers a SwiftUI redraw on its own.
///
/// One instance is constructed in `LibraVaultApp.init` and handed down via
/// `.environmentObject(...)` — see that file — rather than a bare global
/// `static let`, so tests can substitute a scratch `rootDir` and a fake
/// `HardwareKeyWrapFactory` the same way `AppState`'s own dependency-injected
/// initializer does.
final class EncryptedVaultRuntime: ObservableObject {

    let sessionManager: VaultSessionManager
    private let lockObserver: VaultForegroundLockObserver?

    /// - Parameters:
    ///   - rootDir: where every vault's own subdirectory + the shared
    ///     `vaults.json` registry live. Defaults to a dedicated directory
    ///     inside the app's real Application Support folder, kept separate
    ///     from `Folder`'s unencrypted scanning roots and from
    ///     `ReadingDataPersistence`'s `UserDefaults` storage — Encrypted
    ///     Vault content must never share a directory with anything an
    ///     unencrypted code path also reads or writes.
    ///   - keyWrapFactory: defaults to the real Secure-Enclave-backed
    ///     factory. Tests override this with a fake to run off the Simulator
    ///     without hardware, matching every `VaultStore`/`VaultSessionManager`
    ///     test's own convention.
    ///   - observeForegroundLock: `false` in tests that construct a
    ///     `VaultSessionManager` of their own already covered by
    ///     `VaultForegroundLockObserverTests` — avoids registering a second,
    ///     redundant real `NotificationCenter` observer per test run.
    init(
        rootDir: URL = EncryptedVaultRuntime.defaultRootDir,
        keyWrapFactory: HardwareKeyWrapFactory = SecureEnclaveHardwareKeyWrapFactory(),
        observeForegroundLock: Bool = true
    ) {
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: keyWrapFactory)
        self.sessionManager = manager
        #if canImport(UIKit)
        self.lockObserver = observeForegroundLock ? VaultForegroundLockObserver(sessionManager: manager) : nil
        #else
        self.lockObserver = nil
        #endif
    }

    static let defaultRootDir: URL = {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return appSupport.appendingPathComponent("EncryptedVaults", isDirectory: true)
    }()
}
