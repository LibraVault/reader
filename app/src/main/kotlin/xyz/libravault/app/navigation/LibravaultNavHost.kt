package xyz.libravault.app.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.vaultstore.VAULT_AUDIO_FORMAT_NAMES
import xyz.libravault.core.vaultstore.toHexString
import xyz.libravault.feature.library.LibraryScreen
import xyz.libravault.feature.onboarding.OnboardingScreen
import xyz.libravault.feature.player.PlayerScreen
import xyz.libravault.feature.player.service.PlaybackStateHolder
import xyz.libravault.feature.player.service.VAULT_MEDIA_URI_SCHEME
import xyz.libravault.feature.reader.ReaderScreen
import xyz.libravault.feature.settings.SettingsScreen
import xyz.libravault.feature.vault.CreateVaultScreen
import xyz.libravault.feature.vault.UnlockVaultScreen
import xyz.libravault.feature.vault.VaultContentsScreen
import xyz.libravault.feature.vault.VaultListScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Library    : Screen("library")
    data object Settings   : Screen("settings")

    // ── Encrypted Vaults (PRD: "Vault", distinct from the unencrypted
    // "Folder" concept — see feature:settings's "Encrypted Vaults" section) ──
    data object VaultList : Screen("vaults")
    // "vault/new", not "vaults/new" — deliberately a different top segment
    // from "vaults/{vaultId}" below, so the two routes can never structurally
    // collide regardless of how Navigation-Compose breaks matching ties.
    data object CreateVault : Screen("vault/new")
    data object UnlockVault : Screen("vaults/{vaultId}/unlock") {
        fun createRoute(vaultId: String) = "vaults/$vaultId/unlock"
    }
    data object VaultContents : Screen("vaults/{vaultId}") {
        fun createRoute(vaultId: String) = "vaults/$vaultId"
    }
    data object VaultRead : Screen("vaults/{vaultId}/read/{fileId}") {
        fun createRoute(vaultId: String, fileId: String) = "vaults/$vaultId/read/$fileId"
    }
    data object VaultPlay : Screen("vaults/{vaultId}/play/{fileId}") {
        fun createRoute(vaultId: String, fileId: String) = "vaults/$vaultId/play/$fileId"
    }

    /** Open a library item by its Room ID (normal flow). */
    data object Reader : Screen("reader/{itemId}") {
        fun createRoute(itemId: Long) = "reader/$itemId"
    }
    data object Player : Screen("player/{itemId}?seekMs={seekMs}") {
        fun createRoute(itemId: Long) = "player/$itemId"
        fun createRouteWithSeek(itemId: Long, seekMs: Long) = "player/$itemId?seekMs=$seekMs"
    }

    /** Open an external file URI directly (ACTION_VIEW flow). */
    data object ExternalReader : Screen("reader/external/{encodedUri}") {
        fun createRoute(encodedUri: String) = "reader/external/$encodedUri"
    }
    data object ExternalPlayer : Screen("player/external/{encodedUri}") {
        fun createRoute(encodedUri: String) = "player/external/$encodedUri"
    }
}

/**
 * Routes a mini-player tap to the right player destination — [Screen.Player] for a
 * real-file item, [Screen.VaultPlay] for an Encrypted Vault one (#493). One shared
 * helper for the four `onNowPlayingClick` call sites below (Library + three
 * ReaderScreen entry points) rather than duplicating the branch four times.
 */
private fun navigateToNowPlaying(navController: NavHostController, state: PlaybackStateHolder.State) {
    val itemId = state.itemId
    val vault = state.vaultEntry
    when {
        itemId != null -> navController.navigate(Screen.Player.createRoute(itemId))
        vault != null -> navController.navigate(Screen.VaultPlay.createRoute(vault.vaultId, vault.fileIdHex))
    }
}

/**
 * Decides the route for a Library item tap (Phase 3, #508) — extracted per
 * `AGENTS.md`'s guidance to keep "what happens" decisions unit-testable
 * rather than left inline in a composable body, matching
 * `LibraryScreenLogic.kt`'s `isVaultLibraryItem`/`shouldShowMiniPlayer`
 * precedent and this file's own `ScreenRouteTest`.
 *
 * A vault-sourced [LibraryItem] carries a `vault://$vaultId/$fileIdHex`
 * `filePath` (see `LibraryViewModel.toLibraryItem`) — parsed here with plain
 * string splitting rather than `android.net.Uri.parse`, both because
 * `item.id` is a synthetic, non-Room id for these (`Screen.Reader`/`Player`
 * expect a real one, so they'd resolve nothing) and because a malformed
 * `filePath` degrades to [Screen.Reader]/[Screen.Player] instead of crashing
 * on a `requireNotNull` — this is app-level navigation, not an internal
 * invariant, so tolerating unexpected input beats an unhandled exception.
 */
internal fun libraryItemClickRoute(item: LibraryItem): String {
    val vaultPrefix = "$VAULT_MEDIA_URI_SCHEME://"
    val rest = item.filePath.removePrefix(vaultPrefix)
    if (rest != item.filePath) {
        val vaultId = rest.substringBefore('/')
        val fileIdHex = rest.substringAfter('/', missingDelimiterValue = "")
        if (vaultId.isNotEmpty() && fileIdHex.isNotEmpty()) {
            return if (item.format.isAudio()) {
                Screen.VaultPlay.createRoute(vaultId, fileIdHex)
            } else {
                Screen.VaultRead.createRoute(vaultId, fileIdHex)
            }
        }
    }
    return if (item.format.isAudio()) Screen.Player.createRoute(item.id) else Screen.Reader.createRoute(item.id)
}

@Composable
fun LibravaultNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController    = navController,
        startDestination = startDestination,
    ) {
        composable(Screen.Onboarding.route) {
            val activity = LocalContext.current as? Activity
            OnboardingScreen(
                onFinished = {
                    (activity as? xyz.libravault.app.MainActivity)?.markOnboarded()
                    navController.navigate(Screen.Library.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                onItemClick = { item -> navController.navigate(libraryItemClickRoute(item)) },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onNowPlayingClick = { state -> navigateToNowPlaying(navController, state) },
                onBookmarkItemClick = { item, seekMs ->
                    navController.navigate(Screen.Player.createRouteWithSeek(item.id, seekMs))
                },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onEncryptedVaultsClick = { navController.navigate(Screen.VaultList.route) },
            )
        }

        // ── Encrypted Vaults ────────────────────────────────────────────────

        composable(Screen.VaultList.route) {
            VaultListScreen(
                onCreateVault = { navController.navigate(Screen.CreateVault.route) },
                onUnlockVault = { vaultId -> navController.navigate(Screen.UnlockVault.createRoute(vaultId)) },
                onOpenVault = { vaultId -> navController.navigate(Screen.VaultContents.createRoute(vaultId)) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.CreateVault.route) {
            CreateVaultScreen(
                onCreated = { vaultId ->
                    navController.navigate(Screen.VaultContents.createRoute(vaultId)) {
                        popUpTo(Screen.VaultList.route)
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            route     = Screen.UnlockVault.route,
            arguments = listOf(navArgument("vaultId") { type = NavType.StringType }),
        ) {
            UnlockVaultScreen(
                onUnlocked = { vaultId ->
                    navController.navigate(Screen.VaultContents.createRoute(vaultId)) {
                        popUpTo(Screen.VaultList.route)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route     = Screen.VaultContents.route,
            arguments = listOf(navArgument("vaultId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val vaultId = backStackEntry.arguments!!.getString("vaultId")!!
            VaultContentsScreen(
                onBack = { navController.popBackStack(Screen.VaultList.route, inclusive = false) },
                onOpenEntry = { entry ->
                    val fileId = entry.fileId.toHexString()
                    val route = if (entry.format in VAULT_AUDIO_FORMAT_NAMES) {
                        Screen.VaultPlay.createRoute(vaultId, fileId)
                    } else {
                        Screen.VaultRead.createRoute(vaultId, fileId)
                    }
                    navController.navigate(route)
                },
            )
        }

        composable(
            route     = Screen.VaultRead.route,
            arguments = listOf(
                navArgument("vaultId") { type = NavType.StringType },
                navArgument("fileId") { type = NavType.StringType },
            ),
        ) {
            // #505 — vault EPUB/PDF/Markdown now render through the same ReaderScreen
            // as plain files; ReaderViewModel reads vaultId/fileId off this composable's
            // own SavedStateHandle (same Hilt nav-arg auto-population that already makes
            // itemId/encodedUri work below), exactly like VaultReaderScreen used to.
            ReaderScreen(
                onBack            = { navController.popBackStack() },
                onNowPlayingClick = { state -> navigateToNowPlaying(navController, state) },
            )
        }

        composable(
            route     = Screen.VaultPlay.route,
            arguments = listOf(
                navArgument("vaultId") { type = NavType.StringType },
                navArgument("fileId") { type = NavType.StringType },
            ),
        ) {
            // #493 — vault audio now plays through the same shared PlaybackService/
            // MediaSession as real files; PlayerViewModel reads vaultId/fileId off this
            // composable's own SavedStateHandle (same Hilt nav-arg auto-population
            // ReaderScreen's VaultRead route above already relies on), exactly like
            // VaultPlayerScreen used to.
            PlayerScreen(onBack = { navController.popBackStack() })
        }

        // ── Library-item routes (by Room ID) ──────────────────────────────

        composable(
            route     = Screen.Reader.route,
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { backStackEntry ->
            ReaderScreen(
                itemId           = backStackEntry.arguments!!.getLong("itemId"),
                onBack           = { navController.popBackStack() },
                onNowPlayingClick = { state -> navigateToNowPlaying(navController, state) },
            )
        }

        composable(
            route     = Screen.Player.route,
            arguments = listOf(
                navArgument("itemId") { type = NavType.LongType },
                navArgument("seekMs") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { backStackEntry ->
            PlayerScreen(
                itemId = backStackEntry.arguments!!.getLong("itemId"),
                onBack = { navController.popBackStack() },
            )
        }

        // ── External file routes (ACTION_VIEW, by URI) ────────────────────

        composable(
            route     = Screen.ExternalReader.route,
            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments!!.getString("encodedUri")!!
            val uri        = Uri.parse(Uri.decode(encodedUri))
            ReaderScreen(
                fileUri           = uri,
                itemId            = null,
                onBack            = { navController.popBackStack() },
                onNowPlayingClick = { state -> navigateToNowPlaying(navController, state) },
            )
        }

        composable(
            route     = Screen.ExternalPlayer.route,
            arguments = listOf(navArgument("encodedUri") { type = NavType.StringType }),
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments!!.getString("encodedUri")!!
            val uri        = Uri.parse(Uri.decode(encodedUri))
            PlayerScreen(
                fileUri = uri,
                itemId  = null,
                onBack  = { navController.popBackStack() },
            )
        }
    }
}

