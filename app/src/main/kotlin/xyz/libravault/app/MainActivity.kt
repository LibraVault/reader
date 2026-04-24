package xyz.libravault.app

import android.content.Context
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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

    @Inject
    lateinit var intentRouter: IntentRouter

    /**
     * Set once inside [setContent] via [remember]. Guaranteed to be non-null
     * by the time [onNewIntent] can fire (activity is already running).
     */
    private var navController: NavController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()

        val hasOnboarded = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDED, false)

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
                },
                useDynamicColor = prefs.dynamicColorEnabled,
            ) {
                val nav = rememberNavController()
                val start = remember {
                    if (hasOnboarded) Screen.Library.route else Screen.Onboarding.route
                }

                // Store reference so onNewIntent can navigate without recreating
                navController = nav

                LibravaultNavHost(
                    navController    = nav,
                    startDestination = start,
                )

                // Handle ACTION_VIEW intent that cold-launched this activity
                remember(nav) {
                    intent?.takeIf { it.action == Intent.ACTION_VIEW }
                        ?.data
                        ?.let { uri ->
                            lifecycleScope.launch { intentRouter.route(uri, nav) }
                        }
                    true
                }
            }
        }
    }

    /** Called when the app is already running and a file is opened externally. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                lifecycleScope.launch {
                    navController?.let { intentRouter.route(uri, it) }
                }
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
