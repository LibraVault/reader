package xyz.libravault.core.domain

/**
 * Feature flags for LibraVault.
 *
 * All flags are **disabled by default** in production.
 * In debug builds, flags can be toggled via Settings → Advanced → Experimental Features.
 *
 * This is the pure-domain definition — no Compose, DataStore, or BuildConfig
 * dependencies. UI integration (composable helpers) and persistence (DataStore)
 * are provided by higher-layer modules via DI.
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
    // State (in-memory — production always returns false)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Test/debug overrides. Only accessed in non-production builds.
     * For production, isEnabled() always returns false.
     */
    private val overrides: MutableMap<Feature, Boolean> = mutableMapOf()

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Check if a feature is enabled.
     *
     * - In **production**: Always returns `false` (all features opt-in via release notes).
     * - In **debug builds**: May return stored or overridden value.
     *
     * Production-detection is done via a pluggable [DebugDetector] so the domain
     * module stays free of BuildConfig / Android dependencies.
     */
    fun isEnabled(feature: Feature): Boolean {
        if (!DebugDetector.isDebug) return false
        return overrides[feature] ?: false
    }

    /**
     * Set a feature flag override. Only effective in debug builds.
     *
     * WARNING: This should only be called from Settings UI in debug builds.
     * Persistence (SharedPreferences / DataStore) is handled by higher layers.
     */
    fun setEnabled(feature: Feature, enabled: Boolean) {
        if (!DebugDetector.isDebug) return
        overrides[feature] = enabled
    }

    /**
     * Reset all feature flags to defaults (disabled).
     */
    fun resetAll() {
        overrides.clear()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Debug Detection (pluggable — no BuildConfig dependency in domain)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pluggable debug-mode detector.
 *
 * Default implementation always returns false (production-safe).
 * The app module injects a real implementation that reads BuildConfig.DEBUG
 * via Hilt module binding.
 */
object DebugDetector {
    var isDebug: Boolean = false
        private set

    /**
     * Called once during app startup (from Application.onCreate or Hilt module).
     * Must be set before any [FeatureFlags] calls.
     */
    fun initialize(debug: Boolean) {
        isDebug = debug
    }
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
    FeatureFlags.setEnabled(feature, enabled)
}

/**
 * Reset all test overrides.
 */
fun FeatureFlags.resetOverrides() {
    FeatureFlags.resetAll()
}
