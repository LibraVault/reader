import CoreGraphics

/// Mirrors core/ui/theme/Dimens.kt — strict 4pt grid. Anything outside this set is a bug.
enum LibraVaultSpacing {
    static let xs: CGFloat  = 4
    static let sm: CGFloat  = 8
    static let md: CGFloat  = 12
    static let lg: CGFloat  = 16
    static let xl: CGFloat  = 24
    static let xxl: CGFloat = 32

    /// Fixed cover-art width used in library rows.
    static let coverWidth: CGFloat = 120
    static let coverAspect: CGFloat = 2.0 / 3.0

    /// Mini-player / reader mini-bar height.
    static let miniBarHeight: CGFloat = 64

    /// Standard nav-bar height (matches Android's Material top-app-bar default).
    static let topBarHeight: CGFloat = 56
}
