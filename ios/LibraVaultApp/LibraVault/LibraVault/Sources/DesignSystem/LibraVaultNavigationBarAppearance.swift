import UIKit

/// Styles every `UINavigationBar`'s title with Lora app-wide, so a screen's
/// nav title matches any `LibraVaultTypography` headline/title text below it
/// instead of falling back to system San Francisco (#661). SwiftUI's
/// `.navigationTitle(...)` has no font modifier of its own — `UIKit`'s
/// `UINavigationBarAppearance` is the only lever, and it's global by
/// construction (`UINavigationBar.appearance()`), which is what makes this a
/// one-time app-wide fix rather than something each screen opts into.
///
/// `configureWithDefaultBackground()` preserves the system's default
/// background/blur/shadow so this only overrides the title font, nothing
/// else visual.
enum LibraVaultNavigationBarAppearance {
    static func apply() {
        let appearance = UINavigationBarAppearance()
        appearance.configureWithDefaultBackground()
        appearance.titleTextAttributes = [.font: LibraVaultTypography.navigationTitleUIFont]

        UINavigationBar.appearance().standardAppearance = appearance
        UINavigationBar.appearance().compactAppearance = appearance
        UINavigationBar.appearance().scrollEdgeAppearance = appearance
    }
}
