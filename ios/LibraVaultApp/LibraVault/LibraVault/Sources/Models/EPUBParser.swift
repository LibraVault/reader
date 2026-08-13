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

    private static func extract(_ path: String, from archive: Archive) throws -> Data {
        guard let entry = archive[path] else { throw ParseError.entryNotFound(path) }
        return try read(entry, from: archive)
    }

    /// Reads a spine content document, tolerating the gap between how an href is
    /// *spelled* in the OPF and how the entry is *named* in the ZIP. `archive[path]`
    /// is an exact string match, so a spine item whose href doesn't literally equal
    /// its entry name used to come back as empty `Data()` and render as a completely
    /// blank page (issue #108). Tries each `resolveEntryPath` candidate (normalised
    /// and percent-decoded forms, plus the literal spelling), then falls back to a
    /// filename match against the archive's entries.
    private static func extractSpineItem(_ href: String, from archive: Archive) throws -> Data {
        for candidate in resolveEntryPath(href) {
            if let entry = archive[candidate] {
                return try read(entry, from: archive)
            }
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

    /// Minimal XHTML → text fallback for the case above. Drops `<script>`/`<style>`
    /// content wholesale (it is markup machinery, never prose), turns block-level
    /// boundaries into newlines so paragraphs don't run together, removes remaining
    /// tags, and decodes the handful of entities that survive tag removal.
    static func strippingTags(from data: Data) -> String {
        guard let html = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1) else { return "" }

        var text = html
        for pattern in [#"(?is)<script\b[^>]*>.*?</script>"#, #"(?is)<style\b[^>]*>.*?</style>"#] {
            text = text.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }
        text = text.replacingOccurrences(
            of: #"(?i)</(p|div|h[1-6]|li|tr|blockquote|section)\s*>|<br\s*/?>"#,
            with: "\n",
            options: .regularExpression
        )
        text = text.replacingOccurrences(of: "(?s)<[^>]+>", with: "", options: .regularExpression)

        for (entity, replacement) in [
            ("&nbsp;", " "), ("&#160;", " "), ("&lt;", "<"), ("&gt;", ">"),
            ("&quot;", "\""), ("&apos;", "'"), ("&#39;", "'"), ("&amp;", "&"),
        ] {
            text = text.replacingOccurrences(of: entity, with: replacement, options: .caseInsensitive)
        }

        // Collapse the runs of blank lines left behind by stripped block tags.
        text = text.replacingOccurrences(of: #"[ \t]*\n[ \t\n]*\n[ \t\n]*"#, with: "\n\n", options: .regularExpression)
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
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
