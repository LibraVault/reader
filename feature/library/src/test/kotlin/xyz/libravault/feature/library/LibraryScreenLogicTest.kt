package xyz.libravault.feature.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.VaultFolder

/**
 * Covers the decision logic extracted from [LibraryScreen] — the repo's largest
 * file (1,344 lines) and, at 8.7%, its biggest coverage gap
 * (docs/TEST_COVERAGE_PRD.md, S2).
 *
 * The screen decides what to show through an ordered chain of interlocking
 * conditions. Inline in a ~500-line composable that chain was untestable, and
 * its failure modes are quiet ones: two sections rendering at once, or a
 * section that silently never renders for some combination of search + vault +
 * format filter. Those are the cases below.
 */
class LibraryScreenLogicTest {

    private fun item(id: Long, format: MediaFormat) = LibraryItem(
        id = id,
        vaultFolderId = 1,
        filePath = "/books/$id",
        title = "Item $id",
        author = "Author",
        format = format,
    )

    private fun vault(id: Long) = VaultFolder(id = id, uri = "content://v$id", displayName = "Vault $id")

    private val book = item(1, MediaFormat.EPUB)
    private val audio = item(2, MediaFormat.MP3)

    /** A state that renders the ordinary grid, for tests to vary one axis of. */
    private fun populated(
        vaults: List<VaultFolder> = listOf(vault(1)),
        allItems: List<LibraryItem> = listOf(book, audio),
        searchResults: List<LibraryItem>? = null,
        selectedVault: VaultFolder? = null,
        formatFilter: String? = null,
        continueItems: List<LibraryItem> = emptyList(),
        vaultGroupedItems: Map<VaultFolder, List<LibraryItem>> = emptyMap(),
        isScanning: Boolean = false,
    ) = LibraryUiState(
        vaults = vaults,
        allItems = allItems,
        searchResults = searchResults,
        selectedVault = selectedVault,
        formatFilter = formatFilter,
        continueItems = continueItems,
        vaultGroupedItems = vaultGroupedItems,
        isScanning = isScanning,
    )

    // ── Empty library ─────────────────────────────────────────────────────────

    @Test
    fun `no items and not scanning shows the empty library`() {
        val layout = libraryLayoutFor(populated(allItems = emptyList()))
        assertEquals(LibraryContent.EmptyLibrary, layout.content)
    }

    /**
     * The empty state replaces the whole grid, so nothing else may claim to be
     * visible alongside it. Getting this wrong would render filter chips above
     * an "add your first vault" prompt.
     */
    @Test
    fun `empty library suppresses continue row and both chip rows`() {
        val layout = libraryLayoutFor(
            populated(
                allItems = emptyList(),
                vaults = listOf(vault(1), vault(2)),
                continueItems = listOf(book),
            ),
        )
        assertFalse(layout.showContinue, "no continue row on the empty state")
        assertFalse(layout.showVaultChips, "no vault chips on the empty state")
        assertFalse(layout.showFormatChips, "no format chips on the empty state")
    }

    /**
     * A scan in progress is not the same as "you have no books" — showing the
     * empty state mid-scan makes a first launch flash an incorrect prompt.
     */
    @Test
    fun `no items while scanning does not show the empty library`() {
        val layout = libraryLayoutFor(populated(allItems = emptyList(), isScanning = true))
        assertEquals(LibraryContent.AllGrouped, layout.content)
    }

    // ── Content precedence ────────────────────────────────────────────────────

    @Test
    fun `search results win over a selected vault and a format filter`() {
        val layout = libraryLayoutFor(
            populated(
                searchResults = listOf(book),
                selectedVault = vault(1),
                formatFilter = MediaFormat.PDF.name,
            ),
        )
        assertEquals(
            LibraryContent.SearchResults,
            layout.content,
            "search mode must take precedence over every other filter",
        )
    }

    @Test
    fun `a selected vault wins over a format filter`() {
        val layout = libraryLayoutFor(
            populated(selectedVault = vault(1), formatFilter = MediaFormat.EPUB.name),
        )
        assertEquals(LibraryContent.SingleVault, layout.content)
    }

    @Test
    fun `no format filter shows the grouped books and audiobooks view`() {
        assertEquals(LibraryContent.AllGrouped, libraryLayoutFor(populated()).content)
    }

    @Test
    fun `a format filter matching nothing shows the filtered empty state`() {
        val layout = libraryLayoutFor(
            populated(
                formatFilter = MediaFormat.PDF.name,
                vaultGroupedItems = mapOf(vault(1) to emptyList()),
            ),
        )
        assertEquals(LibraryContent.FilteredEmpty, layout.content)
    }

    @Test
    fun `a format filter matching something shows per-vault sections`() {
        val layout = libraryLayoutFor(
            populated(
                formatFilter = MediaFormat.EPUB.name,
                vaultGroupedItems = mapOf(vault(1) to listOf(book)),
            ),
        )
        assertEquals(LibraryContent.VaultSections, layout.content)
    }

    /**
     * A format filter with no vaults at all must not fall through to
     * "per-vault sections", which would render nothing and look like a hang.
     * `all {}` on an empty map is vacuously true, so this pins that the
     * vacuous case lands on the empty state deliberately.
     */
    @Test
    fun `a format filter with no vault groups shows the filtered empty state`() {
        val layout = libraryLayoutFor(
            populated(formatFilter = MediaFormat.PDF.name, vaultGroupedItems = emptyMap()),
        )
        assertEquals(LibraryContent.FilteredEmpty, layout.content)
    }

    // ── Chip visibility ───────────────────────────────────────────────────────

    @Test
    fun `vault chips need more than one vault`() {
        assertFalse(libraryLayoutFor(populated(vaults = listOf(vault(1)))).showVaultChips)
        assertTrue(libraryLayoutFor(populated(vaults = listOf(vault(1), vault(2)))).showVaultChips)
    }

    @Test
    fun `vault chips hide once a vault is selected`() {
        val layout = libraryLayoutFor(
            populated(vaults = listOf(vault(1), vault(2)), selectedVault = vault(1)),
        )
        assertFalse(layout.showVaultChips, "already inside a vault — the chips would be redundant")
    }

    /**
     * The search overlay renders its own format chips. If the grid also showed
     * a set, the user would see two chip rows, one of them behind the overlay
     * and out of sync.
     */
    @Test
    fun `both chip rows hide during search`() {
        val layout = libraryLayoutFor(
            populated(vaults = listOf(vault(1), vault(2)), searchResults = listOf(book)),
        )
        assertFalse(layout.showVaultChips)
        assertFalse(layout.showFormatChips)
    }

    @Test
    fun `format chips show outside search`() {
        assertTrue(libraryLayoutFor(populated()).showFormatChips)
    }

    @Test
    fun `continue row shows only when there is something to continue`() {
        assertFalse(libraryLayoutFor(populated(continueItems = emptyList())).showContinue)
        assertTrue(libraryLayoutFor(populated(continueItems = listOf(book))).showContinue)
    }

    // ── partitionByMedium ─────────────────────────────────────────────────────

    @Test
    fun `partition splits books from audiobooks`() {
        val (books, audiobooks) = partitionByMedium(listOf(book, audio))
        assertEquals(listOf(book), books)
        assertEquals(listOf(audio), audiobooks)
    }

    /**
     * Every format must land in exactly one bucket. The original code wrote the
     * predicate and its negation separately at two call sites, which is how a
     * newly-added format ends up in both sections or in neither.
     */
    @Test
    fun `every media format lands in exactly one bucket`() {
        val all = MediaFormat.entries.mapIndexed { i, f -> item(i.toLong(), f) }
        val (books, audiobooks) = partitionByMedium(all)
        assertEquals(all.size, books.size + audiobooks.size, "no item may be dropped or duplicated")
        assertTrue(books.none { it.format.isAudio() }, "no audio in the books bucket")
        assertTrue(audiobooks.all { it.format.isAudio() }, "no books in the audio bucket")
    }

    @Test
    fun `partition preserves order within each bucket`() {
        val items = listOf(item(1, MediaFormat.EPUB), item(2, MediaFormat.MP3), item(3, MediaFormat.PDF))
        val (books, _) = partitionByMedium(items)
        assertEquals(listOf(1L, 3L), books.map { it.id })
    }

    @Test
    fun `partition of an empty library yields two empty buckets`() {
        val (books, audiobooks) = partitionByMedium(emptyList())
        assertTrue(books.isEmpty() && audiobooks.isEmpty())
    }

    // ── vaultDisplayNameFrom ──────────────────────────────────────────────────

    @Test
    fun `strips the SAF authority prefix`() {
        assertEquals("Books", vaultDisplayNameFrom("primary:Books"))
    }

    @Test
    fun `uses the leaf folder of a nested SAF path`() {
        assertEquals("Fiction", vaultDisplayNameFrom("primary:Books/Fiction"))
    }

    @Test
    fun `handles a segment with no authority prefix`() {
        assertEquals("Books", vaultDisplayNameFrom("Books"))
    }

    @Test
    fun `falls back when there is no last path segment`() {
        assertEquals(DEFAULT_VAULT_NAME, vaultDisplayNameFrom(null))
    }

    /**
     * `primary:` produces an empty string once the prefix is stripped. The
     * original inline version only guarded against a null segment, so this case
     * gave the vault an empty display name in the UI. Fixed as part of the
     * extraction — this is the regression test for it.
     */
    @Test
    fun `falls back when stripping leaves nothing`() {
        assertEquals(DEFAULT_VAULT_NAME, vaultDisplayNameFrom("primary:"))
        assertEquals(DEFAULT_VAULT_NAME, vaultDisplayNameFrom(""))
        assertEquals(DEFAULT_VAULT_NAME, vaultDisplayNameFrom("   "))
        assertEquals(DEFAULT_VAULT_NAME, vaultDisplayNameFrom("primary:Books/"))
    }
}
