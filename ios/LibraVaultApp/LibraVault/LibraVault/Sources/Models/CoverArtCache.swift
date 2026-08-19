import CryptoKit
import Foundation
import ImageIO
import UIKit

/// iOS-native counterpart to Android's `core:storage` `CoverArtCache.kt` — same
/// on-disk layout and constraints (SHA-256(key) filename, 512px long-edge cap, JPEG
/// quality 85, app-private cache directory), so both platforms cache covers the same
/// way even though nothing here is shared KMP code (see DomainBridge.swift's header).
///
/// Covers live under `Caches/covers/` — the OS is free to purge this directory under
/// disk pressure, which is fine: `CoverArtExtractor` re-derives a cover from the
/// source file on a cache miss.
struct CoverArtCache {
    static let maxCoverPx = 512

    private let cacheDir: URL

    init(fileManager: FileManager = .default) {
        let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        cacheDir = caches.appendingPathComponent("covers", isDirectory: true)
        try? fileManager.createDirectory(at: cacheDir, withIntermediateDirectories: true)
    }

    /// Saves raw image bytes (extracted from an EPUB manifest, a PDF page render, or
    /// embedded audio artwork) to cache, downsampling to `maxCoverPx` on the long edge.
    /// Returns the cached file's URL, or nil if `imageData` doesn't decode (corrupt
    /// header, unsupported format) — callers treat nil as "no cover available".
    func save(key: String, imageData: Data) -> URL? {
        guard let jpeg = Self.downsampledJPEG(from: imageData, maxDimension: Self.maxCoverPx) else {
            return nil
        }

        let file = cacheDir.appendingPathComponent("\(Self.keyHash(key)).jpg")
        do {
            try jpeg.write(to: file, options: .atomic)
            return file
        } catch {
            return nil
        }
    }

    /// Returns the cached cover's URL if it already exists — avoids re-extraction.
    func getCachedPath(key: String) -> URL? {
        let file = cacheDir.appendingPathComponent("\(Self.keyHash(key)).jpg")
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    /// Removes the cached cover for a specific key (e.g. a file removed from its folder).
    func evict(key: String) {
        try? FileManager.default.removeItem(at: cacheDir.appendingPathComponent("\(Self.keyHash(key)).jpg"))
    }

    /// Clears the entire cover cache.
    func clearAll() {
        guard let files = try? FileManager.default.contentsOfDirectory(at: cacheDir, includingPropertiesForKeys: nil) else { return }
        files.forEach { try? FileManager.default.removeItem(at: $0) }
    }

    /// Decodes `data` directly to a thumbnail at most `maxDimension`px on the long
    /// edge using ImageIO's thumbnail-generation API, which downsamples *during*
    /// decode rather than fully decoding at native resolution first. This is the same
    /// header-then-bounded-decode shape as Android's `BitmapFactory.Options` two-pass
    /// approach (`inJustDecodeBounds`, then `inSampleSize`), and for the same reason:
    /// an adversarial oversized image (embedded in a hostile EPUB, say) must not be
    /// able to force a full-resolution decode into memory before it gets downsized —
    /// the `CoverArtCacheTest.kt` comments on the Android side call this out as a
    /// CVE-2020-0103-class concern; ImageIO's thumbnail path is the iOS equivalent
    /// guard. Returns nil for a corrupt/0×0 header, same as the Kotlin version.
    static func downsampledJPEG(from data: Data, maxDimension: Int) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }

        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int, width > 0,
              let height = properties[kCGImagePropertyPixelHeight] as? Int, height > 0
        else {
            return nil
        }

        let thumbnailOptions: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: maxDimension,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnailOptions as CFDictionary) else {
            return nil
        }

        return UIImage(cgImage: thumbnail).jpegData(compressionQuality: 0.85)
    }

    private static func keyHash(_ key: String) -> String {
        let digest = SHA256.hash(data: Data(key.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
