// Exposes sherpa-onnx's C API (from sherpa-onnx.xcframework, fetched by
// third-party/sherpa-onnx/setup-ios.sh - see SHERPA_ONNX_SETUP.md) to Swift.
// Referenced via SWIFT_OBJC_BRIDGING_HEADER in project.pbxproj.
//
// Sources/KmpInterop/PocketTTS/SherpaOnnx.swift (vendored from sherpa-onnx
// itself) calls the C functions this declares directly - it predates
// sherpa-onnx's newer Swift Package Manager support, which uses a proper
// Clang module (`import SherpaOnnxC`) instead. This project isn't set up as
// a Swift package, so the traditional bridging-header route is used.
#include "c-api.h"
