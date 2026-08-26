import Foundation
import ZIPFoundation

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
        case drmProtected
    }

    static func parse(fileURL: URL) throws -> [BookChapter] {
        let archive = try openArchive(at: fileURL)

        // `META-INF/encryption.xml` is the EPUB OCF spec's marker that some (or all)
        // resources in the archive are encrypted. Its *presence* alone isn't a
        // reliable DRM signal, though: the same file/element shape is also used for
        // **font obfuscation** (IDPF's algorithm `http://www.idpf.org/2008/embedding`
        // and Adobe's `http://ns.adobe.com/pdf/enc#RC`), a routine, non-DRM step many
        // otherwise-unprotected EPUBs take to license embedded custom fonts. An
        // existence-only check (the original form of this fix) rejected every one of
        // those as "protected" — a real false positive, not a hypothetical one; see
        // the OCF/IDPF font-mangling spec these two algorithm URIs come from. Only an
        // `EncryptedData` entry using some *other* algorithm — real Content Protection
        // (Adobe ADEPT, Readium LCP, …) Libravault has no decryption support for, see
        // KNOWN_LIMITATIONS.md — should reject the book. Left unchecked entirely, only
        // the (often unencrypted) cover page reads back cleanly and every other spine
        // item's ciphertext gets force-decoded as UTF-8/Latin-1 text by the plainText
        // fallback below, which renders as garbled glyph soup instead of a clean error
        // (issue #351).
        if let encryptionEntry = archive["META-INF/encryption.xml"] {
            let encryptionData = try read(encryptionEntry, from: archive)
            if isContentEncrypted(encryptionData) {
                throw ParseError.drmProtected
            }
        }

        let containerData = try extract("META-INF/container.xml", from: archive)
        let opfPath = try parseContainer(containerData)

        let opfData = try extract(opfPath, from: archive)
        let opfBaseDirectory = (opfPath as NSString).deletingLastPathComponent
        let spineHrefs = try parseOPF(opfData, baseDirectory: opfBaseDirectory)

        return spineHrefs.enumerated().map { index, href in
            let html = (try? extractSpineItem(href, from: archive)) ?? Data()
            let text = plainText(fromHTML: html)
            let title = chapterTitle(fromHTML: html, fallback: "Chapter \(index + 1)")
            let blocks = parseBlocks(fromHTML: html)
            let images = resolveImages(for: blocks, chapterHref: href, archive: archive)
            let segments = NarrationSegmenter.segments(forBlocks: blocks)
            return BookChapter(title: title, text: text, blocks: blocks, images: images, segments: segments)
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

    // MARK: - encryption.xml

    /// Font-obfuscation-only algorithm URIs from the IDPF/Adobe font-mangling specs —
    /// not real Content Protection, so an `EncryptedData` entry using only these
    /// doesn't mean the *book* is DRM-protected, just that its embedded fonts are
    /// license-mangled (a routine, widely-used step that has nothing to do with
    /// whether the reading content itself is readable).
    private static let fontObfuscationAlgorithms: Set<String> = [
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#RC",
    ]

    /// True if `encryption.xml` declares at least one `EncryptedData` entry whose
    /// algorithm isn't one of the known font-obfuscation-only ones above — i.e. real
    /// Content Protection is in play, not just mangled embedded fonts. A malformed
    /// `encryption.xml` (present but unparsable) is treated conservatively as real
    /// encryption: the file's own presence is still the OCF signal that *something*
    /// in the archive is encrypted, and a parse failure shouldn't silently downgrade
    /// that to "safe to render as plaintext."
    private static func isContentEncrypted(_ data: Data) -> Bool {
        let delegate = EncryptionXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse() else { return true }
        guard !delegate.algorithms.isEmpty else { return true }
        return !delegate.algorithms.allSatisfy { fontObfuscationAlgorithms.contains($0) }
    }

    private final class EncryptionXMLDelegate: NSObject, XMLParserDelegate {
        var algorithms: [String] = []

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String]
        ) {
            if elementName == "EncryptionMethod", let algorithm = attributeDict["Algorithm"] {
                algorithms.append(algorithm)
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

    private static func chapterTitle(fromHTML data: Data, fallback: String) -> String {
        let text = plainText(fromHTML: data)
        guard let firstLine = text.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: true).first,
              !firstLine.isEmpty else {
            return fallback
        }
        return String(firstLine.prefix(80))
    }

    // MARK: - HTML → block model (#356)

    /// Parses one chapter's XHTML into a structured block model instead of reducing it
    /// to flat text — reuses `MarkdownBlock`'s shape (`MarkdownDocumentParser.swift`)
    /// rather than a parallel type, since the subset of XHTML real EPUBs use (headings,
    /// paragraphs, lists, images) maps directly onto cases that type already defines
    /// for Markdown, and `MarkdownBlockView` can render it with no changes. Wired into
    /// `BookChapter.blocks`/`.images` since #357 and `.segments` since #635 (narration);
    /// on-screen block-based rendering in `ReaderView` is still separate, tracked by
    /// #360.
    ///
    /// `XMLParser`, not `NSAttributedString`, drives the primary path: it can express
    /// `<img>` structurally (`NSAttributedString`'s HTML importer discards `src`), and
    /// unlike that importer its accept/reject behaviour doesn't vary by OS version.
    /// XHTML that's valid-but-unusual (undeclared entities, unusual doctypes) still
    /// makes `XMLParser` fail outright rather than degrade gracefully — the same shape
    /// of problem `plainText(fromHTML:)` exists to solve (#108) — so on any parse
    /// failure, or a parse that yields nothing, this falls back to a single
    /// `.paragraph` block built by `fallbackInlineRuns(from:)` (#635). Never an empty
    /// array for a document with prose: that would be the blank page, restated as a
    /// block-model invariant.
    static func parseBlocks(fromHTML data: Data) -> [MarkdownBlock] {
        guard !data.isEmpty else { return [] }

        if let blocks = parseXHTMLBlocks(data), !blocks.isEmpty {
            return blocks
        }

        let runs = fallbackInlineRuns(from: data)
        return runs.isEmpty ? [] : [.paragraph(text: runs)]
    }

    /// `parseBlocks`'s fallback for XHTML `parseXHTMLBlocks` can't parse at all — the
    /// same malformed-but-valid-EPUB case `strippingTags`/`plainText` exist to handle
    /// (#108). Before #635, this degraded to one flat `MarkdownInlineRun` with
    /// `bold`/`italic` always `false` — `strippingTags` treats `<em>/<i>/<b>/<strong>`
    /// identically to every other tag it strips, so a document that hits this path lost
    /// all emphasis signal, unlike the primary `parseXHTMLBlocks`/`walkInline` path,
    /// which already distinguishes them. That gap matters now that `blocks` feeds
    /// `NarrationSegmenter` (#635): a bold/italic run reaching this fallback would
    /// otherwise render as `.plain` regardless of source markup.
    ///
    /// Scans for `<em>/<i>/<b>/<strong>` span boundaries directly (`parseXHTMLBlocks`
    /// already failed on the whole document, so there's no XML tree left to walk), and
    /// otherwise reuses `strippingTags`'s own head/script/style/comment removal and
    /// block-boundary-to-newline rules before stripping whatever tags remain per
    /// resulting fragment. Doesn't attempt to recover block structure (headings, lists,
    /// paragraph boundaries) beyond the single fallback `.paragraph` — there's no
    /// reliable structure left to recover once the whole document has failed to parse.
    private static func fallbackInlineRuns(from data: Data) -> [MarkdownInlineRun] {
        guard let html = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1) else { return [] }

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

        guard let emphasisTagRegex = try? NSRegularExpression(pattern: #"(?is)<(/?)(em|i|b|strong)\b[^>]*>"#) else {
            let flat = decodingEntities(stripRemainingTags(text))
            return flat.isEmpty ? [] : [MarkdownInlineRun(text: flat, bold: false, italic: false, code: false)]
        }

        let nsText = text as NSString
        var runs: [MarkdownInlineRun] = []
        var bold = false
        var italic = false
        var cursor = 0

        // No collapsingWhitespace/trimmingCharacters(in: .whitespaces) here — both
        // treat U+00A0 (non-breaking space) as whitespace to collapse/strip, which
        // would silently turn a real, meaningful `&nbsp;` (already decoded to
        // U+00A0 by this point) into an ordinary space or drop it outright. Matches
        // strippingTags's own behavior, which never collapses inline whitespace
        // either — only a fragment that's *entirely* whitespace is dropped, and
        // only from the decision to keep it, not from the text itself.
        func appendRun(upTo location: Int) {
            guard location > cursor else { return }
            let fragment = nsText.substring(with: NSRange(location: cursor, length: location - cursor))
            let plain = decodingEntities(stripRemainingTags(fragment))
            guard !plain.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            runs.append(MarkdownInlineRun(text: plain, bold: bold, italic: italic, code: false))
        }

        let matches = emphasisTagRegex.matches(in: text, range: NSRange(location: 0, length: nsText.length))
        for match in matches {
            appendRun(upTo: match.range.location)
            let isClosing = nsText.substring(with: match.range(at: 1)) == "/"
            switch nsText.substring(with: match.range(at: 2)).lowercased() {
            case "b", "strong": bold = !isClosing
            case "i", "em": italic = !isClosing
            default: break
            }
            cursor = match.range.location + match.range.length
        }
        appendRun(upTo: nsText.length)

        return trimmedEdges(runs)
    }

    /// Removes any tag other than the `<em>/<i>/<b>/<strong>` ones `fallbackInlineRuns`
    /// already consumed before calling this — used per-fragment there, so entities are
    /// decoded by the caller afterward, same order `strippingTags` uses.
    private static func stripRemainingTags(_ fragment: String) -> String {
        fragment.replacingOccurrences(
            of: #"(?s)<(?:[^>"']|"[^"]*"|'[^']*')*>"#,
            with: "",
            options: .regularExpression
        )
    }

    // MARK: - Chapter image resolution (#357)

    /// Reads the bytes for every `.image` block's `src` in `blocks`, keyed by that raw
    /// `src` — the EPUB counterpart of `ReaderView.loadMarkdownImages`/
    /// `BookContentProvider.markdownAssetData`, but resolved eagerly here (at parse
    /// time, against the already-open archive) rather than later against the
    /// filesystem, since an EPUB's images live inside the ZIP, not beside it. A `src`
    /// that can't be resolved to an archive entry is skipped, not treated as a
    /// whole-chapter failure — mirrors `loadMarkdownImages`'s same per-image tolerance,
    /// and `MarkdownBlockView` already renders a broken-image placeholder for any url
    /// missing from the dictionary.
    private static func resolveImages(for blocks: [MarkdownBlock], chapterHref: String, archive: Archive) -> [String: Data] {
        var images: [String: Data] = [:]
        for url in imageURLs(in: blocks) where images[url] == nil {
            guard let archivePath = resolvedImagePath(url, relativeToHref: chapterHref) else { continue }
            if let data = try? extractSpineItem(archivePath, from: archive) {
                images[url] = data
            }
        }
        return images
    }

    /// Every `.image` `src` referenced anywhere in `blocks`, including inside lists and
    /// block quotes — an `<img>` under `<li>`/`<blockquote>` is common enough in real
    /// EPUBs (figure captions, list-based galleries) that a top-level-only scan would
    /// silently leave those unresolved.
    private static func imageURLs(in blocks: [MarkdownBlock]) -> [String] {
        var urls: [String] = []
        for block in blocks {
            switch block {
            case let .image(url, _):
                urls.append(url)
            case let .blockQuote(nested):
                urls.append(contentsOf: imageURLs(in: nested))
            case let .unorderedList(items):
                urls.append(contentsOf: items.flatMap { imageURLs(in: $0) })
            case let .orderedList(items, _):
                urls.append(contentsOf: items.flatMap { imageURLs(in: $0) })
            default:
                break
            }
        }
        return urls
    }

    /// Resolves an `<img src>` (as written in the XHTML) to an archive-entry-lookup
    /// path, relative to the chapter's own href the same way a browser would resolve it
    /// relative to the document's URL — mirrors `markdownAssetData`'s refusal to
    /// resolve absolute http(s) URLs (LibraVault is offline-first) and its `data:` URIs
    /// have no archive entry to resolve to either. `extractSpineItem` (not a plain
    /// archive lookup) does the actual read, so the same href-vs-entry-name tolerance
    /// that protects spine items (#108) applies to images too.
    private static func resolvedImagePath(_ src: String, relativeToHref href: String) -> String? {
        let lower = src.lowercased()
        guard !lower.hasPrefix("http://"), !lower.hasPrefix("https://"), !lower.hasPrefix("data:") else {
            return nil
        }
        guard !src.isEmpty else { return nil }
        if src.hasPrefix("/") { return String(src.dropFirst()) }

        let chapterDirectory = (href as NSString).deletingLastPathComponent
        return chapterDirectory.isEmpty ? src : "\(chapterDirectory)/\(src)"
    }

    private static func parseXHTMLBlocks(_ data: Data) -> [MarkdownBlock]? {
        let delegate = XHTMLTreeXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse() else { return nil }
        return blocksForContainer(delegate.root.children)
    }

    /// A single node of the raw XHTML tree — `tag == nil` marks a text node, whose
    /// content lives in `text`. Built once per chapter by `XHTMLTreeXMLDelegate`, then
    /// walked separately by the block- and inline-level converters below; keeping the
    /// tree itself dumb (no block-model knowledge) keeps those converters testable in
    /// isolation from `XMLParser`'s SAX callbacks.
    private final class XHTMLNode {
        let tag: String?
        var text: String = ""
        var attributes: [String: String] = [:]
        var children: [XHTMLNode] = []
        init(tag: String?) { self.tag = tag }
    }

    /// Builds an `XHTMLNode` tree from `XMLParser`'s SAX callbacks. Adjacent character
    /// data is merged into one text node — `foundCharacters` can fire more than once
    /// for what is logically a single run (e.g. split around an entity reference) —
    /// so block/inline conversion never has to re-merge fragments itself.
    private final class XHTMLTreeXMLDelegate: NSObject, XMLParserDelegate {
        let root = XHTMLNode(tag: nil)
        private var stack: [XHTMLNode]

        override init() {
            stack = [root]
            super.init()
        }

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String]
        ) {
            let node = XHTMLNode(tag: elementName.lowercased())
            node.attributes = attributeDict
            stack.last?.children.append(node)
            stack.append(node)
        }

        func parser(_ parser: XMLParser, foundCharacters string: String) {
            guard let current = stack.last, current.tag != "script", current.tag != "style" else { return }
            if let last = current.children.last, last.tag == nil {
                last.text += string
            } else {
                let node = XHTMLNode(tag: nil)
                node.text = string
                current.children.append(node)
            }
        }

        func parser(
            _ parser: XMLParser,
            didEndElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?
        ) {
            if stack.count > 1 { stack.removeLast() }
        }
    }

    /// Tags whose presence among a container's *direct* children marks that container
    /// as block-level content (split child-by-child) rather than inline content (see
    /// `blocksForContainer`). `img` counts as block-level here so a bare `<img>` in the
    /// body becomes an `.image` block rather than being swallowed into a paragraph.
    private static let blockLevelTags: Set<String> = [
        "html", "body", "p", "ul", "ol", "li", "blockquote", "hr", "div",
        "section", "article", "header", "footer", "nav", "aside", "figure",
        "h1", "h2", "h3", "h4", "h5", "h6", "table", "img",
    ]

    /// Converts a container's children to blocks. A container with no block-level
    /// direct child (e.g. `<li>plain text with <b>emphasis</b></li>`) is inline-only
    /// content and collapses to a single `.paragraph` so its styling and spacing stay
    /// intact, rather than shattering into one block per text/element child — the same
    /// distinction HTML's own content model draws between block and inline elements.
    private static func blocksForContainer(_ children: [XHTMLNode]) -> [MarkdownBlock] {
        let hasBlockChild = children.contains { child in
            guard let tag = child.tag else { return false }
            return blockLevelTags.contains(tag)
        }
        guard hasBlockChild else {
            let runs = inlineRuns(for: children)
            return runs.isEmpty ? [] : [.paragraph(text: runs)]
        }
        return children.flatMap { blocks(for: $0) }
    }

    private static func blocks(for node: XHTMLNode) -> [MarkdownBlock] {
        guard let tag = node.tag else {
            let trimmed = node.text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return [] }
            return [.paragraph(text: [MarkdownInlineRun(text: trimmed, bold: false, italic: false, code: false)])]
        }

        switch tag {
        case "h1", "h2", "h3", "h4", "h5", "h6":
            let level = Int(tag.dropFirst()) ?? 1
            let runs = inlineRuns(for: node.children)
            return runs.isEmpty ? [] : [.heading(level: level, text: runs)]

        case "p":
            if let image = singleImage(in: node) { return [image] }
            let runs = inlineRuns(for: node.children)
            return runs.isEmpty ? [] : [.paragraph(text: runs)]

        case "img":
            return [.image(url: node.attributes["src"] ?? "", altText: node.attributes["alt"] ?? "")]

        case "ul":
            let items = node.children.filter { $0.tag == "li" }.map { blocksForContainer($0.children) }
            return items.isEmpty ? [] : [.unorderedList(items: items)]

        case "ol":
            let start = node.attributes["start"].flatMap(Int.init) ?? 1
            let items = node.children.filter { $0.tag == "li" }.map { blocksForContainer($0.children) }
            return items.isEmpty ? [] : [.orderedList(items: items, start: start)]

        case "blockquote":
            let nested = blocksForContainer(node.children)
            return nested.isEmpty ? [] : [.blockQuote(blocks: nested)]

        case "hr":
            return [.thematicBreak]

        case "head", "title", "style", "script":
            return []

        default:
            return blocksForContainer(node.children)
        }
    }

    /// True when `node` (a `<p>`) contains nothing but a single `<img>` — the XHTML
    /// analogue of `MarkdownDocumentParser.visitParagraph`'s same special case, so a
    /// standalone `![alt](src)`-equivalent paragraph becomes an `.image` block rather
    /// than an empty one (an `<img>` has no inline text of its own to fall back to).
    private static func singleImage(in node: XHTMLNode) -> MarkdownBlock? {
        let meaningfulChildren = node.children.filter { child in
            guard child.tag == nil else { return true }
            return !child.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }
        guard meaningfulChildren.count == 1, let only = meaningfulChildren.first, only.tag == "img" else {
            return nil
        }
        return .image(url: only.attributes["src"] ?? "", altText: only.attributes["alt"] ?? "")
    }

    private static func inlineRuns(for children: [XHTMLNode]) -> [MarkdownInlineRun] {
        var runs: [MarkdownInlineRun] = []
        for child in children {
            walkInline(child, bold: false, italic: false, code: false, into: &runs)
        }
        return trimmedEdges(runs)
    }

    /// Mirrors `MarkdownDocumentParser.BlockBuilder.walk`: recurses through inline
    /// markup accumulating style flags, and falls through to a plain text run (or, for
    /// `<img>`, its alt text) for anything unrecognised, rather than dropping it.
    private static func walkInline(
        _ node: XHTMLNode,
        bold: Bool,
        italic: Bool,
        code: Bool,
        into runs: inout [MarkdownInlineRun]
    ) {
        guard let tag = node.tag else {
            let collapsed = collapsingWhitespace(node.text)
            guard !collapsed.isEmpty else { return }
            runs.append(MarkdownInlineRun(text: collapsed, bold: bold, italic: italic, code: code))
            return
        }

        switch tag {
        case "b", "strong":
            for child in node.children { walkInline(child, bold: true, italic: italic, code: code, into: &runs) }
        case "i", "em":
            for child in node.children { walkInline(child, bold: bold, italic: true, code: code, into: &runs) }
        case "code", "tt":
            for child in node.children { walkInline(child, bold: bold, italic: italic, code: true, into: &runs) }
        case "br":
            runs.append(MarkdownInlineRun(text: "\n", bold: bold, italic: italic, code: code))
        case "img":
            let altText = node.attributes["alt"] ?? ""
            guard !altText.isEmpty else { return }
            runs.append(MarkdownInlineRun(text: altText, bold: bold, italic: italic, code: code))
        default:
            for child in node.children { walkInline(child, bold: bold, italic: italic, code: code, into: &runs) }
        }
    }

    /// Collapses a run of whitespace (including the newlines/indentation pretty-printed
    /// XHTML introduces between tags) to a single space, matching how a browser
    /// collapses inline whitespace — without this, indentation inside `<p>`/`<li>` would
    /// render as literal blank lines in the reader.
    private static func collapsingWhitespace(_ text: String) -> String {
        text.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
    }

    /// Trims leading whitespace off the first run and trailing whitespace off the last
    /// — the collapsed-but-unresolved edges of a block's own content (e.g. `<p>\n  Hi\n
    /// </p>`) — then drops any run left empty by that trim. Only the outer edges are
    /// touched; a single space *between* two runs (e.g. `"foo "` before `<b>bar</b>`)
    /// is real content, not indentation, and must survive.
    private static func trimmedEdges(_ runs: [MarkdownInlineRun]) -> [MarkdownInlineRun] {
        guard !runs.isEmpty else { return runs }
        var result = runs
        let first = result[0]
        result[0] = MarkdownInlineRun(
            text: first.text.replacingOccurrences(of: #"^\s+"#, with: "", options: .regularExpression),
            bold: first.bold, italic: first.italic, code: first.code
        )
        let lastIndex = result.count - 1
        let last = result[lastIndex]
        result[lastIndex] = MarkdownInlineRun(
            text: last.text.replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression),
            bold: last.bold, italic: last.italic, code: last.code
        )
        return result.filter { !$0.text.isEmpty }
    }
}
