import Foundation
import ZIPFoundation

/// One inline text run with its accumulated styling — the EPUB-side counterpart of
/// `MarkdownInlineRun` (`MarkdownDocumentParser.swift`). Kept as a separate type
/// rather than reusing `MarkdownInlineRun` directly: this file has no dependency on
/// the `Markdown` package today, and XHTML's inline vocabulary (`<b>`/`<strong>`,
/// `<i>`/`<em>`) is different from CommonMark's AST, so there is nothing to share
/// beyond the shape.
struct EPUBInlineRun: Equatable {
    let text: String
    let bold: Bool
    let italic: Bool
}

/// A block-level element in one EPUB chapter's parsed XHTML — the EPUB-side
/// counterpart of `MarkdownBlock` (see `MarkdownDocumentParser.swift`), intentionally
/// scoped to what real EPUB chapter markup actually uses and #356 asks for: headings,
/// paragraphs, lists, images. `image(url:altText:)` deliberately matches
/// `MarkdownBlock.image`'s shape exactly (raw `src`, unresolved) so a later renderer
/// (#360) can share `MarkdownBlockView`'s existing image-resolution behaviour rather
/// than reimplementing it.
enum EPUBBlock: Equatable {
    case heading(level: Int, text: [EPUBInlineRun])
    case paragraph(text: [EPUBInlineRun])
    case unorderedList(items: [[EPUBInlineRun]])
    /// Unlike `MarkdownBlock.orderedList`, there is no `start` index — an XHTML
    /// `<ol start="…">` is rare in EPUB chapter content, and nothing in #356's
    /// acceptance criteria exercises it. Add it if a real book needs it.
    case orderedList(items: [[EPUBInlineRun]])
    /// `url` is the raw `src` attribute exactly as written in the XHTML — unresolved
    /// against the archive/base directory. Resolving it to actual image bytes is
    /// out of scope here (see #357).
    case image(url: String, altText: String)
}

/// Parses a real EPUB file's spine into ordered, plain-text chapters. EPUB is a ZIP
/// archive containing a `META-INF/container.xml` pointer to an OPF package document,
/// whose `<manifest>` maps ids to content-document hrefs and whose `<spine>` lists
/// those ids in reading order — see the EPUB 3 Packages spec.
///
/// `NSAttributedString`'s HTML parsing (used below to turn each chapter's XHTML into
/// plain text) must run on the main thread — callers should not move this work to a
/// background queue/task.
enum EPUBParser {
    enum ParseError: Error, Equatable {
        case invalidArchive
        case entryNotFound(String)
        case malformedContainer
        case malformedOPF
    }

    static func parse(fileURL: URL) throws -> [BookChapter] {
        let archive = try openArchive(at: fileURL)

        let containerData = try extract("META-INF/container.xml", from: archive)
        let opfPath = try parseContainer(containerData)

        let opfData = try extract(opfPath, from: archive)
        let opfBaseDirectory = (opfPath as NSString).deletingLastPathComponent
        let spineHrefs = try parseOPF(opfData, baseDirectory: opfBaseDirectory)

        return spineHrefs.enumerated().map { index, href in
            let html = (try? extractSpineItem(href, from: archive)) ?? Data()
            let text = plainText(fromHTML: html)
            let title = chapterTitle(fromHTML: html, fallback: "Chapter \(index + 1)")
            return BookChapter(title: title, text: text)
        }
    }

    // MARK: - Archive access

    private static func openArchive(at fileURL: URL) throws -> Archive {
        do {
            return try Archive(url: fileURL, accessMode: .read)
        } catch {
            throw ParseError.invalidArchive
        }
    }

    /// The href-vs-entry-name gap `extractSpineItem` handles applies to
    /// `container.xml`'s `full-path` too — a percent-encoded OPF path such as
    /// `OEBPS%202/content.opf` would fail the *whole book*, not just one page — so
    /// path resolution runs here as well. The unique-filename fallback deliberately
    /// does not: `container.xml` and the OPF are addressed by exact location, and
    /// guessing at them would turn a clearly-malformed archive into a confusing one.
    private static func extract(_ path: String, from archive: Archive) throws -> Data {
        guard let entry = firstEntry(for: path, in: archive) else { throw ParseError.entryNotFound(path) }
        return try read(entry, from: archive)
    }

    /// First archive entry matching any of `path`'s resolved spellings.
    private static func firstEntry(for path: String, in archive: Archive) -> Entry? {
        for candidate in resolveEntryPath(path) {
            if let entry = archive[candidate] { return entry }
        }
        return nil
    }

    /// Reads a spine content document, tolerating the gap between how an href is
    /// *spelled* in the OPF and how the entry is *named* in the ZIP. `archive[path]`
    /// is an exact string match, so a spine item whose href doesn't literally equal
    /// its entry name used to come back as empty `Data()` and render as a completely
    /// blank page (issue #108). Tries each `resolveEntryPath` candidate (normalised
    /// and percent-decoded forms, plus the literal spelling), then falls back to a
    /// filename match against the archive's entries.
    private static func extractSpineItem(_ href: String, from archive: Archive) throws -> Data {
        if let entry = firstEntry(for: href, in: archive) {
            return try read(entry, from: archive)
        }

        // Some producers write hrefs that don't correspond to the entry's directory at
        // all (flattened archives, mismatched OPF base). Matching on the filename alone
        // is ambiguous in principle, so only accept it when exactly one entry matches.
        let filename = (resolveEntryPath(href).first.map { ($0 as NSString).lastPathComponent }) ?? href
        if !filename.isEmpty {
            let matches = archive.filter { ($0.path as NSString).lastPathComponent == filename }
            if matches.count == 1, let entry = matches.first {
                return try read(entry, from: archive)
            }
        }

        throw ParseError.entryNotFound(href)
    }

    /// Candidate ZIP entry paths for an OPF href, most-canonical first.
    ///
    /// OPF hrefs are IRIs, so `Text/chapter%2008.xhtml` is the correct spelling of an
    /// entry stored as `Text/chapter 08.xhtml`, and `../Text/foo.xhtml` under an
    /// `OEBPS/`-rooted OPF has already been joined to `OEBPS/../Text/foo.xhtml` by the
    /// caller. Fragments and queries are addressing *within* a document and are never
    /// part of the entry name. Both the decoded and raw forms are returned because an
    /// entry name may itself legitimately contain a `%`.
    static func resolveEntryPath(_ href: String) -> [String] {
        // omittingEmptySubsequences: false so a bare "#frag" yields "" rather than
        // silently promoting the fragment itself to the path.
        let withoutFragment = href.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false)[0]
        let withoutQuery = withoutFragment.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false)[0]
        let base = String(withoutQuery)
        guard !base.isEmpty else { return [] }

        var candidates: [String] = []
        for variant in [base.removingPercentEncoding, base].compactMap({ $0 }) {
            let normalized = normalize(variant)
            for path in [normalized, variant] where !path.isEmpty && !candidates.contains(path) {
                candidates.append(path)
            }
        }
        return candidates
    }

    /// Collapses `.` and `..` components. `NSString.standardizingPath` is unsuitable
    /// here: it resolves against the *filesystem* (expanding `~`, following symlinks)
    /// and strips a leading `/`, none of which is meaningful for a ZIP entry name.
    private static func normalize(_ path: String) -> String {
        var components: [String] = []
        for component in path.split(separator: "/", omittingEmptySubsequences: true).map(String.init) {
            switch component {
            case ".":
                continue
            case "..":
                // A `..` that would escape the archive root has nowhere to go; drop it
                // rather than keeping a literal ".." that can never match an entry.
                if !components.isEmpty { components.removeLast() }
            default:
                components.append(component)
            }
        }
        return components.joined(separator: "/")
    }

    private static func read(_ entry: Entry, from archive: Archive) throws -> Data {
        var data = Data()
        _ = try archive.extract(entry, consumer: { data.append($0) })
        return data
    }

    // MARK: - container.xml

    /// Resolves the OPF package document path from `META-INF/container.xml`'s
    /// `<rootfile full-path="...">` element.
    private static func parseContainer(_ data: Data) throws -> String {
        let delegate = ContainerXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse(), let opfPath = delegate.opfPath else {
            throw ParseError.malformedContainer
        }
        return opfPath
    }

    private final class ContainerXMLDelegate: NSObject, XMLParserDelegate {
        var opfPath: String?

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String]
        ) {
            if elementName == "rootfile", let fullPath = attributeDict["full-path"] {
                opfPath = fullPath
            }
        }
    }

    // MARK: - OPF package document

    /// Reads `<manifest>` (id → href) and `<spine>` (ordered idrefs), then resolves
    /// each spine idref to its manifest href, relative to the OPF's own directory —
    /// hrefs inside the OPF are relative to the OPF file's location, not the archive
    /// root.
    private static func parseOPF(_ data: Data, baseDirectory: String) throws -> [String] {
        let delegate = OPFXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse() else { throw ParseError.malformedOPF }

        return delegate.spineIdrefs.compactMap { idref in
            guard let href = delegate.manifest[idref] else { return nil }
            return baseDirectory.isEmpty ? href : "\(baseDirectory)/\(href)"
        }
    }

    private final class OPFXMLDelegate: NSObject, XMLParserDelegate {
        var manifest: [String: String] = [:]
        var spineIdrefs: [String] = []

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String]
        ) {
            switch elementName {
            case "item":
                if let id = attributeDict["id"], let href = attributeDict["href"] {
                    manifest[id] = href
                }
            case "itemref":
                if let idref = attributeDict["idref"] {
                    spineIdrefs.append(idref)
                }
            default:
                break
            }
        }
    }

    // MARK: - HTML → plain text

    /// `NSAttributedString`'s HTML importer gives the best text (it applies the
    /// document's own block structure), but it is intolerant of XHTML that is
    /// perfectly valid in an EPUB — undeclared entities, unusual doctypes — and
    /// answers `nil` rather than degrading. Returning `""` there put a blank page in
    /// front of the reader even though the bytes had been read fine (issue #108), so
    /// fall back to a direct tag strip whenever the importer produces nothing.
    static func plainText(fromHTML data: Data) -> String {
        guard !data.isEmpty else { return "" }

        if let attributed = try? NSAttributedString(
            data: data,
            options: [
                .documentType: NSAttributedString.DocumentType.html,
                .characterEncoding: String.Encoding.utf8.rawValue,
            ],
            documentAttributes: nil
        ) {
            let text = attributed.string.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { return text }
        }

        return strippingTags(from: data)
    }

    /// Minimal XHTML → text fallback for the case above. Drops comments and the
    /// `<head>`/`<script>`/`<style>` regions wholesale (markup machinery, never prose
    /// — leaving `<head>` in would put `<title>` text at the top of the page, mashed
    /// into the first heading when the document is minified), turns block-level
    /// boundaries into newlines so paragraphs don't run together, removes the
    /// remaining tags, and decodes entities.
    static func strippingTags(from data: Data) -> String {
        guard let html = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1) else { return "" }

        var text = html
        for pattern in [
            #"(?s)<!--.*?-->"#,
            #"(?is)<head\b[^>]*>.*?</head>"#,
            #"(?is)<script\b[^>]*>.*?</script>"#,
            #"(?is)<style\b[^>]*>.*?</style>"#,
        ] {
            text = text.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }
        text = text.replacingOccurrences(
            of: #"(?i)</(p|div|h[1-6]|li|tr|blockquote|section)\s*>|<br\s*/?>"#,
            with: "\n",
            options: .regularExpression
        )
        // Quoted attribute values are matched as units rather than scanning to the
        // first `>`, since an unescaped `>` inside one (`<p title="5 > 3">`) is legal
        // XML and would otherwise leave `3">` sitting in the middle of the prose.
        text = text.replacingOccurrences(
            of: #"(?s)<(?:[^>"']|"[^"]*"|'[^']*')*>"#,
            with: "",
            options: .regularExpression
        )
        text = decodingEntities(text)

        // Collapse the runs of blank lines left behind by stripped block tags.
        text = text.replacingOccurrences(of: #"[ \t]*\n[ \t\n]*\n[ \t\n]*"#, with: "\n\n", options: .regularExpression)
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Named entities beyond the five XML predefined ones that books actually use —
    /// typographic punctuation and accented Latin, overwhelmingly. `&amp;` is absent
    /// deliberately; it is decoded last, separately, so that `&amp;lt;` decodes to the
    /// literal text `&lt;` rather than to `<`.
    private static let namedEntities: [String: String] = [
        "&nbsp;": "\u{00A0}", "&shy;": "", "&lt;": "<", "&gt;": ">", "&quot;": "\"", "&apos;": "'",
        "&mdash;": "—", "&ndash;": "–", "&hellip;": "…", "&bull;": "•", "&middot;": "·",
        "&lsquo;": "\u{2018}", "&rsquo;": "\u{2019}", "&ldquo;": "\u{201C}", "&rdquo;": "\u{201D}",
        "&sbquo;": "\u{201A}", "&bdquo;": "\u{201E}", "&laquo;": "«", "&raquo;": "»",
        "&dagger;": "†", "&Dagger;": "‡", "&prime;": "′", "&Prime;": "″",
        "&copy;": "©", "&reg;": "®", "&trade;": "™", "&deg;": "°", "&sect;": "§", "&para;": "¶",
        "&times;": "×", "&divide;": "÷", "&minus;": "−", "&plusmn;": "±",
        "&frac12;": "½", "&frac14;": "¼", "&frac34;": "¾",
        "&pound;": "£", "&euro;": "€", "&yen;": "¥", "&cent;": "¢",
        "&aacute;": "á", "&eacute;": "é", "&iacute;": "í", "&oacute;": "ó", "&uacute;": "ú",
        "&agrave;": "à", "&egrave;": "è", "&igrave;": "ì", "&ograve;": "ò", "&ugrave;": "ù",
        "&acirc;": "â", "&ecirc;": "ê", "&icirc;": "î", "&ocirc;": "ô", "&ucirc;": "û",
        "&auml;": "ä", "&euml;": "ë", "&iuml;": "ï", "&ouml;": "ö", "&uuml;": "ü",
        "&atilde;": "ã", "&ntilde;": "ñ", "&otilde;": "õ", "&ccedil;": "ç", "&szlig;": "ß",
        "&aring;": "å", "&oslash;": "ø", "&aelig;": "æ", "&oelig;": "œ",
    ]

    private static let numericEntityRegex = try? NSRegularExpression(
        pattern: "&#(x?)([0-9a-fA-F]+);",
        options: [.caseInsensitive]
    )

    /// Decodes the entities that survive tag removal. Numeric references are handled
    /// generically, which matters because this path runs precisely on the documents
    /// `NSAttributedString` rejected — often *because* they are entity-heavy — so
    /// leaving them encoded would trade a blank page for a page of visible `&#8217;`.
    /// Anything unrecognised is left verbatim rather than dropped.
    private static func decodingEntities(_ text: String) -> String {
        var result = text
        for (entity, replacement) in namedEntities where result.range(of: entity, options: .caseInsensitive) != nil {
            result = result.replacingOccurrences(of: entity, with: replacement, options: .caseInsensitive)
        }
        result = decodingNumericEntities(result)
        return result.replacingOccurrences(of: "&amp;", with: "&", options: .caseInsensitive)
    }

    private static func decodingNumericEntities(_ text: String) -> String {
        guard let regex = numericEntityRegex else { return text }
        let source = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: source.length))
        guard !matches.isEmpty else { return text }

        var result = ""
        var cursor = 0
        for match in matches {
            result += source.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            let isHex = !source.substring(with: match.range(at: 1)).isEmpty
            let digits = source.substring(with: match.range(at: 2))
            if let value = UInt32(digits, radix: isHex ? 16 : 10), let scalar = Unicode.Scalar(value) {
                result.unicodeScalars.append(scalar)
            } else {
                // Out of Unicode range or otherwise unrepresentable — keep it verbatim.
                result += source.substring(with: match.range)
            }
            cursor = match.range.location + match.range.length
        }
        result += source.substring(from: cursor)
        return result
    }

    // MARK: - HTML → block model (#356)

    /// Parses one chapter's XHTML into a structured [EPUBBlock] sequence (headings,
    /// paragraphs, lists, images), replacing the flat-text-only view `plainText`
    /// gives — foundational groundwork for #352's image-rendering fix; consuming this
    /// output (wiring it into `BookChapter`/`ReaderView`) is out of scope here, see
    /// #357/#359/#360.
    ///
    /// XHTML is well-formed XML by definition, so `XMLParser` — already used above for
    /// `container.xml`/the OPF — is the primary strategy here too. Critically, it is
    /// *not* a second, separate thing to keep tolerant of the real-world XHTML that
    /// broke `NSAttributedString`'s importer (issue #108): `XMLParser` fails outright
    /// on undeclared entities and other not-quite-well-formed markup, which is exactly
    /// the signal used below to fall back, rather than something that needs its own
    /// detection logic. A chapter `XMLParser` can't make sense of — or one whose body
    /// yielded no recognised block at all (e.g. markup outside the handled tag set) —
    /// collapses to a single flat `.paragraph` block built from `strippingTags`'s
    /// output, the same graceful-degradation guarantee `plainText` already has: never
    /// a blank page.
    static func parseBlocks(fromHTML data: Data) -> [EPUBBlock] {
        guard !data.isEmpty else { return [] }

        let delegate = BlockXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        if parser.parse(), !delegate.blocks.isEmpty {
            return delegate.blocks
        }

        return fallbackBlocks(from: data)
    }

    /// The #108 guard, restated for the block model: whatever `strippingTags` can
    /// recover becomes one paragraph block rather than nothing.
    private static func fallbackBlocks(from data: Data) -> [EPUBBlock] {
        let text = strippingTags(from: data)
        guard !text.isEmpty else { return [] }
        return [.paragraph(text: [EPUBInlineRun(text: text, bold: false, italic: false)])]
    }

    /// SAX-driven walk of a chapter's `<body>`, producing an [EPUBBlock] sequence.
    /// Recognises headings (`h1`-`h6`), paragraphs, un/ordered lists, images, and
    /// bold/italic inline runs (`b`/`strong`, `i`/`em`) inside headings/paragraphs/list
    /// items. Everything else (`div`, `section`, `span`, `a`, `blockquote`, …) is
    /// structurally transparent — its children are walked through as if it weren't
    /// there — matching #356's intentionally narrow scope (headings, paragraphs,
    /// lists, images; not a full XHTML/CSS block model).
    ///
    /// Known simplification: a *nested* `<ul>`/`<ol>` — a `<li>` containing its own
    /// child list — attributes any not-yet-flushed text from the *enclosing* `<li>` to
    /// the wrong (inner) list frame, since starting the inner list's first `<li>`
    /// flushes whatever container is currently open into whatever list frame is
    /// currently on top of the stack. Real EPUB chapter markup overwhelmingly uses
    /// flat lists; the failure mode here is misplaced text, not lost or blank content,
    /// so this is left for a follow-up rather than blocking the split.
    private final class BlockXMLDelegate: NSObject, XMLParserDelegate {
        private(set) var blocks: [EPUBBlock] = []

        private enum Container: Equatable {
            case heading(level: Int)
            case paragraph
            case listItem
        }

        private struct ListFrame {
            let ordered: Bool
            var items: [[EPUBInlineRun]] = []
        }

        private var inBody = false
        private var container: Container?
        private var currentRuns: [EPUBInlineRun] = []
        private var listStack: [ListFrame] = []
        private var boldDepth = 0
        private var italicDepth = 0

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String]
        ) {
            switch elementName.lowercased() {
            case "body":
                inBody = true
            case "h1", "h2", "h3", "h4", "h5", "h6":
                guard inBody else { return }
                flushContainer()
                container = .heading(level: Int(elementName.dropFirst()) ?? 1)
                currentRuns = []
            case "p":
                guard inBody else { return }
                flushContainer()
                container = .paragraph
                currentRuns = []
            case "ul":
                guard inBody else { return }
                listStack.append(ListFrame(ordered: false))
            case "ol":
                guard inBody else { return }
                listStack.append(ListFrame(ordered: true))
            case "li":
                guard inBody else { return }
                flushContainer()
                container = .listItem
                currentRuns = []
            case "b", "strong":
                boldDepth += 1
            case "i", "em":
                italicDepth += 1
            case "br":
                currentRuns.append(EPUBInlineRun(text: "\n", bold: boldDepth > 0, italic: italicDepth > 0))
            case "img":
                guard inBody else { return }
                // A <p> wrapping an <img> is the common case — flush whatever text
                // preceded the image inside the current container so reading order is
                // preserved, then emit the image as its own top-level block. The
                // container (if any) stays open (`reopen: true`) so text that follows
                // the image before its own closing tag still lands back in the same
                // block once that tag is reached.
                flushContainer(reopen: true)
                blocks.append(.image(url: attributeDict["src"] ?? "", altText: attributeDict["alt"] ?? ""))
            default:
                break
            }
        }

        func parser(
            _ parser: XMLParser,
            didEndElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?
        ) {
            switch elementName.lowercased() {
            case "body":
                flushContainer()
                inBody = false
            case "h1", "h2", "h3", "h4", "h5", "h6", "p":
                flushContainer()
            case "li":
                flushContainer()
            case "ul", "ol":
                guard let frame = listStack.popLast(), !frame.items.isEmpty else { return }
                blocks.append(frame.ordered ? .orderedList(items: frame.items) : .unorderedList(items: frame.items))
            case "b", "strong":
                boldDepth = max(0, boldDepth - 1)
            case "i", "em":
                italicDepth = max(0, italicDepth - 1)
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            guard inBody, container != nil, !string.isEmpty else { return }
            currentRuns.append(EPUBInlineRun(text: string, bold: boldDepth > 0, italic: italicDepth > 0))
        }

        /// Appends the accumulated runs as a block matching `container`'s type (or as
        /// an item in the innermost open list frame, for a list item), then clears the
        /// run buffer. Whitespace-only containers are dropped rather than emitted as
        /// blank blocks — real XHTML is full of pure-whitespace text nodes between
        /// sibling elements (indentation). `reopen` keeps `container` itself active
        /// (used by the `<img>` case, where text can resume after the image inside the
        /// same still-open tag); every other caller is at that container's own closing
        /// tag, so the default clears it.
        private func flushContainer(reopen: Bool = false) {
            defer {
                if !reopen { container = nil }
                currentRuns = []
            }
            guard let container else { return }
            let trimmed = trimmedRuns(currentRuns)
            guard !trimmed.isEmpty else { return }

            switch container {
            case let .heading(level):
                blocks.append(.heading(level: level, text: trimmed))
            case .paragraph:
                blocks.append(.paragraph(text: trimmed))
            case .listItem:
                guard !listStack.isEmpty else { return }
                listStack[listStack.count - 1].items.append(trimmed)
            }
        }

        /// Drops leading/trailing whitespace-only runs — the indentation `foundCharacters`
        /// otherwise contributes around inline tags — without disturbing intentional
        /// whitespace in the middle of a run sequence (the space between two words split
        /// across a `<b>` boundary, for instance).
        private func trimmedRuns(_ runs: [EPUBInlineRun]) -> [EPUBInlineRun] {
            var result = runs
            while let first = result.first, first.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                result.removeFirst()
            }
            while let last = result.last, last.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                result.removeLast()
            }
            return result
        }
    }

    private static func chapterTitle(fromHTML data: Data, fallback: String) -> String {
        let text = plainText(fromHTML: data)
        guard let firstLine = text.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: true).first,
              !firstLine.isEmpty else {
            return fallback
        }
        return String(firstLine.prefix(80))
    }
}
