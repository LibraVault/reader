import Foundation

/// Encrypts a plaintext stream into the chunked vault format (PRD §8.2).
///
/// Streams chunk-by-chunk - never buffers the whole file in memory - so import
/// of a multi-hundred-MB audiobook doesn't require holding it all in RAM, same
/// requirement as Android's `ChunkedVaultWriter.kt`.
///
/// The caller must know `totalPlaintextLength` up front rather than this
/// writer discovering it by reading to EOF - this is what lets the length be
/// written into the header before any chunk, and is what lets
/// `VaultFileReader` know a file's size without decrypting anything.
///
/// `input`/`output` must already be open (`InputStream.open()` /
/// `OutputStream.open()`) - this function does not open or close either,
/// mirroring the Kotlin original, which leaves stream lifecycle to the caller.
enum ChunkedVaultWriter {

    /// - Parameters:
    ///   - vmk: the Vault Master Key
    ///   - fileId: 16-byte unique id for this file within the vault
    ///   - totalPlaintextLength: exact byte count `input` will produce - validated at the end
    /// - Precondition: traps if `fileId`/`totalPlaintextLength`/`chunkSize` is structurally invalid -
    ///   these mirror Kotlin's `require()` calls, and are genuine caller-argument bugs.
    /// - Throws: `VaultCryptoError.ioError` if `input` produces fewer bytes than `totalPlaintextLength`
    ///   declared, or a genuine stream I/O error occurs. This is a *runtime* condition (the file backing
    ///   `input` can legitimately change between the caller stat-ing it and this reading it - an iCloud
    ///   placeholder still downloading, a removable volume unmounted mid-import), not a caller bug, so
    ///   unlike the argument-validation preconditions above it must stay catchable - it mirrors Kotlin's
    ///   `check()` calls, which throw a catchable `IllegalStateException`, not `precondition`'s
    ///   unconditional (and, critically, un-catchable) process trap.
    static func encrypt(
        vmk: Data,
        fileId: Data,
        totalPlaintextLength: Int64,
        input: InputStream,
        output: OutputStream,
        chunkSize: Int = VaultFormat.defaultChunkSize
    ) throws {
        precondition(fileId.count == VaultFormat.fileIdSizeBytes, "fileId must be \(VaultFormat.fileIdSizeBytes) bytes")
        precondition(totalPlaintextLength >= 0, "totalPlaintextLength must be >= 0")
        precondition(
            chunkSize >= 1 && chunkSize <= VaultFormat.maxReasonableChunkSize,
            "chunkSize must be in 1...\(VaultFormat.maxReasonableChunkSize), got \(chunkSize)"
        )

        try writeHeader(to: output, fileId: fileId, chunkSize: chunkSize, totalPlaintextLength: totalPlaintextLength)

        let fileContentKey = deriveFileContentKey(vmk: vmk, fileId: fileId)
        let chunkCount = chunkCountFor(totalPlaintextLength: totalPlaintextLength, chunkSize: chunkSize)

        var buf = [UInt8](repeating: 0, count: chunkSize)
        var written: Int64 = 0
        var chunkIndex: Int64 = 0
        while chunkIndex < chunkCount {
            let wantThisChunk = Int(min(Int64(chunkSize), totalPlaintextLength - written))
            try readFully(input, buffer: &buf, length: wantThisChunk)
            let isFinal = chunkIndex == chunkCount - 1

            let aad = VaultFormat.chunkAad(
                formatVersion: VaultFormat.formatVersion,
                cipherId: VaultFormat.cipherAes256Gcm,
                fileId: fileId,
                totalPlaintextLength: totalPlaintextLength,
                chunkSize: Int32(chunkSize),
                chunkIndex: chunkIndex,
                isFinalChunk: isFinal
            )
            let nonce = deriveNonce(fileContentKey: fileContentKey, chunkIndex: chunkIndex)
            let plaintext = Data(buf[0..<wantThisChunk])
            let ciphertext = try AesGcmCipher.encrypt(key: fileContentKey, nonce: nonce, aad: aad, plaintext: plaintext)
            try writeFully(output, data: ciphertext)

            written += Int64(wantThisChunk)
            chunkIndex += 1
        }

        guard written == totalPlaintextLength else {
            throw VaultCryptoError.ioError(
                "Writer wrote \(written) bytes but declared totalPlaintextLength=\(totalPlaintextLength)"
                    + " - the input stream did not match its declared size"
            )
        }
    }

    /// Chunk count for a file, including the one empty final chunk written for a zero-length file
    /// (so even an empty file has an authenticated chunk - see `VaultFileReader`'s doc comment).
    static func chunkCountFor(totalPlaintextLength: Int64, chunkSize: Int) -> Int64 {
        if totalPlaintextLength == 0 { return 1 }
        return (totalPlaintextLength + Int64(chunkSize) - 1) / Int64(chunkSize)
    }

    private static func writeHeader(to output: OutputStream, fileId: Data, chunkSize: Int, totalPlaintextLength: Int64) throws {
        var header = Data(capacity: VaultFormat.headerSizeBytes)
        header.append(VaultFormat.formatVersion)
        header.append(VaultFormat.cipherAes256Gcm)
        header.append(fileId)
        header.append(contentsOf: BigEndian.bytes(ofUInt32: UInt32(chunkSize)))
        header.append(contentsOf: BigEndian.bytes(ofInt64: totalPlaintextLength))
        try writeFully(output, data: header)
    }

    /// `InputStream.read` may return fewer bytes than requested even without EOF - loop until
    /// `length` bytes are read or the stream is genuinely exhausted. An early EOF here means the
    /// stream produced fewer bytes than the caller's declared `totalPlaintextLength` promised - a
    /// runtime I/O-consistency condition (see `encrypt`'s doc comment), not a caller-argument bug,
    /// so it throws rather than trapping.
    ///
    /// Note Foundation's `InputStream.read` EOF signal (`0`) differs from `java.io.InputStream`'s
    /// (`-1`) - `0` means EOF here, a negative return means a genuine I/O error.
    private static func readFully(_ input: InputStream, buffer: inout [UInt8], length: Int) throws {
        var offset = 0
        while offset < length {
            let n = buffer.withUnsafeMutableBufferPointer { ptr -> Int in
                input.read(ptr.baseAddress! + offset, maxLength: length - offset)
            }
            if n < 0 {
                throw VaultCryptoError.ioError(input.streamError?.localizedDescription ?? "Input stream read error")
            }
            guard n > 0 else {
                throw VaultCryptoError.ioError("Input stream ended early: expected \(length) bytes at this chunk, got \(offset)")
            }
            offset += n
        }
    }

    /// Writes `data` via `data.withUnsafeBytes` directly rather than copying it into a fresh
    /// `[UInt8]` first - this runs once per ~32 KiB chunk on the streaming-encrypt hot path, so for
    /// a multi-hundred-MB import that copy would otherwise repeat thousands of times for no benefit.
    private static func writeFully(_ output: OutputStream, data: Data) throws {
        guard !data.isEmpty else { return }
        try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            guard let base = raw.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            var offset = 0
            while offset < data.count {
                let n = output.write(base + offset, maxLength: data.count - offset)
                guard n > 0 else {
                    throw VaultCryptoError.ioError(output.streamError?.localizedDescription ?? "Output stream write error")
                }
                offset += n
            }
        }
    }
}
