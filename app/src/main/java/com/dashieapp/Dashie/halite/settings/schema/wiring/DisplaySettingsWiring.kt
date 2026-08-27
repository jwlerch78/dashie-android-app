package com.dashieapp.Dashie.halite.settings.schema.wiring

import android.content.Context
import android.content.res.Configuration
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.DisplayPreferences
import com.dashieapp.Dashie.halite.preferences.GeneralPreferences
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import com.dashieapp.Dashie.halite.settings.schema.HaliteSettingsValueProvider

/**
 * Wiring for Display, Sleep, Screensaver, Photos, and Display Page preferences.
 * Extracted from SettingsSchemaWiring to reduce file size.
 */
object DisplaySettingsWiring {

    // ── General ─────────────────────────────────────────────────────────

    fun registerGeneralPreferences(vp: HaliteSettingsValueProvider, general: GeneralPreferences) {
        vp.registerString("general.zipCode",
            getter = { general.zipCode },
            setter = { general.zipCode = it }
        )
        vp.registerString("general.language",
            getter = { general.language },
            setter = { general.language = it }
        )
        vp.registerBoolean("weather.useHa",
            getter = { general.useHaForWeather },
            setter = { general.useHaForWeather = it }
        )
        vp.registerBoolean("time.useHa",
            getter = { general.useHaForTime },
            setter = { general.useHaForTime = it }
        )
    }

    // ── Device info (read-only, for schema visibleWhen gates) ───────────

    fun registerDeviceInfo(vp: HaliteSettingsValueProvider, context: Context) {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        val isTv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION
        vp.registerBoolean("device.isTv",
            getter = { isTv },
            setter = { /* read-only */ }
        )
    }

    // ── Display ─────────────────────────────────────────────────────────

    fun registerDisplayPreferences(vp: HaliteSettingsValueProvider, prefs: HalitePreferences, context: Context? = null) {
        val display = prefs.display

        // Notify the WebView (settingsStore + Supabase) when use24HourClock /
        // dateFormat change in the Kotlin native preferences page. Without
        // this, the JS dashboard reads stale values from settingsStore until
        // a reload. Mirrors the photo/sleep/calendar dispatch pattern.
        fun notifyDisplaySettingsChanged() {
            context?.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_DISPLAY_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                }
            )
        }

        // Booleans
        vp.registerBoolean("display.use24HourClock",
            getter = { display.use24HourClock },
            setter = {
                display.use24HourClock = it
                notifyDisplaySettingsChanged()
            }
        )
        vp.registerBoolean("display.autoBrightnessEnabled",
            getter = { display.autoBrightnessEnabled },
            setter = { display.autoBrightnessEnabled = it }
        )
        vp.registerBoolean("display.lowBandwidthMode",
            getter = { display.lowBandwidthMode },
            setter = { display.lowBandwidthMode = it }
        )
        vp.registerBoolean("display.gpuHardwareLayerEnabled",
            getter = { display.gpuHardwareLayerEnabled },
            setter = { display.gpuHardwareLayerEnabled = it }
        )
        vp.registerBoolean("display.showUiTips",
            getter = { display.showUiTips },
            setter = { display.showUiTips = it }
        )

        // Ints
        vp.registerInt("display.autoBrightnessMin",
            getter = { display.autoBrightnessMin },
            setter = { display.autoBrightnessMin = it }
        )
        vp.registerInt("display.autoBrightnessMax",
            getter = { display.autoBrightnessMax },
            setter = { display.autoBrightnessMax = it }
        )

        // Strings
        vp.registerString("display.dateFormat",
            getter = { display.dateFormat },
            setter = {
                display.dateFormat = it
                notifyDisplaySettingsChanged()
            }
        )
        vp.registerString("display.temperatureUnit",
            getter = { display.temperatureUnit },
            setter = { display.temperatureUnit = it }
        )
        vp.registerString("display.autoBrightnessCurve",
            getter = { display.autoBrightnessCurve },
            setter = { display.autoBrightnessCurve = it }
        )

        // Computed display values (read-only strings for Navigation items)
        vp.registerString("display.dashboardZoomDisplay",
            getter = { "${display.dashboardZoom}%" },
            setter = { } // read-only
        )
        // Dashboard zoom as string (for picker sub-screen and inline TextInput)
        vp.registerString("display.dashboardZoom",
            getter = { display.dashboardZoom.toString() },
            setter = {
                val value = it.toIntOrNull()?.coerceIn(
                    DisplayPreferences.MIN_DASHBOARD_ZOOM, DisplayPreferences.MAX_DASHBOARD_ZOOM
                ) ?: return@registerString
                display.dashboardZoom = value
            }
        )
        // Widget zoom — independent from dashboard (HA) zoom; applies to
        // Dashie core widgets (calendar, chores, rewards, locations, etc).
        vp.registerString("display.widgetZoomDisplay",
            getter = { "${display.widgetZoom}%" },
            setter = { } // read-only
        )
        vp.registerString("display.widgetZoom",
            getter = { display.widgetZoom.toString() },
            setter = {
                val value = it.toIntOrNull()?.coerceIn(50, 300) ?: return@registerString
                display.widgetZoom = value
            }
        )
        // Font Size — scales web widget TEXT (calendar, chores, weather, photos)
        // via --widget-font-scale. Independent of Widget Zoom (layout). The
        // picker's onChanged=notifyFontSizeChanged pushes it to the WebView.
        vp.registerString("display.widgetFontSizeDisplay",
            getter = { "${display.widgetFontSize}%" },
            setter = { } // read-only
        )
        vp.registerString("display.widgetFontSize",
            getter = { display.widgetFontSize.toString() },
            setter = {
                val value = it.toIntOrNull()?.coerceIn(75, 200) ?: return@registerString
                display.widgetFontSize = value
            }
        )
        // Display Size — scales NATIVE chrome/overlays (sidebar, control center,
        // music/video/timer/voice). Independent of Font Size (web text) and
        // Widget Zoom (web layout). onChanged=notifyDisplaySizeChanged rebuilds
        // the sidebar; overlays pick it up on next render.
        vp.registerString("display.displaySizeDisplay",
            getter = { "${display.displaySize}%" },
            setter = { } // read-only
        )
        vp.registerString("display.displaySize",
            getter = { display.displaySize.toString() },
            setter = {
                val value = it.toIntOrNull()?.coerceIn(100, 200) ?: return@registerString
                display.displaySize = value
            }
        )
        vp.registerString("display.screenOffBehavior",
            getter = { if (prefs.sleep.hardwareScreenOff) "power_off" else "black_overlay" },
            setter = { prefs.sleep.hardwareScreenOff = (it == "power_off") }
        )
        vp.registerString("display.screenOffBehaviorDisplay",
            getter = { if (prefs.sleep.hardwareScreenOff) "Power Off Screen" else "Black Overlay" },
            setter = { } // read-only
        )
        vp.registerString("display.autoBrightnessCurveDisplay",
            getter = {
                when (display.autoBrightnessCurve) {
                    DisplayPreferences.BRIGHTNESS_CURVE_AGGRESSIVE -> "Aggressive"
                    DisplayPreferences.BRIGHTNESS_CURVE_GENTLE -> "Gentle"
                    else -> "Linear"
                }
            },
            setter = { } // read-only
        )

        // Sidebar icon size
        vp.registerString("display.sidebarIconSize",
            getter = { display.sidebarIconSize },
            setter = { display.sidebarIconSize = it }
        )
        vp.registerString("display.sidebarIconSizeDisplay",
            getter = {
                when (display.sidebarIconSize) {
                    "0.75" -> "Very Small"
                    "0.9" -> "Small"
                    "1" -> "Medium"
                    "1.15" -> "Large"
                    "1.3" -> "Extra Large"
                    else -> "Medium"
                }
            },
            setter = { } // read-only
        )
    }

    // ── Sleep ───────────────────────────────────────────────────────────

    fun registerSleepPreferences(vp: HaliteSettingsValueProvider, prefs: HalitePreferences) {
        val sleep = prefs.sleep

        vp.registerBoolean("sleep.startOnBoot",
            getter = { sleep.startOnBoot },
            setter = { sleep.startOnBoot = it }
        )
        vp.registerBoolean("sleep.keepScreenOn",
            getter = { sleep.keepScreenOn },
            setter = { sleep.keepScreenOn = it }
        )
        vp.registerBoolean("sleep.sleepEnabled",
            getter = { sleep.sleepEnabled },
            setter = { sleep.sleepEnabled = it }
        )
        vp.registerString("sleep.sleepMethod",
            getter = { sleep.sleepMethod },
            setter = { sleep.sleepMethod = it }
        )
        vp.registerString("sleep.sleepTime",
            getter = { sleep.sleepTime },
            setter = { sleep.sleepTime = it }
        )
        vp.registerString("sleep.wakeTime",
            getter = { sleep.wakeTime },
            setter = { sleep.wakeTime = it }
        )

        // Composite sleep mode: combines sleepEnabled + sleepMethod
        // "off" → sleepEnabled=false; "schedule"/"inactivity" → sleepEnabled=true + sleepMethod set
        vp.registerString("sleep.sleepMode",
            getter = {
                if (!sleep.sleepEnabled) "off" else sleep.sleepMethod
            },
            setter = { mode ->
                if (mode == "off") {
                    sleep.sleepEnabled = false
                } else {
                    sleep.sleepEnabled = true
                    sleep.sleepMethod = mode
                }
            }
        )

        // Simplified toggle for non-advanced users (no HA, no advanced opt-in):
        // Timeout mode is hidden, so enabling sleep always means scheduled sleep.
        // Reads true only when sleep is enabled AND the method is "schedule";
        // a stale inactivity carryover from a previous advanced session shows
        // as OFF (and re-enabling forces method back to schedule).
        vp.registerBoolean("sleep.scheduleEnabled",
            getter = { sleep.sleepEnabled && sleep.sleepMethod == "schedule" },
            setter = { enabled ->
                if (enabled) {
                    sleep.sleepEnabled = true
                    sleep.sleepMethod = "schedule"
                } else {
                    sleep.sleepEnabled = false
                }
            }
        )

        // Re-sleep timeout (minutes) as string for picker
        vp.registerString("sleep.resleepTimeout",
            getter = { sleep.resleepTimeout.toString() },
            setter = { sleep.resleepTimeout = it.toIntOrNull() ?: 5 }
        )

        // Inactivity timeout (seconds) as string for picker
        vp.registerString("sleep.inactivityTimeout",
            getter = { sleep.inactivityTimeout.toString() },
            setter = { sleep.inactivityTimeout = it.toIntOrNull() ?: 1800 }
        )

        // Booleans for options toggles
        vp.registerBoolean("sleep.sleepShowClock",
            getter = { sleep.sleepShowClock },
            setter = { sleep.sleepShowClock = it }
        )
        vp.registerBoolean("sleep.motionWakeForSleep",
            getter = { sleep.motionWakeForSleep },
            setter = { sleep.motionWakeForSleep = it }
        )
        vp.registerBoolean("sleep.reduceBrightnessOnSleep",
            getter = { sleep.reduceBrightnessOnSleep },
            setter = { sleep.reduceBrightnessOnSleep = it }
        )

        // Computed display values (read-only)
        vp.registerString("sleep.sleepTimeDisplay",
            getter = { formatTime12(sleep.sleepTime) },
            setter = { }
        )
        vp.registerString("sleep.wakeTimeDisplay",
            getter = { formatTime12(sleep.wakeTime) },
            setter = { }
        )
        vp.registerString("sleep.resleepTimeoutDisplay",
            getter = {
                when (sleep.resleepTimeout) {
                    0 -> "Immediate"
                    60 -> "1 hour"
                    else -> "${sleep.resleepTimeout} min"
                }
            },
            setter = { }
        )
        vp.registerString("sleep.inactivityTimeoutDisplay",
            getter = { formatInactivityTimeout(sleep.inactivityTimeout) },
            setter = { }
        )
    }

    /** Format 24h time string to 12h display (e.g. "22:00" → "10:00 PM") */
    private fun formatTime12(time24: String): String {
        return try {
            val parts = time24.split(":")
            if (parts.size != 2) return time24
            val hour = parts[0].toIntOrNull() ?: return time24
            val minute = parts[1].toIntOrNull() ?: return time24
            val period = if (hour < 12) "AM" else "PM"
            val displayH = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            "$displayH:${minute.toString().padStart(2, '0')} $period"
        } catch (_: Exception) { time24 }
    }

    /** Format inactivity timeout seconds to human-readable label. */
    private fun formatInactivityTimeout(seconds: Int): String {
        return when {
            seconds < 60 -> "${seconds} sec"
            seconds < 3600 -> "${seconds / 60} min"
            else -> {
                val h = seconds / 3600
                val m = (seconds % 3600) / 60
                if (m > 0) "${h}h ${m}m" else "$h hour${if (h > 1) "s" else ""}"
            }
        }
    }

    // ── Screensaver ──────────────────────────────────────────────────────

    fun registerScreensaverPreferences(vp: HaliteSettingsValueProvider, ss: ScreensaverPreferences) {
        // Timeout as string (for picker + condition evaluation)
        vp.registerString("screensaver.timeout",
            getter = { ss.screensaverTimeout.toString() },
            setter = { ss.screensaverTimeout = it.toIntOrNull() ?: ScreensaverPreferences.DEFAULT_SCREENSAVER_TIMEOUT }
        )

        // Mode
        vp.registerString("screensaver.mode",
            getter = { ss.screensaverMode },
            setter = { ss.screensaverMode = it }
        )

        // Dim brightness as string (for picker)
        vp.registerString("screensaver.dimBrightness",
            getter = { ss.dimBrightness.toString() },
            setter = { ss.dimBrightness = it.toIntOrNull() ?: ScreensaverPreferences.DEFAULT_DIM_BRIGHTNESS }
        )

        // Booleans
        vp.registerBoolean("screensaver.showPreviewOnWake",
            getter = { ss.showPreviewOnWake },
            setter = { ss.showPreviewOnWake = it }
        )
        vp.registerBoolean("screensaver.showClock",
            getter = { ss.showClock },
            setter = { ss.showClock = it }
        )
        vp.registerBoolean("screensaver.showDate",
            getter = { ss.showDate },
            setter = { ss.showDate = it }
        )
        vp.registerBoolean("screensaver.weatherOverlayEnabled",
            getter = { ss.weatherOverlayEnabled },
            setter = { ss.weatherOverlayEnabled = it }
        )
        vp.registerString("screensaver.weatherMode",
            getter = { ss.weatherMode },
            setter = { ss.weatherMode = it }
        )
        vp.registerString("screensaver.forecastCardSize",
            getter = { ss.forecastCardSize },
            setter = { ss.forecastCardSize = it }
        )
        vp.registerBoolean("screensaver.reduceBrightnessOnBlack",
            getter = { ss.reduceBrightnessOnBlack },
            setter = { ss.reduceBrightnessOnBlack = it }
        )
        vp.registerBoolean("screensaver.slideshowShuffle",
            getter = { ss.slideshowShuffle },
            setter = { ss.slideshowShuffle = it }
        )
        vp.registerBoolean("screensaver.showMetadata",
            getter = { ss.showMetadata },
            setter = { ss.showMetadata = it }
        )

        // Photo source type
        vp.registerString("screensaver.photoSourceType",
            getter = { ss.photoSourceType },
            setter = { ss.photoSourceType = it }
        )

        // HA media folder
        vp.registerString("screensaver.haMediaFolder",
            getter = { ss.haMediaFolder },
            setter = { ss.haMediaFolder = it }
        )

        // Unsplash query
        vp.registerString("screensaver.unsplashQuery",
            getter = { ss.unsplashQuery },
            setter = { ss.unsplashQuery = it }
        )

        // Slideshow interval as string (for picker)
        vp.registerString("screensaver.slideshowInterval",
            getter = { ss.slideshowInterval.toString() },
            setter = { ss.slideshowInterval = it.toIntOrNull() ?: ScreensaverPreferences.DEFAULT_SLIDESHOW_INTERVAL }
        )

        // Slideshow transition
        vp.registerString("screensaver.slideshowTransition",
            getter = { ss.slideshowTransition },
            setter = { ss.slideshowTransition = it }
        )

        // HA page path
        vp.registerString("screensaver.haPagePath",
            getter = { ss.haPagePath },
            setter = { ss.haPagePath = it }
        )

        // Screensaver URL
        vp.registerString("screensaver.screensaverUrl",
            getter = { ss.screensaverUrl },
            setter = { ss.screensaverUrl = it }
        )

        // Clock position
        vp.registerString("screensaver.clockPosition",
            getter = { ss.clockPosition },
            setter = { ss.clockPosition = it }
        )

        // Clock size
        vp.registerString("screensaver.clockSize",
            getter = { ss.clockSize },
            setter = { ss.clockSize = it }
        )

        // Custom clock font size (for "custom" clock size option)
        vp.registerString("screensaver.clockFontSize",
            getter = { ss.clockFontSize.toString() },
            setter = { ss.clockFontSize = it.toIntOrNull() ?: 100 }
        )

        // Launch app display (read-only)
        vp.registerString("screensaver.launchAppDisplay",
            getter = { ss.launchAppLabel.ifEmpty { "Not set" } },
            setter = { }
        )

        // HA media folder display (read-only)
        vp.registerString("screensaver.haMediaFolderDisplay",
            getter = {
                when (ss.haMediaFolder) {
                    "*" -> "All Folders"
                    "." -> "Root Only"
                    else -> ss.haMediaFolder
                }
            },
            setter = { }
        )
    }

    // ── Photos ────────────────────────────────────────────────────────────

    fun registerPhotoPreferences(vp: HaliteSettingsValueProvider, ss: ScreensaverPreferences) {
        // Photo source type
        vp.registerString("photos.sourceType",
            getter = { ss.photoSourceType },
            setter = { ss.photoSourceType = it }
        )

        // HA media folder
        vp.registerString("photos.haMediaFolder",
            getter = { ss.haMediaFolder },
            setter = { ss.haMediaFolder = it }
        )

        // HA media folder display (read-only)
        vp.registerString("photos.haMediaFolderDisplay",
            getter = {
                when (ss.haMediaFolder) {
                    "*" -> "All Folders"
                    "." -> "Root Only"
                    else -> ss.haMediaFolder
                }
            },
            setter = { }
        )

        // Immich status display (read-only)
        vp.registerString("photos.immichStatusDisplay",
            getter = {
                when {
                    !ss.hasImmichConfig -> "Not signed in"
                    com.dashieapp.Dashie.halite.settings.schema.wiring.SettingsDialogWiring
                        .immichTokenExpired -> "Sign-in expired — tap to re-authenticate"
                    else -> "Connected to ${ss.immichServerUrl}"
                }
            },
            setter = { }
        )

        // Immich albums display (read-only)
        vp.registerString("photos.immichAlbumsDisplay",
            getter = {
                val albums = ss.immichSelectedAlbums
                when {
                    albums.isEmpty() || albums == "*" -> "All Photos"
                    else -> try {
                        val arr = org.json.JSONArray(albums)
                        "${arr.length()} album${if (arr.length() != 1) "s" else ""} selected"
                    } catch (_: Exception) { "All Photos" }
                }
            },
            setter = { }
        )

        // Unsplash query
        vp.registerString("photos.unsplashQuery",
            getter = { ss.unsplashQuery },
            setter = { ss.unsplashQuery = it }
        )

        // Unsplash artist hyperlinks
        vp.registerBoolean("photos.unsplashArtistHyperlinks",
            getter = { ss.unsplashArtistHyperlinks },
            setter = { ss.unsplashArtistHyperlinks = it }
        )

        // Slideshow interval as string (for picker)
        vp.registerString("photos.slideshowInterval",
            getter = { ss.slideshowInterval.toString() },
            setter = { ss.slideshowInterval = it.toIntOrNull() ?: ScreensaverPreferences.DEFAULT_SLIDESHOW_INTERVAL }
        )

        // Slideshow transition
        vp.registerString("photos.slideshowTransition",
            getter = { ss.slideshowTransition },
            setter = { ss.slideshowTransition = it }
        )

        // Slideshow shuffle
        vp.registerBoolean("photos.slideshowShuffle",
            getter = { ss.slideshowShuffle },
            setter = { ss.slideshowShuffle = it }
        )

        // Photo scaling: "fit" (letterbox) vs "fill" (center-crop)
        vp.registerString("photos.photoFit",
            getter = { ss.photoFit },
            setter = { ss.photoFit = it }
        )

        // Show metadata
        vp.registerBoolean("photos.showMetadata",
            getter = { ss.showMetadata },
            setter = { ss.showMetadata = it }
        )

        // Summary display (read-only, for screensaver page link)
        vp.registerString("photos.summaryDisplay",
            getter = {
                val sourceLabel = when (ss.photoSourceType) {
                    "ha_media" -> "Home Assistant"
                    "google_drive" -> "Google Drive"
                    "supabase" -> "Dashie Cloud"
                    "local" -> "Local Folder"
                    "unsplash" -> "Unsplash"
                    "immich" -> "Immich"
                    else -> ss.photoSourceType
                }
                "$sourceLabel · ${ss.slideshowInterval}s"
            },
            setter = { }
        )
    }

    // ── Display Page (consolidated) ────────────────────────────────

    fun registerDisplayPagePreferences(
        vp: HaliteSettingsValueProvider,
        prefs: com.dashieapp.Dashie.halite.HalitePreferences,
        context: android.content.Context
    ) {
        val display = prefs.display
        val sleep = prefs.sleep
        val screensaver = prefs.screensaver

        // Layout mode
        vp.registerString("display.layoutMode",
            getter = { display.layoutMode },
            setter = { display.layoutMode = it })
        vp.registerString("display.layoutModeDisplay",
            getter = { when (display.layoutMode) {
                "widgets" -> "Widgets"
                "single_panel" -> "Single Panel"
                "kiosk" -> "Kiosk mode (HA-only)"
                "legacy" -> "Legacy"
                "canvas" -> "Canvas (Dev)"
                else -> display.layoutMode
            } },
            setter = { })

        // Orientation lock (only meaningful in widgets layout mode — the
        // setting entry is hidden in single_panel / kiosk via the schema's
        // visibleWhen predicate).
        vp.registerString("display.orientationLock",
            getter = { display.orientationLock },
            setter = { display.orientationLock = it })
        vp.registerString("display.orientationLockDisplay",
            getter = { when (display.orientationLock) {
                "auto" -> "Auto"
                "landscape" -> "Landscape"
                "landscape_reverse" -> "Landscape (reversed)"
                "portrait" -> "Portrait"
                "portrait_reverse" -> "Portrait (reversed)"
                else -> display.orientationLock
            } },
            setter = { })

        // Theme family (synced to WebView settingsStore)
        vp.registerString("display.themeFamily",
            getter = {
                // Read from WebView settingsStore via SharedPreferences fallback
                val storedTheme = context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .getString("theme_family", "default") ?: "default"
                storedTheme
            },
            setter = {
                context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .edit().putString("theme_family", it).apply()
            })

        // Theme display (read-only)
        vp.registerString("display.themeDisplay",
            getter = {
                val family = context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .getString("theme_family", "default") ?: "default"
                when (family) {
                    "default" -> "Default"
                    "halloween" -> "Halloween"
                    "christmas" -> "Christmas"
                    else -> family.replaceFirstChar { it.uppercase() }
                }
            },
            setter = { })

        // Animations enabled (default: off) — persisted to user_devices.display
        // via notifyAnimationsEnabledChanged callback.
        vp.registerBoolean("display.animationsEnabled",
            getter = {
                context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .getBoolean("animations_enabled", false)
            },
            setter = {
                context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("animations_enabled", it).apply()
            })

        // Animation level (default: high — matches JS default)
        vp.registerString("display.animationLevel",
            getter = {
                context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .getString("animation_level", "high") ?: "high"
            },
            setter = {
                context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .edit().putString("animation_level", it).apply()
            })

        vp.registerString("display.animationLevelDisplay",
            getter = {
                val level = context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
                    .getString("animation_level", "high") ?: "high"
                if (level == "low") "Low" else "High"
            },
            setter = { })

        // Dark mode (uses DarkModeManager SharedPreferences)
        vp.registerBoolean("display.darkMode",
            getter = {
                com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context) ?: false
            },
            setter = {
                val activity = context as? android.app.Activity ?: return@registerBoolean
                val darkModeManager = com.dashieapp.Dashie.devicecontrols.DarkModeManager(activity)
                darkModeManager.setDarkMode(it)
            })

        // Sleep summary (read-only)
        vp.registerString("display.sleepSummary",
            getter = {
                if (!sleep.sleepEnabled) "Inactive"
                else {
                    val timeStr = if (sleep.sleepMethod == "inactivity") {
                        "${formatTimeout(sleep.inactivityTimeout)} timeout"
                    } else {
                        val use24 = display.use24HourClock
                        "${formatClockTime(sleep.sleepTime, use24)} / ${formatClockTime(sleep.wakeTime, use24)}"
                    }
                    val offLabel = if (sleep.hardwareScreenOff) "Power Off" else "Black Overlay"
                    "$timeStr ($offLabel)"
                }
            },
            setter = { })

        // Screensaver summary (read-only)
        vp.registerString("display.screensaverSummary",
            getter = {
                val mode = screensaver.screensaverMode
                val timeout = screensaver.screensaverTimeout
                val modeLabels = mapOf(
                    "dim" to "Dim", "black" to "Black Overlay", "off" to "Screen Off",
                    "photos" to "Photos", "ha_page" to "HA Page", "url" to "URL", "app" to "App"
                )
                val modeStr = modeLabels[mode] ?: mode.replaceFirstChar { it.uppercase() }
                if (timeout > 0) "$modeStr, ${formatTimeout(timeout)}" else "Off"
            },
            setter = { })

        // Wake mode summary (read-only)
        vp.registerString("display.wakeModeSummary",
            getter = {
                when (screensaver.motionWakeMode) {
                    "disabled" -> "Touch Only"
                    "brightness" -> "Brightness Sensor"
                    "camera" -> "Motion (Camera)"
                    "face" -> "Face Detection (Camera)"
                    else -> "Touch Only"
                }
            },
            setter = { })

        // Wake mode (read/write)
        vp.registerString("display.motionWakeMode",
            getter = { screensaver.motionWakeMode },
            setter = { screensaver.motionWakeMode = it })

        // Motion threshold display (read-only)
        vp.registerString("display.motionThresholdDisplay",
            getter = { "${screensaver.cameraWakeThresholdDouble}%" },
            setter = { })

        // Face distance display (read-only)
        vp.registerString("display.faceDistanceDisplay",
            getter = {
                com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences.faceDistanceLabel(
                    screensaver.faceWakeDistance
                )
            },
            setter = { })
    }

    /**
     * Show a Dashie-styled restart prompt dialog matching the wake word picker style.
     */
    private fun showRestartDialog(activity: android.app.Activity, message: String) {
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Restart Required"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text = message

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative).apply {
            text = "Later"
            setOnClickListener { dialog.dismiss() }
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive).apply {
            text = "Restart Now"
            setOnClickListener {
                dialog.dismiss()
                val intent = android.content.Intent(
                    activity, com.dashieapp.Dashie.MainActivity::class.java
                )
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                activity.startActivity(intent)
                activity.finish()
            }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    private fun formatTimeout(seconds: Int): String {
        if (seconds <= 0) return "Off"
        if (seconds < 60) return "${seconds}s"
        return "${seconds / 60} min"
    }

    /** Format "HH:mm" (24-hour) as either 24-hour ("22:00") or 12-hour with AM/PM ("10:00 PM"). */
    private fun formatClockTime(hhmm: String, use24Hour: Boolean): String {
        if (use24Hour) return hhmm
        val parts = hhmm.split(":")
        if (parts.size != 2) return hhmm
        val h = parts[0].toIntOrNull() ?: return hhmm
        val m = parts[1]
        val period = if (h < 12) "AM" else "PM"
        val displayH = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return "$displayH:$m $period"
    }
}
