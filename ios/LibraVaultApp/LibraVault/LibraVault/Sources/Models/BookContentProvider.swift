import Foundation
import PDFKit

struct BookChapter {
    let title: String
    let text: String
    /// Structured blocks for this chapter, reusing `MarkdownBlock`/`MarkdownInlineRun`
    /// (see MarkdownDocumentParser.swift) — the same shape EPUBParser.parseBlocks
    /// (#356) produces from a chapter's XHTML. Empty for parsers not yet wired up to
    /// the block model (PDF, MarkdownDocumentParser.chaptersForNarration) — `text`
    /// stays the source of truth for those, and for bookmarks/TOC (#361) on every
    /// format until that's ported too.
    let blocks: [MarkdownBlock]
    /// This chapter's `<img>` asset bytes, keyed by the raw (unresolved) `src` exactly
    /// as `blocks`' `.image` cases reference it — mirrors how `ReaderView.markdownImages`
    /// keys Markdown's own image dictionary. Resolved and read eagerly at parse time
    /// (see EPUBParser.parse), not lazily during rendering, since that needs the
    /// archive/security-scoped access which is only held open for the duration of the
    /// parse call, not the reader's lifetime. Empty when blocks has no `.image` cases,
    /// or every reference failed to resolve (broken/missing asset — skipped per-image
    /// rather than failing the whole chapter, mirroring markdownAssetData's per-image
    /// failure handling in ReaderView.loadMarkdownImages).
    let images: [String: Data]

    init(title: String, text: String, blocks: [MarkdownBlock] = [], images: [String: Data] = [:]) {
        self.title = title
        self.text = text
        self.blocks = blocks
        self.images = images
    }
}

/// Loads real chapter content for a book from its backing file — the only source of
/// reading content in the app (EPUB, PDF; mobi/cbz/audio throw `.unsupportedFormat`
/// so callers can show an honest "not supported" state). Re-resolves the folder's
/// security-scoped bookmark around the read, since LibraryFileScanner only holds
/// scope briefly during the scan itself, not for the lifetime of the app.
enum BookContentProvider {
    enum ContentError: Error, Equatable {
        case unsupportedFormat
        case missingFileReference
        case folderUnavailable
    }

    /// Whether `chapters(for:)` has a parser for this format. Exposed so callers can
    /// gate *before* starting work that only makes sense for a narratable book — see
    /// AppState.startPlayback — instead of inferring it from a thrown error, which is
    /// indistinguishable from "right format, unreadable file".
    ///
    /// Markdown included since #124 — MarkdownDocumentParser.chaptersForNarration
    /// converts its parsed blocks into the same [BookChapter] currency EPUB/PDF
    /// already narrate through, so the rest of AppState's TTS pipeline needs no
    /// changes, only a source of chapters for a third format. `true` here does NOT
    /// guarantee a non-empty result, though — an image-only Markdown file parses fine
    /// but has nothing speakable; AppState.startPlayback guards that case separately
    /// (an empty, non-nil chapters array), since it isn't a realistic case for
    /// EPUB/PDF the way it is for Markdown.
    static func supportsChapterParsing(_ format: MediaFormat) -> Bool {
        format == .epub || format == .pdf || format == .markdown
    }

    static func chapters(for book: BookItem, folderPersistence: FolderPersistence = FolderPersistence()) throws -> [BookChapter] {
        guard supportsChapterParsing(book.format) else {
            throw ContentError.unsupportedFormat
        }
        return try withSecurityScopedAccess(for: book, folderPersistence: folderPersistence) { fileURL in
            switch book.format {
            case .epub: return try EPUBParser.parse(fileURL: fileURL)
            case .pdf: return try PDFParser.parse(fileURL: fileURL)
            case .markdown:
                let source = try String(contentsOf: fileURL, encoding: .utf8)
                let blocks = MarkdownDocumentParser.parse(source)
                return MarkdownDocumentParser.chaptersForNarration(from: blocks)
            default: throw ContentError.unsupportedFormat
            }
        }
    }

    /// Raw Markdown source for the Markdown viewer. Parsing into renderable blocks
    /// happens separately (see MarkdownDocumentParser) — this stays a plain file read
    /// so it mirrors chapters(for:)'s folder-resolution shape without depending on it.
    static func markdownSource(for book: BookItem, folderPersistence: FolderPersistence = FolderPersistence()) throws -> String {
        guard book.format == .markdown else {
            throw ContentError.unsupportedFormat
        }
        return try withSecurityScopedAccess(for: book, folderPersistence: folderPersistence) { fileURL in
            try String(contentsOf: fileURL, encoding: .utf8)
        }
    }

    /// Resolves a relative image reference (`./img.png`, `images/pic.jpg`,
    /// `../shared/x.png`) against the Markdown file's own location and reads its
    /// bytes — called eagerly once per image at document-load time (see
    /// ReaderView.loadContent), not lazily during rendering, since the security-scoped
    /// access this needs is only held open for the duration of this call, not for the
    /// lifetime of the view. Never resolves absolute http(s) URLs — LibraVault is
    /// offline-first and doesn't request the `INTERNET`-equivalent network entitlement.
    static func markdownAssetData(
        for book: BookItem,
        relativePath: String,
        folderPersistence: FolderPersistence = FolderPersistence()
    ) throws -> Data {
        guard book.format == .markdown else {
            throw ContentError.unsupportedFormat
        }
        if relativePath.lowercased().hasPrefix("http://") || relativePath.lowercased().hasPrefix("https://") {
            throw ContentError.unsupportedFormat
        }
        return try withSecurityScopedAccess(for: book, folderPersistence: folderPersistence) { fileURL in
            guard let resolvedURL = URL(string: relativePath, relativeTo: fileURL)?.standardizedFileURL else {
                throw ContentError.missingFileReference
            }
            return try Data(contentsOf: resolvedURL)
        }
    }

    /// Opens a PDF for on-screen page rendering (PDFReaderContent's PDFView), not text
    /// extraction — unlike chapters(for:)/markdownSource(for:), which read a file's
    /// full content upfront and can release the folder's security scope immediately,
    /// PDFKit's PDFView lazily rereads page data from disk as the user pages/scrolls,
    /// so scope must stay open for as long as the reader is displaying the document.
    /// Returns the opened document plus an `endAccess` closure the caller must invoke
    /// exactly once (e.g. ReaderView's onDisappear) to release that held-open scope.
    static func openPDFDocument(
        for book: BookItem,
        folderPersistence: FolderPersistence = FolderPersistence()
    ) throws -> (document: PDFDocument, endAccess: () -> Void) {
        guard book.format == .pdf else {
            throw ContentError.unsupportedFormat
        }
        let (fileURL, folderURL) = try resolveFileAndFolderURL(for: book, folderPersistence: folderPersistence)

        let didStartAccessing = folderURL.startAccessingSecurityScopedResource()
        let endAccess: () -> Void = {
            if didStartAccessing { folderURL.stopAccessingSecurityScopedResource() }
        }

        guard let document = PDFDocument(url: fileURL) else {
            endAccess()
            throw PDFParser.ParseError.invalidDocument
        }
        guard document.pageCount > 0 else {
            endAccess()
            throw PDFParser.ParseError.emptyDocument
        }
        return (document, endAccess)
    }

    /// Resolves the book's folder security-scoped bookmark and holds the scope open
    /// for the duration of `body`. Shared by chapters(for:) and markdownSource(for:)
    /// since both need the same "resolve folder → start scope → read file" sequence.
    private static func withSecurityScopedAccess<T>(
        for book: BookItem,
        folderPersistence: FolderPersistence,
        _ body: (URL) throws -> T
    ) throws -> T {
        let (fileURL, folderURL) = try resolveFileAndFolderURL(for: book, folderPersistence: folderPersistence)

        let didStartAccessing = folderURL.startAccessingSecurityScopedResource()
        defer { if didStartAccessing { folderURL.stopAccessingSecurityScopedResource() } }

        return try body(fileURL)
    }

    /// Shared by withSecurityScopedAccess and openPDFDocument — the latter can't use
    /// withSecurityScopedAccess's own scope handling since it needs the scope to
    /// outlive this call, not just the body closure.
    private static func resolveFileAndFolderURL(
        for book: BookItem,
        folderPersistence: FolderPersistence
    ) throws -> (fileURL: URL, folderURL: URL) {
        guard let fileURL = book.fileURL, let folderId = book.folderId else {
            throw ContentError.missingFileReference
        }
        guard let folder = folderPersistence.loadFolders().first(where: { $0.id == folderId }),
              let resolvedFolderURL = folderPersistence.resolvedURL(for: folder) else {
            throw ContentError.folderUnavailable
        }
        return (fileURL, resolvedFolderURL)
    }
}
