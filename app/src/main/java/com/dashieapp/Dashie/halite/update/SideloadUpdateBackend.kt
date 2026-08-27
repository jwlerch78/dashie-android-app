package com.dashieapp.Dashie.halite.update

import android.app.Activity
import android.content.Context
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
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import com.dashieapp.Dashie.edition.brandName

/**
 * [UpdateBackend] for sideloaded / non-Play builds (the `sideload` flavor).
 *
 * Updates aren't available through any store, so this backend rolls its own:
 *  - [checkForUpdate] fetches a version manifest JSON from [manifestUrl] and
 *    compares its `versionCode` to this build's.
 *  - [startDownload] downloads the APK and verifies its SHA-256.
 *  - [installAndRestart] installs it via [ApkInstaller] (system PackageInstaller).
 *
 * Manifest JSON shape:
 * `{ "versionCode": 124, "versionName": "1.0.4", "apkUrl": "...", "sha256": "..." }`
 */
class SideloadUpdateBackend(
    context: Context,
    private val manifestUrl: String
) : UpdateBackend(context) {

    companion object {
        private const val TAG = "DashieUpdate"
    }

    private data class SideloadManifest(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notify: Boolean,
        val title: String?,
        val description: String?
    )

    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var manifest: SideloadManifest? = null
    private var downloadedApk: File? = null

    // A non-device-owner sideload install needs a human tap on the system
    // "Update Dashie?" prompt, so it can't run unattended ("Later" → snooze).
    // Only a device-owner installs silently — then "Later" can defer to 2 AM.
    override val supportsDeferredInstall: Boolean
        get() = ApkInstaller(context).isDeviceOwner()

    /** Set when an install was reached but blocked on the install-unknown-apps grant. */
    private var awaitingInstallPermission = false

    override fun checkForUpdate() {
        scope.launch {
            try {
                // resp.request.url is the FINAL request of the redirect chain, so this
                // captures where the manifest actually came from — not where we asked.
                var servedFrom = manifestUrl
                val json = http.newCall(Request.Builder().url(manifestUrl).build())
                    .execute().use { resp ->
                        servedFrom = resp.request.url.toString()
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.string()
                    } ?: throw IOException("empty manifest")

                val o = JSONObject(json)
                val m = SideloadManifest(
                    versionCode = o.getInt("versionCode"),
                    versionName = o.getString("versionName"),
                    apkUrl = o.getString("apkUrl"),
                    sha256 = o.getString("sha256"),
                    notify = o.optBoolean("notify", true),
                    title = o.optString("title").takeIf { it.isNotBlank() },
                    description = o.optString("description").takeIf { it.isNotBlank() }
                )
                manifest = m

                // Success marker (standing rule 2 — no silent drops). Without this the
                // path is silent unless an update happens to be available, so a healthy
                // check and one that never ran look identical, and the update path is
                // unverifiable in the field. Logging `servedFrom` makes redirect-following
                // directly observable: if it differs from manifestUrl the client followed
                // the hop. That matters since the 2026-08-18 brand swap put a
                // dashieapp.com -> heydashie.com redirect in front of every manifest and
                // APK URL baked into shipped builds — a client that stopped following
                // redirects would strand every sideload install, silently.
                Log.i(TAG, "Sideload manifest OK: served from $servedFrom " +
                    "(requested $manifestUrl), manifest vc${m.versionCode} (${m.versionName}), " +
                    "running vc${BuildConfig.VERSION_CODE} -> " +
                    if (m.versionCode > BuildConfig.VERSION_CODE) "update available" else "up to date")

                if (m.versionCode > BuildConfig.VERSION_CODE && !updateAvailable) {
                    updateAvailable = true
                    availableVersionName = m.versionName
                    shouldNotify = m.notify
                    releaseTitle = m.title
                    releaseDescription = m.description
                    Log.i(TAG, "Sideload update available: ${m.versionName} (vc${m.versionCode}, notify=${m.notify})")
                    if (m.notify) onUpdateAvailable?.invoke()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sideload update check failed: ${e.message}")
            }
        }
    }

    override fun startDownload(activity: Activity) {
        val m = manifest ?: return
        if (downloadInProgress) return
        downloadInProgress = true
        scope.launch {
            try {
                val apk = File(context.cacheDir, "dashie-update.apk")
                var servedFrom = m.apkUrl
                http.newCall(Request.Builder().url(m.apkUrl).build()).execute().use { resp ->
                    servedFrom = resp.request.url.toString()
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty APK body")
                    apk.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                val actual = sha256(apk)
                if (!actual.equals(m.sha256, ignoreCase = true)) {
                    Log.w(TAG, "SHA-256 mismatch — discarding APK (expected ${m.sha256}, got $actual)")
                    apk.delete()
                    downloadInProgress = false
                    onDownloadFailed?.invoke("verification failed")
                    return@launch
                }
                downloadedApk = apk
                downloadInProgress = false
                Log.i(TAG, "Sideload APK downloaded + verified (${apk.length()} bytes) " +
                    "from $servedFrom (requested ${m.apkUrl})")
                onReadyToInstall?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Sideload download failed: ${e.message}")
                downloadInProgress = false
                onDownloadFailed?.invoke(e.message ?: "network error")
            }
        }
    }

    override fun installAndRestart() {
        val apk = downloadedApk
        if (apk == null || !apk.exists()) {
            Log.i(TAG, "installAndRestart: no downloaded APK staged")
            return
        }
        val installer = ApkInstaller(context)
        if (!installer.canInstall()) {
            // Deep-link to the grant screen and remember to resume the install
            // when the host activity comes back (see onActivityResumed).
            awaitingInstallPermission = true
            Log.i(TAG, "Install-unknown-apps not granted — opening settings; install resumes on return")
            installer.requestInstallPermission()
            return
        }
        awaitingInstallPermission = false
        cancelInstallAlarm()  // clears the pending flag + any armed alarm
        // Flip the install-in-progress flag so the CC card surfaces an
        // "Installing..." state instead of looking idle while the system
        // confirmation prompt + actual install happen out-of-process.
        // Reset on the OS replacing the process; if the user dismisses
        // the system prompt without confirming, this stays true until
        // the user retries Install Now (which is a fine no-op).
        installInProgress = true
        installer.install(apk)
    }

    /**
     * "Update Tonight" was tapped — the install fires unattended at 2 AM, so
     * secure the install-unknown-apps grant now while the user is here.
     */
    override fun prepareForDeferredInstall(activity: Activity) {
        val installer = ApkInstaller(context)
        if (!installer.canInstall()) {
            Log.i(TAG, "Deferred install needs the install-unknown-apps grant — prompting now")
            android.widget.Toast.makeText(
                activity,
                "Allow ${context.brandName()} to install apps so tonight's update can apply.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            installer.requestInstallPermission()
        }
    }

    /**
     * Resume an install that was blocked on the install-unknown-apps grant.
     * Called when the host activity resumes — the user may have just granted
     * it. The APK is already downloaded, so this installs without re-fetching.
     */
    override fun onActivityResumed() {
        if (awaitingInstallPermission && ApkInstaller(context).canInstall()) {
            Log.i(TAG, "Install permission granted — resuming deferred install")
            installAndRestart()
        }
    }

    override fun cleanup() {
        scope.cancel()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = ins.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
