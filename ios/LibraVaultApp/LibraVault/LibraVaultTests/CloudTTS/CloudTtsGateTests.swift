import XCTest
@testable import LibraVault

final class CloudTtsGateTests: XCTestCase {

    func testGateOpenOnlyWhenBothSubscribedAndConsented() {
        XCTAssertTrue(CloudTtsGate.canUseCloudTts(isSubscribed: true, consentEnabled: true))
    }

    func testGateClosedWhenSubscribedButNotConsented() {
        XCTAssertFalse(CloudTtsGate.canUseCloudTts(isSubscribed: true, consentEnabled: false))
    }

    func testGateClosedWhenConsentedButNotSubscribed() {
        XCTAssertFalse(CloudTtsGate.canUseCloudTts(isSubscribed: false, consentEnabled: true))
    }

    func testGateClosedWhenNeitherSubscribedNorConsented() {
        XCTAssertFalse(CloudTtsGate.canUseCloudTts(isSubscribed: false, consentEnabled: false))
    }

    /// Regression guard for the exact bug class this gate exists to prevent: buying the
    /// subscription alone must never be sufficient on its own (PRD §4) — this is the
    /// single most safety-critical assertion in this file even though it's implied by
    /// the four cases above.
    func testSubscriptionAloneNeverOpensTheGate() {
        XCTAssertFalse(CloudTtsGate.canUseCloudTts(isSubscribed: true, consentEnabled: false))
    }
}
