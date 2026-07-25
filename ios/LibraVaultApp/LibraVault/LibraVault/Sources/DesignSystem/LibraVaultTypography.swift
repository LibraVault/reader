import SwiftUI
import UIKit

/// Lora is a variable font (wght axis 400–700, registered via Fonts/Lora.ttf +
/// Info.plist's UIAppFonts — same file as Android's core/ui res/font/lora.ttf).
/// SwiftUI's `Font.custom` can't drive a variable axis directly, so this resolves
/// a specific weight through UIFontDescriptor, the same mechanism Android's
/// `FontVariation.Settings(FontVariation.weight(...))` maps to at the OS level.
private enum Lora {
    /// The 'wght' axis identifier (four-char code packed as UInt32), per the
    /// OpenType spec — `UIFontDescriptor` has no named constant for it.
    private static let wghtAxis: UInt32 = 0x77_67_68_74

    static func font(size: CGFloat, weight: CGFloat) -> Font {
        let descriptor = UIFontDescriptor(fontAttributes: [
            .name: "Lora-Regular",
            UIFontDescriptor.AttributeName(rawValue: "NSCTFontVariationAttribute"): [wghtAxis: weight],
        ])
        return Font(UIFont(descriptor: descriptor, size: size))
    }
}

/// Mirrors core/ui/theme/Type.kt's `LibravaultTypography` scale — same 15 styles,
/// same sizes/weights, Lora for display/headline/title, system sans for everything else.
enum LibraVaultTypography {
    static let displayLarge   = Lora.font(size: 40, weight: 700)
    static let displayMedium  = Lora.font(size: 32, weight: 700)
    static let displaySmall   = Lora.font(size: 28, weight: 600)
    static let headlineLarge  = Lora.font(size: 28, weight: 600)
    static let headlineMedium = Lora.font(size: 22, weight: 600)
    static let headlineSmall  = Lora.font(size: 18, weight: 600)
    static let titleLarge     = Lora.font(size: 18, weight: 500)

    static let titleMedium = Font.system(size: 16, weight: .medium)
    static let titleSmall  = Font.system(size: 14, weight: .medium)
    static let bodyLarge   = Font.system(size: 16, weight: .regular)
    static let bodyMedium  = Font.system(size: 14, weight: .regular)
    static let bodySmall   = Font.system(size: 12, weight: .regular)
    static let labelLarge  = Font.system(size: 14, weight: .medium)
    static let labelMedium = Font.system(size: 12, weight: .medium)
    static let labelSmall  = Font.system(size: 11, weight: .medium)
}
