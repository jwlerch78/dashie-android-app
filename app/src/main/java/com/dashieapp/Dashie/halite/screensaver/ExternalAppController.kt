package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

/**
 * "App" screensaver mode — launches a configured external Android package
 * and shows a floating return button so the user can come back to Dashie.
 *
 * The state machine here is unusual: while an external app is in front of
 * Dashie, [isActive] stays true and ScreenDimmer's `isDimmed` also stays
 * true (so screensaver-state queries report correctly). The flag is only
 * cleared when the host calls [markReturned] from the Activity onResume
 * path. This is what lets MainActivity skip the URL-change check that
 * would otherwise trigger a page reload on every return-from-app.
 */
class ExternalAppController(
    private val context: Context,
    private val prefs: ScreensaverPreferences,
    private val dimOverlay: ViewGroup,
    private val backgroundViewProvider: () -> View?
) {
    companion object {
        private const val TAG = "ExternalAppController"
    }

    private var isExternalAppActive = false
    private var floatingReturnButton: FloatingReturnButton? = null

    fun isActive(): Boolean = isExternalAppActive

    /**
     * Launch the configured external app. Falls back to dim mode (revealing
     * backgroundView) if no package is configured, package isn't installed,
     * or the launch throws.
     */
    fun launch() {
        val packageName = prefs.launchAppPackage
        if (packageName.isEmpty()) {
            Log.w(TAG, "No app configured for launch")
            Toast.makeText(context, "No app configured", Toast.LENGTH_SHORT).show()
            backgroundViewProvider()?.visibility = View.VISIBLE
            return
        }

        Log.i(TAG, "Launching external app: $packageName")

        try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                // Stop lock task (screen pinning) so the external app can launch.
                // Without this, Android blocks the launch with "app is pinned" toast.
                // Only needed if actually pinned — Fire tablets and devices with
                // "Pin App When Locked" disabled use immersive mode only (no pinning).
                // Lock task is re-applied when user returns to Dashie via onResume().
                try {
                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    if (am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                        (context as? android.app.Activity)?.stopLockTask()
                        Log.i(TAG, "Stopped lock task for external app launch")
                    }
                } catch (e: Exception) {
                    // Not pinned or Fire tablet (skips pinning) - safe to ignore
                }

                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.i(TAG, "Successfully launched: $packageName")

                // Hide the dim overlay since the app is now visible above us.
                dimOverlay.visibility = View.GONE

                // isDimmed (in ScreenDimmer) stays TRUE while the external app
                // is running — screensaver-state queries should still report
                // active. Only this controller's flag tracks the app-vs-dim
                // distinction.
                isExternalAppActive = true

                showFloatingReturnButton()

                // Note: Motion detection for external app mode is handled by
                // RTSP stream (if enabled) via onRtspMotionDetected in
                // MainActivity. We don't start a separate camera detector
                // because RTSP already has the camera and would conflict.
            } else {
                Log.e(TAG, "App not found: $packageName")
                Toast.makeText(context, "App not installed: $packageName", Toast.LENGTH_SHORT).show()
                backgroundViewProvider()?.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
            Toast.makeText(context, "Failed to launch app", Toast.LENGTH_SHORT).show()
            backgroundViewProvider()?.visibility = View.VISIBLE
        }
    }

    /**
     * Called when Dashie comes back to foreground after the external app.
     * Clears the active flag and hides the return button. Caller is
     * responsible for waking the screen + restarting the inactivity timer.
     */
    fun markReturned() {
        if (isExternalAppActive) {
            Log.i(TAG, "External app return — clearing flag + hiding return button")
            isExternalAppActive = false
            hideFloatingReturnButton()
        }
    }

    /**
     * Trigger return from external app via motion or API call. Brings
     * Dashie to the foreground via the floating button; state cleanup
     * happens in [markReturned] driven by the Activity onResume path.
     */
    fun triggerReturn() {
        if (isExternalAppActive) {
            Log.i(TAG, "Triggering return from external app (motion or API)")
            floatingReturnButton?.triggerReturn()
        }
    }

    fun destroy() {
        floatingReturnButton?.destroy()
        floatingReturnButton = null
    }

    private fun showFloatingReturnButton() {
        if (floatingReturnButton == null) {
            floatingReturnButton = FloatingReturnButton(context)
            // The button just brings Dashie to the foreground and hides
            // itself — onResume in MainActivity detects the active flag
            // and drives the rest of the cleanup (which deliberately
            // skips the URL-change check that would cause a page reload).
            floatingReturnButton?.onReturnClicked = {
                Log.i(TAG, "Floating button clicked — Dashie brought to foreground")
            }
        }
        floatingReturnButton?.show()
    }

    private fun hideFloatingReturnButton() {
        floatingReturnButton?.hide()
    }
}
