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
            let html = (try? extract(href, from: archive)) ?? Data()
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

    private static func plainText(fromHTML data: Data) -> String {
        guard !data.isEmpty, let attributed = try? NSAttributedString(
            data: data,
            options: [
                .documentType: NSAttributedString.DocumentType.html,
                .characterEncoding: String.Encoding.utf8.rawValue,
            ],
            documentAttributes: nil
        ) else {
            return ""
        }
        return attributed.string.trimmingCharacters(in: .whitespacesAndNewlines)
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
