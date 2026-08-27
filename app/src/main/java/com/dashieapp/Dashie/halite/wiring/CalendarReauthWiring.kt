package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import com.dashieapp.Dashie.halite.HaliteComponentRegistry

/**
 * Wires the JsBridgeReauthDelegate's callbacks to the
 * [CalendarReauthOverlayManager] in the registry.
 *
 * Kept in its own file (rather than folded into SensorComponentWiring)
 * per the feature-owner wiring pattern — calendar reauth is a
 * standalone concern with three small bridge methods, no need to grow
 * the central wiring file.
 */
object CalendarReauthWiring {
    private const val TAG = "CalendarReauthWiring"

    fun wire(registry: HaliteComponentRegistry) {
        val jsBridge = registry.jsBridge ?: run {
            Log.w(TAG, "JS bridge not ready; reauth overlay callbacks NOT wired")
            return
        }

        jsBridge.reauthDelegate.onReauthPillBounds = { slotId, _, x, y, w, h ->
            registry.calendarReauthOverlayManager.setBounds(slotId, x, y, w, h)
        }
        jsBridge.reauthDelegate.onReauthPillRemoved = { slotId ->
            registry.calendarReauthOverlayManager.removeBounds(slotId)
        }
        jsBridge.reauthDelegate.onReauthPillState = { count, soleEmail ->
            registry.calendarReauthOverlayManager.setCount(count, soleEmail)
        }
        Log.i(TAG, "Calendar reauth overlay callbacks wired")
    }
}
