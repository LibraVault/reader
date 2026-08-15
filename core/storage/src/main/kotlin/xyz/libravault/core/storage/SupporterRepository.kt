package xyz.libravault.core.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME    = "libravault_prefs"
private const val KEY_SUPPORTER = "is_supporter"

/**
 * Whether the user has earned the Supporter badge. Read/write is preserved
 * even though nothing in the app calls [setSupporter] anymore (the in-app
 * BTCPay invoice flow that used to set it was removed — see
 * `SUPPORT_URL`/`fix/donation-external-link-only`) — this keeps the badge
 * showing for anyone who already earned it, and leaves the door open for a
 * future legitimate way to set it (e.g. Play Billing) without another storage
 * migration.
 */
@Singleton
class SupporterRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSupporter(): Boolean = prefs.getBoolean(KEY_SUPPORTER, false)

    fun setSupporter(value: Boolean) {
        prefs.edit().putBoolean(KEY_SUPPORTER, value).apply()
    }

    fun observe(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_SUPPORTER) trySend(isSupporter())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(isSupporter())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
