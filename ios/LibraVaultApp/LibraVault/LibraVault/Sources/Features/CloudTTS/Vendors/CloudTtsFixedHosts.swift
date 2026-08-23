import Foundation

/// The fixed, closed set of hosts the five vendor adapters may ever call — single
/// source of truth for `CloudTtsFixedHostsRegressionTests`. Direct port of Android's
/// `CloudTtsFixedHosts` (core/cloudtts/vendor/CloudTtsFixedHosts.kt). PRD §7: pinning
/// these means a future change can't silently turn one into a user-configurable value,
/// or add a sixth vendor, without that being a deliberate, reviewed diff — no custom/
/// arbitrary endpoint in v1 (PRD §3).
///
/// Azure and Amazon Polly are region-specific (the region comes from the user's own
/// credentials, PRD BYOK) — for those two, only the fixed host *pattern* is pinned, not
/// a literal, matching the PRD's explicit callout for Azure. Azure additionally has two
/// real hosts: the synthesis endpoint and a separate token-issuance endpoint used for
/// the (non-synthesizing) key-validation call.
enum CloudTtsFixedHosts {
    static let elevenLabs = "api.elevenlabs.io"
    static let openAI = "api.openai.com"
    static let googleCloudTTS = "texttospeech.googleapis.com"

    static let azureSpeechHostSuffix = ".tts.speech.microsoft.com"
    static let azureTokenHostSuffix = ".api.cognitive.microsoft.com"

    static let amazonPollyHostPrefix = "polly."
    static let amazonPollyHostSuffix = ".amazonaws.com"

    static func azureSpeechHost(region: String) -> String { "\(region)\(azureSpeechHostSuffix)" }
    static func azureTokenHost(region: String) -> String { "\(region)\(azureTokenHostSuffix)" }
    static func pollyHost(region: String) -> String { "\(amazonPollyHostPrefix)\(region)\(amazonPollyHostSuffix)" }
}
