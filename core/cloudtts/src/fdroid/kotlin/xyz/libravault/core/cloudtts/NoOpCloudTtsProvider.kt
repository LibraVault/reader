package xyz.libravault.core.cloudtts

import javax.inject.Inject

/**
 * F-Droid has no cloud TTS backend at all — no vendor HTTP client dependency
 * reaches this flavor's classpath (verify: `./gradlew assembleFdroidDebug`
 * then `:app:dependencies --configuration fdroidDebugRuntimeClasspath`
 * should show none of the five vendors' HTTP clients, same manual check
 * `core:billing` was verified with).
 *
 * Unreachable in practice even without this class existing:
 * `NoOpBillingClient.observeSubscriptionActive()` always emits `false` on
 * F-Droid, so [xyz.libravault.core.cloudtts.CloudTtsGate] never opens. This
 * exists anyway so the flavor split is real and provable, matching
 * `NoOpBillingClient`'s shape exactly.
 */
class NoOpCloudTtsProvider @Inject constructor() : CloudTtsProvider {

    override suspend fun synthesize(
        provider: CloudProviderId,
        text: String,
        voiceId: String,
        apiKey: String,
    ): Result<ByteArray> = Result.failure(UnsupportedOperationException("Cloud TTS is not available on the F-Droid build"))

    override suspend fun validateKey(provider: CloudProviderId, apiKey: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Cloud TTS is not available on the F-Droid build"))
}
