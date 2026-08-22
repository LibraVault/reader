package xyz.libravault.core.tts.pocket

import xyz.libravault.core.tts.TtsVoiceInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Describes the voice(s) available once [PocketModelManager] has downloaded
 * the bundled Pocket TTS model, and the file layout that model was extracted
 * into (used by [PocketTtsEngine] to build the sherpa-onnx model config).
 *
 * v1 ships exactly one voice - a single-speaker Piper VITS model. See
 * SHERPA_ONNX_SETUP.md for why this specific voice (LJSpeech, public domain)
 * was chosen over sherpa-onnx's own "Pocket TTS" model family (non-commercial
 * license) or other Piper voices (several are derived from a voice whose
 * license also forbids commercial use).
 */
@Singleton
class PocketVoiceCatalog @Inject constructor(
    private val modelManager: PocketModelManager,
) {
    /** Returns the bundled voice once its model has finished downloading, else empty. */
    fun availableVoices(): List<TtsVoiceInfo> =
        if (modelManager.isModelValid()) listOf(DEFAULT_VOICE) else emptyList()

    companion object {
        // "high" tier, not "medium" - swapped 2026-08-22 in response to real
        // TestFlight feedback describing the voice as robotic. Same LJSpeech
        // (public-domain) training data and license as medium, just a bigger
        // checkpoint - see SHERPA_ONNX_SETUP.md's "Updating the voice model"
        // section for how this was picked.
        const val DEFAULT_VOICE_ID = "en_US-ljspeech-high"

        /** Filenames within [PocketModelManager]'s extracted model directory. */
        const val MODEL_FILE_NAME = "en_US-ljspeech-high.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
        const val DATA_DIR_NAME = "espeak-ng-data"

        /** Single-speaker model - always speaker index 0. */
        const val DEFAULT_SPEAKER_ID = 0

        val DEFAULT_VOICE = TtsVoiceInfo(
            id = DEFAULT_VOICE_ID,
            displayName = "Ljspeech (English)",
            locale = "en-US",
            requiresNetwork = false,
        )
    }
}
