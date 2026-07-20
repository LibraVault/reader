package xyz.libravault.core.domain.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class MediaFormatTest {

    @ParameterizedTest
    @EnumSource(MediaFormat::class)
    fun `isAudio identifies audio formats correctly`(format: MediaFormat) {
        when (format) {
            MediaFormat.MP3,
            MediaFormat.M4B,
            MediaFormat.OGG,
            MediaFormat.FLAC,
            MediaFormat.OPUS,
            MediaFormat.AAC -> assertTrue(format.isAudio(), "$format should be audio")
            MediaFormat.EPUB,
            MediaFormat.PDF -> assertFalse(format.isAudio(), "$format should not be audio")
        }
    }
}
