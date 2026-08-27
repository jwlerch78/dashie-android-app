package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.HalitePreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fetches weather data: HA primary (current + forecasts), Open-Meteo fallback.
 *
 * Strategy:
 * 1. Try HA for current weather + daily/hourly forecasts
 * 2. If HA unavailable or missing forecasts, fall back to Open-Meteo
 * 3. Location for Open-Meteo: zip code → geocode, or HA zone.home
 */
class WeatherDataProvider(
    private val halitePrefs: HalitePreferences,
    private val entityId: String = "weather.forecast_home",
    context: Context? = null
) {
    companion object {
        private const val TAG = "WeatherData"
        private const val DEFAULT_REFRESH_MS = 15 * 60 * 1000L // 15 minutes
        private const val OPEN_METEO_BASE = "https://api.open-meteo.com/v1/forecast"
        private const val ZIPPOPOTAM_BASE = "https://api.zippopotam.us/us"
        private const val OPEN_METEO_GEOCODER = "https://geocoding-api.open-meteo.com/v1/search"
        private const val CACHE_PREFS = "dashie_weather_cache"
        private const val CACHE_KEY_DATA = "lastWeatherData"
        // Cache stays usable for 1 hour. Beyond that, the forecast is too
        // stale to show as a placeholder while we fetch fresh — better to
        // show the loading spinner than misleading old conditions.
        private const val CACHE_MAX_AGE_MS = 60 * 60 * 1000L
        private const val CACHE_KEY_TIMESTAMP = "lastWeatherTimestamp"

        // ── Process-wide fetch coordination ──────────────────────────────
        // Three WeatherDataProvider instances run concurrently — the
        // daily-forecast widget, the hourly-forecast widget, and the
        // screensaver overlay — each with its own poll loop. Without
        // coordination they fire near-simultaneous, identical Open-Meteo
        // requests on startup; the burst gets HTTP 429'd and weather breaks
        // entirely for non-HA users (Open-Meteo is their only source).
        //
        // sharedFetchLock serializes the network fetch; the last result is
        // cached process-wide and reused (no network) by instances that fetch
        // within the window of the last *attempt* — keyed on attempt, not
        // success, so a string of failures (e.g. Open-Meteo 429) doesn't let
        // every instance re-hit the network. A success is held longer
        // (SHARED_RESULT_TTL_MS); a failure is retried sooner
        // (SHARED_FAIL_BACKOFF_MS) so weather recovers quickly once Open-Meteo
        // un-throttles. A user-triggered refresh() passes force=true to bypass
        // the window so a zip / temp-unit change re-fetches immediately.
        private val sharedFetchLock = Any()
        @Volatile private var sharedResult: WeatherData? = null
        @Volatile private var sharedAttemptAtMs: Long = 0L
        private const val SHARED_RESULT_TTL_MS = 5 * 60 * 1000L  // reuse a success for 5 min
        private const val SHARED_FAIL_BACKOFF_MS = 90 * 1000L    // retry a failure after 90s
        // Even a forced refresh reuses a fetch this recent — collapses bursts
        // of refresh() calls (settings-sync fan-out, both weather widgets
        // refreshing together) without re-hitting the network. Short enough
        // that a genuine user zip/unit change still re-fetches.
        private const val SHARED_FORCE_DEDUP_MS = 5 * 1000L

        /**
         * The last successfully-fetched weather, process-wide — populated by whichever
         * surface is polling (the weather widget or screensaver overlay), already
         * HONORING the HA↔Open-Meteo toggle. The voice weather tool reads this so a
         * spoken answer matches what the dashboard shows, without starting its own poll.
         * Null until some surface has fetched at least once.
         */
        fun sharedSnapshot(): WeatherData? = sharedResult

        /**
         * Voice path (WeatherVoiceTool): the shared snapshot, fetching SYNCHRONOUSLY on
         * the calling (background) thread when no surface has populated it yet — a fresh
         * boot where neither the weather widget nor the screensaver has run must not make
         * "what's the weather" fail (found on the Samsung kiosk, 2026-07-19). Uses the
         * SAME shared lock + attempt windows as the surface fetches (429-storm safe), the
         * same entity/toggle prefs, and updates [sharedResult] so the next caller is warm.
         * NEVER call on the main thread.
         */
        fun sharedSnapshotOrFetch(halitePrefs: com.dashieapp.Dashie.halite.HalitePreferences, context: android.content.Context?): WeatherData? {
            sharedResult?.let { return it }
            return synchronized(sharedFetchLock) {
                sharedResult ?: run {
                    val ageMs = System.currentTimeMillis() - sharedAttemptAtMs
                    if (ageMs < SHARED_FAIL_BACKOFF_MS && sharedAttemptAtMs != 0L) {
                        Log.i(TAG, "🌤️ voice fetch: recent attempt failed ${ageMs / 1000}s ago — backing off")
                        null
                    } else {
                        sharedAttemptAtMs = System.currentTimeMillis()
                        val provider = WeatherDataProvider(
                            halitePrefs, halitePrefs.screensaver.weatherEntityId, context)
                        val d = provider.fetchWeatherFromNetwork()
                        if (d != null) sharedResult = d
                        Log.i(TAG, "🌤️ voice on-demand fetch: ${if (d != null) "ok" else "FAILED"}")
                        d
                    }
                }
            }
        }
    }

    // Optional disk cache — restored on first fetch attempt, written after
    // every successful fetch. Without it, every cold start (or first dim
    // cycle after process death) waits ~3-5s on the network before showing
    // any weather. With it, the overlay shows the previous forecast
    // instantly while the network call runs in the background.
    private val cachePrefs: SharedPreferences? =
        context?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    data class ForecastDay(
        val dayName: String,
        val condition: String,
        val tempHigh: Double,
        val tempLow: Double,
        val precipProbability: Int = 0  // 0-100, rounded to nearest 5%
    )

    data class HourlyForecast(
        val time: String,       // "3PM", "4PM", etc.
        val condition: String,
        val temperature: Double,
        val precipProbability: Int = 0  // 0-100, rounded to nearest 5%
    )

    data class WeatherData(
        val condition: String,
        val temperature: Double,
        val tempUnit: String,
        val humidity: Int?,
        val windSpeed: Double?,
        val windUnit: String?,
        val forecast: List<ForecastDay>,
        val hourly: List<HourlyForecast> = emptyList()
    )

    var onWeatherUpdated: ((WeatherData) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** Fired when the fetch can't even attempt because no location is
     *  configured (empty zip code AND no HA zone.home). Used by
     *  WeatherWidgetController to render the friendly "Update location in
     *  preferences to show weather" empty state instead of the generic
     *  "Could not fetch weather data" error. */
    var onNoLocation: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var lastData: WeatherData? = null
    private var refreshIntervalMs = DEFAULT_REFRESH_MS

    init {
        // Restore cached weather from disk so the overlay shows something
        // immediately on cold start. Network fetch in start() refreshes it.
        if (cachePrefs == null) {
            Log.w(TAG, "💾 Weather cache: NO CONTEXT — disk cache disabled")
        } else {
            val cachedJson = cachePrefs.getString(CACHE_KEY_DATA, null)
            val ts = cachePrefs.getLong(CACHE_KEY_TIMESTAMP, 0L)
            when {
                cachedJson == null -> Log.i(TAG, "💾 Weather cache: empty (cold start, no prior fetch)")
                System.currentTimeMillis() - ts > CACHE_MAX_AGE_MS -> {
                    val ageMin = (System.currentTimeMillis() - ts) / 60_000
                    Log.i(TAG, "💾 Weather cache: stale (age=${ageMin}min, max=${CACHE_MAX_AGE_MS / 60_000}min), discarding")
                }
                else -> try {
                    lastData = deserializeWeather(JSONObject(cachedJson))
                    val ageSec = (System.currentTimeMillis() - ts) / 1000
                    Log.i(TAG, "💾 Weather cache: HIT (age=${ageSec}s) — ${lastData?.forecast?.size ?: 0} daily, ${lastData?.hourly?.size ?: 0} hourly")
                } catch (e: Exception) {
                    Log.w(TAG, "💾 Weather cache: deserialize failed — ${e.message}")
                }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Faster client used for HA calls only. HA lives on the local network
     * (typically <100ms RTT), so a 10s connect timeout meant a full 10s
     * wait before falling back to Open-Meteo when HA was unreachable.
     * 3s is plenty for any healthy HA instance and gets the user to the
     * Open-Meteo fallback ~7s sooner during outages.
     */
    // Accepts self-signed certs for LAN HA URLs.
    private val haHttpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        // OkHttp's default retryOnConnectionFailure=true was making the HA
        // fetch take ~10s when HA was offline: each connect timeout (3s)
        // would route-retry up to 3 times. HA is on the local LAN — if it
        // doesn't answer the first attempt it's not coming back. Single
        // attempt = single 3s timeout, so total fetchWeather drops to ~3s.
        .retryOnConnectionFailure(false)
        .build()

    // Tracks the HA entity's native temperature unit (set during parseHaWeatherJson)
    private var haEntityTempUnit: String = ""

    // Circuit breaker for HA reachability. After a failed HA fetch, skip
    // HA for HA_SKIP_AFTER_FAIL_MS so subsequent screensaver activations
    // don't pay the 3s connect-timeout cost again — they go straight to
    // Open-Meteo (~500ms). The first fetch after the window expires
    // probes HA again; if HA is back, the breaker resets.
    @Volatile private var haLastFailMs: Long = 0L
    private val haSkipAfterFailMs = 60 * 1000L  // 60s

    private val pollRunnable = object : Runnable {
        override fun run() {
            fetchWeather()
            if (isRunning) handler.postDelayed(this, refreshIntervalMs)
        }
    }

    // Top-of-hour refresh. The 15-min data poll (pollRunnable) is NOT aligned
    // to the clock, and the hourly forecast's "Now" label + past-hour filter
    // are baked at fetch time (see the hourly parse: first non-past hour →
    // "Now"). So without an aligned refresh the hourly window can sit up to a
    // full poll interval past the hour change before rolling over. This fires a
    // fetch a few seconds after each :00 so "Now" advances promptly on the hour,
    // then reschedules for the next hour. Covers every surface that runs a
    // provider (screensaver overlay + native dashboard widget).
    private val hourlyBoundaryRunnable = object : Runnable {
        override fun run() {
            Log.i(TAG, "🕐 Top-of-hour weather refresh — rolling hourly window")
            fetchWeather()
            if (isRunning) scheduleHourlyBoundary()
        }
    }

    private fun scheduleHourlyBoundary() {
        val now = System.currentTimeMillis()
        val next = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            // A few seconds past :00 so the just-completed hour is filtered out
            // (the parse drops buckets older than now-1h) and "Now" lands on the
            // new hour rather than the one that just ended.
            set(Calendar.SECOND, 5)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val delay = next - now
        handler.postDelayed(hourlyBoundaryRunnable, delay)
        Log.i(TAG, "🕐 Next top-of-hour weather refresh in ${delay / 1000}s")
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        // Emit cached data to listeners immediately so the view doesn't sit
        // on "Loading weather..." while the first fetch is in flight (or
        // hung — e.g., HA fetch stuck inside sharedFetchLock blocks the
        // emission path indefinitely). Cache HIT at init() loaded prior
        // data into `lastData`; pushing it here means the widget paints
        // the last good data immediately, and any successful fetch later
        // refreshes it. Listener wiring happens in WeatherWidgetController
        // before start() so callbacks are guaranteed set.
        lastData?.let { cached ->
            Log.i(TAG, "🌤️ Emitting cached weather data on start (${cached.forecast.size} daily, ${cached.hourly.size} hourly)")
            handler.post { onWeatherUpdated?.invoke(cached) }
        }
        fetchWeather()
        handler.postDelayed(pollRunnable, refreshIntervalMs)
        scheduleHourlyBoundary()
        Log.i(TAG, "Started polling every ${refreshIntervalMs / 60000}min")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        handler.removeCallbacks(hourlyBoundaryRunnable)
        Log.i(TAG, "Stopped polling")
    }

    fun getLastData(): WeatherData? = lastData

    /**
     * Force an immediate weather re-fetch, bypassing the 15-minute poll
     * schedule. Called from WeatherWidgetController when the user changes
     * a weather-relevant preference (useHaForWeather, zip/location).
     */
    fun refresh() {
        if (!isRunning) return
        Log.i(TAG, "Forcing weather re-fetch (user-triggered)")
        fetchWeather(force = true)
    }

    /**
     * Fetch weather and emit to this instance's listeners.
     *
     * @param force when true (user-triggered refresh) the process-wide
     *   shared-result TTL is bypassed so a zip / temp-unit change re-fetches.
     *   Poll- and startup-driven fetches pass false so concurrent instances
     *   collapse onto a single Open-Meteo request — see [sharedFetchLock].
     */
    private fun fetchWeather(force: Boolean = false) {
        Thread {
            val fetchStart = System.currentTimeMillis()

            // Serialize the network fetch across all WeatherDataProvider
            // instances and reuse a recent shared result. This is what
            // prevents the Open-Meteo 429 storm that broke weather for
            // non-HA users (see sharedFetchLock doc).
            var didNetworkFetch = false
            val merged: WeatherData? = synchronized(sharedFetchLock) {
                val ageMs = System.currentTimeMillis() - sharedAttemptAtMs
                // Window since the last attempt — within it, reuse the shared
                // result instead of re-hitting the network. A forced refresh
                // uses a short window (collapses refresh() bursts but still
                // honors a real user change); otherwise a success is held
                // 5 min and a failure retried after 90s.
                val window = when {
                    force                -> SHARED_FORCE_DEDUP_MS
                    sharedResult != null -> SHARED_RESULT_TTL_MS
                    else                 -> SHARED_FAIL_BACKOFF_MS
                }
                if (sharedAttemptAtMs > 0L && ageMs < window) {
                    Log.i(TAG, "🌤️ Fetch — reusing shared result (age ${ageMs / 1000}s, got=${sharedResult != null}), skipping network")
                    sharedResult
                } else {
                    didNetworkFetch = true
                    val fresh = fetchWeatherFromNetwork()
                    sharedResult = fresh
                    sharedAttemptAtMs = System.currentTimeMillis()
                    fresh
                }
            }

            val fetchDurationMs = System.currentTimeMillis() - fetchStart
            if (merged != null) {
                lastData = merged
                // Only the instance that actually hit the network rewrites
                // the (shared) disk cache — reusing instances would just
                // write back identical bytes.
                if (didNetworkFetch) writeCache(merged)
                Log.i(TAG, "🌤️ Fetch DONE (${fetchDurationMs}ms, networkFetch=$didNetworkFetch)")
                handler.post { onWeatherUpdated?.invoke(merged) }
            } else if (lastData != null) {
                // Transient failure — both HA and Open-Meteo briefly
                // unavailable but we have a prior good fetch (in-memory or
                // disk-cached). Re-emit the last good data instead of
                // surfacing onError, which would partially overwrite the
                // top row (icon + condition + temp) with the error message
                // while leaving the forecast row intact, producing the
                // contradictory "Could not fetch weather data" caption
                // sitting above a perfectly-rendered 5-day forecast.
                Log.w(TAG, "🌤️ Fetch FAILED (${fetchDurationMs}ms) — keeping lastData (cache holds; will retry next poll)")
                handler.post { onWeatherUpdated?.invoke(lastData!!) }
            } else {
                // No prior data to fall back on. Distinguish "no location
                // configured" from a transient fetch failure so the widget
                // can show the friendlier "Update location in preferences"
                // state. Empty zipCode is the common signal — HA fallback
                // only runs when useHa is true, and even then a HA without
                // zone.home can't geocode either.
                val zipMissing = halitePrefs.general.zipCode.trim().isEmpty()
                if (zipMissing) {
                    Log.w(TAG, "🌤️ Fetch FAILED (${fetchDurationMs}ms) — no zip code configured")
                    handler.post { onNoLocation?.invoke() }
                } else {
                    Log.w(TAG, "🌤️ Fetch FAILED (${fetchDurationMs}ms) — both HA and Open-Meteo unavailable")
                    handler.post { onError?.invoke("Could not fetch weather data") }
                }
            }
        }.start()
    }

    /**
     * Perform the HA + Open-Meteo network fetch and merge. Called inside
     * [sharedFetchLock] so only one instance hits the network at a time.
     * Returns the merged WeatherData, or null when both sources fail.
     */
    private fun fetchWeatherFromNetwork(): WeatherData? {
        Log.i(TAG, "🌤️ Fetch START (cache age: ${cacheAgeStr()})")

        // HA primary for current weather + forecasts, Open-Meteo for precip % overlay.
            // User can disable HA as the weather source (e.g. non-HA user flow),
            // in which case we skip the HA fetch entirely and use Open-Meteo only.
            //
            // Run HA + Open-Meteo in PARALLEL so HA's 3s connect timeout
            // doesn't serialize ahead of the Open-Meteo fetch when HA is
            // offline (was ~5s end-to-end → ~3s, capped by HA timeout
            // alone since Open-Meteo is fast on a healthy network).
            val useHa = halitePrefs.general.useHaForWeather

            var data: WeatherData? = null
            var openMeteoData: WeatherData? = null

            // Circuit-break HA: if a recent fetch failed, skip HA entirely
            // for HA_SKIP_AFTER_FAIL_MS. Total fetch becomes Open-Meteo
            // only (~500ms) instead of waiting for HA's 3-10s timeout.
            val haRecentlyFailed = haLastFailMs > 0 &&
                (System.currentTimeMillis() - haLastFailMs) < haSkipAfterFailMs
            val haThread = if (useHa && !haRecentlyFailed) Thread {
                val t0 = System.currentTimeMillis()
                data = fetchFromHA()
                val elapsed = System.currentTimeMillis() - t0
                if (data == null) haLastFailMs = System.currentTimeMillis()
                else haLastFailMs = 0L  // reset breaker on success
                Log.i(TAG, "🌤️   ha thread done in ${elapsed}ms (got=${data != null})")
            }.apply { start() } else null
            if (useHa && haRecentlyFailed) {
                val ageS = (System.currentTimeMillis() - haLastFailMs) / 1000
                Log.i(TAG, "🌤️   ha SKIPPED — recent failure ${ageS}s ago, going OM-only")
            }
            val omThread = Thread {
                val t0 = System.currentTimeMillis()
                openMeteoData = fetchFromOpenMeteo()
                Log.i(TAG, "🌤️   om thread done in ${System.currentTimeMillis() - t0}ms (got=${openMeteoData != null})")
            }.apply { start() }
            haThread?.join()
            omThread.join()

            // Snapshot to local immutable refs so smart-casts work below
            // (Kotlin can't smart-cast `data` / `openMeteoData` because they
            // were mutated inside the capturing thread closures).
            val haData = data
            val omData = openMeteoData

            var merged: WeatherData? = if (haData != null && omData != null) {
                // Overlay precip % from Open-Meteo onto HA forecasts
                val augmentedDaily = haData.forecast.map { haDay ->
                    val omMatch = omData.forecast.find { it.dayName == haDay.dayName }
                    if (omMatch != null) haDay.copy(precipProbability = omMatch.precipProbability) else haDay
                }.toMutableList()

                // Append extra days from Open-Meteo beyond what HA provides
                if (augmentedDaily.size < 9 && omData.forecast.size > augmentedDaily.size) {
                    val extraDays = omData.forecast.drop(augmentedDaily.size)
                    augmentedDaily.addAll(extraDays.take(9 - augmentedDaily.size))
                }

                val augmentedHourly = haData.hourly.map { haHour ->
                    val omMatch = omData.hourly.find { it.time == haHour.time }
                    if (omMatch != null) haHour.copy(precipProbability = omMatch.precipProbability) else haHour
                }.toMutableList()

                // Append extra hours from Open-Meteo beyond what HA provides
                if (augmentedHourly.size < 10 && omData.hourly.size > augmentedHourly.size) {
                    val extraHours = omData.hourly.drop(augmentedHourly.size)
                    augmentedHourly.addAll(extraHours.take(10 - augmentedHourly.size))
                }

                Log.d(TAG, "HA + Open-Meteo: ${augmentedDaily.size} daily, ${augmentedHourly.size} hourly")
                haData.copy(forecast = augmentedDaily, hourly = augmentedHourly)
            } else {
                haData ?: omData
            }

            Log.d(TAG, "Network fetch result — ha=${haData != null}, om=${omData != null}, merged=${merged != null}")
            return merged
    }

    private fun cacheAgeStr(): String {
        val ts = cachePrefs?.getLong(CACHE_KEY_TIMESTAMP, 0L) ?: return "no-cache"
        if (ts == 0L) return "empty"
        val ageSec = (System.currentTimeMillis() - ts) / 1000
        return if (ageSec < 60) "${ageSec}s" else "${ageSec / 60}m"
    }

    private fun writeCache(data: WeatherData) {
        if (cachePrefs == null) {
            Log.w(TAG, "💾 Weather cache: skip write (no context)")
            return
        }
        cachePrefs.edit()
            .putString(CACHE_KEY_DATA, serializeWeather(data).toString())
            .putLong(CACHE_KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "💾 Weather cache: wrote ${data.forecast.size} daily + ${data.hourly.size} hourly")
    }

    private fun serializeWeather(d: WeatherData): JSONObject {
        return JSONObject().apply {
            put("condition", d.condition)
            put("temperature", d.temperature)
            put("tempUnit", d.tempUnit)
            d.humidity?.let { put("humidity", it) }
            d.windSpeed?.let { put("windSpeed", it) }
            d.windUnit?.let { put("windUnit", it) }
            put("forecast", JSONArray().apply {
                d.forecast.forEach { day ->
                    put(JSONObject().apply {
                        put("dayName", day.dayName)
                        put("condition", day.condition)
                        put("tempHigh", day.tempHigh)
                        put("tempLow", day.tempLow)
                        put("precipProbability", day.precipProbability)
                    })
                }
            })
            put("hourly", JSONArray().apply {
                d.hourly.forEach { hour ->
                    put(JSONObject().apply {
                        put("time", hour.time)
                        put("condition", hour.condition)
                        put("temperature", hour.temperature)
                        put("precipProbability", hour.precipProbability)
                    })
                }
            })
        }
    }

    private fun deserializeWeather(json: JSONObject): WeatherData {
        val forecast = mutableListOf<ForecastDay>()
        val forecastArr = json.optJSONArray("forecast") ?: JSONArray()
        for (i in 0 until forecastArr.length()) {
            val obj = forecastArr.getJSONObject(i)
            forecast.add(
                ForecastDay(
                    dayName = obj.getString("dayName"),
                    condition = obj.getString("condition"),
                    tempHigh = obj.getDouble("tempHigh"),
                    tempLow = obj.getDouble("tempLow"),
                    precipProbability = obj.optInt("precipProbability", 0)
                )
            )
        }
        val hourly = mutableListOf<HourlyForecast>()
        val hourlyArr = json.optJSONArray("hourly") ?: JSONArray()
        for (i in 0 until hourlyArr.length()) {
            val obj = hourlyArr.getJSONObject(i)
            hourly.add(
                HourlyForecast(
                    time = obj.getString("time"),
                    condition = obj.getString("condition"),
                    temperature = obj.getDouble("temperature"),
                    precipProbability = obj.optInt("precipProbability", 0)
                )
            )
        }
        return WeatherData(
            condition = json.getString("condition"),
            temperature = json.getDouble("temperature"),
            tempUnit = json.getString("tempUnit"),
            humidity = if (json.has("humidity")) json.getInt("humidity") else null,
            windSpeed = if (json.has("windSpeed")) json.getDouble("windSpeed") else null,
            windUnit = json.optString("windUnit").ifEmpty { null },
            forecast = forecast,
            hourly = hourly
        )
    }

    // =========================================================================
    // HA REST API (primary)
    // =========================================================================

    private fun getHaCredentials(): Pair<String, String>? {
        // Don't trigger a token refresh from the weather path — refresh
        // calls use the standard HaTokenExtractor httpClient (10s timeout
        // + default retries), so if HA is offline AND the token has
        // expired, getValidCredentialsSync would block for ~10s on a
        // refresh attempt that we don't actually need. Weather is a
        // low-priority background fetch; if the token is stale, just
        // skip HA and use Open-Meteo for this cycle. Some other code
        // path (the main dashboard, settings, etc.) will refresh it.
        val conn = halitePrefs.connection
        val baseUrl = conn.haBaseUrl.ifEmpty { conn.haUrl.substringBefore("?").trimEnd('/') }
        if (baseUrl.isEmpty()) return null
        val token = conn.haAccessToken
        if (token.isEmpty()) return null
        if (conn.isHaTokenExpired) {
            Log.d(TAG, "🌤️ Skipping HA fetch: token expired (let main app refresh it)")
            return null
        }
        return Pair(baseUrl, token)
    }

    private fun fetchFromHA(): WeatherData? {
        val (baseUrl, token) = getHaCredentials() ?: return null

        return try {
            // Step 1: Current weather from entity state
            val request = Request.Builder()
                .url("$baseUrl/api/states/$entityId")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            var result = haHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parseHaWeatherJson(body)
            } ?: return null

            // Step 2: Daily forecast via service call
            val dailyForecast = fetchHaForecast(baseUrl, token, "daily")
            if (dailyForecast.isNotEmpty()) {
                result = result.copy(forecast = dailyForecast)
            }

            // Step 3: Hourly forecast via service call
            val hourlyForecast = fetchHaHourlyForecast(baseUrl, token)
            if (hourlyForecast.isNotEmpty()) {
                result = result.copy(hourly = hourlyForecast)
            }

            Log.d(TAG, "HA: ${result.forecast.size} daily, ${result.hourly.size} hourly")
            result
        } catch (e: Exception) {
            Log.w(TAG, "HA fetch failed: ${e.message}")
            null
        }
    }

    private fun fetchHaForecast(baseUrl: String, token: String, type: String): List<ForecastDay> {
        return try {
            val url = "$baseUrl/api/services/weather/get_forecasts?return_response"
            val jsonBody = """{"entity_id":"$entityId","type":"$type"}"""
            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            haHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseHaDailyServiceResponse(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HA $type forecast failed: ${e.message}")
            emptyList()
        }
    }

    private fun fetchHaHourlyForecast(baseUrl: String, token: String): List<HourlyForecast> {
        return try {
            val url = "$baseUrl/api/services/weather/get_forecasts?return_response"
            val jsonBody = """{"entity_id":"$entityId","type":"hourly"}"""
            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Authorization", "Bearer $token")
                .build()

            haHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseHaHourlyJson(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HA hourly forecast failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get lat/lon from HA's zone.home entity — fallback when no zip code configured.
     */
    private fun fetchHaHomeLocation(): Pair<Double, Double>? {
        val (baseUrl, token) = getHaCredentials() ?: return null

        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/states/zone.home")
                .get()
                .addHeader("Authorization", "Bearer $token")
                .build()

            haHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val obj = JSONObject(body)
                val attrs = obj.getJSONObject("attributes")
                val lat = attrs.optDouble("latitude", Double.NaN)
                val lon = attrs.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return null
                Log.d(TAG, "Got location from HA zone.home: $lat, $lon")
                // Cache so Open-Meteo can still get a location when HA is
                // later unreachable (HA outage / network blip). Tag with the
                // current location string so a later location change invalidates it.
                halitePrefs.general.cachedLatitude = lat
                halitePrefs.general.cachedLongitude = lon
                halitePrefs.general.cachedLocationKey = halitePrefs.general.zipCode.trim()
                Pair(lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "HA zone.home fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Last-resort coordinates when HA zone.home is unreachable. Returns a
     * cached value from the most recent successful HA fetch, or null on
     * first run / before any HA call has succeeded.
     */
    private fun cachedHaCoordinates(): Pair<Double, Double>? {
        // Invalidate fallback coords cached for a DIFFERENT location string —
        // otherwise a changed zip whose geocode + HA fetch both fail would
        // silently serve the previous location's coords (e.g. "denver, co"
        // keeping the old Clearwater FL coordinates).
        if (halitePrefs.general.cachedLocationKey != halitePrefs.general.zipCode.trim()) {
            Log.d(TAG, "Cached coords are for a different location — invalidating")
            return null
        }
        val lat = halitePrefs.general.cachedLatitude
        val lon = halitePrefs.general.cachedLongitude
        if (lat.isNaN() || lon.isNaN()) return null
        Log.d(TAG, "Using cached HA coordinates ($lat, $lon) — HA appears unreachable")
        return Pair(lat, lon)
    }

    // =========================================================================
    // Open-Meteo API (fallback)
    // =========================================================================

    private fun fetchFromOpenMeteo(): WeatherData? {
        val coords = getCoordinates()
        if (coords == null) {
            Log.w(TAG, "No location available (no zip code, no HA zone.home)")
            return null
        }

        return try {
            val tempUnit = halitePrefs.display.temperatureUnit
            val tempParam = if (tempUnit == "C") "celsius" else "fahrenheit"

            val url = "$OPEN_METEO_BASE?" +
                "latitude=${coords.first}&longitude=${coords.second}" +
                "&current_weather=true" +
                "&daily=temperature_2m_max,temperature_2m_min,weathercode,precipitation_probability_max" +
                "&hourly=temperature_2m,weathercode,precipitation_probability" +
                "&temperature_unit=$tempParam" +
                "&windspeed_unit=mph" +
                "&timezone=auto" +
                "&forecast_days=10"

            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Open-Meteo returned HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                Log.d(TAG, "Using Open-Meteo fallback")
                // Pass the SAME unit used to build the request (tempParam) so the
                // parsed label can't disagree with the fetched values. Re-reading
                // the pref at parse time raced a concurrent unit change (rapid
                // toggle / boot account-sync convergence): the request returned
                // Celsius while the label got stamped °F → 27°F for an 80°F reading.
                parseOpenMeteoJson(body, tempUnit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Coordinates the in-app web resolver geocoded for the current location and
     * pushed via setWeatherCoordinates. The web geocoder is state/country-
     * disambiguated and resolves "City STATE" / international strings our native
     * name-geocoder can't (it returns nothing for "Lexington KY"). Gated on the
     * location string matching the current zip so coords from a previous
     * location are ignored. Returns null when unset or stale.
     */
    private fun webProvidedCoordinates(): Pair<Double, Double>? {
        val key = halitePrefs.general.webLocationKey
        if (key.isEmpty() || key != halitePrefs.general.zipCode.trim()) return null
        val lat = halitePrefs.general.webLatitude
        val lon = halitePrefs.general.webLongitude
        if (lat.isNaN() || lon.isNaN()) return null
        return Pair(lat, lon)
    }

    private fun getCoordinates(): Pair<Double, Double>? {
        val tStart = System.currentTimeMillis()

        // Web resolver's coords win: they cover the "City STATE" / international
        // forms our native geocoder fails on, and skip a redundant geocode.
        val webCoords = webProvidedCoordinates()
        if (webCoords != null) {
            Log.i(TAG, "🌤️     getCoordinates: using web-provided coords (${webCoords.first}, ${webCoords.second})")
            return webCoords
        }

        val location = halitePrefs.general.zipCode.trim()  // free-text: zip or "City, Country"
        if (location.isNotEmpty()) {
            val isUsZip = location.matches(Regex("^\\d{5}$"))
            if (isUsZip) {
                val coords = geocodeUsZip(location)
                if (coords != null) {
                    Log.i(TAG, "🌤️     getCoordinates: zip-geocoded in ${System.currentTimeMillis() - tStart}ms")
                    return coords
                }
                Log.d(TAG, "Zippopotam miss for zip '$location' — falling through to open-meteo geocoder")
            }
            val omCoords = geocodeFreeText(location)
            if (omCoords != null) {
                Log.i(TAG, "🌤️     getCoordinates: text-geocoded in ${System.currentTimeMillis() - tStart}ms")
                return omCoords
            }
            Log.w(TAG, "Could not geocode location '$location', trying HA zone.home")
        }
        val tHa = System.currentTimeMillis()
        val haCoords = fetchHaHomeLocation()
        Log.i(TAG, "🌤️     getCoordinates: HA zone.home took ${System.currentTimeMillis() - tHa}ms (got=${haCoords != null})")
        if (haCoords != null) return haCoords
        val cached = cachedHaCoordinates()
        if (cached != null) {
            Log.i(TAG, "🌤️     getCoordinates: using cached HA coords (total ${System.currentTimeMillis() - tStart}ms)")
            return cached
        }
        val tIp = System.currentTimeMillis()
        val ipCoords = fetchIpGeolocation()
        Log.i(TAG, "🌤️     getCoordinates: ipapi.co took ${System.currentTimeMillis() - tIp}ms (got=${ipCoords != null})")
        return ipCoords
    }

    /**
     * IP-based geolocation fallback. Used when no zip code is configured,
     * HA is unreachable, AND we have no cached HA coordinates from a prior
     * session. Returns city-level coordinates from the requesting IP. Caches
     * the result so subsequent fetches don't re-query the IP service.
     */
    private fun fetchIpGeolocation(): Pair<Double, Double>? {
        return try {
            val request = Request.Builder().url("https://ipapi.co/json/").get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "IP geolocation returned HTTP ${response.code}")
                    return null
                }
                val body = response.body?.string() ?: return null
                val obj = JSONObject(body)
                val lat = obj.optDouble("latitude", Double.NaN)
                val lon = obj.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return null
                Log.d(TAG, "Got location from IP geolocation: $lat, $lon")
                // Cache so we don't re-query ipapi.co every poll cycle. Tag with
                // the current location string so a later location change invalidates it.
                halitePrefs.general.cachedLatitude = lat
                halitePrefs.general.cachedLongitude = lon
                halitePrefs.general.cachedLocationKey = halitePrefs.general.zipCode.trim()
                Pair(lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "IP geolocation failed: ${e.message}")
            null
        }
    }

    private fun geocodeUsZip(zipCode: String): Pair<Double, Double>? {
        return try {
            val request = Request.Builder()
                .url("$ZIPPOPOTAM_BASE/$zipCode")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val obj = JSONObject(body)
                val places = obj.optJSONArray("places") ?: return null
                if (places.length() == 0) return null
                val place = places.getJSONObject(0)
                val lat = place.optString("latitude").toDoubleOrNull() ?: return null
                val lon = place.optString("longitude").toDoubleOrNull() ?: return null
                Pair(lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Zippopotam geocoding failed for $zipCode: ${e.message}")
            null
        }
    }

    /**
     * Geocode a free-text location string via open-meteo's geocoding API.
     * Accepts any format open-meteo understands: "Berlin", "Berlin, Germany",
     * "London, UK", "Tokyo", "90210", "Paris, TX", etc.
     */
    private fun geocodeFreeText(query: String): Pair<Double, Double>? {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$OPEN_METEO_GEOCODER?name=$encoded&count=1&language=en&format=json"
            val request = Request.Builder().url(url).get().build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val obj = JSONObject(body)
                val results = obj.optJSONArray("results") ?: return null
                if (results.length() == 0) return null
                val first = results.getJSONObject(0)
                val lat = first.optDouble("latitude", Double.NaN)
                val lon = first.optDouble("longitude", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return null
                Log.d(TAG, "Open-meteo geocoder: '$query' → ${first.optString("name")}, " +
                        "${first.optString("country")} ($lat, $lon)")
                Pair(lat, lon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Open-meteo geocoding failed for '$query': ${e.message}")
            null
        }
    }

    // =========================================================================
    // JSON Parsing
    // =========================================================================

    private fun parseHaWeatherJson(json: String): WeatherData? {
        return try {
            val obj = JSONObject(json)
            val condition = obj.getString("state")
            val attrs = obj.getJSONObject("attributes")

            val rawTemp = attrs.optDouble("temperature", 0.0)
            val haUnit = attrs.optString("temperature_unit", "°F")
            haEntityTempUnit = haUnit // Cache for forecast conversion
            val userUnit = halitePrefs.display.temperatureUnit // "F" or "C"

            // Convert temperature if HA unit doesn't match user preference
            val temperature = when {
                haUnit.contains("F") && userUnit == "C" -> (rawTemp - 32.0) * 5.0 / 9.0
                haUnit.contains("C") && userUnit == "F" -> rawTemp * 9.0 / 5.0 + 32.0
                else -> rawTemp
            }
            val tempUnit = if (userUnit == "C") "°C" else "°F"

            val humidity = if (attrs.has("humidity")) attrs.optInt("humidity") else null
            val windSpeed = if (attrs.has("wind_speed")) attrs.optDouble("wind_speed") else null
            val windUnit = if (attrs.has("wind_speed_unit")) attrs.optString("wind_speed_unit") else null

            WeatherData(
                condition = condition,
                temperature = temperature,
                tempUnit = tempUnit,
                humidity = humidity,
                windSpeed = windSpeed,
                windUnit = windUnit,
                forecast = emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HA weather JSON", e)
            null
        }
    }

    private fun parseHaDailyServiceResponse(json: String): List<ForecastDay> {
        return try {
            val obj = JSONObject(json)
            val serviceResp = obj.optJSONObject("service_response")
                ?: obj.optJSONObject("response")
                ?: return emptyList()

            val entityData = serviceResp.optJSONObject(entityId) ?: return emptyList()
            val forecastArr = entityData.optJSONArray("forecast") ?: return emptyList()

            val forecast = mutableListOf<ForecastDay>()
            val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
            val parseFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

            for (i in 0 until forecastArr.length()) {
                if (forecast.size >= 10) break
                val fc = forecastArr.getJSONObject(i)
                val datetime = fc.optString("datetime", "")
                val datePart = datetime.substringBefore("T")
                try {
                    val date = parseFormat.parse(datePart)
                    if (date != null) {
                        val cal = Calendar.getInstance().apply { time = date }
                        if (cal.get(Calendar.DAY_OF_YEAR) == today) continue

                        val rawPrecip = fc.optInt("precipitation_probability", 0)
                        val precipRounded = (Math.round(rawPrecip / 5.0) * 5).toInt()

                        forecast.add(ForecastDay(
                            dayName = dayFormat.format(date),
                            condition = fc.optString("condition", "cloudy"),
                            tempHigh = convertHaTemp(fc.optDouble("temperature", 0.0)),
                            tempLow = convertHaTemp(fc.optDouble("templow", 0.0)),
                            precipProbability = precipRounded
                        ))
                    }
                } catch (_: Exception) {}
            }

            Log.d(TAG, "Parsed ${forecast.size} daily from HA")
            forecast
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HA daily forecast", e)
            emptyList()
        }
    }

    private fun parseHaHourlyJson(json: String): List<HourlyForecast> {
        return try {
            val obj = JSONObject(json)
            val serviceResp = obj.optJSONObject("service_response")
                ?: obj.optJSONObject("response")
                ?: return emptyList()

            val entityData = serviceResp.optJSONObject(entityId) ?: return emptyList()
            val forecastArr = entityData.optJSONArray("forecast") ?: return emptyList()

            val hourly = mutableListOf<HourlyForecast>()
            val nowMillis = Calendar.getInstance().timeInMillis
            val use24Hour = halitePrefs.display.use24HourClock
            val timeFormat = if (use24Hour) SimpleDateFormat("H:mm", Locale.US) else SimpleDateFormat("ha", Locale.US)
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

            for (i in 0 until forecastArr.length()) {
                if (hourly.size >= 10) break
                val fc = forecastArr.getJSONObject(i)
                val datetime = fc.optString("datetime", "")

                try {
                    val date = isoFormat.parse(datetime) ?: continue
                    if (date.time < nowMillis - 3600_000) continue

                    val timeStr = if (use24Hour) timeFormat.format(date) else timeFormat.format(date).uppercase()
                    val rawPrecip = fc.optInt("precipitation_probability", 0)
                    val precipRounded = (Math.round(rawPrecip / 5.0) * 5).toInt()

                    hourly.add(HourlyForecast(
                        time = if (hourly.isEmpty()) "Now" else timeStr,
                        condition = fc.optString("condition", "cloudy"),
                        temperature = convertHaTemp(fc.optDouble("temperature", 0.0)),
                        precipProbability = precipRounded
                    ))
                } catch (_: Exception) {}
            }

            Log.d(TAG, "Parsed ${hourly.size} hourly from HA")
            hourly
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HA hourly JSON", e)
            emptyList()
        }
    }

    // requestUnit ("F"/"C") is captured at fetch start and used to build the API
    // request; it MUST also drive the label here so a concurrent unit change can't
    // desync value vs label (the 27°F-for-80°F cache bug). Do NOT re-read the pref.
    private fun parseOpenMeteoJson(json: String, requestUnit: String): WeatherData? {
        return try {
            val obj = JSONObject(json)
            val current = obj.getJSONObject("current_weather")
            val daily = obj.optJSONObject("daily")
            val hourlyData = obj.optJSONObject("hourly")

            val weatherCode = current.optInt("weathercode", 0)
            val condition = wmoCodeToCondition(weatherCode)
            val temperature = current.optDouble("temperature", 0.0)
            val windSpeed = current.optDouble("windspeed", 0.0)

            // Parse daily forecast
            val forecast = mutableListOf<ForecastDay>()
            if (daily != null) {
                val times = daily.optJSONArray("time")
                val highs = daily.optJSONArray("temperature_2m_max")
                val lows = daily.optJSONArray("temperature_2m_min")
                val codes = daily.optJSONArray("weathercode")
                val precipProbs = daily.optJSONArray("precipitation_probability_max")

                if (times != null && highs != null && lows != null && codes != null) {
                    val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
                    val parseFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

                    for (i in 0 until minOf(times.length(), 10)) {
                        try {
                            val date = parseFormat.parse(times.getString(i)) ?: continue
                            val cal = Calendar.getInstance().apply { time = date }
                            if (cal.get(Calendar.DAY_OF_YEAR) == today) continue

                            val rawPrecip = precipProbs?.optInt(i, 0) ?: 0
                            val precipRounded = (Math.round(rawPrecip / 5.0) * 5).toInt()

                            forecast.add(ForecastDay(
                                dayName = dayFormat.format(date),
                                condition = wmoCodeToCondition(codes.optInt(i, 0)),
                                tempHigh = highs.optDouble(i, 0.0),
                                tempLow = lows.optDouble(i, 0.0),
                                precipProbability = precipRounded
                            ))
                        } catch (_: Exception) {}
                    }
                }
            }

            // Parse hourly forecast
            val hourly = mutableListOf<HourlyForecast>()
            if (hourlyData != null) {
                val times = hourlyData.optJSONArray("time")
                val temps = hourlyData.optJSONArray("temperature_2m")
                val codes = hourlyData.optJSONArray("weathercode")
                val precipProbs = hourlyData.optJSONArray("precipitation_probability")

                if (times != null && temps != null && codes != null) {
                    // Open-Meteo (timezone=auto) returns hourly times in the
                    // LOCATION's local time. Parse AND format them in that tz (via
                    // utc_offset_seconds) and anchor "Now" by absolute instant — so
                    // a remote location whose tz differs from the device's (e.g.
                    // viewing Seattle/Pacific from an Eastern device) shows the
                    // correct hours instead of ones shifted by the offset. The old
                    // code parsed with no tz (device-local) and filtered on
                    // device HOUR_OF_DAY, mis-anchoring "Now" by the tz delta.
                    val utcOffsetSec = obj.optInt("utc_offset_seconds", 0)
                    val locTz = java.util.SimpleTimeZone(utcOffsetSec * 1000, "loc")
                    val use24h = halitePrefs.display.use24HourClock
                    val timeFormat = (if (use24h) SimpleDateFormat("H:mm", Locale.US)
                        else SimpleDateFormat("ha", Locale.US)).apply { timeZone = locTz }
                    val parseFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = locTz }

                    for (i in 0 until times.length()) {
                        if (hourly.size >= 10) break
                        try {
                            val date = parseFormat.parse(times.getString(i)) ?: continue
                            if (date.time < System.currentTimeMillis() - 3600_000) continue

                            val rawPrecip = precipProbs?.optInt(i, 0) ?: 0
                            val precipRounded = (Math.round(rawPrecip / 5.0) * 5).toInt()

                            val timeStr = if (use24h) timeFormat.format(date) else timeFormat.format(date).uppercase()
                            hourly.add(HourlyForecast(
                                time = if (hourly.isEmpty()) "Now" else timeStr,
                                condition = wmoCodeToCondition(codes.optInt(i, 0)),
                                temperature = temps.optDouble(i, 0.0),
                                precipProbability = precipRounded
                            ))
                        } catch (_: Exception) {}
                    }
                }
            }

            val displayUnit = if (requestUnit == "C") "°C" else "°F"
            WeatherData(
                condition = condition,
                temperature = temperature,
                tempUnit = displayUnit,
                humidity = null,
                windSpeed = windSpeed,
                windUnit = "mph",
                forecast = forecast,
                hourly = hourly
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Open-Meteo JSON", e)
            null
        }
    }

    /**
     * Convert HA temperature to user's preferred unit.
     * HA forecasts use the same unit as the entity (stored in haEntityTempUnit).
     */
    private fun convertHaTemp(temp: Double): Double {
        val userUnit = halitePrefs.display.temperatureUnit
        return when {
            haEntityTempUnit.contains("F") && userUnit == "C" -> (temp - 32.0) * 5.0 / 9.0
            haEntityTempUnit.contains("C") && userUnit == "F" -> temp * 9.0 / 5.0 + 32.0
            else -> temp
        }
    }

    private fun wmoCodeToCondition(code: Int): String {
        return when (code) {
            0 -> "sunny"
            1, 2 -> "partlycloudy"
            3 -> "cloudy"
            45, 48 -> "fog"
            51, 53, 55, 56, 57 -> "rainy"
            61, 63, 80, 81 -> "rainy"
            65, 82 -> "pouring"
            66, 67 -> "snowy-rainy"
            71, 73, 75, 77, 85, 86 -> "snowy"
            95 -> "lightning-rainy"
            96, 99 -> "hail"
            else -> "cloudy"
        }
    }
}
