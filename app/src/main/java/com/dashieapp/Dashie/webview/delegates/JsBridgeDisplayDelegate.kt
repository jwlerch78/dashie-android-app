package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * JS bridge delegate for display-related settings:
 *   - Dashboard zoom + layout
 *   - Screensaver configuration (all modes + app picker)
 *   - Dash menu / dash bar
 *   - Display preferences (24h clock, date format, temperature unit, zip, weather overlay)
 *   - Photo settings readback (getPhotoSettings — reads from halitePrefs.screensaver)
 *
 * Extracted from JsBridgeSettingsDelegate in Phase 7 (structural split).
 */
class JsBridgeDisplayDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?
) {
    companion object {
        private const val TAG = "JsBridgeDisplay"
    }

    // Callbacks
    var onScreensaverSettingsChanged: (() -> Unit)? = null
    var onShowHaMediaFolderPicker: (() -> Unit)? = null
    var onOpenMotionWakeSettings: (() -> Unit)? = null
    var onLayoutEditModeChanged: ((Boolean) -> Unit)? = null

    // ============================================
    // Display Settings
    // ============================================

    // ========== HA Dashboard Zoom ==========
    // Applies only to the HA dashboard widget. Keeps its existing name
    // for JS bridge backward compatibility (DashieNative.setDashboardZoom).

    fun getDashboardZoom(): Int {
        return halitePrefs()?.display?.dashboardZoom ?: HalitePreferences.DEFAULT_DASHBOARD_ZOOM
    }

    fun setDashboardZoom(zoom: Int) {
        val clampedZoom = zoom.coerceIn(
            com.dashieapp.Dashie.halite.preferences.DisplayPreferences.MIN_DASHBOARD_ZOOM,
            com.dashieapp.Dashie.halite.preferences.DisplayPreferences.MAX_DASHBOARD_ZOOM
        )
        halitePrefs()?.display?.dashboardZoom = clampedZoom
        val zoomFactor = clampedZoom / 100.0
        Log.i(TAG, "🔍 setDashboardZoom($clampedZoom)")
        webView.post {
            webView.evaluateJavascript(
                """
                (function() {
                    // Apply zoom only to dashboard content via CSS variable
                    // (sidebar and overlays are NOT affected)
                    document.documentElement.style.setProperty('--dashboard-zoom', '$zoomFactor');
                    document.documentElement.setAttribute('data-dashboard-zoom', '$clampedZoom');

                    // Reload-safety (Finding #5): this is the cloud/Console/cross-device
                    // apply path. Mirror the localStorage twin that core-initializer.js
                    // re-reads on every HA (re)load, and postMessage the HA iframe — exactly
                    // like MainKioskController.applyDashboardZoom (the native-UI path). Without
                    // these, a cross-device/Console HA-zoom change set only the CSS var and
                    // reverted to the stale twin on the next HA reload.
                    try { localStorage.setItem('dashie-dashboard-zoom', '$clampedZoom'); } catch(e) {}
                    try {
                        document.querySelectorAll('iframe').forEach(function(f) {
                            try {
                                if (f.contentWindow) {
                                    f.contentWindow.postMessage({ type: 'zoom-changed', target: 'ha', value: $clampedZoom }, '*');
                                }
                            } catch(e) {}
                        });
                    } catch(e) {}

                    // Clear any legacy whole-page zoom
                    document.documentElement.style.zoom = '';
                    document.documentElement.style.transform = '';
                    document.documentElement.style.transformOrigin = '';
                    document.documentElement.style.width = '';
                    document.documentElement.style.height = '';
                    var overlay = document.getElementById('dashie-overlay');
                    if (overlay) { overlay.style.zoom = ''; }

                    console.log('[Dashie] Dashboard zoom applied via CSS variable: ${clampedZoom}%');
                })();
                """.trimIndent(),
                null
            )
        }
    }

    // ========== Widget Zoom ==========
    // Applies to Dashie's core widgets (calendar, chores, rewards,
    // locations, etc.) — everything except the HA dashboard.

    fun getWidgetZoom(): Int {
        return halitePrefs()?.display?.widgetZoom
            ?: com.dashieapp.Dashie.halite.preferences.DisplayPreferences.DEFAULT_WIDGET_ZOOM
    }

    // Kotlin-canonical readback for the two display settings whose value is
    // owned by the native picker and pushed to JS via an ACTION_APPLY_*
    // broadcast (no per-key native: setter). getCurrentDeviceSettings reads
    // these so the upload payload always reflects the true device value rather
    // than a not-yet-hydrated settingsStore default (which could clobber the
    // cloud value back to 100 on relaunch — the displaySize/widgetFontSize
    // revert bug, June 2026).
    fun getWidgetFontSize(): Int {
        return halitePrefs()?.display?.widgetFontSize
            ?: com.dashieapp.Dashie.halite.preferences.DisplayPreferences.DEFAULT_WIDGET_FONT_SIZE
    }

    fun getDisplaySize(): Int {
        return halitePrefs()?.display?.displaySize
            ?: com.dashieapp.Dashie.halite.preferences.DisplayPreferences.DEFAULT_DISPLAY_SIZE
    }

    /**
     * Cloud→Kotlin setters for the two previously write-only Console traps
     * (audit F2/H9, Phase 4.1 console honesty): a Console edit lands in
     * user_devices, pushDeviceSettingsToNative calls these, and the same
     * ACTION_APPLY_* broadcast the native picker fires re-applies the chrome
     * live (font push to WebView / sidebar+overlay rescale). Old APKs simply
     * lack the method — the push feature-detects and no-ops.
     */
    fun setWidgetFontSize(size: Int) {
        Log.i(TAG, "🔧 setWidgetFontSize($size)")
        halitePrefs()?.display?.widgetFontSize = size
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_APPLY_WIDGET_FONT_SIZE").apply {
                setPackage(context.packageName)
            }
        )
    }

    fun setDisplaySize(size: Int) {
        Log.i(TAG, "🔧 setDisplaySize($size)")
        halitePrefs()?.display?.displaySize = size
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_APPLY_DISPLAY_SIZE").apply {
                setPackage(context.packageName)
            }
        )
    }

    /** Weather entity readback — completes the display-category readback set
     *  (the setter existed without a getter, so live sync dead-ended). */
    fun getWeatherEntityId(): String {
        return halitePrefs()?.screensaver?.weatherEntityId ?: ""
    }

    fun setWidgetZoom(zoom: Int) {
        val clampedZoom = zoom.coerceIn(50, 200)
        halitePrefs()?.display?.widgetZoom = clampedZoom
        Log.i(TAG, "🔍 setWidgetZoom($clampedZoom)")
        // Mirror to localStorage and broadcast to non-HA iframes. The
        // iframe-side widget-zoom.js self-identifies via URL and applies
        // only events matching its target ('widget' here).
        webView.post {
            webView.evaluateJavascript(
                """
                (function() {
                    try { localStorage.setItem('dashie-widget-zoom', '$clampedZoom'); } catch (e) {}
                    document.querySelectorAll('iframe').forEach(function(f) {
                        try {
                            f.contentWindow && f.contentWindow.postMessage(
                                { type: 'zoom-changed', target: 'widget', value: $clampedZoom },
                                '*'
                            );
                        } catch (e) {}
                    });
                    console.log('[Dashie] Widget zoom applied: ${clampedZoom}%');
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun getScreensaverDescription(): String {
        val prefs = halitePrefs() ?: return "—"
        val timeout = prefs.screensaver.screensaverTimeout
        val mode = prefs.screensaver.screensaverMode
        if (timeout <= 0) return "Off"
        val timeStr = if (timeout >= 60) "${timeout / 60} min" else "${timeout}s"
        val modeLabels = mapOf(
            "dim" to "Dim", "black" to "Black Overlay", "off" to "Screen Off",
            "photos" to "Photos", "ha_page" to "HA Page", "url" to "URL", "app" to "App"
        )
        var modeStr = modeLabels[mode] ?: mode.replaceFirstChar { it.uppercase() }
        if (mode == "app") {
            val appLabel = prefs.screensaver.launchAppLabel
            if (appLabel.isNotEmpty()) modeStr += " ($appLabel)"
        }
        return "$modeStr, $timeStr"
    }

    fun getScreensaverSettings(): String {
        val prefs = halitePrefs() ?: return "{}"
        val screensaver = prefs.screensaver
        return org.json.JSONObject().apply {
            put("timeout", prefs.screensaver.screensaverTimeout)
            put("mode", prefs.screensaver.screensaverMode)
            put("dimBrightness", screensaver.dimBrightness)
            put("showClock", screensaver.showClock)
            put("showDate", screensaver.showDate)
            put("clockPosition", screensaver.clockPosition)
            put("clockSize", screensaver.clockSize)
            put("clockFontSize", screensaver.clockFontSize)
            put("slideshowInterval", screensaver.slideshowInterval)
            put("slideshowTransition", screensaver.slideshowTransition)
            put("slideshowShuffle", screensaver.slideshowShuffle)
            put("photoFit", screensaver.photoFit)
            put("showMetadata", screensaver.showMetadata)
            put("screensaverUrl", screensaver.screensaverUrl)
            put("launchAppLabel", screensaver.launchAppLabel)
            put("launchAppPackage", screensaver.launchAppPackage)
            put("photoSourceType", screensaver.photoSourceType)
            put("haMediaFolder", screensaver.haMediaFolder)
            put("haPagePath", screensaver.haPagePath)
            put("reduceBrightnessOnBlack", screensaver.reduceBrightnessOnBlack)
        }.toString()
    }

    fun setScreensaverTimeout(seconds: Int) {
        Log.i(TAG, "🔧 setScreensaverTimeout($seconds)")
        halitePrefs()?.screensaver?.screensaverTimeout = seconds
        notifyScreensaverSettingsChanged()
    }

    fun setScreensaverMode(mode: String) {
        Log.i(TAG, "🔧 setScreensaverMode($mode)")
        halitePrefs()?.screensaver?.screensaverMode = mode
        notifyScreensaverSettingsChanged()
    }

    fun setDimBrightness(percent: Int) {
        Log.i(TAG, "🔧 setDimBrightness($percent)")
        halitePrefs()?.screensaver?.dimBrightness = percent
        notifyScreensaverSettingsChanged()
    }

    fun setScreensaverShowClock(enabled: Boolean) {
        Log.i(TAG, "🔧 setScreensaverShowClock($enabled)")
        halitePrefs()?.screensaver?.showClock = enabled
        notifyScreensaverSettingsChanged()
    }

    fun setScreensaverShowDate(enabled: Boolean) {
        Log.i(TAG, "🔧 setScreensaverShowDate($enabled)")
        halitePrefs()?.screensaver?.showDate = enabled
        notifyScreensaverSettingsChanged()
    }

    fun setReduceBrightnessOnBlack(enabled: Boolean) {
        Log.i(TAG, "🔧 setReduceBrightnessOnBlack($enabled)")
        halitePrefs()?.screensaver?.reduceBrightnessOnBlack = enabled
    }

    fun setClockPosition(position: String) {
        Log.i(TAG, "🔧 setClockPosition($position)")
        halitePrefs()?.screensaver?.clockPosition = position
        notifyScreensaverSettingsChanged()
    }

    fun setClockSize(size: String) {
        Log.i(TAG, "🔧 setClockSize($size)")
        halitePrefs()?.screensaver?.clockSize = size
        notifyScreensaverSettingsChanged()
    }

    fun setClockFontSize(pt: Int) {
        Log.i(TAG, "🔧 setClockFontSize($pt)")
        halitePrefs()?.screensaver?.clockFontSize = pt
        notifyScreensaverSettingsChanged()
    }

    fun setSlideshowInterval(seconds: Int) {
        Log.i(TAG, "🔧 setSlideshowInterval($seconds)")
        halitePrefs()?.screensaver?.slideshowInterval = seconds
        notifyScreensaverSettingsChanged()
    }

    fun setScreensaverShowMetadata(enabled: Boolean) {
        Log.i(TAG, "🔧 setScreensaverShowMetadata($enabled)")
        halitePrefs()?.screensaver?.showMetadata = enabled
        notifyScreensaverSettingsChanged()
    }

    fun setPhotoSourceType(type: String) {
        val prev = halitePrefs()?.screensaver?.photoSourceType
        Log.i(TAG, "🔧 setPhotoSourceType($type) (was $prev)")
        halitePrefs()?.screensaver?.photoSourceType = type
        notifyScreensaverSettingsChanged()
        // When the source ACTUALLY changes, run the same coordinated reset +
        // widget-reconfigure the native settings UI fires via
        // ACTION_PHOTO_SOURCE_CHANGED. Previously this JS-driven path (used by
        // device-settings-sync, incl. fresh login) only updated the pref — so
        // the screensaver's shared photos (PhotoSourceFactory.sharedPhotosProvider)
        // stayed on the OLD source and the widget kept showing e.g. Unsplash
        // despite supabase until the next app restart. The prev != type guard
        // keeps this a one-shot, so the handler's JS native-settings-changed
        // dispatch can't loop back into another source change.
        if (prev != type) {
            // setPackage is required — an implicit broadcast without it isn't
            // delivered to the runtime-registered receiver on modern Android
            // (matches the other ACTION_PHOTO_SOURCE_CHANGED senders).
            context.sendBroadcast(
                Intent("com.dashieapp.Dashie.ACTION_PHOTO_SOURCE_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }
    }

    fun setHaMediaFolder(folder: String) {
        Log.i(TAG, "🔧 setHaMediaFolder($folder)")
        halitePrefs()?.screensaver?.haMediaFolder = folder
        notifyScreensaverSettingsChanged()
    }

    fun setUnsplashQuery(query: String) {
        Log.i(TAG, "🔧 setUnsplashQuery($query)")
        halitePrefs()?.screensaver?.unsplashQuery = query
        notifyScreensaverSettingsChanged()
    }

    fun setUnsplashArtistHyperlinks(enabled: Boolean) {
        Log.i(TAG, "🔧 setUnsplashArtistHyperlinks($enabled)")
        halitePrefs()?.screensaver?.unsplashArtistHyperlinks = enabled
        notifyScreensaverSettingsChanged()
    }

    fun setWeatherOverlayEnabled(enabled: String) {
        val isEnabled = enabled == "true"
        Log.i(TAG, "🔧 setWeatherOverlayEnabled($isEnabled)")
        halitePrefs()?.screensaver?.weatherOverlayEnabled = isEnabled
        notifyScreensaverSettingsChanged()
    }

    /** Boolean-accepting overload for the SETTINGS_KEY_MAP native-push path.
     *  Original takes String for legacy JS callers that pass "true"/"false". */
    fun setWeatherOverlayEnabledBool(enabled: Boolean) {
        Log.i(TAG, "🔧 setWeatherOverlayEnabledBool($enabled)")
        halitePrefs()?.screensaver?.weatherOverlayEnabled = enabled
        notifyScreensaverSettingsChanged()
    }

    fun setWeatherEntityId(entityId: String) {
        Log.i(TAG, "🔧 setWeatherEntityId($entityId)")
        halitePrefs()?.screensaver?.weatherEntityId = entityId
        notifyScreensaverSettingsChanged()
    }

    fun setScreensaverHaPagePath(path: String) {
        Log.i(TAG, "🔧 setScreensaverHaPagePath($path)")
        halitePrefs()?.screensaver?.haPagePath = path
        notifyScreensaverSettingsChanged()
    }

    fun setSlideshowTransition(transition: String) {
        Log.i(TAG, "🔧 setSlideshowTransition($transition)")
        halitePrefs()?.screensaver?.slideshowTransition = transition
        notifyScreensaverSettingsChanged()
    }

    fun setSlideshowShuffle(enabled: Boolean) {
        Log.i(TAG, "🔧 setSlideshowShuffle($enabled)")
        halitePrefs()?.screensaver?.slideshowShuffle = enabled
        notifyScreensaverSettingsChanged()
    }

    fun setPhotoFit(mode: String) {
        Log.i(TAG, "🔧 setPhotoFit($mode)")
        halitePrefs()?.screensaver?.photoFit = mode
        notifyScreensaverSettingsChanged()
    }

    fun setScreensaverApp(packageName: String, label: String) {
        Log.i(TAG, "🔧 setScreensaverApp($packageName, $label)")
        halitePrefs()?.screensaver?.launchAppPackage = packageName
        halitePrefs()?.screensaver?.launchAppLabel = label
        notifyScreensaverSettingsChanged()
    }

    /**
     * Returns a JSON array of installed launchable apps.
     * Each entry: { "package": "com.example.app", "label": "App Name" }
     * Excludes Dashie itself and system apps (com.amazon.*, com.android.*).
     */
    fun getInstalledApps(): String {
        val pm = context.packageManager
        val appMap = mutableMapOf<String, String>() // packageName -> label

        // Standard launcher apps
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.queryIntentActivities(launcherIntent, 0).forEach { resolveInfo ->
            appMap[resolveInfo.activityInfo.packageName] = resolveInfo.loadLabel(pm).toString()
        }

        // Leanback launcher apps (Android TV)
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        }
        pm.queryIntentActivities(leanbackIntent, 0).forEach { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            if (!appMap.containsKey(pkg)) {
                appMap[pkg] = resolveInfo.loadLabel(pm).toString()
            }
        }

        val apps = appMap.entries
            .filter { it.key != context.packageName }
            .filter { !it.key.startsWith("com.amazon.") }
            .filter { !it.key.startsWith("com.android.") }
            .sortedBy { it.value.lowercase() }

        val result = JSONArray()
        apps.forEach { (pkg, label) ->
            result.put(JSONObject().apply {
                put("package", pkg)
                put("label", label)
            })
        }
        Log.i(TAG, "📱 getInstalledApps: found ${result.length()} apps")
        return result.toString()
    }

    private fun notifyScreensaverSettingsChanged() {
        webView.post { onScreensaverSettingsChanged?.invoke() }
    }

    fun getMotionWakeDescription(): String {
        val prefs = halitePrefs() ?: return "Touch Only"
        return when (prefs.screensaver.motionWakeMode) {
            "disabled" -> "Touch Only"
            "brightness" -> "Brightness Sensor"
            "camera" -> "Motion (Camera)"
            "face" -> "Face Detection (Camera)"
            else -> "Touch Only"
        }
    }

    /** Raw motionWakeMode value — used by getCurrentDeviceSettings in
     *  device-registration.js to surface the current Wake Mode setting
     *  in user_devices.settings.display.motionWakeMode. The description
     *  variant above is for UI; this one is the persistence key the
     *  Console reads. */
    fun getMotionWakeMode(): String {
        return halitePrefs()?.screensaver?.motionWakeMode ?: "disabled"
    }

    /** Sidebar icon size scalar (0.75 / 0.9 / 1 / 1.15 / 1.3). Same
     *  reason — surface the canonical value for the Console + cloud sync. */
    fun getSidebarIconSize(): String {
        // SharedPreferences holds it as a float string; default 1 ("Medium").
        val raw = halitePrefs()?.display?.sidebarIconSize
        return if (raw != null) raw.toString() else "1"
    }

    fun showHaMediaFolderPicker() {
        webView.post { onShowHaMediaFolderPicker?.invoke() }
    }

    fun openMotionWakeSettings() {
        webView.post { onOpenMotionWakeSettings?.invoke() }
    }

    fun getLayoutDescription(): String {
        val prefs = halitePrefs() ?: return "Single Panel"
        val dashBar = prefs.display.dashMenuEnabled
        return if (dashBar) "Single Panel + Dash bar" else "Single Panel"
    }

    fun getLayoutMode(): String {
        return halitePrefs()?.display?.layoutMode ?: "widgets"
    }

    fun setLayoutEditMode(enabled: Boolean) {
        android.util.Log.i("JsBridgeDisplay", "✏️ setLayoutEditMode($enabled)")
        webView.post { onLayoutEditModeChanged?.invoke(enabled) }
    }

    fun isDashMenuEnabled(): Boolean {
        return halitePrefs()?.display?.dashMenuEnabled ?: false
    }

    fun setDashMenuEnabled(enabled: Boolean) {
        Log.i(TAG, "🔧 setDashMenuEnabled($enabled)")
        halitePrefs()?.display?.dashMenuEnabled = enabled
    }

    // ============================================
    // Display Preferences
    // ============================================

    fun getUse24HourClock(): Boolean {
        return halitePrefs()?.display?.use24HourClock ?: false
    }

    fun setUse24HourClock(enabled: Boolean) {
        val previous = halitePrefs()?.display?.use24HourClock
        if (previous == enabled) {
            // Settings-sync hydration on startup re-pushes the same values
            // repeatedly; without this dedup, each redundant call broadcasts
            // ACTION_DISPLAY_SETTINGS_CHANGED, which kicks every weather
            // widget into a force-refetch. Observed ~20 broadcasts → ~80
            // queued WeatherDataProvider.refresh() threads in 10s on cold
            // start, masking real fetch failures behind a flood.
            Log.d(TAG, "🔧 setUse24HourClock($enabled) — unchanged, skipping")
            return
        }
        Log.i(TAG, "🔧 setUse24HourClock($enabled) (was $previous)")
        halitePrefs()?.display?.use24HourClock = enabled
        // Force weather widgets to re-format their cached time strings.
        // WeatherDataProvider bakes the use24Hour value into HourlyForecast.time
        // at parse time, so without a refresh the widget keeps the old format
        // until the next 15-min poll. Mirrors what the schema setter does
        // locally via notifyDisplaySettingsChanged() in DisplaySettingsWiring;
        // when the value lands here from cross-device JS sync, that path
        // doesn't run and the widget would otherwise silently stay stale.
        broadcastDisplaySettingsChanged()
    }

    fun getDateFormat(): String {
        return halitePrefs()?.display?.dateFormat ?: "mdy"
    }

    fun setDateFormat(format: String) {
        val previous = halitePrefs()?.display?.dateFormat
        if (previous == format) {
            Log.d(TAG, "🔧 setDateFormat($format) — unchanged, skipping")
            return
        }
        Log.i(TAG, "🔧 setDateFormat($format) (was '$previous')")
        halitePrefs()?.display?.dateFormat = format
        broadcastDisplaySettingsChanged()
    }

    fun getTemperatureUnit(): String {
        return halitePrefs()?.display?.temperatureUnit ?: "F"
    }

    fun setTemperatureUnit(unit: String) {
        val previous = halitePrefs()?.display?.temperatureUnit
        if (previous == unit) {
            Log.d(TAG, "🔧 setTemperatureUnit($unit) — unchanged, skipping")
            return
        }
        Log.i(TAG, "🔧 setTemperatureUnit($unit) (was '$previous')")
        halitePrefs()?.display?.temperatureUnit = unit
        // Temperature unit drives WeatherDataProvider's URL params + HA
        // unit conversion. Refresh on change so the widget re-fetches with
        // the new unit instead of waiting for the next 15-min poll.
        try {
            context.sendBroadcast(
                Intent("com.dashieapp.Dashie.ACTION_WEATHER_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast weather re-fetch: ${e.message}")
        }
    }

    private fun broadcastDisplaySettingsChanged() {
        try {
            context.sendBroadcast(
                Intent("com.dashieapp.Dashie.ACTION_DISPLAY_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast display settings change: ${e.message}")
        }
    }

    fun setZipCode(zipCode: String) {
        val previous = halitePrefs()?.general?.zipCode ?: ""
        Log.i(TAG, "🔧 setZipCode($zipCode) (was '$previous')")
        halitePrefs()?.general?.zipCode = zipCode
        if (previous != zipCode) {
            // Force an immediate weather re-fetch so the widget reflects
            // the new location without waiting for the next 15-min poll.
            // Common case: post-login settings sync pushes the user's zip
            // *after* the data provider has already started with empty
            // prefs and surfaced "Could not fetch weather data".
            try {
                context.sendBroadcast(
                    Intent("com.dashieapp.Dashie.ACTION_WEATHER_CONFIG_CHANGED").apply {
                        setPackage(context.packageName)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to broadcast weather re-fetch: ${e.message}")
            }
        }
    }

    fun getZipCode(): String {
        return halitePrefs()?.general?.zipCode ?: ""
    }

    /**
     * Store coordinates the in-app web resolver geocoded for [locationKey].
     * Native weather prefers these over re-geocoding the location string (its
     * name-geocoder returns nothing for "City STATE" / international forms),
     * tagged with the location string so stale coords from a prior location are
     * ignored. Broadcasts a weather re-fetch so the new coords take effect now.
     */
    fun setWeatherCoordinates(latitude: Double, longitude: Double, locationKey: String) {
        val general = halitePrefs()?.general ?: return
        Log.i(TAG, "🔧 setWeatherCoordinates($latitude, $longitude) for '$locationKey'")
        general.webLatitude = latitude
        general.webLongitude = longitude
        general.webLocationKey = locationKey.trim()
        try {
            context.sendBroadcast(
                Intent("com.dashieapp.Dashie.ACTION_WEATHER_CONFIG_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to broadcast weather re-fetch: ${e.message}")
        }
    }

    /**
     * Store the canonical IANA timezone the webapp resolved from the configured
     * location (family.timezone). The realtime voice relay prefers this over
     * TimeZone.getDefault() so a device with a wrong default tz still answers
     * times in the user's configured zone. Blank string clears it (fall back to
     * the device default).
     */
    fun setResolvedTimezone(timezone: String) {
        Log.i(TAG, "🔧 setResolvedTimezone('$timezone')")
        halitePrefs()?.general?.resolvedTimezone = timezone.trim()
    }

    // Account-level preference readbacks — the JS account_prefs handler reads
    // these after a native Preferences edit and writes them to user_settings
    // (general.language / weather.useHa / time.useHa), where the console + cloud
    // AI prompts already read them. Without this readback a native edit never
    // reached the cloud (the sibling of the zip → family.zipCode bug).
    fun getLanguage(): String {
        return halitePrefs()?.general?.language ?: "system"
    }

    fun getUseHaForWeather(): Boolean {
        return halitePrefs()?.general?.useHaForWeather ?: true
    }

    fun getUseHaForTime(): Boolean {
        return halitePrefs()?.general?.useHaForTime ?: false
    }

    /** Cloud→Kotlin half of the ACCOUNT-level weather-source toggle
     *  (user_settings.weather.useHa). Discovered missing 2026-07-06 during
     *  soak testing: the toggle uploaded to the account but no setter
     *  existed, so every other device kept its local pref and the
     *  "account-wide" setting behaved device-local. Equality-gated; fires
     *  the existing weather-config broadcast so native widgets re-fetch
     *  from the new source immediately (also clears the mixed-units
     *  display without waiting for the 15-min poll). */
    fun setUseHaForWeather(enabled: Boolean) {
        val p = halitePrefs() ?: return
        if (p.general.useHaForWeather == enabled) return
        p.general.useHaForWeather = enabled
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_WEATHER_CONFIG_CHANGED").apply {
                setPackage(context.packageName)
            }
        )
    }

    /** Cloud→Kotlin half of user_settings.time.useHa — same class as
     *  setUseHaForWeather above. */
    fun setUseHaForTime(enabled: Boolean) {
        val p = halitePrefs() ?: return
        if (p.general.useHaForTime == enabled) return
        p.general.useHaForTime = enabled
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_TIME_CONFIG_CHANGED").apply {
                setPackage(context.packageName)
            }
        )
    }

    fun getWeatherOverlayEnabled(): Boolean {
        return halitePrefs()?.screensaver?.weatherOverlayEnabled ?: false
    }

    // ============================================
    // Photo Settings Readback
    // (data lives in screensaver prefs — keeps photo config logically grouped
    //  with the rest of display since photos are a screensaver mode)
    // ============================================

    fun getPhotoSettings(): String {
        val prefs = halitePrefs() ?: return "{}"
        val ss = prefs.screensaver
        return org.json.JSONObject().apply {
            put("sourceType", ss.photoSourceType)
            put("haMediaFolder", ss.haMediaFolder)
            put("unsplashQuery", ss.unsplashQuery)
            put("slideshowInterval", ss.slideshowInterval)
            put("slideshowTransition", ss.slideshowTransition)
            put("slideshowShuffle", ss.slideshowShuffle)
            put("photoFit", ss.photoFit)
            put("showMetadata", ss.showMetadata)
            put("unsplashArtistHyperlinks", ss.unsplashArtistHyperlinks)
        }.toString()
    }

    // ============================================
    // SETTINGS_KEY_MAP native-push setters
    //
    // Invoked from the JS side via window.DashieNative.<name>(value) to keep
    // Kotlin SharedPrefs in sync with cloud/user_devices state. These fire
    // on both direct JS writes (saveDeviceSetting → map.native) and
    // cross-device cloud broadcasts (applyDeviceSettings →
    // pushDeviceSettingsToNative). Without these, Kotlin settings UI and any
    // Kotlin runtime consumers render stale state after JS-originated changes.
    // ============================================

    /** dashie_theme_prefs.animations_enabled — raw SharedPrefs, same file
     *  as theme_family. Kotlin Display schema toggles this. */
    fun setAnimationsEnabled(enabled: Boolean) {
        Log.i(TAG, "🔧 setAnimationsEnabled($enabled)")
        context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("animations_enabled", enabled).apply()
    }

    /** dashie_theme_prefs.animation_level — picker value "high" / "low". */
    fun setAnimationLevel(level: String) {
        Log.i(TAG, "🔧 setAnimationLevel($level)")
        context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
            .edit().putString("animation_level", level).apply()
    }

    /** DisplayPreferences.showUiTips — drives photo widget d-pad hint strip. */
    fun setShowUiTips(enabled: Boolean) {
        Log.i(TAG, "🔧 setShowUiTips($enabled)")
        halitePrefs()?.display?.showUiTips = enabled
    }

    /** dashie_theme_prefs.theme_family — "default" / "halloween" / "christmas". */
    fun setThemeFamily(family: String) {
        Log.i(TAG, "🔧 setThemeFamily($family)")
        context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
            .edit().putString("theme_family", family).apply()
    }
}
