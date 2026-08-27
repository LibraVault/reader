//
//  LibraVaultUITestsLaunchTests.swift
//  LibraVaultUITests
//
//  Created by Rob on 24/07/2026.
//

import XCTest

final class LibraVaultUITestsLaunchTests: XCTestCase {

    override class var runsForEachTargetApplicationUIConfiguration: Bool {
        true
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testLaunch() throws {
        let app = XCUIApplication()
        app.launch()

        // Insert steps here to perform after app launch but before taking a screenshot,
        // such as logging into a test account or navigating somewhere in the app

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Launch Screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Startup baseline for issue #695 Phase 0 — the Android side is
    /// `benchmark/src/main/kotlin/xyz/libravault/benchmark/StartupBenchmark.kt`.
    ///
    /// No numbers are committed alongside this yet: this repo's CI has no
    /// macOS/Xcode runner wired up (tracked as #695's own Phase 1 follow-up),
    /// and this test cannot be built or run outside Xcode, so it has not been
    /// executed anywhere. Run it once from Xcode (Product > Test, or
    /// `xcodebuild test -scheme LibraVault -only-testing:LibraVaultUITests/LibraVaultUITestsLaunchTests/testLaunchPerformance`)
    /// on real hardware and commit the resulting numbers as the baseline.
    @MainActor
    func testLaunchPerformance() throws {
        let options = XCTMeasureOptions()
        options.iterationCount = 5

        measure(
            metrics: [
                XCTOSSignpostMetric.applicationLaunch,
                XCTClockMetric(),
                XCTCPUMetric(),
            ],
            options: options
        ) {
            XCUIApplication().launch()
        }
    }
}
