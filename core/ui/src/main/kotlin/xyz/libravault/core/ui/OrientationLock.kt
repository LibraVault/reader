package xyz.libravault.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/** Walks the Compose [LocalContext] wrapper chain to find the hosting Activity. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Locks the screen to [orientation] for as long as the calling composable stays in
 * composition, restoring the Activity's previous requestedOrientation on dispose.
 *
 * MainActivity itself is left unlocked (`android:screenOrientation` unset) so most
 * screens rotate freely; only screens like the audiobook player — where rotating mid
 * playback serves no purpose — opt into this.
 */
@Composable
fun LockScreenOrientation(orientation: Int = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
    val context = LocalContext.current
    DisposableEffect(orientation) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val originalOrientation = activity.requestedOrientation
        activity.requestedOrientation = orientation
        onDispose {
            activity.requestedOrientation = originalOrientation
        }
    }
}
