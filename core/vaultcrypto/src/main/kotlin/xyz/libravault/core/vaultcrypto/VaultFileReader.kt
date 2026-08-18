package xyz.libravault.core.vaultcrypto

import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Seekable, random-access decrypting reader over a chunked vault file (PRD §8.2).
 *
 * This is the primitive that makes the whole content-delivery architecture work
 * (implementation plan §B/§D.0.RESULTS): a PDF proxy fd, a Media3 [DataSource][
 * androidx.media3.datasource.DataSource]-style adapter, or a [MediaDataSource][
 * android.media.MediaDataSource] can all be built as a thin wrapper around
 * [readAt] — none of them need the whole file decrypted up front. Those Android
 * adapters live in core:vaultstore/feature layers (Phase 3), not here; this
 * class has zero Android dependencies so it's fully JVM-testable.
 *
 * Even a zero-byte file gets exactly one (empty) authenticated chunk written by
 * [ChunkedVaultWriter] — this is deliberate: without it, an attacker could
 * remove *every* chunk from a file and there would be nothing left whose AEAD
 * tag could fail, defeating tamper detection entirely for that case.
 *
 * Caches the most recently decrypted chunk, since both PDF page rendering and
 * audio playback read sequentially within a chunk far more often than they
 * jump across chunk boundaries.
 *
 * **Not thread-safe.** One instance wraps one reusable [AesGcmCipher] (itself
 * not thread-safe — see its doc) plus mutable single-chunk cache state, so
 * concurrent calls to [readAt] on the same instance from multiple threads are
 * unsafe. Open one instance per concurrent reader (e.g. per proxy-fd callback
 * on its own dedicated thread — see implementation plan §D.0.RESULTS); do not
 * share one instance across simultaneously-active callbacks.
 *
 * @param expectedFileId the caller's already-known identity for this blob (e.g. from a
 * manifest entry) — cross-checked against the id embedded in the file's own header, so a
 * caller that opens the wrong blob by mistake gets a fast, clear error instead of an opaque
 * authentication failure three steps later. Pass `null` when the caller has no independent
 * way to know the identity ahead of time (currently only core:vaultstore's manifest, which
 * lives at a single fixed path with nothing else to cross-check against) — the id embedded
 * in the header is then trusted directly to derive the key. This weakens nothing: every header
 * field, fileId included, is bound into every chunk's AEAD tag ([VaultFormat.chunkAad]), so
 * a tampered fileId already fails to decrypt (wrong derived key) with or without this
 * pre-check; the check is purely a nicer failure mode, never the actual security boundary.
 * @throws VaultAuthenticationException wrong VMK, or the file was tampered with
 * @throws VaultTruncatedException the file is shorter than the header requires
 * @throws UnsupportedVaultFormatException the header's format version isn't one this build understands
 * @throws UnsupportedCipherException the header's cipher id isn't one this build understands
 * @throws MalformedVaultHeaderException a header field is structurally invalid (e.g. chunkSize <= 0)
 */
class VaultFileReader(
    file: java.io.File,
    private val vmk: ByteArray,
    expectedFileId: ByteArray?,
) : Closeable {

    private val raf = RandomAccessFile(file, "r")
    private val cipher = AesGcmCipher() // one instance, reused across every chunk — see AesGcmCipher's doc

    val fileId: ByteArray
    val chunkSize: Int
    val plainSize: Long
    private val formatVersion: Byte
    private val cipherId: Byte
    private val fileContentKey: ByteArray

    private var cachedChunkIndex = -1L
    private var cachedChunk: ByteArray? = null

    /** Counts actual chunk decryptions (cache misses) — lets tests assert the
     * cache and lazy random-access behavior actually work as designed, which is
     * the entire premise the Phase 0 spike validated on real hardware. */
    @Volatile var decryptCount: Int = 0
        private set

    init {
        val header = ByteArray(VaultFormat.HEADER_SIZE_BYTES)
        val headerRead = raf.read(header)
        if (headerRead < VaultFormat.HEADER_SIZE_BYTES) {
            raf.close()
            throw VaultTruncatedException(VaultFormat.HEADER_SIZE_BYTES.toLong(), maxOf(headerRead, 0).toLong())
        }
        val buf = ByteBuffer.wrap(header)
        val version = buf.get()
        val cipherId = buf.get()
        val parsedFileId = ByteArray(VaultFormat.FILE_ID_SIZE_BYTES).also { buf.get(it) }
        val parsedChunkSize = buf.int
        val parsedTotalLength = buf.long

        if (version != VaultFormat.FORMAT_VERSION) {
            raf.close()
            throw UnsupportedVaultFormatException(version)
        }
        if (cipherId != VaultFormat.CIPHER_AES_256_GCM) {
            raf.close()
            throw UnsupportedCipherException(cipherId)
        }
        require(expectedFileId == null || expectedFileId.size == VaultFormat.FILE_ID_SIZE_BYTES) {
            "expectedFileId must be ${VaultFormat.FILE_ID_SIZE_BYTES} bytes"
        }
        // null means the caller has no independent way to know the identity ahead of time —
        // trust whatever the header says (see the class doc's @param expectedFileId). Every
        // header field, including fileId, is still bound into every chunk's AEAD tag, so a
        // tampered fileId fails to decrypt regardless of this check ever running.
        if (expectedFileId != null && !expectedFileId.contentEquals(parsedFileId)) {
            raf.close()
            // Not itself an AEAD failure — a fast, clear error for "this is the wrong
            // file's blob" rather than letting the caller discover it as an opaque
            // authentication failure three steps later on the first chunk read.
            throw VaultAuthenticationException(
                IllegalStateException("fileId mismatch: expected != stored header fileId"),
            )
        }
        // Structural validation BEFORE any arithmetic uses these values. Found
        // during PR review: chunkSize == 0 previously reached an unguarded
        // division in chunkCountFor and crashed with an unhandled
        // ArithmeticException instead of failing the same clean way every other
        // corrupted-header case does.
        if (parsedChunkSize <= 0 || parsedChunkSize > VaultFormat.MAX_REASONABLE_CHUNK_SIZE) {
            raf.close()
            throw MalformedVaultHeaderException("Invalid chunkSize in header: $parsedChunkSize")
        }
        if (parsedTotalLength < 0) {
            raf.close()
            throw MalformedVaultHeaderException("Negative totalPlaintextLength in header: $parsedTotalLength")
        }

        this.fileId = parsedFileId
        this.formatVersion = version
        this.cipherId = cipherId
        this.chunkSize = parsedChunkSize
        this.plainSize = parsedTotalLength
        this.fileContentKey = deriveFileContentKey(vmk, parsedFileId)

        // Eagerly authenticate chunk 0 at open time, even for a legitimately empty
        // (plainSize == 0) file. This is deliberate, not incidental: readAt() below
        // never touches disk for an empty file (there is nothing to return), which
        // means without this eager check, an attacker could truncate a NON-empty
        // file down to just the header and rewrite totalPlaintextLength to 0 —
        // "erasing" its content as a silent, plausible-looking empty file instead
        // of a loud failure. Decrypting chunk 0 here closes that gap: it's what
        // makes the "every file has at least one authenticated chunk" guarantee
        // (see this class's doc comment) actually hold for every open, not just
        // for opens that happen to read content.
        decryptChunk(0)
    }

    private val chunkCount: Long get() = ChunkedVaultWriter.chunkCountFor(plainSize, chunkSize)

    private fun decryptChunk(index: Long): ByteArray {
        cachedChunk?.let { if (cachedChunkIndex == index) return it }

        val isFinal = index == chunkCount - 1
        val plainLenOfChunk = if (isFinal) (plainSize - index * chunkSize) else chunkSize.toLong()
        val storedLen = plainLenOfChunk + VaultFormat.TAG_SIZE_BYTES
        val filePos = VaultFormat.HEADER_SIZE_BYTES + index * (chunkSize + VaultFormat.TAG_SIZE_BYTES)

        val ciphertext = ByteArray(storedLen.toInt())
        raf.seek(filePos)
        var offset = 0
        while (offset < ciphertext.size) {
            val n = raf.read(ciphertext, offset, ciphertext.size - offset)
            if (n < 0) throw VaultTruncatedException(ciphertext.size.toLong(), offset.toLong())
            offset += n
        }

        val aad = VaultFormat.chunkAad(formatVersion, cipherId, fileId, plainSize, chunkSize, index, isFinal)
        val nonce = deriveNonce(fileContentKey, index)
        val plaintext = cipher.decrypt(fileContentKey, nonce, aad, ciphertext)

        decryptCount++
        cachedChunkIndex = index
        cachedChunk = plaintext
        return plaintext
    }

    /**
     * Reads up to [length] plaintext bytes starting at plaintext [offset].
     * Returns fewer bytes than requested only at end-of-file (mirrors the
     * contract callers like a [ProxyFileDescriptorCallback][
     * android.os.ProxyFileDescriptorCallback] or [MediaDataSource][
     * android.media.MediaDataSource] expect).
     *
     * @throws VaultAuthenticationException wrong key, or the file was tampered with
     * @throws VaultTruncatedException fewer bytes exist on disk than the header implies
     */
    fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0) return ByteArray(0)
        if (offset >= plainSize) return ByteArray(0)

        val want = minOf(length.toLong(), plainSize - offset).toInt()
        val result = ByteArray(want)
        var produced = 0
        var pos = offset
        while (produced < want) {
            val index = pos / chunkSize
            val withinChunk = (pos % chunkSize).toInt()
            val chunk = decryptChunk(index)
            val take = minOf(chunk.size - withinChunk, want - produced)
            if (take <= 0) break
            System.arraycopy(chunk, withinChunk, result, produced, take)
            produced += take
            pos += take
        }
        return if (produced == want) result else result.copyOf(produced)
    }

    override fun close() = raf.close()
}
