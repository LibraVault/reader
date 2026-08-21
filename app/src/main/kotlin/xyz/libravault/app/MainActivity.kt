package xyz.libravault.app

import android.content.Context
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
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
class MainActivity : FragmentActivity() {

    @Inject lateinit var prefsRepository: UserPreferencesRepository
    @Inject lateinit var intentRouter: IntentRouter

    // Pending ACTION_VIEW intent. Updated by onNewIntent; consumed by the
    // LaunchedEffect inside setContent. Holding the Intent in a Compose
    // state (rather than a mutable Activity field + nullable navController)
    // closes the race window between super.onCreate() and the first
    // composition: the consumer is keyed on the Intent itself and re-runs
    // whenever onNewIntent sets a new value (review finding #25 / WS5.6).
    private var pendingIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        val hasOnboarded = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

        // Seed the pending intent so cold-launch ACTION_VIEW flows route
        // on first composition.
        pendingIntent = intent

        setContent {
            val prefs by prefsRepository.observe().collectAsState(
                initial = prefsRepository.read()
            )

            LibravaultTheme(
                readingTheme    = when (prefs.defaultReadingTheme) {
                    xyz.libravault.core.domain.model.AppReadingTheme.DARK  ->
                        xyz.libravault.core.ui.theme.ReadingTheme.DARK
                    xyz.libravault.core.domain.model.AppReadingTheme.LIGHT ->
                        xyz.libravault.core.ui.theme.ReadingTheme.LIGHT
                    xyz.libravault.core.domain.model.AppReadingTheme.SEPIA ->
                        xyz.libravault.core.ui.theme.ReadingTheme.SEPIA
                    xyz.libravault.core.domain.model.AppReadingTheme.SYSTEM ->
                        xyz.libravault.core.ui.theme.ReadingTheme.SYSTEM
                },
                useDynamicColor = prefs.dynamicColorEnabled,
            ) {
                val nav = rememberNavController()
                val start = remember {
                    if (hasOnboarded) Screen.Library.route else Screen.Onboarding.route
                }

                LibravaultNavHost(
                    navController    = nav,
                    startDestination = start,
                )

                // Consume the pending ACTION_VIEW intent. Re-runs whenever
                // pendingIntent changes (i.e. onNewIntent sets a new one).
                LaunchedEffect(pendingIntent, nav) {
                    val current = pendingIntent ?: return@LaunchedEffect
                    if (current.action == Intent.ACTION_VIEW) {
                        current.data?.let { uri -> intentRouter.route(uri, nav) }
                    }
                    pendingIntent = null
                }
            }
        }
    }

    /** Called when the app is already running and a file is opened externally. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }

    fun markOnboarded() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDED, true)
            .apply()
    }
}
