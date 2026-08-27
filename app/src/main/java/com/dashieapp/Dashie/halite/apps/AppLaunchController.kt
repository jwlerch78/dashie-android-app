package com.dashieapp.Dashie.halite.apps

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.dashieapp.Dashie.halite.screensaver.FloatingReturnButton

/**
 * Launches an external app in response to a voice "open <app>" command.
 *
 * Resolves the spoken name via [AppLaunchRegistry], stops kiosk lock-task
 * (screen pinning) if active so the launch isn't blocked, starts the app, and
 * shows a [FloatingReturnButton] so the user can get back to Dashie. The return
 * button uses moveTaskToFront (no dashboard reload), so we don't need to touch
 * the screensaver's external-app state machine.
 *
 * The happy-path spoken response ("Opening Netflix.") is produced by the intent
 * classifier; this controller only surfaces failures (not installed / unknown)
 * as a Toast.
 */
class AppLaunchController(private val context: Context) {
    companion object {
        private const val TAG = "AppLaunchController"

        /** Process-lifetime handle set from VoiceComponentWiring, so the realtime
         *  tool dispatcher (which has only a bare Context) can reach the
         *  Activity-scoped controller. Mirrors MusicVoiceTool.instance. */
        @Volatile
        var instance: AppLaunchController? = null
    }

    private var returnButton: FloatingReturnButton? = null

    init {
        // Cheap, once per wiring — helps us understand device DRM in the field.
        DrmCapabilities.logCapabilities()
    }

    /**
     * Resolve and launch. [knownPackage]/[knownLabel] are set when the classifier
     * already matched a curated app; otherwise [query] is resolved dynamically
     * against installed apps. Must be called on the main thread.
     */
    fun openApp(query: String, knownPackage: String = "", knownLabel: String = "") {
        val resolved = if (knownPackage.isNotEmpty()) {
            AppLaunchRegistry.ResolvedApp(
                knownLabel.ifEmpty { knownPackage },
                knownPackage,
                AppLaunchRegistry.isInstalled(context, knownPackage)
            )
        } else {
            AppLaunchRegistry.resolve(context, query)
        }

        if (resolved == null) {
            Log.w(TAG, "No app matched for '$query'")
            toast("I couldn't find an app called $query")
            return
        }
        if (!resolved.installed) {
            Log.w(TAG, "App not installed: ${resolved.label} (${resolved.packageName})")
            toast("${resolved.label} isn't installed")
            return
        }
        launchPackage(resolved.packageName, resolved.label)
    }

    private fun launchPackage(packageName: String, label: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            Log.e(TAG, "No launch intent for $packageName")
            toast("$label isn't installed")
            return
        }

        // Stop lock task (screen pinning) so the launch isn't blocked in kiosk mode.
        // Only if actually pinned — Fire tablets / non-pinned devices skip this.
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
                (context as? Activity)?.stopLockTask()
                Log.i(TAG, "Stopped lock task for app launch")
            }
        } catch (_: Exception) { /* not pinned — safe to ignore */ }

        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Log.i(TAG, "Launched $label ($packageName)")
            showReturnButton()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
            toast("Couldn't open $label")
        }
    }

    private fun showReturnButton() {
        if (returnButton == null) {
            returnButton = FloatingReturnButton(context).apply {
                onReturnClicked = { Log.i(TAG, "Returned to Dashie from launched app") }
            }
        }
        returnButton?.show()
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
