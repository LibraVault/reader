package xyz.libravault.core.tts.pocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PocketModelManagerTest {

    @Test
    fun `ModelStatus sealed class has expected types`() {
        val idle = ModelStatus.Idle
        val downloading = ModelStatus.Downloading(0.5f)
        val ready = ModelStatus.Ready("/path/to/model")
        val failed = ModelStatus.Failed("error message")

        assertNotNull(idle)
        assertNotNull(downloading)
        assertNotNull(ready)
        assertNotNull(failed)
    }

    @Test
    fun `Downloading status captures progress`() {
        val status = ModelStatus.Downloading(0.75f)
        assertEquals(0.75f, status.progress)
    }

    @Test
    fun `Ready status stores model path`() {
        val path = "/data/user/0/xyz.libravault.app/files/pocket-tts/model"
        val status = ModelStatus.Ready(path)
        assertEquals(path, status.path)
    }

    @Test
    fun `Failed status stores error message`() {
        val error = "Checksum mismatch"
        val status = ModelStatus.Failed(error)
        assertEquals(error, status.error)
    }

    @Test
    fun `SHA256 hash format is hex string`() {
        val hexPattern = Regex("^[a-f0-9]{64}$")
        val exampleHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertTrue(hexPattern.matches(exampleHash), "SHA256 should be 64 hex chars")
    }
}
