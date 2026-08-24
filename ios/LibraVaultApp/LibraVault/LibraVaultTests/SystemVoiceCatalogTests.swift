import XCTest
import AVFoundation
@testable import LibraVault

/// `AVSpeechSynthesisVoice.speechVoices()` itself depends on the real device/Simulator
/// voice catalog, which CI doesn't control (same reasoning as `TTSEngineBridgeTests`'
/// own doc comment on `voice(for:)`), so this covers the pure, unit-testable half:
/// `displayLabel(for:)`'s mapping from `AVSpeechSynthesisVoiceQuality` to a UI label.
final class SystemVoiceCatalogTests: XCTestCase {
    func testDisplayLabelForDefaultQualityIsStandard() {
        XCTAssertEqual(SystemVoiceCatalog.displayLabel(for: .default), "Standard")
    }

    func testDisplayLabelForEnhancedQualityIsEnhanced() {
        XCTAssertEqual(SystemVoiceCatalog.displayLabel(for: .enhanced), "Enhanced")
    }

    func testDisplayLabelForPremiumQualityIsPremium() {
        XCTAssertEqual(SystemVoiceCatalog.displayLabel(for: .premium), "Premium")
    }

    func testDisplayLabelForMaleGenderIsMale() {
        XCTAssertEqual(SystemVoiceCatalog.displayLabel(for: .male), "Male")
    }

    func testDisplayLabelForFemaleGenderIsFemale() {
        XCTAssertEqual(SystemVoiceCatalog.displayLabel(for: .female), "Female")
    }

    func testDisplayLabelForUnspecifiedGenderIsNil() {
        XCTAssertNil(SystemVoiceCatalog.displayLabel(for: .unspecified))
    }

    /// `availableVoices()` itself is a thin wrapper around real system state, so this
    /// only asserts the one thing that's actually deterministic regardless of what
    /// the Simulator's installed catalog looks like: whatever it returns comes back
    /// sorted by language, then name.
    func testAvailableVoicesIsSortedByLanguageThenName() {
        let voices = SystemVoiceCatalog.availableVoices()
        for (lhs, rhs) in zip(voices, voices.dropFirst()) {
            if lhs.language == rhs.language {
                XCTAssertLessThanOrEqual(lhs.name, rhs.name)
            } else {
                XCTAssertLessThan(lhs.language, rhs.language)
            }
        }
    }
}
