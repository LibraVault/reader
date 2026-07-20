package xyz.libravault.core.tts.pocket

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PocketModelManager"
private const val MODEL_DIR_NAME = "pocket-tts/model"

sealed class ModelStatus {
    object Idle : ModelStatus()
    data class Downloading(val progress: Float) : ModelStatus()
    data class Ready(val path: String) : ModelStatus()
    data class Failed(val error: String) : ModelStatus()
}

@Singleton
class PocketModelManager @Inject constructor(
    private val context: Context,
) {

    private val modelDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME)

    fun ensureModelAvailable(modelUrl: String, expectedSha256: String): Flow<ModelStatus> = flow {
        emit(ModelStatus.Idle)

        // Check if model already exists with correct hash
        if (isModelValid(expectedSha256)) {
            Log.d(TAG, "Model already available at ${modelDir.absolutePath}")
            emit(ModelStatus.Ready(modelDir.absolutePath))
            return@flow
        }

        try {
            modelDir.mkdirs()
            val tempFile = File(modelDir, "model.tar.gz.tmp")

            emit(ModelStatus.Downloading(0f))

            // Download with progress tracking
            downloadFile(modelUrl, tempFile) { progress ->
                Log.d(TAG, "Download progress: $progress")
            }

            // Verify checksum
            val actualSha256 = calculateSha256(tempFile)
            if (actualSha256 != expectedSha256) {
                tempFile.delete()
                emit(ModelStatus.Failed("Checksum mismatch: expected $expectedSha256, got $actualSha256"))
                return@flow
            }

            emit(ModelStatus.Downloading(1.0f))

            // Extract tarball
            extractTarGz(tempFile, modelDir)
            tempFile.delete()

            Log.d(TAG, "Model ready at ${modelDir.absolutePath}")
            emit(ModelStatus.Ready(modelDir.absolutePath))
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}", e)
            emit(ModelStatus.Failed(e.message ?: "Unknown error"))
        }
    }

    private fun isModelValid(expectedSha256: String): Boolean {
        if (!modelDir.exists() || !modelDir.isDirectory) return false

        // Check if essential model files exist and hash matches
        val modelFiles = modelDir.listFiles() ?: return false
        if (modelFiles.isEmpty()) return false

        val hashFile = File(modelDir, "sha256.txt")
        if (!hashFile.exists()) return false

        return try {
            val storedHash = hashFile.readText().trim()
            storedHash == expectedSha256
        } catch (e: Exception) {
            Log.e(TAG, "Could not read hash file: ${e.message}")
            false
        }
    }

    private suspend fun downloadFile(url: String, destination: File, onProgress: (Float) -> Unit) {
        // TODO: Implement HTTP download using java.net.URL or equivalent
        // For now, this is a placeholder that will be connected to actual HTTP client
        Log.d(TAG, "TODO: Download model from $url to ${destination.absolutePath}")
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractTarGz(tarFile: File, destination: File) {
        // TODO: Implement tar.gz extraction (or use external library)
        // For now, placeholder that logs intent
        Log.d(TAG, "TODO: Extract $tarFile to ${destination.absolutePath}")
        // In production, use a tar library like commons-compress
    }
}
