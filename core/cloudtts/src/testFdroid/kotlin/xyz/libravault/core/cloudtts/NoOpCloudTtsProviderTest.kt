package xyz.libravault.core.cloudtts

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NoOpCloudTtsProviderTest {

    private val provider = NoOpCloudTtsProvider()

    @Test
    fun `synthesize always fails`() = runTest {
        val result = provider.synthesize(CloudProviderId.ELEVENLABS, "text", "voice", "key")
        assertTrue(result.isFailure)
    }

    @Test
    fun `validateKey always fails`() = runTest {
        val result = provider.validateKey(CloudProviderId.OPENAI, "key")
        assertTrue(result.isFailure)
    }
}
