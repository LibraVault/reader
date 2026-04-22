package xyz.libravault.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import xyz.libravault.app.navigation.LibravaultNavHost
import xyz.libravault.app.navigation.Screen
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.feature.settings.UserPreferencesRepository
import javax.inject.Inject

private const val PREFS_NAME    = "libravault_prefs"
private const val KEY_ONBOARDED = "onboarded"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var prefsRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val hasOnboarded = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

        setContent {
            val prefs by prefsRepository.observe().collectAsState(
                initial = prefsRepository.read()
            )

            LibravaultTheme(
                readingTheme    = when (prefs.defaultReadingTheme) {
                    xyz.libravault.core.domain.model.AppReadingTheme.DARK  -> xyz.libravault.core.ui.theme.ReadingTheme.DARK
                    xyz.libravault.core.domain.model.AppReadingTheme.LIGHT -> xyz.libravault.core.ui.theme.ReadingTheme.LIGHT
                    xyz.libravault.core.domain.model.AppReadingTheme.SEPIA -> xyz.libravault.core.ui.theme.ReadingTheme.SEPIA
                },
                useDynamicColor = prefs.dynamicColorEnabled,
            ) {
                val navController = rememberNavController()
                val start = remember {
                    if (hasOnboarded) Screen.Library.route else Screen.Onboarding.route
                }

                LibravaultNavHost(
                    navController    = navController,
                    startDestination = start,
                )
            }
        }
    }

    fun markOnboarded() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
    }
}
