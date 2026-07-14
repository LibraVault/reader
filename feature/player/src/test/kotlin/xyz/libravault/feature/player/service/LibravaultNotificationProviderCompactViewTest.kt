package xyz.libravault.feature.player.service

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure compact-view index logic in
 * [LibravaultNotificationProvider.Companion.compactViewIndicesFor].
 *
 * Background: the provider always returns a 5-button list `[Prev, −seek, PlayPause, +seek, Next]`
 * for the lockscreen / Quick-Settings media tile, but a future override (or a defensive caller)
 * could pass a shorter list. The helper must clamp the returned indices to the actual action-list
 * size so `Notification.MediaStyle.setShowActionsInCompactView` never receives an out-of-bounds
 * index — that would either crash `addNotificationActions` or silently corrupt the tile.
 *
 * `DefaultMediaNotificationProvider.addNotificationActions` (1.3.1 source) appends every
 * `CommandButton` from the button list as a notification action regardless of `isEnabled`, so
 * in practice the action-list size always equals the button-list size. These tests pin that
 * invariant.
 */
class LibravaultNotificationProviderCompactViewTest {

    @Test
    fun `full 5-slot strip returns all five indices in order`() {
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3, 4),
            LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 5),
        )
    }

    @Test
    fun `more than 5 actions is clamped to the first 5`() {
        // Defensive: should never happen (the provider always returns exactly 5), but if a
        // future override returned more, we still return 5 indices — `Notification.MediaStyle`
        // caps compact-view at 5 anyway.
        assertArrayEquals(
            intArrayOf(0, 1, 2, 3, 4),
            LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 7),
        )
    }

    @Test
    fun `single-track audiobook simulating 4 actions still returns 4 in-bounds indices`() {
        // If a future override dropped prev/next on single-item playlists (the only scenario
        // where COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM is unavailable), the action list would
        // be 4 entries. Returning [0, 1, 2, 3, 4] in that case would be out-of-bounds; the
        // helper clamps to 4.
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 4)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), indices)
        assertTrue(indices.all { it >= 0 && it < 4 })
    }

    @Test
    fun `2-action minimum (play-pause only) returns 2 indices, both in bounds`() {
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 2)
        assertArrayEquals(intArrayOf(0, 1), indices)
        assertTrue(indices.all { it >= 0 && it < 2 })
    }

    @Test
    fun `1-action minimum returns a single in-bounds index`() {
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 1)
        assertArrayEquals(intArrayOf(0), indices)
        assertTrue(indices.all { it >= 0 && it < 1 })
    }

    @Test
    fun `empty action list returns an empty array rather than OOB indices`() {
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 0)
        assertEquals(0, indices.size)
    }

    @Test
    fun `negative input returns empty array rather than OOB indices`() {
        // Defensive: a caller passing a negative size shouldn't crash the notification build.
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = -1)
        assertEquals(0, indices.size)
    }
}
