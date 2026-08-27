package com.dashieapp.Dashie.devicecontrols

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Launches various Android settings screens and provides location/timezone utilities.
 */
class SettingsLauncher(private val context: Context) {

    companion object {
        private const val TAG = "SettingsLauncher"
    }

    /**
     * Open the main Android System Settings
     */
    fun openSystemSettings() {
        Log.i(TAG, "⚙️ Opening System Settings (main)")
        context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Open the system Date & Time settings
     */
    fun openDateTimeSettings() {
        Log.i(TAG, "🕐 Opening Date & Time settings")
        context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Open the system WiFi settings (network picker)
     */
    fun openWifiSettings() {
        Log.i(TAG, "📶 Opening WiFi settings")
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Open the system Location settings
     */
    fun openLocationSettings() {
        Log.i(TAG, "📍 Opening Location settings")
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Open the app-specific settings page (for permissions)
     */
    fun openAppSettings() {
        Log.i(TAG, "⚙️ Opening App Settings")
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Open the per-app "install unknown apps" permission screen for Dashie.
     * Used by the sideload self-updater so the user can grant install access
     * before an APK update. On API < 26 (no per-app screen) falls back to the
     * Security settings page.
     */
    fun openInstallUnknownAppsSettings() {
        Log.i(TAG, "📦 Opening Install Unknown Apps settings")
        val intent = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        context.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    /**
     * Open the device's built-in ("system") launcher for a one-shot visit, so a
     * user whose default home app is Dashie can temporarily reach other apps
     * WITHOUT changing the default launcher.
     *
     * A bare CATEGORY_HOME intent resolves straight back to Dashie, so instead we
     * enumerate the OTHER home apps and launch one directly (no CATEGORY_HOME on
     * the launch → no "Just once / Always" home picker). More than one qualifies
     * → let the user choose; none visible → fall back to the home-role settings
     * screen. Needs the CATEGORY_HOME <queries> entry in the manifest to see
     * other launchers on Android 11+.
     */
    fun openSystemLauncher() {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        // Exclude Dashie itself and com.android.settings (its FallbackHome is a
        // transient boot-time home that immediately hands back to the default
        // launcher — launching it just bounces straight back to Dashie).
        val launchers = pm.queryIntentActivities(homeIntent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName && it.packageName != "com.android.settings" }

        when {
            launchers.size == 1 -> launchHomeActivity(launchers[0])
            launchers.size > 1 -> {
                val labels = launchers.map { it.loadLabel(pm).toString() }.toTypedArray()
                android.app.AlertDialog.Builder(context)
                    .setTitle("Open Launcher")
                    .setItems(labels) { _, i -> launchHomeActivity(launchers[i]) }
                    .show()
            }
            else -> {
                Log.i(TAG, "🏠 No other launcher visible — opening home-role picker")
                val picker = Intent(Settings.ACTION_HOME_SETTINGS)
                if (pm.resolveActivity(picker, 0) != null) {
                    context.startActivity(picker.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } else {
                    context.startActivity(homeIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }
        }
    }

    /** Launch a launcher's activity directly as a one-shot visit (no CATEGORY_HOME). */
    private fun launchHomeActivity(ai: android.content.pm.ActivityInfo) {
        Log.i(TAG, "🏠 Opening system launcher: ${ai.packageName}")
        context.startActivity(Intent(Intent.ACTION_MAIN).apply {
            setClassName(ai.packageName, ai.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    /**
     * Get the device's current timezone ID
     * @return Timezone ID like "America/New_York", "Asia/Shanghai", etc.
     */
    fun getDeviceTimezone(): String {
        val tz = TimeZone.getDefault().id
        Log.d(TAG, "🕐 getDeviceTimezone(): $tz")
        return tz
    }

    /**
     * Check if location services are enabled
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                       locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        Log.d(TAG, "📍 isLocationEnabled(): $isEnabled")
        return isEnabled
    }

    /**
     * Check if the app has location permission
     */
    fun hasLocationPermission(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "📍 hasLocationPermission(): fine=$hasFine, coarse=$hasCoarse")
        return hasFine || hasCoarse
    }
}
