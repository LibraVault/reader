package xyz.libravault.core.cloudtts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.libravault.core.cloudtts.testing.FakeHardwareKeyWrapFactory
import xyz.libravault.core.vaultstore.KeystoreHardwareUnavailableException
import java.io.File

class CloudApiKeyStoreTest {

    private fun store(tempDir: File, keyWrapFactory: FakeHardwareKeyWrapFactory = FakeHardwareKeyWrapFactory()): RealCloudApiKeyStore =
        RealCloudApiKeyStore(
            dataStore = PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "test_cloud_tts_api_keys.preferences_pb") },
            ),
            hardwareKeyWrapFactory = keyWrapFactory,
        )

    @Test
    fun `loadKey returns null when nothing has been saved`(@TempDir tempDir: File) = runTest {
        assertNull(store(tempDir).loadKey(CloudProviderId.ELEVENLABS))
    }

    @Test
    fun `saveKey then loadKey round-trips the plaintext key`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        cloudApiKeyStore.saveKey(CloudProviderId.OPENAI, "sk-test-12345")
        assertEquals("sk-test-12345", cloudApiKeyStore.loadKey(CloudProviderId.OPENAI))
    }

    @Test
    fun `keys for different providers are stored independently`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        cloudApiKeyStore.saveKey(CloudProviderId.ELEVENLABS, "elevenlabs-key")
        cloudApiKeyStore.saveKey(CloudProviderId.AZURE_SPEECH, "azure-key")

        assertEquals("elevenlabs-key", cloudApiKeyStore.loadKey(CloudProviderId.ELEVENLABS))
        assertEquals("azure-key", cloudApiKeyStore.loadKey(CloudProviderId.AZURE_SPEECH))
        assertNull(cloudApiKeyStore.loadKey(CloudProviderId.GOOGLE_CLOUD_TTS))
    }

    @Test
    fun `clearKey removes a saved key`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        cloudApiKeyStore.saveKey(CloudProviderId.AMAZON_POLLY, "polly-key")
        cloudApiKeyStore.clearKey(CloudProviderId.AMAZON_POLLY)
        assertNull(cloudApiKeyStore.loadKey(CloudProviderId.AMAZON_POLLY))
    }

    @Test
    fun `on-disk store never contains the plaintext key`(@TempDir tempDir: File) = runTest {
        val dataStoreFile = File(tempDir, "test_cloud_tts_api_keys.preferences_pb")
        val cloudApiKeyStore = RealCloudApiKeyStore(
            dataStore = PreferenceDataStoreFactory.create(produceFile = { dataStoreFile }),
            hardwareKeyWrapFactory = FakeHardwareKeyWrapFactory(),
        )
        val plaintext = "sk-super-secret-do-not-leak"
        cloudApiKeyStore.saveKey(CloudProviderId.OPENAI, plaintext)

        val onDisk = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(!onDisk.contains(plaintext), "plaintext API key must never appear in the on-disk store")
    }

    @Test
    fun `propagates KeystoreHardwareUnavailableException rather than silently downgrading`(@TempDir tempDir: File) = runTest {
        val unavailableFactory = FakeHardwareKeyWrapFactory().apply { simulateHardwareUnavailable = true }
        val cloudApiKeyStore = store(tempDir, unavailableFactory)

        try {
            cloudApiKeyStore.saveKey(CloudProviderId.ELEVENLABS, "sk-test")
            throw AssertionError("Expected KeystoreHardwareUnavailableException")
        } catch (e: KeystoreHardwareUnavailableException) {
            // Expected — see RealCloudApiKeyStore's class doc: callers (Settings
            // UI) must surface this, never fall back to a software-backed key.
        }
    }
}
