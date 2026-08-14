package xyz.libravault.core.vaultstore

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Regression test for implementation plan §A.6/Phase 4's third leak
 * (`LibraryItemEntity.title`/`author` in plaintext Room): it was already
 * closed by construction (Phase 2's [VaultStore.importFile] has always
 * written straight to [VaultManifest], never to Room), but "never touches it
 * today" is a fact about the current call graph, not a guarantee — the risk
 * is a future change adding `core:database` or `core:storage` as a
 * dependency here "for convenience" and silently reopening the leak.
 *
 * Asserting Room and `CoverArtCache` are unreachable on this module's own
 * classpath is a cheap, permanent guard: if this module's `build.gradle.kts`
 * ever gains a dependency on `core:database`/`core:storage`, those classes
 * become loadable and this test starts failing — catching exactly the
 * regression this test exists to prevent, not a hypothetical one.
 */
class VaultStoreHasNoLeakSurfaceDependencyTest {

    @Test
    fun `Room is not on core-vaultstore's classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("androidx.room.RoomDatabase")
        }
    }

    @Test
    fun `core-database's LibraryItemEntity is not on core-vaultstore's classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("xyz.libravault.core.database.entity.LibraryItemEntity")
        }
    }

    @Test
    fun `core-storage's CoverArtCache is not on core-vaultstore's classpath`() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("xyz.libravault.core.storage.CoverArtCache")
        }
    }
}
