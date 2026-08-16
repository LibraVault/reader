// This project's single Objective-C bridging header (Xcode allows only one
// per target, set via SWIFT_OBJC_BRIDGING_HEADER in project.pbxproj) - kept
// under its original sherpa-onnx-specific filename to avoid an unrelated
// project.pbxproj build-setting change, but it now bridges two vendored C
// dependencies:
//
// - sherpa-onnx's C API (from sherpa-onnx.xcframework, fetched by
//   third-party/sherpa-onnx/setup-ios.sh - see SHERPA_ONNX_SETUP.md).
//   Sources/KmpInterop/PocketTTS/SherpaOnnx.swift (vendored from sherpa-onnx
//   itself) calls the C functions this declares directly - it predates
//   sherpa-onnx's newer Swift Package Manager support, which uses a proper
//   Clang module (`import SherpaOnnxC`) instead. This project isn't set up
//   as a Swift package, so the traditional bridging-header route is used.
//
// - the vendored Argon2 reference implementation (ThirdParty/Argon2 - see
//   ThirdParty/Argon2/VENDORING.md). Sources/VaultCrypto/Argon2idKdf.swift
//   calls argon2id_hash_raw directly. Xcode's per-target header map makes a
//   quote-include ("argon2.h") of any header that's a member of this target
//   reachable by bare name from here regardless of subdirectory, which is
//   why sherpa-onnx's c-api.h above needs no extra configuration - but
//   ThirdParty/Argon2/src/blake2/blake2.h itself uses an ANGLE-bracket
//   #include <argon2.h> (an upstream quirk, not ours), which the header map
//   does NOT resolve. That's why HEADER_SEARCH_PATHS in project.pbxproj
//   (both Debug and Release configs) explicitly adds
//   ThirdParty/Argon2/include - don't remove it as looking redundant.
#include "c-api.h"
#include "argon2.h"
