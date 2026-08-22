import XCTest
@testable import LibraVault

final class SupporterStatusPersistenceTests: XCTestCase {

    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "SupporterStatusPersistenceTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    func testLoadIsSupporterDefaultsToFalseWhenNothingSaved() {
        let persistence = SupporterStatusPersistence(defaults: makeIsolatedDefaults())
        XCTAssertFalse(persistence.loadIsSupporter())
    }

    func testSaveThenLoadRoundTripsIsSupporterTrue() {
        let defaults = makeIsolatedDefaults()
        SupporterStatusPersistence(defaults: defaults).save(isSupporter: true)
        XCTAssertTrue(SupporterStatusPersistence(defaults: defaults).loadIsSupporter())
    }

    func testSaveThenLoadRoundTripsIsSupporterFalse() {
        let defaults = makeIsolatedDefaults()
        SupporterStatusPersistence(defaults: defaults).save(isSupporter: true)
        SupporterStatusPersistence(defaults: defaults).save(isSupporter: false)
        XCTAssertFalse(SupporterStatusPersistence(defaults: defaults).loadIsSupporter())
    }

    /// Regression guard for the actual reason this cache exists: a fresh
    /// `SupporterStatusPersistence` instance backed by the *same* `UserDefaults`
    /// suite as one that already saved `true` must read `true` back — this is what
    /// lets a tip purchase from a previous launch survive into a new
    /// `StoreKitBillingManager` instance without re-querying StoreKit.
    func testIsSupporterPersistsAcrossPersistenceInstances() {
        let defaults = makeIsolatedDefaults()
        SupporterStatusPersistence(defaults: defaults).save(isSupporter: true)

        let reloaded = SupporterStatusPersistence(defaults: defaults)
        XCTAssertTrue(reloaded.loadIsSupporter())
    }
}
