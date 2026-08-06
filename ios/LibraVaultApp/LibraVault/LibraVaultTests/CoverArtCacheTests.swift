import XCTest
import UIKit
@testable import LibraVault

final class CoverArtCacheTests: XCTestCase {
    private var cache: CoverArtCache!

    override func setUpWithError() throws {
        cache = CoverArtCache()
        cache.clearAll()
    }

    override func tearDownWithError() throws {
        cache.clearAll()
    }

    /// Draws a solid-color raster image of `size` and encodes it as PNG — a real,
    /// decodable image (not a synthetic stand-in), matching the fixture-building
    /// approach `PDFParserTests`/`EPUBParserTests` use elsewhere in this target.
    ///
    /// `format.scale = 1` is required: `UIGraphicsImageRenderer`'s default format
    /// scale comes from the current trait collection (3x on the iPhone 17 Simulator
    /// this project's CI runs), so an unpinned renderer would silently bake a 100x50
    /// *point* size into a 300x150 *pixel* PNG — the size assertions below compare
    /// against actual decoded pixel dimensions, not points, so they'd fail against a
    /// scaled fixture even though `CoverArtCache` behaved correctly.
    private func makeFixtureImage(size: CGSize) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let image = renderer.image { context in
            UIColor.red.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        return image.pngData()!
    }

    private func pixelSize(of url: URL) -> CGSize? {
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else { return nil }
        return image.size
    }

    func testSaveDownsamplesLargeImageToCap() throws {
        let imageData = makeFixtureImage(size: CGSize(width: 2000, height: 1000))

        let url = try XCTUnwrap(cache.save(key: "large-\(UUID())", imageData: imageData))
        let size = try XCTUnwrap(pixelSize(of: url))

        XCTAssertLessThanOrEqual(max(size.width, size.height), CGFloat(CoverArtCache.maxCoverPx))
        // Aspect ratio (2:1) should survive downsampling.
        XCTAssertEqual(size.width / size.height, 2.0, accuracy: 0.05)
    }

    func testSaveDoesNotUpscaleImageSmallerThanCap() throws {
        let imageData = makeFixtureImage(size: CGSize(width: 100, height: 50))

        let url = try XCTUnwrap(cache.save(key: "small-\(UUID())", imageData: imageData))
        let size = try XCTUnwrap(pixelSize(of: url))

        XCTAssertLessThanOrEqual(size.width, 100)
        XCTAssertLessThanOrEqual(size.height, 50)
    }

    func testSaveReturnsNilForCorruptData() {
        let corrupt = Data("this is not an image".utf8)
        XCTAssertNil(cache.save(key: "corrupt-\(UUID())", imageData: corrupt))
    }

    func testGetCachedPathIsNilUntilSaved() {
        let key = "roundtrip-\(UUID())"
        XCTAssertNil(cache.getCachedPath(key: key))

        _ = cache.save(key: key, imageData: makeFixtureImage(size: CGSize(width: 64, height: 64)))

        XCTAssertNotNil(cache.getCachedPath(key: key))
    }

    func testDifferentKeysProduceDifferentFiles() {
        let imageData = makeFixtureImage(size: CGSize(width: 64, height: 64))
        let keyA = "a-\(UUID())"
        let keyB = "b-\(UUID())"

        let urlA = cache.save(key: keyA, imageData: imageData)
        let urlB = cache.save(key: keyB, imageData: imageData)

        XCTAssertNotEqual(urlA, urlB)
    }

    func testEvictRemovesCachedFile() {
        let key = "evict-\(UUID())"
        _ = cache.save(key: key, imageData: makeFixtureImage(size: CGSize(width: 64, height: 64)))
        XCTAssertNotNil(cache.getCachedPath(key: key))

        cache.evict(key: key)

        XCTAssertNil(cache.getCachedPath(key: key))
    }

    func testClearAllRemovesEveryCachedFile() {
        let keys = (0..<3).map { "clear-\($0)-\(UUID())" }
        for key in keys {
            _ = cache.save(key: key, imageData: makeFixtureImage(size: CGSize(width: 64, height: 64)))
        }
        for key in keys {
            XCTAssertNotNil(cache.getCachedPath(key: key))
        }

        cache.clearAll()

        for key in keys {
            XCTAssertNil(cache.getCachedPath(key: key))
        }
    }
}
