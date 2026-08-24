package xyz.libravault.feature.reader.markdown

import androidx.documentfile.provider.DocumentFile
import xyz.libravault.core.domain.model.ContentSource

/**
 * State of the read → text pipeline for the Markdown reader.
 * Mirrors [xyz.libravault.feature.reader.epub.EpubPublicationState]'s shape — the
 * Markdown reader has no equivalent of Readium's [org.readium.r2.shared.publication.Publication]
 * object to hold, so [Ready] carries the raw Markdown source text directly.
 */
sealed class MarkdownPublicationState {

    /** Initial state before [xyz.libravault.feature.reader.markdown.MarkdownReaderViewModel.load] is called. */
    data object Idle : MarkdownPublicationState()

    /** File is being read (SAF or, since #505, an Encrypted Vault entry). */
    data object Loading : MarkdownPublicationState()

    /**
     * File read successfully; [text] is the raw Markdown source. [assetParentDirectory]
     * is the file's own containing directory (for relative image resolution — see
     * MarkdownAssetResolver); null if there's no vault association to resolve it from
     * (including all [ContentSource.VaultEntry] content — see #505's ReaderUiState note),
     * or resolution failed.
     */
    data class Ready(val source: ContentSource, val text: String, val assetParentDirectory: DocumentFile?) : MarkdownPublicationState()

    /** Non-recoverable error reading the file. */
    data class Error(val message: String) : MarkdownPublicationState()
}
