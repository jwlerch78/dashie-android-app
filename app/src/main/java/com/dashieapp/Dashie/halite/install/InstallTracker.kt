package com.dashieapp.Dashie.halite.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Registers the device install on first app launch.
 * Fires once, stores the install_token for future check-ins.
 *
 * Call [registerIfNeeded] from MainActivity.onCreate().
 */
class InstallTracker(private val context: Context) {
    companion object {
        private const val TAG = "InstallTracker"
        private const val PREFS_NAME = "dashie_install"
        private const val KEY_REGISTERED = "install_registered"
        internal const val KEY_INSTALL_TOKEN = "install_token"

        /**
         * Returns the installer package name (e.g. "com.android.vending" for
         * Play, "com.amazon.venezia" for Amazon, system installer for sideload
         * via UI, null for ADB or unknown). Shared by registration + check-in.
         */
        fun detectInstallSource(context: Context): String? {
            return try {
                val pm = context.packageManager
                val pkg = context.packageName
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(pkg).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(pkg)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** The install token, available after successful registration. */
    val installToken: String?
        get() = prefs.getString(KEY_INSTALL_TOKEN, null)

    /**
     * Register this device if not already registered.
     * Safe to call on every app launch — returns immediately if already registered.
     */
    fun registerIfNeeded() {
        if (prefs.getBoolean(KEY_REGISTERED, false)) return

        Log.i(TAG, "📱 First launch detected — registering install")

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: return

        // Hardware-tied identifier (Widevine) — survives reinstall + package
        // change. Sent alongside android_id so register-install can recognize
        // the same physical device across packages.
        val stableDeviceId = com.dashieapp.Dashie.util.StableDeviceId.read(context)
            .takeIf { it.isNotEmpty() && it != androidId }

        val networkId = NetworkFingerprint.generate(context)

        val data = InstallData(
            androidId = androidId,
            stableDeviceId = stableDeviceId,
            deviceModel = Build.MODEL,
            deviceBrand = Build.MANUFACTURER,
            deviceProduct = Build.PRODUCT,
            screenWidthDp = context.resources.configuration.screenWidthDp,
            screenHeightDp = context.resources.configuration.screenHeightDp,
            androidVersion = Build.VERSION.SDK_INT,
            appVersion = BuildConfig.VERSION_NAME,
            buildFlavor = BuildConfig.FLAVOR,
            networkId = networkId,
            countryCode = java.util.Locale.getDefault().country,
            timezone = java.util.TimeZone.getDefault().id,
            installSource = detectInstallSource(context)
        )

        // Intentionally unscoped: one-shot install registration that should complete
        // regardless of UI lifecycle; holds no UI references.
        CoroutineScope(Dispatchers.IO).launch {
            val result = InstallRegistrationClient.register(data)
            if (result.success && result.installToken != null) {
                prefs.edit()
                    .putBoolean(KEY_REGISTERED, true)
                    .putString(KEY_INSTALL_TOKEN, result.installToken)
                    .apply()
                Log.i(TAG, "✅ Install registered, token stored")
            } else {
                Log.w(TAG, "⚠️ Registration failed — will retry on next launch")
            }
        }
    }
}
