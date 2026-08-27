package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * Schema definition for the Screensaver settings page.
 *
 * Matches the JS settings-screensaver-page.js layout:
 * 1. General — Timeout, Mode
 * 2. Dim Settings — Brightness, Clock, Date, Weather
 * 3. Black Screen Settings — Clock, Date, Weather, Reduce Brightness
 * 4. Photo Slideshow — Link to Photos settings, Clock, Date, Weather
 * 5. Clock Settings — Position, Size (shared across dim/black/photos)
 * 6. HA Page Settings — Page path text input
 * 7. URL Settings — URL display
 * 8. App Settings — App picker navigation
 */
object ScreensaverPageSchema {

    // ── Reusable conditions ─────────────────────────────────────────────

    private val screensaverEnabled = Condition.NotEquals("screensaver.timeout", "0")

    private fun modeIs(mode: String) = Condition.And(listOf(
        screensaverEnabled,
        Condition.Equals("screensaver.mode", mode)
    ))

    private val isDim = modeIs("dim")
    private val isBlack = modeIs("black")
    private val isPhotos = modeIs("photos")
    private val isWeather = modeIs("weather")
    private val isHaPage = modeIs("ha_page")
    private val isUrl = modeIs("url")
    private val isApp = modeIs("app")

    /** Clock visible in dim, black, or photos mode */
    private fun clockEnabledIn(modeCondition: Condition) = Condition.And(listOf(
        modeCondition,
        Condition.IsTrue("screensaver.showClock")
    ))

    private val dimClockEnabled = clockEnabledIn(isDim)
    private val blackClockEnabled = clockEnabledIn(isBlack)
    private val photosClockEnabled = clockEnabledIn(isPhotos)

    /** Clock enabled in any mode that supports it.
     *  "weather" mode always shows the clock (it's the whole point of
     *  Weather & Time) so it bypasses the showClock toggle. */
    private val anyClockEnabled = Condition.Or(listOf(
        Condition.And(listOf(
            screensaverEnabled,
            Condition.IsTrue("screensaver.showClock"),
            Condition.Or(listOf(
                Condition.Equals("screensaver.mode", "dim"),
                Condition.Equals("screensaver.mode", "black"),
                Condition.Equals("screensaver.mode", "photos")
            ))
        )),
        Condition.And(listOf(
            screensaverEnabled,
            Condition.Equals("screensaver.mode", "weather")
        ))
    ))

    /** Advanced tablet-control visibility — see AdvancedPageSchema for the full rationale. */
    private val advancedVisible = Condition.Or(listOf(
        Condition.IsTrue("home_assistant.enabled"),
        Condition.IsTrue("advanced.tabletControlsEnabled")
    ))

    /** Weather only makes sense paired with the clock — hide unless show clock is on. */
    private val showClockOn = Condition.IsTrue("screensaver.showClock")

    /** Forecast card size sub-option, only visible when weatherMode == "forecast"
     *  and the parent Weather picker is itself visible (showClock is on). */
    private fun forecastCardSizeItem(idPrefix: String, modeCondition: Condition) = SchemaItem.Picker(
        id = "${idPrefix}_forecast_card_size",
        label = "Forecast Card Size",
        settingKey = "screensaver.forecastCardSize",
        options = listOf(
            SchemaPickerOption("vsmall", "Very Small"),
            SchemaPickerOption("small", "Small"),
            SchemaPickerOption("medium", "Medium"),
            SchemaPickerOption("large", "Large"),
            SchemaPickerOption("xlarge", "Extra Large")
        ),
        visibleWhen = Condition.And(listOf(
            modeCondition,
            showClockOn,
            Condition.Equals("screensaver.weatherMode", "forecast")
        )),
        onChanged = "notifyScreensaverChanged"
    )

    /**
     * @param isTv true on Android TV form factors — used to drop modes that
     *   don't make sense without on-screen brightness/power-off control.
     * @param isAdvancedEnabled true when HA is on OR the user has opted in
     *   to Advanced Device Controls — gates the HA Page / URL / App modes.
     */
    fun create(isTv: Boolean = false, isAdvancedEnabled: Boolean = false) = SettingsPageSchema(
        id = "screensaver",
        title = "Screensaver",
        sections = listOf(
            generalSection(isTv, isAdvancedEnabled),
            dimSettingsSection(),
            blackSettingsSection(),
            photoSlideshowSection(),
            weatherTimeSection(),
            haPageSection(),
            urlSection(),
            appSection(),
            clockSettingsSection()
        )
    )

    // ── Section 1: General ──────────────────────────────────────────────

    private fun generalSection(isTv: Boolean, isAdvancedEnabled: Boolean) = SettingsSection(
        items = listOf(
            SchemaItem.Picker(
                id = "screensaver_timeout",
                label = "Timeout",
                settingKey = "screensaver.timeout",
                options = listOf(
                    SchemaPickerOption("0", "Off"),
                    SchemaPickerOption("10", "10 sec"),
                    SchemaPickerOption("30", "30 sec"),
                    SchemaPickerOption("60", "1 min"),
                    SchemaPickerOption("120", "2 min"),
                    SchemaPickerOption("300", "5 min"),
                    SchemaPickerOption("600", "10 min"),
                    SchemaPickerOption("1800", "30 min")
                ),
                onChanged = "notifyScreensaverChanged"
            ),
            SchemaItem.Picker(
                id = "screensaver_mode",
                label = "Mode",
                settingKey = "screensaver.mode",
                // Screen Off needs hardware power-off, which is not how TVs
                // are typically driven from this app. HA Page / URL / App
                // are advanced surfaces — only offer them when HA is on or
                // the user has opted in to Advanced Device Controls.
                options = buildList {
                    add(SchemaPickerOption("dim", "Dim"))
                    add(SchemaPickerOption("black", "Black Overlay"))
                    if (!isTv) add(SchemaPickerOption("off", "Screen Off"))
                    add(SchemaPickerOption("photos", "Photos"))
                    add(SchemaPickerOption("weather", "Weather & Time"))
                    if (isAdvancedEnabled) {
                        add(SchemaPickerOption("ha_page", "HA Page"))
                        add(SchemaPickerOption("url", "URL"))
                        add(SchemaPickerOption("app", "App"))
                    }
                },
                onChanged = "notifyScreensaverModeChanged",
                visibleWhen = screensaverEnabled
            )
        )
    )

    // ── Section 2: Dim Settings ─────────────────────────────────────────

    private fun dimSettingsSection() = SettingsSection(
        header = "Dim Settings",
        visibleWhen = isDim,
        items = listOf(
            SchemaItem.Picker(
                id = "dim_brightness",
                label = "Brightness",
                settingKey = "screensaver.dimBrightness",
                options = listOf(
                    SchemaPickerOption("5", "5%"),
                    SchemaPickerOption("10", "10%"),
                    SchemaPickerOption("15", "15%"),
                    SchemaPickerOption("25", "25%"),
                    SchemaPickerOption("50", "50%"),
                    SchemaPickerOption("75", "75%")
                )
            ),
            SchemaItem.Toggle(
                id = "dim_show_clock",
                label = "Show Clock",
                settingKey = "screensaver.showClock",
                onChanged = "notifyScreensaverChanged"
            ),
            SchemaItem.Toggle(
                id = "dim_show_date",
                label = "Show Date",
                settingKey = "screensaver.showDate",
                visibleWhen = dimClockEnabled
            ),
            SchemaItem.Picker(
                id = "dim_weather_mode",
                label = "Weather",
                settingKey = "screensaver.weatherMode",
                options = listOf(
                    SchemaPickerOption("disabled", "Disabled"),
                    SchemaPickerOption("current", "Simple Weather"),
                    SchemaPickerOption("forecast", "Forecast Card")
                ),
                onChanged = "notifyScreensaverChanged",
                visibleWhen = showClockOn
            ),
            forecastCardSizeItem("dim", isDim)
        )
    )

    // ── Section 3: Black Screen Settings ────────────────────────────────

    private fun blackSettingsSection() = SettingsSection(
        header = "Black Screen Settings",
        visibleWhen = isBlack,
        items = listOf(
            SchemaItem.Toggle(
                id = "black_show_clock",
                label = "Show Clock",
                settingKey = "screensaver.showClock",
                onChanged = "notifyScreensaverChanged"
            ),
            SchemaItem.Toggle(
                id = "black_show_date",
                label = "Show Date",
                settingKey = "screensaver.showDate",
                visibleWhen = blackClockEnabled
            ),
            SchemaItem.Picker(
                id = "black_weather_mode",
                label = "Weather",
                settingKey = "screensaver.weatherMode",
                options = listOf(
                    SchemaPickerOption("disabled", "Disabled"),
                    SchemaPickerOption("current", "Simple Weather"),
                    SchemaPickerOption("forecast", "Forecast Card")
                ),
                onChanged = "notifyScreensaverChanged",
                visibleWhen = showClockOn
            ),
            forecastCardSizeItem("black", isBlack),
            SchemaItem.Toggle(
                id = "black_reduce_brightness",
                label = "Reduce Brightness during screensaver",
                settingKey = "screensaver.reduceBrightnessOnBlack"
            )
        )
    )

    // ── Section 4: Photo Slideshow ───────────────────────────────────────

    private fun photoSlideshowSection() = SettingsSection(
        header = "Photo Slideshow",
        visibleWhen = isPhotos,
        items = listOf(
            SchemaItem.Navigation(
                id = "photo_settings_link",
                label = "Photo Settings",
                navigateTo = "ext:open_photos_page",
                displayValueKey = "photos.summaryDisplay"
            ),
            SchemaItem.Toggle(
                id = "photos_show_thumbnail_on_wake",
                label = "Show Thumbnail on Wake",
                settingKey = "screensaver.showPreviewOnWake",
                // Hidden in widgets layout — the photo widget on the dashboard
                // already gives the user a way back to the slideshow's photos,
                // so a corner-thumbnail-on-wake is redundant. Single-panel mode
                // has no widget grid, so the thumbnail is the only entry point.
                visibleWhen = com.dashieapp.Dashie.halite.settings.schema.Condition.NotEquals(
                    "display.layoutMode", "widgets"
                )
            ),
            SchemaItem.Toggle(
                id = "photos_show_clock",
                label = "Show Clock",
                settingKey = "screensaver.showClock",
                onChanged = "notifyScreensaverChanged"
            ),
            SchemaItem.Toggle(
                id = "photos_show_date",
                label = "Show Date",
                settingKey = "screensaver.showDate",
                visibleWhen = photosClockEnabled
            ),
            SchemaItem.Picker(
                id = "photos_weather_mode",
                label = "Weather",
                settingKey = "screensaver.weatherMode",
                options = listOf(
                    SchemaPickerOption("disabled", "Disabled"),
                    SchemaPickerOption("current", "Simple Weather"),
                    SchemaPickerOption("forecast", "Forecast Card")
                ),
                onChanged = "notifyScreensaverChanged",
                visibleWhen = showClockOn
            ),
            forecastCardSizeItem("photos", isPhotos)
        )
    )

    // ── Section: Weather & Time ─────────────────────────────────────────

    private fun weatherTimeSection() = SettingsSection(
        header = "Weather & Time",
        visibleWhen = isWeather,
        items = listOf(
            SchemaItem.Toggle(
                id = "weather_show_date",
                label = "Show Date",
                settingKey = "screensaver.showDate"
            )
        )
    )

    // ── Section 7: HA Page Settings ─────────────────────────────────────

    private fun haPageSection() = SettingsSection(
        header = "HA Page Settings",
        visibleWhen = Condition.And(listOf(isHaPage, advancedVisible)),
        items = listOf(
            SchemaItem.TextInput(
                id = "ha_page_path",
                label = "Page Path",
                settingKey = "screensaver.haPagePath",
                placeholder = "e.g. lovelace-kitchen/weather"
            )
        ),
        footer = "Path after your HA base URL"
    )

    // ── Section 8: URL Settings ─────────────────────────────────────────

    private fun urlSection() = SettingsSection(
        header = "URL Settings",
        visibleWhen = Condition.And(listOf(isUrl, advancedVisible)),
        items = listOf(
            SchemaItem.TextInput(
                id = "screensaver_url",
                label = "URL",
                settingKey = "screensaver.screensaverUrl",
                placeholder = "https://example.com",
                inputType = "url"
            )
        )
    )

    // ── Section 9: App Settings ─────────────────────────────────────────

    private fun appSection() = SettingsSection(
        header = "App Settings",
        visibleWhen = Condition.And(listOf(isApp, advancedVisible)),
        items = listOf(
            SchemaItem.Navigation(
                id = "screensaver_app",
                label = "App",
                navigateTo = "ext:app_picker",
                displayValueKey = "screensaver.launchAppDisplay"
            )
        )
    )

    // ── Section 10: Date & Time Settings ─────────────────────────────────

    private fun clockSettingsSection() = SettingsSection(
        header = "Date & Time Settings",
        visibleWhen = anyClockEnabled,
        items = listOf(
            SchemaItem.Picker(
                id = "clock_position",
                label = "Position",
                settingKey = "screensaver.clockPosition",
                options = listOf(
                    SchemaPickerOption("top", "Top"),
                    SchemaPickerOption("bottom", "Bottom"),
                    SchemaPickerOption("random", "Random")
                ),
                onChanged = "notifyScreensaverChanged"
            ),
            SchemaItem.Picker(
                id = "clock_size",
                label = "Size",
                settingKey = "screensaver.clockSize",
                options = listOf(
                    SchemaPickerOption("vsmall", "Very Small (40pt)"),
                    SchemaPickerOption("small", "Small (60pt)"),
                    SchemaPickerOption("medium", "Medium (80pt)"),
                    SchemaPickerOption("large", "Large (100pt)"),
                    SchemaPickerOption("xlarge", "Extra Large (120pt)"),
                    SchemaPickerOption(
                        value = "custom",
                        label = "Custom",
                        isCustomInput = true,
                        customInputSettingKey = "screensaver.clockFontSize",
                        customInputTitle = "Custom Size (pt)",
                        customInputHint = "e.g. 100",
                        customInputUnit = "pt"
                    )
                ),
                onChanged = "notifyScreensaverChanged"
            )
        )
    )
}
