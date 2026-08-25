package xyz.libravault.core.domain.model

/**
 * A single chapter/TOC entry shared across every narratable format's Read Aloud path
 * (#591 Phase 0). Markdown, EPUB, and PDF each currently produce their own unrelated
 * chapter shape ([xyz.libravault.feature.reader.markdown.MarkdownTtsChapter], Readium
 * `Link`/spine items, and nothing at all for PDF) — this is the one currency Phases 1–3
 * adapt each format onto, so the reader ViewModel and a future TOC sidebar only need to
 * deal with a single type.
 *
 * A book's full table of contents is just `List<ReaderChapter>`, in reading order — no
 * separate wrapper type, since a plain list already carries that and nothing here needs
 * more structure than "an ordered sequence of chapters" yet.
 *
 * [textProvider] is a suspend function rather than an eagerly-resolved [String]
 * deliberately: EPUB (Readium spine reads) and PDF (page text extraction, #591 Phase 3)
 * both need I/O per chapter, and building every chapter's full text up front would
 * defeat the point of chapter-based lazy loading Markdown's [index]-based navigation
 * already benefits from. Two [ReaderChapter]s are equal only if their [textProvider]
 * references are equal (data class equality falls back to identity for function types),
 * so equality is meaningful for chapters produced by the same call but not across two
 * independently-parsed TOCs — callers needing TOC-level equality should compare
 * [title]/[index] directly instead.
 *
 * Naming/shape intentionally stays close to iOS's existing
 * `BookChapter(title, text, blocks, images)`
 * (`ios/.../Sources/Models/BookContentProvider.swift`) so a future KMP convergence isn't
 * harder than it needs to be — but this phase introduces no code sharing with iOS (that's
 * separately blocked on the `java.time.Instant` KMP conversion, see PR #47 history).
 */
data class ReaderChapter(
    val title: String,
    val index: Int,
    val textProvider: suspend () -> String,
)
