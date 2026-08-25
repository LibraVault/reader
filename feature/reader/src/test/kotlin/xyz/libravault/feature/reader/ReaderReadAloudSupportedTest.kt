package xyz.libravault.feature.reader

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Coverage for [readAloudSupported] — the gate deciding which formats expose the
 * "Read Aloud" entry point in the settings sheet. EPUB (#137), Markdown (#276), and
 * now PDF (#591 Phase 3, one chapter per page) are all supported.
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
    fun `PDF supports Read Aloud`() {
        assertTrue(readAloudSupported(MediaFormat.PDF))
    }
}
