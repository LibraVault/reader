package xyz.libravault.core.domain

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import xyz.libravault.core.BuildConfig

/**
 * Feature flags for LibraVault.
 *
 * All flags are **disabled by default** in production.
 * In debug builds, flags can be toggled via Settings → Advanced → Experimental Features.
 */
object FeatureFlags {

    // ──────────────────────────────────────────────────────────────────────────
    // Feature Definitions
    // ──────────────────────────────────────────────────────────────────────────

    enum class Feature {
        /** Parallel vault scanning (batched, multi-threaded) */
        PARALLEL_SCANNING,

        /** Show format breakdown (EPUB/PDF/Audiobook counts) in scan completion message */
        SCAN_FORMAT_BREAKDOWN,

        /** Scan health dashboard in Settings → Advanced */
        SCAN_HEALTH_DASHBOARD,

        /** Scan preview modal — show items before committing to library */
        SCAN_PREVIEW,

        /** Smart scan resumption — only scan folders modified since last scan */
        SMART_RESUMPTION,
    }

    // ──────────────────────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────────────────────

    private val Context.dataStore by preferencesDataStore(name = "feature_flags")

    private val SharedPreferences.allFlags: Map<String, Boolean>
        get() = all

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Check if a feature is enabled.
     *
     * - In **production**: Always returns `false` (all features opt-in via release notes).
     * - In **debug builds**: Returns stored preference + fallback.
     */
    fun isEnabled(feature: Feature): Boolean {
        return if (BuildConfig.DEBUG) {
            // TODO: Replace with dataStore-based read when DataStore is integrated
            false // Placeholder — actual implementation needs SharedPreferences/DataStore
        } else {
            false // Production: all features disabled by default
        }
    }

    /**
     * Set a feature flag. Only affects debug builds.
     *
     * WARNING: This should only be called from Settings UI in debug builds.
     */
    fun setEnabled(feature: Feature, enabled: Boolean) {
        if (BuildConfig.DEBUG) {
            // TODO: Replace with dataStore.writePreference when DataStore is integrated
        }
    }

    /**
     * Reset all feature flags to defaults (disabled).
     */
    fun resetAll() {
        // TODO: Clear SharedPreferences or DataStore
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Compose Integration
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Compose helper to observe feature flag changes.
 *
 * Usage:
 * ```kotlin
 * @Composable
 * fun MyScreen() {
 *     val parallelScanningEnabled = featureFlag(FeatureFlags.Feature.PARALLEL_SCANNING)
 *
 *     if (parallelScanningEnabled) {
 *         ParallelScanUI()
 *     } else {
 *         SequentialScanUI()
 *     }
 * }
 * ```
 */
@Composable
fun featureFlag(feature: FeatureFlags.Feature): Boolean {
    // TODO: Implement with DataStore flow subscription
    return FeatureFlags.isEnabled(feature)
}

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel Helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ViewModel helper for testability — inject FeatureFlags into ViewModels.
 */
class FeatureFlagProvider {
    fun isEnabled(feature: FeatureFlags.Feature): Boolean = FeatureFlags.isEnabled(feature)

    fun setEnabled(feature: FeatureFlags.Feature, enabled: Boolean) {
        FeatureFlags.setEnabled(feature, enabled)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Testing Utilities
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Temporary override for unit tests.
 *
 * WARNING: Must be cleaned up in `tearDown()` or `@After`!
 */
fun FeatureFlags.override(feature: FeatureFlags.Feature, enabled: Boolean) {
    // TODO: Implement test override
}

/**
 * Reset all test overrides.
 */
fun FeatureFlags.resetOverrides() {
    // TODO: Clear test overrides
}
