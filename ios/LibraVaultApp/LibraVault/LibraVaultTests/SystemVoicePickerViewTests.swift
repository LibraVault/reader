import AVFoundation
import XCTest
@testable import LibraVault

/// Unit tests for `SystemVoicePickerView.grouped(_:)`, the pure
/// language-grouping/sorting logic behind #506's voice picker. Runs against
/// the real `AVSpeechSynthesisVoice.speechVoices()` catalog where it can -
/// `AVSpeechSynthesisVoice` has no public initializer, so a synthetic voice
/// fixture isn't possible, and voice *catalog* queries (unlike actual
/// playback) are confirmed safe/available in the CI Simulator - see
/// `TTSEngineBridgeTests`'s own doc comment for that same distinction.
final class SystemVoicePickerViewTests: XCTestCase {

    func testGroupedDropsNoVoices() {
        let voices = AVSpeechSynthesisVoice.speechVoices()
        let groups = SystemVoicePickerView.grouped(voices)

        let totalGrouped = groups.reduce(0) { $0 + $1.voices.count }
        XCTAssertEqual(totalGrouped, voices.count, "grouping should partition, not drop, every voice")
    }

    func testGroupedSortsLanguageGroupsByDisplayName() {
        let groups = SystemVoicePickerView.grouped(AVSpeechSynthesisVoice.speechVoices())
        let displayNames = groups.map(\.languageDisplayName)
        XCTAssertEqual(displayNames, displayNames.sorted(), "language groups should be alphabetically sorted")
    }

    func testGroupedSortsVoicesWithinEachGroupByName() {
        let groups = SystemVoicePickerView.grouped(AVSpeechSynthesisVoice.speechVoices())
        for group in groups {
            let names = group.voices.map(\.name)
            XCTAssertEqual(names, names.sorted(), "voices within \(group.languageDisplayName) should be sorted by name")
        }
    }

    func testGroupedOnEmptyInputReturnsNoGroups() {
        XCTAssertTrue(SystemVoicePickerView.grouped([]).isEmpty)
    }

    func testEachVoiceLandsInExactlyOneGroupMatchingItsOwnLanguageCode() {
        let voices = AVSpeechSynthesisVoice.speechVoices()
        let groups = SystemVoicePickerView.grouped(voices)
        for voice in voices {
            let matchingGroups = groups.filter { $0.voices.contains(where: { $0.identifier == voice.identifier }) }
            XCTAssertEqual(matchingGroups.count, 1, "\(voice.identifier) should appear in exactly one group")
            XCTAssertEqual(matchingGroups.first?.languageCode, voice.language)
        }
    }
}
