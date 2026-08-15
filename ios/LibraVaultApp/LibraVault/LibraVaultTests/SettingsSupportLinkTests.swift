import XCTest
@testable import LibraVault

/// Regression guard for `SettingsView.supportURL` — the one place iOS defines
/// the Support button's destination. The Android app defines the identical
/// string independently as `SUPPORT_URL` in feature:settings's SupportLink.kt
/// (see its own SupportLinkTest); keep both in sync by hand if this ever
/// changes.
final class SettingsSupportLinkTests: XCTestCase {

    func testSupportURLMatchesTheWebsitesActualPageExactly() {
        XCTAssertEqual(SettingsView.supportURL.absoluteString, "https://libravault.xyz/support.html")
    }

    func testSupportURLIsHTTPS() {
        XCTAssertEqual(SettingsView.supportURL.scheme, "https")
        XCTAssertEqual(SettingsView.supportURL.host, "libravault.xyz")
    }
}
