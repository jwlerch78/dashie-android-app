package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * General user preferences for Dashie Kiosk.
 *
 * Manages:
 * - Zip code (used for weather and timezone)
 *
 * Uses the same SharedPreferences file as HalitePreferences for backward compatibility.
 */
class GeneralPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        private const val KEY_ZIP_CODE = "zip_code"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_USE_HA_FOR_WEATHER = "use_ha_for_weather"
        private const val KEY_USE_HA_FOR_TIME = "use_ha_for_time"
        private const val KEY_CACHED_LAT = "weather_cached_latitude"
        private const val KEY_CACHED_LON = "weather_cached_longitude"
        private const val KEY_CACHED_LOCATION_KEY = "weather_cached_location_key"
        private const val KEY_WEB_LAT = "weather_web_latitude"
        private const val KEY_WEB_LON = "weather_web_longitude"
        private const val KEY_WEB_LOCATION_KEY = "weather_web_location_key"
        private const val KEY_RESOLVED_TIMEZONE = "resolved_timezone"
        private const val KEY_HIDE_INACTIVE_CONTROLS = "cc_hide_inactive_controls"
        private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check_enabled"

        /** Sentinel for [language] meaning "follow the device locale". */
        const val LANG_SYSTEM = "system"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** User's zip code, used for weather data and timezone detection */
    var zipCode: String
        get() = prefs.getString(KEY_ZIP_CODE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ZIP_CODE, value).commit() }

    /**
     * App language as a BCP-47 tag (e.g. "es-ES") or [LANG_SYSTEM] to follow the
     * device locale. Drives local Android TTS today; intended to also feed STT,
     * the AI "respond in {language}" instruction, and the UI later. Default:
     * [LANG_SYSTEM].
     */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM
        set(value) { prefs.edit().putString(KEY_LANGUAGE, value).commit() }

    /**
     * Use Home Assistant as the primary weather source. When true,
     * WeatherDataProvider fetches current conditions + forecasts from the
     * HA weather entity and only falls back to Open-Meteo for
     * precipitation % and additional days/hours HA doesn't expose. When
     * false, HA is skipped entirely and Open-Meteo (via the user's
     * location string) is the sole source. Default: true.
     * The UI toggle is hidden when the user isn't HA-linked.
     */
    var useHaForWeather: Boolean
        get() = prefs.getBoolean(KEY_USE_HA_FOR_WEATHER, true)
        set(value) { prefs.edit().putBoolean(KEY_USE_HA_FOR_WEATHER, value).commit() }

    /**
     * Use Home Assistant as the source for the current time. Useful for
     * Fire tablets that firewall Amazon's time servers — the device clock
     * drifts but HA's clock stays accurate. When true, HaTimeProvider
     * polls HA's HTTP `Date` header to compute an offset from device time
     * and the screensaver clock + Dashie UI clocks render off that
     * adjusted time. The UI toggle is hidden when the user isn't HA-linked.
     * Default: false.
     */
    var useHaForTime: Boolean
        get() = prefs.getBoolean(KEY_USE_HA_FOR_TIME, false)
        set(value) { prefs.edit().putBoolean(KEY_USE_HA_FOR_TIME, value).commit() }

    /**
     * Cached lat/lon from the last successful HA zone.home lookup. Used as
     * an Open-Meteo fallback location when HA is unreachable and the user
     * hasn't configured a zip code. Stored as Float bits in SharedPreferences
     * since Android prefs don't support Double natively. NaN means unset.
     */
    var cachedLatitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_CACHED_LAT, Double.NaN.toRawBits()))
        set(value) { prefs.edit().putLong(KEY_CACHED_LAT, value.toRawBits()).commit() }

    var cachedLongitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_CACHED_LON, Double.NaN.toRawBits()))
        set(value) { prefs.edit().putLong(KEY_CACHED_LON, value.toRawBits()).commit() }

    /**
     * The [zipCode] the cached coords were fetched for. Lets WeatherDataProvider
     * invalidate stale fallback coords when the location string changes, so a new
     * location never serves the previous location's cached coordinates.
     */
    var cachedLocationKey: String
        get() = prefs.getString(KEY_CACHED_LOCATION_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_CACHED_LOCATION_KEY, value).commit() }

    /**
     * Coordinates the in-app web resolver geocoded for [webLocationKey] and
     * pushed down via DashieJSBridge.setWeatherCoordinates. The web resolver
     * (Open-Meteo, state/country-disambiguated) handles "City STATE" and
     * international strings that the native name-geocoder can't, so when these
     * match the current [zipCode] WeatherDataProvider uses them directly instead
     * of re-geocoding the string. NaN means unset. See setWeatherCoordinates.
     */
    var webLatitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_WEB_LAT, Double.NaN.toRawBits()))
        set(value) { prefs.edit().putLong(KEY_WEB_LAT, value.toRawBits()).commit() }

    var webLongitude: Double
        get() = Double.fromBits(prefs.getLong(KEY_WEB_LON, Double.NaN.toRawBits()))
        set(value) { prefs.edit().putLong(KEY_WEB_LON, value.toRawBits()).commit() }

    /** The location string [webLatitude]/[webLongitude] were resolved for. */
    var webLocationKey: String
        get() = prefs.getString(KEY_WEB_LOCATION_KEY, "") ?: ""
        set(value) { prefs.edit().putString(KEY_WEB_LOCATION_KEY, value).commit() }

    /**
     * The canonical IANA timezone the webapp resolved from the user's configured
     * location (family.timezone — e.g. "America/New_York"), pushed via
     * DashieJSBridge.setResolvedTimezone. Native consumers (the realtime voice
     * relay) prefer this over TimeZone.getDefault() so a device with a wrong
     * default tz still answers times in the user's configured zone. Blank until
     * the webapp resolves + pushes it; callers fall back to the device default.
     */
    var resolvedTimezone: String
        get() = prefs.getString(KEY_RESOLVED_TIMEZONE, "") ?: ""
        set(value) { prefs.edit().putString(KEY_RESOLVED_TIMEZONE, value).commit() }

    /**
     * Control Center "Hide inactive" toggle. When true, the Control Center
     * hides pills for features that are off/inactive, leaving a lean list for
     * kiosk/dashboard-only users (Home Assistant when connected, Screensaver &
     * Display, Preferences, Advanced, and Account when signed in). Device-local
     * UI convenience — never synced. Default: false (show everything).
     */
    var hideInactiveControls: Boolean
        get() = prefs.getBoolean(KEY_HIDE_INACTIVE_CONTROLS, false)
        set(value) { prefs.edit().putBoolean(KEY_HIDE_INACTIVE_CONTROLS, value).commit() }

    /**
     * Whether Dashie performs its OWN automatic update checks (startup, the
     * daily refresh, and the 6-hourly self-poll in [DashieUpdateController]).
     *
     * ⚠️ Scope, because the honest name matters more than the short one: this
     * gates **our** check, not the store's. What it stops per backend:
     *  - `sideload` — our manifest fetch + APK download. A complete off-switch.
     *  - `amazon`   — our manifest fetch + our banner. **Also a real off-switch
     *                 for our traffic** (AmazonUpdateBackend fetches the same
     *                 manifest ourselves; it only hands off to the store to
     *                 INSTALL). The Appstore's own auto-update is untouched.
     *  - `play`     — our prompt + our release-notes fetch. Play's auto-update
     *                 is the STORE's behaviour and keeps running regardless.
     * So the toggle means ONE thing on every flavor ("stop Dashie's own update
     * checking"), which is the property that makes it worth shipping — a switch
     * that meant different things per flavor would be worse than none. The Play
     * caveat is surfaced in the settings subtitle rather than left implied.
     *
     * It deliberately does NOT gate the explicit "Check for Updates" action in
     * Settings → Advanced: silently no-oping a button the user just pressed is
     * the fails-quiet class this codebase keeps getting bitten by.
     *
     * Device-local, never synced (see SettingsSyncNotifier.SYNC_EXEMPT) — it
     * describes one device's maintenance behaviour, like `start_on_boot` and
     * `keep_screen_on` beside it. Default: true (today's behaviour exactly, so
     * an existing install sees no change).
     */
    var autoUpdateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, value).commit() }
}
