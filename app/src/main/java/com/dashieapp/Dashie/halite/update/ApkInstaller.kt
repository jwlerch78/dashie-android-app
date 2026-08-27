package com.dashieapp.Dashie.halite.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.dashieapp.Dashie.devicecontrols.SettingsLauncher
import java.io.File

/**
 * Installs an APK via the system [PackageInstaller] — the sideload self-update
 * path (Fire / non-Play devices). The system shows an "Update Dashie?"
 * confirmation; the install result is broadcast to [ApkInstallResultReceiver].
 *
 * Requires the `REQUEST_INSTALL_PACKAGES` permission (sideload flavor only)
 * plus a one-time user grant of "install unknown apps" — [canInstall] reports
 * whether that's been granted, [requestInstallPermission] deep-links to it.
 */
class ApkInstaller(private val context: Context) {

    companion object {
        private const val TAG = "DashieUpdate"
        const val ACTION_INSTALL_STATUS = "com.dashieapp.Dashie.halite.update.INSTALL_STATUS"
    }

    /** True once the user has granted "install unknown apps" for Dashie. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /**
     * True if Dashie is the device owner — a device-owner app installs via
     * [PackageInstaller] silently (no STATUS_PENDING_USER_ACTION prompt), so
     * an unattended deferred install is possible. Otherwise the install always
     * needs a human tap on the system "Update Dashie?" confirmation.
     */
    fun isDeviceOwner(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
            as? android.app.admin.DevicePolicyManager
        return dpm?.isDeviceOwnerApp(context.packageName) == true
    }

    /** Deep-link to the "install unknown apps" permission screen. */
    fun requestInstallPermission() {
        SettingsLauncher(context).openInstallUnknownAppsSettings()
    }

    /**
     * Install [apk] via a [PackageInstaller] session. The system surfaces an
     * "Update Dashie?" confirmation (unless the app is a device owner); the
     * STATUS_PENDING_USER_ACTION / SUCCESS / FAILURE result is delivered to
     * [ApkInstallResultReceiver]. On success the app is restarted by the OS.
     */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("dashie.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val statusPi = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName),
                flags
            )
            session.commit(statusPi.intentSender)
        }
        Log.i(TAG, "APK install session committed (id=$sessionId)")
    }
}
