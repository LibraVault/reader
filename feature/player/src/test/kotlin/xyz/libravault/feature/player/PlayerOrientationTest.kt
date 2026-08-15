package xyz.libravault.feature.player

import android.content.res.Configuration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the actual routing condition PlayerScreen uses to pick between
 * PortraitPlayerContent and LandscapePlayerContent (see #164/#165) — a plain
 * JUnit test rather than Robolectric since Configuration.orientation is a
 * public field, not a method call the "not mocked" Android stub intercepts.
 * PlayerScreenLandscapeTest covers the two layouts themselves but calls them
 * directly, so it wouldn't catch a flipped comparison or swapped constant
 * here.
 */
class PlayerOrientationTest {

    @Test
    fun `landscape configuration is landscape`() {
        val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_LANDSCAPE }

        assertTrue(isLandscapeOrientation(configuration))
    }

    @Test
    fun `portrait configuration is not landscape`() {
        val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_PORTRAIT }

        assertFalse(isLandscapeOrientation(configuration))
    }

    @Test
    fun `undefined configuration is not landscape`() {
        val configuration = Configuration().apply { orientation = Configuration.ORIENTATION_UNDEFINED }

        assertFalse(isLandscapeOrientation(configuration))
    }
}
