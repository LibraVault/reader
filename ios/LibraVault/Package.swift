// swift-tools-version:5.9
import PackageDescription

// Phase D: KMP Framework Integration
// This Package.swift is configured to link KMP frameworks built from:
// - core:domain (Kotlin domain models and use cases)
// - core:tts (Text-to-speech engine)
// - core:logger (Diagnostic logging)
// - core:storage (File access and metadata)
// - core:licensing (Pro features and licensing)
//
// Framework Build:
// Run on macOS: ./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks
// Frameworks output to: ../../build/XCFrameworks/
//
// Framework Linking:
// Uncomment the binary targets below after frameworks are built and copied to Frameworks/

let package = Package(
    name: "LibraVault",
    platforms: [
        .iOS(.v17)
    ],
    products: [
        .library(
            name: "LibraVault",
            targets: ["LibraVault"]
        )
    ],
    dependencies: [],
    targets: [
        // Main iOS app target
        .target(
            name: "LibraVault",
            dependencies: [
                // Phase D: Uncomment when frameworks are built
                // "LibravaultDomain",
                // "LibravaultTts",
                // "LibravaultLogger",
                // "LibravaultStorage",
                // "LibravaultLicensing",
            ],
            path: "Sources",
            swiftSettings: [
                // Enable strict concurrency checking for Swift 5.9+
                .enableUpcomingFeature("StrictConcurrency"),
            ]
        ),

        // Phase D: KMP Framework Targets
        // Uncomment and configure these after building frameworks
        //
        // .binaryTarget(
        //     name: "LibravaultDomain",
        //     path: "Frameworks/LibravaultDomain.xcframework"
        // ),
        // .binaryTarget(
        //     name: "LibravaultTts",
        //     path: "Frameworks/LibravaultTts.xcframework"
        // ),
        // .binaryTarget(
        //     name: "LibravaultLogger",
        //     path: "Frameworks/LibravaultLogger.xcframework"
        // ),
        // .binaryTarget(
        //     name: "LibravaultStorage",
        //     path: "Frameworks/LibravaultStorage.xcframework"
        // ),
        // .binaryTarget(
        //     name: "LibravaultLicensing",
        //     path: "Frameworks/LibravaultLicensing.xcframework"
        // ),

        // Tests
        .testTarget(
            name: "LibraVaultTests",
            dependencies: ["LibraVault"],
            path: "Tests"
        )
    ]
)
