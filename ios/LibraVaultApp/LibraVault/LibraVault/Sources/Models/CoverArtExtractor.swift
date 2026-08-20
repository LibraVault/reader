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

        guard let imageData = await extractRawCoverData(format: book.format, fileURL: fileURL) else { return nil }
        return cache.save(key: cacheKey, imageData: imageData)
    }

    /// Same format-dispatch as `extractCoverPath`, but returns raw bytes and
    /// never touches `CoverArtCache` — the entry point for Encrypted Vault
    /// import (`EncryptedVaultContentsViewModel.importFiles`), which must
    /// never let vault content's cover art land in the shared plaintext
    /// cover-art cache. Mirrors Android's `MetadataExtractor
    /// .extractWithoutCaching`, kept as its own deliberate name (not just
    /// "the private helper `extractCoverPath` already has") specifically so
    /// this leak-avoidance property is visible at every call site, not just
    /// inferred from omitting a `cache:` parameter.
    static func extractRawCoverData(format: MediaFormat, fileURL: URL) async -> Data? {
        switch format {
        case .epub:
            return extractEpubCover(fileURL: fileURL)
        case .pdf:
            return extractPdfCover(fileURL: fileURL)
        case .mp3, .m4b, .aac, .flac, .ogg, .opus:
            return await extractAudioCover(fileURL: fileURL)
        case .markdown, .mobi, .cbz:
            return nil
        }
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

    /// `PDFPage.thumbnail(of:for:)` rasterizes at exactly the pixel size requested —
    /// unlike `UIGraphicsImageRenderer`, it has no notion of the device's Retina scale
    /// to apply on top. Requesting `CoverArtCache.maxCoverPx` up front (rather than an
    /// arbitrary smaller size like the 256px this used to request) means the raster
    /// this hands to `CoverArtCache.save` already fills its 512px-long-edge cap instead
    /// of falling short of it, so the cache's own downsample step has real detail to
    /// work with instead of upscale-blurring a too-small source when the grid displays
    /// it at 120pt (240-360 physical px on a 2x/3x device) — field-reported as "PDF
    /// covers blurred" while EPUB covers (pulled from the manifest at native
    /// resolution) looked sharp.
    private static func extractPdfCover(fileURL: URL) -> Data? {
        guard let document = PDFDocument(url: fileURL), let page = document.page(at: 0) else { return nil }

        let pageBounds = page.bounds(for: .cropBox)
        guard pageBounds.width > 0, pageBounds.height > 0 else { return nil }

        let width = CGFloat(CoverArtCache.maxCoverPx)
        let height = pageBounds.height / pageBounds.width * width
        let thumbnail = page.thumbnail(of: CGSize(width: width, height: height), for: .cropBox)
        return thumbnail.jpegData(compressionQuality: 0.85)
    }

    // MARK: - Audio (embedded artwork via AVAsset metadata)

    /// Reads embedded artwork from container-level metadata (ID3/M4B tags) — a pure
    /// file-parsing read, not audio playback or session activation. Safe to run
    /// unconditionally, including under CI: unlike AVAudioSession/AVSpeechSynthesizer
    /// activation (see TTSEngineBridge.isRunningUnderXCTest in DomainBridge.swift, which
    /// hangs the CI Simulator because it touches the audio daemon), this never opens an
    /// audio session — the same class of call as AVAudioPlayer's non-playing `load()`,
    /// which that CI-hang investigation confirmed runs clean.
    ///
    /// `commonMetadata` only surfaces artwork that AVFoundation itself maps to a
    /// common key, which it reliably does for iTunes-style atoms (M4A/M4B) but often
    /// doesn't for ID3 `APIC` frames on MP3s — those need the ID3 keyspace read
    /// explicitly via `loadMetadata(for:)` (reported in the field: audiobook cover
    /// art missing, while EPUB/PDF cover art — neither of which goes through
    /// AVFoundation — worked fine).
    private static func extractAudioCover(fileURL: URL) async -> Data? {
        let asset = AVURLAsset(url: fileURL)

        if let commonMetadata = try? await asset.load(.commonMetadata),
           let data = await artworkData(in: commonMetadata) {
            return data
        }

        guard let formats = try? await asset.load(.availableMetadataFormats) else { return nil }
        for format in formats where format == .id3Metadata || format == .iTunesMetadata {
            guard let items = try? await asset.loadMetadata(for: format) else { continue }
            if let data = await artworkData(in: items) {
                return data
            }
        }
        return nil
    }

    private static func artworkData(in items: [AVMetadataItem]) async -> Data? {
        guard let artworkItem = items.first(where: {
            $0.commonKey == .commonKeyArtwork
                || $0.identifier == .id3MetadataAttachedPicture
                || $0.identifier == .iTunesMetadataCoverArt
        }) else { return nil }
        return try? await artworkItem.load(.dataValue)
    }
}
