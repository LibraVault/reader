package xyz.libravault.core.vaultstore

/**
 * Hex encoding for a [VaultManifestEntry.fileId], for use as a nav-route
 * argument (a [ByteArray] can't be a nav argument directly) — `VaultStore`'s
 * own hex helper (`toHexForFileName`) is private, so this is a small,
 * separate public codec rather than exposing that one.
 *
 * Moved here from `feature:vault` by #505 — `feature:reader` needs it too,
 * to resolve a `ContentSource.VaultEntry`'s `fileIdHex` back to the raw id
 * `VaultStore.openReader`/bookmark/highlight calls take. Living in
 * `core:vaultstore` avoids a feature-to-feature dependency that would
 * otherwise be needed just for these two functions.
 */
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/** Inverse of [toHexString]. */
fun String.hexToFileId(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** [VaultManifestEntry.format] values that belong on the player rather than the
 * reader — used by navigation to dispatch a tapped entry to the right screen,
 * and (since #505) by `ReaderViewModel` to defensively reject an
 * audio-format vault entry that was routed to the reader by mistake. Mirrors
 * `core.domain.model.MediaFormat.isAudio()`'s entry set; kept as plain
 * strings here rather than depending on `core:domain` for one check.
 *
 * Moved here from `feature:vault` by #505, alongside [toHexString] — the
 * same "`feature:reader` needs it too" reasoning applies now that
 * `ReaderViewModel` does this check as well, not just `feature:vault`'s own
 * navigation fork. */
val VAULT_AUDIO_FORMAT_NAMES = setOf("MP3", "M4B", "OGG", "FLAC", "OPUS", "AAC")
