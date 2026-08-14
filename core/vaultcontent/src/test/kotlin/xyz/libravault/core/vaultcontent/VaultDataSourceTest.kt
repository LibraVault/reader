@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.core.vaultcontent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom

/**
 * Runs on Robolectric (JVM, no emulator) — needed here specifically because
 * `DataSpec` requires a real `android.net.Uri`, which throws "not mocked"
 * under AGP's default stub android.jar. Same setup as core:ui's
 * `LibravaultThemeTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultDataSourceTest {

    private fun spec(position: Long = 0, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(Uri.parse("vault://test")).setPosition(position).setLength(length).build()

    @Test
    fun `open reports the remaining byte count from the requested position`() {
        val plain = ByteArray(1000)
        val vault = TestVaultFile.encrypt(plain)
        val source = VaultDataSource(vault.openReader())

        val reported = source.open(spec(position = 100))
        assertEquals(900L, reported)
        source.close()
    }

    @Test
    fun `read returns exactly the requested bytes in order`() {
        val plain = ByteArray(500).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain)
        val source = VaultDataSource(vault.openReader())
        source.open(spec())

        val buffer = ByteArray(50)
        var total = 0
        while (total < 50) {
            val n = source.read(buffer, total, 50 - total)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        assertArrayEquals(plain.copyOfRange(0, 50), buffer)
        source.close()
    }

    @Test
    fun `read returns RESULT_END_OF_INPUT at the end of the declared range`() {
        val plain = ByteArray(100)
        val vault = TestVaultFile.encrypt(plain)
        val source = VaultDataSource(vault.openReader())
        source.open(spec(position = 0, length = 10))

        val buffer = ByteArray(10)
        var total = 0
        while (total < 10) total += source.read(buffer, total, 10 - total)

        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
        source.close()
    }

    @Test
    fun `opening past the end of the file throws DataSourceException`() {
        val plain = ByteArray(10)
        val vault = TestVaultFile.encrypt(plain)
        val source = VaultDataSource(vault.openReader())

        assertThrows(DataSourceException::class.java) { source.open(spec(position = 100)) }
    }

    @Test
    fun `getUri reflects the DataSpec passed to open`() {
        val vault = TestVaultFile.encrypt(ByteArray(10))
        val source = VaultDataSource(vault.openReader())
        source.open(spec())
        assertEquals(Uri.parse("vault://test"), source.uri)
        source.close()
    }

    @Test
    fun `Factory produces a fresh reader per DataSource via the provider lambda`() {
        val vault = TestVaultFile.encrypt(ByteArray(10))
        var callCount = 0
        val factory = VaultDataSource.Factory {
            callCount++
            vault.openReader()
        }

        factory.createDataSource()
        factory.createDataSource()
        assertEquals(2, callCount)
    }
}
