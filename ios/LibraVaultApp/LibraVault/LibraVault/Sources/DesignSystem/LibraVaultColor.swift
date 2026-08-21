import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Raw brand palette — mirrors core/ui/theme/Color.kt on Android value-for-value.
enum LibraVaultPalette {
    // Brand — warm leather & aged gold
    static let leatherBrown = Color(hex: 0x8B5E3C)
    static let leatherDark  = Color(hex: 0x3D2208)
    static let leatherLight = Color(hex: 0xE8C898)

    // Accent — aged brass
    static let agedBrass = Color(hex: 0xC9A24B)

    // Neutrals — warm, not cool grey
    static let warmNeutral900 = Color(hex: 0x1A0E04)
    static let warmNeutral700 = Color(hex: 0x3D2208)
    static let warmNeutral500 = Color(hex: 0x8B6E47)
    static let warmNeutral400 = Color(hex: 0xA89072)
    static let warmNeutral300 = Color(hex: 0xD9C5A6)
    static let warmNeutral200 = Color(hex: 0xE8D5BC)
    static let warmNeutral100 = Color(hex: 0xF5EDE0)
    static let warmNeutral50  = Color(hex: 0xFBF7F2)
    static let warmGrey400    = Color(hex: 0xB8A98E)

    // Sepia (reading mode)
    static let sepiaBackground = Color(hex: 0xF5EDD6)
    static let sepiaText       = Color(hex: 0x3B2F1E)
    static let sepiaOutline    = Color(hex: 0x7A5C3E)

    // Surface ramp (dark mode) — 3-step leather hierarchy
    static let darkSurface0 = Color(hex: 0x1A1410)
    static let darkSurface1 = Color(hex: 0x241B14)
    static let darkSurface2 = Color(hex: 0x2E231A)
}

/// Mirrors the 13-role Material 3 ColorScheme surface from Theme.kt.
struct LibraVaultColorScheme {
    let primary: Color
    let onPrimary: Color
    let primaryContainer: Color
    let onPrimaryContainer: Color
    let secondary: Color
    let onSecondary: Color
    let background: Color
    let onBackground: Color
    let surface: Color
    let onSurface: Color
    let surfaceVariant: Color
    let onSurfaceVariant: Color
    let outline: Color

    static let light = LibraVaultColorScheme(
        primary: LibraVaultPalette.leatherBrown,
        onPrimary: LibraVaultPalette.warmNeutral50,
        primaryContainer: LibraVaultPalette.leatherLight,
        onPrimaryContainer: LibraVaultPalette.leatherDark,
        secondary: LibraVaultPalette.agedBrass,
        onSecondary: LibraVaultPalette.warmNeutral900,
        background: LibraVaultPalette.warmNeutral50,
        onBackground: LibraVaultPalette.warmNeutral900,
        surface: LibraVaultPalette.warmNeutral100,
        onSurface: LibraVaultPalette.warmNeutral900,
        surfaceVariant: LibraVaultPalette.warmNeutral300,
        onSurfaceVariant: LibraVaultPalette.warmNeutral700,
        outline: LibraVaultPalette.warmNeutral700
    )

    static let dark = LibraVaultColorScheme(
        primary: LibraVaultPalette.leatherLight,
        onPrimary: LibraVaultPalette.warmNeutral900,
        primaryContainer: LibraVaultPalette.leatherDark,
        onPrimaryContainer: LibraVaultPalette.leatherLight,
        secondary: LibraVaultPalette.agedBrass,
        onSecondary: LibraVaultPalette.warmNeutral900,
        background: LibraVaultPalette.darkSurface0,
        onBackground: LibraVaultPalette.warmNeutral100,
        surface: LibraVaultPalette.darkSurface1,
        onSurface: LibraVaultPalette.warmNeutral100,
        surfaceVariant: LibraVaultPalette.darkSurface2,
        onSurfaceVariant: LibraVaultPalette.warmGrey400,
        outline: LibraVaultPalette.warmNeutral500
    )

    static let sepia = LibraVaultColorScheme(
        primary: LibraVaultPalette.leatherBrown,
        onPrimary: LibraVaultPalette.warmNeutral50,
        primaryContainer: LibraVaultPalette.leatherLight,
        onPrimaryContainer: LibraVaultPalette.leatherDark,
        secondary: LibraVaultPalette.agedBrass,
        onSecondary: LibraVaultPalette.warmNeutral900,
        background: LibraVaultPalette.sepiaBackground,
        onBackground: LibraVaultPalette.sepiaText,
        surface: LibraVaultPalette.sepiaBackground,
        onSurface: LibraVaultPalette.sepiaText,
        surfaceVariant: Color(hex: 0xE3D5B6),
        onSurfaceVariant: LibraVaultPalette.sepiaText.opacity(0.7),
        outline: LibraVaultPalette.sepiaOutline
    )

    /// Takes `ConcreteReadingTheme`, not `ReadingTheme` — callers resolve `.system`
    /// against the environment's colorScheme first (see `ReadingTheme.resolved(for:)`),
    /// so this switch never needs an arm for a case that has nothing to resolve it.
    static func forReadingTheme(_ theme: ConcreteReadingTheme) -> LibraVaultColorScheme {
        switch theme {
        case .dark:  return .dark
        case .light: return .light
        case .sepia: return .sepia
        }
    }
}

/// App-chrome colors that should track the system light/dark appearance automatically.
/// Backed by the LV*.colorset entries in Assets.xcassets (same light/dark values as
/// LibraVaultColorScheme.light / .dark above) rather than the `Color` constants directly,
/// so SwiftUI re-resolves them on trait-collection changes without extra plumbing.
enum LibraVaultColor {
    static let primary            = Color("LVPrimary", bundle: .main)
    static let onPrimary          = Color("LVOnPrimary", bundle: .main)
    static let primaryContainer   = Color("LVPrimaryContainer", bundle: .main)
    static let onPrimaryContainer = Color("LVOnPrimaryContainer", bundle: .main)
    static let secondary          = Color("LVSecondary", bundle: .main)
    static let onSecondary        = Color("LVOnSecondary", bundle: .main)
    static let background         = Color("LVBackground", bundle: .main)
    static let onBackground       = Color("LVOnBackground", bundle: .main)
    static let surface            = Color("LVSurface", bundle: .main)
    static let onSurface          = Color("LVOnSurface", bundle: .main)
    static let surfaceVariant     = Color("LVSurfaceVariant", bundle: .main)
    static let onSurfaceVariant   = Color("LVOnSurfaceVariant", bundle: .main)
    static let outline            = Color("LVOutline", bundle: .main)
}
