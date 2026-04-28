package xyz.libravault.core.domain

/**
 * Feature flags for LibraVault.
 *
 * All flags are **disabled by default** in production.
 * In debug builds, flags can be toggled via Settings → Advanced → Experimental Features.
 *
 * This is a pure Kotlin class with no Android dependencies.
 * Compose/DataStore integration lives in the UI/data layers.
 */
object FeatureFlags {

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

    private val overrides = mutableMapOf<Feature, Boolean>()

    /**
     * Check if a feature is enabled.
     * Defaults to false. Override in debug/test builds.
     */
    fun isEnabled(feature: Feature): Boolean = overrides[feature] ?: false

    /**
     * Set a feature flag override. Primarily for debug/test use.
     */
    fun setEnabled(feature: Feature, enabled: Boolean) {
        overrides[feature] = enabled
    }

    /**
     * Reset all feature flags to defaults (disabled).
     */
    fun resetAll() {
        overrides.clear()
    }
}