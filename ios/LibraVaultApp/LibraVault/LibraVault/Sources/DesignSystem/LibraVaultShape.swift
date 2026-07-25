import SwiftUI

/// Mirrors core/ui/theme/Shape.kt's radius scale.
enum LibraVaultRadius {
    /// Small artwork (book/audio covers).
    static let cover: CGFloat = 6
    /// Primary card surface.
    static let card: CGFloat = 14
    /// Modal bottom sheet content.
    static let sheet: CGFloat = 20
    /// Large sheet corners (top edges on bottom sheets).
    static let sheetLarge: CGFloat = 28
    /// Chips and inline tags.
    static let chip: CGFloat = 8
}
