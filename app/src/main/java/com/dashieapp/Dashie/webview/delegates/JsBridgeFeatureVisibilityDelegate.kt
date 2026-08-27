package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import com.dashieapp.Dashie.halite.preferences.FeatureVisibilityPreferences

/**
 * JS bridge delegate for feature visibility synchronization.
 *
 * Called by feature-access-service.js via DashieNative.syncFeatureVisibility(json)
 * after each successful init/refresh. Pushes the per-user "hidden features"
 * list to native SharedPreferences so the Control Center can hide alpha-rollout
 * features (e.g. Locations, Ask Dashie) from default beta users.
 *
 * Data flow:
 *   1. JS featureAccessService initializes from Supabase (user_profiles.special_access)
 *   2. JS computes getHiddenFeatures() — denial list from canAccess() decisions
 *   3. JS calls DashieNative.syncFeatureVisibility(json)
 *   4. This delegate writes to FeatureVisibilityPreferences
 *   5. Control center / settings read isHidden(featureId) on next render
 *
 * The visibility-change refresh hook in featureAccessService re-pushes when the
 * app foregrounds, so admin tier-promotions (alpha grant) propagate without
 * requiring a relaunch.
 *
 * Plan: dashieapp_staging .reference/build-plans/20260508_DASHIE_CLOUD_BETA_READINESS.md §1.6
 */
class JsBridgeFeatureVisibilityDelegate(private val context: Context) {

    companion object {
        private const val TAG = "JsBridgeFeatureVis"
    }

    private val featurePrefs = FeatureVisibilityPreferences(context)

    /** Callback invoked after feature visibility is updated. UI components
     *  should re-render any feature-gated cards/menus when this fires. */
    var onFeatureVisibilityUpdated: (() -> Unit)? = null

    @JavascriptInterface
    fun syncFeatureVisibility(json: String) {
        Log.i(TAG, "syncFeatureVisibility: ${json.take(200)}")
        try {
            featurePrefs.importFromJson(json)
            Log.i(
                TAG,
                "Feature visibility synced: " +
                    "hidden=${featurePrefs.hiddenFeatures}, " +
                    "accessLevel=${featurePrefs.accessLevel}, " +
                    "tier=${featurePrefs.tier}"
            )
            onFeatureVisibilityUpdated?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync feature visibility", e)
        }
    }
}
