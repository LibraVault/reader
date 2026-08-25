package xyz.libravault.core.domain.model

/**
 * Where a reader screen's bytes come from — resolved once, at navigation entry
 * ([xyz.libravault.feature.reader.ReaderViewModel]'s `init{}`), per the vault-content-
 * unification PRD's "only the entry point should ever fork" direction (issue #505). Every
 * downstream format renderer (EPUB/PDF/Markdown) takes this instead of assuming a directly
 * resolvable [android.net.Uri].
 *
 * Deliberately does NOT carry an already-open
 * [xyz.libravault.core.vaultcrypto.VaultFileReader] for the vault case (contrast the PRD's
 * illustrative sketch) — two reasons:
 *  1. `VaultFileReader` is explicitly not thread-safe / single-consumer (see its own doc
 *     comment): EPUB (Readium, held open for the publication's lifetime), PDF (FUSE proxy-fd
 *     callback thread), and Markdown (one-shot full read) each need their own reader with
 *     their own lifecycle — sharing one instance across them would violate that constraint
 *     the moment more than one format's resolver was active at once.
 *  2. `VaultStore.openReader(fileId)` is cheap and repeatable (it just derives a fresh
 *     content key from the currently-held vault master key) — there is no reason to force a
 *     single reader to be threaded through multiple consumers when opening a second one costs
 *     nothing.
 *
 * [VaultEntry.vaultId]/[VaultEntry.fileIdHex] are resolved against a live, unlocked
 * [xyz.libravault.core.vaultstore.VaultSessionManager] at each point of use — this also means
 * re-resolution naturally reflects the vault's *current* lock state rather than baking in a
 * stale "was unlocked when this ContentSource was built" assumption.
 *
 * Lives in `core:domain`, not a feature module or `core:vaultstore`/`core:vaultcontent` —
 * matches this module's existing convention of storing URIs as a plain [String]
 * (see [LibraryItem.filePath], [VaultFolder.uri]) rather than [android.net.Uri] directly, so
 * this file introduces no new Android dependency into `core:domain`.
 */
sealed interface ContentSource {

    /**
     * A real, resolvable file — SAF `content://` URI, `file://` URI, or app-private path.
     * [uriString] is parsed via `Uri.parse()` at the point of use in the feature layer, exactly
     * like [LibraryItem.filePath] is today.
     */
    data class RealFile(val uriString: String) : ContentSource

    /**
     * An entry inside a (possibly since-relocked) Encrypted Vault. [vaultId] identifies the
     * vault itself; [fileIdHex] is the hex encoding of the entry's 16-byte manifest file id
     * (see `VaultManifestEntry.fileId` / `VaultFileId.toHexString()`).
     */
    data class VaultEntry(
        val vaultId: String,
        val fileIdHex: String,
        val format: MediaFormat,
    ) : ContentSource
}
