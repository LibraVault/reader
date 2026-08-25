package xyz.libravault.feature.reader.epub

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import xyz.libravault.core.vaultcrypto.ChunkedVaultWriter
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultcrypto.VaultFormat
import java.io.File
import java.security.SecureRandom

/**
 * Vault-native counterpart to [ReadiumIntegrationTest] — same real Readium
 * `AssetRetriever` -> `EpubParser` -> `PublicationOpener` pipeline, but through
 * [ReadiumProvider.openVaultFile] (#505) instead of [ReadiumProvider.open]:
 * a genuinely AES-256-GCM-encrypted copy of the same `demo.epub` asset,
 * decrypted through the real (unmocked) [VaultFileReader] -> `VaultReadiumResource`
 * chain, exercising Readium's real ZIP/XML parsers against it — not attempted
 * in a plain JVM test for the same reason [ReadiumIntegrationTest] isn't.
 *
 * Security-audit checklist item (#505 issue, PRD §5's "same rigor as PR #166's
 * CoverArtCache audit"): the only file this test ever writes is the ciphertext
 * itself (in the app's private [android.content.Context.filesDir], asserted
 * marker-free below) — `ReadiumProvider.openVaultFile`'s call chain
 * (`VaultReadiumResource.read()` -> `VaultFileReader.readAt`) never touches a
 * `File`/`ContentResolver` API, so there is no second file it could plausibly
 * have leaked decrypted bytes into.
 */
@RunWith(AndroidJUnit4::class)
class ReadiumVaultIntegrationTest {

    private lateinit var readiumProvider: ReadiumProvider
    private lateinit var encryptedFile: File
    private lateinit var vmk: ByteArray
    private lateinit var fileId: ByteArray

    @Before
    fun setup() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        readiumProvider = ReadiumProvider(appContext)

        val testContext = InstrumentationRegistry.getInstrumentation().context
        val plaintext = testContext.assets.open("demo.epub").use { it.readBytes() }

        encryptedFile = File(appContext.filesDir, "test_vault_demo.epub.enc")
        vmk = ByteArray(32).also { SecureRandom().nextBytes(it) }
        fileId = ByteArray(VaultFormat.FILE_ID_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        encryptedFile.outputStream().use { out ->
            ChunkedVaultWriter.encrypt(
                vmk = vmk,
                fileId = fileId,
                totalPlaintextLength = plaintext.size.toLong(),
                input = plaintext.inputStream(),
                output = out,
            )
        }

        // The "Libravault Test EPUB" title string (asserted via the real open
        // below) must not appear in the ciphertext on disk — otherwise the
        // no-leak assertion in the test itself would be vacuous.
        assertFalse(
            "ciphertext must not contain the plaintext title before it's ever decrypted",
            encryptedFile.readBytes().toString(Charsets.ISO_8859_1)
                .contains("Libravault Test EPUB"),
        )
    }

    @Test
    fun openVaultFile_decryptsRealCiphertextAndParsesWithReadium(): Unit = runBlocking {
        val ciphertextBefore = encryptedFile.readBytes()

        val reader = VaultFileReader(encryptedFile, vmk, fileId)
        val result = readiumProvider.openVaultFile(reader, fileId.joinToString("") { "%02x".format(it) })

        assertTrue("Expected success but got $result", result.isSuccess)
        val publication = result.getOrThrow()
        assertEquals("Libravault Test EPUB", publication.metadata.title)

        // No plaintext leak: the only file on disk related to this test is the
        // ciphertext written in setup() — confirm the open+parse above left it
        // byte-for-byte unchanged (nothing rewrote it, e.g. in plaintext) and
        // still contains no plaintext title.
        assertArrayEquals(
            "the ciphertext file on disk must be untouched by a decrypt+parse — VaultFileReader is read-only",
            ciphertextBefore,
            encryptedFile.readBytes(),
        )
        assertFalse(
            "ciphertext on disk must still not contain the plaintext title after a real decrypt+parse",
            encryptedFile.readBytes().toString(Charsets.ISO_8859_1).contains("Libravault Test EPUB"),
        )

        publication.close()
        reader.close()
    }
}
