import XCTest
@testable import LibraVault

/// Exercises `ReaderView.persistedDefaultReadingTheme(afterInReaderChangeTo:)` — the
/// mapping issue #427's fix writes into `appState.defaultReadingTheme` whenever the
/// toolbar cycle button or `ReaderSettingsSheet` changes the in-reader theme. Before
/// #427, an in-reader theme change only ever mutated `ReaderView`'s local `@State`,
/// so it was silently discarded the moment a new `BookItem` (e.g. a re-imported copy
/// of the same document) produced a fresh `ReaderView` instance — see the issue for
/// the full repro. `AppStateSettingsTests` already covers that `defaultReadingTheme`
/// itself persists once set; this covers the other half, that an in-reader change
/// produces the *same* value Settings would have written, for every theme.
final class ReaderViewThemeSyncTests: XCTestCase {

    func testPersistedDefaultReadingThemeMatchesTheInReaderChangeForEveryTheme() {
        for theme in ReadingTheme.allCases {
            XCTAssertEqual(ReaderView.persistedDefaultReadingTheme(afterInReaderChangeTo: theme), theme)
        }
    }
}
