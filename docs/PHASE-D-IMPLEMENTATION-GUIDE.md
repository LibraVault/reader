# Phase D Implementation Guide: KMP-Swift Integration

> **⚠️ STATUS CORRECTION (2026-07-25):** This guide's `build-xcframeworks.gradle.kts` doesn't exist in the repo — this work was never started, not even the D1 framework-building step. Also note: Xcode 15+ is no longer sufficient — App Store Connect now requires the iOS 26 SDK (Xcode 26+). TestFlight distribution was solved by a different, simpler path (a real Xcode App target, no KMP frameworks) — see **[iOS-TESTFLIGHT-RELEASE-PROCESS.md](iOS-TESTFLIGHT-RELEASE-PROCESS.md)** for what's actually working today. This guide is kept in case the KMP-linking goal (real domain data instead of mocks) gets picked up later, but treat every command below as unverified against current code.

**For:** Running on macOS with Xcode 15+  
**Duration:** ~4-6 hours (depending on first-time setup)  
**Prerequisites:** macOS 13+, Xcode 15+, Kotlin 2.0.0 (already in repo)

---

## Overview

Phase D bridges the Kotlin Multiplatform domain layer with the native Swift iOS UI. This guide provides exact steps to complete the integration on macOS.

**Three paths:**
1. **Automated (GitHub Actions)** - Runs on macOS runners automatically
2. **Local macOS development** - Manual build and test on your Mac
3. **Hybrid** - Local testing + CI automation

---

## Part 1: Verify Prerequisites

### Check Kotlin Version
```bash
cd /path/to/LibraVault/reader
cat gradle/libs.versions.toml | grep "^kotlin"
# Should show: kotlin = "2.0.0" ✅
```

### Check Gradle
```bash
./gradlew --version
# Should be Gradle 9.x+ ✅
```

### Check macOS & Xcode
```bash
xcode-select --print-path
# Should show path to Xcode.app ✅

xcode-select --install  # If not installed
```

### Verify Kotlin Native iOS Toolchain
```bash
# Kotlin Native toolchain is downloaded on first iOS build
# No manual installation needed - it happens automatically

# First build will download ~2GB, be patient!
```

---

## Part 2: Build XCFrameworks

### Step 1: Build All iOS Frameworks

**From main repo directory:**
```bash
cd /path/to/LibraVault/reader

# Show available framework build commands
./gradlew -f build-xcframeworks.gradle.kts xcframeworkHelp

# Build all frameworks for all iOS architectures (arm64, simulator x64, simulator arm64)
# WARNING: This is slow (~30-45 min on first run, ~10-15 min on subsequent runs)
./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks

# During build, you'll see:
# - Downloading Kotlin Native iOS toolchain (first time only, ~2GB)
# - Compiling Kotlin code for iosArm64
# - Compiling Kotlin code for iosSimulatorArm64
# - Compiling Kotlin code for iosX64
# - Linking frameworks

# When complete:
# ✅ All iOS XCFrameworks Built!
# Frameworks available in: build/XCFrameworks/
```

### Step 2: Verify Frameworks Built

```bash
# List generated frameworks
ls -lh build/XCFrameworks/

# Expected output:
# LibravaultDomain.xcframework/
# LibravaultTts.xcframework/
# LibravaultLogger.xcframework/
# LibravaultLicensing.xcframework/
# LibravaultStorage.xcframework/

# Verify framework integrity
./gradlew -f build-xcframeworks.gradle.kts verifyXCFrameworks
# Should show: ✅ Found 5 XCFrameworks
```

### Step 3: Copy Frameworks to iOS Project

```bash
# Create frameworks directory in iOS project
mkdir -p ios/LibraVault/Frameworks

# Copy all frameworks
cp -r build/XCFrameworks/*.xcframework ios/LibraVault/Frameworks/

# Verify copy
ls -la ios/LibraVault/Frameworks/
```

---

## Part 3: Update iOS App Configuration

### Step 1: Update Package.swift

**File:** `ios/LibraVault/Package.swift`

Replace the commented framework targets section with:

```swift
// Phase D: KMP Framework Targets
.binaryTarget(
    name: "LibravaultDomain",
    path: "Frameworks/LibravaultDomain.xcframework"
),
.binaryTarget(
    name: "LibravaultTts",
    path: "Frameworks/LibravaultTts.xcframework"
),
.binaryTarget(
    name: "LibravaultLogger",
    path: "Frameworks/LibravaultLogger.xcframework"
),
.binaryTarget(
    name: "LibravaultStorage",
    path: "Frameworks/LibravaultStorage.xcframework"
),
.binaryTarget(
    name: "LibravaultLicensing",
    path: "Frameworks/LibravaultLicensing.xcframework"
),
```

Also update the main target dependencies:

```swift
.target(
    name: "LibraVault",
    dependencies: [
        "LibravaultDomain",
        "LibravaultTts",
        "LibravaultLogger",
        "LibravaultStorage",
        "LibravaultLicensing",
    ],
    ...
)
```

### Step 2: Verify Package Resolves

```bash
cd ios/LibraVault

# This will verify Package.swift syntax and link frameworks
swift package describe

# Should complete without errors ✅
```

---

## Part 4: Implement Real KMP Integration in iOS App

### Step 1: Update DomainBridge (Phase D Implementation)

**File:** `ios/LibraVault/Sources/KmpInterop/DomainBridge.swift`

Replace the mock implementation section with real KMP calls:

```swift
// Replace this mock implementation:
// private func loadMockLibrary() { ... }

// With this real KMP implementation:
private func loadMockLibrary() {
    // Phase D: Replace with actual KMP call:
    // let useCases = kmpDomain.getLibrary()
    // for item in useCases {
    //     let book = mapToBookData(item)
    //     allBooks.append(book)
    // }
}
```

### Step 2: Create Real Framework Initialization

**Add to DomainBridge:**

```swift
// Phase D: Initialize actual KMP frameworks
private func initializeKmpFrameworks() throws {
    // Import from build XCFrameworks
    // Example (actual syntax depends on KMP Swift interop):
    
    // kmpDomain = LibravaultDomain.LibravaultDomainUseCases()
    // kmpTts = LibravaultTts.TtsEngine()
    // kmpLogger = LibravaultLogger.LibravaultLogger()
    
    // Initialize each framework
    // try kmpLogger?.initialize()
    // try kmpTts?.initialize()
    
    println("✅ KMP frameworks initialized")
}
```

### Step 3: Update AppState to Use Real Data

**File:** `ios/LibraVault/Sources/Models/AppState.swift`

```swift
// Replace mock data loading with real KMP calls:

func loadLibrary() async {
    isLoading = true
    defer { isLoading = false }

    do {
        // Phase D: Call real core:domain GetLibraryUseCase
        // let items = try await bridge.kmpDomain.getLibrary()
        // books = items.map { BookItem(from: $0) }
        
        // For now, still use mock while integrating
        books = bridge.allBooks.map { BookItem(from: $0) }
        
        bridge.log("Loaded \(books.count) books", tag: "Library")
    } catch {
        self.error = AppError.libraryLoadFailed(error.localizedDescription)
    }
}
```

---

## Part 5: Test Integration

### Step 1: Build iOS App

```bash
cd ios/LibraVault

# Clean build with frameworks
swift build --configuration debug

# Should complete without framework linking errors ✅
```

### Step 2: Test on Simulator

```bash
# If using Xcode (recommended for first-time)
open -a Xcode ios/LibraVault/

# In Xcode:
# 1. Select iPhone 15 Pro (simulator)
# 2. Product → Build (⌘B)
# 3. Product → Run (⌘R)
# 4. Test library loading, reading, bookmarks, etc.
```

### Step 3: Verify KMP Integration

**In iOS app:**
- [ ] Library loads without mock data
- [ ] Books display with correct metadata
- [ ] Reading progress persists
- [ ] Bookmarks save and load
- [ ] Highlights save and load
- [ ] TTS reads text aloud
- [ ] Logging captures events
- [ ] Settings reflect KMP state

---

## Part 6: Advanced Testing

### Test on Physical Device

```bash
# In Xcode:
# 1. Connect iPhone via USB
# 2. Select device from target selector
# 3. Build & Run
# 4. Approve developer certificate if prompted
# 5. Test full features on device
```

### Test Specific Frameworks

Create test files to verify each framework independently:

```swift
// ios/LibraVault/Tests/KmpFrameworkTests.swift

import XCTest

class KmpFrameworkTests: XCTestCase {
    func testDomainFramework() async throws {
        // Test that domain framework loads
        // let library = try await bridge.loadLibrary()
        // XCTAssertGreaterThan(library.count, 0)
    }

    func testTtsFramework() async throws {
        // Test TTS initialization
        // let tts = bridge.ttsEngine
        // XCTAssertNotNil(tts)
    }

    func testLoggerFramework() throws {
        // Test logging
        // bridge.log("Test", tag: "Test")
        // let logs = try bridge.readLogs()
        // XCTAssert(logs.contains("Test"))
    }
}
```

---

## Part 7: CI/CD Setup (GitHub Actions)

### Update Workflow

**File:** `.github/workflows/ios-build.yml`

Add macOS job to build frameworks:

```yaml
jobs:
  build-kmp-frameworks:
    runs-on: macos-14
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Xcode
        uses: maxim-lobanov/setup-xcode@v1
        with:
          xcode-version: '15.3'
      
      - name: Build KMP XCFrameworks
        run: |
          cd reader
          ./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks
      
      - name: Upload Frameworks
        uses: actions/upload-artifact@v3
        with:
          name: ios-frameworks
          path: reader/build/XCFrameworks/
```

### Download Frameworks in iOS Build

```yaml
  build-ios:
    runs-on: macos-14
    needs: build-kmp-frameworks
    steps:
      - uses: actions/checkout@v4
      
      - name: Download Frameworks
        uses: actions/download-artifact@v3
        with:
          name: ios-frameworks
          path: reader-ios/ios/LibraVault/Frameworks/
      
      - name: Build iOS App
        run: |
          cd reader-ios/ios/LibraVault
          swift build --configuration release
```

---

## Part 8: Troubleshooting

### Issue: Kotlin Native Toolchain Download Fails
**Solution:**
```bash
# Increase timeout and retry
./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks \
  -Dorg.gradle.jvmargs=-Xmx4g \
  --debug

# Or manually download:
# https://github.com/JetBrains/kotlin-native/releases
```

### Issue: Framework Linking Fails
**Solution:**
```bash
# Verify frameworks exist
ls -lh ios/LibraVault/Frameworks/

# Verify Package.swift paths are correct
# Paths should be relative to Package.swift location

# Rebuild package
cd ios/LibraVault
rm -rf .build
swift build --configuration debug
```

### Issue: Swift Compilation Errors with KMP Types
**Solution:**
```bash
# Update type mappings in KmpTypeMappings.swift
# Uncomment mapping functions specific to your KMP framework versions

# Reference Kotlin Swift interop docs:
# https://kotlinlang.org/docs/native-objc-interop.html
```

### Issue: Slow Builds
**Solution:**
```bash
# Build simulator only (faster for development)
./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworksSimulator

# Or build specific module only
./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworkDomain

# Cache frameworks for faster subsequent builds
ls -lh ios/LibraVault/Frameworks/
```

---

## Part 9: Performance Optimization

### Reduce First Build Time
```bash
# Build only what you need
# Simulator: 10-15 minutes
./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworksSimulator

# Full (arm64 + simulator): 30-45 minutes
./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks

# Device only: 5-10 minutes (for testing on real device)
./gradlew -f build-xcframeworks.gradle.kts buildXCFrameworksDevice
```

### Incremental Builds
```bash
# Subsequent builds are faster (~2-5 minutes)
# Only changed modules are recompiled
./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks
```

### Parallel Compilation
```bash
# Use Gradle parallel builds (default, ~8 workers on M1/M2/M3 Macs)
./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks

# Explicitly set workers
./gradlew -f build-xcframeworks.gradle.kts buildAllXCFrameworks \
  --max-workers=12
```

---

## Checklist: Phase D Complete

- [ ] **D1:** KMP frameworks build successfully
- [ ] **D2:** Package.swift links frameworks
- [ ] **D3:** Type mappings work correctly
- [ ] **D4:** Real domain data loads (not mock)
- [ ] **D5:** TTS framework integrated
- [ ] **D6:** Logger framework integrated
- [ ] **D7:** Licensing framework integrated
- [ ] **D8:** Storage framework integrated
- [ ] **D9:** All features tested end-to-end
- [ ] **D10:** CI/CD automated and working

---

## Success Indicators

✅ **Phase D Complete When:**
1. XCFrameworks build without errors
2. iOS app links frameworks without issues
3. Real library loads from core:domain
4. All features work with real KMP data
5. Tests pass on simulator and device
6. CI/CD builds automatically on macOS runner
7. App ready for App Store submission

---

## Next Steps After Phase D

1. **App Store Preparation**
   - Configure bundle ID and provisioning
   - Set up App Store Connect app record
   - Create screenshots and app description

2. **Beta Testing**
   - Build for TestFlight
   - Invite testers
   - Collect feedback

3. **Release**
   - Submit to App Store review
   - Wait for approval
   - Launch app!

---

## Support & References

- **Kotlin Multiplatform:** https://kotlinlang.org/docs/multiplatform.html
- **Swift Package Manager:** https://swift.org/package-manager/
- **KMP iOS Docs:** https://kotlinlang.org/docs/multiplatform-ios-understanding-the-architecture.html
- **Swift Interop:** https://kotlinlang.org/docs/native-objc-interop.html
- **Xcode Docs:** https://developer.apple.com/xcode/

---

## Time Breakdown

| Step | Duration |
|------|----------|
| Verify prerequisites | 5 min |
| Build frameworks (first time) | 30-45 min |
| Copy & configure frameworks | 5 min |
| Test integration | 10-15 min |
| Debug & fix issues | 10-20 min |
| **Total** | **1-2 hours** |

*Note: First build includes ~2GB Kotlin Native toolchain download. Subsequent builds are 5-10x faster.*
