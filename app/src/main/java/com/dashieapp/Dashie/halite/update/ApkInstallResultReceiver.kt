package com.dashieapp.Dashie.halite.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the [PackageInstaller] commit status for a sideload APK install
 * (see [ApkInstaller]). Manifest-registered so the result is delivered even
 * if the install spans an activity teardown.
 *
 * - STATUS_PENDING_USER_ACTION → launch the system "Update Dashie?" prompt.
 * - STATUS_SUCCESS → the OS relaunches the app on the new version.
 * - failure → logged.
 */
class ApkInstallResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DashieUpdate"

        /** Fires (any thread) when an install attempt resolves (success
         *  or failure), but NOT for STATUS_PENDING_USER_ACTION which is
         *  an intermediate event. Lets [DashieUpdateController] clear
         *  the installInProgress flag + the status banner when the user
         *  cancels the system "Install Dashie?" prompt or the install
         *  fails for any other reason. Status codes are from
         *  [PackageInstaller] (STATUS_SUCCESS, STATUS_FAILURE_ABORTED,
         *  STATUS_FAILURE_BLOCKED, etc.). */
        var onInstallFinished: ((status: Int, message: String?) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                    Log.i(TAG, "Install needs user confirmation — launching system prompt")
                } else {
                    Log.w(TAG, "Install pending user action but no confirm intent")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The OS doesn't auto-reopen a self-updated app — relaunch it
                // ourselves so the kiosk comes straight back on the new version
                // instead of stranding the user on the launcher.
                Log.i(TAG, "Update installed — relaunching Dashie")
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                } else {
                    Log.w(TAG, "No launch intent — cannot relaunch after update")
                }
                onInstallFinished?.invoke(status, msg)
            }
            else -> {
                Log.w(TAG, "APK install failed (status $status): $msg")
                onInstallFinished?.invoke(status, msg)
            }
        }
    }
}
