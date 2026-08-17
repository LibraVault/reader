package xyz.libravault.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `LibravaultNavHost.kt` was 247 lines at 0% coverage
 * (docs/TEST_COVERAGE_PRD.md, S2). The `@Composable` graph itself needs the
 * full Hilt/Compose stack to exercise, but the [Screen] route table is pure
 * string logic and carries the part that actually breaks: a route that does not
 * match its own pattern, or two patterns that can match the same path.
 *
 * The file already documents a non-collision property in a comment —
 * `"vault/new"` uses a deliberately different top segment from
 * `"vaults/{vaultId}"` "so the two routes can never structurally collide
 * regardless of how Navigation-Compose breaks matching ties". That claim was
 * unverified. These tests check it, and check it stays true for every other
 * pair as routes are added.
 *
 * ## On the matcher
 *
 * [matches] is a deliberately simple model of Navigation-Compose's matching:
 * split on `/`, same segment count, and a `{placeholder}` segment matches any
 * single non-empty segment. It is **not** Navigation's real matcher, and it is
 * not trying to be — using the real one would mean standing up a NavHost with
 * every destination's dependencies. What it does model faithfully is segment
 * arity and literal-vs-placeholder shape, which is the class of collision the
 * comment is worried about and the class a human reviewer cannot reliably
 * eyeball across 13 routes.
 */
class ScreenRouteTest {

    /** Every declared destination, so new routes are covered as they are added. */
    private val allScreens: List<Screen> = listOf(
        Screen.Onboarding, Screen.Library, Screen.Settings,
        Screen.VaultList, Screen.CreateVault, Screen.UnlockVault,
        Screen.VaultContents, Screen.VaultRead, Screen.VaultPlay,
        Screen.Reader, Screen.Player,
        Screen.ExternalReader, Screen.ExternalPlayer,
    )

    /** Strips a query suffix — Navigation treats `?a={a}` as optional arguments. */
    private fun path(route: String) = route.substringBefore('?')

    private fun matches(pattern: String, concrete: String): Boolean {
        val p = path(pattern).split('/')
        val c = path(concrete).split('/')
        if (p.size != c.size) return false
        return p.zip(c).all { (pat, seg) ->
            if (pat.startsWith("{") && pat.endsWith("}")) seg.isNotEmpty() else pat == seg
        }
    }

    // ── Routes match their own patterns ───────────────────────────────────────

    @Test
    fun `every createRoute output matches its own declared pattern`() {
        val cases = listOf(
            Screen.UnlockVault.route to Screen.UnlockVault.createRoute("v1"),
            Screen.VaultContents.route to Screen.VaultContents.createRoute("v1"),
            Screen.VaultRead.route to Screen.VaultRead.createRoute("v1", "f1"),
            Screen.VaultPlay.route to Screen.VaultPlay.createRoute("v1", "f1"),
            Screen.Reader.route to Screen.Reader.createRoute(42L),
            Screen.Player.route to Screen.Player.createRoute(42L),
            Screen.Player.route to Screen.Player.createRouteWithSeek(42L, 1000L),
            Screen.ExternalReader.route to Screen.ExternalReader.createRoute("abc"),
            Screen.ExternalPlayer.route to Screen.ExternalPlayer.createRoute("abc"),
        )
        cases.forEach { (pattern, concrete) ->
            assertTrue(matches(pattern, concrete), "'$concrete' should match pattern '$pattern'")
        }
    }

    @Test
    fun `player seek variant carries the argument as a query parameter`() {
        assertEquals("player/42?seekMs=1000", Screen.Player.createRouteWithSeek(42L, 1000L))
        // Without seek it must NOT emit an empty query, which would not match.
        assertEquals("player/42", Screen.Player.createRoute(42L))
    }

    // ── The documented non-collision property ─────────────────────────────────

    /**
     * The specific claim in the source comment: `vault/new` must not be
     * matchable by `vaults/{vaultId}`, in either direction.
     */
    @Test
    fun `create-vault route cannot be confused with a vault id`() {
        assertFalse(
            matches(Screen.VaultContents.route, Screen.CreateVault.route),
            "'vault/new' must not match 'vaults/{vaultId}' — that is why it uses a different top segment",
        )
        assertFalse(matches(Screen.CreateVault.route, Screen.VaultContents.createRoute("new")))
    }

    /**
     * Generalises the same property across the whole table: no concrete route
     * produced for one destination may match a different destination's pattern.
     * This is what stops a future route addition silently shadowing an existing
     * screen.
     */
    @Test
    fun `no concrete route matches a different destination's pattern`() {
        val concretes: List<Pair<Screen, String>> = listOf(
            Screen.Onboarding to Screen.Onboarding.route,
            Screen.Library to Screen.Library.route,
            Screen.Settings to Screen.Settings.route,
            Screen.VaultList to Screen.VaultList.route,
            Screen.CreateVault to Screen.CreateVault.route,
            Screen.UnlockVault to Screen.UnlockVault.createRoute("v1"),
            Screen.VaultContents to Screen.VaultContents.createRoute("v1"),
            Screen.VaultRead to Screen.VaultRead.createRoute("v1", "f1"),
            Screen.VaultPlay to Screen.VaultPlay.createRoute("v1", "f1"),
            Screen.Reader to Screen.Reader.createRoute(42L),
            Screen.Player to Screen.Player.createRoute(42L),
            Screen.ExternalReader to Screen.ExternalReader.createRoute("abc"),
            Screen.ExternalPlayer to Screen.ExternalPlayer.createRoute("abc"),
        )

        concretes.forEach { (owner, concrete) ->
            allScreens.filter { it !== owner }.forEach { other ->
                assertFalse(
                    matches(other.route, concrete),
                    "route '$concrete' (${owner::class.simpleName}) also matches " +
                        "${other::class.simpleName}'s pattern '${other.route}' — " +
                        "two destinations can be reached by one path",
                )
            }
        }
    }

    /**
     * `reader/{itemId}` and `reader/external/{encodedUri}` share a top segment
     * and differ only in arity, so this pair is the one most likely to break if
     * someone "simplifies" the external route later.
     */
    @Test
    fun `external reader and player routes are distinguishable from their id-based forms`() {
        assertFalse(matches(Screen.Reader.route, Screen.ExternalReader.createRoute("abc")))
        assertFalse(matches(Screen.ExternalReader.route, Screen.Reader.createRoute(42L)))
        assertFalse(matches(Screen.Player.route, Screen.ExternalPlayer.createRoute("abc")))
        assertFalse(matches(Screen.ExternalPlayer.route, Screen.Player.createRoute(42L)))
    }

    // ── Patterns are internally well-formed ───────────────────────────────────

    @Test
    fun `no route pattern has an empty or malformed segment`() {
        allScreens.forEach { screen ->
            val route = screen.route
            assertFalse(route.startsWith("/"), "'$route' must not start with '/'")
            assertFalse(route.endsWith("/"), "'$route' must not end with '/'")
            assertFalse(route.contains("//"), "'$route' must not contain an empty segment")
            assertEquals(
                route.count { it == '{' },
                route.count { it == '}' },
                "unbalanced braces in '$route'",
            )
        }
    }

    @Test
    fun `route patterns are unique`() {
        val routes = allScreens.map { it.route }
        assertEquals(routes.size, routes.toSet().size, "duplicate route pattern declared")
    }

    /**
     * An encoded URI must stay a single path segment. `IntentRouter` passes
     * `Uri.encode(...)`, which escapes `/` as `%2F`; if that ever changed to a
     * raw URI the route would gain segments and match nothing, and the external
     * open flow would silently fail to navigate.
     */
    @Test
    fun `an encoded uri stays one segment so the external route still matches`() {
        val encoded = "content%3A%2F%2Fcom.example%2Fdoc%2F1"
        val route = Screen.ExternalReader.createRoute(encoded)
        assertTrue(matches(Screen.ExternalReader.route, route))
        assertEquals(3, route.split('/').size, "encoded uri must not introduce extra path segments")

        // A raw, unencoded URI would break matching — documents why encoding is required.
        val raw = "content://com.example/doc/1"
        assertFalse(matches(Screen.ExternalReader.route, Screen.ExternalReader.createRoute(raw)))
    }
}
