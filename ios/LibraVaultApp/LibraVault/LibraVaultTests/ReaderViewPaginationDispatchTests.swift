import UIKit
import XCTest
@testable import LibraVault

/// Exercises `ReaderView.dispatchChapterPagination(...)` — the actual off-main-thread
/// dispatch mechanics `repaginate(for:)` uses for issue #336 — rather than only
/// `BlockPaginator.paginate`'s pure output the way `BlockPaginatorTests` does. QA on
/// PR #411 flagged that the original tests would still pass unchanged even if
/// `repaginate(for:)` were reverted to run `BlockPaginator.paginate` synchronously
/// inline on the main thread, since neither new test there touched `ReaderView` at
/// all. This test calls the real dispatch function directly with a `paginate` spy
/// that records which thread it actually ran on, so that specific revert fails here.
final class ReaderViewPaginationDispatchTests: XCTestCase {

    private let font = UIFont.systemFont(ofSize: 16)
    private let pageSize = CGSize(width: 320, height: 480)

    private func paragraph(_ text: String) -> MarkdownBlock {
        .paragraph(text: [MarkdownInlineRun(text: text, bold: false, italic: false, code: false)])
    }

    /// The core regression guard for issue #336: `paginate` must be invoked off the
    /// main thread. If `dispatchChapterPagination` (or `repaginate(for:)`'s use of it)
    /// were reverted to call `paginate` synchronously on the caller's thread, this
    /// assertion fails, since this test itself runs on the main thread.
    func testPaginateRunsOffTheMainThread() {
        let chapters = [BookChapter(title: "Ch1", text: "", blocks: [paragraph("Hello, world.")])]
        var paginateRanOnMainThread: Bool?
        let done = expectation(description: "dispatch completes")

        ReaderView.dispatchChapterPagination(
            chapters: chapters,
            font: font,
            lineSpacing: 4,
            pageSize: pageSize,
            paginate: { blocks, images, font, lineSpacing, pageSize in
                paginateRanOnMainThread = Thread.isMainThread
                return BlockPaginator.paginate(blocks: blocks, images: images, font: font, lineSpacing: lineSpacing, pageSize: pageSize)
            },
            apply: { _ in done.fulfill() }
        )

        wait(for: [done], timeout: 5)
        XCTAssertEqual(
            paginateRanOnMainThread, false,
            "pagination must run off the main thread (issue #336) — this test is invoked from the main thread, "
                + "so a revert back to a synchronous inline call would make this true and fail here"
        )
    }

    /// `apply` (the closure that actually mutates `ReaderView`'s `@State`) must land
    /// back on the main actor — SwiftUI state can't be safely mutated off it.
    func testApplyRunsOnTheMainActor() {
        let chapters = [BookChapter(title: "Ch1", text: "", blocks: [paragraph("Hello, world.")])]
        var applyRanOnMainThread: Bool?
        let done = expectation(description: "dispatch completes")

        ReaderView.dispatchChapterPagination(
            chapters: chapters,
            font: font,
            lineSpacing: 4,
            pageSize: pageSize,
            paginate: BlockPaginator.paginate,
            apply: { _ in
                applyRanOnMainThread = Thread.isMainThread
                done.fulfill()
            }
        )

        wait(for: [done], timeout: 5)
        XCTAssertEqual(applyRanOnMainThread, true)
    }

    /// One entry in `apply`'s result per input chapter, each equal to what
    /// `BlockPaginator.paginate` itself would produce for that chapter — confirms the
    /// dispatch wrapper doesn't drop, reorder, or corrupt chapters on its way through
    /// the background hop.
    func testAppliedResultMatchesDirectPaginationPerChapter() {
        let chapterA = BookChapter(title: "A", text: "", blocks: [paragraph("Chapter A text.")])
        let chapterB = BookChapter(title: "B", text: "", blocks: [paragraph("Chapter B text.")])
        let chapters = [chapterA, chapterB]
        var result: [[[MarkdownBlock]]]?
        let done = expectation(description: "dispatch completes")

        ReaderView.dispatchChapterPagination(
            chapters: chapters,
            font: font,
            lineSpacing: 4,
            pageSize: pageSize,
            paginate: BlockPaginator.paginate,
            apply: { newBlockPagination in
                result = newBlockPagination
                done.fulfill()
            }
        )

        wait(for: [done], timeout: 5)
        let expected = chapters.map {
            BlockPaginator.paginate(blocks: $0.blocks, images: $0.images, font: font, lineSpacing: 4, pageSize: pageSize)
        }
        XCTAssertEqual(result, expected)
    }
}
