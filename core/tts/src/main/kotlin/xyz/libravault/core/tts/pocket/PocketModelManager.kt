package xyz.libravault.core.tts.pocket

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.libravault.core.tts.BuildConfig
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

    /**
     * Downloads and extracts the bundled Pocket TTS voice model if it isn't
     * already present, using the URL/checksum baked in at build time
     * (see core/tts/build.gradle.kts). Safe to call every time the engine
     * initializes - a valid on-disk copy short-circuits straight to [ModelStatus.Ready].
     */
    fun ensureModelAvailable(): Flow<ModelStatus> = flow {
        emit(ModelStatus.Idle)

        if (isModelValid()) {
            Log.d(TAG, "Model already available at ${modelDir.absolutePath}")
            emit(ModelStatus.Ready(modelDir.absolutePath))
            return@flow
        }

        val expectedSha256 = BuildConfig.POCKET_TTS_MODEL_SHA256

        try {
            modelDir.deleteRecursively()
            modelDir.mkdirs()
            val tempFile = File(modelDir, "model.tar.bz2.tmp")

            emit(ModelStatus.Downloading(0f))

            downloadFile(BuildConfig.POCKET_TTS_MODEL_URL, tempFile) { progress ->
                Log.d(TAG, "Download progress: $progress")
            }

            val actualSha256 = calculateSha256(tempFile)
            if (actualSha256 != expectedSha256) {
                tempFile.delete()
                emit(ModelStatus.Failed("Checksum mismatch: expected $expectedSha256, got $actualSha256"))
                return@flow
            }

            emit(ModelStatus.Downloading(1.0f))

            extractTarBz2(tempFile, modelDir)
            tempFile.delete()

            // Record the verified hash so isModelValid() recognizes this
            // install on future launches without re-downloading.
            File(modelDir, "sha256.txt").writeText(expectedSha256)

            Log.d(TAG, "Model ready at ${modelDir.absolutePath}")
            emit(ModelStatus.Ready(modelDir.absolutePath))
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed: ${e.message}", e)
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

    private suspend fun downloadFile(url: String, destination: File, onProgress: (Float) -> Unit) {
        try {
            val okHttpClient = okhttp3.OkHttpClient()
            val request = okhttp3.Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw Exception("Download failed: HTTP ${response.code}")
            }

            val contentLength = response.body?.contentLength() ?: -1L
            var downloadedBytes = 0L

            destination.outputStream().use { fileOut ->
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        fileOut.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (contentLength > 0) {
                            onProgress(downloadedBytes.toFloat() / contentLength)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}", e)
            throw e
        }
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

    /**
     * Extracts a .tar.bz2 archive, stripping the single top-level directory
     * every sherpa-onnx model release wraps its files in (e.g.
     * `vits-piper-en_US-ljspeech-medium-int8/model.onnx` -> `model.onnx`)
     * so callers can reference files directly under [modelDir].
     *
     * Package-visible so the strip/extract logic can be unit-tested with a
     * small fixture archive.
     */
    internal fun extractTarBz2(archiveFile: File, destination: File) {
        try {
            val bzip2InputStream = org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(
                archiveFile.inputStream(),
            )
            val tarInputStream = org.apache.commons.compress.archivers.tar.TarArchiveInputStream(bzip2InputStream)

            var entry = tarInputStream.nextTarEntry
            while (entry != null) {
                val strippedName = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (strippedName.isNotEmpty()) {
                    val entryFile = File(destination, strippedName)

                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        entryFile.outputStream().use { output ->
                            tarInputStream.copyTo(output)
                        }
                    }
                }

                entry = tarInputStream.nextTarEntry
            }

            tarInputStream.close()
            bzip2InputStream.close()

            Log.d(TAG, "Extracted ${archiveFile.name} to ${destination.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction error: ${e.message}", e)
            throw Exception("Failed to extract model: ${e.message}", e)
        }
    }
}
