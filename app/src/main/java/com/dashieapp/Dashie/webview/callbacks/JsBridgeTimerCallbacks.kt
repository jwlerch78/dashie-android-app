package com.dashieapp.Dashie.webview.callbacks

import android.util.Log
import com.dashieapp.Dashie.halite.timer.TimerData
import com.dashieapp.Dashie.halite.timer.TimerOverlayManager
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeTimerCallbacks(
    private val timerManagerProvider: () -> TimerOverlayManager?,
    private val runOnUiThread: (Runnable) -> Unit
) : DashieJSBridge.TimerCallbacks {

    override fun onTimerCreated(timerJson: String) {
        Log.i("DashieJSBridge", "⏱️ onTimerCreated callback: $timerJson")
        runOnUiThread {
            try {
                timerManagerProvider()?.createTimer(TimerData.fromJson(timerJson))
            } catch (e: Exception) {
                Log.e("DashieJSBridge", "❌ Failed to create timer", e)
            }
        }
    }

    override fun onTimerUpdated(timerJson: String) {
        Log.v("DashieJSBridge", "⏱️ onTimerUpdated callback: $timerJson")
        runOnUiThread {
            try {
                timerManagerProvider()?.updateTimer(TimerData.fromJson(timerJson))
            } catch (e: Exception) {
                Log.e("DashieJSBridge", "❌ Failed to update timer", e)
            }
        }
    }

    override fun onTimerCompleted(timerJson: String) {
        Log.i("DashieJSBridge", "⏱️ onTimerCompleted callback: $timerJson")
        runOnUiThread {
            try {
                timerManagerProvider()?.updateTimer(TimerData.fromJson(timerJson))
            } catch (e: Exception) {
                Log.e("DashieJSBridge", "❌ Failed to handle timer completion", e)
            }
        }
    }

    override fun onTimerCancelled(timerId: String) {
        Log.i("DashieJSBridge", "⏱️ onTimerCancelled callback: $timerId")
        runOnUiThread { timerManagerProvider()?.removeTimer(timerId) }
    }
}
