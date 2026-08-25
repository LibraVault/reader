@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.player.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import xyz.libravault.core.vaultcontent.VaultDataSource
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import xyz.libravault.core.vaultstore.hexToFileId

/** Scheme used for a [MediaItem] built from an Encrypted Vault entry — see
 *  [VaultAwareMediaSourceFactory]'s class doc for the `vault://$vaultId/$fileIdHex`
 *  shape. Shared with [xyz.libravault.feature.player.PlayerViewModel]'s vault
 *  playback branch so the URI is only ever assembled/parsed in one place. */
const val VAULT_MEDIA_URI_SCHEME = "vault"

/**
 * One [VaultStore.openReader] call per `createDataSource()`, never a reader
 * shared across instances (issue #527 — a shared reader/`VaultDataSource` isn't
 * safe across the retries/re-buffering that make Media3 open a track's
 * `DataSource` more than once). Extracted so the fan-out itself is
 * unit-testable without constructing a real [MediaSource].  Ported from the
 * deleted `feature:vault`'s `VaultPlayerViewModel.vaultPlayerDataSourceFactory`
 * — same rationale, same guard.
 */
internal fun vaultPlayerDataSourceFactory(store: VaultStore, fileId: ByteArray): VaultDataSource.Factory =
    VaultDataSource.Factory { store.openReader(fileId) }

/**
 * Installed on the shared [PlaybackService]-owned `ExoPlayer`
 * ([PlayerModule.provideExoPlayer]) so Encrypted Vault audio can play through
 * the same background-capable player real files already use — #493.
 *
 * A [androidx.media3.session.MediaController] (what
 * [xyz.libravault.feature.player.PlayerViewModel] actually holds) can only
 * hand the player a [MediaItem] — a serializable URI+metadata descriptor —
 * across the session-client boundary, never a live `MediaSource`/
 * `DataSource.Factory`. Resolving a vault-backed `MediaItem` into a real
 * `MediaSource` therefore has to happen here, inside [PlaybackService]'s own
 * process, at the point Media3 actually asks for one — exactly the deferred
 * design `core:vaultcontent`'s [VaultDataSource] doc comment already named:
 * *"needs a URI scheme plus a registry of currently-open vaults for
 * DataSource.Factory to resolve against."* [sessionManager] is that registry.
 *
 * A `vault://$vaultId/$fileIdHex` [MediaItem] URI resolves via
 * [VaultSessionManager.requireUnlocked] + [vaultPlayerDataSourceFactory] into a
 * [ProgressiveMediaSource]; any other URI (real files) is delegated to
 * [delegate] ([androidx.media3.exoplayer.source.DefaultMediaSourceFactory])
 * unmodified.
 */
class VaultAwareMediaSourceFactory(
    private val sessionManager: VaultSessionManager,
    private val delegate: MediaSource.Factory,
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri
        if (uri != null && uri.scheme == VAULT_MEDIA_URI_SCHEME) {
            return createVaultMediaSource(uri, mediaItem)
        }
        return delegate.createMediaSource(mediaItem)
    }

    private fun createVaultMediaSource(uri: Uri, mediaItem: MediaItem): MediaSource {
        val vaultId = requireNotNull(uri.host) { "vault:// URI missing vault id: $uri" }
        val fileIdHex = requireNotNull(uri.lastPathSegment) { "vault:// URI missing file id: $uri" }
        // requireUnlocked throws VaultLockedException if the vault locked between
        // PlayerViewModel resolving the entry and Media3 actually asking for a
        // MediaSource — surfaces as a player error rather than a silent hang.
        val store = sessionManager.requireUnlocked(vaultId)
        val fileId = fileIdHex.hexToFileId()
        return ProgressiveMediaSource.Factory(vaultPlayerDataSourceFactory(store, fileId))
            .createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(
        drmSessionManagerProvider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply { delegate.setDrmSessionManagerProvider(drmSessionManagerProvider) }

    override fun setLoadErrorHandlingPolicy(
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply { delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }
}
