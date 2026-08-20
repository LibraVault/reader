import Foundation
import ZIPFoundation

/// In-memory counterpart to `EPUBParser.parse(fileURL:)`, for EPUBs decrypted
/// from a vault into a `Data` buffer (`VaultStore.readFullContent`) rather
/// than sitting on disk as a plain file. #203's design deliberately keeps
/// this as its own adapter rather than adding a `Data`-based entry point to
/// `EPUBParser.swift` itself — no changes to the existing non-vault reader
/// files, mirroring Android's own choice not to share a storage interface
/// between vault and non-vault content (see `VaultStore`'s own doc comment).
///
/// The ZIP-entry-resolution and container/OPF parsing below is therefore a
/// deliberate, independent port of `EPUBParser`'s equivalent logic onto
/// `Archive(data:accessMode:)` instead of `Archive(url:accessMode:)` — the
/// only thing that actually differs is *where the archive's bytes live*
/// (ZIPFoundation's in-memory `MemoryFile` backing needs no real file for
/// random ZIP access). The HTML→text conversion itself is not duplicated:
/// `EPUBParser.plainText(fromHTML:)`/`strippingTags(from:)` and
/// `resolveEntryPath(_:)` are `internal`, not `private`, precisely so this
/// adapter (and its tests) can call them directly instead of re-implementing
/// prose extraction a second time.
enum VaultEPUBParser {

    static func parse(data: Data) throws -> [BookChapter] {
        let archive = try openArchive(data: data)

        let containerData = try extract("META-INF/container.xml", from: archive)
        let opfPath = try parseContainer(containerData)

        let opfData = try extract(opfPath, from: archive)
        let opfBaseDirectory = (opfPath as NSString).deletingLastPathComponent
        let spineHrefs = try parseOPF(opfData, baseDirectory: opfBaseDirectory)

        return spineHrefs.enumerated().map { index, href in
            let html = (try? extractSpineItem(href, from: archive)) ?? Data()
            let text = EPUBParser.plainText(fromHTML: html)
            let title = chapterTitle(fromHTML: html, fallback: "Chapter \(index + 1)")
            return BookChapter(title: title, text: text)
        }
    }

    // MARK: - Archive access

    private static func openArchive(data: Data) throws -> Archive {
        do {
            return try Archive(data: data, accessMode: .read)
        } catch {
            throw EPUBParser.ParseError.invalidArchive
        }
    }

    private static func extract(_ path: String, from archive: Archive) throws -> Data {
        guard let entry = firstEntry(for: path, in: archive) else { throw EPUBParser.ParseError.entryNotFound(path) }
        return try read(entry, from: archive)
    }

    private static func firstEntry(for path: String, in archive: Archive) -> Entry? {
        for candidate in EPUBParser.resolveEntryPath(path) {
            if let entry = archive[candidate] { return entry }
        }
        return nil
    }

    /// See `EPUBParser.extractSpineItem`'s doc comment — same href-vs-entry-name
    /// tolerance (issue #108), ported onto an in-memory `Archive`.
    private static func extractSpineItem(_ href: String, from archive: Archive) throws -> Data {
        if let entry = firstEntry(for: href, in: archive) {
            return try read(entry, from: archive)
        }

        let filename = (EPUBParser.resolveEntryPath(href).first.map { ($0 as NSString).lastPathComponent }) ?? href
        if !filename.isEmpty {
            let matches = archive.filter { ($0.path as NSString).lastPathComponent == filename }
            if matches.count == 1, let entry = matches.first {
                return try read(entry, from: archive)
            }
        }

        throw EPUBParser.ParseError.entryNotFound(href)
    }

    private static func read(_ entry: Entry, from archive: Archive) throws -> Data {
        var data = Data()
        _ = try archive.extract(entry, consumer: { data.append($0) })
        return data
    }

    // MARK: - container.xml

    private static func parseContainer(_ data: Data) throws -> String {
        let delegate = ContainerXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse(), let opfPath = delegate.opfPath else {
            throw EPUBParser.ParseError.malformedContainer
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

    private static func parseOPF(_ data: Data, baseDirectory: String) throws -> [String] {
        let delegate = OPFXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        guard parser.parse() else { throw EPUBParser.ParseError.malformedOPF }

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

    private static func chapterTitle(fromHTML data: Data, fallback: String) -> String {
        let text = EPUBParser.plainText(fromHTML: data)
        guard let firstLine = text.split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: true).first,
              !firstLine.isEmpty else {
            return fallback
        }
        return String(firstLine.prefix(80))
    }
}
