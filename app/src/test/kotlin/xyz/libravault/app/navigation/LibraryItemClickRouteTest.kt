package xyz.libravault.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Unit coverage for [libraryItemClickRoute] — the click-through routing
 * decision behind `LibravaultNavHost`'s `onItemClick` (Phase 3, #508).
 * Flagged by QA on PR #693 as untested inline `when` logic; extracted per
 * `AGENTS.md`'s guidance, same as [ScreenRouteTest]'s own reason for
 * existing — a route decision is exactly the kind of "what happens" logic
 * that must not be left unverified inside a composable body.
 */
class LibraryItemClickRouteTest {

    private fun item(id: Long, filePath: String, format: MediaFormat) = LibraryItem(
        id = id,
        vaultFolderId = 1,
        filePath = filePath,
        title = "Item $id",
        author = "Author",
        format = format,
    )

    // ── Real, Room-backed items ──────────────────────────────────────────────

    @Test
    fun `a real audio item routes to Player by Room id`() {
        val route = libraryItemClickRoute(item(42, "content://tree/books/x.mp3", MediaFormat.MP3))
        assertEquals(Screen.Player.createRoute(42), route)
    }

    @Test
    fun `a real book item routes to Reader by Room id`() {
        val route = libraryItemClickRoute(item(7, "content://tree/books/x.epub", MediaFormat.EPUB))
        assertEquals(Screen.Reader.createRoute(7), route)
    }

    // ── Vault-sourced items (Phase 3, #508) ──────────────────────────────────

    @Test
    fun `a vault audio item routes to VaultPlay with the parsed vaultId and fileId`() {
        val route = libraryItemClickRoute(item(-99, "vault://vault-1/aabbcc", MediaFormat.MP3))
        assertEquals(Screen.VaultPlay.createRoute("vault-1", "aabbcc"), route)
    }

    @Test
    fun `a vault book item routes to VaultRead with the parsed vaultId and fileId`() {
        val route = libraryItemClickRoute(item(-98, "vault://vault-1/ddeeff", MediaFormat.EPUB))
        assertEquals(Screen.VaultRead.createRoute("vault-1", "ddeeff"), route)
    }

    @Test
    fun `vault routing never uses the synthetic item id`() {
        // A vault item's id is a negative, non-Room sentinel (LibraryViewModel's
        // vaultLibraryItemId) — Screen.Reader/Player would resolve nothing for it,
        // so the route must never fall through to them for a vault filePath.
        val route = libraryItemClickRoute(item(-12345, "vault://vault-2/112233", MediaFormat.PDF))
        assertEquals(Screen.VaultRead.createRoute("vault-2", "112233"), route)
    }

    // ── Malformed input degrades instead of crashing ─────────────────────────

    @Test
    fun `a vault-scheme filePath missing the file id segment falls back to real-item routing`() {
        // "vault://vault-1" with no trailing "/fileId" — substringAfter('/', "")
        // yields an empty fileIdHex, so this must not produce a VaultRead/VaultPlay
        // route with a blank segment.
        val route = libraryItemClickRoute(item(5, "vault://vault-1", MediaFormat.EPUB))
        assertEquals(Screen.Reader.createRoute(5), route)
    }

    @Test
    fun `an empty filePath falls back to real-item routing`() {
        val route = libraryItemClickRoute(item(3, "", MediaFormat.MP3))
        assertEquals(Screen.Player.createRoute(3), route)
    }
}
