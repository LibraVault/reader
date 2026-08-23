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
    fun `loadCredentials returns null when nothing has been saved`(@TempDir tempDir: File) = runTest {
        assertNull(store(tempDir).loadCredentials(CloudProviderId.ELEVENLABS))
    }

    @Test
    fun `saveCredentials then loadCredentials round-trips a single api_key field`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        cloudApiKeyStore.saveCredentials(CloudProviderId.OPENAI, mapOf(CloudCredentialFields.API_KEY to "sk-test-12345"))
        assertEquals(mapOf(CloudCredentialFields.API_KEY to "sk-test-12345"), cloudApiKeyStore.loadCredentials(CloudProviderId.OPENAI))
    }

    @Test
    fun `saveCredentials round-trips Amazon Polly's multi-field AWS credentials`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        val pollyCredentials = mapOf(
            CloudCredentialFields.ACCESS_KEY_ID to "AKIAEXAMPLE",
            CloudCredentialFields.SECRET_ACCESS_KEY to "supersecret",
            CloudCredentialFields.REGION to "us-east-1",
        )
        cloudApiKeyStore.saveCredentials(CloudProviderId.AMAZON_POLLY, pollyCredentials)
        assertEquals(pollyCredentials, cloudApiKeyStore.loadCredentials(CloudProviderId.AMAZON_POLLY))
    }

    @Test
    fun `credentials for different providers are stored independently`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        cloudApiKeyStore.saveCredentials(CloudProviderId.ELEVENLABS, mapOf(CloudCredentialFields.API_KEY to "elevenlabs-key"))
        cloudApiKeyStore.saveCredentials(CloudProviderId.AZURE_SPEECH, mapOf(CloudCredentialFields.API_KEY to "azure-key", CloudCredentialFields.REGION to "eastus"))

        assertEquals(mapOf(CloudCredentialFields.API_KEY to "elevenlabs-key"), cloudApiKeyStore.loadCredentials(CloudProviderId.ELEVENLABS))
        assertEquals("azure-key", cloudApiKeyStore.loadCredentials(CloudProviderId.AZURE_SPEECH)?.get(CloudCredentialFields.API_KEY))
        assertNull(cloudApiKeyStore.loadCredentials(CloudProviderId.GOOGLE_CLOUD_TTS))
    }

    @Test
    fun `clearCredentials removes saved credentials`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        cloudApiKeyStore.saveCredentials(
            CloudProviderId.AMAZON_POLLY,
            mapOf(
                CloudCredentialFields.ACCESS_KEY_ID to "AKIAEXAMPLE",
                CloudCredentialFields.SECRET_ACCESS_KEY to "supersecret",
                CloudCredentialFields.REGION to "us-east-1",
            ),
        )
        cloudApiKeyStore.clearCredentials(CloudProviderId.AMAZON_POLLY)
        assertNull(cloudApiKeyStore.loadCredentials(CloudProviderId.AMAZON_POLLY))
    }

    @Test
    fun `on-disk store never contains plaintext credential values`(@TempDir tempDir: File) = runTest {
        val dataStoreFile = File(tempDir, "test_cloud_tts_api_keys.preferences_pb")
        val cloudApiKeyStore = RealCloudApiKeyStore(
            dataStore = PreferenceDataStoreFactory.create(produceFile = { dataStoreFile }),
            hardwareKeyWrapFactory = FakeHardwareKeyWrapFactory(),
        )
        val secretValue = "sk-super-secret-do-not-leak"
        cloudApiKeyStore.saveCredentials(CloudProviderId.OPENAI, mapOf(CloudCredentialFields.API_KEY to secretValue))

        val onDisk = dataStoreFile.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(!onDisk.contains(secretValue), "plaintext credential value must never appear in the on-disk store")
    }

    @Test
    fun `propagates KeystoreHardwareUnavailableException rather than silently downgrading`(@TempDir tempDir: File) = runTest {
        val unavailableFactory = FakeHardwareKeyWrapFactory().apply { simulateHardwareUnavailable = true }
        val cloudApiKeyStore = store(tempDir, unavailableFactory)

        try {
            cloudApiKeyStore.saveCredentials(CloudProviderId.ELEVENLABS, mapOf(CloudCredentialFields.API_KEY to "sk-test"))
            throw AssertionError("Expected KeystoreHardwareUnavailableException")
        } catch (e: KeystoreHardwareUnavailableException) {
            // Expected — see RealCloudApiKeyStore's class doc: callers (Settings
            // UI) must surface this, never fall back to a software-backed key.
        }
    }

    @Test
    fun `saveCredentials rejects a map missing a required field`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        try {
            // Azure needs api_key AND region — only api_key given.
            cloudApiKeyStore.saveCredentials(CloudProviderId.AZURE_SPEECH, mapOf(CloudCredentialFields.API_KEY to "azure-key"))
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected — fail fast at save time, not at synthesis time later.
        }
    }

    @Test
    fun `saveCredentials rejects a map with unexpected extra fields`(@TempDir tempDir: File) = runTest {
        val cloudApiKeyStore = store(tempDir)
        try {
            cloudApiKeyStore.saveCredentials(
                CloudProviderId.OPENAI,
                mapOf(CloudCredentialFields.API_KEY to "sk-test", CloudCredentialFields.REGION to "us-east-1"),
            )
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected — OpenAI doesn't take a region; an extra field usually
            // means the caller mixed up which provider it's saving for.
        }
    }

    @Test
    fun `loadCredentials returns null rather than throwing when the wrap key has been lost`(@TempDir tempDir: File) = runTest {
        val keyWrapFactory = FakeHardwareKeyWrapFactory()
        val cloudApiKeyStore = store(tempDir, keyWrapFactory)
        cloudApiKeyStore.saveCredentials(CloudProviderId.OPENAI, mapOf(CloudCredentialFields.API_KEY to "sk-test"))

        // Simulate the Keystore losing the key after it was used to wrap this
        // entry (KeystoreKeyLostException) — the ciphertext on disk is now
        // permanently unreadable. loadCredentials must treat this as "nothing
        // saved," not throw VaultAuthenticationException out of a suspend
        // function whose signature promises Map<String,String>?.
        keyWrapFactory.forgetKey("cloud_tts_api_keys")

        assertNull(cloudApiKeyStore.loadCredentials(CloudProviderId.OPENAI))
    }

    @Test
    fun `saving new credentials after the wrap key was lost self-heals with a fresh key`(@TempDir tempDir: File) = runTest {
        val keyWrapFactory = FakeHardwareKeyWrapFactory()
        val cloudApiKeyStore = store(tempDir, keyWrapFactory)
        cloudApiKeyStore.saveCredentials(CloudProviderId.OPENAI, mapOf(CloudCredentialFields.API_KEY to "sk-old"))
        keyWrapFactory.forgetKey("cloud_tts_api_keys")

        // A write after key loss must succeed (mint a fresh key), even though
        // a read in the same state returns null.
        cloudApiKeyStore.saveCredentials(CloudProviderId.OPENAI, mapOf(CloudCredentialFields.API_KEY to "sk-new"))
        assertEquals(mapOf(CloudCredentialFields.API_KEY to "sk-new"), cloudApiKeyStore.loadCredentials(CloudProviderId.OPENAI))
    }

    @Test
    fun `CloudCredentialFields requiredFields matches each provider's real API shape`() {
        assertEquals(setOf(CloudCredentialFields.API_KEY), CloudCredentialFields.requiredFields(CloudProviderId.ELEVENLABS))
        assertEquals(setOf(CloudCredentialFields.API_KEY), CloudCredentialFields.requiredFields(CloudProviderId.OPENAI))
        assertEquals(setOf(CloudCredentialFields.API_KEY), CloudCredentialFields.requiredFields(CloudProviderId.GOOGLE_CLOUD_TTS))
        assertEquals(setOf(CloudCredentialFields.API_KEY, CloudCredentialFields.REGION), CloudCredentialFields.requiredFields(CloudProviderId.AZURE_SPEECH))
        assertEquals(
            setOf(CloudCredentialFields.ACCESS_KEY_ID, CloudCredentialFields.SECRET_ACCESS_KEY, CloudCredentialFields.REGION),
            CloudCredentialFields.requiredFields(CloudProviderId.AMAZON_POLLY),
        )
    }
}
