import Foundation

/// Seekable, random-access decrypting reader over a chunked vault file (PRD §8.2).
///
/// This is the primitive Android's equivalent doc comment describes as making
/// the whole content-delivery architecture work: a PDF/audio data-source
/// adapter can be built as a thin wrapper around `readAt` - none of them need
/// the whole file decrypted up front. Those adapters live above this module
/// (in whatever iOS equivalent of core:vaultstore/feature ends up wrapping
/// it), not here; this type has zero UIKit/AVFoundation dependency, same as
/// the Kotlin original has zero Android dependency.
///
/// Even a zero-byte file gets exactly one (empty) authenticated chunk written
/// by `ChunkedVaultWriter` - deliberate: without it, an attacker could remove
/// *every* chunk from a file and there would be nothing left whose AEAD tag
/// could fail, defeating tamper detection entirely for that case.
///
/// Caches the most recently decrypted chunk, since both PDF page rendering and
/// audio playback read sequentially within a chunk far more often than they
/// jump across chunk boundaries.
///
/// **Not thread-safe.** Mutable single-chunk cache state plus one `FileHandle`
/// - open one instance per concurrent reader; do not share one instance across
/// simultaneously-active callers.
///
/// - Throws (from `init`): `.authenticationFailed` (wrong VMK, or the file was tampered with,
///   or a non-`nil` `expectedFileId` doesn't match the header's embedded id),
///   `.truncated` (the file is shorter than the header requires),
///   `.unsupportedFormatVersion`/`.unsupportedCipher` (the header names one this build doesn't understand),
///   `.malformedHeader` (a header field is structurally invalid, e.g. chunkSize <= 0).
final class VaultFileReader {

    private let fileHandle: FileHandle

    let fileId: Data
    let chunkSize: Int
    let plainSize: Int64
    private let formatVersion: UInt8
    private let cipherId: UInt8
    // `private(set)`, not `private` — external read access (write stays
    // internal-only) is what lets `VaultFileReaderTests.
    // testCloseZeroesFileContentKeyAndCachedChunk` actually observe that
    // `close()` zeroed these in place rather than a copy, the same
    // "otherwise no black-box way to tell zeroed-in-place from zeroed-a-copy"
    // reasoning `VaultStore.create`'s `onCreateFailureVmkForTesting` hook's
    // own doc comment gives for its identical problem.
    private(set) var fileContentKey: Data

    private var cachedChunkIndex: Int64 = -1
    private(set) var cachedChunk: Data?

    /// Counts actual chunk decryptions (cache misses) - lets tests assert the
    /// cache and lazy random-access behavior actually work as designed.
    private(set) var decryptCount: Int = 0

    private var chunkCount: Int64 {
        ChunkedVaultWriter.chunkCountFor(totalPlaintextLength: plainSize, chunkSize: chunkSize)
    }

    /// - Parameter expectedFileId: cross-checked against the fileId embedded
    ///   in the file's own header, closing "this is the wrong file's blob"
    ///   fast rather than surfacing it as an opaque authentication failure
    ///   three steps later on the first chunk read. Pass `nil` only when the
    ///   caller genuinely has no external fileId to check against — the one
    ///   caller that does this today is `VaultManifest.read` (#302), because
    ///   the manifest's fileId varies on every write (see that type's doc
    ///   comment) and is trusted from its own AEAD-authenticated header
    ///   instead. Every other caller must keep passing a concrete id: `nil`
    ///   is not "skip validation," it's "there is provably nothing else to
    ///   validate against."
    init(fileURL: URL, vmk: Data, expectedFileId: Data?) throws {
        precondition(
            expectedFileId == nil || expectedFileId!.count == VaultFormat.fileIdSizeBytes,
            "expectedFileId must be \(VaultFormat.fileIdSizeBytes) bytes"
        )

        let handle = try FileHandle(forReadingFrom: fileURL)

        do {
            let headerData = (try handle.read(upToCount: VaultFormat.headerSizeBytes)) ?? Data()
            guard headerData.count == VaultFormat.headerSizeBytes else {
                throw VaultCryptoError.truncated(
                    expectedBytes: Int64(VaultFormat.headerSizeBytes),
                    actualBytes: Int64(headerData.count)
                )
            }
            let header = [UInt8](headerData)

            let version = header[0]
            let parsedCipherId = header[1]
            let parsedFileId = Data(header[2..<(2 + VaultFormat.fileIdSizeBytes)])
            let parsedChunkSize = BigEndian.int32(header, at: 2 + VaultFormat.fileIdSizeBytes)
            let parsedTotalLength = BigEndian.int64(header, at: 2 + VaultFormat.fileIdSizeBytes + 4)

            guard version == VaultFormat.formatVersion else {
                throw VaultCryptoError.unsupportedFormatVersion(found: version)
            }
            guard parsedCipherId == VaultFormat.cipherAes256Gcm else {
                throw VaultCryptoError.unsupportedCipher(found: parsedCipherId)
            }
            // Not itself an AEAD failure - a fast, clear error for "this is the wrong
            // file's blob" rather than letting the caller discover it as an opaque
            // authentication failure three steps later on the first chunk read.
            // Skipped entirely when expectedFileId is nil - see the init's doc comment.
            if let expectedFileId, expectedFileId != parsedFileId {
                throw VaultCryptoError.authenticationFailed
            }
            // Structural validation BEFORE any arithmetic uses these values - a
            // corrupted chunkSize == 0 must fail cleanly, not crash on a division.
            guard parsedChunkSize > 0 && parsedChunkSize <= VaultFormat.maxReasonableChunkSize else {
                throw VaultCryptoError.malformedHeader("Invalid chunkSize in header: \(parsedChunkSize)")
            }
            guard parsedTotalLength >= 0 else {
                throw VaultCryptoError.malformedHeader("Negative totalPlaintextLength in header: \(parsedTotalLength)")
            }

            self.fileHandle = handle
            self.fileId = parsedFileId
            self.formatVersion = version
            self.cipherId = parsedCipherId
            self.chunkSize = Int(parsedChunkSize)
            self.plainSize = parsedTotalLength
            self.fileContentKey = deriveFileContentKey(vmk: vmk, fileId: parsedFileId)

            // Eagerly authenticate chunk 0 at open time, even for a legitimately empty
            // (plainSize == 0) file. Deliberate, not incidental: readAt() below never
            // touches disk for an empty file (there is nothing to return), which means
            // without this eager check, an attacker could truncate a NON-empty file
            // down to just the header and rewrite totalPlaintextLength to 0 - "erasing"
            // its content as a silent, plausible-looking empty file instead of a loud
            // failure. Decrypting chunk 0 here closes that gap.
            _ = try decryptChunk(0)
        } catch {
            try? handle.close()
            throw error
        }
    }

    private func decryptChunk(_ index: Int64) throws -> Data {
        if let cached = cachedChunk, cachedChunkIndex == index {
            return cached
        }

        let isFinal = index == chunkCount - 1
        let plainLenOfChunk = isFinal ? (plainSize - index * Int64(chunkSize)) : Int64(chunkSize)
        let storedLen = Int(plainLenOfChunk + Int64(VaultFormat.tagSizeBytes))
        let filePos = UInt64(VaultFormat.headerSizeBytes) + UInt64(index) * UInt64(chunkSize + VaultFormat.tagSizeBytes)

        try fileHandle.seek(toOffset: filePos)
        var ciphertext = Data(capacity: storedLen)
        while ciphertext.count < storedLen {
            guard let chunk = try fileHandle.read(upToCount: storedLen - ciphertext.count), !chunk.isEmpty else {
                throw VaultCryptoError.truncated(expectedBytes: Int64(storedLen), actualBytes: Int64(ciphertext.count))
            }
            ciphertext.append(chunk)
        }

        let aad = VaultFormat.chunkAad(
            formatVersion: formatVersion,
            cipherId: cipherId,
            fileId: fileId,
            totalPlaintextLength: plainSize,
            chunkSize: Int32(chunkSize),
            chunkIndex: index,
            isFinalChunk: isFinal
        )
        let nonce = deriveNonce(fileContentKey: fileContentKey, chunkIndex: index)
        let plaintext = try AesGcmCipher.decrypt(key: fileContentKey, nonce: nonce, aad: aad, ciphertextWithTag: ciphertext)

        decryptCount += 1
        cachedChunkIndex = index
        cachedChunk = plaintext
        return plaintext
    }

    /// Reads up to `length` plaintext bytes starting at plaintext `offset`.
    /// Returns fewer bytes than requested only at end-of-file (mirrors the
    /// contract a data-source-style adapter expects).
    ///
    /// - Throws: `.authenticationFailed` (wrong key, or the file was tampered with),
    ///   `.truncated` (fewer bytes exist on disk than the header implies).
    func readAt(offset: Int64, length: Int) throws -> Data {
        guard offset >= 0, length >= 0 else { return Data() }
        guard offset < plainSize else { return Data() }

        let want = Int(min(Int64(length), plainSize - offset))
        var result = Data(capacity: want)
        var produced = 0
        var pos = offset
        while produced < want {
            let index = pos / Int64(chunkSize)
            let withinChunk = Int(pos % Int64(chunkSize))
            let chunk = try decryptChunk(index)
            let take = min(chunk.count - withinChunk, want - produced)
            if take <= 0 { break }
            result.append(chunk[(chunk.startIndex + withinChunk)..<(chunk.startIndex + withinChunk + take)])
            produced += take
            pos += Int64(take)
        }
        return result
    }

    /// Closes the file handle and scrubs this instance's own derived key
    /// material and last-decrypted plaintext chunk — a direct Swift port of
    /// the Kotlin fix from #525/#526 (PR #539's `VaultFileReader.close()`
    /// change), which found the same gap: closing only the file handle left
    /// a live, unscrubbed copy of `fileContentKey` sitting in memory for as
    /// long as this instance itself lived. Idempotent — re-zeroing already-
    /// zeroed `Data` is harmless, so a caller (or `deinit`) calling this
    /// twice is safe.
    func close() {
        try? fileHandle.close()
        fileContentKey.secureZero()
        cachedChunk?.secureZero()
        cachedChunk = nil
    }

    deinit {
        close()
    }
}
