package xyz.libravault.app

import android.net.Uri
import androidx.navigation.NavController
import dagger.hilt.android.scopes.ActivityScoped
import xyz.libravault.app.navigation.Screen
import xyz.libravault.core.storage.usecase.OpenFileUseCase
import javax.inject.Inject

/**
 * Resolves an incoming ACTION_VIEW [Uri] and navigates to the correct screen.
 *
 * If the file is already in the library the user's reading/listening
 * progress is preserved. If it's a new external file a transient
 * [LibraryItem] is created (not persisted) and opened directly.
 */
@ActivityScoped
class IntentRouter @Inject constructor(
    private val openFile: OpenFileUseCase,
) {
    suspend fun route(uri: Uri, navController: NavController) {
        val item = openFile(uri) ?: return   // unsupported format — ignore

        val route = when {
            item.format.isAudio() ->
                if (item.id > 0) Screen.Player.createRoute(item.id)
                else Screen.ExternalPlayer.createRoute(Uri.encode(uri.toString()))

            else ->
                if (item.id > 0) Screen.Reader.createRoute(item.id)
                else Screen.ExternalReader.createRoute(Uri.encode(uri.toString()))
        }

        navController.navigate(route) {
            // Don't stack multiple file-open destinations
            launchSingleTop = true
        }
    }
}
