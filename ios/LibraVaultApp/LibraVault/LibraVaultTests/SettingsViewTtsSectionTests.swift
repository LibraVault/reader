import AVFoundation
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

    // MARK: - System voice picker row (#506)

    func testShowsSystemVoicePickerRowOnlyWhenSystemEngineIsActive() {
        XCTAssertTrue(SettingsView.showsSystemVoicePickerRow(engineType: .system))
        XCTAssertFalse(SettingsView.showsSystemVoicePickerRow(engineType: .pocket))
        XCTAssertFalse(SettingsView.showsSystemVoicePickerRow(engineType: .cloud))
    }

    func testSystemVoiceDisplayNameIsAutomaticWhenNoSelection() {
        XCTAssertEqual(SettingsView.systemVoiceDisplayName(for: nil), "Automatic")
    }

    func testSystemVoiceDisplayNameIsAutomaticForAStaleIdentifier() {
        // Degrades the same way TTSEngineBridge.resolvedVoice does for a voice
        // that no longer resolves (e.g. a language pack removed since picked) -
        // rather than showing the raw, meaningless identifier string.
        XCTAssertEqual(SettingsView.systemVoiceDisplayName(for: "not-a-real-voice-identifier"), "Automatic")
    }

    func testSystemVoiceDisplayNameShowsTheRealVoiceNameForAValidIdentifier() throws {
        // AVSpeechSynthesisVoice(language:) queries the real installed voice
        // catalog - confirmed safe/available in the CI Simulator, unlike
        // actual playback (see TTSEngineBridgeTests). Skips rather than fails
        // if no English voice happens to be installed on the runner.
        guard let englishVoice = AVSpeechSynthesisVoice(language: "en-US") else {
            throw XCTSkip("No en-US voice installed on this runner")
        }
        XCTAssertEqual(SettingsView.systemVoiceDisplayName(for: englishVoice.identifier), englishVoice.name)
    }
}
