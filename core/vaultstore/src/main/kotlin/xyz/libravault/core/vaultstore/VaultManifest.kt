package xyz.libravault.core.vaultstore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.libravault.core.vaultcrypto.ChunkedVaultWriter
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultcrypto.VaultFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * One imported document's metadata (PRD §8.2 point 6: title/author/format
 * live in the encrypted manifest, not in plaintext Room — see implementation
 * plan §A.6, the plaintext-metadata leak this whole module exists to close).
 *
 * [fileId] is the 16-byte id under which the actual content is stored — see
 * [VaultStore.contentFile]. It is NOT a filesystem-visible name: on-disk
 * filenames are opaque (PRD §8.2 point 6), so this manifest is the only place
 * the mapping from a human-meaningful title to a file exists at all, and it's
 * as encrypted as the content itself.
 */
data class VaultManifestEntry(
    val fileId: ByteArray,
    val title: String,
    val author: String?,
    val format: String,
    val sizeBytes: Long,
    val addedAtEpochMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultManifestEntry &&
            fileId.contentEquals(other.fileId) && title == other.title && author == other.author &&
            format == other.format && sizeBytes == other.sizeBytes && addedAtEpochMillis == other.addedAtEpochMillis

    override fun hashCode(): Int = fileId.contentHashCode()
}

@Serializable
private data class VaultManifestEntryDto(
    val fileIdHex: String,
    val title: String,
    val author: String?,
    val format: String,
    val sizeBytes: Long,
    val addedAtEpochMillis: Long,
)

@Serializable
private data class VaultManifestDto(val entries: List<VaultManifestEntryDto> = emptyList())

/**
 * The manifest is stored as just another file in the vault, under a reserved
 * all-zero [MANIFEST_FILE_ID] — reusing [ChunkedVaultWriter]/[VaultFileReader]
 * directly rather than exposing new internal API from core:vaultcrypto for a
 * second encryption path. [VaultStore] must never hand out the all-zero id to
 * a real imported file (see [VaultStore.newFileId]).
 */
object VaultManifest {

    val MANIFEST_FILE_ID: ByteArray = ByteArray(VaultFormat.FILE_ID_SIZE_BYTES)
    private const val MANIFEST_FILE_NAME = "manifest.enc"
    private val json = Json { ignoreUnknownKeys = true }

    fun manifestPath(vaultDir: File): File = File(vaultDir, MANIFEST_FILE_NAME)

    fun write(vaultDir: File, vmk: ByteArray, entries: List<VaultManifestEntry>) {
        val dto = VaultManifestDto(
            entries.map {
                VaultManifestEntryDto(
                    fileIdHex = it.fileId.toHex(),
                    title = it.title,
                    author = it.author,
                    format = it.format,
                    sizeBytes = it.sizeBytes,
                    addedAtEpochMillis = it.addedAtEpochMillis,
                )
            },
        )
        val plainBytes = json.encodeToString(VaultManifestDto.serializer(), dto).toByteArray(Charsets.UTF_8)

        // Encrypt to a temp file, then atomically replace — a crash mid-write
        // must never leave a half-written (and therefore unreadable, per the
        // truncation defense in core:vaultcrypto) manifest behind.
        val tmp = File(vaultDir, "$MANIFEST_FILE_NAME.tmp")
        ChunkedVaultWriter.encrypt(
            vmk, MANIFEST_FILE_ID, plainBytes.size.toLong(),
            ByteArrayInputStream(plainBytes), tmp.outputStream(),
        )
        check(tmp.renameTo(manifestPath(vaultDir))) { "Failed to atomically replace manifest" }
    }

    /** Returns an empty list if no manifest exists yet (a brand-new vault). */
    fun read(vaultDir: File, vmk: ByteArray): List<VaultManifestEntry> {
        val file = manifestPath(vaultDir)
        if (!file.exists()) return emptyList()

        val plainBytes = VaultFileReader(file, vmk, MANIFEST_FILE_ID).use { reader ->
            val out = ByteArrayOutputStream()
            var offset = 0L
            while (offset < reader.plainSize) {
                val chunk = reader.readAt(offset, VaultFormat.DEFAULT_CHUNK_SIZE)
                if (chunk.isEmpty()) break
                out.write(chunk)
                offset += chunk.size
            }
            out.toByteArray()
        }
        val dto = json.decodeFromString(VaultManifestDto.serializer(), plainBytes.toString(Charsets.UTF_8))
        return dto.entries.map {
            VaultManifestEntry(
                fileId = it.fileIdHex.fromHex(),
                title = it.title,
                author = it.author,
                format = it.format,
                sizeBytes = it.sizeBytes,
                addedAtEpochMillis = it.addedAtEpochMillis,
            )
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun String.fromHex(): ByteArray = ByteArray(length / 2) { i ->
    ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
}
