package com.dashieapp.Dashie.halite.update

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
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
 * [UpdateBackend] backed by Play In-App Updates (AppUpdateManager).
 *
 * Flexible-update flow (one-time Play consent dialog when the download starts):
 *  - [checkForUpdate] queries Play; [onUpdateAvailable] fires when found.
 *  - [startDownload] launches the Play consent dialog + background download.
 *  - [onReadyToInstall] fires when the download finishes.
 *  - [installAndRestart] applies the staged update (installs + restarts).
 *
 * Only functions on builds installed from Google Play — sideloaded/debug
 * builds simply report no update available. Sideload-flavor builds use
 * [SideloadUpdateBackend] instead.
 */
class PlayUpdateBackend(
    context: Context,
    /** Optional manifest URL — same shape as the amazon/sideload manifests
     *  ({ versionCode, versionName, notify, title, description }). Play's
     *  AppUpdateManager only exposes versionCode, so without this we
     *  can't populate the rich modal's title/description/version label.
     *  Empty string = skip the fetch and fall back to generic copy. */
    private val manifestUrl: String = ""
) : UpdateBackend(context) {

    companion object {
        private const val TAG = "DashieUpdate"
        /** Activity request code for the Play update flow (result is optional for FLEXIBLE). */
        const val REQUEST_CODE = 7301
    }

    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(context)
    private var cachedInfo: AppUpdateInfo? = null

    private val http = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Play's FLEXIBLE update flow can't be applied silently — `completeUpdate()`
     * shows a Play-managed restart prompt that requires the app to be alive
     * and visible. Scheduling a 2 AM unattended install doesn't reliably work
     * because:
     *  - If the app is killed/backgrounded by then, the 2 AM alarm's callback
     *    targets a dead activity reference and the install never fires.
     *  - Even if the app is alive, Play's completeUpdate() typically waits for
     *    user confirmation rather than silently restarting.
     *
     * So hide the "Tonight" button on Play — the middle banner button
     * relabels to "Later" (snoozes the banner ~24h) instead, which is an
     * honest representation of what we can actually deliver. Sideload's
     * device-owner path is the only backend that can promise a true
     * unattended install.
     */
    override val supportsDeferredInstall: Boolean = false

    private val installListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                downloadInProgress = false
                Log.i(TAG, "Update downloaded — ready to install")
                onReadyToInstall?.invoke()
            }
            InstallStatus.FAILED -> {
                downloadInProgress = false
                Log.w(TAG, "Update download failed (code ${state.installErrorCode()})")
                onDownloadFailed?.invoke("download failed")
            }
            InstallStatus.CANCELED -> {
                // User canceled mid-download (or Play canceled it). Fires our
                // status-banner cleanup + CC card "Update Available" reset,
                // mirroring the sideload PackageInstaller cancel path.
                downloadInProgress = false
                Log.i(TAG, "Update canceled by user")
                onDownloadFailed?.invoke("canceled")
            }
            else -> { /* PENDING / DOWNLOADING / INSTALLING — no action */ }
        }
    }

    /**
     * Forward [Activity.onActivityResult] for [REQUEST_CODE] — Play's
     * consent dialog returns its result here, BEFORE any
     * [InstallStateUpdatedListener] event fires. If the user dismisses
     * the consent dialog (RESULT_CANCELED), the listener never sees
     * the cancellation because no install session was committed; we
     * have to clean up state ourselves here. Wired from
     * [DashieUpdateController.handleActivityResult].
     */
    fun onActivityResult(requestCode: Int, resultCode: Int) {
        if (requestCode != REQUEST_CODE) return
        if (resultCode != Activity.RESULT_OK) {
            downloadInProgress = false
            Log.i(TAG, "Play update consent canceled (resultCode=$resultCode)")
            onDownloadFailed?.invoke("canceled")
        }
    }

    override fun checkForUpdate() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                cachedInfo = info
                val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                if (available && !updateAvailable) {
                    val playVc = info.availableVersionCode()
                    Log.i(TAG, "Flexible update available (versionCode $playVc)")
                    // Fetch the shared release-notes manifest so the modal
                    // can render the same rich UX as sideload/amazon — Play
                    // API only exposes versionCode. Falls back to generic
                    // copy if the fetch fails or the file's vc doesn't
                    // match Play's vc (e.g. Amazon shipped ahead of Play).
                    fetchManifestAndFire(playVc)
                }
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    onReadyToInstall?.invoke()
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Update check failed: ${e.message}")
            }
    }

    /** Fetch the release-notes manifest off-thread, populate metadata if
     *  vc matches Play's reported vc, then fire onUpdateAvailable on the
     *  main thread. Fires even if the fetch fails so the banner still
     *  appears (just without rich metadata). */
    private fun fetchManifestAndFire(playVc: Int) {
        if (manifestUrl.isBlank()) {
            updateAvailable = true
            onUpdateAvailable?.invoke()
            return
        }
        scope.launch {
            try {
                val json = http.newCall(Request.Builder().url(manifestUrl).build())
                    .execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.string()
                    } ?: throw IOException("empty manifest")

                val o = JSONObject(json)
                val manifestVc = o.optInt("versionCode", -1)
                if (manifestVc == playVc) {
                    availableVersionName = o.optString("versionName").takeIf { it.isNotBlank() }
                    releaseTitle = o.optString("title").takeIf { it.isNotBlank() }
                    releaseDescription = o.optString("description").takeIf { it.isNotBlank() }
                    shouldNotify = o.optBoolean("notify", true)
                    Log.i(TAG, "Play release notes loaded for vc$manifestVc (${availableVersionName})")
                } else {
                    Log.i(TAG, "Manifest vc$manifestVc doesn't match Play vc$playVc — using generic copy")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Release-notes fetch failed: ${e.message}")
            }
            // Fire onUpdateAvailable on the main thread regardless of
            // fetch outcome — the banner should appear even if we
            // couldn't load rich metadata.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                updateAvailable = true
                onUpdateAvailable?.invoke()
            }
        }
    }

    override fun startDownload(activity: Activity) {
        val info = cachedInfo
        if (info == null || downloadInProgress) return
        manager.registerListener(installListener)
        try {
            manager.startUpdateFlowForResult(
                info,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build(),
                REQUEST_CODE
            )
            downloadInProgress = true
            Log.i(TAG, "Update flow started")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start update flow: ${e.message}")
        }
    }

    override fun installAndRestart() {
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                cachedInfo = info
                if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    Log.i(TAG, "Completing update — app will restart")
                    cancelInstallAlarm()  // clears the pending flag + any armed alarm
                    manager.completeUpdate()
                } else {
                    Log.i(TAG, "installAndRestart: no update staged (status ${info.installStatus()})")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "installAndRestart: appUpdateInfo failed: ${e.message}")
            }
    }

    override fun cleanup() {
        try {
            manager.unregisterListener(installListener)
        } catch (_: Exception) {
        }
        scope.cancel()
    }
}
