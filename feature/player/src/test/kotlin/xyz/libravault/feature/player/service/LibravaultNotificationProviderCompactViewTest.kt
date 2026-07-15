package xyz.libravault.feature.player.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LibravaultNotificationProvider.Companion.compactViewIndicesFor].
 *
 * The helper produces the compact-view indices handed to
 * `Notification.MediaStyle.setShowActionsInCompactView`. Returning indices that are
 * out of bounds relative to the actual action-list size is a corruption hazard —
 * some system surfaces have been observed to crash; others silently drop the slot.
 *
 * The helper now always returns `[0, size)` so every index references a real entry
 * in the action list, regardless of whether the provider returned the standard 5
 * buttons or fewer (or more, after custom buttons are appended).
 *
 * `DefaultMediaNotificationProvider.addNotificationActions` (1.3.1 source) appends
 * every `CommandButton` from the input list as a notification action regardless of
 * `isEnabled`, so the action-list size always equals the button-list size.
 */
class LibravaultNotificationProviderCompactViewTest {

    @Test
    fun `standard 5-slot strip returns all five indices in order`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 5).toList(),
        )
    }

    @Test
    fun `custom buttons appended beyond the 5 - every position is returned, not just 0 to 4`() {
        // Regression: an earlier version hardcoded `intArrayOf(0, 1, 2, 3, 4)`, which
        // silently hid any custom buttons beyond slot 5 from the compact view.
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 7)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), indices.toList())
    }

    @Test
    fun `more than 5 actions is fine - the platform caps compact view at 5 anyway`() {
        // Notification.MediaStyle.setShowActionsInCompactView itself caps at 5 visible
        // slots, so returning all 7 indices lets the system pick the first 5 to render
        // (the pinned Prev / -seek / PlayPause / +seek / Next).
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 7)
        assertTrue(indices.all { it >= 0 && it < 7 })
    }

    @Test
    fun `single-track audiobook simulating 4 actions still returns 4 in-bounds indices`() {
        // If a future override dropped prev/next on single-item playlists (the only scenario
        // where COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM is unavailable), the action list would
        // be 4 entries. Returning the indices [0, 1, 2, 3] here keeps the tile valid.
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 4)
        assertEquals(listOf(0, 1, 2, 3), indices.toList())
        assertTrue(indices.all { it >= 0 && it < 4 })
    }

    @Test
    fun `2-action minimum (play-pause only) returns 2 indices, both in bounds`() {
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 2)
        assertEquals(listOf(0, 1), indices.toList())
        assertTrue(indices.all { it >= 0 && it < 2 })
    }

    @Test
    fun `1-action minimum returns a single in-bounds index`() {
        val indices = LibravaultNotificationProvider.Companion.compactViewIndicesFor(actionListSize = 1)
        assertEquals(listOf(0), indices.toList())
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
