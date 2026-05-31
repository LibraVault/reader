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
import xyz.libravault.feature.library.LibraryScreen
import xyz.libravault.feature.onboarding.OnboardingScreen
import xyz.libravault.feature.player.PlayerScreen
import xyz.libravault.feature.reader.ReaderScreen
import xyz.libravault.feature.settings.SettingsScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Library    : Screen("library")
    data object Settings   : Screen("settings")

    /** Open a library item by its Room ID (normal flow). */
    data object Reader : Screen("reader/{itemId}") {
        fun createRoute(itemId: Long) = "reader/$itemId"
    }
    data object Player : Screen("player/{itemId}") {
        fun createRoute(itemId: Long) = "player/$itemId"
    }

    /** Open an external file URI directly (ACTION_VIEW flow). */
    data object ExternalReader : Screen("reader/external/{encodedUri}") {
        fun createRoute(encodedUri: String) = "reader/external/$encodedUri"
    }
    data object ExternalPlayer : Screen("player/external/{encodedUri}") {
        fun createRoute(encodedUri: String) = "player/external/$encodedUri"
    }
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
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                onItemClick = { item ->
                    val route = when {
                        item.format.isAudio() -> Screen.Player.createRoute(item.id)
                        else                  -> Screen.Reader.createRoute(item.id)
                    }
                    navController.navigate(route)
                },
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onNowPlayingClick = { itemId ->
                    navController.navigate(Screen.Player.createRoute(itemId))
                },
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
            )
        }

        // ── Library-item routes (by Room ID) ──────────────────────────────

        composable(
            route     = Screen.Reader.route,
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { backStackEntry ->
            ReaderScreen(
                itemId = backStackEntry.arguments!!.getLong("itemId"),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route     = Screen.Player.route,
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
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
                fileUri = uri,
                itemId  = null,
                onBack  = { navController.popBackStack() },
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

