package xyz.libravault.feature.reader.markdown

import android.net.Uri

/**
 * State of the SAF read → text pipeline for the Markdown reader.
 * Mirrors [xyz.libravault.feature.reader.epub.EpubPublicationState]'s shape — the
 * Markdown reader has no equivalent of Readium's [org.readium.r2.shared.publication.Publication]
 * object to hold, so [Ready] carries the raw Markdown source text directly.
 */
sealed class MarkdownPublicationState {

    /** Initial state before [xyz.libravault.feature.reader.markdown.MarkdownReaderViewModel.load] is called. */
    data object Idle : MarkdownPublicationState()

    /** File is being read from SAF. */
    data object Loading : MarkdownPublicationState()

    /** File read successfully; [text] is the raw Markdown source. */
    data class Ready(val uri: Uri, val text: String) : MarkdownPublicationState()

    /** Non-recoverable error reading the file. */
    data class Error(val message: String) : MarkdownPublicationState()
}
