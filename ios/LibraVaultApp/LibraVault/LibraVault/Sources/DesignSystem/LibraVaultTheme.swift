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

    /// SF Symbol for ReaderView's theme-cycle toolbar button.
    var systemImageName: String {
        switch self {
        case .dark:  return "moon.fill"
        case .light: return "sun.max.fill"
        case .sepia: return "book.fill"
        }
    }

    /// The theme ReaderView's toolbar button switches to next — Dark → Light →
    /// Sepia → Dark. Pulled out of ReaderView as a pure, directly testable mapping
    /// rather than a private switch statement only reachable by tapping a button.
    var next: ReadingTheme {
        switch self {
        case .dark:  return .light
        case .light: return .sepia
        case .sepia: return .dark
        }
    }
}
