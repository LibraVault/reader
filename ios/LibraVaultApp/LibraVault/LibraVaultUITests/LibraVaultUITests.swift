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

    @MainActor
    func testExample() throws {
        // UI tests must launch the application that they test.
        let app = XCUIApplication()
        app.launch()

        // Use XCTAssert and related functions to verify your tests produce the correct results.
    }

    @MainActor
    func testLaunchPerformance() throws {
        if #available(macOS 10.15, iOS 13.0, tvOS 13.0, watchOS 7.0, *) {
            // This measures how long it takes to launch your application.
            measure(metrics: [XCTApplicationLaunchMetric()]) {
                XCUIApplication().launch()
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
        let app = XCUIApplication()
        app.launch()

        XCTAssertFalse(app.tabBars.firstMatch.waitForExistence(timeout: 2))
    }

    @MainActor
    func testSettingsIsReachableFromLibraryToolbar() throws {
        let app = XCUIApplication()
        app.launch()

        let settingsButton = app.buttons["libraryToolbar.settingsButton"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 5))
        settingsButton.tap()

        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
    }
}
