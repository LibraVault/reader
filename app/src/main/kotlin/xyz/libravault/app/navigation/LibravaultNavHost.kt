package xyz.libravault.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import xyz.libravault.feature.library.LibraryScreen
import xyz.libravault.feature.onboarding.OnboardingScreen
import xyz.libravault.feature.player.PlayerScreen
import xyz.libravault.feature.reader.ReaderScreen

sealed class Screen(val route: String) {
    data object Onboarding : Screen("onboarding")
    data object Library    : Screen("library")
    data object Reader     : Screen("reader/{itemId}") {
        fun createRoute(itemId: Long) = "reader/$itemId"
    }
    data object Player     : Screen("player/{itemId}") {
        fun createRoute(itemId: Long) = "player/$itemId"
    }
}

@Composable
fun LibravaultNavHost(
    navController: NavHostController,
    startDestination: String,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onFinished = {
                    navController.navigate(Screen.Library.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Library.route) {
            LibraryScreen(
                onItemClick = { item ->
                    val route = when (item.format) {
                        xyz.libravault.core.domain.model.MediaFormat.MP3,
                        xyz.libravault.core.domain.model.MediaFormat.M4B ->
                            Screen.Player.createRoute(item.id)
                        else ->
                            Screen.Reader.createRoute(item.id)
                    }
                    navController.navigate(route)
                },
            )
        }

        composable(
            route = Screen.Reader.route,
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { backStackEntry ->
            ReaderScreen(
                itemId = backStackEntry.arguments!!.getLong("itemId"),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(navArgument("itemId") { type = NavType.LongType }),
        ) { backStackEntry ->
            PlayerScreen(
                itemId = backStackEntry.arguments!!.getLong("itemId"),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
