import Foundation

/// Outcome of an unlock attempt. Deliberately does not distinguish "wrong
/// PIN" from "tampered data" — same reasoning as `VaultCryptoError
/// .authenticationFailed`'s own doc comment: an oracle that tells them apart
/// is itself a side channel. Mirrors Android's `UnlockOutcome` sealed class.
enum UnlockOutcome: Equatable {
    /// The `VaultStore` is now unlocked.
    case success
    /// Wrong PIN/recovery key, or the persisted key material was tampered with.
    case wrongCredential
    /// Too many recent failures — try again in `remainingDelayMillis`.
    case throttled(remainingDelayMillis: Int64)
    /// The Secure Enclave key is gone — PIN unlock is unavailable on this
    /// device; the caller must fall back to `VaultStore.unlockWithRecoveryKey`.
    /// Precisely the scenario the recovery key exists to rescue.
    case keystoreKeyLost
}

/// Errors `VaultStore` throws, beyond what `VaultCrypto`/`HardwareKeyWrap`
/// already define. Mirrors Android's per-case exception classes
/// (`VaultAlreadyExistsException`, `VaultLockedException`, etc.) as one enum,
/// matching the style already established by `VaultCryptoError`/
/// `HardwareKeyWrapError` in this codebase.
enum VaultStoreError: Error, Equatable {
    /// Not enough free space to import a file of the declared size — checked
    /// before writing anything.
    case insufficientStorage(requiredBytes: Int64, availableBytes: Int64)
    /// `create` called on a directory that already holds a vault.
    case vaultAlreadyExists
    /// A call requiring an unlocked vault was made while locked.
    case vaultLocked
    /// `setCoverArt`/`addHighlight`/`removeHighlight`/etc. called with a
    /// `fileId` that isn't in the manifest.
    case entryNotFound(fileId: Data)
    /// Sanity cap on cover art size — cheap insurance against a caller
    /// accidentally handing this an unprocessed, multi-hundred-MB embedded
    /// image (cover art is meant to already be a small thumbnail, run
    /// through `CoverArtCache`'s downsampling before it reaches here).
    case coverArtTooLarge(sizeBytes: Int, maxBytes: Int)
    /// An internal file operation (finalizing an imported file/cover art)
    /// failed unexpectedly — mirrors Android's `check()` failures, which
    /// throw a catchable `IllegalStateException` for the same conditions.
    case internalError(String)
}

extension VaultStoreError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .insufficientStorage(let required, let available):
            return "Not enough free space: need \(required) bytes, have \(available) available"
        case .vaultAlreadyExists:
            return "A vault already exists at this location"
        case .vaultLocked:
            return "Vault is locked"
        case .entryNotFound(let fileId):
            return "No manifest entry for fileId \(fileId.map { String(format: "%02x", $0) }.joined())"
        case .coverArtTooLarge(let size, let max):
            return "Cover art is \(size) bytes, exceeding the \(max) byte cap"
        case .internalError(let message):
            return message
        }
    }
}

/// On-disk filename for a content fileId — an opaque hex string, never the
/// real filename (never the title/author). Mirrors Android's private
/// `ByteArray.toHexForFileName()` (`VaultStore.kt`) — deliberately its own
/// small helper rather than reusing `VaultManifest.swift`'s equivalent,
/// matching that file's own choice not to share one either (Android doesn't
/// share this between `VaultStore.kt` and `VaultManifest.kt` either).
private extension Data {
    var hexEncodedFileName: String {
        map { String(format: "%02x", $0) }.joined()
    }
}

/// Vault lifecycle: create, unlock (PIN or recovery key), lock, import,
/// list/read manifest entries, highlights/bookmarks/cover-art. Swift port of
/// Android's `VaultStore.kt`.
///
/// One instance per vault directory, **not thread-safe** — matches
/// `VaultFileReader`'s own constraint, since this class holds a single
/// in-memory VMK and delegates content reads to that type. Callers are
/// expected to own one `VaultStore` per open vault behind a single-writer
/// boundary — `VaultSessionManager`'s actor isolation, not this type itself.
///
/// `vaultDir`/`keyWrapFactory` are constructor parameters, not resolved from
/// app-global state directly, specifically so this class is unit-testable
/// against a temporary directory and a fake `HardwareKeyWrapFactory` — the
/// lesson Android's `core.licensing.ProStateManager` (hardcodes real Keystore
/// access, untestable as a result) exists not to repeat here either.
final class VaultStore {

    /// 8 MiB — generous for a downsampled thumbnail (`CoverArtCache` caps
    /// the long edge and JPEG-compresses, so a real cover is normally tens
    /// of KB), tight enough to catch a caller accidentally passing an
    /// unprocessed embedded image.
    static let maxCoverArtBytes = 8 * 1024 * 1024

    private let vaultDir: URL
    private let keystoreKeyAlias: String
    private let keyWrapFactory: HardwareKeyWrapFactory
    private let nowEpochMillis: () -> Int64
    private let usableSpaceBytes: () -> Int64
    private let newFileIdGenerator: () throws -> Data

    private var vmk: Data?

    var isUnlocked: Bool { vmk != nil }
    func exists() -> Bool { VaultConfig.exists(vaultDir: vaultDir) }

    /// - Parameters:
    ///   - nowEpochMillis: injectable clock, for deterministic throttle tests.
    ///   - usableSpaceBytes: injectable free-space estimator, defaulting to
    ///     the real filesystem — overridable in tests to exercise
    ///     `.insufficientStorage` without actually filling a disk.
    ///   - newFileId: injectable fileId generator, defaulting to real
    ///     randomness — overridable in tests to force a predictable
    ///     collision, mirroring Android's injectable `random: SecureRandom`
    ///     constructor parameter (used by its "failed rename during import"
    ///     regression test).
    init(
        vaultDir: URL,
        keystoreKeyAlias: String,
        keyWrapFactory: HardwareKeyWrapFactory,
        nowEpochMillis: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        usableSpaceBytes: (() -> Int64)? = nil,
        newFileId: (() throws -> Data)? = nil
    ) {
        self.vaultDir = vaultDir
        self.keystoreKeyAlias = keystoreKeyAlias
        self.keyWrapFactory = keyWrapFactory
        self.nowEpochMillis = nowEpochMillis
        self.usableSpaceBytes = usableSpaceBytes ?? { Self.defaultUsableSpaceBytes(vaultDir: vaultDir) }
        self.newFileIdGenerator = newFileId ?? { try SecureRandom.bytes(count: VaultFormat.fileIdSizeBytes) }
    }

    private static func defaultUsableSpaceBytes(vaultDir: URL) -> Int64 {
        // vaultDir may not exist yet (e.g. before create() has run) — fall
        // back to its parent, the same "closest existing ancestor" leniency
        // Android's File.usableSpace gets for free from the OS.
        for candidate in [vaultDir, vaultDir.deletingLastPathComponent()] {
            if let attrs = try? FileManager.default.attributesOfFileSystem(forPath: candidate.path),
               let free = (attrs[.systemFreeSize] as? NSNumber)?.int64Value {
                return free
            }
        }
        return Int64.max
    }

    /// Creates a brand-new vault and leaves it unlocked (the caller just set
    /// the PIN; there is no reason to make them re-enter it immediately).
    ///
    /// - Returns: the 256-bit recovery key — show it to the user exactly
    ///   once. This method does not persist it in recoverable form anywhere;
    ///   losing it before the user saves it means losing the vault's only
    ///   defense against a lost Secure Enclave key.
    /// - Throws: `VaultStoreError.vaultAlreadyExists` if `vaultDir` already
    ///   holds a vault. `HardwareKeyWrapError.secureEnclaveUnavailable` if
    ///   this device has no Secure Enclave — do not fall back to a
    ///   software-only key; the caller should require a stronger credential,
    ///   or refuse to create the vault, not silently proceed with weaker
    ///   protection than the UI promised.
    func create(pin: [UInt8], argon2Params: Argon2Params = .defaultParams) throws -> Data {
        if exists() { throw VaultStoreError.vaultAlreadyExists }
        try FileManager.default.createDirectory(at: vaultDir, withIntermediateDirectories: true)

        var newVault = try VaultKeyManager.create(pin: pin, argon2Params: argon2Params)
        do {
            let keyWrap = try keyWrapFactory.createNew(keyAlias: keystoreKeyAlias)
            let keystoreWrap = try keyWrap.wrap(newVault.material.wrappedVmkByKek.serialized)
            try VaultConfig.write(
                vaultDir: vaultDir,
                keystoreKeyAlias: keystoreKeyAlias,
                argon2Salt: newVault.material.argon2Salt,
                argon2Params: argon2Params,
                keystoreWrap: keystoreWrap,
                wrappedVmkByRecovery: newVault.material.wrappedVmkByRecovery
            )
            try VaultManifest.write(vaultDir: vaultDir, vmk: newVault.vmk, entries: [])
            vmk = newVault.vmk
            return newVault.recoveryKey
        } catch {
            // Scrub in place, not a copy — see NewVault.vmk's doc comment for
            // why `var vmkCopy = newVault.vmk; vmkCopy.secureZero()` would
            // silently zero the wrong buffer.
            newVault.vmk.secureZero()
            try? FileManager.default.removeItem(at: vaultDir)
            throw error
        }
    }

    func unlockWithPin(_ pin: [UInt8]) throws -> UnlockOutcome {
        let config = try VaultConfig.read(vaultDir: vaultDir)
        let throttleState = UnlockAttemptThrottleStore.read(vaultDir: vaultDir)
        let now = nowEpochMillis()

        let delay = UnlockAttemptThrottle.remainingDelayMillis(
            failedAttempts: throttleState.failedAttempts,
            lastAttemptEpochMillis: throttleState.lastAttemptEpochMillis,
            nowEpochMillis: now
        )
        if delay > 0 { return .throttled(remainingDelayMillis: delay) }

        let keyWrap: HardwareKeyWrap
        do {
            keyWrap = try keyWrapFactory.forExisting(keyAlias: config.keystoreKeyAlias)
        } catch HardwareKeyWrapError.keyLost {
            return .keystoreKeyLost
        }

        let outcome: UnlockOutcome
        do {
            let kekWrappedVmkBytes = try keyWrap.unwrap(config.keystoreWrap)
            let unlockedVmk = try VaultKeyManager.unlockWithPin(
                pin: pin,
                argon2Salt: config.argon2Salt,
                argon2Params: config.argon2Params,
                wrappedVmkByKek: WrappedKey(serialized: kekWrappedVmkBytes)
            )
            vmk = unlockedVmk
            outcome = .success
        } catch VaultCryptoError.authenticationFailed {
            outcome = .wrongCredential
        }

        let newFailedAttempts = outcome == .success ? 0 : throttleState.failedAttempts + 1
        try UnlockAttemptThrottleStore.write(
            vaultDir: vaultDir,
            state: UnlockThrottleState(failedAttempts: newFailedAttempts, lastAttemptEpochMillis: now)
        )
        return outcome
    }

    /// Deliberately independent of the Secure Enclave layer and the PIN
    /// throttle — must keep working even if both are broken, since that's
    /// the entire justification for this path existing.
    func unlockWithRecoveryKey(_ recoveryKey: Data) throws -> UnlockOutcome {
        let config = try VaultConfig.read(vaultDir: vaultDir)
        do {
            vmk = try VaultKeyManager.unlockWithRecoveryKey(recoveryKey: recoveryKey, wrappedVmkByRecovery: config.wrappedVmkByRecovery)
            return .success
        } catch VaultCryptoError.authenticationFailed {
            return .wrongCredential
        }
    }

    /// Zeroes the in-memory VMK and drops the reference. Idempotent.
    func lock() {
        vmk?.secureZero()
        vmk = nil
    }

    private func requireUnlocked() throws -> Data {
        guard let vmk else { throw VaultStoreError.vaultLocked }
        return vmk
    }

    func listEntries() throws -> [VaultManifestEntry] {
        try VaultManifest.read(vaultDir: vaultDir, vmk: try requireUnlocked())
    }

    /// Streams `input` into the vault as a new entry — never buffers the
    /// whole file, so importing a multi-hundred-MB audiobook doesn't require
    /// holding it all in RAM. `input` must already be open (matches
    /// `ChunkedVaultWriter.encrypt`'s own contract, propagated up) — this
    /// method does not open or close it, leaving stream lifecycle with the
    /// caller, same as the Kotlin original leaves it with `importFile`'s own
    /// caller.
    ///
    /// Crash-safety, in this specific order: encrypt to a temp file, rename
    /// it into place, and only THEN update the manifest — the same ordering
    /// Android's own review caught (see `VaultStore.kt`'s doc comment):
    /// updating the manifest first would let a crash between those two steps
    /// leave the manifest pointing at a file that doesn't exist yet. With
    /// rename-then-manifest, the worst case a crash can leave behind is an
    /// orphaned content file with no manifest entry — wasted space, never a
    /// broken reference.
    ///
    /// `coverArt`, if provided, must already be a small, processed
    /// thumbnail — see `setCoverArt`'s doc for why this class doesn't
    /// decode/downsample cover art itself.
    ///
    /// - Throws: `.insufficientStorage`, `.coverArtTooLarge`.
    @discardableResult
    func importFile(
        input: InputStream,
        declaredSize: Int64,
        title: String,
        author: String?,
        format: String,
        coverArt: Data? = nil
    ) throws -> VaultManifestEntry {
        let vmkNow = try requireUnlocked()
        if let coverArt, coverArt.count > Self.maxCoverArtBytes {
            throw VaultStoreError.coverArtTooLarge(sizeBytes: coverArt.count, maxBytes: Self.maxCoverArtBytes)
        }

        // A generous margin above the declared size, not just >=: chunking
        // overhead (one AEAD tag per 32 KiB chunk) and the manifest rewrite
        // both cost a little more than the raw content size.
        let required = declaredSize + declaredSize / 32 + Int64(coverArt?.count ?? 0) + 16 * 1024
        let available = usableSpaceBytes()
        guard available >= required else {
            throw VaultStoreError.insufficientStorage(requiredBytes: required, availableBytes: available)
        }

        let fileId = try newFileId()
        let tmpURL = vaultDir.appendingPathComponent("\(fileId.hexEncodedFileName).tmp")
        let finalURL = contentFile(fileId: fileId)
        let coverFileId = try coverArt.map { _ in try newFileId() }
        let coverTmpURL = coverFileId.map { vaultDir.appendingPathComponent("\($0.hexEncodedFileName).tmp") }
        let coverFinalURL = coverFileId.map { contentFile(fileId: $0) }

        do {
            try encryptStreamToFile(vmk: vmkNow, fileId: fileId, totalLength: declaredSize, input: input, outputURL: tmpURL)
            if let coverArt, let coverFileId, let coverTmpURL {
                try encryptDataToFile(vmk: vmkNow, fileId: coverFileId, data: coverArt, outputURL: coverTmpURL)
            }
        } catch {
            try? FileManager.default.removeItem(at: tmpURL)
            if let coverTmpURL { try? FileManager.default.removeItem(at: coverTmpURL) }
            throw error
        }

        do {
            try finalize(tmpURL: tmpURL, finalURL: finalURL, what: "imported file")
            if let coverTmpURL, let coverFinalURL {
                try finalize(tmpURL: coverTmpURL, finalURL: coverFinalURL, what: "cover art")
            }

            let entry = VaultManifestEntry(
                fileId: fileId,
                title: title,
                author: author,
                format: format,
                sizeBytes: declaredSize,
                addedAtEpochMillis: nowEpochMillis(),
                coverArtFileId: coverFileId
            )
            let updatedEntries = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow) + [entry]
            try VaultManifest.write(vaultDir: vaultDir, vmk: vmkNow, entries: updatedEntries) // atomic — see VaultManifest.write
            return entry
        } catch {
            try? FileManager.default.removeItem(at: finalURL)
            try? FileManager.default.removeItem(at: tmpURL) // still present if the rename itself is what failed
            if let coverFinalURL { try? FileManager.default.removeItem(at: coverFinalURL) }
            if let coverTmpURL { try? FileManager.default.removeItem(at: coverTmpURL) }
            throw error
        }
    }

    /// Sets or replaces `fileId`'s cover art after the fact (e.g. a later
    /// cover-art extraction pass, or a user-supplied cover).
    ///
    /// This does NOT decode, downsample, or compress `jpegBytes` itself —
    /// that logic in `CoverArtCache` is security-hardened (OOM defense
    /// against malicious/corrupt images, 0×0 header rejection, sample-size
    /// capping) and is not duplicated here. Callers must run cover bytes
    /// through that same hardened path first and hand this method only the
    /// final, small, already-processed thumbnail.
    ///
    /// - Throws: `.entryNotFound`, `.coverArtTooLarge`.
    func setCoverArt(fileId: Data, jpegBytes: Data) throws {
        let vmkNow = try requireUnlocked()
        if jpegBytes.count > Self.maxCoverArtBytes {
            throw VaultStoreError.coverArtTooLarge(sizeBytes: jpegBytes.count, maxBytes: Self.maxCoverArtBytes)
        }

        let entries = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow)
        guard let index = entries.firstIndex(where: { $0.fileId == fileId }) else {
            throw VaultStoreError.entryNotFound(fileId: fileId)
        }
        let previousCoverFileId = entries[index].coverArtFileId

        let newCoverFileId = try newFileId()
        let tmpURL = vaultDir.appendingPathComponent("\(newCoverFileId.hexEncodedFileName).tmp")
        let finalURL = contentFile(fileId: newCoverFileId)
        do {
            try encryptDataToFile(vmk: vmkNow, fileId: newCoverFileId, data: jpegBytes, outputURL: tmpURL)
            try finalize(tmpURL: tmpURL, finalURL: finalURL, what: "cover art")

            var updated = entries
            updated[index].coverArtFileId = newCoverFileId
            try VaultManifest.write(vaultDir: vaultDir, vmk: vmkNow, entries: updated)

            // Only remove the old cover file once the manifest points at the
            // new one — deleting it first would risk losing both if the
            // write above had failed instead.
            if let previousCoverFileId {
                try? FileManager.default.removeItem(at: contentFile(fileId: previousCoverFileId))
            }
        } catch {
            try? FileManager.default.removeItem(at: finalURL)
            try? FileManager.default.removeItem(at: tmpURL)
            throw error
        }
    }

    /// Decrypts and returns `fileId`'s cover art, or `nil` if it has none.
    /// - Throws: `.entryNotFound` if there's no manifest entry for `fileId`.
    func readCoverArt(fileId: Data) throws -> Data? {
        let vmkNow = try requireUnlocked()
        guard let entry = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow).first(where: { $0.fileId == fileId }) else {
            throw VaultStoreError.entryNotFound(fileId: fileId)
        }
        guard let coverFileId = entry.coverArtFileId else { return nil }

        let reader = try VaultFileReader(fileURL: contentFile(fileId: coverFileId), vmk: vmkNow, expectedFileId: coverFileId)
        defer { reader.close() }
        var result = Data(capacity: Int(reader.plainSize))
        var offset: Int64 = 0
        while offset < reader.plainSize {
            let chunk = try reader.readAt(offset: offset, length: VaultFormat.defaultChunkSize)
            if chunk.isEmpty { break }
            result.append(chunk)
            offset += Int64(chunk.count)
        }
        return result
    }

    /// Appends a new highlight to `fileId`'s manifest entry.
    /// - Throws: `.entryNotFound` if there's no manifest entry for `fileId`.
    @discardableResult
    func addHighlight(
        fileId: Data,
        positionRef: String,
        highlightedText: String,
        colorHex: String = "#FFE066",
        note: String? = nil
    ) throws -> VaultHighlight {
        let vmkNow = try requireUnlocked()
        var entries = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow)
        guard let index = entries.firstIndex(where: { $0.fileId == fileId }) else {
            throw VaultStoreError.entryNotFound(fileId: fileId)
        }

        let nextId = (entries[index].highlights.map(\.id).max() ?? 0) + 1
        let highlight = VaultHighlight(
            id: nextId, positionRef: positionRef, highlightedText: highlightedText,
            colorHex: colorHex, note: note, createdAtEpochMillis: nowEpochMillis()
        )
        entries[index].highlights.append(highlight)
        try VaultManifest.write(vaultDir: vaultDir, vmk: vmkNow, entries: entries)
        return highlight
    }

    /// Removes a highlight by id. A no-op if `highlightId` doesn't exist —
    /// deleting something already gone isn't an error.
    /// - Throws: `.entryNotFound` if there's no manifest entry for `fileId`.
    func removeHighlight(fileId: Data, highlightId: Int64) throws {
        let vmkNow = try requireUnlocked()
        var entries = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow)
        guard let index = entries.firstIndex(where: { $0.fileId == fileId }) else {
            throw VaultStoreError.entryNotFound(fileId: fileId)
        }
        entries[index].highlights.removeAll { $0.id == highlightId }
        try VaultManifest.write(vaultDir: vaultDir, vmk: vmkNow, entries: entries)
    }

    /// Appends a new bookmark to `fileId`'s manifest entry — same pattern as
    /// `addHighlight`.
    /// - Throws: `.entryNotFound` if there's no manifest entry for `fileId`.
    @discardableResult
    func addBookmark(fileId: Data, positionRef: String, label: String? = nil, note: String? = nil) throws -> VaultBookmark {
        let vmkNow = try requireUnlocked()
        var entries = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow)
        guard let index = entries.firstIndex(where: { $0.fileId == fileId }) else {
            throw VaultStoreError.entryNotFound(fileId: fileId)
        }

        let nextId = (entries[index].bookmarks.map(\.id).max() ?? 0) + 1
        let bookmark = VaultBookmark(id: nextId, positionRef: positionRef, label: label, note: note, createdAtEpochMillis: nowEpochMillis())
        entries[index].bookmarks.append(bookmark)
        try VaultManifest.write(vaultDir: vaultDir, vmk: vmkNow, entries: entries)
        return bookmark
    }

    /// Removes a bookmark by id. A no-op if `bookmarkId` doesn't exist.
    /// - Throws: `.entryNotFound` if there's no manifest entry for `fileId`.
    func removeBookmark(fileId: Data, bookmarkId: Int64) throws {
        let vmkNow = try requireUnlocked()
        var entries = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow)
        guard let index = entries.firstIndex(where: { $0.fileId == fileId }) else {
            throw VaultStoreError.entryNotFound(fileId: fileId)
        }
        entries[index].bookmarks.removeAll { $0.id == bookmarkId }
        try VaultManifest.write(vaultDir: vaultDir, vmk: vmkNow, entries: entries)
    }

    /// Replaces a bookmark's note (`nil` clears it). A no-op if `bookmarkId`
    /// doesn't exist.
    /// - Throws: `.entryNotFound` if there's no manifest entry for `fileId`.
    func updateBookmarkNote(fileId: Data, bookmarkId: Int64, note: String?) throws {
        let vmkNow = try requireUnlocked()
        var entries = try VaultManifest.read(vaultDir: vaultDir, vmk: vmkNow)
        guard let index = entries.firstIndex(where: { $0.fileId == fileId }) else {
            throw VaultStoreError.entryNotFound(fileId: fileId)
        }
        if let bIndex = entries[index].bookmarks.firstIndex(where: { $0.id == bookmarkId }) {
            entries[index].bookmarks[bIndex].note = note
        }
        try VaultManifest.write(vaultDir: vaultDir, vmk: vmkNow, entries: entries)
    }

    /// Opens a seekable decrypting reader for `fileId` — the primitive
    /// content-delivery adapters (PDF proxy, AVAudioEngine data source, etc.,
    /// none of which exist yet on iOS) will wrap.
    func openReader(fileId: Data) throws -> VaultFileReader {
        try VaultFileReader(fileURL: contentFile(fileId: fileId), vmk: try requireUnlocked(), expectedFileId: fileId)
    }

    /// On-disk path for a file's encrypted content — an opaque, hex-encoded
    /// id, never the real filename.
    func contentFile(fileId: Data) -> URL {
        vaultDir.appendingPathComponent(fileId.hexEncodedFileName)
    }

    /// A fresh file id, guaranteed not to collide with the reserved
    /// `VaultManifest.legacyManifestFileId` (astronomically unlikely on its
    /// own for real randomness, but this is cheap insurance and a clear
    /// place to assert the invariant).
    private func newFileId() throws -> Data {
        while true {
            let id = try newFileIdGenerator()
            if id != VaultManifest.legacyManifestFileId { return id }
        }
    }

    private func encryptStreamToFile(vmk: Data, fileId: Data, totalLength: Int64, input: InputStream, outputURL: URL) throws {
        guard FileManager.default.createFile(atPath: outputURL.path, contents: nil) else {
            throw VaultStoreError.internalError("Could not create temp file at \(outputURL.path)")
        }
        guard let output = OutputStream(url: outputURL, append: false) else {
            throw VaultStoreError.internalError("Could not open temp file for writing at \(outputURL.path)")
        }
        output.open()
        defer { output.close() }
        try ChunkedVaultWriter.encrypt(vmk: vmk, fileId: fileId, totalPlaintextLength: totalLength, input: input, output: output)
    }

    private func encryptDataToFile(vmk: Data, fileId: Data, data: Data, outputURL: URL) throws {
        let input = InputStream(data: data)
        input.open()
        defer { input.close() }
        try encryptStreamToFile(vmk: vmk, fileId: fileId, totalLength: Int64(data.count), input: input, outputURL: outputURL)
    }

    /// - Throws: `.internalError` if `tmpURL` can't be moved to `finalURL`
    ///   (e.g. `finalURL` is already occupied) — mirrors Android's
    ///   `check(tmp.renameTo(finalFile))`. Uses `moveItem`, not
    ///   `replaceItemAt`: a content file is only ever created fresh under a
    ///   new random fileId, never legitimately overwritten, so this should
    ///   fail loudly (like `File.renameTo` returning `false`) rather than
    ///   silently replacing something that shouldn't be there.
    private func finalize(tmpURL: URL, finalURL: URL, what: String) throws {
        do {
            try FileManager.default.moveItem(at: tmpURL, to: finalURL)
        } catch {
            throw VaultStoreError.internalError("Failed to finalize \(what): \(error.localizedDescription)")
        }
    }
}
