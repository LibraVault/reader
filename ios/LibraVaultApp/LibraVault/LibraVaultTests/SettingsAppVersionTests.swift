import XCTest
@testable import LibraVault

/// Regression guard for `SettingsView.appVersion` — it used to be a hardcoded
/// "3.0.0-alpha" literal that never matched what Xcode's MARKETING_VERSION
/// (and therefore TestFlight / App Store Connect) actually shipped. It must
/// read the bundle's real `CFBundleShortVersionString` instead, matching how
/// Android's SettingsViewModel reads `versionName` from PackageManager.
final class SettingsAppVersionTests: XCTestCase {

    func testAppVersionMatchesTheBundlesRealMarketingVersion() {
        let expected = Bundle(for: Self.self).infoDictionary?["CFBundleShortVersionString"] as? String
        XCTAssertEqual(SettingsView.appVersion, expected)
    }

    func testAppVersionIsNotTheOldHardcodedPlaceholder() {
        XCTAssertNotEqual(SettingsView.appVersion, "3.0.0-alpha")
    }
}
