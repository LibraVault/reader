import Foundation

/// `TTSEngineProtocol` conformer for Premium Cloud TTS Voices — the engine
/// `LibravaultDomainBridge.switchTTSEngine(to:)` constructs for `TTSEngineType.cloud`.
/// Mirrors Android's `CloudTtsEngine`: the four-quadrant gate (`CloudTtsGate`) is
/// re-checked on every `speak()` call, not just once at engine selection, so a lapsed
/// subscription or revoked consent stops new calls mid-session; any failure (gate
/// closed, not configured, vendor error) falls back to the on-device engine via the
/// same code path, never a silent stall.
///
/// `@MainActor`: `isSubscribedProvider` reads `StoreKitBillingManager.isSubscribed`, a
/// main-actor-isolated `@Published` property (see `LibravaultDomainBridge`'s
/// `configureCloudTts`/`switchTTSEngine`, which construct this on the main actor and
/// pass a closure that touches it) — keeping this whole type on the main actor avoids
/// any cross-actor synchronization for that read, matching how `StoreKitBillingManager`/
/// `LibravaultDomainBridge`/`AppState` are already all `@MainActor` themselves.
@MainActor
final class CloudTtsEngine: TTSEngineProtocol {
    /// Not `StoreKitBillingManager` directly — a closure, so `CloudTtsEngineTests` can
    /// supply `{ true }`/`{ false }` without any live StoreKit dependency (mirrors the
    /// implementation plan's "Known gaps" note: only a thin wiring test, not the gate
    /// logic itself, would ever need real StoreKit).
    private let isSubscribedProvider: @MainActor () -> Bool
    private let voicePreferences: CloudVoicePreferences
    private let apiKeyStore: CloudApiKeyStore
    private let cloudTtsProvider: CloudTtsProvider
    private let fallbackEngine: TTSEngineProtocol
    private let playback: CloudPlayback

    /// Set whenever `speak()` falls back to the on-device engine — surfaced for
    /// diagnostics/tests, mirrors Android's identical `lastFallbackReason` property
    /// (added there specifically because `android.util.Log` isn't mockable in plain
    /// JVM unit tests; kept here too for parity and because it's a genuinely useful
    /// signal regardless of platform).
    private(set) var lastFallbackReason: String?

    private var activeTask: Task<Void, Never>?
    /// Which engine actually owns the currently-playing audio — `pause()`/`resume()`
    /// need to route to whichever one is real, not always the cloud `playback`.
    private var isSpeakingViaFallback = false

    init(
        isSubscribedProvider: @escaping @MainActor () -> Bool,
        voicePreferences: CloudVoicePreferences = CloudVoicePreferences(),
        apiKeyStore: CloudApiKeyStore = KeychainCloudApiKeyStore(),
        cloudTtsProvider: CloudTtsProvider = RealCloudTtsProvider(),
        fallbackEngine: TTSEngineProtocol = TTSEngineBridge(),
        playback: CloudPlayback = AVAudioPlayerCloudPlayback()
    ) {
        self.isSubscribedProvider = isSubscribedProvider
        self.voicePreferences = voicePreferences
        self.apiKeyStore = apiKeyStore
        self.cloudTtsProvider = cloudTtsProvider
        self.fallbackEngine = fallbackEngine
        self.playback = playback
    }

    func initialize() async throws {
        try await fallbackEngine.initialize()
    }

    func speak(text: String, rate: Double) async {
        activeTask?.cancel()
        let task = Task { [weak self] in
            await self?.performSpeak(text: text, rate: rate)
        }
        activeTask = task
        await task.value
    }

    private func performSpeak(text: String, rate: Double) async {
        isSpeakingViaFallback = false

        guard
            let providerID = voicePreferences.loadSelectedProvider(),
            let voiceID = voicePreferences.loadSelectedVoiceID(), !voiceID.isEmpty,
            let credentials = apiKeyStore.load(provider: providerID),
            CloudTtsGate.canUseCloudTts(isSubscribed: isSubscribedProvider(), consentEnabled: voicePreferences.loadConsentEnabled())
        else {
            lastFallbackReason = "Cloud Voices not available (subscription/consent/configuration)"
            await fallBack(text: text, rate: rate)
            return
        }

        do {
            let audioData = try await cloudTtsProvider.synthesize(
                provider: providerID, text: text, voiceID: voiceID, credentials: credentials
            )
            try Task.checkCancellation()
            try await playback.play(data: audioData)
        } catch is CancellationError {
            // Swift Task cancellation (Self.stop() cancelling activeTask) — do NOT
            // fall back, matching Android's runCatchingCancellable fix: falling back
            // here would start the on-device engine speaking right after the user
            // pressed Stop.
        } catch let urlError as URLError where urlError.code == .cancelled {
            // URLSession's async bridging surfaces a cancelled in-flight network
            // call as URLError(.cancelled), NOT CancellationError, when the
            // underlying URLSessionTask itself is what gets cancelled — a real
            // Foundation nuance, not a hypothetical one (see
            // VendorTtsAdapterHelpers.executeOrFail's doc comment). Same "don't
            // fall back" reasoning as the CancellationError case above.
        } catch {
            lastFallbackReason = "Cloud TTS call failed: \(error.localizedDescription)"
            await fallBack(text: text, rate: rate)
        }
    }

    private func fallBack(text: String, rate: Double) async {
        isSpeakingViaFallback = true
        await fallbackEngine.speak(text: text, rate: rate)
    }

    func stop() async {
        activeTask?.cancel()
        playback.stop()
        await fallbackEngine.stop()
    }

    func pause() async {
        if isSpeakingViaFallback {
            await fallbackEngine.pause()
        } else {
            playback.pause()
        }
    }

    func resume() async {
        if isSpeakingViaFallback {
            await fallbackEngine.resume()
        } else {
            playback.resume()
        }
    }
}
