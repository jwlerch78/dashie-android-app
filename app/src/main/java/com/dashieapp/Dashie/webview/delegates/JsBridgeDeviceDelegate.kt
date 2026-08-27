package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.api.DashieApiPreferences
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.LightSensorBrightnessController
import com.dashieapp.Dashie.util.DeviceInfoHelper
import com.dashieapp.Dashie.util.DiagnosticToastController
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Delegate handling device-related JavaScript bridge methods.
 * Includes: Auto-brightness, device info, diagnostic toasts,
 * kiosk/immersive mode, dark mode, and app lifecycle.
 */
class JsBridgeDeviceDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?,
    private val deviceControls: DeviceControlsCoordinator
) {
    companion object {
        private const val TAG = "JsBridgeDevice"
    }

    // Lazy-initialized DashieApiPreferences for device name storage
    private val dashieApiPrefs by lazy {
        DashieApiPreferences(context)
    }

    // Callbacks
    var onExitApp: (() -> Unit)? = null
    var onRestartApp: (() -> Unit)? = null
    var onSoftRestartApp: (() -> Unit)? = null
    var onShowExitConfirmation: (() -> Unit)? = null
    var onEnableImmersiveMode: (() -> Unit)? = null
    var onDisableImmersiveMode: (() -> Unit)? = null
    var isKioskModeEnabled: (() -> Boolean)? = null
    var onSetDarkMode: ((Boolean) -> Unit)? = null
    var onOpenWifiSetup: (() -> Unit)? = null
    var onOpenAutoBrightnessSettings: (() -> Unit)? = null

    // Light sensor controller provider for custom auto-brightness
    var lightSensorProvider: (() -> LightSensorBrightnessController?)? = null

    // ============================================
    // App Lifecycle Methods
    // ============================================

    fun exitApp() {
        onExitApp?.invoke()
    }

    fun showExitConfirmation() {
        Log.i(TAG, "showExitConfirmation()")
        webView.post { onShowExitConfirmation?.invoke() }
    }

    /** Item 23: 3-button dialog for X on the sign-in screen when HA is
     *  configured. Cancel / Close Sign-in / Exit App. */
    var onShowSignInClose: (() -> Unit)? = null

    fun showSignInCloseConfirmation() {
        Log.i(TAG, "showSignInCloseConfirmation()")
        webView.post { onShowSignInClose?.invoke() }
    }

    fun restartApp() {
        Log.i(TAG, "restartApp()")
        onRestartApp?.invoke()
    }

    fun softRestartApp() {
        Log.i(TAG, "softRestartApp()")
        onSoftRestartApp?.invoke()
    }

    fun clearCache() {
        Log.i(TAG, "clearCache()")
        webView.post {
            webView.clearCache(true)
            // The JS Clear Cache path reloads THIS same WebView (no restart), so
            // force the imminent location.reload() to bypass the cache —
            // clearCache(true) is async and races the reload. onPageFinished
            // restores LOAD_DEFAULT so later navigation is cached again.
            webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
    }

    // ============================================
    // Device Type & Version Info
    // ============================================

    fun getDeviceType(): String {
        val hasLeanback = context.packageManager.hasSystemFeature("android.software.leanback")
        val hasTelevision = context.packageManager.hasSystemFeature("android.hardware.type.television")
        val isTv = hasLeanback || hasTelevision
        return if (isTv) "tv" else "mobile"
    }

    /**
     * Physical-to-CSS pixel ratio for JS↔Kotlin coordinate conversion.
     *
     * window.devicePixelRatio in the WebView is unreliable on some Fire TV
     * devices (observed: dpr=4 on a 1080p Fire TV that should report 2),
     * which produces a 2× mismatch when JS multiplies CSS bounds by dpr
     * to send physical px to Kotlin, OR divides Kotlin's physical px by
     * dpr to get CSS px. JS pixel-ratio.js calls this when available so
     * coordinates round-trip correctly. Returns Android's authoritative
     * display density (e.g. 2.0 for xhdpi 1080p, 3.0 for xxhdpi).
     */
    fun getDisplayDensity(): Float {
        return context.resources.displayMetrics.density
    }

    fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * Distribution channel for this APK ("play" / "amazon" / "sideload" /
     * "staging" / "local"). Used by the JS login page to hide the App
     * Reviewer Access link on the sideload flavor (end-user channel, not
     * a reviewer surface).
     */
    fun getBuildChannel(): String = BuildConfig.BUILD_CHANNEL

    /**
     * Widevine DRM capability of this device, as JSON:
     * {"widevine":"L1"|"L3"|"unknown","hardwareDrm":Bool,"netflixHd":Bool}.
     * L3 (software-only) can't do Netflix/Prime HD and often flickers on cheap
     * SoCs. Lets a diagnostics surface answer "will Netflix HD work here?".
     */
    fun getDrmInfo(): String = com.dashieapp.Dashie.halite.apps.DrmCapabilities.toJson()

    fun getAppVersionInfo(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName = pInfo.versionName ?: "unknown"
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            org.json.JSONObject(mapOf(
                "versionName" to versionName,
                "versionCode" to versionCode,
                "packageName" to context.packageName,
                "environment" to BuildConfig.ENVIRONMENT,
                "baseUrl" to BuildConfig.BASE_URL
            )).toString()
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }
    }

    /**
     * The Supabase project this APK is built against — **the authoritative answer**, from the
     * build flavor (local/staging → the dev project, prod → the prod project).
     *
     * WHY THIS EXISTS (Kiosk Real Login, Phase 2): JS picks its Supabase project by sniffing
     * `window.location.hostname` (`auth-config.js`): `dev.` / `local.` / `localhost` → dev,
     * **everything else → PRODUCTION**. That works for a browser on dev.dashieapp.com. It is
     * catastrophically wrong for a KIOSK: the kiosk shell is served from the Home Assistant
     * origin (e.g. `http://192.168.1.50:8123`) or the virtual `dashie-kiosk-overlay.local`,
     * neither of which matches — so a **staging/local APK's kiosk would read and write the
     * PRODUCTION database**. Hostname is simply not a valid signal for a device whose page is
     * hosted by the user's own HA box.
     *
     * The flavor is. This exposes it so JS can prefer it over the hostname guess.
     * Additive + feature-detected on the JS side, so old APKs are unaffected.
     */
    fun getSupabaseConfig(): String {
        // 🔴 Gated: this method is what turns a string in a binary into a DISPENSING SERVICE.
        // Blanking the Chickadee flavors' BuildConfig (see app/build.gradle.kts) removes the
        // value; this removes the counter that hands it out. ruled both halves, belt and
        // braces (2026-08-02) — because a later flavor that forgets to blank, or any other
        // route that repopulates the field, would otherwise be one JS call away from a
        // credential in an account-free product.
        //
        // Returns "{}" — the SAME shape the catch below already returns, so every existing JS
        // consumer takes a path it has always had. A null or a throw would be a new failure mode
        // to discover in the field.
        if (!com.dashieapp.Dashie.edition.EditionSeams.hasAccounts) {
            android.util.Log.i("JsBridgeDevice", "DROP: [expected] getSupabaseConfig() — this " +
                "edition is account-free, so there is no Supabase project to hand to JS.")
            return "{}"
        }
        return try {
            org.json.JSONObject(mapOf(
                "url" to BuildConfig.SUPABASE_URL,
                "anonKey" to BuildConfig.SUPABASE_ANON_KEY,
                "flavor" to BuildConfig.FLAVOR
            )).toString()
        } catch (e: Exception) {
            "{}"
        }
    }

    fun getDeviceInfo(): String {
        return DeviceInfoHelper.getDeviceInfo(context)
    }

    /**
     * Which EDITION this build is — `"dashie"` or `"chickadee"` (`JS_KOTLIN_CONTRACTS #64`).
     *
     * ## Why the kiosk overlay has to ASK
     *
     * The overlay bundle lives in `app/src/main/assets/webapp/`, the **main** source set, so
     * every flavor of both editions ships byte-identical JS. There is no build-time seam
     * available to it the way `BuildConfig.EDITION` is one here — splitting the bundle per
     * flavor would be a second copy of the whole overlay, which is exactly what standing rule
     * 1 exists to prevent. One runtime question, asked once per page load, is the cheap
     * alternative.
     *
     * Returns the raw `BuildConfig.EDITION` **without** interpreting it: the brand facts
     * (product name, mark, legal URLs, whether accounts exist) live in
     * `kiosk-overlay/js/brand.js`, because the assets they name exist only on the JS side.
     * Splitting that table across the boundary would put half a brand in each language.
     *
     * Additive and feature-detected on the JS side — an old APK simply has no such method and
     * the overlay falls back to Dashie, which is correct for every APK that predates this.
     */
    fun getEdition(): String = BuildConfig.EDITION

    /**
     * The HA integration's API prefix for THIS build — `ApiPaths.HA` verbatim
     * (`JS_KOTLIN_CONTRACTS #63`).
     *
     * ## Why the overlay asks instead of deriving it from `getEdition()`
     *
     * It could: the overlay already knows the edition, and `edition → prefix` is a two-row
     * table. That is precisely the second copy standing rule 1 says not to write. #63's whole
     * content is **one prefix per brand, one derivation** — a JS table mapping the same axis to
     * the same literals would be a hand-mirror of `ApiPaths`, invisible to `lint:wire-values`
     * (which reads the Kotlin), and free to drift the moment a third edition lands.
     *
     * So this is tier 1 (eliminate), not tier 3 (gate): there is still exactly one place the
     * literals live, and the overlay reads it.
     *
     * Additive and feature-detected on the JS side. An old APK has no such method and the
     * overlay falls back to `/api/dashie` — correct for every APK that predates this, all of
     * which are Dashie, same fallback direction and reasoning as `getEdition()`.
     */
    fun getHaApiPrefix(): String = com.dashieapp.Dashie.edition.ApiPaths.HA

    fun getDeviceId(): String {
        return com.dashieapp.Dashie.util.StableDeviceId.read(context)
    }

    fun setDeviceName(name: String) {
        Log.i(TAG, "setDeviceName('$name')")
        dashieApiPrefs.deviceName = name
        // #8/M5 convergence (build-plan 20260722_DEVICE_NAME_CONVERGENCE_PLAN.md):
        // user_devices.device_name is the SSOT; mirror it to connection.deviceFriendlyName
        // so a Console/cloud rename reaches the surfaces that read the friendly name
        // (MQTT, music/Sendspin, HA-login). Equality-gated — setDeviceName runs every boot
        // with the cloud device_name, so only re-publish when it actually changed (avoid a
        // Sendspin restart per boot). The one-time legacy promotion (JS device-registration
        // migration) runs BEFORE this so an existing native rename isn't clobbered.
        val conn = halitePrefs()?.connection ?: return
        if (name.isNotBlank() && conn.deviceFriendlyName != name) {
            conn.deviceFriendlyName = name
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_RESTART_SENDSPIN").apply {
                    setPackage(context.packageName)
                }
            )
            Log.i(TAG, "setDeviceName: mirrored → deviceFriendlyName + restarted Sendspin")
        }
    }

    /** The user-facing device name read by MQTT/music/HA-login (ConnectionPreferences).
     *  Exposed so the JS boot migration can promote a pre-existing native rename to the
     *  cloud SSOT before setDeviceName mirrors device_name back (#8/M5). */
    fun getDeviceFriendlyName(): String = halitePrefs()?.connection?.deviceFriendlyName ?: ""

    // ============================================
    // Diagnostic Toast Control
    // ============================================

    fun enableDiagnosticToasts() {
        webView.post { DiagnosticToastController.enable() }
    }

    fun disableDiagnosticToasts() {
        webView.post { DiagnosticToastController.disable() }
    }

    fun isDiagnosticToastsEnabled(): Boolean {
        return DiagnosticToastController.isEnabled()
    }

    // ============================================
    // Kiosk Mode / Immersive Mode
    // ============================================

    fun isKioskModeActive(): Boolean {
        return isKioskModeEnabled?.invoke() ?: false
    }

    // ============================================
    // Dark Mode Control
    // ============================================

    fun setDarkMode(isDark: Boolean) {
        Log.i(TAG, "setDarkMode($isDark)")
        webView.post { onSetDarkMode?.invoke(isDark) }
    }

    // ============================================
    // Auto-Brightness Control (Custom Light Sensor)
    // ============================================

    fun setAutoBrightness(enabled: Boolean) {
        Log.i(TAG, "setAutoBrightness($enabled)")

        // Save preference
        halitePrefs()?.display?.autoBrightnessEnabled = enabled

        // Enable/disable the light sensor controller
        val lightSensor = lightSensorProvider?.invoke()
        if (lightSensor != null) {
            lightSensor.setEnabled(enabled)

            // If enabling, also apply saved min/max/curve settings
            if (enabled) {
                val prefs = halitePrefs()
                val min = prefs?.display?.autoBrightnessMin ?: 10
                val max = prefs?.display?.autoBrightnessMax ?: 100
                val curve = prefs?.display?.autoBrightnessCurve ?: HalitePreferences.BRIGHTNESS_CURVE_LINEAR
                lightSensor.setMinMax(min, max)
                lightSensor.setCurve(curve)
                Log.i(TAG, "Auto-brightness enabled with min=$min, max=$max, curve=$curve")
            }
        } else {
            Log.w(TAG, "Light sensor controller not available - falling back to system auto-brightness")
            deviceControls.setAutoBrightness(enabled)
        }
    }

    /** Per-key readback for the enabled flag — equality-skip on boot pushes
     *  and the display-category live readback (audit F8: previously the only
     *  path for this key was its onChanged, so a missed event never healed). */
    fun getAutoBrightness(): Boolean {
        return halitePrefs()?.display?.autoBrightnessEnabled ?: false
    }

    fun getAutoBrightnessSettings(): String {
        val prefs = halitePrefs()
        val min = prefs?.display?.autoBrightnessMin ?: 10
        val max = prefs?.display?.autoBrightnessMax ?: 100
        val curve = prefs?.display?.autoBrightnessCurve ?: HalitePreferences.BRIGHTNESS_CURVE_LINEAR

        return org.json.JSONObject(mapOf(
            "min" to min,
            "max" to max,
            "curve" to curve
        )).toString()
    }

    fun setAutoBrightnessSettings(min: Int, max: Int, curve: String) {
        Log.i(TAG, "setAutoBrightnessSettings(min=$min, max=$max, curve=$curve)")

        val prefs = halitePrefs()
        if (prefs != null) {
            prefs.display.autoBrightnessMin = min.coerceIn(0, 100)
            prefs.display.autoBrightnessMax = max.coerceIn(0, 100)
            prefs.display.autoBrightnessCurve = curve
        }

        // Apply to light sensor controller if available
        val lightSensor = lightSensorProvider?.invoke()
        if (lightSensor != null) {
            lightSensor.setMinMax(min, max)
            lightSensor.setCurve(curve)
            Log.i(TAG, "Auto-brightness settings applied to light sensor controller")
        }
    }

    fun openAutoBrightnessSettings() {
        Log.i(TAG, "openAutoBrightnessSettings()")
        onOpenAutoBrightnessSettings?.invoke()
    }

    /**
     * Get brightness info with isAuto reflecting the light sensor controller state
     * (not the Android system auto-brightness setting).
     */
    fun getBrightnessInfo(): String {
        // Get base info from device controls
        val baseInfo = deviceControls.getBrightnessInfo()

        // If we have a light sensor controller, override the isAuto value
        val prefs = halitePrefs()
        if (prefs != null) {
            try {
                val json = org.json.JSONObject(baseInfo)
                json.put("isAuto", prefs.display.autoBrightnessEnabled)
                return json.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Error modifying brightness info: ${e.message}")
            }
        }

        return baseInfo
    }

    // ============================================
    // WiFi Setup
    // ============================================

    fun openWifiSetup() {
        Log.i(TAG, "openWifiSetup()")
        onOpenWifiSetup?.invoke()
    }

    // ============================================
    // Microphone Permission
    // ============================================

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    var onRequestMicrophonePermission: (() -> Unit)? = null

    fun requestMicrophonePermission() {
        Log.i(TAG, "Microphone permission requested from webapp")
        onRequestMicrophonePermission?.invoke()
    }

    // ============================================
    // Exact Alarm Permission (Android 12+)
    // ============================================

    fun canScheduleExactAlarms(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    fun needsExactAlarmPermission(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !canScheduleExactAlarms()
    }

    fun setUseExactAlarms(enabled: Boolean): Boolean {
        val prefs = halitePrefs()?.performance
        if (prefs == null) {
            Log.w(TAG, "setUseExactAlarms: prefs not available")
            return false
        }

        if (enabled && needsExactAlarmPermission()) {
            Log.i(TAG, "setUseExactAlarms: permission required, not setting preference yet")
            return false
        }

        prefs.useExactAlarms = enabled
        Log.i(TAG, "setUseExactAlarms: preference set to $enabled")
        return true
    }
}
