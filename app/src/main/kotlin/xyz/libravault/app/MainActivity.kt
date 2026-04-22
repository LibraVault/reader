package xyz.libravault.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import xyz.libravault.app.navigation.LibravaultNavHost
import xyz.libravault.app.navigation.Screen
import xyz.libravault.core.ui.theme.LibravaultTheme

private const val PREFS_NAME      = "libravault_prefs"
private const val KEY_ONBOARDED   = "onboarded"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val hasOnboarded = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

        setContent {
            LibravaultTheme {
                val navController = rememberNavController()
                val start = remember {
                    if (hasOnboarded) Screen.Library.route else Screen.Onboarding.route
                }

                LibravaultNavHost(
                    navController = navController,
                    startDestination = start,
                )
            }
        }
    }

    /** Called from OnboardingViewModel after first vault is added. */
    fun markOnboarded() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
    }
}
