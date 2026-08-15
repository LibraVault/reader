package xyz.libravault.feature.vault

/** Hex encoding for a [xyz.libravault.core.vaultstore.VaultManifestEntry.fileId]
 * for use as a nav-route argument — `core:vaultstore`'s own hex helper
 * (`VaultStore`'s private `toHexForFileName`) isn't visible here, and a
 * `ByteArray` can't be a nav argument directly. */
fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

/** Inverse of [toHexString]. */
fun String.hexToFileId(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** [xyz.libravault.core.vaultstore.VaultManifestEntry.format] values that
 * belong on [VaultPlayerScreen] rather than [VaultReaderScreen] — used by
 * navigation to dispatch a tapped entry to the right screen. Mirrors
 * `core.domain.model.MediaFormat.isAudio()`'s entry set; kept as plain
 * strings here rather than depending on `core:domain` for one check. */
val VAULT_AUDIO_FORMAT_NAMES = setOf("MP3", "M4B", "OGG", "FLAC", "OPUS", "AAC")
