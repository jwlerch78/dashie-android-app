package com.dashieapp.Dashie.halite.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for the "Update Tonight" deferred-install alarm.
 *
 * Fired by AlarmManager at [UpdateBackend.INSTALL_HOUR]:00. Invokes the
 * static [onInstallTriggered] callback the running app registers. If the
 * callback is null (app not running), the persisted pending flag re-arms the
 * alarm on the next app launch — so the deferred install isn't lost.
 *
 * Independent of the daily-refresh and sleep schedules — "Update Tonight"
 * installs at a fixed hour regardless of how those features are configured.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DashieUpdate"
        const val ACTION_INSTALL_UPDATE = "com.dashieapp.Dashie.halite.ACTION_INSTALL_UPDATE"

        /** Set by DashieUpdateController while the app is running. */
        var onInstallTriggered: (() -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_UPDATE) return
        Log.i(TAG, "Deferred-install alarm fired")
        val callback = onInstallTriggered
        if (callback != null) {
            callback.invoke()
        } else {
            Log.i(TAG, "No install callback — deferred install will re-arm on next app launch")
        }
    }
}
