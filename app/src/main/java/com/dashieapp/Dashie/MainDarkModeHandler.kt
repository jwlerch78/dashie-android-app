package com.dashieapp.Dashie

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.devicecontrols.DarkModeManager
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator

/**
 * Handles dark mode configuration for MainActivity.
 * Extracted from MainActivity to reduce its size and improve maintainability.
 *
 * Responsibilities:
 * - Enable system dark mode support on startup
 * - Set WebView dark mode programmatically (from JS or system)
 * - Apply WebView-specific dark settings (disable algorithmic darkening)
 * - Signal web app about color scheme changes
 */
class MainDarkModeHandler(
    private val context: Context,
    private val webView: WebView,
    private val deviceControls: DeviceControlsCoordinator,
    private val packageName: String
) {
    companion object {
        private const val TAG = "MainDarkMode"
    }

    /** Callback for native components that need theme updates (photo widget, etc.) */
    var onThemeApplied: ((isDark: Boolean) -> Unit)? = null

    /**
     * Enable WebView to follow system dark/light mode automatically.
     * This allows the WebView to adapt based on the device's system theme setting.
     *
     * Uses setForceDarkStrategy with WEB_THEME_DARKENING_ONLY to let the web page
     * handle its own dark mode via prefers-color-scheme CSS media query.
     *
     * @param configuration Current configuration to read UI mode from
     */
    fun enableSystemDarkModeSupport(configuration: Configuration) {
        // If user has a stored preference, honour it instead of reading from
        // resources.configuration (which setContentView may have reset to light).
        val storedPref = DarkModeManager.getStoredPreference(context)
        if (storedPref != null) {
            Log.i(TAG, "🌓 Halite: Using stored dark mode preference = $storedPref (skipping system config)")
            // Re-force resources in case setContentView reset uiMode
            DarkModeManager.forceResourcesNightMode(context, storedPref)
            applyWebViewDarkSettings()
            return
        }

        // First launch — Dashie's explicit default is LIGHT, regardless of
        // the device's system theme. (Samsung tablets often ship with system
        // dark mode on by default; we don't want that to bleed into Dashie.)
        //
        // Force resources to light AND apply the WebView dark settings directly,
        // mirroring the storedPref branch above. We deliberately do NOT route
        // through setDarkMode(false): its no-op guard compares against the
        // pref-based isSystemDarkMode() (which returns the first-launch default
        // of false), sees "already light", and returns early — skipping
        // applyWebViewDarkSettings(). On a device whose system is in
        // MODE_NIGHT_AUTO, that left isAlgorithmicDarkeningAllowed at its default
        // so the WebView's prefers-color-scheme followed the system's AUTO-night,
        // rendering the login / HA-setup screens dark even though the Activity
        // resources were forced light. (Onn Google TV stick, 2026-05-28.)
        Log.i(TAG, "🌓 Halite: First launch — forcing LIGHT (system mode ignored)")
        DarkModeManager.forceResourcesNightMode(context, false)
        applyWebViewDarkSettings()
    }

    /**
     * Set WebView dark mode programmatically.
     * Called from JavaScript via DashieNative.setDarkMode() to sync with web app theme.
     *
     * This method controls SYSTEM-WIDE dark mode using Settings.Secure.ui_night_mode.
     * This changes the device's dark mode setting, which affects:
     * - The activity's theme (via DayNight parent)
     * - WebView's prefers-color-scheme CSS media query
     * - All iframes (including Home Assistant)
     *
     * Requires WRITE_SECURE_SETTINGS permission granted via ADB:
     * adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS
     *
     * @param isDark true for dark mode, false for light mode
     */
    fun setDarkMode(isDark: Boolean) {
        val currentDark = deviceControls.isSystemDarkMode()
        val canWrite = deviceControls.canWriteSecureSettings()
        Log.i(TAG, "🌓 setDarkMode($isDark) current=$currentDark canWriteSecureSettings=$canWrite")

        // No-op if already in the requested state
        if (currentDark == isDark) {
            Log.i(TAG, "🌓 Already in ${if (isDark) "DARK" else "LIGHT"} mode — skipping")
            // Still notify native widgets (theme may have been set from settings)
            onThemeApplied?.invoke(isDark)
            return
        }

        // Save preference to SharedPreferences and apply resources night mode.
        // DarkModeManager.setDarkMode() does NOT require WRITE_SECURE_SETTINGS.
        val success = deviceControls.setSystemDarkMode(isDark)
        Log.i(TAG, "🌓 Saved dark mode = ${if (isDark) "DARK" else "LIGHT"} (success=$success)")

        if (!success) {
            Log.e(TAG, "🌓 Failed to save dark mode preference")
            return
        }

        // Apply WebView-specific settings
        applyWebViewDarkSettings()

        // Notify native widgets of theme change
        onThemeApplied?.invoke(isDark)

        // Signal the web app that the color scheme changed so it can reload iframes if needed.
        // applyThemeFromNative() on the JS side guards against re-entrancy via _nativeSync.
        Log.i(TAG, "🌓 Signaling web app about color scheme change isDark=$isDark")
        webView.evaluateJavascript(
            "if (window.onColorSchemeChanged) window.onColorSchemeChanged($isDark);",
            null
        )

        if (!canWrite) {
            Log.w(TAG, "🌓 No WRITE_SECURE_SETTINGS — HA iframe prefers-color-scheme won't change")
        }
    }

    /**
     * Apply WebView-specific dark mode settings.
     *
     * IMPORTANT: We DISABLE algorithmic darkening and FORCE_DARK because:
     * 1. We control dark mode via system Settings.Secure.ui_night_mode
     * 2. This sets prefers-color-scheme correctly for the WebView
     * 3. Our CSS handles all theming via prefers-color-scheme media queries
     * 4. Algorithmic darkening interferes with our CSS by auto-darkening SVG icons
     */
    private fun applyWebViewDarkSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33): DISABLE algorithmic darkening
            // Our CSS handles dark mode; this would double-darken and break icons
            webView.settings.isAlgorithmicDarkeningAllowed = false
            Log.i(TAG, "🌓 Disabled isAlgorithmicDarkeningAllowed (CSS handles theming)")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12 (API 29-32): DISABLE FORCE_DARK
            // Our CSS handles dark mode via prefers-color-scheme
            try {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                    @Suppress("DEPRECATION")
                    androidx.webkit.WebSettingsCompat.setForceDark(
                        webView.settings,
                        androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
                    )
                    Log.i(TAG, "🌓 Disabled FORCE_DARK (CSS handles theming)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "WebSettingsCompat dark mode not available: ${e.message}")
            }
        }
    }

    /**
     * Notify web app about color scheme change from configuration change.
     * Called from onConfigurationChanged when UI mode changes.
     *
     * @param isNowDark true if system is now in dark mode
     */
    fun notifyColorSchemeChanged(isNowDark: Boolean) {
        Log.i(TAG, "🌓 Notifying web app about color scheme change: isDark=$isNowDark")
        webView.evaluateJavascript(
            "if (window.onColorSchemeChanged) window.onColorSchemeChanged($isNowDark);",
            null
        )
    }
}
