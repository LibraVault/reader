package xyz.libravault.core.cloudtts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import xyz.libravault.core.billing.SupportBillingClient
import xyz.libravault.core.tts.TtsPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single seam every "is a cloud TTS network call allowed right now?"
 * check goes through — PRD §4: two independent switches, BOTH required.
 * Enforced here, one level below the UI, so no other call path (a future
 * player-screen integration, a test harness bug) can fire a network call by
 * skipping a UI-only check. The engine wiring follow-up's `CloudTtsEngine`
 * re-checks this before every `speak()` call, not just once at engine
 * selection, so a lapsed subscription or revoked consent stops new cloud
 * calls immediately, mid-session.
 */
@Singleton
class CloudTtsGate @Inject constructor(
    private val billingClient: SupportBillingClient,
    private val preferences: TtsPreferences,
) {
    /**
     * True only when both the real subscription signal
     * ([SupportBillingClient.observeSubscriptionActive]) and the separate,
     * off-by-default "Cloud Voices" consent toggle
     * ([TtsPreferences.cloudVoicesConsentFlow]) are true. Buying the
     * subscription alone must never make this true — PRD §4.
     */
    fun observeCanUseCloudTts(): Flow<Boolean> =
        combine(billingClient.observeSubscriptionActive(), preferences.cloudVoicesConsentFlow) { subscribed, consented ->
            subscribed && consented
        }
}
