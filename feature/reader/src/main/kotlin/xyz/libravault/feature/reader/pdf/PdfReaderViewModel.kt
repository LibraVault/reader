package xyz.libravault.feature.reader.pdf

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.libravault.core.domain.model.ContentSource
import xyz.libravault.core.vaultcontent.VaultMemfdFallback
import xyz.libravault.core.vaultcontent.VaultProxyFdHost
import xyz.libravault.core.vaultstore.VaultSessionManager
import xyz.libravault.core.vaultstore.hexToFileId
import javax.inject.Inject

/**
 * Resolves a [ContentSource] to a [ParcelFileDescriptor] for [PdfReaderScreen]'s
 * [android.graphics.pdf.PdfRenderer]. The first ViewModel `PdfReaderScreen` has
 * had — previously a bare composable, since it needed no Hilt-injected
 * resources until #505 added the vault-backed path.
 *
 * [ContentSource.VaultEntry] resolution is a verbatim port of what
 * `VaultPdfReaderScreen` (feature:vault, deleted by #505) did: proxy fd first
 * — validated on real hardware, decrypts lazily, no extra memory — falling
 * back to [VaultMemfdFallback] if the proxy fd fails on this device. Never
 * writes decrypted bytes to disk either way.
 */
@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // One host per ViewModel instance (i.e. per composition of PdfReaderScreen),
    // not per open — matches VaultProxyFdHost's own doc ("should be called
    // [close()] ... not per-file"). Created lazily so the plain-file path never
    // spins up its HandlerThread.
    private var vaultProxyFdHost: VaultProxyFdHost? = null

    suspend fun openFileDescriptor(source: ContentSource): ParcelFileDescriptor =
        withContext(Dispatchers.IO) {
            when (source) {
                is ContentSource.RealFile ->
                    appContext.contentResolver.openFileDescriptor(Uri.parse(source.uriString), "r")
                        ?: throw IllegalStateException("Could not open the PDF — file may be inaccessible.")

                is ContentSource.VaultEntry -> {
                    val store = sessionManager.requireUnlocked(source.vaultId)
                    val reader = store.openReader(source.fileIdHex.hexToFileId())
                    val host = vaultProxyFdHost ?: VaultProxyFdHost(appContext).also { vaultProxyFdHost = it }
                    runCatching { host.open(reader) }.getOrElse { VaultMemfdFallback.open(reader) }
                }
            }
        }

    override fun onCleared() {
        super.onCleared()
        vaultProxyFdHost?.close()
    }
}
