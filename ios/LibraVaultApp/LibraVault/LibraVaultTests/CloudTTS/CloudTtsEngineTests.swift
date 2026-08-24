import XCTest
@testable import LibraVault

@MainActor
final class CloudTtsEngineTests: XCTestCase {

    private func makeIsolatedPreferences() -> CloudVoicePreferences {
        let suiteName = "CloudTtsEngineTests.\(UUID().uuidString)"
        return CloudVoicePreferences(defaults: UserDefaults(suiteName: suiteName)!)
    }

    /// Fully configured: consented, a provider selected, a voice id set, and matching
    /// credentials saved — the state every "does the happy path actually run" test
    /// starts from, then individually un-configures one piece per gate/config test.
    private func makeConfiguredPreferences(consentEnabled: Bool = true) -> (CloudVoicePreferences, FakeCloudApiKeyStore) {
        let prefs = makeIsolatedPreferences()
        prefs.save(consentEnabled: consentEnabled)
        prefs.save(selectedProvider: .openAI)
        prefs.save(selectedVoiceID: "alloy")
        let apiKeyStore = FakeCloudApiKeyStore()
        try? apiKeyStore.save(provider: .openAI, credentials: [.apiKey: "sk-test"])
        return (prefs, apiKeyStore)
    }

    private func makeEngine(
        isSubscribed: Bool = true,
        preferences: CloudVoicePreferences? = nil,
        apiKeyStore: CloudApiKeyStore? = nil,
        cloudTtsProvider: FakeCloudTtsProvider = FakeCloudTtsProvider(),
        fallbackEngine: FakeTTSEngine = FakeTTSEngine(),
        playback: FakeCloudPlayback = FakeCloudPlayback()
    ) -> CloudTtsEngine {
        let (defaultPrefs, defaultStore) = makeConfiguredPreferences()
        return CloudTtsEngine(
            isSubscribedProvider: { isSubscribed },
            voicePreferences: preferences ?? defaultPrefs,
            apiKeyStore: apiKeyStore ?? defaultStore,
            cloudTtsProvider: cloudTtsProvider,
            fallbackEngine: fallbackEngine,
            playback: playback
        )
    }

    // MARK: - Gate / configuration

    func testFallsBackWhenNotSubscribed() async {
        let fallback = FakeTTSEngine()
        let provider = FakeCloudTtsProvider()
        let engine = makeEngine(isSubscribed: false, cloudTtsProvider: provider, fallbackEngine: fallback)

        await engine.speak(text: "hello", rate: 1.0)

        XCTAssertEqual(provider.synthesizeCallCount, 0, "must not call the vendor when the gate is closed")
        XCTAssertEqual(fallback.speakCallCount, 1)
        XCTAssertEqual(fallback.lastSpokenText, "hello")
    }

    /// Regression guard for the single most safety-critical PRD requirement (§4):
    /// buying the subscription alone must never be enough on its own.
    func testFallsBackWhenSubscribedButConsentIsFalse() async {
        let (prefs, store) = makeConfiguredPreferences(consentEnabled: false)
        let fallback = FakeTTSEngine()
        let provider = FakeCloudTtsProvider()
        let engine = makeEngine(isSubscribed: true, preferences: prefs, apiKeyStore: store, cloudTtsProvider: provider, fallbackEngine: fallback)

        await engine.speak(text: "hello", rate: 1.0)

        XCTAssertEqual(provider.synthesizeCallCount, 0)
        XCTAssertEqual(fallback.speakCallCount, 1)
    }

    func testFallsBackWhenNoProviderSelected() async {
        let prefs = makeIsolatedPreferences()
        prefs.save(consentEnabled: true)
        // deliberately no selectedProvider/selectedVoiceID saved
        let fallback = FakeTTSEngine()
        let engine = makeEngine(preferences: prefs, apiKeyStore: FakeCloudApiKeyStore(), fallbackEngine: fallback)

        await engine.speak(text: "hello", rate: 1.0)

        XCTAssertEqual(fallback.speakCallCount, 1)
    }

    func testFallsBackWhenVoiceIDIsEmpty() async {
        let prefs = makeIsolatedPreferences()
        prefs.save(consentEnabled: true)
        prefs.save(selectedProvider: .openAI)
        prefs.save(selectedVoiceID: "")
        let store = FakeCloudApiKeyStore()
        try? store.save(provider: .openAI, credentials: [.apiKey: "sk-test"])
        let fallback = FakeTTSEngine()
        let engine = makeEngine(preferences: prefs, apiKeyStore: store, fallbackEngine: fallback)

        await engine.speak(text: "hello", rate: 1.0)

        XCTAssertEqual(fallback.speakCallCount, 1)
    }

    func testFallsBackWhenProviderIsNotConfiguredWithCredentials() async {
        let prefs = makeIsolatedPreferences()
        prefs.save(consentEnabled: true)
        prefs.save(selectedProvider: .openAI)
        prefs.save(selectedVoiceID: "alloy")
        // deliberately empty — no credentials saved for .openAI
        let fallback = FakeTTSEngine()
        let engine = makeEngine(preferences: prefs, apiKeyStore: FakeCloudApiKeyStore(), fallbackEngine: fallback)

        await engine.speak(text: "hello", rate: 1.0)

        XCTAssertEqual(fallback.speakCallCount, 1)
    }

    // MARK: - Happy path

    func testFullyConfiguredAndSubscribedSynthesizesAndPlaysWithoutFallingBack() async {
        let fakeAudio = Data([9, 9, 9])
        let provider = FakeCloudTtsProvider()
        provider.synthesizeBehavior = .success(fakeAudio)
        let playback = FakeCloudPlayback()
        let fallback = FakeTTSEngine()
        let engine = makeEngine(cloudTtsProvider: provider, fallbackEngine: fallback, playback: playback)

        await engine.speak(text: "hello world", rate: 1.0)

        XCTAssertEqual(provider.synthesizeCallCount, 1)
        XCTAssertEqual(playback.playedData, [fakeAudio])
        XCTAssertEqual(fallback.speakCallCount, 0, "the happy path must never fall back")
    }

    // MARK: - Failure fallback

    func testFallsBackWhenVendorSynthesizeFails() async {
        let provider = FakeCloudTtsProvider()
        provider.synthesizeBehavior = .failure(CloudTtsProviderError.httpError(statusCode: 500, body: ""))
        let fallback = FakeTTSEngine()
        let engine = makeEngine(cloudTtsProvider: provider, fallbackEngine: fallback)

        await engine.speak(text: "hello", rate: 1.0)

        XCTAssertEqual(fallback.speakCallCount, 1)
        XCTAssertEqual(fallback.lastSpokenText, "hello")
    }

    func testFallsBackWhenPlaybackFails() async {
        let playback = FakeCloudPlayback()
        playback.playError = CloudPlaybackError.decodeError
        let fallback = FakeTTSEngine()
        let engine = makeEngine(fallbackEngine: fallback, playback: playback)

        await engine.speak(text: "hello", rate: 1.0)

        XCTAssertEqual(fallback.speakCallCount, 1)
    }

    // MARK: - Cancellation (stop() must not trigger a fallback)

    /// The single most important regression test in this file: pressing Stop while a
    /// cloud synthesis call is genuinely in flight must stop cleanly, NOT start the
    /// on-device engine speaking a moment later. Mirrors Android's `CloudTtsEngineTest`
    /// using `SuspendingCloudTtsProvider`/`awaitCancellation()` for the identical
    /// regression — the fix this guards is `runCatchingCancellable`'s reasoning
    /// (`kotlin.runCatching` also catches `CancellationException`) ported to Swift's
    /// "does the broad `catch` in `performSpeak` accidentally swallow `CancellationError`
    /// too" equivalent risk.
    func testStopCancelsInFlightSynthesizeCallWithoutFallingBack() async {
        let provider = FakeCloudTtsProvider()
        provider.synthesizeBehavior = .hangUntilCancelled
        let fallback = FakeTTSEngine()
        let playback = FakeCloudPlayback()
        let engine = makeEngine(cloudTtsProvider: provider, fallbackEngine: fallback, playback: playback)

        let speakTask = Task { await engine.speak(text: "hello", rate: 1.0) }
        // Give performSpeak a moment to actually enter the hanging synthesize() call
        // before stop() cancels it — otherwise stop() could race ahead of speak()
        // even starting.
        try? await Task.sleep(nanoseconds: 50_000_000)
        await engine.stop()
        await speakTask.value

        XCTAssertEqual(fallback.speakCallCount, 0, "stop() must not trigger a fallback to the on-device engine")
        XCTAssertEqual(playback.playedData.count, 0)
    }

    // MARK: - pause/resume routing

    func testPauseRoutesToCloudPlaybackWhenSpeakingViaCloud() async {
        let playback = FakeCloudPlayback()
        let fallback = FakeTTSEngine()
        let engine = makeEngine(fallbackEngine: fallback, playback: playback)
        await engine.speak(text: "hello", rate: 1.0) // happy path — speaks via cloud

        await engine.pause()

        XCTAssertEqual(playback.pauseCallCount, 1)
        XCTAssertEqual(fallback.pauseCallCount, 0)
    }

    func testPauseRoutesToFallbackEngineWhenSpeakingViaFallback() async {
        let provider = FakeCloudTtsProvider()
        provider.synthesizeBehavior = .failure(CloudTtsProviderError.invalidResponse)
        let playback = FakeCloudPlayback()
        let fallback = FakeTTSEngine()
        let engine = makeEngine(cloudTtsProvider: provider, fallbackEngine: fallback, playback: playback)
        await engine.speak(text: "hello", rate: 1.0) // falls back

        await engine.pause()

        XCTAssertEqual(fallback.pauseCallCount, 1)
        XCTAssertEqual(playback.pauseCallCount, 0)
    }

    func testResumeRoutesToCloudPlaybackWhenSpeakingViaCloud() async {
        let playback = FakeCloudPlayback()
        let fallback = FakeTTSEngine()
        let engine = makeEngine(fallbackEngine: fallback, playback: playback)
        await engine.speak(text: "hello", rate: 1.0)

        await engine.resume()

        XCTAssertEqual(playback.resumeCallCount, 1)
        XCTAssertEqual(fallback.resumeCallCount, 0)
    }

    func testResumeRoutesToFallbackEngineWhenSpeakingViaFallback() async {
        let provider = FakeCloudTtsProvider()
        provider.synthesizeBehavior = .failure(CloudTtsProviderError.invalidResponse)
        let playback = FakeCloudPlayback()
        let fallback = FakeTTSEngine()
        let engine = makeEngine(cloudTtsProvider: provider, fallbackEngine: fallback, playback: playback)
        await engine.speak(text: "hello", rate: 1.0)

        await engine.resume()

        XCTAssertEqual(fallback.resumeCallCount, 1)
        XCTAssertEqual(playback.resumeCallCount, 0)
    }

    // MARK: - initialize()

    func testInitializeInitializesTheFallbackEngine() async throws {
        let fallback = FakeTTSEngine()
        let engine = makeEngine(fallbackEngine: fallback)

        try await engine.initialize()

        XCTAssertEqual(fallback.initializeCallCount, 1)
    }
}
