import XCTest
import SwiftUI
@testable import LibraVault

final class LibraVaultDesignSystemTests: XCTestCase {

    // MARK: - Color(hex:)

    func testHexColorMatchesComponents() {
        let color = Color(hex: 0x8B5E3C) // LeatherBrown
        let (r, g, b) = rgbComponents(of: color)
        XCTAssertEqual(r, 0x8B / 255.0, accuracy: 0.01)
        XCTAssertEqual(g, 0x5E / 255.0, accuracy: 0.01)
        XCTAssertEqual(b, 0x3C / 255.0, accuracy: 0.01)
    }

    // MARK: - ReadingTheme

    func testReadingThemeNextCyclesDarkLightSepiaDark() {
        XCTAssertEqual(ReadingTheme.dark.next, .light)
        XCTAssertEqual(ReadingTheme.light.next, .sepia)
        XCTAssertEqual(ReadingTheme.sepia.next, .dark)
    }

    func testReadingThemeNextIsAFullCycleWithNoFixedPoint() {
        // Every case should return to itself after exactly 3 steps, and never sooner —
        // guards against a typo collapsing two cases onto the same next value.
        for theme in ReadingTheme.allCases {
            let afterThree = theme.next.next.next
            XCTAssertEqual(afterThree, theme)
            XCTAssertNotEqual(theme.next, theme)
        }
    }

    func testReadingThemeSystemImageNamesAreDistinct() {
        let names = Set(ReadingTheme.allCases.map(\.systemImageName))
        XCTAssertEqual(names.count, ReadingTheme.allCases.count)
    }

    // MARK: - LibraVaultColorScheme

    func testForReadingThemeReturnsMatchingScheme() {
        XCTAssertEqual(
            rgbComponents(of: LibraVaultColorScheme.forReadingTheme(.dark).background).0,
            rgbComponents(of: LibraVaultColorScheme.dark.background).0
        )
        XCTAssertEqual(
            rgbComponents(of: LibraVaultColorScheme.forReadingTheme(.light).background).0,
            rgbComponents(of: LibraVaultColorScheme.light.background).0
        )
        XCTAssertEqual(
            rgbComponents(of: LibraVaultColorScheme.forReadingTheme(.sepia).background).0,
            rgbComponents(of: LibraVaultColorScheme.sepia.background).0
        )
    }

    func testSepiaSchemeUsesSepiaPalette() {
        let (r, g, b) = rgbComponents(of: LibraVaultColorScheme.sepia.background)
        XCTAssertEqual(r, 0xF5 / 255.0, accuracy: 0.01)
        XCTAssertEqual(g, 0xED / 255.0, accuracy: 0.01)
        XCTAssertEqual(b, 0xD6 / 255.0, accuracy: 0.01)
    }

    func testDarkAndLightSchemesInvertPrimaryRoles() {
        // Android's DarkColorScheme.primary == LeatherLight; LightColorScheme.primary == LeatherBrown.
        XCTAssertEqual(rgbComponents(of: LibraVaultColorScheme.dark.primary).0, rgbComponents(of: LibraVaultPalette.leatherLight).0)
        XCTAssertEqual(rgbComponents(of: LibraVaultColorScheme.light.primary).0, rgbComponents(of: LibraVaultPalette.leatherBrown).0)
    }

    // MARK: - Typography

    func testLoraFontsResolveToRegisteredFont() {
        let uiFont = UIFont(descriptor: UIFontDescriptor(fontAttributes: [.name: "Lora-Regular"]), size: 22)
        XCTAssertTrue(
            uiFont.familyName.localizedCaseInsensitiveContains("Lora"),
            "Expected Lora.ttf to be registered via Info.plist's UIAppFonts; got family '\(uiFont.familyName)'"
        )
    }

    // MARK: - Spacing / Shape scales stay in sync with Android's Dimens.kt / Shape.kt

    func testSpacingScaleMatchesDimens() {
        XCTAssertEqual(LibraVaultSpacing.xs, 4)
        XCTAssertEqual(LibraVaultSpacing.sm, 8)
        XCTAssertEqual(LibraVaultSpacing.md, 12)
        XCTAssertEqual(LibraVaultSpacing.lg, 16)
        XCTAssertEqual(LibraVaultSpacing.xl, 24)
        XCTAssertEqual(LibraVaultSpacing.xxl, 32)
        XCTAssertEqual(LibraVaultSpacing.coverWidth, 120)
        XCTAssertEqual(LibraVaultSpacing.miniBarHeight, 64)
        XCTAssertEqual(LibraVaultSpacing.topBarHeight, 56)
    }

    func testRadiusScaleMatchesShape() {
        XCTAssertEqual(LibraVaultRadius.cover, 6)
        XCTAssertEqual(LibraVaultRadius.card, 14)
        XCTAssertEqual(LibraVaultRadius.sheet, 20)
        XCTAssertEqual(LibraVaultRadius.sheetLarge, 28)
        XCTAssertEqual(LibraVaultRadius.chip, 8)
    }

    // MARK: - Helpers

    private func rgbComponents(of color: Color) -> (CGFloat, CGFloat, CGFloat) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        return (r, g, b)
    }
}
