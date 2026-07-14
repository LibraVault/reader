package xyz.libravault.feature.player.service

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.LibravaultPreferences

/**
 * Unit tests for [SkipDurationPreference.getSkipDurationMs].
 *
 * Uses a pure-JVM [SharedPreferences] stub so we don't need Robolectric.
 * Covers the default, clamping, and boundary cases the third-review-pass
 * reviewer asked for.
 */
class SkipDurationPreferenceTest {

    @Test
    fun `default when key is absent is 30 seconds`() {
        val prefs = FakePrefs(skipSec = null)
        assertEquals(30_000L, SkipDurationPreference.getSkipDurationMs(prefs))
    }

    @Test
    fun `reads stored value in seconds and converts to milliseconds`() {
        assertEquals(45_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 45)))
        assertEquals(10_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 10)))
        assertEquals(60_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 60)))
    }

    @Test
    fun `clamps values below 5 seconds to 5 seconds`() {
        // 4 seconds is below the MIN — we don't allow sub-5-second skips because
        // they would let the ±seek button seek in opposite direction in edge cases.
        assertEquals(5_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 4)))
        assertEquals(5_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 0)))
    }

    @Test
    fun `clamps values above 120 seconds to 120 seconds`() {
        assertEquals(120_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 121)))
        assertEquals(120_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 999)))
    }

    @Test
    fun `accepts boundary values 5 and 120 exactly`() {
        assertEquals(5_000L,   SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 5)))
        assertEquals(120_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = 120)))
    }

    @Test
    fun `negative values are clamped to 5 seconds`() {
        // Defensive — a hand-edited prefs file or a future bug upstream shouldn't
        // produce a negative skip duration that would seek backwards on every tap.
        assertEquals(5_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = -30)))
    }

    @Test
    fun `Int_MAX overflows to 120 seconds via coerceIn`() {
        // `coerceIn(MIN, MAX)` on an Int won't overflow — verify the boundary.
        assertEquals(120_000L, SkipDurationPreference.getSkipDurationMs(FakePrefs(skipSec = Int.MAX_VALUE)))
    }

    /** Minimal SharedPreferences stub returning just the key we care about. */
    private class FakePrefs(skipSec: Int?) : SharedPreferences {
        private val map = buildMap<String, Any?> {
            if (skipSec != null) put(LibravaultPreferences.KEY_SKIP_DURATION_SEC, skipSec)
        }

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = null
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = null
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = defValue
        override fun getFloat(key: String, defValue: Float): Float = defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
