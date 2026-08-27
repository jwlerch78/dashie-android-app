package com.dashieapp.Dashie.halite.preferences

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Video Feed PiP overlay preferences.
 *
 * Manages:
 * - Feature enabled state
 * - Rules JSON (array of trigger → camera mappings with display settings)
 * - Saved PiP positions per rule (for drag-to-reposition persistence)
 *
 * Uses the same SharedPreferences file as other Dashie preferences.
 */
class VideoFeedPreferences(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_lite_prefs"

        private const val KEY_ENABLED = "video_feed_enabled"
        private const val KEY_CONFIG_JSON = "video_feed_config_json"
        private const val KEY_POSITION_PREFIX = "video_feed_pos_"

        // Display settings stored as top-level prefs (not in JSON blob)
        private const val KEY_FEED_SIZE = "video_feed_size"
        private const val KEY_FEED_LOCATION = "video_feed_location"
        private const val KEY_FEED_LAYOUT = "video_feed_layout"
        // Persistent pause flag — when true, new trigger-based pop-ups are
        // suppressed. Survives app restart (was previously in-memory only
        // on VideoFeedOverlayManager.isPaused and reset to false each launch).
        private const val KEY_POPUPS_PAUSED = "video_feed_popups_paused"
        // Persistent alerts-mute flag — when true, the chime that plays on
        // a trigger-based video feed alert is suppressed. Backs the bell
        // toggle in the sidebar volume popout. Survives app restart.
        private const val KEY_ALERTS_MUTED = "video_feed_alerts_muted"
        // Cached availability from the most recent strip scan — ruleId → true
        // (online) / false (offline). Used at strip-open time so cards that
        // were offline on the last scan start hidden instead of flashing the
        // loading state before the background scan catches up. Stored as a
        // JSON object.
        private const val KEY_LAST_AVAILABILITY = "video_feed_last_availability"

        // Rules saved locally within this window are not overwritten by HA pulls
        private const val LOCAL_SAVE_PROTECT_MS = 30_000L
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Guards all read-modify-write operations on configJson */
    private val configLock = Any()

    /** Tracks the last time each rule was saved locally (by rule ID) */
    private val localSaveTimes = mutableMapOf<String, Long>()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).commit() }

    /**
     * Full config JSON (mirrored from JS localStorage via bridge).
     * Format: { "enabled": true, "rules": [...] }
     */
    var configJson: String
        get() = prefs.getString(KEY_CONFIG_JSON, """{"enabled":false,"rules":[]}""") ?: """{"enabled":false,"rules":[]}"""
        set(value) {
            prefs.edit().putString(KEY_CONFIG_JSON, value).commit()
            // Sync enabled flag from config
            try {
                val json = JSONObject(value)
                val enabledFromJson = json.optBoolean("enabled", false)
                val enabledBefore = prefs.getBoolean(KEY_ENABLED, false)
                prefs.edit().putBoolean(KEY_ENABLED, enabledFromJson).commit()
                if (enabledBefore != enabledFromJson) {
                    android.util.Log.w("VF_DEBUG", "configJson setter CHANGED enabled: $enabledBefore → $enabledFromJson")
                    android.util.Log.w("VF_DEBUG", "  configJson (first 300): ${value.take(300)}")
                }
            } catch (_: Exception) {}
        }

    /**
     * Get all configured rules from the stored config.
     */
    fun getRules(): List<JSONObject> {
        return try {
            val config = JSONObject(configJson)
            val rulesArray = config.optJSONArray("rules") ?: JSONArray()
            (0 until rulesArray.length()).map { rulesArray.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Get enabled rules only.
     */
    fun getEnabledRules(): List<JSONObject> {
        return getRules().filter { it.optBoolean("enabled", true) }
    }

    /**
     * Find a rule by its trigger entity ID.
     */
    fun findRuleByTrigger(triggerEntityId: String, triggerState: String): JSONObject? {
        return getEnabledRules().find {
            val mode = it.optString("subscriptionMode", "trigger")
            val isTriggerMode = mode == "trigger" || mode == "trigger_alert"
            isTriggerMode &&
                it.optString("triggerEntityId") == triggerEntityId &&
                it.optString("triggerState", "on") == triggerState
        }
    }

    /**
     * Get global cooldown seconds from the config JSON.
     * Returns 0 (no cooldown) if not set.
     */
    fun getCooldownSeconds(): Int {
        return try {
            JSONObject(configJson).optInt("cooldownSeconds", 0)
        } catch (_: Exception) {
            0
        }
    }

    fun getStreamResolution(): Int {
        return try {
            JSONObject(configJson).optInt("streamResolution", 640)
        } catch (_: Exception) {
            640
        }
    }

    fun getStreamFps(): Int {
        return try {
            JSONObject(configJson).optInt("streamFps", 15)
        } catch (_: Exception) {
            15
        }
    }

    fun getFeedLocation(): String =
        prefs.getString(KEY_FEED_LOCATION, "sidebar") ?: "sidebar"

    fun getFeedLayout(): String =
        prefs.getString(KEY_FEED_LAYOUT, "grid") ?: "grid"

    fun getFeedSize(): String =
        prefs.getString(KEY_FEED_SIZE, "medium") ?: "medium"

    // ── Individual Config Field Setters ──────────────────────────────
    // Update a single field in the config JSON without replacing the whole blob.

    private fun updateConfigField(key: String, value: Any?) {
        synchronized(configLock) {
            try {
                val config = JSONObject(configJson)
                when (value) {
                    null -> config.remove(key)
                    is Boolean -> config.put(key, value)
                    is Int -> config.put(key, value)
                    is String -> config.put(key, value)
                    else -> config.put(key, value.toString())
                }
                configJson = config.toString()
            } catch (_: Exception) {}
        }
    }

    fun setFeedLocation(value: String) { prefs.edit().putString(KEY_FEED_LOCATION, value).commit() }
    fun setFeedSize(value: String) { prefs.edit().putString(KEY_FEED_SIZE, value).commit() }
    fun setFeedLayout(value: String) { prefs.edit().putString(KEY_FEED_LAYOUT, value).commit() }

    fun getPopupsPaused(): Boolean = prefs.getBoolean(KEY_POPUPS_PAUSED, false)
    fun setPopupsPaused(value: Boolean) {
        prefs.edit().putBoolean(KEY_POPUPS_PAUSED, value).commit()
    }

    fun getAlertsMuted(): Boolean = prefs.getBoolean(KEY_ALERTS_MUTED, false)
    fun setAlertsMuted(value: Boolean) {
        prefs.edit().putBoolean(KEY_ALERTS_MUTED, value).commit()
    }

    /**
     * Last-known availability of each feed ruleId. Written after every scan,
     * read at strip-open time so cards offline in the last session are
     * hidden immediately rather than shown "loading" until the scan resolves.
     */
    fun getLastAvailability(): Map<String, Boolean> {
        val raw = prefs.getString(KEY_LAST_AVAILABILITY, null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, Boolean>()
            json.keys().forEach { key -> result[key] = json.optBoolean(key, true) }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun setLastAvailability(map: Map<String, Boolean>) {
        val json = JSONObject()
        map.forEach { (k, v) -> json.put(k, v) }
        prefs.edit().putString(KEY_LAST_AVAILABILITY, json.toString()).commit()
    }
    fun setCooldownSeconds(value: Int) = updateConfigField("cooldownSeconds", value)
    fun setStreamResolution(value: Int) = updateConfigField("streamResolution", value)
    fun setStreamFps(value: Int) = updateConfigField("streamFps", value)
    fun setAutoDismissSeconds(value: Int) = updateConfigField("autoDismissSeconds", value)
    fun setContinueWhileActive(value: Boolean) = updateConfigField("continueWhileActive", value)
    fun setAlertsEnabled(value: Boolean) = updateConfigField("alertsEnabled", value)

    fun getContinueWhileActive(): Boolean {
        return try {
            JSONObject(configJson).optBoolean("continueWhileActive", true)
        } catch (_: Exception) { true }
    }

    fun getAlertsEnabled(): Boolean {
        return try {
            JSONObject(configJson).optBoolean("alertsEnabled", true)
        } catch (_: Exception) { true }
    }

    fun getAutoDismissSeconds(): Int {
        return try {
            JSONObject(configJson).optInt("autoDismissSeconds", 30)
        } catch (_: Exception) { 30 }
    }

    /**
     * Get the subscription mode for a specific feed, read directly from the rule.
     */
    fun getFeedMode(feedId: String): String {
        return getRules().find {
            it.optString("id", it.optString("ruleId", "")) == feedId
        }?.optString("subscriptionMode", "subscribed") ?: "subscribed"
    }

    fun setFeedMode(feedId: String, mode: String) {
        synchronized(configLock) {
            try {
                val config = JSONObject(configJson)
                val rules = config.optJSONArray("rules") ?: JSONArray()
                for (i in 0 until rules.length()) {
                    val rule = rules.getJSONObject(i)
                    if (rule.optString("id", rule.optString("ruleId", "")) == feedId) {
                        rule.put("subscriptionMode", mode)
                        rule.put("enabled", mode != "ignored")
                        rules.put(i, rule)
                        break
                    }
                }
                config.put("rules", rules)
                configJson = config.toString()
                localSaveTimes[feedId] = System.currentTimeMillis()
            } catch (_: Exception) {}
        }
        // Push the new subscription mode to HA so the integration's feed_modes
        // map updates for this device — otherwise the HA coordinator keeps using
        // the previous mode (or the feed's default_mode, usually "subscribed")
        // and never pushes triggers back to this tablet.
        syncSubscriptionToHa(feedId, mode)
    }

    /**
     * POST /api/dashie/feeds/subscriptions/{deviceId} with feed_modes={feedId: mode}
     * so the HA integration knows this device wants trigger pushes for the feed.
     */
    private fun syncSubscriptionToHa(feedId: String, mode: String) {
        if (feedId.isEmpty()) return
        Thread {
            try {
                val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
                val (baseUrl, token) = com.dashieapp.Dashie.halite.HaTokenExtractor
                    .getValidCredentialsSync(halitePrefs) ?: return@Thread
                val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(context)
                if (deviceId.isEmpty()) return@Thread
                val subBody = JSONObject().apply {
                    put("feed_modes", JSONObject().apply { put(feedId, mode) })
                }
                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val subRequest = okhttp3.Request.Builder()
                    .url("$baseUrl${ApiPaths.HA}/feeds/subscriptions/$deviceId")
                    .addHeader("Authorization", "Bearer $token")
                    .post(subBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(subRequest).execute().use { response ->
                    android.util.Log.i("VideoFeedPreferences",
                        "Sync subscription to HA: $feedId=$mode → ${response.code}")
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoFeedPreferences",
                    "Failed to sync subscription to HA: $feedId=$mode", e)
            }
        }.start()
    }

    /**
     * Add or update a rule in the config JSON.
     */
    fun saveRule(rule: JSONObject) {
        val ruleId = rule.optString("id")
        synchronized(configLock) {
            try {
                val config = JSONObject(configJson)
                val rules = config.optJSONArray("rules") ?: JSONArray()

                // Find existing rule index
                var existingIdx = -1
                for (i in 0 until rules.length()) {
                    if (rules.getJSONObject(i).optString("id") == ruleId) {
                        existingIdx = i
                        break
                    }
                }

                if (existingIdx >= 0) {
                    rules.put(existingIdx, rule)
                } else {
                    rules.put(rule)
                }
                config.put("rules", rules)
                configJson = config.toString()
                // Record save time so concurrent HA pulls don't overwrite this rule
                if (ruleId.isNotEmpty()) localSaveTimes[ruleId] = System.currentTimeMillis()
            } catch (_: Exception) {}
        }

        // Save to HA feed registry (background thread)
        saveToHa(rule)
    }

    /**
     * Call POST /api/dashie/feeds on the HA instance to save/update a feed.
     * Converts the local rule format to the HA feed format.
     */
    private fun saveToHa(rule: JSONObject) {
        Thread {
            try {
                val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
                val (baseUrl, token) = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(halitePrefs) ?: return@Thread

                // Convert local rule format → HA feed format
                val triggerEntityId = rule.optString("triggerEntityId", "")
                val triggerState = rule.optString("triggerState", "on")
                val triggers = JSONArray()
                if (triggerEntityId.isNotEmpty()) {
                    triggers.put(JSONObject().apply {
                        put("entity_id", triggerEntityId)
                        put("state", triggerState)
                    })
                }

                val playSound = rule.optBoolean("playSoundOnTrigger", false)
                val haFeed = JSONObject().apply {
                    put("id", rule.optString("id"))
                    put("label", rule.optString("name", rule.optString("cameraName", "")))
                    put("camera_entity_id", rule.optString("cameraEntityId", ""))
                    put("triggers", triggers)
                    put("stream_source_type", rule.optString("streamSourceType", "entity"))
                    put("stream_source_url", rule.optString("streamSourceUrl", ""))
                    put("auto_dismiss_seconds", rule.optInt("autoDismissSeconds", 30))
                    put("continue_while_active", rule.optBoolean("continueWhileActive", true))
                    put("alert_sound", if (playSound) rule.optString("triggerSound", "extra_wood_knock") else "")
                    put("fps", rule.optInt("fps", 10))
                    put("quality", rule.optInt("quality", 8))
                    put("resolution", rule.optInt("resolution", 480))
                    put("frigate_camera_override", rule.optString("frigateCameraOverride", ""))
                }

                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val feedRequest = okhttp3.Request.Builder()
                    .url("$baseUrl${ApiPaths.HA}/feeds")
                    .addHeader("Authorization", "Bearer $token")
                    .post(haFeed.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(feedRequest).execute().use { response ->
                    android.util.Log.i("VideoFeedPreferences",
                        "Save feed to HA: ${rule.optString("id")} → ${response.code}")
                }

                // Also update this device's subscription mode in HA so the coordinator
                // knows whether to send trigger notifications to this device.
                val feedId = rule.optString("id")
                val subscriptionMode = rule.optString("subscriptionMode", "subscribed")
                if (feedId.isNotEmpty()) {
                    val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(context)
                    if (deviceId.isNotEmpty()) {
                        val subBody = JSONObject().apply {
                            put("feed_modes", JSONObject().apply {
                                put(feedId, subscriptionMode)
                            })
                        }
                        val subRequest = okhttp3.Request.Builder()
                            .url("$baseUrl${ApiPaths.HA}/feeds/subscriptions/$deviceId")
                            .addHeader("Authorization", "Bearer $token")
                            .post(subBody.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(subRequest).execute().use { response ->
                            android.util.Log.i("VideoFeedPreferences",
                                "Sync subscription to HA: $feedId=$subscriptionMode → ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoFeedPreferences",
                    "Failed to save feed to HA: ${rule.optString("id")}", e)
            }
        }.start()
    }

    /**
     * Delete a rule by ID from the config JSON and from the HA feed registry.
     */
    fun deleteRule(ruleId: String) {
        // Remove from local config
        try {
            val config = JSONObject(configJson)
            val rules = config.optJSONArray("rules") ?: return
            val newRules = JSONArray()
            for (i in 0 until rules.length()) {
                val r = rules.getJSONObject(i)
                if (r.optString("id") != ruleId) newRules.put(r)
            }
            config.put("rules", newRules)
            configJson = config.toString()
        } catch (_: Exception) {}

        // Delete from HA feed registry (background thread)
        deleteFromHa(ruleId)
    }

    /**
     * Call DELETE /api/dashie/feeds/{feedId} on the HA instance.
     */
    private fun deleteFromHa(feedId: String) {
        Thread {
            try {
                val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
                val (baseUrl, token) = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(halitePrefs) ?: return@Thread

                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url("$baseUrl${ApiPaths.HA}/feeds/${java.net.URLEncoder.encode(feedId, "UTF-8")}")
                    .addHeader("Authorization", "Bearer $token")
                    .delete()
                    .build()

                client.newCall(request).execute().use { response ->
                    android.util.Log.i("VideoFeedPreferences",
                        "Delete feed from HA: $feedId → ${response.code}")
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoFeedPreferences",
                    "Failed to delete feed from HA: $feedId", e)
            }
        }.start()
    }

    /**
     * Pull feeds from the HA feed registry (GET /api/dashie/feeds) and merge
     * into local rules. This is the Kotlin equivalent of the JS _loadHaFeeds()
     * + _syncHaFeedsToLocal() flow.
     *
     * @param onComplete optional callback (on background thread) after sync finishes
     */
    fun pullFeedsFromHa(onComplete: ((success: Boolean) -> Unit)? = null) {
        Thread {
            try {
                val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
                val credentials = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(halitePrefs)
                if (credentials == null) {
                    onComplete?.invoke(false)
                    return@Thread
                }
                val (baseUrl, token) = credentials

                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val request = okhttp3.Request.Builder()
                    .url("$baseUrl${ApiPaths.HA}/feeds")
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                // Also fetch this device's subscription modes from HA
                val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(context)
                var haFeedModes: Map<String, String> = emptyMap()
                if (deviceId.isNotEmpty()) {
                    try {
                        val subRequest = okhttp3.Request.Builder()
                            .url("$baseUrl${ApiPaths.HA}/feeds/subscriptions/$deviceId")
                            .addHeader("Authorization", "Bearer $token")
                            .get()
                            .build()
                        client.newCall(subRequest).execute().use { subResponse ->
                            if (subResponse.isSuccessful) {
                                val subBody = subResponse.body?.string() ?: "{}"
                                val subJson = JSONObject(subBody)
                                val modesJson = subJson.optJSONObject("feed_modes")
                                if (modesJson != null) {
                                    val modes = mutableMapOf<String, String>()
                                    val keys = modesJson.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        modes[k] = modesJson.getString(k)
                                    }
                                    haFeedModes = modes
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        android.util.Log.w("VideoFeedPreferences",
                            "Pull feeds from HA failed: ${response.code}")
                        onComplete?.invoke(false)
                        return@use
                    }

                    val body = response.body?.string() ?: "{}"
                    val json = JSONObject(body)
                    val feedsObj = json.optJSONObject("feeds") ?: JSONObject()

                    if (feedsObj.length() == 0) {
                        // HA has 0 feeds — leave local rules untouched.
                        // Orphan cleanup in syncHaFeedsToLocal handles the general
                        // case when HA has some feeds but not others.
                        onComplete?.invoke(true)
                        return@use
                    }

                    // Convert HA feeds → local rules and merge
                    syncHaFeedsToLocal(feedsObj, haFeedModes)
                    android.util.Log.i("VideoFeedPreferences",
                        "Pulled ${feedsObj.length()} feeds from HA")
                    onComplete?.invoke(true)
                }
            } catch (e: Exception) {
                android.util.Log.w("VideoFeedPreferences",
                    "Failed to pull feeds from HA", e)
                onComplete?.invoke(false)
            }
        }.start()
    }

    /**
     * Merge HA feeds into local config rules (upsert by ID, dedup by camera entity).
     * Mirrors the JS _syncHaFeedsToLocal() logic.
     *
     * @param haSubscriptionModes Optional map of feedId → mode from HA's subscription registry.
     *        Used as fallback when no local mode exists (e.g., fresh install).
     */
    private fun syncHaFeedsToLocal(feedsObj: JSONObject, haSubscriptionModes: Map<String, String> = emptyMap()) {
        synchronized(configLock) { try {
            val config = JSONObject(configJson)
            // Preserve the current enabled state (may have been set by the toggle
            // before the JSON blob was updated)
            config.put("enabled", enabled)
            val existingRules = config.optJSONArray("rules") ?: JSONArray()
            val now = System.currentTimeMillis()

            // Collect HA feed IDs and camera entities for dedup
            val haFeedIds = mutableSetOf<String>()
            val haCameraEntities = mutableSetOf<String>()
            val feedKeys = feedsObj.keys()
            while (feedKeys.hasNext()) {
                val feedId = feedKeys.next()
                haFeedIds.add(feedId)
                val feed = feedsObj.optJSONObject(feedId) ?: continue
                val camEntity = feed.optString("camera_entity_id", "")
                if (camEntity.isNotEmpty()) haCameraEntities.add(camEntity)
            }

            // Collect existing subscription modes before removing rules, so we can
            // preserve them when re-adding from HA (the user's per-device choice).
            val existingModes = mutableMapOf<String, String>()
            for (i in 0 until existingRules.length()) {
                val r = existingRules.getJSONObject(i)
                val ruleId = r.optString("id", "")
                val mode = r.optString("subscriptionMode", "")
                if (ruleId.isNotEmpty() && mode.isNotEmpty()) existingModes[ruleId] = mode
            }

            // Remove local rules that duplicate an HA feed (by ID or camera entity),
            // OR that are orphans (no longer in HA = deleted from another device).
            // UNLESS the rule was saved locally within the protection window — in that
            // case keep the local version so a slow saveToHa() doesn't get clobbered.
            val filteredRules = JSONArray()
            for (i in 0 until existingRules.length()) {
                val r = existingRules.getJSONObject(i)
                val ruleId = r.optString("id", "")
                val camId = r.optString("cameraEntityId", "")
                val savedAt = localSaveTimes[ruleId]
                val recentlySaved = savedAt != null && (now - savedAt) < LOCAL_SAVE_PROTECT_MS
                if (haFeedIds.contains(ruleId)) {
                    if (recentlySaved) {
                        // Keep the locally-saved version; HA data may be stale
                        filteredRules.put(r)
                        android.util.Log.d("VideoFeedPreferences",
                            "Protecting recently-saved rule $ruleId from HA overwrite")
                    }
                    continue
                }
                if (camId.isNotEmpty() && haCameraEntities.contains(camId)) continue
                // Orphan: local rule whose ID doesn't exist in HA (deleted from another device).
                // Remove it unless it was just saved locally (protection window).
                if (ruleId.isNotEmpty() && !recentlySaved) {
                    android.util.Log.i("VideoFeedPreferences",
                        "Removing orphan rule $ruleId (not in HA, not recently saved)")
                    continue
                }
                filteredRules.put(r)
            }

            // Build set of remaining IDs to avoid re-adding
            val remainingIds = mutableSetOf<String>()
            for (i in 0 until filteredRules.length()) {
                remainingIds.add(filteredRules.getJSONObject(i).optString("id", ""))
            }

            // Add all HA feeds as local rules
            var added = 0
            val haKeys = feedsObj.keys()
            while (haKeys.hasNext()) {
                val feedId = haKeys.next()
                if (remainingIds.contains(feedId)) continue
                val feed = feedsObj.optJSONObject(feedId) ?: continue

                val triggers = feed.optJSONArray("triggers")
                val trigger = triggers?.optJSONObject(0)
                val alertSound = feed.optString("alert_sound", "")
                // Prefer the locally stored mode, then HA's subscription mode, then HA's default_mode
                // so the user's per-device subscription choice survives an HA sync.
                val feedMode = existingModes[feedId]
                    ?: haSubscriptionModes[feedId]
                    ?: feed.optString("default_mode", "subscribed")
                val isTriggerMode = feedMode == "trigger" || feedMode == "trigger_alert"

                val localRule = JSONObject().apply {
                    put("id", feedId)
                    put("name", feed.optString("label", feedId))
                    put("cameraEntityId", feed.optString("camera_entity_id", ""))
                    put("cameraName", feed.optString("label", ""))
                    put("triggerEntityId", trigger?.optString("entity_id", "") ?: "")
                    put("triggerState", trigger?.optString("state", "on") ?: "on")
                    put("autoDismissSeconds", feed.optInt("auto_dismiss_seconds", 30))
                    put("continueWhileActive", feed.optBoolean("continue_while_active", true))
                    put("streamSourceType", feed.optString("stream_source_type", "entity"))
                    put("streamSourceUrl", feed.optString("stream_source_url", ""))
                    put("playSoundOnTrigger", feedMode == "trigger_alert" && alertSound.isNotEmpty())
                    put("triggerSound", if (alertSound.isNotEmpty()) alertSound else "extra_wood_knock")
                    put("enabled", feedMode != "ignored")
                    put("subscriptionMode", feedMode)
                    put("available", feed.optBoolean("available", true))
                    put("fps", feed.optInt("fps", 10))
                    put("quality", feed.optInt("quality", 8))
                    put("resolution", feed.optInt("resolution", 480))
                    val isFrigate = feed.optBoolean("is_frigate_camera", false)
                    val frigateName = feed.optString("frigate_camera_name", "")
                    put("isFrigateCamera", isFrigate)
                    put("frigateCameraName", frigateName)
                    put("frigateCameraOverride", feed.optString("frigate_camera_override", ""))
                    if (isFrigate) {
                        android.util.Log.i("VideoFeedPreferences",
                            "Feed $feedId is Frigate camera: $frigateName")
                    }
                }
                // Log the raw HA feed keys for debugging
                android.util.Log.d("VideoFeedPreferences",
                    "HA feed $feedId keys: ${feed.keys().asSequence().toList()}")
                // Also log the fields most likely to be wrong (empty/missing)
                android.util.Log.i("VideoFeedPreferences",
                    "HA feed $feedId: camera_entity_id='${feed.optString("camera_entity_id", "")}' " +
                        "label='${feed.optString("label", "")}' " +
                        "stream_source_type='${feed.optString("stream_source_type", "")}' " +
                        "is_frigate_camera=${feed.optBoolean("is_frigate_camera", false)} " +
                        "available=${feed.optBoolean("available", true)}")
                filteredRules.put(localRule)
                added++
            }

            if (added > 0 || filteredRules.length() != existingRules.length()) {
                config.put("rules", filteredRules)
                val enabledInConfig = config.optBoolean("enabled", false)
                android.util.Log.i("VF_DEBUG", "syncHaFeedsToLocal: writing configJson, enabled=$enabledInConfig, rules=${filteredRules.length()}, existing=${existingRules.length()}, added=$added")
                configJson = config.toString()
                android.util.Log.i("VF_DEBUG", "syncHaFeedsToLocal: after write, enabled pref=${enabled}")
                android.util.Log.i("VideoFeedPreferences",
                    "Feed sync: added=$added, total=${filteredRules.length()}")
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoFeedPreferences", "syncHaFeedsToLocal failed", e)
        } }
    }

    /**
     * Save/restore PiP position for a specific rule (for drag persistence).
     * Format: "x,y"
     */
    fun savePosition(ruleId: String, isMinimized: Boolean, x: Int, y: Int) {
        val suffix = if (isMinimized) "_min" else "_normal"
        prefs.edit().putString("${KEY_POSITION_PREFIX}${ruleId}${suffix}", "$x,$y").commit()
    }

    fun loadPosition(ruleId: String, isMinimized: Boolean): Pair<Int, Int>? {
        val suffix = if (isMinimized) "_min" else "_normal"
        val stored = prefs.getString("${KEY_POSITION_PREFIX}${ruleId}${suffix}", null) ?: return null
        return try {
            val parts = stored.split(",")
            Pair(parts[0].toInt(), parts[1].toInt())
        } catch (_: Exception) {
            null
        }
    }
}
