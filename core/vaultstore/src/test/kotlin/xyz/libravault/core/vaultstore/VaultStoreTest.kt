package xyz.libravault.core.vaultstore

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xyz.libravault.core.vaultcrypto.Argon2Params
import xyz.libravault.core.vaultstore.testing.FakeHardwareKeyWrapFactory
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import kotlin.io.path.createTempDirectory

class VaultStoreTest {

    // Small params — correctness tests, not latency benchmarks (on-device latency
    // was already measured for the real default; see PRD §8.4b).
    private val fastParams = Argon2Params(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)

    private fun newStore(
        keyWrapFactory: FakeHardwareKeyWrapFactory = FakeHardwareKeyWrapFactory(),
        now: () -> Long = { 0L },
        usableSpace: () -> Long = { Long.MAX_VALUE / 2 },
    ): VaultStore {
        val dir = createTempDirectory(prefix = "vaultstore-test").toFile()
        dir.deleteOnExit()
        return VaultStore(dir, "test-vault-alias", keyWrapFactory, now, SecureRandom(), usableSpace)
    }

    @Test
    fun `create leaves the vault unlocked and returns a usable recovery key`() = runTest {
        val store = newStore()
        val recoveryKey = store.create("1234".toCharArray(), fastParams)

        assertTrue(store.isUnlocked)
        assertTrue(store.exists())
        assertEquals(32, recoveryKey.size)
    }

    @Test
    fun `create twice on the same directory fails`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        assertThrows<VaultAlreadyExistsException> { store.create("5678".toCharArray(), fastParams) }
    }

    @Test
    fun `create cleans up the directory if Keystore hardware is unavailable`() = runTest {
        val factory = FakeHardwareKeyWrapFactory().apply { simulateHardwareUnavailable = true }
        val store = newStore(factory)

        assertThrows<KeystoreHardwareUnavailableException> { store.create("1234".toCharArray(), fastParams) }
        assertFalse(store.exists(), "a failed create() must not leave a half-created vault behind")
    }

    @Test
    fun `lock then unlock with the correct PIN succeeds`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        store.lock()
        assertFalse(store.isUnlocked)

        val outcome = store.unlockWithPin("1234".toCharArray())
        assertEquals(UnlockOutcome.Success, outcome)
        assertTrue(store.isUnlocked)
    }

    @Test
    fun `unlock with the wrong PIN fails and does not unlock`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        store.lock()

        val outcome = store.unlockWithPin("9999".toCharArray())
        assertEquals(UnlockOutcome.WrongCredential, outcome)
        assertFalse(store.isUnlocked)
    }

    @Test
    fun `repeated wrong PINs eventually throttle`() = runTest {
        var clock = 0L
        val store = newStore(now = { clock })
        store.create("1234".toCharArray(), fastParams)
        store.lock()

        var lastOutcome: UnlockOutcome? = null
        repeat(10) {
            lastOutcome = store.unlockWithPin("9999".toCharArray())
            clock += 1 // attempts happen "instantly" in test time, so throttling must kick in
        }
        assertTrue(lastOutcome is UnlockOutcome.Throttled, "expected throttling after repeated failures, got $lastOutcome")
    }

    @Test
    fun `a successful unlock resets the failure count`() = runTest {
        var clock = 0L
        val store = newStore(now = { clock })
        store.create("1234".toCharArray(), fastParams)
        store.lock()

        repeat(3) {
            store.unlockWithPin("9999".toCharArray())
            clock += 1
        }
        clock += 10_000 // clear of any throttle window
        assertEquals(UnlockOutcome.Success, store.unlockWithPin("1234".toCharArray()))

        store.lock()
        // Immediately wrong again — if the counter had NOT reset, this could already be throttled.
        assertEquals(UnlockOutcome.WrongCredential, store.unlockWithPin("0000".toCharArray()))
    }

    @Test
    fun `recovery key unlocks even after the PIN is forgotten by the test (independent path)`() = runTest {
        val store = newStore()
        val recoveryKey = store.create("1234".toCharArray(), fastParams)
        store.lock()

        assertEquals(UnlockOutcome.Success, store.unlockWithRecoveryKey(recoveryKey))
        assertTrue(store.isUnlocked)
    }

    @Test
    fun `recovery key still unlocks after the Keystore key is lost`() = runTest {
        // This is the entire justification for the recovery key existing
        // (implementation plan §A.5) — implementation plan §A.4 failure case (c).
        val factory = FakeHardwareKeyWrapFactory()
        val store = newStore(factory)
        val recoveryKey = store.create("1234".toCharArray(), fastParams)
        store.lock()

        factory.forgetKey("test-vault-alias")

        assertEquals(UnlockOutcome.Success, store.unlockWithRecoveryKey(recoveryKey))
    }

    @Test
    fun `PIN unlock reports KeystoreKeyLost, not a generic failure, when the Keystore key is gone`() = runTest {
        val factory = FakeHardwareKeyWrapFactory()
        val store = newStore(factory)
        store.create("1234".toCharArray(), fastParams)
        store.lock()

        factory.forgetKey("test-vault-alias")

        assertEquals(UnlockOutcome.KeystoreKeyLost, store.unlockWithPin("1234".toCharArray()))
    }

    @Test
    fun `wrong recovery key fails`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        store.lock()

        val wrongKey = ByteArray(32)
        assertEquals(UnlockOutcome.WrongCredential, store.unlockWithRecoveryKey(wrongKey))
    }

    @Test
    fun `importFile while locked throws`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        store.lock()

        assertThrows<VaultLockedException> {
            store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "title", null, "pdf")
        }
    }

    @Test
    fun `imported file round-trips through the manifest and content reader`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)

        val content = ByteArray(100_000).also { SecureRandom().nextBytes(it) }
        val entry = store.importFile(
            ByteArrayInputStream(content), content.size.toLong(), "My Document", "Some Author", "pdf",
        )

        val entries = store.listEntries()
        assertEquals(1, entries.size)
        assertEquals(entry, entries[0])
        assertEquals("My Document", entries[0].title)

        store.openReader(entry.fileId).use { reader ->
            assertArrayEquals(content, reader.readAt(0, content.size))
        }
    }

    @Test
    fun `manifest survives a lock-unlock cycle`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")

        store.lock()
        store.unlockWithPin("1234".toCharArray())

        assertEquals(1, store.listEntries().size)
    }

    @Test
    fun `insufficient storage is rejected before any bytes are written`() = runTest {
        val store = newStore(usableSpace = { 100L }) // far less than what's about to be requested
        store.create("1234".toCharArray(), fastParams)

        assertThrows<InsufficientStorageException> {
            store.importFile(ByteArrayInputStream(ByteArray(1_000_000)), 1_000_000L, "title", null, "pdf")
        }
        assertTrue(store.listEntries().isEmpty(), "a rejected import must not appear in the manifest")
    }

    @Test
    fun `two imported files get distinct file ids`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)

        val e1 = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "A", null, "pdf")
        val e2 = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "B", null, "pdf")

        assertFalse(e1.fileId.contentEquals(e2.fileId))
        assertEquals(2, store.listEntries().size)
    }

    /**
     * Regression test for the ordering bug found during review: an earlier
     * draft of [VaultStore.importFile] updated the manifest BEFORE renaming
     * the temp file into place, so a failure between those two steps left the
     * manifest pointing at a file that didn't exist yet. Forces the rename
     * itself to fail (by pre-occupying the target path with a directory, so
     * `File.renameTo` cannot succeed) and asserts the manifest was never
     * touched — proving the fix's ordering, not just its happy path.
     */
    @Test
    fun `a failed rename during import leaves no manifest entry behind`() = runTest {
        // A SecureRandom subclass with fully predictable output, so the test can
        // pre-occupy the exact path importFile's internal newFileId() will pick —
        // real vault key generation (VaultKeyManager.create) uses its own
        // SecureRandom internally and is unaffected by this.
        val deterministicRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                bytes.fill(0x42)
            }
        }
        val dir = createTempDirectory(prefix = "vaultstore-rename-fail-test").toFile()
        dir.deleteOnExit()
        val store = VaultStore(dir, "alias", FakeHardwareKeyWrapFactory(), random = deterministicRandom)
        store.create("1234".toCharArray(), fastParams)

        val predictedFileId = ByteArray(16) { 0x42 }
        store.contentFile(predictedFileId).mkdirs() // occupy the target path so renameTo must fail

        assertThrows<IllegalStateException> {
            store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "title", null, "pdf")
        }
        assertTrue(store.listEntries().isEmpty(), "a failed rename must not leave a manifest entry behind")
    }

    /**
     * [setCoverArt] has the identical crash-safety structure as [importFile]
     * above (encrypt-to-temp, rename, THEN update the manifest — old cover
     * art is only deleted once the manifest points at the new one). Forces
     * the rename of a NEW cover art into place to fail and asserts the
     * manifest still points at the OLD cover art, and that the old cover art
     * file itself was never deleted.
     */
    @Test
    fun `a failed rename during setCoverArt leaves the manifest and old cover art untouched`() = runTest {
        // Same technique as the import regression test above, but each call
        // needs a distinct predictable id — the imported file's own id, the
        // first (successful) cover art's id, and the second
        // (deliberately-failed) cover art's id — so this test isn't
        // accidentally exercising a path collision instead of the rename
        // failure it's targeting.
        var callCount = 0
        val deterministicRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                callCount++
                bytes.fill((0x40 + callCount).toByte())
            }
        }
        val dir = createTempDirectory(prefix = "vaultstore-setcoverart-rename-fail-test").toFile()
        dir.deleteOnExit()
        val store = VaultStore(dir, "alias", FakeHardwareKeyWrapFactory(), random = deterministicRandom)
        store.create("1234".toCharArray(), fastParams)

        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "title", null, "pdf")
        val originalCover = ByteArray(10) { 1 }
        store.setCoverArt(entry.fileId, originalCover)
        val originalCoverFileId = store.listEntries().single().coverArtFileId!!

        val predictedNewCoverFileId = ByteArray(16) { (0x40 + 3).toByte() }
        store.contentFile(predictedNewCoverFileId).mkdirs() // occupy the target path so renameTo must fail

        assertThrows<IllegalStateException> {
            store.setCoverArt(entry.fileId, ByteArray(10) { 2 })
        }

        val entryAfter = store.listEntries().single()
        assertArrayEquals(
            originalCoverFileId,
            entryAfter.coverArtFileId,
            "a failed rename must leave the manifest pointing at the old cover art",
        )
        assertTrue(
            store.contentFile(originalCoverFileId).exists(),
            "a failed rename must not delete the old cover art file",
        )
        assertArrayEquals(originalCover, store.readCoverArt(entry.fileId))
    }
}
