package xyz.libravault.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Walks the Compose [LocalContext] wrapper chain to find the hosting Activity. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
