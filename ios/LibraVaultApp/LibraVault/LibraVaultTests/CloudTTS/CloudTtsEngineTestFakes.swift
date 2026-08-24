import Foundation
@testable import LibraVault

/// Test-only `CloudTtsProvider` whose behavior per call is fully controllable —
/// mirrors Android's `CloudTtsEngineTest`'s inline fakes (a `Result`-returning fake for
/// success/failure, `SuspendingCloudTtsProvider` for the stop()-doesn't-fall-back
/// regression test).
final class FakeCloudTtsProvider: CloudTtsProvider {
    enum Behavior {
        case success(Data)
        case failure(Error)
        /// Suspends until the calling Task is cancelled, respecting cooperative
        /// cancellation via `Task.sleep` — the closest stdlib equivalent to
        /// Kotlin's `awaitCancellation()`, used to simulate a genuinely in-flight
        /// network call for the stop()-doesn't-fall-back regression test.
        case hangUntilCancelled
    }

    var synthesizeBehavior: Behavior = .success(Data([1, 2, 3]))
    private(set) var synthesizeCallCount = 0

    func synthesize(
        provider: CloudProviderId,
        text: String,
        voiceID: String,
        credentials: [CloudCredentialField: String]
    ) async throws -> Data {
        synthesizeCallCount += 1
        switch synthesizeBehavior {
        case .success(let data):
            return data
        case .failure(let error):
            throw error
        case .hangUntilCancelled:
            try await Task.sleep(nanoseconds: 60_000_000_000)
            throw CloudTtsProviderError.invalidResponse // unreachable in practice — cancellation throws first
        }
    }

    func validateKey(provider: CloudProviderId, credentials: [CloudCredentialField: String]) async throws {}
}

/// Test-only `CloudPlayback` that records calls instead of touching real
/// `AVAudioPlayer`/audio hardware.
final class FakeCloudPlayback: CloudPlayback {
    private(set) var playedData: [Data] = []
    private(set) var pauseCallCount = 0
    private(set) var resumeCallCount = 0
    private(set) var stopCallCount = 0
    var playError: Error?

    func play(data: Data) async throws {
        playedData.append(data)
        if let playError { throw playError }
    }

    func pause() { pauseCallCount += 1 }
    func resume() { resumeCallCount += 1 }
    func stop() { stopCallCount += 1 }
}

/// Test-only `TTSEngineProtocol` — stands in for the on-device fallback engine so
/// tests can assert whether a fallback actually happened, without touching
/// `AVSpeechSynthesizer`/`TTSEngineBridge`.
final class FakeTTSEngine: TTSEngineProtocol {
    private(set) var initializeCallCount = 0
    private(set) var speakCallCount = 0
    private(set) var lastSpokenText: String?
    private(set) var stopCallCount = 0
    private(set) var pauseCallCount = 0
    private(set) var resumeCallCount = 0

    func initialize() async throws { initializeCallCount += 1 }

    func speak(text: String, rate: Double) async {
        speakCallCount += 1
        lastSpokenText = text
    }

    func stop() async { stopCallCount += 1 }
    func pause() async { pauseCallCount += 1 }
    func resume() async { resumeCallCount += 1 }
}
