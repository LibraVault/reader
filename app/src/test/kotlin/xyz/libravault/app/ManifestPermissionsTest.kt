package xyz.libravault.app

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the manifest's PERMISSIONS POLICY comment
 * ([AndroidManifest.xml]): Libravault never requests READ_EXTERNAL_STORAGE,
 * WRITE_EXTERNAL_STORAGE or READ_PHONE_STATE — all file access goes through
 * SAF (Storage Access Framework) URIs the user picks explicitly.
 *
 * Worth testing because these three are *not* declared anywhere in our own
 * manifest or in any dependency AAR's manifest — AGP's ManifestMerger2
 * (`com.android.manifmerger.XmlDocument`) auto-injects them as a legacy
 * permission-split compatibility shim, keyed off the lowest minSdkVersion
 * declared by any merged library manifest (several AndroidX/Readium
 * transitive deps still declare 14-19), even though this app's effective
 * minSdk is 31. Verified via a clean `processFdroidDebugMainManifest` /
 * `processPlayDebugMainManifest` build plus a full sweep of every dependency
 * AAR's manifest before concluding the injection was AGP-internal, not a
 * dependency leak. Explicitly suppressed with `tools:node="remove"` — this
 * test is what stops a future dependency bump from silently reintroducing
 * them (a reviewer already flagged this once, F-Droid MR !43520
 * note_3686431461).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestPermissionsTest {

    @Test
    fun `manifest does not request storage or phone-state permissions`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

        assertFalse(
            "READ_EXTERNAL_STORAGE must not be requested — see PERMISSIONS POLICY in AndroidManifest.xml",
            requested.contains("android.permission.READ_EXTERNAL_STORAGE"),
        )
        assertFalse(
            "WRITE_EXTERNAL_STORAGE must not be requested — see PERMISSIONS POLICY in AndroidManifest.xml",
            requested.contains("android.permission.WRITE_EXTERNAL_STORAGE"),
        )
        assertFalse(
            "READ_PHONE_STATE must not be requested — see PERMISSIONS POLICY in AndroidManifest.xml",
            requested.contains("android.permission.READ_PHONE_STATE"),
        )
        assertFalse(
            "INTERNET must not be requested — the app makes zero network calls now that " +
                "the in-app BTCPay donation flow was removed in favor of an external " +
                "Support link (see SUPPORT_URL in feature:settings)",
            requested.contains("android.permission.INTERNET"),
        )
    }
}
