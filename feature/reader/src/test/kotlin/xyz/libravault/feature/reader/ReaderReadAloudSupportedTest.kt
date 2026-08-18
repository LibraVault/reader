package xyz.libravault.feature.reader

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Coverage for [readAloudSupported] — the gate deciding which formats expose the
 * "Read Aloud" entry point in the settings sheet. EPUB (#137) and Markdown (#276)
 * are supported; PDF is explicitly out of scope for both.
 */
class ReaderReadAloudSupportedTest {

    @Test
    fun `EPUB supports Read Aloud`() {
        assertTrue(readAloudSupported(MediaFormat.EPUB))
    }

    @Test
    fun `Markdown supports Read Aloud`() {
        assertTrue(readAloudSupported(MediaFormat.MARKDOWN))
    }

    @Test
    fun `PDF does not support Read Aloud`() {
        assertFalse(readAloudSupported(MediaFormat.PDF))
    }
}
