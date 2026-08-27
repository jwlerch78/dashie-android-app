package com.dashieapp.Dashie.halite

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dashieapp.Dashie.edition.brandName
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * One-stop helper for the Device Admin / DevicePolicyManager checks
 * that were duplicated across ~13 files (sidebar dialogs, settings,
 * MainActivity, kiosk/lifecycle controllers, JS bridge delegates,
 * screen hardware controller, etc).
 *
 * Always pairs `getSystemService(DEVICE_POLICY_SERVICE)` with the
 * `DashieDeviceAdminReceiver` ComponentName so callers don't have to
 * remember to do both. All methods are null-safe — they return false
 * (or null) when DPM isn't available rather than throwing.
 */
object DeviceAdminHelper {

    private const val TAG = "DeviceAdminHelper"

    /**
     * The two reasons this app ever asks for Device Admin, brand-resolved.
     *
     * 🔴 BRAND-TRIAGED (A, 2026-08-05, sweep pass 2). These were SIX copies of one sentence
     * across six files, spelled three different ways — "Dashie", "Dashie Lite", "Dashie
     * Kiosk". Substituting the brand into each copy would have left six copies and produced
     * "Chickadee Lite" / "Chickadee Kiosk": **tier names that do not exist in that edition**,
     * i.e. the false-statement failure again in a milder form. So the tier word is dropped,
     * not translated, and the sentence is shared — standing rule 1, share before you mirror.
     * This is also a fix for the Dashie edition, which has not shipped a "Lite" tier in months.
     */
    fun defaultExplanation(context: Context): String =
        "${context.brandName()} needs Device Admin permission to control screen sleep/wake."

    /** The reason used by every screen-off prompt (the six former copies). */
    fun screenOffExplanation(context: Context): String =
        "${context.brandName()} needs Device Admin permission to turn off the screen hardware."

    /** The DevicePolicyManager for this context, or null if unavailable. */
    fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    /** ComponentName for our DashieDeviceAdminReceiver. */
    fun component(context: Context): ComponentName =
        ComponentName(context, DashieDeviceAdminReceiver::class.java)

    /** True iff Device Admin is currently active for Dashie. */
    fun isActive(context: Context): Boolean =
        dpm(context)?.isAdminActive(component(context)) == true

    /** True iff this package is set as the Device Owner (provisioning state). */
    fun isDeviceOwnerApp(context: Context): Boolean =
        dpm(context)?.isDeviceOwnerApp(context.packageName) == true

    /**
     * Build the intent that prompts the user to enable Device Admin.
     * Caller passes [explanation] to customize the system dialog's reason text.
     */
    fun buildActivationIntent(
        context: Context,
        explanation: String = defaultExplanation(context)
    ): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
    }

    /**
     * Self-grant the Device-Owner keep-alive lever available to a regular DO app
     * so the camera/motion foreground service survives aggressive OEM power
     * management (e.g. Honor MagicOS) while the screen is off.
     *
     * No-op unless we are the Device Owner. Version-gated and try/caught so a
     * failure — or an OEM that ignores the AOSP exemption — never crashes
     * startup. Safe to call repeatedly.
     *
     * Uses [DevicePolicyManager.setUserControlDisabledPackages] (API 31+): the
     * package can't be force-stopped by the user, and from Android 14 is also
     * exempt from background/standby restrictions — directly targeting the
     * "app gets fully killed" symptom and helping keep the camera FGS resident.
     *
     * Note: the more direct setApplicationExemptions(POWER_RESTRICTIONS) is a
     * SystemApi gated behind MANAGE_DEVICE_POLICY_APP_EXEMPTIONS, which a normal
     * Device Owner can't hold — so it's deliberately not used here. The camera
     * FGS background-survival is handled separately by gating the
     * FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED bit on Device Owner status
     * (see DashieApiService.applyForegroundServiceType).
     */
    fun applyDeviceOwnerPowerExemptions(context: Context) {
        val dpm = dpm(context) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) {
            PersistentLog.info(TAG, "DO power exemptions skipped — not device owner")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                dpm.setUserControlDisabledPackages(component(context), listOf(context.packageName))
                PersistentLog.info(TAG, "DO setUserControlDisabledPackages applied")
            } catch (e: Exception) {
                PersistentLog.warn(TAG, "DO setUserControlDisabledPackages failed: ${e.message}")
            }
        }
    }

    /**
     * Lock the screen via [DevicePolicyManager.lockNow]. No-op + returns false
     * if Device Admin isn't active or [SecurityException] is thrown.
     */
    fun lockNow(context: Context): Boolean {
        val dpm = dpm(context) ?: return false
        if (!dpm.isAdminActive(component(context))) return false
        return try {
            dpm.lockNow()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "lockNow SecurityException: ${e.message}")
            false
        }
    }
}
