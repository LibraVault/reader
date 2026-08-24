import XCTest
@testable import LibraVault

/// Regression guard for issue #495: `SettingsView.ttsSection`'s segmented Picker
/// bound to `$appState.ttsEngineType` over every case except `.cloud` (#491), so
/// when `CloudVoicesSection`'s "Use Cloud Voices" toggle set `ttsEngineType` to
/// `.cloud`, the picker's own selection matched none of its displayed segments —
/// SwiftUI renders that as nothing highlighted, visually disagreeing with the
/// toggle right below it. `showsCloudVoicesActiveLabel` is what `ttsSection` now
/// branches on to swap in a fixed label instead.
final class SettingsViewTtsSectionTests: XCTestCase {

    func testShowsCloudVoicesActiveLabelWhenCloudEngineIsActive() {
        XCTAssertTrue(SettingsView.showsCloudVoicesActiveLabel(engineType: .cloud))
    }

    func testShowsPickerWhenSystemEngineIsActive() {
        XCTAssertFalse(SettingsView.showsCloudVoicesActiveLabel(engineType: .system))
    }

    func testShowsPickerWhenPocketEngineIsActive() {
        XCTAssertFalse(SettingsView.showsCloudVoicesActiveLabel(engineType: .pocket))
    }
}
