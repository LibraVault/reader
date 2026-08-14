package xyz.libravault.core.tts.pocket

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.libravault.core.tts.pocket.audio.PcmAnalysis
import java.io.File

private const val TAG = "PocketTtsAudioOutputTest"

/**
 * On-device test that Pocket TTS actually produces speech - issue #107.
 *
 * Every other Pocket TTS test mocks or avoids the native layer and checks
 * only engine lifecycle, model management and playback plumbing. Nothing
 * asserted that English text turns into non-silent, plausibly-shaped audio.
 * That gap became concrete in PR #106, which deleted 111 espeak-ng phonemizer
 * dictionaries: a clean build, green unit tests and a crash-free Robo run all
 * passed without any of them being able to tell whether English still spoke.
 *
 * So this runs the real pipeline - real bundled model, real espeak-ng
 * phonemization, real sherpa-onnx synthesis, no mocking - through the same
 * [pocketTtsConfig] the shipped engine uses, and asserts on the PCM that comes
 * out.
 *
 * ## Assertion strategy
 *
 * ONNX runtime output is not bit-identical across devices, architectures or
 * runs (VITS samples from a noise distribution), so there is no golden
 * waveform to diff against. Instead the checks are of two kinds:
 *
 *  - **Absolute**, with deliberately wide bands, to catch total failure:
 *    empty output, digital silence, NaNs, absurd durations.
 *  - **Relative**, which are the tighter and more useful regression signal:
 *    longer text must yield longer audio, higher speed must yield shorter
 *    audio, different text must yield different audio. These hold regardless
 *    of device and would break loudly if phonemization silently degraded to
 *    something text-independent.
 *
 * ## Where this runs
 *
 * `third-party/sherpa-onnx/sherpa-onnx-android.aar` bundles **arm64-v8a only**
 * (a deliberate project scope decision - see `AAR_RESOLUTION.md`), so the
 * native library is simply absent on the x86_64 emulator used by
 * `ui-tests.yml`. There these tests skip via [Assume] rather than fail. To get
 * real signal they must run on arm64 hardware - use the `tts-audio-test.yml`
 * workflow, which drives a Firebase Test Lab physical device.
 */
@RunWith(AndroidJUnit4::class)
class PocketTtsAudioOutputTest {

    private lateinit var tts: OfflineTts
    private lateinit var modelPath: String

    @Before
    fun setUp() {
        Assume.assumeTrue(
            "sherpa-onnx ships arm64-v8a natives only; this device reports " +
                "${Build.SUPPORTED_ABIS.toList()}. Run on arm64 hardware for real coverage.",
            Build.SUPPORTED_ABIS.contains(ARM64_ABI),
        )

        // Loading a 19 MB ONNX model takes seconds, so it is shared across the
        // whole class rather than rebuilt per test. Synthesis itself is
        // stateless, so there is nothing to reset between tests.
        synchronized(lock) {
            if (sharedTts == null) {
                val context = ApplicationProvider.getApplicationContext<Context>()
                sharedModelPath = prepareModel(context)
                sharedTts = OfflineTts(config = pocketTtsConfig(sharedModelPath!!))
            }
        }
        tts = sharedTts!!
        modelPath = sharedModelPath!!
    }

    // ── The bundled model and its phonemizer data ──

    @Test
    fun englishEspeakDataIsBundledOnDisk() {
        val espeakDir = File(modelPath, PocketVoiceCatalog.DATA_DIR_NAME)
        assertTrue("espeak-ng-data missing at $espeakDir", espeakDir.isDirectory)

        // The specific files English phonemization needs. PR #106 trimmed the
        // other languages' dictionaries; naming these makes a future trim that
        // goes one file too far fail here with an obvious message rather than
        // as unexplained silence further down.
        listOf(
            "en_dict",          // English pronunciation dictionary
            "phontab",          // phoneme tables
            "phondata",         // phoneme data
            "phonindex",        // phoneme index
            "intonations",      // prosody
            "lang/gmw/en",      // the "en" voice the model's config selects
        ).forEach { relativePath ->
            val file = File(espeakDir, relativePath)
            assertTrue("Required espeak-ng file missing: $relativePath", file.isFile)
            assertTrue("Required espeak-ng file is empty: $relativePath", file.length() > 0)
        }
    }

    @Test
    fun engineReportsTheBundledModelsSampleRateAndSpeakerCount() {
        assertEquals(
            "Model sample rate changed - the duration bands in this test assume 22.05 kHz",
            EXPECTED_SAMPLE_RATE,
            tts.sampleRate(),
        )
        assertEquals("Bundled voice is single-speaker", 1, tts.numSpeakers())
    }

    // ── The core assertion: English text becomes audible speech ──

    @Test
    fun synthesizesAudibleNonSilentSpeechForEnglishText() {
        val audio = synthesize(SENTENCE)
        val samples = audio.samples

        assertTrue("Synthesis returned no samples at all", samples.isNotEmpty())
        assertEquals(EXPECTED_SAMPLE_RATE, audio.sampleRate)

        assertFalse(
            "Output contains NaN or infinite samples - these reach AudioTrack as artefacts",
            PcmAnalysis.hasNonFiniteSamples(samples),
        )

        // A little overshoot past full scale is normal model headroom; a large
        // share of it means the buffer would clip audibly.
        val outOfRange = PcmAnalysis.outOfRangeCount(samples)
        assertTrue(
            "$outOfRange of ${samples.size} samples exceed +-1.0 - output would clip",
            outOfRange < samples.size / 100,
        )

        val rms = PcmAnalysis.rms(samples)
        assertTrue("Output is silent or near-silent (RMS $rms)", rms > MIN_SPEECH_RMS)

        val peak = PcmAnalysis.peak(samples)
        assertTrue("Output never rises above a whisper (peak $peak)", peak > MIN_SPEECH_PEAK)

        val silentFraction = PcmAnalysis.silentFraction(samples, audio.sampleRate)
        assertTrue(
            "${(silentFraction * 100).toInt()}% of the output is silence - " +
                "phonemization may have dropped most of the text",
            silentFraction < MAX_SILENT_FRACTION,
        )

        // Catches output that is loud overall but has lost a stretch in the
        // middle - a whole clause gone - which the aggregate figures above
        // would happily average over.
        val longestSilence = PcmAnalysis.longestSilenceSeconds(samples, audio.sampleRate)
        assertTrue(
            "Found a ${"%.2f".format(longestSilence)}s unbroken silence inside a " +
                "single spoken sentence",
            longestSilence < MAX_INTERNAL_SILENCE_SECONDS,
        )
    }

    @Test
    fun speechDurationIsPlausibleForTheSentenceLength() {
        val audio = synthesize(SENTENCE)
        val duration = PcmAnalysis.durationSeconds(audio.samples.size, audio.sampleRate)
        val charsPerSecond = SENTENCE.length / duration

        Log.d(TAG, "Spoke ${SENTENCE.length} chars in ${"%.2f".format(duration)}s")

        // Wide band on purpose - it is here to catch "produced a fraction of a
        // second of noise" and "produced a minute of garbage", not to pin down
        // a speaking rate that legitimately varies with voice and device.
        assertTrue(
            "Implausible speaking rate: ${"%.1f".format(charsPerSecond)} chars/sec " +
                "(${"%.2f".format(duration)}s for ${SENTENCE.length} chars)",
            charsPerSecond in MIN_CHARS_PER_SECOND..MAX_CHARS_PER_SECOND,
        )
    }

    @Test
    fun synthesizesTextContainingNumbersAndAbbreviations() {
        // Number and abbreviation expansion lives in espeak's English
        // dictionary rather than the ONNX model, so this exercises a different
        // part of the phonemizer than plain prose - and it is the part a
        // dictionary trim is most likely to break.
        val audio = synthesize(NUMERIC_SENTENCE)

        assertTrue("Synthesis returned no samples", audio.samples.isNotEmpty())
        assertFalse(PcmAnalysis.hasNonFiniteSamples(audio.samples))
        assertTrue(
            "Numeric text produced silence - number expansion may have failed",
            PcmAnalysis.rms(audio.samples) > MIN_SPEECH_RMS,
        )

        // "12" and "3:45 p.m." spoken out is far more audio than the 34 written
        // characters suggest, so only the floor is meaningful here.
        val duration = PcmAnalysis.durationSeconds(audio.samples.size, audio.sampleRate)
        assertTrue(
            "Numeric text produced only ${"%.2f".format(duration)}s of audio",
            duration > MIN_UTTERANCE_SECONDS,
        )
    }

    // ── Relative checks: output must actually track its input ──

    @Test
    fun longerTextProducesProportionallyLongerAudio() {
        val shortAudio = synthesize(SENTENCE)
        val longAudio = synthesize(SENTENCE + " " + SECOND_SENTENCE)

        val shortDuration = PcmAnalysis.durationSeconds(shortAudio.samples.size, shortAudio.sampleRate)
        val longDuration = PcmAnalysis.durationSeconds(longAudio.samples.size, longAudio.sampleRate)
        val ratio = longDuration / shortDuration

        Log.d(TAG, "Durations: short ${"%.2f".format(shortDuration)}s, long ${"%.2f".format(longDuration)}s")

        // Roughly-doubled text should give roughly-doubled audio. If
        // phonemization silently stopped tracking the input, this ratio
        // collapses towards 1.
        assertTrue(
            "Doubling the text changed duration by only ${"%.2f".format(ratio)}x " +
                "- output may not depend on the input text",
            ratio > MIN_LENGTH_RATIO && ratio < MAX_LENGTH_RATIO,
        )
    }

    @Test
    fun higherSpeedProducesShorterAudio() {
        val normal = synthesize(SENTENCE, speed = 1.0f)
        val fast = synthesize(SENTENCE, speed = 2.0f)

        val normalDuration = PcmAnalysis.durationSeconds(normal.samples.size, normal.sampleRate)
        val fastDuration = PcmAnalysis.durationSeconds(fast.samples.size, fast.sampleRate)
        val ratio = normalDuration / fastDuration

        // Verifies the speech-rate setting is genuinely reaching the native
        // layer - the only automated check that TtsEngine.setSpeechRate does
        // anything audible.
        assertTrue(
            "Doubling speed changed duration by ${"%.2f".format(ratio)}x, expected ~2x",
            ratio > MIN_SPEED_RATIO && ratio < MAX_SPEED_RATIO,
        )
        assertTrue(
            "Speeding up produced silence",
            PcmAnalysis.rms(fast.samples) > MIN_SPEECH_RMS,
        )
    }

    @Test
    fun differentTextProducesDifferentAudio() {
        val first = synthesize(SENTENCE)
        val second = synthesize(VERY_DIFFERENT_SENTENCE)

        // Guards the degenerate failure this whole test class exists to rule
        // out: a pipeline that returns some fixed buffer - a canned tone, a
        // zero-filled block - regardless of what it was asked to say.
        assertNotEquals(
            "Two unrelated sentences produced byte-identical audio",
            first.samples.toList(),
            second.samples.toList(),
        )
        assertTrue(
            "Two sentences of very different length produced the same duration",
            kotlin.math.abs(first.samples.size - second.samples.size) > MIN_SAMPLE_COUNT_DIFFERENCE,
        )
    }

    @Test
    fun repeatedSynthesisOfTheSameTextIsStable() {
        val first = synthesize(SENTENCE)
        val second = synthesize(SENTENCE)

        val firstDuration = PcmAnalysis.durationSeconds(first.samples.size, first.sampleRate)
        val secondDuration = PcmAnalysis.durationSeconds(second.samples.size, second.sampleRate)
        val ratio = maxOf(firstDuration, secondDuration) / minOf(firstDuration, secondDuration)

        // VITS samples from a noise distribution, so the waveforms differ every
        // run - but the duration predictor is stable, so length should not.
        // Tolerance is loose because the variation is real, just bounded.
        assertTrue(
            "Same text synthesized twice differed in length by ${"%.2f".format(ratio)}x",
            ratio < MAX_REPEAT_DURATION_RATIO,
        )
        assertTrue(
            "Second synthesis of the same text was silent",
            PcmAnalysis.rms(second.samples) > MIN_SPEECH_RMS,
        )
    }

    // ── The streaming path the engine actually uses ──

    @Test
    fun streamingCallbackDeliversAudibleChunksCoveringTheWholeUtterance() {
        val chunks = mutableListOf<FloatArray>()
        // Deliberately the production callback type, not a lambda: a lambda
        // here would abort the process under CheckJNI rather than fail, and
        // this test is the thing that caught that in the first place.
        val audio = tts.generateWithConfigAndCallback(
            text = SENTENCE + " " + SECOND_SENTENCE,
            config = GenerationConfig(speed = 1.0f, sid = PocketVoiceCatalog.DEFAULT_SPEAKER_ID),
            callback = SherpaGenerationCallback { samples -> chunks += samples.copyOf() },
        )

        // PocketTtsEngine.generateChunks streams through this callback, so a
        // regression here (no chunks, empty chunks) would surface to the user
        // as a player that runs but stays silent.
        assertTrue("Streaming callback was never invoked", chunks.isNotEmpty())
        assertTrue("Streaming callback delivered an empty chunk", chunks.all { it.isNotEmpty() })

        val streamed = FloatArray(chunks.sumOf { it.size }).also { combined ->
            var offset = 0
            for (chunk in chunks) {
                chunk.copyInto(combined, offset)
                offset += chunk.size
            }
        }

        assertEquals(
            "Streamed chunks do not add up to the returned audio",
            audio.samples.size,
            streamed.size,
        )
        assertTrue(
            "Streamed audio is silent",
            PcmAnalysis.rms(streamed) > MIN_SPEECH_RMS,
        )
    }

    // ── Helpers ──

    private fun synthesize(text: String, speed: Float = 1.0f) = tts.generateWithConfig(
        text = text,
        config = GenerationConfig(speed = speed, sid = PocketVoiceCatalog.DEFAULT_SPEAKER_ID),
    )

    companion object {
        private const val ARM64_ABI = "arm64-v8a"

        /** The bundled LJSpeech Piper voice's native rate, per its `.onnx.json`. */
        private const val EXPECTED_SAMPLE_RATE = 22_050

        private const val SENTENCE = "The quick brown fox jumps over the lazy dog."
        private const val SECOND_SENTENCE = "Pack my box with five dozen liquor jugs."
        private const val VERY_DIFFERENT_SENTENCE = "Hello."
        private const val NUMERIC_SENTENCE = "Chapter 12 begins at 3:45 p.m."

        // Absolute floors - wide, tuned to catch total failure rather than to
        // characterize the voice.
        private const val MIN_SPEECH_RMS = 0.01
        private const val MIN_SPEECH_PEAK = 0.05
        private const val MAX_SILENT_FRACTION = 0.5
        private const val MAX_INTERNAL_SILENCE_SECONDS = 1.0
        private const val MIN_UTTERANCE_SECONDS = 0.5
        private const val MIN_CHARS_PER_SECOND = 5.0
        private const val MAX_CHARS_PER_SECOND = 40.0

        // Relative bands - the real regression signal.
        private const val MIN_LENGTH_RATIO = 1.4
        private const val MAX_LENGTH_RATIO = 3.0
        private const val MIN_SPEED_RATIO = 1.4
        private const val MAX_SPEED_RATIO = 2.6
        private const val MAX_REPEAT_DURATION_RATIO = 1.25
        private const val MIN_SAMPLE_COUNT_DIFFERENCE = 10_000

        private val lock = Any()
        private var sharedTts: OfflineTts? = null
        private var sharedModelPath: String? = null

        /**
         * Copies the bundled model out of assets exactly the way the app does
         * on first launch, so the asset-extraction path is covered on a real
         * filesystem too - `PocketModelManagerTest` only ever sees a fake
         * `AssetManager`.
         */
        private fun prepareModel(context: Context): String {
            val status = runBlocking { PocketModelManager(context).ensureModelAvailable().last() }
            assertTrue(
                "Model was not ready for synthesis: $status",
                status is ModelStatus.Ready,
            )
            return (status as ModelStatus.Ready).path
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            sharedTts?.release()
            sharedTts = null
            sharedModelPath = null
        }
    }
}
