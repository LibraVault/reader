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
