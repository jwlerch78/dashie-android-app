package com.dashieapp.Dashie.halite.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * [UpdateBackend] for Dashie builds distributed through the Amazon Appstore.
 *
 * Amazon has no in-app update API and won't reliably auto-update a foreground
 * kiosk, so this backend rolls the *detection* itself (same Dashie-hosted
 * manifest as [SideloadUpdateBackend]) but can't install — an Amazon-installed
 * app must update through the Appstore. The "update action" therefore opens
 * the Amazon Appstore's Dashie detail page, where the user taps Update.
 *
 * [supportsDeferredInstall] is false — there's no "Update Tonight" since the
 * install isn't ours to schedule.
 */
class AmazonUpdateBackend(
    context: Context,
    private val manifestUrl: String
) : UpdateBackend(context) {

    companion object {
        private const val TAG = "DashieUpdate"
        /** Amazon Appstore deep link to Dashie's detail page. */
        private const val APPSTORE_URI = "amzn://apps/android?p=com.dashieapp.Dashie"
        /** Web fallback if the Appstore app can't handle the amzn:// scheme. */
        private const val APPSTORE_WEB = "https://www.amazon.com/gp/mas/dl/android?p=com.dashieapp.Dashie"
    }

    override val supportsDeferredInstall = false

    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun checkForUpdate() {
        scope.launch {
            try {
                val json = http.newCall(Request.Builder().url(manifestUrl).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.string()
                    } ?: throw IOException("empty manifest")

                val o = JSONObject(json)
                val versionCode = o.getInt("versionCode")
                if (versionCode > BuildConfig.VERSION_CODE && !updateAvailable) {
                    updateAvailable = true
                    availableVersionName = o.optString("versionName").takeIf { it.isNotBlank() }
                    shouldNotify = o.optBoolean("notify", true)
                    releaseTitle = o.optString("title").takeIf { it.isNotBlank() }
                    releaseDescription = o.optString("description").takeIf { it.isNotBlank() }
                    Log.i(TAG, "Amazon update available: vc$versionCode (notify=$shouldNotify)")
                    if (shouldNotify) onUpdateAvailable?.invoke()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Amazon update check failed: ${e.message}")
            }
        }
    }

    /**
     * The "Update" action — open the Amazon Appstore's Dashie detail page so
     * the user can install the update there. (Named startDownload to satisfy
     * the [UpdateBackend] contract; the Amazon backend downloads nothing.)
     */
    override fun startDownload(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APPSTORE_URI)))
            Log.i(TAG, "Opened Amazon Appstore detail page")
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APPSTORE_WEB)))
                Log.i(TAG, "Opened Amazon Appstore (web fallback)")
            } catch (e2: Exception) {
                Log.w(TAG, "Could not open Amazon Appstore: ${e2.message}")
            }
        }
    }

    /** No-op — an Amazon-installed app is updated by the Appstore, not by us. */
    override fun installAndRestart() {
        Log.i(TAG, "Amazon backend — install is handled by the Appstore")
    }

    override fun cleanup() {
        scope.cancel()
    }
}
