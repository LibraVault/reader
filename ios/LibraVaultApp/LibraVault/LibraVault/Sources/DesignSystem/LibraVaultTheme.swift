import SwiftUI

/// Mirrors core/ui/theme/Theme.kt's `ReadingTheme` enum.
enum ReadingTheme: String, CaseIterable {
    case dark, light, sepia

    var label: String {
        switch self {
        case .dark:  return "Dark"
        case .light: return "Light"
        case .sepia: return "Sepia"
        }
    }
}

private struct ReadingThemeKey: EnvironmentKey {
    static let defaultValue: ReadingTheme = .dark
}

extension EnvironmentValues {
    var readingTheme: ReadingTheme {
        get { self[ReadingThemeKey.self] }
        set { self[ReadingThemeKey.self] = newValue }
    }
}

private struct LibraVaultColorSchemeKey: EnvironmentKey {
    static let defaultValue = LibraVaultColorScheme.light
}

extension EnvironmentValues {
    /// The resolved reading-mode color scheme (Dark/Light/Sepia). Distinct from the
    /// system-appearance-driven `LibraVaultColor.*` tokens, which cover app chrome —
    /// this one covers the book content surface, which the user picks explicitly.
    var libraVaultColors: LibraVaultColorScheme {
        get { self[LibraVaultColorSchemeKey.self] }
        set { self[LibraVaultColorSchemeKey.self] = newValue }
    }
}

/// Equivalent of the `LibravaultTheme` composable: applies the chosen reading theme's
/// color scheme into the environment for a subtree (the Reader screen and its sheets).
struct LibraVaultReadingTheme: ViewModifier {
    let theme: ReadingTheme

    func body(content: Content) -> some View {
        content
            .environment(\.readingTheme, theme)
            .environment(\.libraVaultColors, .forReadingTheme(theme))
    }
}

extension View {
    func libraVaultReadingTheme(_ theme: ReadingTheme) -> some View {
        modifier(LibraVaultReadingTheme(theme: theme))
    }
}
