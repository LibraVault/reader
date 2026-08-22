import XCTest
@testable import LibraVault

/// Exercises `EncryptedVaultContentsView.errorBannerText` and
/// `ImportProgressSheet.errorDisplay` directly — the two static functions `body`
/// calls for #417's fix — rather than only `EncryptedVaultContentsViewModel`'s state,
/// the way `EncryptedVaultContentsViewModelTests` does. QA on PR #418 round 1 flagged
/// that every test there lived entirely in the view model and would still pass
/// unchanged even if the view-layer wiring added for #417 were fully reverted. This
/// project has no ViewInspector/snapshot UI-testing infrastructure, so no test here
/// can prove SwiftUI's `body` actually renders these values on screen — see each
/// function's own doc comment for why the untestable surface is kept to a single
/// one-line call instead.
final class EncryptedVaultContentsViewTests: XCTestCase {

    func testErrorBannerTextReturnsTheMessageWhenPresent() {
        XCTAssertEqual(EncryptedVaultContentsView.errorBannerText(errorMessage: "manifest is unreadable"), "manifest is unreadable")
    }

    func testErrorBannerTextIsNilWhenThereIsNoError() {
        XCTAssertNil(EncryptedVaultContentsView.errorBannerText(errorMessage: nil))
    }

    func testErrorDisplayReturnsTheMessageAndAnAccessibilityLabelForAnErrorStatus() {
        let display = ImportProgressSheet.errorDisplay(for: .error("unsupported.xyz isn't a format LibraVault reads."))
        XCTAssertEqual(display?.text, "unsupported.xyz isn't a format LibraVault reads.")
        XCTAssertEqual(display?.accessibilityLabel, "Import failed: unsupported.xyz isn't a format LibraVault reads.")
    }

    func testErrorDisplayIsNilForEveryNonErrorStatus() {
        XCTAssertNil(ImportProgressSheet.errorDisplay(for: .pending))
        XCTAssertNil(ImportProgressSheet.errorDisplay(for: .importing))
        XCTAssertNil(ImportProgressSheet.errorDisplay(for: .done))
    }
}
