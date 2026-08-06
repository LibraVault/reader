import XCTest
import ZIPFoundation
@testable import LibraVault

@MainActor
final class AppStateSettingsTests: XCTestCase {

    // Isolated from the real UserDefaults.standard so these tests can assert on
    // "nothing saved yet" defaults without depending on (or polluting) whatever a
    // previous test run left behind — same reasoning as AppStateVaultTests.
    private func makeIsolatedPersistence() -> UserPreferencesPersistence {
        UserPreferencesPersistence(defaults: UserDefaults(suiteName: "AppStateSettingsTests.\(UUID().uuidString)")!)
    }

    private func makeIsolatedVaultPersistence() -> VaultPersistence {
        VaultPersistence(defaults: UserDefaults(suiteName: "AppStateSettingsTests.Vaults.\(UUID().uuidString)")!)
    }

    /// A real, single-chapter EPUB with a real 200-word chapter — comfortably above
    /// estimateDuration's 1-second floor at every speed, so seek/skip math in
    /// testSkipDurationFeedsSkipForwardAndBackward has room to be meaningfully
    /// asserted on (see AppStatePlaybackTests' longChapterHTML for the same reasoning).
    private func makeRealEPUBBook(vaultPersistence: VaultPersistence) throws -> BookItem {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("AppStateSettingsTests-\(UUID().uuidString)")
        let sourceDir = tempDir.appendingPathComponent("source", isDirectory: true)
        let vaultFolder = tempDir.appendingPathComponent("vault", isDirectory: true)
        let oebpsDir = sourceDir.appendingPathComponent("OEBPS", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: vaultFolder, withIntermediateDirectories: true)

        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)

        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest><item id="chap0" href="chap0.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="chap0"/></spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        let words = Array(repeating: "word", count: 200).joined(separator: " ")
        try "<html><body><h1>Chapter One</h1><p>\(words)</p></body></html>"
            .write(to: oebpsDir.appendingPathComponent("chap0.xhtml"), atomically: true, encoding: .utf8)

        let finalEpubURL = vaultFolder.appendingPathComponent("Fixture.epub")
        try FileManager().zipItem(at: sourceDir, to: finalEpubURL, shouldKeepParent: false)

        let vault = try vaultPersistence.makeVault(from: vaultFolder)
        vaultPersistence.save([vault])

        return BookItem(
            id: "vault:\(vault.id):\(finalEpubURL.path)",
            title: "Fixture",
            author: "",
            format: .epub,
            fileURL: finalEpubURL,
            vaultId: vault.id
        )
    }

    func testDefaultReadingThemeDefaultsToDark() {
        XCTAssertEqual(AppState(userPreferencesPersistence: makeIsolatedPersistence()).defaultReadingTheme, .dark)
    }

    func testDefaultReadingThemeIsSettable() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.defaultReadingTheme = .sepia
        XCTAssertEqual(state.defaultReadingTheme, .sepia)
    }

    func testDefaultPlaybackSpeedDefaultsTo1() {
        XCTAssertEqual(AppState(userPreferencesPersistence: makeIsolatedPersistence()).defaultPlaybackSpeed, 1.0)
    }

    func testDefaultPlaybackSpeedIsSettable() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.defaultPlaybackSpeed = 1.75
        XCTAssertEqual(state.defaultPlaybackSpeed, 1.75)
    }

    func testSkipDurationSecondsDefaultsTo30() {
        XCTAssertEqual(AppState(userPreferencesPersistence: makeIsolatedPersistence()).skipDurationSeconds, 30)
    }

    func testSkipDurationSecondsIsSettable() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.skipDurationSeconds = 15
        XCTAssertEqual(state.skipDurationSeconds, 15)
    }

    /// Regression guard for the actual wiring, not just the property: Settings'
    /// "Skip duration" chips are only meaningful if Player's transport buttons
    /// genuinely read this value rather than a hardcoded 30s (see PlayerView.swift).
    ///
    /// The fixture's 200-word chapter is ~80s of estimated duration at the default
    /// 1.0x speed (see AppState.estimateDuration), so seek/skip targets here have room
    /// to stay well under that ceiling.
    func testSkipDurationFeedsSkipForwardAndBackward() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealEPUBBook(vaultPersistence: vaultPersistence)
        state.startPlayback(book: book)
        state.seek(to: 5)
        state.skipDurationSeconds = 3

        state.skipForward(seconds: state.skipDurationSeconds)
        XCTAssertEqual(state.elapsedSeconds, 8, accuracy: 0.01)

        state.skipBackward(seconds: state.skipDurationSeconds)
        XCTAssertEqual(state.elapsedSeconds, 5, accuracy: 0.01)
    }

    /// Regression guard for the actual bug being fixed: these three settings used to
    /// be pure in-memory @Published state, reset to their compiled-in defaults on
    /// every relaunch despite Settings presenting them as saved preferences.
    func testSettingsPersistAcrossAppStateInstances() {
        let persistence = makeIsolatedPersistence()

        let state = AppState(userPreferencesPersistence: persistence)
        state.defaultReadingTheme = .light
        state.defaultPlaybackSpeed = 2.0
        state.skipDurationSeconds = 45

        let reloaded = AppState(userPreferencesPersistence: persistence)
        XCTAssertEqual(reloaded.defaultReadingTheme, .light)
        XCTAssertEqual(reloaded.defaultPlaybackSpeed, 2.0)
        XCTAssertEqual(reloaded.skipDurationSeconds, 45)
    }

    func testMiniPlayerAutoHideEnabledDefaultsToTrue() {
        XCTAssertTrue(AppState(userPreferencesPersistence: makeIsolatedPersistence()).miniPlayerAutoHideEnabled)
    }

    func testMiniPlayerAutoHideEnabledIsSettableAndPersistsAcrossAppStateInstances() {
        let persistence = makeIsolatedPersistence()

        let state = AppState(userPreferencesPersistence: persistence)
        state.miniPlayerAutoHideEnabled = false
        XCTAssertFalse(state.miniPlayerAutoHideEnabled)

        let reloaded = AppState(userPreferencesPersistence: persistence)
        XCTAssertFalse(reloaded.miniPlayerAutoHideEnabled)
    }
}
