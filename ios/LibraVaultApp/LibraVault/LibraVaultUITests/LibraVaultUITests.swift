//
//  LibraVaultUITests.swift
//  LibraVaultUITests
//
//  Created by Rob on 24/07/2026.
//

import XCTest

final class LibraVaultUITests: XCTestCase {

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.

        // In UI tests it is usually best to stop immediately when a failure occurs.
        continueAfterFailure = false

        // In UI tests it’s important to set the initial state - such as interface orientation - required for your tests before they run. The setUp method is a good place to do this.
    }

    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }

    /// Every test needs a real (non-mock) book to navigate against. The launch
    /// argument tells the app to bootstrap a vault backed by a file it writes into
    /// its own sandbox — see UITestFixtures.swift — instead of relying on any
    /// hardcoded library.
    private func makeApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-uiTestFixtureVault"]
        return app
    }

    @MainActor
    func testExample() throws {
        // UI tests must launch the application that they test.
        let app = makeApp()
        app.launch()

        // Use XCTAssert and related functions to verify your tests produce the correct results.
    }

    @MainActor
    func testLaunchPerformance() throws {
        if #available(macOS 10.15, iOS 13.0, tvOS 13.0, watchOS 7.0, *) {
            // This measures how long it takes to launch your application.
            measure(metrics: [XCTApplicationLaunchMetric()]) {
                self.makeApp().launch()
            }
        }
    }

    // MARK: - Navigation shape (Phase 1 of the Android/iOS UI parity plan)
    //
    // The app used to be a bottom TabView (Library/Reader/Settings); it's now a single
    // NavigationStack rooted at Library, matching Android's model. These guard the two
    // observable consequences of that change: there's no tab bar, and Settings — which
    // used to be its own tab — is still reachable, now via the Library toolbar.

    @MainActor
    func testNoBottomTabBar() throws {
        let app = makeApp()
        app.launch()

        XCTAssertFalse(app.tabBars.firstMatch.waitForExistence(timeout: 2))
    }

    @MainActor
    func testSettingsIsReachableFromLibraryToolbar() throws {
        let app = makeApp()
        app.launch()

        let settingsButton = app.buttons["libraryToolbar.settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 5))
        settingsButton.tap()

        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
    }

    // MARK: - Library screen parity (Phase 2 of the Android/iOS UI parity plan)

    @MainActor
    func testFormatFilterChipsExist() throws {
        let app = makeApp()
        app.launch()

        XCTAssertTrue(app.buttons["All"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["EPUB"].exists)
        XCTAssertTrue(app.buttons["PDF"].exists)
    }

    @MainActor
    func testSelectingPdfFilterHidesEpubOnlyBook() throws {
        let app = makeApp()
        app.launch()

        // "To Kill a Mockingbird" is the fixture vault's only book — a freshly scanned
        // file always starts at 0% progress, so it never shows in the Continue row.
        XCTAssertTrue(app.staticTexts["To Kill a Mockingbird"].waitForExistence(timeout: 5))

        app.buttons["PDF"].tap()

        XCTAssertFalse(app.staticTexts["To Kill a Mockingbird"].waitForExistence(timeout: 2))
    }

    // MARK: - Reader screen parity (Phase 3 of the Android/iOS UI parity plan)
    //
    // All three navigate via "To Kill a Mockingbird" — the fixture vault's only book,
    // at 0% progress, so its title appears exactly once on the Library screen
    // (Continue-row books show their title twice: once there, once in the grid), which
    // is what a single-match staticTexts[...] query needs to avoid an ambiguous-match
    // failure. See testSelectingPdfFilterHidesEpubOnlyBook above for the same reasoning.

    private func openReaderForMockingbird(in app: XCUIApplication) {
        app.launch()
        XCTAssertTrue(app.staticTexts["To Kill a Mockingbird"].waitForExistence(timeout: 5))
        app.staticTexts["To Kill a Mockingbird"].tap()

        let continueButton = app.buttons["bookDetail.continueReadingButton"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        continueButton.tap()
    }

    @MainActor
    func testReaderToolbarControlsExist() throws {
        let app = makeApp()
        openReaderForMockingbird(in: app)

        XCTAssertTrue(app.buttons["reader.themeButton"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["reader.addBookmarkButton"].exists)
        XCTAssertTrue(app.buttons["reader.bookmarksButton"].exists)
        XCTAssertTrue(app.buttons["reader.settingsButton"].exists)
    }

    @MainActor
    func testBookmarksSheetShowsAddedBookmark() throws {
        let app = makeApp()
        openReaderForMockingbird(in: app)

        let addBookmarkButton = app.buttons["reader.addBookmarkButton"]
        XCTAssertTrue(addBookmarkButton.waitForExistence(timeout: 5))
        addBookmarkButton.tap()

        let bookmarksButton = app.buttons["reader.bookmarksButton"]
        XCTAssertTrue(bookmarksButton.waitForExistence(timeout: 5))
        bookmarksButton.tap()

        XCTAssertTrue(app.staticTexts["Bookmarks"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Chapter 1"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testReaderSettingsSheetShowsThemeAndModeOptions() throws {
        let app = makeApp()
        openReaderForMockingbird(in: app)

        let settingsButton = app.buttons["reader.settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 5))
        settingsButton.tap()

        XCTAssertTrue(app.staticTexts["Reading settings"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Dark"].exists)
        XCTAssertTrue(app.buttons["Light"].exists)
        XCTAssertTrue(app.buttons["Sepia"].exists)
        XCTAssertTrue(app.buttons["Paginated"].exists)
        XCTAssertTrue(app.buttons["Scrolling"].exists)
    }

    // MARK: - Player screen (Phase 4 of the Android/iOS UI parity plan)

    @MainActor
    func testReadAloudNavigatesToPlayer() throws {
        let app = makeApp()
        openReaderForMockingbird(in: app)

        let settingsButton = app.buttons["reader.settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 5))
        settingsButton.tap()

        let readAloudButton = app.buttons["Read Aloud"]
        XCTAssertTrue(readAloudButton.waitForExistence(timeout: 5))
        readAloudButton.tap()

        XCTAssertTrue(app.navigationBars["Now Playing"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["player.playPauseButton"].exists)
        XCTAssertTrue(app.staticTexts["To Kill a Mockingbird"].exists)
    }

    @MainActor
    func testPlayerChaptersSheetShowsAllChapters() throws {
        let app = makeApp()
        openReaderForMockingbird(in: app)

        app.buttons["reader.settingsButton"].tap()
        let readAloudButton = app.buttons["Read Aloud"]
        XCTAssertTrue(readAloudButton.waitForExistence(timeout: 5))
        readAloudButton.tap()

        let chaptersButton = app.buttons["player.chaptersButton"]
        XCTAssertTrue(chaptersButton.waitForExistence(timeout: 5))
        chaptersButton.tap()

        XCTAssertTrue(app.staticTexts["Chapters"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Chapter 1: The Beginning"].exists)
    }

    // MARK: - Settings screen parity (Phase 5 of the Android/iOS UI parity plan)

    private func openSettings(in app: XCUIApplication) {
        app.launch()
        let settingsButton = app.buttons["libraryToolbar.settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 5))
        settingsButton.tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
    }

    /// Settings is a Form long enough that "Support Development" (its last section)
    /// sits below the fold on the simulator's screen — waitForExistence doesn't
    /// scroll a SwiftUI Form/List into view on its own, so a swipe is needed first.
    private func scrollDownUntilVisible(_ element: XCUIElement, in app: XCUIApplication, maxSwipes: Int = 6) {
        var attempts = 0
        while !element.exists && attempts < maxSwipes {
            app.swipeUp()
            attempts += 1
        }
    }

    @MainActor
    func testSettingsShowsAllSevenSections() throws {
        let app = makeApp()
        openSettings(in: app)

        XCTAssertTrue(app.staticTexts["Vaults"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Reading"].exists)
        XCTAssertTrue(app.staticTexts["Playback"].exists)
        XCTAssertTrue(app.staticTexts["Privacy & Diagnostics"].exists)

        let supportDevelopment = app.staticTexts["Support Development"]
        scrollDownUntilVisible(supportDevelopment, in: app)
        XCTAssertTrue(app.staticTexts["About"].exists)
        XCTAssertTrue(supportDevelopment.exists)

        // Deliberately omitted (see SettingsView.swift's comments): no iOS equivalent
        // for Material You dynamic color, and no real cover cache to clear yet.
        XCTAssertFalse(app.staticTexts["Appearance"].exists)
        XCTAssertFalse(app.staticTexts["Storage"].exists)
    }

    @MainActor
    func testSettingsReadingSectionShowsThemeChips() throws {
        let app = makeApp()
        openSettings(in: app)

        XCTAssertTrue(app.staticTexts["Default theme"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Dark"].exists)
        XCTAssertTrue(app.buttons["Light"].exists)
        XCTAssertTrue(app.buttons["Sepia"].exists)
    }

    @MainActor
    func testSettingsPlaybackSectionShowsSpeedAndSkipControls() throws {
        let app = makeApp()
        openSettings(in: app)

        XCTAssertTrue(app.staticTexts["Default speed"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.sliders.firstMatch.exists)
        XCTAssertTrue(app.staticTexts["Skip duration"].exists)
        XCTAssertTrue(app.buttons["10s"].exists)
        XCTAssertTrue(app.buttons["30s"].exists)
        XCTAssertTrue(app.buttons["60s"].exists)
    }

    @MainActor
    func testSettingsHasNoNonFunctionalDonateButton() throws {
        let app = makeApp()
        openSettings(in: app)

        let supportDevelopment = app.staticTexts["Support Development"]
        scrollDownUntilVisible(supportDevelopment, in: app)
        XCTAssertTrue(supportDevelopment.exists)
        // Android's Support Development has a real BTCPay-verified donate flow; iOS
        // has none yet, so there must be no button implying one — see
        // SettingsView.swift's supportSection comment.
        XCTAssertFalse(app.buttons["Donate BTC or XMR"].exists)
    }
}
