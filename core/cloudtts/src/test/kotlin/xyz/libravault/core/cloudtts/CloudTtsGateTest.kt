package xyz.libravault.core.cloudtts

import android.app.Activity
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import xyz.libravault.core.billing.SupportBillingClient
import xyz.libravault.core.tts.TtsPreferences
import java.io.File

/**
 * The four-quadrant gate test PRD §7 requires: (subscription × consent),
 * on/off, asserting the gate opens in exactly the true/true quadrant.
 */
class CloudTtsGateTest {

    private class FakeSupportBillingClient(initialSubscriptionActive: Boolean) : SupportBillingClient {
        override val isSupported: Boolean = true
        private val subscriptionActive = MutableStateFlow(initialSubscriptionActive)
        override fun observeProductsAvailable(): Flow<Boolean> = MutableStateFlow(true)
        override fun observeSubscriptionActive(): Flow<Boolean> = subscriptionActive
        override suspend fun purchaseSubscription(activity: Activity): Result<Unit> = Result.success(Unit)
        override suspend fun purchaseOneTimeTip(activity: Activity): Result<Unit> = Result.success(Unit)
    }

    private fun preferences(tempDir: File): TtsPreferences =
        TtsPreferences(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "test_tts_preferences.preferences_pb") },
            ),
        )

    @ParameterizedTest(name = "subscribed={0}, consented={1} -> canUseCloudTts={2}")
    @CsvSource(
        "false, false, false",
        "true,  false, false",
        "false, true,  false",
        "true,  true,  true",
    )
    fun `gate opens only when both subscription and consent are true`(
        subscribed: Boolean,
        consented: Boolean,
        expected: Boolean,
        @TempDir tempDir: File,
    ) = runTest {
        val prefs = preferences(tempDir)
        prefs.setCloudVoicesConsent(consented)
        val gate = CloudTtsGate(FakeSupportBillingClient(subscribed), prefs)

        val actual = gate.observeCanUseCloudTts().first()
        if (expected) assertTrue(actual) else assertFalse(actual)
    }

    @Test
    fun `buying the subscription alone never opens the gate`(@TempDir tempDir: File) = runTest {
        // Consent left at its default (false) — only the subscription flips.
        val prefs = preferences(tempDir)
        val gate = CloudTtsGate(FakeSupportBillingClient(initialSubscriptionActive = true), prefs)

        assertFalse(gate.observeCanUseCloudTts().first())
    }
}
