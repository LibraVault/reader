package xyz.libravault.core.vaultstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class VaultRegistryTest {

    private lateinit var baseDir: File

    @BeforeEach
    fun setUp() {
        baseDir = createTempDirectory("vault-registry-test").toFile()
    }

    @Test
    fun `list on a fresh directory is empty, no file created`() {
        assertTrue(VaultRegistry.list(baseDir).isEmpty())
        assertTrue(baseDir.listFiles()?.isEmpty() != false)
    }

    @Test
    fun `add then list round-trips`() {
        val entry = VaultRegistryEntryDto(id = "abc123", displayName = "Personal", createdAtEpochMillis = 42L)
        VaultRegistry.add(baseDir, entry)

        assertEquals(listOf(entry), VaultRegistry.list(baseDir))
    }

    @Test
    fun `add preserves insertion order across multiple entries`() {
        val first = VaultRegistryEntryDto("id-1", "First", 1L)
        val second = VaultRegistryEntryDto("id-2", "Second", 2L)
        VaultRegistry.add(baseDir, first)
        VaultRegistry.add(baseDir, second)

        assertEquals(listOf(first, second), VaultRegistry.list(baseDir))
    }

    @Test
    fun `add rejects a duplicate id`() {
        val entry = VaultRegistryEntryDto("dup", "One", 1L)
        VaultRegistry.add(baseDir, entry)

        assertThrows(IllegalStateException::class.java) {
            VaultRegistry.add(baseDir, entry.copy(displayName = "Two"))
        }
        // The rejected write must not have clobbered the existing entry.
        assertEquals(listOf(entry), VaultRegistry.list(baseDir))
    }

    @Test
    fun `remove drops only the matching id`() {
        val keep = VaultRegistryEntryDto("keep", "Keep me", 1L)
        val drop = VaultRegistryEntryDto("drop", "Drop me", 2L)
        VaultRegistry.add(baseDir, keep)
        VaultRegistry.add(baseDir, drop)

        VaultRegistry.remove(baseDir, "drop")

        assertEquals(listOf(keep), VaultRegistry.list(baseDir))
    }

    @Test
    fun `remove of an unknown id is a no-op`() {
        val entry = VaultRegistryEntryDto("id", "Name", 1L)
        VaultRegistry.add(baseDir, entry)

        VaultRegistry.remove(baseDir, "does-not-exist")

        assertEquals(listOf(entry), VaultRegistry.list(baseDir))
    }

    @Test
    fun `rename updates displayName without touching id or createdAt`() {
        val entry = VaultRegistryEntryDto("id", "Old name", 7L)
        VaultRegistry.add(baseDir, entry)

        VaultRegistry.rename(baseDir, "id", "New name")

        assertEquals(listOf(entry.copy(displayName = "New name")), VaultRegistry.list(baseDir))
    }

    @Test
    fun `vaultDir is a subdirectory named after the id, not created eagerly`() {
        val dir = VaultRegistry.vaultDir(baseDir, "some-id")

        assertEquals(File(baseDir, "some-id"), dir)
        assertTrue(!dir.exists())
    }

    @Test
    fun `no stray tmp file survives a successful write`() {
        VaultRegistry.add(baseDir, VaultRegistryEntryDto("id", "Name", 1L))

        val leftoverTmp = baseDir.listFiles { f -> f.name.endsWith(".tmp") }
        assertTrue(leftoverTmp.isNullOrEmpty())
    }
}
