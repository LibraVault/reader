package xyz.libravault.core.tts.pocket

import android.content.Context
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.libravault.core.tts.BuildConfig
import java.io.File
import java.nio.file.Path

class PocketModelManagerTest {

    @BeforeEach
    fun mockAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @Test
    fun `ModelStatus sealed class has expected types`() {
        val idle = ModelStatus.Idle
        val downloading = ModelStatus.Downloading(0.5f)
        val ready = ModelStatus.Ready("/path/to/model")
        val failed = ModelStatus.Failed("error message")

        assertNotNull(idle)
        assertNotNull(downloading)
        assertNotNull(ready)
        assertNotNull(failed)
    }

    @Test
    fun `Downloading status captures progress`() {
        val status = ModelStatus.Downloading(0.75f)
        assertEquals(0.75f, status.progress)
    }

    @Test
    fun `Ready status stores model path`() {
        val path = "/data/user/0/xyz.libravault.app/files/pocket-tts/model"
        val status = ModelStatus.Ready(path)
        assertEquals(path, status.path)
    }

    @Test
    fun `Failed status stores error message`() {
        val error = "Checksum mismatch"
        val status = ModelStatus.Failed(error)
        assertEquals(error, status.error)
    }

    @Test
    fun `SHA256 hash format is hex string`() {
        val hexPattern = Regex("^[a-f0-9]{64}$")
        val exampleHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertTrue(hexPattern.matches(exampleHash), "SHA256 should be 64 hex chars")
    }

    // ── isModelValid() / modelPathIfReady() ──────────────────────────────────

    @TempDir
    lateinit var tempDir: Path

    private fun manager(): PocketModelManager {
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir.toFile()
        return PocketModelManager(context)
    }

    private val modelDir: File
        get() = File(tempDir.toFile(), "pocket-tts/model")

    @Test
    fun `isModelValid is false when the model directory does not exist`() {
        assertFalse(manager().isModelValid())
    }

    @Test
    fun `isModelValid is false when the model directory is empty`() {
        modelDir.mkdirs()
        assertFalse(manager().isModelValid())
    }

    @Test
    fun `isModelValid is false when sha256 txt is missing`() {
        modelDir.mkdirs()
        File(modelDir, "model.onnx").writeText("stub")
        assertFalse(manager().isModelValid())
    }

    @Test
    fun `isModelValid is false when sha256 txt has a stale hash`() {
        modelDir.mkdirs()
        File(modelDir, "model.onnx").writeText("stub")
        File(modelDir, "sha256.txt").writeText("0000000000000000000000000000000000000000000000000000000000000000")
        assertFalse(manager().isModelValid())
    }

    @Test
    fun `isModelValid is true when sha256 txt matches the build-time hash`() {
        modelDir.mkdirs()
        File(modelDir, "model.onnx").writeText("stub")
        File(modelDir, "sha256.txt").writeText(BuildConfig.POCKET_TTS_MODEL_SHA256)
        assertTrue(manager().isModelValid())
    }

    @Test
    fun `isModelValid tolerates surrounding whitespace in sha256 txt`() {
        modelDir.mkdirs()
        File(modelDir, "model.onnx").writeText("stub")
        File(modelDir, "sha256.txt").writeText("\n${BuildConfig.POCKET_TTS_MODEL_SHA256}\n")
        assertTrue(manager().isModelValid())
    }

    @Test
    fun `modelPathIfReady returns null when the model is not ready`() {
        assertNull(manager().modelPathIfReady())
    }

    @Test
    fun `modelPathIfReady returns the model directory path once ready`() {
        modelDir.mkdirs()
        File(modelDir, "model.onnx").writeText("stub")
        File(modelDir, "sha256.txt").writeText(BuildConfig.POCKET_TTS_MODEL_SHA256)
        assertEquals(modelDir.absolutePath, manager().modelPathIfReady())
    }

    // ── extractTarBz2() ──────────────────────────────────────────────────────

    @Test
    fun `extractTarBz2 strips the single top-level release directory`() {
        val archive = File(tempDir.toFile(), "fixture.tar.bz2")
        writeFixtureTarBz2(
            archive,
            "vits-piper-en_US-ljspeech-medium-int8/en_US-ljspeech-medium.onnx" to "onnx-bytes",
            "vits-piper-en_US-ljspeech-medium-int8/tokens.txt" to "token-bytes",
            "vits-piper-en_US-ljspeech-medium-int8/espeak-ng-data/phontab" to "phontab-bytes",
        )

        val destination = File(tempDir.toFile(), "extracted").apply { mkdirs() }
        manager().extractTarBz2(archive, destination)

        assertEquals("onnx-bytes", File(destination, "en_US-ljspeech-medium.onnx").readText())
        assertEquals("token-bytes", File(destination, "tokens.txt").readText())
        assertEquals("phontab-bytes", File(destination, "espeak-ng-data/phontab").readText())
        assertFalse(
            File(destination, "vits-piper-en_US-ljspeech-medium-int8").exists(),
            "the wrapping release directory itself must not be materialized",
        )
    }

    /** Builds a minimal real .tar.bz2 so the extraction/strip logic (including the codec) is exercised end to end. */
    private fun writeFixtureTarBz2(destination: File, vararg entries: Pair<String, String>) {
        BZip2CompressorOutputStream(destination.outputStream()).use { bzOut ->
            TarArchiveOutputStream(bzOut).use { tarOut ->
                for ((name, content) in entries) {
                    val bytes = content.toByteArray()
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(bytes)
                    tarOut.closeArchiveEntry()
                }
            }
        }
    }
}
