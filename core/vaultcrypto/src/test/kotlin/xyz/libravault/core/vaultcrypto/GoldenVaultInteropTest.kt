package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pins the on-disk vault format against a frozen artifact shared with iOS.
 *
 * Android (`core:vaultcrypto`) and iOS (`ios/.../Sources/VaultCrypto`) are two
 * independent implementations of the same format. Every other test in this
 * module checks Android against itself: encrypt, decrypt, assert you got back
 * what you put in. A round-trip cannot detect the failure that actually
 * matters here — both platforms drifting, or one being self-consistently
 * wrong — because a wrong-but-deterministic implementation round-trips
 * perfectly and still cannot open the other platform's vaults.
 *
 * The fixture makes that detectable. Because the format is fully
 * deterministic — nonces are derived, not random, and there is no per-file
 * random IV or salt in the blob — encrypting fixed inputs must produce
 * *byte-identical* output on every platform, forever, for format version 1. So
 * this asserts equality against the bytes, not merely that a round-trip works.
 *
 * That single assertion transitively pins a lot: HKDF (`Hkdf`,
 * [deriveFileContentKey]) has no known-answer test of its own, but any change
 * to it changes the file content key, which changes every chunk's ciphertext,
 * which fails here. Same for the AAD layout, the header layout, the chunk
 * count rule, and the final-chunk flag.
 *
 * `ios/.../LibraVaultTests/GoldenVaultInteropTests.swift` asserts the same
 * thing against the same file. Keep the two in sync.
 *
 * ## If this test fails
 *
 * You changed the wire format. That is not automatically wrong, but it is
 * never incidental:
 *  - If deliberate, bump [VaultFormat.FORMAT_VERSION], add a *new* fixture
 *    beside this one, and keep the v1 fixture and its test so vaults already
 *    on users' devices stay readable.
 *  - If not deliberate, you have just broken every existing vault. Do not
 *    regenerate the fixture to make the test pass.
 *
 * Regeneration is possible but deliberately awkward, and lands in review as a
 * changed binary:
 *
 *     VAULTCRYPTO_REGENERATE_GOLDEN=true \
 *       ./gradlew :core:vaultcrypto:testDebugUnitTest --tests '*GoldenVaultInteropTest*'
 *
 * Two details that are easy to get wrong, both verified the hard way:
 *  - `testDebugUnitTest`, not `test`. On an Android module `test` is a
 *    lifecycle task and rejects `--tests` outright.
 *  - An environment variable, not `-D`. Gradle forks a separate JVM for tests
 *    and does not forward system properties to it, but does inherit the
 *    environment — a `-D` flag here silently does nothing.
 */
class GoldenVaultInteropTest {

    companion object {
        /**
         * Fixed test inputs. Not secrets and never used outside tests — the
         * whole point is that they are public, boring, and identical in the
         * Swift test.
         */
        private val VMK = ByteArray(32) { it.toByte() }
        private val FILE_ID = ByteArray(16) { (0xA0 + it).toByte() }

        /**
         * Deliberately tiny so a 150-byte payload spans three chunks
         * (64 + 64 + 22). That exercises multi-chunk nonce derivation, the
         * chunk-count rule, and a partial final chunk — none of which a
         * single-chunk fixture would touch.
         */
        private const val CHUNK_SIZE = 64

        /**
         * Defined by formula rather than shipped as a second file, so the
         * Swift test reproduces it exactly with no encoding questions:
         *     plaintext[i] = (i * 7 + 11) % 251
         */
        private val PLAINTEXT = ByteArray(150) { i -> ((i * 7 + 11) % 251).toByte() }

        private const val REGENERATE_ENV = "VAULTCRYPTO_REGENERATE_GOLDEN"
        private const val FIXTURE_RELATIVE_PATH = "testdata/vault-format/v1/golden.vault"

        /**
         * Gradle runs tests with the working directory set to the module dir
         * (`core/vaultcrypto`), so the repo root is two levels up. Resolved and
         * asserted rather than hardcoded, so a future Gradle change fails with
         * a clear message instead of a confusing "file not found".
         */
        private fun repoRoot(): File {
            val root = File("").absoluteFile.parentFile.parentFile
            assertTrue(
                File(root, "settings.gradle.kts").isFile,
                "Expected the repo root at $root (working dir was ${File("").absolutePath}). " +
                    "If Gradle's test working directory changed, fix repoRoot().",
            )
            return root
        }

        private fun fixturePath(): File = File(repoRoot(), FIXTURE_RELATIVE_PATH)

        private fun encodeGolden(): ByteArray {
            val out = ByteArrayOutputStream()
            ChunkedVaultWriter.encrypt(
                vmk = VMK,
                fileId = FILE_ID,
                totalPlaintextLength = PLAINTEXT.size.toLong(),
                input = ByteArrayInputStream(PLAINTEXT),
                output = out,
                chunkSize = CHUNK_SIZE,
            )
            return out.toByteArray()
        }

        /**
         * Regeneration runs once, before any test, rather than inside the
         * writer test.
         *
         * JUnit gives no ordering guarantee between test methods, so
         * regenerating inside one of them let the reader tests run first and
         * fail against a fixture that did not exist yet — a regeneration run
         * came out red despite having produced a correct file. Doing it here
         * keeps every per-test assertion strict (no skipping, see
         * [existingFixture]) while still letting a deliberate regeneration
         * produce a green run.
         */
        @JvmStatic
        @BeforeAll
        fun regenerateIfRequested() {
            if (System.getenv(REGENERATE_ENV) != "true") return
            val produced = encodeGolden()
            val fixture = fixturePath()
            fixture.parentFile.mkdirs()
            fixture.writeBytes(produced)
            println("Regenerated ${fixture.absolutePath} (${produced.size} bytes)")
        }
    }

    /**
     * The fixture, asserted to exist.
     *
     * Deliberately fails rather than skipping. These tests originally used
     * `Assumptions.assumeTrue`, which meant a missing fixture turned the whole
     * interop suite green-by-skipping — indistinguishable on a CI dashboard
     * from it having actually verified something. That is precisely the failure
     * mode this class exists to prevent, so it must not be reintroduced by the
     * class's own scaffolding. The Swift side asserts for the same reason.
     *
     * The fixture is committed; if it is absent, the checkout is broken and
     * that should be red.
     */
    private fun existingFixture(): File {
        val fixture = fixturePath()
        assertTrue(
            fixture.isFile,
            "Golden fixture missing at ${fixture.absolutePath}. It is committed to the repo — " +
                "see testdata/vault-format/README.md before regenerating it.",
        )
        return fixture
    }

    // ── Writer side ───────────────────────────────────────────────────────────

    @Test
    fun `writer reproduces the golden vault byte for byte`() {
        assertArrayEquals(
            existingFixture().readBytes(),
            encodeGolden(),
            "Encrypting the fixed test inputs no longer reproduces the committed v1 fixture. " +
                "The on-disk format changed — see this class's doc comment before touching the fixture.",
        )
    }

    // ── Reader side ───────────────────────────────────────────────────────────

    @Test
    fun `reader recovers the exact plaintext from the golden vault`() {
        VaultFileReader(existingFixture(), VMK, FILE_ID).use { reader ->
            assertEquals(PLAINTEXT.size.toLong(), reader.plainSize, "plaintext length")
            assertEquals(CHUNK_SIZE, reader.chunkSize, "chunk size")
            assertArrayEquals(FILE_ID, reader.fileId, "file id")
            assertArrayEquals(
                PLAINTEXT,
                reader.readAt(0, PLAINTEXT.size),
                "Decrypting the committed v1 fixture did not return the expected plaintext.",
            )
        }
    }

    /**
     * Reads across a chunk boundary specifically. A reader that mishandled
     * partial chunks could still pass a whole-file read from offset 0 by
     * concatenating correctly.
     */
    @Test
    fun `reader returns correct bytes for a read spanning chunk boundaries`() {
        VaultFileReader(existingFixture(), VMK, FILE_ID).use { reader ->
            // 50..109 straddles the 64-byte boundary between chunk 0 and 1.
            assertArrayEquals(
                PLAINTEXT.copyOfRange(50, 110),
                reader.readAt(50, 60),
                "cross-boundary read",
            )
            // 130..149 lands inside the 22-byte final chunk.
            assertArrayEquals(
                PLAINTEXT.copyOfRange(130, 150),
                reader.readAt(130, 20),
                "final short chunk read",
            )
        }
    }

    // ── Header layout ─────────────────────────────────────────────────────────

    /**
     * Asserts the frozen v1 header bytes explicitly, so a header-layout change
     * produces a precise failure here rather than only an opaque whole-file
     * mismatch in the writer test above.
     */
    @Test
    fun `golden vault header matches the frozen v1 layout`() {
        val bytes = existingFixture().readBytes()

        assertEquals(VaultFormat.FORMAT_VERSION, bytes[0], "byte 0 = format version")
        assertEquals(VaultFormat.CIPHER_AES_256_GCM, bytes[1], "byte 1 = cipher id")
        assertArrayEquals(FILE_ID, bytes.copyOfRange(2, 18), "bytes 2..17 = file id")

        val buf = java.nio.ByteBuffer.wrap(bytes, 18, 12)
        assertEquals(CHUNK_SIZE, buf.int, "bytes 18..21 = chunk size (big-endian)")
        assertEquals(PLAINTEXT.size.toLong(), buf.long, "bytes 22..29 = total plaintext length")

        // 3 chunks: two full (64) + one partial (22), each carrying a 16-byte tag.
        assertEquals(
            VaultFormat.HEADER_SIZE_BYTES + PLAINTEXT.size + 3 * VaultFormat.TAG_SIZE_BYTES,
            bytes.size,
            "total file size",
        )
    }
}
