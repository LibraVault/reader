package xyz.libravault.core.tts.pocket

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.libravault.core.tts.BuildConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PocketModelManager"
private const val MODEL_DIR_NAME = "pocket-tts/model"

/** Root of the bundled voice model within the APK's assets. */
internal const val ASSET_MODEL_DIR = "pocket-tts-model"

sealed class ModelStatus {
    object Idle : ModelStatus()
    data class Preparing(val progress: Float) : ModelStatus()
    data class Ready(val path: String) : ModelStatus()
    data class Failed(val error: String) : ModelStatus()
}

@Singleton
class PocketModelManager @Inject constructor(
    private val context: Context,
) {

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    /**
     * Copies the Pocket TTS voice model bundled into the APK's assets (see
     * `third-party/sherpa-onnx/setup-android-model.sh`) into app-private
     * storage on first use - sherpa-onnx's native code needs a real
     * filesystem path, not an APK-internal asset path. Safe to call every
     * time the engine initializes: a valid on-disk copy short-circuits
     * straight to [ModelStatus.Ready].
     *
     * No network involved. The model ships inside the app itself on both
     * Play and F-Droid, the same way iOS bundles it at build time - see
     * SHERPA_ONNX_SETUP.md. This used to download the model on-device
     * instead; that path required the Play-only INTERNET permission and
     * meant F-Droid couldn't offer Pocket TTS at all.
     */
    fun ensureModelAvailable(): Flow<ModelStatus> = flow {
        emit(ModelStatus.Idle)

        if (isModelValid()) {
            Log.d(TAG, "Model already available at ${modelDir.absolutePath}")
            emit(ModelStatus.Ready(modelDir.absolutePath))
            return@flow
        }

        try {
            modelDir.deleteRecursively()
            modelDir.mkdirs()

            emit(ModelStatus.Preparing(0f))
            copyModelAssets { progress -> emit(ModelStatus.Preparing(progress)) }

            // Record the bundled model's version so isModelValid() recognizes
            // this install on future launches without re-copying, and so an
            // app update that ships a different model version re-copies.
            File(modelDir, "sha256.txt").writeText(BuildConfig.POCKET_TTS_MODEL_SHA256)

            Log.d(TAG, "Model ready at ${modelDir.absolutePath}")
            emit(ModelStatus.Ready(modelDir.absolutePath))
        } catch (e: Exception) {
            Log.e(TAG, "Model setup failed: ${e.message}", e)
            modelDir.deleteRecursively()
            emit(ModelStatus.Failed(e.message ?: "Unknown error"))
        }
    }

    /** True if a verified copy of the current build's model is already on disk. */
    fun isModelValid(): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) return false

        val modelFiles = modelDir.listFiles() ?: return false
        if (modelFiles.isEmpty()) return false

        val hashFile = File(modelDir, "sha256.txt")
        if (!hashFile.exists()) return false

        return try {
            hashFile.readText().trim() == BuildConfig.POCKET_TTS_MODEL_SHA256
        } catch (e: Exception) {
            Log.e(TAG, "Could not read hash file: ${e.message}")
            false
        }
    }

    /** Absolute path to the extracted model directory, or null if not ready yet. */
    fun modelPathIfReady(): String? = if (isModelValid()) modelDir.absolutePath else null

    /**
     * Recursively copies the bundled `assets/pocket-tts-model/` tree into
     * [modelDir], reporting fraction-complete by file count. Package-visible
     * so the copy logic can be unit-tested against a fake [AssetManager].
     */
    internal suspend fun copyModelAssets(onProgress: suspend (Float) -> Unit = {}) {
        val assetManager = context.assets

        // AssetManager.list() can't distinguish "this path doesn't exist" from
        // "this path is a file" - both return an empty array - so this is the
        // only point where "missing entirely" is checkable: ASSET_MODEL_DIR is
        // always a directory when the setup script has been run, so an empty
        // listing here specifically means it hasn't been.
        if (assetManager.list(ASSET_MODEL_DIR).isNullOrEmpty()) {
            throw IllegalStateException(
                "No files found under assets/$ASSET_MODEL_DIR - was " +
                    "setup-android-model.sh run before building?",
            )
        }

        val assetPaths = collectAssetFiles(assetManager, ASSET_MODEL_DIR)
        assetPaths.forEachIndexed { index, assetPath ->
            val relativePath = assetPath.removePrefix("$ASSET_MODEL_DIR/")
            val destFile = File(modelDir, relativePath)
            destFile.parentFile?.mkdirs()
            assetManager.open(assetPath).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            onProgress((index + 1).toFloat() / assetPaths.size)
        }
    }

    /**
     * [AssetManager.list] returns child names for a directory and an empty
     * array for a file - there's no direct "is this a file" API - so a leaf
     * is identified by an empty listing.
     */
    private fun collectAssetFiles(assetManager: AssetManager, path: String): List<String> {
        val children = assetManager.list(path)
        return if (children.isNullOrEmpty()) {
            listOf(path)
        } else {
            children.flatMap { collectAssetFiles(assetManager, "$path/$it") }
        }
    }
}
