import CoreGraphics

/// PlayerView's GeometryReader used to inline `proxy.size.width > proxy.size.height`
/// directly at the call site (see #164/#165 — the player no longer locks rotation to
/// portrait) — pulled out so the actual routing condition has direct test coverage
/// instead of only the two layouts it chooses between.
func isLandscapeOrientation(size: CGSize) -> Bool {
    size.width > size.height
}
