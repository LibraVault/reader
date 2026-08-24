@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.feature.vault

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultstore.VaultStore

/**
 * Regression guard for issue #527: `VaultPlayerViewModel` used to open one
 * `VaultFileReader` and hand that same instance to every `VaultDataSource`
 * Media3 created, racing `raf.seek`/the chunk cache across instances, or
 * reading from a reader another instance had already closed.
 * [vaultPlayerDataSourceFactory] is the extracted fan-out; this asserts each
 * `createDataSource()` call opens its own fresh reader via
 * `VaultStore.openReader` — the pre-fix code would have called `openReader`
 * once total, up front, and handed that single instance to both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultPlayerDataSourceFactoryTest {

    @Test
    fun `each createDataSource call opens its own fresh reader from the store`() {
        val store = mockk<VaultStore>()
        val fileId = ByteArray(16) { it.toByte() }
        every { store.openReader(fileId) } answers { mockk<VaultFileReader>(relaxed = true) }

        val factory = vaultPlayerDataSourceFactory(store, fileId)
        factory.createDataSource()
        factory.createDataSource()
        factory.createDataSource()

        verify(exactly = 3) { store.openReader(fileId) }
    }
}
