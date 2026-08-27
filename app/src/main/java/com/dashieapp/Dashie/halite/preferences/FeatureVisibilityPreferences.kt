package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Per-user feature visibility preferences.
 *
 * Mirror of the JS featureAccessService.getHiddenFeatures() decision —
 * JS is authoritative, this cache reflects what was synced. Used by the
 * Control Center and Settings to hide alpha-rollout features from default
 * beta users (and any other rollout/access gate JS evaluates).
 *
 * Populated by JsBridgeFeatureVisibilityDelegate when JS calls
 * DashieNative.syncFeatureVisibility(json) on init/refresh.
 *
 * Plan: dashieapp_staging .reference/build-plans/20260508_DASHIE_CLOUD_BETA_READINESS.md §1.6
 */
class FeatureVisibilityPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_feature_visibility_prefs"

        private const val KEY_HIDDEN = "hidden_features"
        private const val KEY_ACCESS_LEVEL = "access_level"
        private const val KEY_TIER = "tier"
        private const val KEY_SYNCED_AT = "synced_at"

        const val ACCESS_LEVEL_ALPHA = "alpha"
        const val ACCESS_LEVEL_BETA = "beta"
        const val ACCESS_LEVEL_STANDARD = "standard"  // new default (2026-07-03 access-tier restructure)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Set of feature IDs the user should NOT see. */
    var hiddenFeatures: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_HIDDEN, value).commit() }

    /** 'standard' | 'beta' | 'alpha', or empty string. Mirrors user_profiles.special_access.
     *  Ladder: standard < beta < alpha (2026-07-03 access-tier restructure). */
    var accessLevel: String
        get() = prefs.getString(KEY_ACCESS_LEVEL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_ACCESS_LEVEL, value).commit() }

    /** True when access is beta OR alpha (the ladder is inclusive upward). Gates the cloud
     *  Voice/AI surfaces, which moved alpha→beta in the 2026-07-03 access-tier restructure. */
    fun hasBetaAccess(): Boolean =
        accessLevel == ACCESS_LEVEL_BETA || accessLevel == ACCESS_LEVEL_ALPHA

    /** Account tier ('basic', 'core', 'plus', 'vip'). Mirrors user_profiles.tier. */
    var tier: String
        get() = prefs.getString(KEY_TIER, "") ?: ""
        set(value) { prefs.edit().putString(KEY_TIER, value).commit() }

    /** Epoch millis of last sync from JS. Useful for staleness detection. */
    var syncedAt: Long
        get() = prefs.getLong(KEY_SYNCED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_SYNCED_AT, value).commit() }

    /**
     * The primary gate: should this feature be hidden from the user?
     *
     * Returns true ONLY when JS has explicitly told us to hide this feature.
     * If JS hasn't synced yet (empty set, syncedAt=0), returns false — fail
     * open during the brief startup window. The JS layer remains the
     * authoritative gate; this cache is for native UI rendering speed.
     */
    fun isHidden(featureId: String): Boolean = hiddenFeatures.contains(featureId)

    /** Convenience inverse for call sites that read more naturally as positive. */
    fun isVisible(featureId: String): Boolean = !isHidden(featureId)

    /**
     * Bulk import from a JS-originated JSON payload of shape:
     * {
     *   "hidden_features": ["gps", "ai_chat"],
     *   "access_level": "beta",
     *   "tier": "core",
     *   "synced_at": 1234567890
     * }
     */
    fun importFromJson(json: String) {
        val obj = org.json.JSONObject(json)

        val hiddenArr = obj.optJSONArray("hidden_features")
        val hiddenSet = mutableSetOf<String>()
        if (hiddenArr != null) {
            for (i in 0 until hiddenArr.length()) {
                hiddenSet.add(hiddenArr.getString(i))
            }
        }

        prefs.edit()
            .putStringSet(KEY_HIDDEN, hiddenSet)
            .putString(KEY_ACCESS_LEVEL, obj.optString("access_level", ""))
            .putString(KEY_TIER, obj.optString("tier", ""))
            .putLong(KEY_SYNCED_AT, obj.optLong("synced_at", System.currentTimeMillis()))
            .commit()
    }

    /** Clear all visibility state (called on sign-out). */
    fun clear() {
        prefs.edit().clear().commit()
    }
}
