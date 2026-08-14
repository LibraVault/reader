package xyz.libravault.core.vaultcrypto

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Encrypts a plaintext stream into the chunked vault format (PRD §8.2).
 *
 * Streams chunk-by-chunk — never buffers the whole file in memory — so import
 * of a multi-hundred-MB audiobook doesn't require holding it all in RAM. This
 * is a Phase 1 requirement carried over directly from Phase 2's design (core
 * :vaultstore's import pipeline streams from a SAF [InputStream]).
 *
 * The caller must know [totalPlaintextLength] up front (from SAF metadata —
 * see `core/storage/.../ScannedFile.sizeBytes`, already available today) rather
 * than this writer discovering it by reading to EOF. This is what lets the
 * length be written into the header before any chunk, and is what lets
 * [VaultFileReader] know a file's size without decrypting anything.
 */
object ChunkedVaultWriter {

    /**
     * @param vmk the Vault Master Key
     * @param fileId 16-byte unique id for this file within the vault
     * @param totalPlaintextLength exact byte count [input] will produce — validated at the end
     * @throws IllegalStateException if [input] produced a different number of bytes than declared
     */
    fun encrypt(
        vmk: ByteArray,
        fileId: ByteArray,
        totalPlaintextLength: Long,
        input: InputStream,
        output: OutputStream,
        chunkSize: Int = VaultFormat.DEFAULT_CHUNK_SIZE,
    ) {
        require(fileId.size == VaultFormat.FILE_ID_SIZE_BYTES) {
            "fileId must be ${VaultFormat.FILE_ID_SIZE_BYTES} bytes"
        }
        require(totalPlaintextLength >= 0) { "totalPlaintextLength must be >= 0" }
        require(chunkSize > 0) { "chunkSize must be positive" }

        writeHeader(output, fileId, chunkSize, totalPlaintextLength)

        val fileContentKey = deriveFileContentKey(vmk, fileId)
        val cipher = AesGcmCipher()
        val chunkCount = chunkCountFor(totalPlaintextLength, chunkSize)

        val buf = ByteArray(chunkSize)
        var written = 0L
        var chunkIndex = 0L
        while (chunkIndex < chunkCount) {
            val wantThisChunk = minOf(chunkSize.toLong(), totalPlaintextLength - written).toInt()
            readFully(input, buf, wantThisChunk)
            val isFinal = chunkIndex == chunkCount - 1

            val aad = VaultFormat.chunkAad(fileId, totalPlaintextLength, chunkSize, chunkIndex, isFinal)
            val nonce = deriveNonce(fileContentKey, chunkIndex)
            val ciphertext = cipher.encrypt(fileContentKey, nonce, aad, buf.copyOf(wantThisChunk))
            output.write(ciphertext)

            written += wantThisChunk
            chunkIndex++
        }

        check(written == totalPlaintextLength) {
            "Writer wrote $written bytes but declared totalPlaintextLength=$totalPlaintextLength " +
                "— the input stream did not match its declared size"
        }
    }

    /** Chunk count for a file, including the one empty final chunk written for a zero-length file
     * (so even an empty file has an authenticated chunk — see [VaultFileReader] doc comment). */
    internal fun chunkCountFor(totalPlaintextLength: Long, chunkSize: Int): Long =
        if (totalPlaintextLength == 0L) 1L
        else (totalPlaintextLength + chunkSize - 1) / chunkSize

    private fun writeHeader(output: OutputStream, fileId: ByteArray, chunkSize: Int, totalPlaintextLength: Long) {
        val header = ByteBuffer.allocate(VaultFormat.HEADER_SIZE_BYTES)
            .put(VaultFormat.FORMAT_VERSION)
            .put(VaultFormat.CIPHER_AES_256_GCM)
            .put(fileId)
            .putInt(chunkSize)
            .putLong(totalPlaintextLength)
            .array()
        output.write(header)
    }

    /** [InputStream.read] may return fewer bytes than requested even without EOF — loop until
     * [len] bytes are read or the stream is genuinely exhausted (which is itself an error here,
     * since the caller declared how many bytes to expect). */
    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            check(n >= 0) { "Input stream ended early: expected $len bytes at this chunk, got $offset" }
            offset += n
        }
    }
}
