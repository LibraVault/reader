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

private const val PREFS_NAME          = "libravault_prefs"
private const val KEY_SUPPORTER       = "is_supporter"
private const val KEY_PENDING_INVOICE = "pending_invoice_id"

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

    fun getPendingInvoiceId(): String? = prefs.getString(KEY_PENDING_INVOICE, null)

    fun setPendingInvoiceId(id: String?) {
        prefs.edit().apply {
            if (id == null) remove(KEY_PENDING_INVOICE) else putString(KEY_PENDING_INVOICE, id)
            apply()
        }
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
