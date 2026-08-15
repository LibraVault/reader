package xyz.libravault.core.vaultstore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.libravault.core.vaultcrypto.ChunkedVaultWriter
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultcrypto.VaultFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom

/**
 * A user highlight/annotation on a vault document (implementation plan §A.6,
 * §D.0's Phase 4: the second of the two plaintext leaks the Phase 0 review
 * found — `HighlightEntity.highlightedText`/`.note` in plaintext Room —
 * closed by keeping vault highlights out of Room entirely, embedded in the
 * same encrypted manifest as the document's own metadata). Field shapes
 * mirror `core.database.entity.HighlightEntity` deliberately, so a future
 * Phase 5 UI layer can reuse the same rendering code for both Folder and
 * Vault highlights — only the storage backing differs.
 */
data class VaultHighlight(
    val id: Long,
    val positionRef: String, // CFI range for EPUB, "page:N:x1,y1,x2,y2" for PDF — same convention as HighlightEntity
    val highlightedText: String,
    val colorHex: String = "#FFE066",
    val note: String? = null,
    val createdAtEpochMillis: Long,
)

/**
 * A saved reading position on a vault document (implementation plan §D.0's
 * Phase 4 review: same "keep it out of plaintext Room" rationale as
 * [VaultHighlight] — `BookmarkEntity.label`/`.note` would otherwise leak a
 * user's own annotations for a file they deliberately encrypted). Field
 * shapes mirror `core.database.entity.BookmarkEntity` deliberately, same as
 * [VaultHighlight] does for `HighlightEntity`.
 */
data class VaultBookmark(
    val id: Long,
    val positionRef: String, // CFI/Locator JSON for EPUB, "page:N" for PDF, "ms:N" for audio — same convention as BookmarkEntity
    val label: String? = null,
    val note: String? = null,
    val createdAtEpochMillis: Long,
)

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
 *
 * [coverArtFileId], when present, names a second vault-internal file (stored
 * and encrypted exactly like [fileId]'s own content — see
 * [VaultStore.setCoverArt]) holding the cover image bytes. This module does
 * NOT decode/downsample/compress cover art itself — that logic in
 * `core.storage.CoverArtCache` is security-hardened (OOM defense, 0×0 header
 * rejection, sample-size capping; see `docs/threat-model.md`) and
 * deliberately not duplicated here. Callers must run cover bytes through
 * that same hardened path before handing them to [VaultStore.setCoverArt].
 */
data class VaultManifestEntry(
    val fileId: ByteArray,
    val title: String,
    val author: String?,
    val format: String,
    val sizeBytes: Long,
    val addedAtEpochMillis: Long,
    val coverArtFileId: ByteArray? = null,
    val highlights: List<VaultHighlight> = emptyList(),
    val bookmarks: List<VaultBookmark> = emptyList(),
) {
    override fun equals(other: Any?): Boolean =
        other is VaultManifestEntry &&
            fileId.contentEquals(other.fileId) && title == other.title && author == other.author &&
            format == other.format && sizeBytes == other.sizeBytes && addedAtEpochMillis == other.addedAtEpochMillis &&
            (coverArtFileId?.contentEquals(other.coverArtFileId) ?: (other.coverArtFileId == null)) &&
            highlights == other.highlights && bookmarks == other.bookmarks

    override fun hashCode(): Int = fileId.contentHashCode()
}

@Serializable
private data class VaultHighlightDto(
    val id: Long,
    val positionRef: String,
    val highlightedText: String,
    val colorHex: String,
    val note: String?,
    val createdAtEpochMillis: Long,
)

@Serializable
private data class VaultBookmarkDto(
    val id: Long,
    val positionRef: String,
    val label: String?,
    val note: String?,
    val createdAtEpochMillis: Long,
)

@Serializable
private data class VaultManifestEntryDto(
    val fileIdHex: String,
    val title: String,
    val author: String?,
    val format: String,
    val sizeBytes: Long,
    val addedAtEpochMillis: Long,
    val coverArtFileIdHex: String? = null,
    val highlights: List<VaultHighlightDto> = emptyList(),
    // Absent in manifests written before this field existed — kotlinx.serialization
    // falls back to the default (emptyList()) for a missing JSON key, same as
    // `highlights` already relies on for older vaults.
    val bookmarks: List<VaultBookmarkDto> = emptyList(),
)

@Serializable
private data class VaultManifestDto(val entries: List<VaultManifestEntryDto> = emptyList())

/**
 * The manifest is stored as just another file in the vault, at the fixed path
 * [MANIFEST_FILE_NAME] — reusing [ChunkedVaultWriter]/[VaultFileReader]
 * directly rather than exposing new internal API from core:vaultcrypto for a
 * second encryption path.
 *
 * **Security-critical — read before touching [write].** [write] runs on
 * every content mutation (add book, add/remove highlight, add/remove
 * bookmark — see [VaultStore]), so unlike a real imported file (encrypted
 * exactly once, under its own fresh fileId, for life), the manifest is
 * re-encrypted many times over a vault's life while its own key (derived
 * from the fileId it's encrypted under) and the vault's VMK (which never
 * rotates) would otherwise stay fixed. `AesGcmCipher.deriveNonce` is
 * deliberately deterministic in exactly those two things — safe ONLY under
 * the assumption that a given fileId is written exactly once. Reusing one
 * fixed fileId across many manifest rewrites breaks that assumption: it
 * would derive the identical (key, nonce) sequence for every single write
 * while encrypting DIFFERENT plaintext each time — catastrophic AES-GCM
 * nonce reuse, on the single most sensitive asset in the app (see
 * SECURITY.md's asset table). A previous version of this file did exactly
 * that, under the fixed [MANIFEST_FILE_ID].
 *
 * [write] now draws a **fresh random fileId on every call** instead — the
 * same one-fresh-id-per-encryption the rest of the vault already relies on
 * for real content (see [VaultStore.newFileId]). The fresh id is never
 * stored separately: [ChunkedVaultWriter] already embeds it unencrypted
 * (but AEAD-authenticated — see [VaultFormat.chunkAad]) in the blob's own
 * header, which is exactly what lets [read] open it back up with no
 * external bookkeeping, via [VaultFileReader]'s `expectedFileId = null`
 * mode. That also makes the fix self-migrating: a vault created before this
 * fix still has its manifest encrypted under the legacy all-zero id and
 * reads back unchanged; the very next [write] silently rotates it onto a
 * fresh one.
 *
 * [MANIFEST_FILE_ID] is kept only as a reserved sentinel so
 * [VaultStore.newFileId] keeps excluding it from real content/cover
 * fileIds — still necessary as long as a not-yet-migrated vault's manifest
 * might still be encrypted under it.
 */
object VaultManifest {

    /** Legacy sentinel: the single fixed fileId every manifest used to be
     * encrypted under, before nonce-reuse-across-writes was fixed (see the
     * class doc). [write] no longer encrypts under this — kept only so
     * [VaultStore.newFileId] continues to exclude it. */
    val MANIFEST_FILE_ID: ByteArray = ByteArray(VaultFormat.FILE_ID_SIZE_BYTES)
    private const val MANIFEST_FILE_NAME = "manifest.enc"
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun manifestPath(vaultDir: File): File = File(vaultDir, MANIFEST_FILE_NAME)

    fun write(vaultDir: File, vmk: ByteArray, entries: List<VaultManifestEntry>) {
        val dto = VaultManifestDto(
            entries.map { entry ->
                VaultManifestEntryDto(
                    fileIdHex = entry.fileId.toHex(),
                    title = entry.title,
                    author = entry.author,
                    format = entry.format,
                    sizeBytes = entry.sizeBytes,
                    addedAtEpochMillis = entry.addedAtEpochMillis,
                    coverArtFileIdHex = entry.coverArtFileId?.toHex(),
                    highlights = entry.highlights.map {
                        VaultHighlightDto(it.id, it.positionRef, it.highlightedText, it.colorHex, it.note, it.createdAtEpochMillis)
                    },
                    bookmarks = entry.bookmarks.map {
                        VaultBookmarkDto(it.id, it.positionRef, it.label, it.note, it.createdAtEpochMillis)
                    },
                )
            },
        )
        val plainBytes = json.encodeToString(VaultManifestDto.serializer(), dto).toByteArray(Charsets.UTF_8)

        // A fresh fileId every write — see the class doc: this is what stops the
        // manifest's many rewrites from reusing an AES-GCM nonce. Excludes the
        // legacy all-zero sentinel for the same cheap-insurance reason
        // VaultStore.newFileId() does.
        val fileId = ByteArray(VaultFormat.FILE_ID_SIZE_BYTES)
        do {
            random.nextBytes(fileId)
        } while (fileId.contentEquals(MANIFEST_FILE_ID))

        // Encrypt to a temp file, then atomically replace — a crash mid-write
        // must never leave a half-written (and therefore unreadable, per the
        // truncation defense in core:vaultcrypto) manifest behind.
        val tmp = File(vaultDir, "$MANIFEST_FILE_NAME.tmp")
        ChunkedVaultWriter.encrypt(
            vmk, fileId, plainBytes.size.toLong(),
            ByteArrayInputStream(plainBytes), tmp.outputStream(),
        )
        check(tmp.renameTo(manifestPath(vaultDir))) { "Failed to atomically replace manifest" }
    }

    /** Returns an empty list if no manifest exists yet (a brand-new vault). */
    fun read(vaultDir: File, vmk: ByteArray): List<VaultManifestEntry> {
        val file = manifestPath(vaultDir)
        if (!file.exists()) return emptyList()

        // expectedFileId = null: the manifest's fileId now varies per write (see the class
        // doc), so there's nothing external to cross-check against — trust whichever id is
        // embedded in this blob's own AEAD-authenticated header. Transparently handles both
        // a freshly-written manifest AND a legacy one still under the all-zero sentinel.
        val plainBytes = VaultFileReader(file, vmk, expectedFileId = null).use { reader ->
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
        return dto.entries.map { e ->
            VaultManifestEntry(
                fileId = e.fileIdHex.fromHex(),
                title = e.title,
                author = e.author,
                format = e.format,
                sizeBytes = e.sizeBytes,
                addedAtEpochMillis = e.addedAtEpochMillis,
                coverArtFileId = e.coverArtFileIdHex?.fromHex(),
                highlights = e.highlights.map {
                    VaultHighlight(it.id, it.positionRef, it.highlightedText, it.colorHex, it.note, it.createdAtEpochMillis)
                },
                bookmarks = e.bookmarks.map {
                    VaultBookmark(it.id, it.positionRef, it.label, it.note, it.createdAtEpochMillis)
                },
            )
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.fromHex(): ByteArray = ByteArray(length / 2) { i ->
    ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
}
