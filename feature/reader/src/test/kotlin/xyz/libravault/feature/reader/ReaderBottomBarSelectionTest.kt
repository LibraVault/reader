package xyz.libravault.feature.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.feature.player.service.PlaybackStateHolder

/**
 * Regression coverage for the mini-bar precedence bug QA found in #277: a stale,
 * merely-paused audiobook's `showMiniPlayer` (backed by [PlaybackStateHolder.State.itemId],
 * which is never cleared once set) must never win over an actually-active Read Aloud
 * session.
 */
class ReaderBottomBarSelectionTest {

    @Test
    fun `read aloud wins when both a stale paused audiobook and an active session are present`() {
        assertEquals(
            ReaderBottomBar.READ_ALOUD,
            selectReaderBottomBar(showMiniPlayer = true, showReadAloudBar = true),
        )
    }

    @Test
    fun `audiobook shows when read aloud is not active`() {
        assertEquals(
            ReaderBottomBar.AUDIOBOOK,
            selectReaderBottomBar(showMiniPlayer = true, showReadAloudBar = false),
        )
    }

    @Test
    fun `read aloud shows on its own`() {
        assertEquals(
            ReaderBottomBar.READ_ALOUD,
            selectReaderBottomBar(showMiniPlayer = false, showReadAloudBar = true),
        )
    }

    @Test
    fun `neither bar shows when nothing is loaded or active`() {
        assertEquals(
            ReaderBottomBar.NONE,
            selectReaderBottomBar(showMiniPlayer = false, showReadAloudBar = false),
        )
    }

    // ── shouldShowAudiobookMiniPlayer (#493) ─────────────────────────────────

    @Test
    fun `mini-player shows for an active vault item even though itemId stays null`() {
        val holder = PlaybackStateHolder()
        holder.updateVault(
            vaultEntry = ContentSource.VaultEntry("vault-1", "aabbcc", MediaFormat.MP3),
            title = "Vault Audiobook", author = "Author", coverArtPath = null, isPlaying = true,
        )
        assertTrue(shouldShowAudiobookMiniPlayer(holder.state.value))
    }

    @Test
    fun `mini-player shows for an active real-file item`() {
        val holder = PlaybackStateHolder()
        holder.update(
            itemId = 1L, vaultFolderId = 1L, filePath = "content://x",
            title = "Book", author = "Author", coverArtPath = null, isPlaying = true,
        )
        assertTrue(shouldShowAudiobookMiniPlayer(holder.state.value))
    }

    @Test
    fun `mini-player does not show when nothing has ever loaded`() {
        assertFalse(shouldShowAudiobookMiniPlayer(PlaybackStateHolder().state.value))
    }
}
