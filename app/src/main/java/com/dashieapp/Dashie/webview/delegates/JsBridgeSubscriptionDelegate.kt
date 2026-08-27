package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences

/**
 * JS bridge delegate for subscription state synchronization.
 *
 * Called by subscription-sync.js on app load to push subscription state
 * from Supabase (via check-subscription edge function) into native
 * SharedPreferences for the control center and feature gating.
 *
 * Data flow:
 * 1. JS calls check-subscription edge function
 * 2. JS calls DashieNative.syncSubscriptionState(json)
 * 3. This delegate writes to SubscriptionPreferences
 * 4. Control center reads from SubscriptionPreferences on next open
 */
class JsBridgeSubscriptionDelegate(private val context: Context) {

    companion object {
        private const val TAG = "JsBridgeSub"
    }

    private val subPrefs = SubscriptionPreferences(context)

    /** Callback invoked after subscription state is updated */
    var onSubscriptionUpdated: (() -> Unit)? = null

    /** Callback to show trial confirmation dialog (days remaining) */
    var onShowTrialConfirmation: ((Int) -> Unit)? = null

    /** Callback to show "Welcome back! Account already exists" UI when the
     *  user clicks Sign Up but their email is already a Dashie account. */
    var onShowAccountAlreadyExistsNotice: (() -> Unit)? = null

    @JavascriptInterface
    fun syncSubscriptionState(json: String) {
        Log.i(TAG, "syncSubscriptionState: ${json.take(200)}")
        try {
            subPrefs.importFromJson(json)

            // Also store auth_user_id in AccountPreferences (needed for subscribe URLs)
            try {
                val jsonObj = org.json.JSONObject(json)
                val authUserId = jsonObj.optString("auth_user_id", "")
                if (authUserId.isNotEmpty()) {
                    com.dashieapp.Dashie.halite.preferences.AccountPreferences(context).authUserId = authUserId
                }
            } catch (_: Exception) { }

            // D.76 — reset the "I'm not interested in a trial" CC suppression
            // whenever a signed-in subscription state syncs. The flag is for
            // un-signed-in kiosk users who declined the trial offer; once they
            // sign in (or are auto-restored on resume), re-arm it so the
            // promo shows again on their next sign-out → kiosk transition.
            if (subPrefs.subscriptionStatus.isNotEmpty() && subPrefs.trialOfferDismissed) {
                subPrefs.trialOfferDismissed = false
            }

            Log.i(TAG, "Subscription state synced: status=${subPrefs.subscriptionStatus}, tier=${subPrefs.tier}")
            onSubscriptionUpdated?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync subscription state", e)
        }
    }

    @JavascriptInterface
    fun showTrialConfirmation(days: Int) {
        Log.i(TAG, "showTrialConfirmation: $days days")
        onShowTrialConfirmation?.invoke(days)
    }

    /** Called by subscription-sync.js when the user just clicked Sign Up
     *  but their email already had a Dashie account. JS-side toast was
     *  hidden by native overlays (sidebar / control center) so we route
     *  through a native dialog/Snackbar instead. */
    @JavascriptInterface
    fun showAccountAlreadyExistsNotice() {
        Log.i(TAG, "showAccountAlreadyExistsNotice")
        onShowAccountAlreadyExistsNotice?.invoke()
    }

    /**
     * Returns the Android ID for device→account linking.
     * Called by subscription-sync.js to pass device_id to check-subscription.
     */
    @JavascriptInterface
    fun getAndroidId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
    }

    /**
     * Returns the hardware-tied stable device ID (Widevine MediaDrm) that
     * survives reinstall and package-id changes. Sent alongside getAndroidId
     * by subscription-sync.js so check-subscription can recognize the same
     * physical device across package changes. Reads from the same shared
     * prefs key that DeviceInfoHandler and VoiceLicenseManager write.
     */
    @JavascriptInterface
    fun getStableDeviceId(): String {
        return com.dashieapp.Dashie.util.StableDeviceId.read(context)
    }
}
