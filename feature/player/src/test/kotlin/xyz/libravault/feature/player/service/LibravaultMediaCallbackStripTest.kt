package xyz.libravault.feature.player.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LibravaultMediaCallback] command/strip construction.
 *
 * Covers the pure helpers that encode the user's `defaultSkipDurationSec` preference
 * into the ±seek tile buttons ([LibravaultMediaCallback.seekOffset],
 * [LibravaultMediaCallback.seekOffsetBack], [LibravaultMediaCallback.seekByCommand])
 * plus a smoke test of [LibravaultMediaCallback.buildStandardStrip]'s shape.
 *
 * The full Bundle-population path (Bundle.putLong / Bundle.getLong) is exercised
 * transitively by `buildStandardStrip`, but [android.os.Bundle] is not mocked in
 * plain JUnit 5 unit tests — the module's `testOptions.unitTests.isReturnDefaultValues
 * = true` makes the methods return defaults instead of throwing. The offset math is
 * therefore asserted on the pure helpers rather than reading back through Bundle,
 * which is the more robust boundary to test anyway (Bundle is a thin transport).
 */
class LibravaultMediaCallbackStripTest {

    private val names = LibravaultMediaCallback.Companion.StripDisplayNames(
        previous = "Previous",
        back = "Skip back",
        play = "Play",
        forward = "Skip forward",
        next = "Next",
    )

    // ── seekOffset / seekOffsetBack: the pure offset math ───────────────────

    @Test
    fun `seekOffset returns the magnitude unchanged for forward direction`() {
        assertEquals(30_000L, LibravaultMediaCallback.seekOffset(30_000L))
        assertEquals(5_000L, LibravaultMediaCallback.seekOffset(5_000L))
        assertEquals(120_000L, LibravaultMediaCallback.seekOffset(120_000L))
    }

    @Test
    fun `seekOffsetBack returns the negated magnitude for back direction`() {
        assertEquals(-30_000L, LibravaultMediaCallback.seekOffsetBack(30_000L))
        assertEquals(-5_000L, LibravaultMediaCallback.seekOffsetBack(5_000L))
        assertEquals(-120_000L, LibravaultMediaCallback.seekOffsetBack(120_000L))
    }

    @Test
    fun `seekOffset rejects zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibravaultMediaCallback.seekOffset(0L)
        }
    }

    @Test
    fun `seekOffset rejects negative values`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibravaultMediaCallback.seekOffset(-1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibravaultMediaCallback.seekOffset(-30_000L)
        }
    }

    @Test
    fun `seekOffsetBack rejects zero and negative values`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibravaultMediaCallback.seekOffsetBack(0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibravaultMediaCallback.seekOffsetBack(-1L)
        }
    }

    @Test
    fun `seekOffset honors the caller's value - regression guard for hardcoded 30s`() {
        // Regression guard for the original review: DEFAULT_SEEK_STEP_MS used to be a
        // private constant with no parameter, so ±seek buttons always shipped 30s
        // regardless of the user's preference. The pure helpers must respect the input.
        assertEquals(45_000L,  LibravaultMediaCallback.seekOffset(45_000L))
        assertEquals(7_500L,   LibravaultMediaCallback.seekOffset(7_500L))
        assertEquals(-45_000L, LibravaultMediaCallback.seekOffsetBack(45_000L))
        assertEquals(-7_500L,  LibravaultMediaCallback.seekOffsetBack(7_500L))
    }

    // ── seekByCommand: wraps the offset in the SessionCommand ──────────────

    @Test
    fun `seekByCommand produces a SessionCommand on the SEEK_BY action`() {
        val cmd = LibravaultMediaCallback.seekByCommand(15_000L)
        assertEquals(CustomCommandActions.SEEK_BY, cmd.customAction)
        assertNotNull(cmd.customExtras, "SEEK_BY must carry an extras bundle with offsetMs")
    }

    // ── buildStandardStrip: structural smoke test ──────────────────────────

    @Test
    fun `strip is exactly five enabled buttons in order Prev -seek PlayPause +seek Next`() {
        val strip = LibravaultMediaCallback.buildStandardStrip(seekStepMs = 30_000L, displayNames = names)
        assertEquals(5, strip.size)
        strip.forEach { assertTrue(it.isEnabled) }
        // Sanity: every button must carry a SessionCommand so it survives the
        // PlayerWrapper.createPlaybackStateCompat filter (sessionCommand != null AND
        // sessionCommand.commandCode == COMMAND_CODE_CUSTOM).
        strip.forEach {
            assertNotNull(it.sessionCommand, "button missing sessionCommand")
        }
    }

    @Test
    fun `play-pause button uses PLAY_PAUSE action and the middle slot`() {
        val strip = LibravaultMediaCallback.buildStandardStrip(seekStepMs = 30_000L, displayNames = names)
        val playPause = strip[2].sessionCommand!!
        assertEquals(CustomCommandActions.PLAY_PAUSE, playPause.customAction)
    }

    @Test
    fun `seek buttons use SEEK_BY action on slots 1 and 3`() {
        val strip = LibravaultMediaCallback.buildStandardStrip(seekStepMs = 30_000L, displayNames = names)
        assertEquals(CustomCommandActions.SEEK_BY, strip[1].sessionCommand!!.customAction)
        assertEquals(CustomCommandActions.SEEK_BY, strip[3].sessionCommand!!.customAction)
    }

    @Test
    fun `prev and next buttons use PREVIOUS and NEXT actions on the outer slots`() {
        val strip = LibravaultMediaCallback.buildStandardStrip(seekStepMs = 30_000L, displayNames = names)
        assertEquals(CustomCommandActions.PREVIOUS, strip[0].sessionCommand!!.customAction)
        assertEquals(CustomCommandActions.NEXT, strip[4].sessionCommand!!.customAction)
    }

    @Test
    fun `display names are placed on the correct buttons in positional order`() {
        val strip = LibravaultMediaCallback.buildStandardStrip(seekStepMs = 30_000L, displayNames = names)
        assertEquals(names.previous, strip[0].displayName)
        assertEquals(names.back, strip[1].displayName)
        assertEquals(names.play, strip[2].displayName)
        assertEquals(names.forward, strip[3].displayName)
        assertEquals(names.next, strip[4].displayName)
    }

    @Test
    fun `buildStandardStrip rejects zero or negative seekStepMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            LibravaultMediaCallback.buildStandardStrip(seekStepMs = 0L, displayNames = names)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LibravaultMediaCallback.buildStandardStrip(seekStepMs = -1L, displayNames = names)
        }
    }
}