// swift-tools-version:5.9
import PackageDescription

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
    dependencies: [
        // KMP Runtime (will be added as binary dependency)
        // .package(url: "https://github.com/JetBrains/kotlin-multiplatform-mobile", branch: "main"),
    ],
    targets: [
        .target(
            name: "LibraVault",
            dependencies: [],
            path: "Sources",
            linkerSettings: [
                // Link against KMP framework when available
                // .linkedFramework("shared")
            ]
        ),
        .testTarget(
            name: "LibraVaultTests",
            dependencies: ["LibraVault"],
            path: "Tests"
        )
    ]
)
