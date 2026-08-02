import Foundation

/// Locates the bundled Pocket TTS voice model.
///
/// Unlike Android's `PocketModelManager` (core/tts/.../pocket/PocketModelManager.kt),
/// which downloads and verifies the model on first use, iOS ships it bundled
/// in the app: Foundation has no built-in tar/bzip2 decompression (sherpa-onnx's
/// model releases are `.tar.bz2`), and the only on-device alternative -
/// linking `libbz2.dylib` directly - requires headers Apple doesn't ship
/// publicly, which is an App Store rejection risk for using a private API.
/// `third-party/sherpa-onnx/setup-ios.sh` extracts the same model Android
/// uses (same URL/checksum, same licensing reasoning - see
/// SHERPA_ONNX_SETUP.md) into `PocketTTSModel/` at build/dev-setup time,
/// where it's picked up as a bundled resource automatically (the folder
/// lives inside the Xcode project's synchronized source root).
struct PocketModelManager {
    private let bundle: Bundle
    private let subdirectory: String

    init(bundle: Bundle = .main, subdirectory: String = "PocketTTSModel") {
        self.bundle = bundle
        self.subdirectory = subdirectory
    }

    /// Absolute filesystem path to the bundled model directory, or nil if the
    /// bundle resource is missing (e.g. setup-ios.sh was never run before
    /// building - see SHERPA_ONNX_SETUP.md).
    var modelDirectoryPath: String? {
        let candidate = bundle.bundleURL.appendingPathComponent(subdirectory)
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDirectory),
              isDirectory.boolValue
        else {
            return nil
        }
        return candidate.path
    }

    var isModelAvailable: Bool { modelDirectoryPath != nil }
}
