import SwiftUI

/// Mirrors core/ui/theme/Theme.kt's `ReadingTheme` enum (Android's `SYSTEM` case —
/// see #370 for its resolution/default decisions, tracked separately from this one).
enum ReadingTheme: String, CaseIterable {
    case dark, light, sepia, system

    var label: String {
        switch self {
        case .dark:   return "Dark"
        case .light:  return "Light"
        case .sepia:  return "Sepia"
        case .system: return "System"
        }
    }

    /// SF Symbol for ReaderView's theme-cycle toolbar button.
    var systemImageName: String {
        switch self {
        case .dark:   return "moon.fill"
        case .light:  return "sun.max.fill"
        case .sepia:  return "book.fill"
        case .system: return "circle.lefthalf.filled"
        }
    }

    /// The theme ReaderView's toolbar button switches to next — Dark → Light →
    /// Sepia → Dark. Pulled out of ReaderView as a pure, directly testable mapping
    /// rather than a private switch statement only reachable by tapping a button.
    ///
    /// `.system` is deliberately not part of this cycle — it's a Settings-only
    /// choice, so a quick in-reader tap while on System exits to Dark rather than
    /// looping back through System. Existing Dark/Light/Sepia cycling is unchanged.
    var next: ReadingTheme {
        switch self {
        case .dark:   return .light
        case .light:  return .sepia
        case .sepia:  return .dark
        case .system: return .dark
        }
    }

    /// Resolves `.system` against the OS's current appearance; every other case
    /// passes through unchanged. System maps only Dark↔Light — Sepia is never
    /// system-selected, since it isn't one of iOS's two trait-collection choices.
    ///
    /// Returns `ConcreteReadingTheme` rather than `ReadingTheme` so every call site
    /// that needs an actual color/theme-name decision (LibraVaultColorScheme,
    /// mermaidThemeName) is guaranteed by the compiler to have resolved `.system`
    /// first — there is no exhaustive switch anywhere else that can silently
    /// mishandle it.
    func resolved(for colorScheme: ColorScheme) -> ConcreteReadingTheme {
        switch self {
        case .dark:   return .dark
        case .light:  return .light
        case .sepia:  return .sepia
        case .system: return colorScheme == .dark ? .dark : .light
        }
    }
}

/// `ReadingTheme` with `.system` already resolved to a concrete light/dark choice.
/// See `ReadingTheme.resolved(for:)`.
enum ConcreteReadingTheme {
    case dark, light, sepia
}
