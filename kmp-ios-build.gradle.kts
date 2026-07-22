// KMP iOS Framework Build Configuration
// Builds XCFrameworks for all KMP modules targeting iOS
// Usage: ./gradlew -f kmp-ios-build.gradle.kts :buildIosFrameworks

tasks.register("buildIosFrameworks") {
    dependsOn(
        ":core:domain:createXCFrameworkiosArm64",
        ":core:domain:createXCFrameworkiosSimulatorArm64",
        ":core:domain:createXCFrameworkiosX64",
        ":core:tts:createXCFrameworkiosArm64",
        ":core:logger:createXCFrameworkiosArm64"
    )
    
    doLast {
        println("✅ iOS XCFrameworks built successfully")
        println("Location: build/XCFrameworks/")
    }
}

tasks.register("buildIosSimulator") {
    dependsOn(
        ":core:domain:createXCFrameworkiosSimulatorArm64",
        ":core:tts:createXCFrameworkiosSimulatorArm64",
        ":core:logger:createXCFrameworkiosSimulatorArm64"
    )
    
    doLast {
        println("✅ iOS Simulator XCFrameworks built")
    }
}

// Cleanup previous builds
tasks.register("cleanIosFrameworks") {
    doLast {
        delete(fileTree("build/XCFrameworks"))
        println("✅ iOS frameworks cleaned")
    }
}
