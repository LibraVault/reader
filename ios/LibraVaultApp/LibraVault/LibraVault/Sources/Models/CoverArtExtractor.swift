import AVFoundation
import Foundation
import PDFKit
import UIKit
import ZIPFoundation

/// iOS-native counterpart to Android's core:storage `MetadataExtractor.kt`'s cover-art
/// extraction (audio embedded art / EPUB OPF manifest / PDF page-1 render) — paired
/// with CoverArtCache.swift for the matching on-disk cache layer. Deliberately narrower
/// than the Kotlin version: only the cover-art path, not title/author/series/duration,
/// which LibraryFileScanner.swift's header comment already tracks separately as an
/// unimplemented gap.
///
/// Runs off the main actor (see AppState.loadLibrary's phase-2 enrichment) since zip
/// reads, PDF page rendering, and AVAsset metadata loading are all real I/O — this type
/// itself is stateless and actor-agnostic, so callers control where it executes.
enum CoverArtExtractor {
    /// Extracts (or returns an already-cached) cover image URL for `book`, or nil if
    /// the format has no cover concept (Markdown, MOBI, CBZ — none of which this app
    /// parses for embedded art) or none was found/decodable for this particular file.
    static func extractCoverPath(for book: BookData, cache: CoverArtCache = CoverArtCache()) async -> URL? {
        guard let fileURL = book.fileURL else { return nil }

        let cacheKey = fileURL.path
        if let cached = cache.getCachedPath(key: cacheKey) { return cached }

        let imageData: Data?
        switch book.format {
        case .epub:
            imageData = extractEpubCover(fileURL: fileURL)
        case .pdf:
            imageData = extractPdfCover(fileURL: fileURL)
        case .mp3, .m4b, .aac, .flac, .ogg, .opus:
            imageData = await extractAudioCover(fileURL: fileURL)
        case .markdown, .mobi, .cbz:
            imageData = nil
        }

        guard let imageData else { return nil }
        return cache.save(key: cacheKey, imageData: imageData)
    }

    // MARK: - EPUB (container.xml → OPF manifest → cover image entry)

    private static func extractEpubCover(fileURL: URL) -> Data? {
        guard let archive = try? Archive(url: fileURL, accessMode: .read) else { return nil }
        guard let containerData = extract("META-INF/container.xml", from: archive),
              let opfPath = parseContainer(containerData),
              let opfData = extract(opfPath, from: archive) else { return nil }

        let opf = parseOpf(opfData)
        let opfBaseDirectory = (opfPath as NSString).deletingLastPathComponent

        // EPUB3: <meta name="cover" content="ID"> resolved through the manifest.
        // Fallback (EPUB2 and non-conformant files): any manifest item whose id/href
        // mentions "cover" and looks like an image — mirrors MetadataExtractor.kt's
        // parseOpf fallback.
        let coverHref = opf.coverImageId.flatMap { opf.manifest[$0] }
            ?? opf.manifest.first { id, href in
                (id.localizedCaseInsensitiveContains("cover") || href.localizedCaseInsensitiveContains("cover"))
                    && isImageHref(href)
            }?.value

        guard let coverHref else { return nil }
        let fullPath = opfBaseDirectory.isEmpty ? coverHref : "\(opfBaseDirectory)/\(coverHref)"
        return extract(fullPath, from: archive)
    }

    private static func extract(_ path: String, from archive: Archive) -> Data? {
        guard let entry = archive[path] else { return nil }
        var data = Data()
        guard (try? archive.extract(entry, consumer: { data.append($0) })) != nil else { return nil }
        return data
    }

    private static func parseContainer(_ data: Data) -> String? {
        let delegate = ContainerXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return delegate.opfPath
    }

    private static func parseOpf(_ data: Data) -> (manifest: [String: String], coverImageId: String?) {
        let delegate = OpfCoverXMLDelegate()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.parse()
        return (delegate.manifest, delegate.coverImageId)
    }

    private static func isImageHref(_ href: String) -> Bool {
        switch (href as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg", "png": return true
        default: return false
        }
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

    /// Only extracts what's needed to resolve a cover: the manifest (id → href) and the
    /// EPUB3 cover meta. Title/author extraction (Phase D scope) is deliberately not
    /// duplicated here — see LibraryFileScanner.swift.
    private final class OpfCoverXMLDelegate: NSObject, XMLParserDelegate {
        var manifest: [String: String] = [:]
        var coverImageId: String?
        private var inMetadata = false

        func parser(
            _ parser: XMLParser,
            didStartElement elementName: String,
            namespaceURI: String?,
            qualifiedName qName: String?,
            attributes attributeDict: [String: String]
        ) {
            switch elementName {
            case "metadata":
                inMetadata = true
            case "meta":
                if inMetadata, attributeDict["name"] == "cover" {
                    coverImageId = attributeDict["content"]
                }
            case "item":
                if let id = attributeDict["id"], let href = attributeDict["href"] {
                    manifest[id] = href
                }
            default:
                break
            }
        }

        func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
            if elementName == "metadata" { inMetadata = false }
        }
    }

    // MARK: - PDF (page 1 thumbnail via PDFKit)

    private static func extractPdfCover(fileURL: URL) -> Data? {
        guard let document = PDFDocument(url: fileURL), let page = document.page(at: 0) else { return nil }

        let pageBounds = page.bounds(for: .cropBox)
        guard pageBounds.width > 0, pageBounds.height > 0 else { return nil }

        let width: CGFloat = 256
        let height = pageBounds.height / pageBounds.width * width
        let thumbnail = page.thumbnail(of: CGSize(width: width, height: height), for: .cropBox)
        return thumbnail.jpegData(compressionQuality: 0.85)
    }

    // MARK: - Audio (embedded artwork via AVAsset common metadata)

    /// Reads embedded artwork from container-level metadata (ID3/M4B tags) — a pure
    /// file-parsing read, not audio playback or session activation. Safe to run
    /// unconditionally, including under CI: unlike AVAudioSession/AVSpeechSynthesizer
    /// activation (see TTSEngineBridge.isRunningUnderXCTest in DomainBridge.swift, which
    /// hangs the CI Simulator because it touches the audio daemon), this never opens an
    /// audio session — the same class of call as AVAudioPlayer's non-playing `load()`,
    /// which that CI-hang investigation confirmed runs clean.
    private static func extractAudioCover(fileURL: URL) async -> Data? {
        let asset = AVURLAsset(url: fileURL)
        guard let metadata = try? await asset.load(.commonMetadata) else { return nil }
        guard let artworkItem = metadata.first(where: { $0.commonKey == .commonKeyArtwork }) else { return nil }
        return try? await artworkItem.load(.dataValue)
    }
}
