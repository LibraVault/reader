package xyz.libravault.feature.player.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PlaybackStateHolder is only ever mocked (never exercised for real) in
 * PlayerViewModelTest — its own state-transition logic had no direct coverage.
 */
class PlaybackStateHolderTest {

    @Test
    fun `initial state is inactive with no item`() {
        val holder = PlaybackStateHolder()

        val state = holder.state.value

        assertNull(state.itemId)
        assertFalse(state.isActive)
        assertFalse(state.isPlaying)
        assertNull(state.lastKnownPositionMs)
    }

    @Test
    fun `update replaces the whole state and marks it active`() = runTest {
        val holder = PlaybackStateHolder()

        holder.update(
            itemId = 7, vaultFolderId = 5, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = "cover.jpg", isPlaying = true,
        )

        val state = holder.state.value
        assertEquals(7L, state.itemId)
        assertEquals(5L, state.vaultFolderId)
        assertEquals("content://vault/book.mp3", state.filePath)
        assertEquals("T", state.title)
        assertEquals("A", state.author)
        assertEquals("cover.jpg", state.coverArtPath)
        assertTrue(state.isPlaying)
        assertTrue(state.isActive)
    }

    @Test
    fun `update does not carry over a stale lastKnownPositionMs from before it`() {
        val holder = PlaybackStateHolder()
        holder.updatePosition(5_000)

        // A brand new update() call — e.g. switching to a different item — should
        // start fresh, not silently keep the previous item's playback position.
        holder.update(
            itemId = 1, vaultFolderId = 5, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = null, isPlaying = false,
        )

        assertNull(holder.state.value.lastKnownPositionMs)
    }

    @Test
    fun `updatePosition only changes the position, leaving the rest of the state intact`() {
        val holder = PlaybackStateHolder()
        holder.update(
            itemId = 1, vaultFolderId = 5, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = "cover.jpg", isPlaying = true,
        )

        holder.updatePosition(12_345)

        val state = holder.state.value
        assertEquals(12_345L, state.lastKnownPositionMs)
        assertEquals(1L, state.itemId)
        assertEquals("T", state.title)
        assertTrue(state.isPlaying)
    }

    /** #493 — updateVault's counterpart to `update`, keeping itemId/vaultFolderId/
     *  filePath null so LibravaultMediaCallback's existing Room-lookup guard keeps
     *  no-op'ing for a vault item without needing its own vault branch. */
    @Test
    fun `updateVault sets vaultEntry and leaves itemId, vaultFolderId, filePath null`() {
        val holder = PlaybackStateHolder()
        val vaultEntry = xyz.libravault.core.domain.model.ContentSource.VaultEntry(
            vaultId = "vault-1", fileIdHex = "aa", format = xyz.libravault.core.domain.model.MediaFormat.MP3,
        )

        holder.updateVault(
            vaultEntry = vaultEntry, title = "T", author = "A", coverArtPath = "cover.jpg", isPlaying = true,
        )

        val state = holder.state.value
        assertEquals(vaultEntry, state.vaultEntry)
        assertNull(state.itemId)
        assertNull(state.vaultFolderId)
        assertNull(state.filePath)
        assertEquals("T", state.title)
        assertTrue(state.isPlaying)
        assertTrue(state.isActive)
    }

    @Test
    fun `clear resets to the initial inactive state`() {
        val holder = PlaybackStateHolder()
        holder.update(
            itemId = 1, vaultFolderId = 5, filePath = "content://vault/book.mp3",
            title = "T", author = "A", coverArtPath = "cover.jpg", isPlaying = true,
        )
        holder.updatePosition(1_000)

        holder.clear()

        assertEquals(PlaybackStateHolder.State(), holder.state.value)
    }
}
