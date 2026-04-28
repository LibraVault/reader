package xyz.libravault.core.domain

/**
 * Feature flags for LibraVault.
 *
 * All flags are **disabled by default** in production.
 * In debug builds, flags can be toggled via Settings → Advanced → Experimental Features.
 *
 * This is a pure Kotlin class with NO Android or Compose dependencies.
 * Persistence and Compose integration live in the data and UI layers respectively.
 */
class FeatureFlags(private val isDebug: Boolean = false) {

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
    // In-memory state (persisted via data layer in debug builds)
    // ──────────────────────────────────────────────────────────────────────────

    private val overrides = mutableMapOf<Feature, Boolean>()

    // ──────────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Check if a feature is enabled.
     *
     * - In **production**: Always returns `false` (all features opt-in via release notes).
     * - In **debug builds**: Returns stored override if set, otherwise `false`.
     */
    fun isEnabled(feature: Feature): Boolean {
        return if (isDebug) {
            overrides[feature] ?: false
        } else {
            false
        }
    }

    /**
     * Set a feature flag override. Only meaningful in debug builds.
     */
    fun setEnabled(feature: Feature, enabled: Boolean) {
        if (isDebug) {
            overrides[feature] = enabled
        }
    }

    /**
     * Reset all feature flags to defaults (disabled).
     */
    fun resetAll() {
        overrides.clear()
    }
}

/**
 * ViewModel helper for testability — inject FeatureFlags into ViewModels.
 */
class FeatureFlagProvider(private val featureFlags: FeatureFlags) {
    fun isEnabled(feature: FeatureFlags.Feature): Boolean = featureFlags.isEnabled(feature)

    fun setEnabled(feature: FeatureFlags.Feature, enabled: Boolean) {
        featureFlags.setEnabled(feature, enabled)
    }
}

/**
 * Temporary override for unit tests.
 *
 * WARNING: Must be cleaned up in `tearDown()` or `@After`!
 */
fun FeatureFlags.override(feature: FeatureFlags.Feature, enabled: Boolean) {
    setEnabled(feature, enabled)
}

/**
 * Reset all test overrides.
 */
fun FeatureFlags.resetOverrides() {
    resetAll()
}