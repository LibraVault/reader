import XCTest
@testable import LibraVault

final class SystemVoicePickerSectionTests: XCTestCase {
    private let voices = [
        SystemVoiceInfo(identifier: "voice.en-us.samantha", name: "Samantha", language: "en-US", quality: "Standard"),
        SystemVoiceInfo(identifier: "voice.en-gb.daniel", name: "Daniel", language: "en-GB", quality: "Enhanced"),
    ]

    // MARK: - selectedVoiceLabel

    func testSelectedVoiceLabelIsAutomaticWhenNoneSelected() {
        XCTAssertEqual(
            SystemVoicePickerRow.selectedVoiceLabel(selectedVoiceIdentifier: nil, voices: voices),
            "Automatic"
        )
    }

    func testSelectedVoiceLabelIsTheMatchingVoicesName() {
        XCTAssertEqual(
            SystemVoicePickerRow.selectedVoiceLabel(selectedVoiceIdentifier: "voice.en-gb.daniel", voices: voices),
            "Daniel"
        )
    }

    /// A stale identifier with no matching installed voice must read the same as
    /// "nothing selected" — it never claims a voice is picked that speech won't
    /// actually use (mirrors `TTSEngineBridge.voice(for:)`'s own fallback for the
    /// same case).
    func testSelectedVoiceLabelFallsBackToAutomaticForAnUnknownIdentifier() {
        XCTAssertEqual(
            SystemVoicePickerRow.selectedVoiceLabel(selectedVoiceIdentifier: "not-installed", voices: voices),
            "Automatic"
        )
    }

    // MARK: - search filtering

    func testFilteredVoicesReturnsAllVoicesForEmptySearch() {
        XCTAssertEqual(SystemVoicePickerList.filteredVoices(voices, matching: ""), voices)
    }

    func testFilteredVoicesMatchesByName() {
        let filtered = SystemVoicePickerList.filteredVoices(voices, matching: "sam")
        XCTAssertEqual(filtered.map(\.identifier), ["voice.en-us.samantha"])
    }

    func testFilteredVoicesMatchesByLanguageCaseInsensitively() {
        let filtered = SystemVoicePickerList.filteredVoices(voices, matching: "EN-GB")
        XCTAssertEqual(filtered.map(\.identifier), ["voice.en-gb.daniel"])
    }

    func testFilteredVoicesReturnsEmptyForNoMatch() {
        XCTAssertTrue(SystemVoicePickerList.filteredVoices(voices, matching: "zzz-nonexistent").isEmpty)
    }
}
