package com.dashieapp.Dashie.halite

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dashieapp.Dashie.edition.brandName

/**
 * Device Admin Receiver.
 *
 * Required for screen off functionality using DevicePolicyManager.lockNow().
 * When enabled as a Device Admin, the app can turn off the screen hardware
 * (not just show a black overlay).
 */
class DashieDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DashieDeviceAdmin"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled - screen off feature now available")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(TAG, "Device Admin disabled - screen off feature no longer available")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // BRAND-TRIAGED (A, 2026-08-05): shown by the SYSTEM settings UI when the user tries to
        // revoke Device Admin, so it is reachable on any edition. Tier word dropped, not
        // translated — see DeviceAdminHelper.defaultExplanation.
        return "Disabling Device Admin will prevent ${context.brandName()} from turning off the screen."
    }
}
