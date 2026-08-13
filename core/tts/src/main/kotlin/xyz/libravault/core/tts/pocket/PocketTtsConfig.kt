package xyz.libravault.core.tts.pocket

import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig

/** Threads sherpa-onnx uses for synthesis - small, since this runs alongside playback. */
private const val NUM_THREADS = 2

/**
 * Builds the sherpa-onnx model config for the bundled Piper VITS voice,
 * given the on-disk directory [PocketModelManager] extracted it into.
 *
 * Lives outside [PocketTtsEngine] so the on-device audio test
 * (`PocketTtsAudioOutputTest`) can synthesize through the *same* config the
 * app ships with. A test that rebuilt this config itself could keep passing
 * while the real engine pointed at, say, a missing `espeak-ng-data` dir -
 * exactly the class of regression that test exists to catch.
 */
internal fun pocketTtsConfig(modelPath: String): OfflineTtsConfig = OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        vits = OfflineTtsVitsModelConfig(
            model = "$modelPath/${PocketVoiceCatalog.MODEL_FILE_NAME}",
            tokens = "$modelPath/${PocketVoiceCatalog.TOKENS_FILE_NAME}",
            // espeak-ng phonemizer data. Only the English dictionaries are
            // bundled (the other 111 were dropped in PR #106), so a broken
            // path here means no phonemization at all, not a fallback.
            dataDir = "$modelPath/${PocketVoiceCatalog.DATA_DIR_NAME}",
        ),
        numThreads = NUM_THREADS,
        provider = "cpu",
    ),
)
