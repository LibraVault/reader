package xyz.libravault.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.logger.LibravaultLogger
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val coverArtCache: CoverArtCache,
    private val logger: LibravaultLogger,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = prefsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefsRepo.read())

    fun onReadingThemeChanged(theme: AppReadingTheme) = update { it.copy(defaultReadingTheme = theme) }

    fun onPlaybackSpeedChanged(speed: Float) = update {
        it.copy(defaultPlaybackSpeed = snapPlaybackSpeed(speed))
    }

    fun onSkipDurationChanged(seconds: Int) = update {
        it.copy(defaultSkipDurationSec = seconds.coerceIn(5, 120))
    }

    fun onLoggingToggled(enabled: Boolean) {
        logger.isEnabled = enabled
        update { it.copy(loggingEnabled = enabled) }
    }

    fun onDynamicColorToggled(enabled: Boolean) = update {
        it.copy(dynamicColorEnabled = enabled)
    }

    fun clearCoverCache() {
        viewModelScope.launch {
            coverArtCache.clearAll()
            logger.i("Settings", "Cover art cache cleared")
        }
    }

    fun viewLogs() {
        viewModelScope.launch {
            val logs = logger.readLogs()
            // Logs are displayed via SettingsScreen state — emitted separately
            _logsContent = logs
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logger.clearLogs()
        }
    }

    private var _logsContent: String = ""

    private fun update(transform: (UserPreferences) -> UserPreferences) {
        prefsRepo.update(transform(prefsRepo.read()))
    }
}
