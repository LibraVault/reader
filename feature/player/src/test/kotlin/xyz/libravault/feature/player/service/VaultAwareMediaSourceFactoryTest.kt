@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.player.service

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.VaultStore
import xyz.libravault.core.vaultstore.toHexString

/**
 * [VaultAwareMediaSourceFactory] is what makes Encrypted Vault audio (#493) play
 * through the same shared `ExoPlayer`/`PlaybackService` real files already use —
 * see its class doc for the full "why". This asserts the two things that matter:
 * a `vault://` [MediaItem] resolves against [VaultSessionManager] instead of the
 * delegate, and every other URI passes through to the delegate untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultAwareMediaSourceFactoryTest {

    @Test
    fun `vault URI resolves via the session manager, not the delegate`() {
        val fileId = ByteArray(16) { it.toByte() }
        val fileIdHex = fileId.toHexString()
        val reader = mockk<VaultFileReader>(relaxed = true)
        val store = mockk<VaultStore> {
            every { openReader(fileId) } returns reader
        }
        val sessionManager = mockk<VaultSessionManager> {
            every { requireUnlocked("vault-1") } returns store
        }
        val delegate = mockk<MediaSource.Factory>(relaxed = true)
        val factory = VaultAwareMediaSourceFactory(sessionManager, delegate)

        val mediaItem = MediaItem.fromUri(Uri.parse("$VAULT_MEDIA_URI_SCHEME://vault-1/$fileIdHex"))
        val source = factory.createMediaSource(mediaItem)

        assertTrue(
            "a vault:// MediaItem must resolve to a ProgressiveMediaSource built from " +
                "the vault's own VaultDataSource, not whatever the delegate would produce",
            source is ProgressiveMediaSource,
        )
        verify(exactly = 1) { sessionManager.requireUnlocked("vault-1") }
        verify(exactly = 0) { delegate.createMediaSource(any()) }
    }

    @Test
    fun `non-vault URI falls through to the delegate unmodified`() {
        val sessionManager = mockk<VaultSessionManager>()
        val delegate = mockk<MediaSource.Factory>()
        val expected = mockk<MediaSource>()
        val mediaItem = MediaItem.fromUri(Uri.parse("content://real/file.mp3"))
        every { delegate.createMediaSource(mediaItem) } returns expected

        val factory = VaultAwareMediaSourceFactory(sessionManager, delegate)
        val source = factory.createMediaSource(mediaItem)

        assertSame(expected, source)
        verify(exactly = 1) { delegate.createMediaSource(mediaItem) }
    }

    /**
     * Regression guard for issue #527, ported from the deleted `feature:vault`'s
     * `VaultPlayerDataSourceFactoryTest` — `VaultPlayerViewModel` used to open one
     * `VaultFileReader` and hand that same instance to every `VaultDataSource`
     * Media3 created, racing `raf.seek`/the chunk cache across instances, or
     * reading from a reader another instance had already closed.
     * [vaultPlayerDataSourceFactory] is the extracted fan-out; this asserts each
     * `createDataSource()` call opens its own fresh reader via
     * `VaultStore.openReader` — the pre-fix code would have called `openReader`
     * once total, up front, and handed that single instance to both.
     */
    @Test
    fun `each createDataSource call opens its own fresh reader from the store`() {
        val store = mockk<VaultStore>()
        val fileId = ByteArray(16) { it.toByte() }
        every { store.openReader(fileId) } answers { mockk<VaultFileReader>(relaxed = true) }

        val factory = vaultPlayerDataSourceFactory(store, fileId)
        factory.createDataSource()
        factory.createDataSource()
        factory.createDataSource()

        verify(exactly = 3) { store.openReader(fileId) }
    }
}
