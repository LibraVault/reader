package xyz.libravault.feature.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Publication
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultstore.VaultSessionManager
import javax.inject.Inject

sealed class VaultReaderState {
    object Loading : VaultReaderState()
    data class Error(val message: String) : VaultReaderState()
    data class EpubReady(val title: String, val publication: Publication) : VaultReaderState()
    /** [reader] stays open for as long as this state is current — the PDF
     * screen reads from it directly (see [VaultReaderViewModel.pdfReader]),
     * unlike EPUB where Readium owns the read lifecycle once [Publication]
     * exists. */
    data class PdfReady(val title: String) : VaultReaderState()
    /** Not a reading format — the caller should have routed audio to
     * [VaultPlayerScreen] instead; surfaced here only as a defensive
     * fallback if that dispatch is ever wrong. */
    data class WrongScreen(val message: String) : VaultReaderState()
}

/**
 * Opens one vault file for reading — EPUB via [VaultReadiumProvider], PDF via
 * the [VaultFileReader] directly (the PDF screen builds its own
 * `ParcelFileDescriptor` from it via `core:vaultcontent`'s proxy-fd/memfd
 * adapters). No bookmarks, highlights, or reading-progress persistence in
 * this pass — [xyz.libravault.core.vaultstore.VaultManifestEntry] has no
 * progress field yet, and highlights/bookmarks UI is deliberately out of
 * scope (see the PR description).
 */
@HiltViewModel
class VaultReaderViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    private val readiumProvider: VaultReadiumProvider,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle["vaultId"]) { "VaultReaderScreen requires a vaultId nav argument" }
    private val fileIdHex: String = checkNotNull(savedStateHandle["fileId"]) { "VaultReaderScreen requires a fileId nav argument" }
    private val fileId: ByteArray = fileIdHex.hexToFileId()

    private val _state = MutableStateFlow<VaultReaderState>(VaultReaderState.Loading)
    val state: StateFlow<VaultReaderState> = _state.asStateFlow()

    private var reader: VaultFileReader? = null
    private var publication: Publication? = null

    /** Only valid once [state] is [VaultReaderState.PdfReady]. */
    fun pdfReader(): VaultFileReader = requireNotNull(reader) { "PDF reader requested before it was opened" }

    init {
        viewModelScope.launch {
            if (!sessionManager.isUnlocked(vaultId)) {
                _state.value = VaultReaderState.Error("Vault is locked")
                return@launch
            }
            val store = sessionManager.requireUnlocked(vaultId)
            val entry = store.listEntries().find { it.fileId.contentEquals(fileId) }
            if (entry == null) {
                _state.value = VaultReaderState.Error("File not found in this vault")
                return@launch
            }
            if (entry.format in VAULT_AUDIO_FORMAT_NAMES) {
                _state.value = VaultReaderState.WrongScreen("This is an audio file — open it from the player instead")
                return@launch
            }

            val r = store.openReader(fileId)
            reader = r
            when (entry.format) {
                "EPUB" -> readiumProvider.open(r, fileIdHex).fold(
                    onSuccess = { pub -> publication = pub; _state.value = VaultReaderState.EpubReady(entry.title, pub) },
                    onFailure = { e -> _state.value = VaultReaderState.Error(e.message ?: "Could not open EPUB") },
                )
                "PDF" -> _state.value = VaultReaderState.PdfReady(entry.title)
                else -> _state.value = VaultReaderState.Error("Unsupported format: ${entry.format}")
            }
        }
    }

    override fun onCleared() {
        publication?.close()
        reader?.close()
    }
}
