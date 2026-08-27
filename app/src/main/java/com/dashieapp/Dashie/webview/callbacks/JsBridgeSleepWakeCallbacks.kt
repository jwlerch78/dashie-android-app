package com.dashieapp.Dashie.webview.callbacks

import android.util.Log
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeSleepWakeCallbacks(
    private val screenControllerProvider: () -> HaliteScreenController?
) : DashieJSBridge.SleepWakeCallbacks {

    override fun onSleepNow() {
        Log.i("DashieJSBridge", "🌙 onSleepNow() callback - triggering screen off")
        screenControllerProvider()?.screenOff()
    }

    override fun onWakeNow() {
        Log.i("DashieJSBridge", "☀️ onWakeNow() callback - triggering screen on")
        screenControllerProvider()?.screenOn()
    }

    override fun onSleepSettingsChanged() {
        Log.i("DashieJSBridge", "🌙 onSleepSettingsChanged() callback - rescheduling alarms")
        screenControllerProvider()?.onSleepSettingsChanged()
    }
}
