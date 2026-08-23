package xyz.libravault.core.cloudtts

import xyz.libravault.core.cloudtts.vendor.AmazonPollyAdapter
import xyz.libravault.core.cloudtts.vendor.AzureSpeechAdapter
import xyz.libravault.core.cloudtts.vendor.ElevenLabsAdapter
import xyz.libravault.core.cloudtts.vendor.GoogleCloudTtsAdapter
import xyz.libravault.core.cloudtts.vendor.OpenAiAdapter
import xyz.libravault.core.cloudtts.vendor.VendorTtsAdapter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fans out to one of the five vendor HTTP adapters by [CloudProviderId].
 * F-Droid's counterpart is [NoOpCloudTtsProvider] — this class only exists
 * in the `play` flavor's compilation.
 *
 * Not `internal`: this class's own primary constructor is `internal`
 * (Hilt uses the public `@Inject` secondary one — same pattern as
 * [xyz.libravault.core.cloudtts.RealCloudApiKeyStore]), but the class
 * itself, [xyz.libravault.core.cloudtts.vendor.VendorTtsAdapter], and the
 * five vendor adapter classes all need Kotlin-public visibility — a public
 * class can't have a constructor exposing `internal` parameter types.
 * Nothing outside this module references any of them directly regardless;
 * only [CloudTtsProvider] (bound to this class in
 * `di.CloudTtsPlayModule`) is the real public surface.
 */
@Singleton
class RealCloudTtsProvider @Inject constructor(
    private val elevenLabsAdapter: ElevenLabsAdapter,
    private val openAiAdapter: OpenAiAdapter,
    private val googleCloudTtsAdapter: GoogleCloudTtsAdapter,
    private val azureSpeechAdapter: AzureSpeechAdapter,
    private val amazonPollyAdapter: AmazonPollyAdapter,
) : CloudTtsProvider {

    override suspend fun synthesize(
        provider: CloudProviderId,
        text: String,
        voiceId: String,
        credentials: Map<String, String>,
    ): Result<ByteArray> = adapterFor(provider).synthesize(text, voiceId, credentials)

    override suspend fun validateKey(provider: CloudProviderId, credentials: Map<String, String>): Result<Unit> =
        adapterFor(provider).validateKey(credentials)

    private fun adapterFor(provider: CloudProviderId): VendorTtsAdapter = when (provider) {
        CloudProviderId.ELEVENLABS -> elevenLabsAdapter
        CloudProviderId.OPENAI -> openAiAdapter
        CloudProviderId.GOOGLE_CLOUD_TTS -> googleCloudTtsAdapter
        CloudProviderId.AZURE_SPEECH -> azureSpeechAdapter
        CloudProviderId.AMAZON_POLLY -> amazonPollyAdapter
    }
}
