package xyz.libravault.core.tts.pocket

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.libravault.core.tts.BuildConfig
import java.io.ByteArrayInputStream
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
        val preparing = ModelStatus.Preparing(0.5f)
        val ready = ModelStatus.Ready("/path/to/model")
        val failed = ModelStatus.Failed("error message")

        assertNotNull(idle)
        assertNotNull(preparing)
        assertNotNull(ready)
        assertNotNull(failed)
    }

    @Test
    fun `Preparing status captures progress`() {
        val status = ModelStatus.Preparing(0.75f)
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

    private fun manager(assetManager: AssetManager = mockk()): PocketModelManager {
        val context = mockk<Context>()
        every { context.filesDir } returns tempDir.toFile()
        every { context.assets } returns assetManager
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

    // ── copyModelAssets() ────────────────────────────────────────────────────

    /**
     * Builds a fake [AssetManager] over an in-memory tree, mirroring
     * `assets/pocket-tts-model/`'s real shape (a couple of top-level files
     * plus a nested `espeak-ng-data/` directory). [AssetManager.list] must
     * return an empty array (not null) for a file path and the child names
     * for a directory path - real devices behave that way, and
     * `PocketModelManager`'s private path-classification logic relies on it
     * to tell files from directories.
     */
    private fun fakeAssetManager(files: Map<String, String>): AssetManager {
        val assetManager = mockk<AssetManager>()
        val childrenByDir = mutableMapOf<String, MutableSet<String>>()
        for (path in files.keys) {
            var current = path
            while (current.contains('/')) {
                val parent = current.substringBeforeLast('/')
                val child = current.substringAfterLast('/')
                childrenByDir.getOrPut(parent) { mutableSetOf() }.add(child)
                current = parent
            }
        }
        for ((dir, children) in childrenByDir) {
            every { assetManager.list(dir) } returns children.toTypedArray()
        }
        for (path in files.keys) {
            every { assetManager.list(path) } returns emptyArray()
        }
        for ((path, content) in files) {
            every { assetManager.open(path) } answers { ByteArrayInputStream(content.toByteArray()) }
        }
        return assetManager
    }

    @Test
    fun `copyModelAssets copies every file into the model directory, stripping the assets root`() = runTest {
        val assetManager = fakeAssetManager(
            mapOf(
                "$ASSET_MODEL_DIR/model.onnx" to "onnx-bytes",
                "$ASSET_MODEL_DIR/tokens.txt" to "token-bytes",
                "$ASSET_MODEL_DIR/espeak-ng-data/phontab" to "phontab-bytes",
            ),
        )

        manager(assetManager).copyModelAssets()

        assertEquals("onnx-bytes", File(modelDir, "model.onnx").readText())
        assertEquals("token-bytes", File(modelDir, "tokens.txt").readText())
        assertEquals("phontab-bytes", File(modelDir, "espeak-ng-data/phontab").readText())
    }

    @Test
    fun `copyModelAssets reports progress from 0 up to 1`() = runTest {
        val assetManager = fakeAssetManager(
            mapOf(
                "$ASSET_MODEL_DIR/a" to "a",
                "$ASSET_MODEL_DIR/b" to "b",
            ),
        )
        val progressUpdates = mutableListOf<Float>()

        manager(assetManager).copyModelAssets { progressUpdates.add(it) }

        assertEquals(listOf(0.5f, 1.0f), progressUpdates)
    }

    @Test
    fun `copyModelAssets fails loudly when the assets root is empty`() = runTest {
        val assetManager = mockk<AssetManager>()
        every { assetManager.list(ASSET_MODEL_DIR) } returns emptyArray()

        try {
            manager(assetManager).copyModelAssets()
            fail<Unit>("expected copyModelAssets to throw")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("setup-android-model.sh"))
        }
    }
}
