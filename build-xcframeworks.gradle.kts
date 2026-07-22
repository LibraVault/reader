// LibraVault iOS XCFramework Build Configuration
//
// This file defines Gradle tasks to build XCFrameworks for all KMP modules
// targeting iOS (arm64, simulator arm64, simulator x64).
//
// Usage:
//   ./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks
//   ./gradlew -f build-xcframeworks.gradle.kts buildXCFramework<ModuleName>
//
// Prerequisites:
//   - macOS with Xcode 15+
//   - Kotlin 2.0.0+
//   - Kotlin Native toolchain for iOS
//
// Output: build/XCFrameworks/
//   ├── LibravaultDomain.xcframework/
//   ├── LibravaultTts.xcframework/
//   ├── LibravaultLogger.xcframework/
//   ├── LibravaultLicensing.xcframework/
//   └── LibravaultStorage.xcframework/
//
// For Swift Package Manager integration:
//   Update ios/LibraVault/Package.swift to reference these frameworks

// Define XCFramework build tasks for each KMP module
val iosFrameworkModules = mapOf(
    "domain" to ":core:domain",
    "tts" to ":core:tts",
    "logger" to ":core:logger",
    "licensing" to ":core:licensing",
    "storage" to ":core:storage"
)

// iOS targets
val iosTargets = listOf("iosArm64", "iosSimulatorArm64", "iosX64")

// Create individual module framework build tasks
iosFrameworkModules.forEach { (moduleName, projectPath) ->
    // Task to build all iOS targets for this module
    tasks.register("buildXCFramework${moduleName.capitalize()}") {
        group = "iOS Framework"
        description = "Build XCFramework for $moduleName targeting all iOS architectures"

        dependsOn(iosTargets.map { "$projectPath:linkDebug$it" })

        doLast {
            println("✅ Built $moduleName XCFramework for: ${iosTargets.joinToString(", ")}")
        }
    }

    // Task to build simulator frameworks for faster local testing
    tasks.register("buildXCFramework${moduleName.capitalize()}Simulator") {
        group = "iOS Framework"
        description = "Build simulator XCFramework for $moduleName (arm64 + x64)"

        dependsOn(
            "$projectPath:linkDebugiosSimulatorArm64",
            "$projectPath:linkDebugiosX64"
        )

        doLast {
            println("✅ Built $moduleName simulator XCFramework")
        }
    }

    // Task to build device framework only (faster for device testing)
    tasks.register("buildXCFramework${moduleName.capitalize()}Device") {
        group = "iOS Framework"
        description = "Build device XCFramework for $moduleName (arm64 only)"

        dependsOn("$projectPath:linkDebugiosArm64")

        doLast {
            println("✅ Built $moduleName device XCFramework")
        }
    }
}

// Master task to build all frameworks
tasks.register("buildAllXCFrameworks") {
    group = "iOS Framework"
    description = "Build all XCFrameworks for iOS (all modules, all architectures)"

    dependsOn(iosFrameworkModules.keys.map { "buildXCFramework${it.capitalize()}" })

    doLast {
        println("""

            ╔════════════════════════════════════════════╗
            ║   ✅ All iOS XCFrameworks Built!          ║
            ╚════════════════════════════════════════════╝

            Frameworks available in: build/XCFrameworks/

            Next steps:
            1. Copy frameworks to iOS project
            2. Update Package.swift with framework paths
            3. Link frameworks in Xcode
            4. Test integration in iOS app

            For Swift Package Manager:
            Add to Package.swift:

            .binaryTarget(
                name: "LibravaultDomain",
                path: "Frameworks/LibravaultDomain.xcframework"
            ),

            Then update product dependencies to use the framework.
        """.trimIndent())
    }
}

// Build only simulator frameworks (faster for local development)
tasks.register("buildXCFrameworksSimulator") {
    group = "iOS Framework"
    description = "Build simulator XCFrameworks (faster for development)"

    dependsOn(iosFrameworkModules.keys.map { "buildXCFramework${it.capitalize()}Simulator" })

    doLast {
        println("✅ Simulator XCFrameworks ready for testing")
    }
}

// Build only device frameworks
tasks.register("buildXCFrameworksDevice") {
    group = "iOS Framework"
    description = "Build device XCFrameworks for release"

    dependsOn(iosFrameworkModules.keys.map { "buildXCFramework${it.capitalize()}Device" })

    doLast {
        println("✅ Device XCFrameworks ready for release")
    }
}

// Clean up built frameworks
tasks.register("cleanXCFrameworks") {
    group = "iOS Framework"
    description = "Clean all built XCFrameworks"

    doLast {
        delete(fileTree("build") {
            include("**/XCFrameworks/")
            include("**/iosArm64/", "**/iosSimulatorArm64/", "**/iosX64/")
        })
        println("✅ XCFrameworks cleaned")
    }
}

// Task to verify framework builds
tasks.register("verifyXCFrameworks") {
    group = "iOS Framework"
    description = "Verify XCFramework integrity"

    doLast {
        val buildDir = File(project.buildDir, "XCFrameworks")
        if (!buildDir.exists()) {
            println("❌ No XCFrameworks built yet")
            println("Run: ./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks")
            return@doLast
        }

        val frameworks = buildDir.listFiles()?.filter { it.isDirectory && it.name.endsWith(".xcframework") } ?: emptyList()

        if (frameworks.isEmpty()) {
            println("❌ No frameworks found in ${buildDir.absolutePath}")
            return@doLast
        }

        println("✅ Found ${frameworks.size} XCFrameworks:")
        frameworks.forEach { framework ->
            val infoPath = File(framework, "Info.plist")
            if (infoPath.exists()) {
                println("  ✓ ${framework.name}")
            } else {
                println("  ⚠ ${framework.name} (missing Info.plist)")
            }
        }
    }
}

// Print help for iOS framework commands
tasks.register("xcframeworkHelp") {
    group = "iOS Framework"
    description = "Show XCFramework build commands help"

    doLast {
        println("""

            ╔═════════════════════════════════════════════════════╗
            ║    LibraVault iOS XCFramework Build Commands         ║
            ╚═════════════════════════════════════════════════════╝

            Build ALL frameworks (all architectures):
              ./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks

            Build simulator frameworks (faster):
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworksSimulator

            Build device frameworks (release):
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworksDevice

            Build specific module:
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkDomain
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkTts
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkLogger
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkLicensing
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkStorage

            Build specific module for simulator only:
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkDomainSimulator

            Build specific module for device only:
              ./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkDomainDevice

            Verify frameworks:
              ./gradlew -f build-xcframeworks.gradle.kts verifyXCFrameworks

            Clean frameworks:
              ./gradlew -f build-xcframeworks.gradle.kts cleanXCFrameworks

            Requirements:
              - macOS 13+ with Xcode 15+
              - Kotlin 2.0.0+
              - Kotlin Native iOS toolchain installed

            Framework output location:
              build/XCFrameworks/

            Integration steps:
              1. Run buildAllXCFrameworks
              2. Copy frameworks to ios/LibraVault/Frameworks/
              3. Update Package.swift with framework paths
              4. Link in Xcode project

        """.trimIndent())
    }
}

// Print help on task execution
defaultTasks("xcframeworkHelp")
