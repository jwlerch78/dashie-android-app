package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Chores & Rewards feature preferences.
 *
 * Manages:
 * - Chores master toggle (cascades to rewards)
 * - Rewards toggle
 * - Anyone-can-complete and participant selection
 * - Upcoming chores display window
 */
class ChoresRewardsPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_chores_prefs"

        private const val KEY_CHORES_ENABLED = "chores_enabled"
        private const val KEY_REWARDS_ENABLED = "rewards_enabled"
        private const val KEY_ANYONE_ENABLED = "anyone_enabled"
        private const val KEY_PARTICIPANTS = "participants_json"
        private const val KEY_UPCOMING_DAYS = "upcoming_days"

        const val DEFAULT_UPCOMING_DAYS = 7
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Boolean fields ──────────────────────────────────────────────────

    // Defaults flipped to false — chores & rewards are opt-in features.
    // Was true, which caused new accounts (and post-delete re-installs) to
    // see all feature cards "ON" in Control Center even though the user
    // hadn't enabled them.
    var choresEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHORES_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_CHORES_ENABLED, value).commit() }

    var rewardsEnabled: Boolean
        get() = prefs.getBoolean(KEY_REWARDS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_REWARDS_ENABLED, value).commit() }

    var anyoneEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANYONE_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_ANYONE_ENABLED, value).commit() }

    // ── Int fields ──────────────────────────────────────────────────────

    var upcomingDays: Int
        get() = prefs.getInt(KEY_UPCOMING_DAYS, DEFAULT_UPCOMING_DAYS)
        set(value) { prefs.edit().putInt(KEY_UPCOMING_DAYS, value).commit() }

    // ── Participants (JSON array of member IDs) ─────────────────────────

    /** Get participant IDs, or null if "all members" (default) */
    fun getParticipants(): List<String>? {
        val json = prefs.getString(KEY_PARTICIPANTS, null) ?: return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { null }
    }

    /** Set participant IDs, or null to reset to "all members" */
    fun setParticipants(ids: List<String>?) {
        if (ids == null) {
            prefs.edit().remove(KEY_PARTICIPANTS).commit()
        } else {
            val arr = JSONArray(ids)
            prefs.edit().putString(KEY_PARTICIPANTS, arr.toString()).commit()
        }
    }

    // ── Display helpers ─────────────────────────────────────────────────

    fun participantsDisplay(): String {
        val participants = getParticipants()
        return if (participants == null) "All Members" else "${participants.size} members"
    }

    fun statusSummary(): String {
        if (!choresEnabled) return "Inactive"
        return if (rewardsEnabled) "Chores & Rewards" else "Chores only"
    }
}
