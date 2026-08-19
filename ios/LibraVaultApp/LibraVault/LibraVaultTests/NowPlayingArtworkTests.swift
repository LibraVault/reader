import XCTest
import MediaPlayer
import UIKit
@testable import LibraVault

/// Covers `SystemNowPlayingManager`'s lazy Now Playing artwork decode (issue #321) —
/// `update(_:)` itself no-ops under XCTest (see `isRunningUnderXCTest`'s doc comment
/// on `SystemNowPlayingManager`, guarding against talking to the real
/// `MPNowPlayingInfoCenter`/`nowplayingd` in the headless CI Simulator), so these
/// exercise the `internal static` decode helpers directly — pure ImageIO/Foundation
/// work with no daemon involved, the same reasoning `CoverArtCacheTests` relies on.
final class NowPlayingArtworkTests: XCTestCase {
    private var tempFiles: [URL] = []

    override func tearDownWithError() throws {
        for url in tempFiles {
            try? FileManager.default.removeItem(at: url)
        }
        tempFiles = []
    }

    /// Mirrors `CoverArtCacheTests.makeFixtureImage` — a real, decodable raster image
    /// (not a synthetic stand-in), scale pinned to 1 so pixel-size assertions below
    /// aren't thrown off by the Simulator's default trait-collection scale.
    private func writeFixtureImage(size: CGSize) throws -> URL {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let image = renderer.image { context in
            UIColor.blue.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).png")
        try image.pngData()!.write(to: url)
        tempFiles.append(url)
        return url
    }

    // MARK: - decodeArtwork

    func testDecodeArtworkDownsamplesToTheRequestedSize() throws {
        let url = try writeFixtureImage(size: CGSize(width: 2000, height: 1000))

        let image = SystemNowPlayingManager.decodeArtwork(atPath: url.path, requestedSize: CGSize(width: 200, height: 100))

        XCTAssertLessThanOrEqual(max(image.size.width, image.size.height), 200)
        XCTAssertEqual(image.size.width / image.size.height, 2.0, accuracy: 0.05, "aspect ratio should survive downsampling")
    }

    func testDecodeArtworkDoesNotUpscaleAnImageSmallerThanRequested() throws {
        let url = try writeFixtureImage(size: CGSize(width: 50, height: 50))

        let image = SystemNowPlayingManager.decodeArtwork(atPath: url.path, requestedSize: CGSize(width: 512, height: 512))

        XCTAssertLessThanOrEqual(image.size.width, 50)
        XCTAssertLessThanOrEqual(image.size.height, 50)
    }

    func testDecodeArtworkReturnsEmptyImageForAMissingFile() {
        let missing = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).png").path

        let image = SystemNowPlayingManager.decodeArtwork(atPath: missing, requestedSize: CGSize(width: 200, height: 200))

        XCTAssertEqual(image.size, .zero)
    }

    func testDecodeArtworkReturnsEmptyImageForCorruptData() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID()).png")
        try Data("not an image".utf8).write(to: url)
        tempFiles.append(url)

        let image = SystemNowPlayingManager.decodeArtwork(atPath: url.path, requestedSize: CGSize(width: 200, height: 200))

        XCTAssertEqual(image.size, .zero)
    }

    func testDecodeArtworkClampsARequestedSizeOfZero() throws {
        let url = try writeFixtureImage(size: CGSize(width: 100, height: 100))

        let image = SystemNowPlayingManager.decodeArtwork(atPath: url.path, requestedSize: .zero)

        XCTAssertGreaterThan(image.size.width, 0, "a degenerate requested size must not crash the ImageIO thumbnail call or produce an unusable 0x0 image")
    }

    // MARK: - artwork(forCoverAt:)

    func testArtworkDeclaresBoundsSizeMatchingTheCoverCap() throws {
        let url = try writeFixtureImage(size: CGSize(width: 100, height: 100))

        let artwork = SystemNowPlayingManager.artwork(forCoverAt: url.path)

        XCTAssertEqual(artwork.bounds.size, CGSize(width: CGFloat(CoverArtCache.maxCoverPx), height: CGFloat(CoverArtCache.maxCoverPx)))
    }

    /// End-to-end through the public `MPMediaItemArtwork` API: `.image(at:)` is what
    /// actually invokes the request handler, mirroring what MediaPlayer itself does
    /// only if/when it needs pixels — the laziness this issue is about.
    func testArtworkLazilyDecodesAtTheSizeRequestedThroughImageAt() throws {
        let url = try writeFixtureImage(size: CGSize(width: 2000, height: 1000))

        let artwork = SystemNowPlayingManager.artwork(forCoverAt: url.path)
        let image = artwork.image(at: CGSize(width: 200, height: 100))

        let size = try XCTUnwrap(image?.size)
        XCTAssertLessThanOrEqual(max(size.width, size.height), 200)
    }
}
