package xyz.libravault.feature.vault

/** [xyz.libravault.core.vaultstore.VaultManifestEntry.format] values that
 * belong on [VaultPlayerScreen] rather than the reader — used by navigation
 * to dispatch a tapped entry to the right screen. Mirrors
 * `core.domain.model.MediaFormat.isAudio()`'s entry set; kept as plain
 * strings here rather than depending on `core:domain` for one check.
 *
 * Split out of `VaultFileId.kt` by #505 — the hex codec that used to live
 * alongside this moved to `core:vaultstore` (needed by `feature:reader`
 * too), but this constant is only ever used by this module's own
 * navigation-routing fork, so it stays here. */
val VAULT_AUDIO_FORMAT_NAMES = setOf("MP3", "M4B", "OGG", "FLAC", "OPUS", "AAC")
