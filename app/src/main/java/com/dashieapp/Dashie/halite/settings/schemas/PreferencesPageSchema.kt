package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schema definition for the Preferences settings page.
 *
 * Sections:
 * 1. Interface — Show UI Tips (TV-only)
 * 2. Date & Time — 24-hour clock, date format
 * 3. Weather — show in sidebar, temperature unit, zip code
 *
 * (Start on Boot / Keep Screen On moved to Advanced > Startup & Recovery
 *  to consolidate boot/startup configuration in one place.)
 */
object PreferencesPageSchema {

    fun create() = SettingsPageSchema(
        id = "preferences",
        title = "Preferences",
        sections = listOf(
            languageSection(),
            interfaceSection(),
            dateTimeSection(),
            weatherSection()
        )
    )

    // ── Section: Language ────────────────────────────────────────────
    // App language. Drives the local Android text-to-speech voice today;
    // intended to also drive STT, the AI "respond in {language}" instruction,
    // and the UI later. "System default" follows the device locale.

    private fun languageSection() = SettingsSection(
        header = "Language",
        items = listOf(
            SchemaItem.Picker(
                id = "app_language",
                label = "Language",
                settingKey = "general.language",
                options = languageOptions(),
                onChanged = "notifyAccountPrefsChanged"
            )
        )
    )

    private fun languageOptions() = listOf(
        SchemaPickerOption("system", "System default"),
        SchemaPickerOption("en-US", "English (US)"),
        SchemaPickerOption("en-GB", "English (UK)"),
        SchemaPickerOption("es-ES", "Spanish"),
        SchemaPickerOption("es-US", "Spanish (US)"),
        SchemaPickerOption("fr-FR", "French"),
        SchemaPickerOption("de-DE", "German"),
        SchemaPickerOption("it-IT", "Italian"),
        SchemaPickerOption("pt-BR", "Portuguese (Brazil)"),
        SchemaPickerOption("nl-NL", "Dutch"),
        SchemaPickerOption("pl-PL", "Polish"),
        SchemaPickerOption("hi-IN", "Hindi"),
        SchemaPickerOption("ja-JP", "Japanese"),
        SchemaPickerOption("ko-KR", "Korean"),
        SchemaPickerOption("zh-CN", "Chinese (Simplified)")
    )

    // ── Section: Interface ───────────────────────────────────────────
    // TV-only: UI tips (hint overlays on focused widgets) exist only on
    // TV-class devices, so the whole section is hidden on tablets/phones.

    private fun interfaceSection() = SettingsSection(
        header = "Interface",
        visibleWhen = Condition.IsTrue("device.isTv"),
        items = listOf(
            SchemaItem.Toggle(
                id = "show_ui_tips",
                label = "Show User Interface Tips",
                settingKey = "display.showUiTips"
            )
        )
    )

    // ── Section 1: Date & Time ───────────────────────────────────────

    private fun dateTimeSection() = SettingsSection(
        header = "Date & Time",
        items = listOf(
            SchemaItem.Toggle(
                id = "24_hour_clock",
                label = "24-hour Clock",
                settingKey = "display.use24HourClock"
            ),
            SchemaItem.Picker(
                id = "date_format",
                label = "Date Format",
                settingKey = "display.dateFormat",
                options = listOf(
                    SchemaPickerOption("mdy", currentDateMdy()),
                    SchemaPickerOption("dmy", currentDateDmy())
                )
            ),
            // "Use HA for time" — for users whose tablets can't reach
            // public NTP / Amazon time servers (e.g., firewalled Fire
            // tablets). Polls HA's HTTP Date header to derive an offset
            // applied to the screensaver clock and other Dashie clocks.
            // Hidden when HA is not configured on this device.
            SchemaItem.Toggle(
                id = "use_ha_for_time",
                label = "Use Home Assistant for time",
                settingKey = "time.useHa",
                visibleWhen = Condition.IsTrue("home_assistant.enabled"),
                onChanged = "notifyTimeConfigChanged"
            )
        )
    )

    // ── Section 2: Weather ───────────────────────────────────────────

    private fun weatherSection() = SettingsSection(
        header = "Weather",
        items = listOf(
            SchemaItem.Picker(
                id = "temperature_unit",
                label = "Temperature Unit",
                settingKey = "display.temperatureUnit",
                options = listOf(
                    SchemaPickerOption("F", "Fahrenheit"),
                    SchemaPickerOption("C", "Celsius")
                ),
                onChanged = "notifyWeatherConfigChanged"
            ),
            // Free-text location — accepts US zip ("90210") or any
            // city+country ("Berlin, Germany"). Smart-routed in
            // WeatherDataProvider.getCoordinates(): numeric 5-digit tries
            // Zippopotam US first; anything else (or a Zippopotam miss)
            // falls back to Open-Meteo's geocoder.
            SchemaItem.TextInput(
                id = "location",
                label = "Location",
                settingKey = "general.zipCode",
                placeholder = "90210 or Berlin, Germany",
                inputType = "text",
                sublabel = "Enter a zip code, or a city and country",
                dialogMode = true,
                dialogTitle = "Location",
                onChanged = "notifyLocationConfigChanged"
            ),
            // "Use Home Assistant for weather" — hidden when HA is off on
            // this device. When true (default for HA users), WeatherDataProvider
            // uses HA as primary source + Open-Meteo for precip overlay.
            // When false, Open-Meteo is the sole source.
            SchemaItem.Toggle(
                id = "use_ha_for_weather",
                label = "Use Home Assistant for weather",
                settingKey = "weather.useHa",
                visibleWhen = Condition.IsTrue("home_assistant.enabled"),
                onChanged = "notifyWeatherConfigChanged"
            )
        )
    )

    // ── Date format helpers ─────────────────────────────────────────

    /** e.g. "March 10, 2026" */
    private fun currentDateMdy(): String {
        return SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH).format(Date())
    }

    /** e.g. "10 March 2026" */
    private fun currentDateDmy(): String {
        return SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH).format(Date())
    }
}
