package com.dashieapp.Dashie.halite.billing

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.Toast
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences
import com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Trial-expired modal. Wrapped in AlertDialog so it captures d-pad focus
 * (matches the voice-license purchase + connect-to-mobile QR dialogs).
 * Auto-shows when subscription_status == 'trial_expired' AND the gate is
 * in FULL mode; auto-dismisses on any other state.
 *
 * Buttons (vertical column, d-pad navigable, Subscribe focused by default):
 * - Subscribe Now → triggers the same purchase flow CC uses
 * - Manage Dashie Account → QR to the mobile site (Settings → Account),
 *   where the owner manages/deletes the account from their own phone.
 *   Replaced the old in-modal Delete Account button.
 * - Continue with Home Assistant Only → only visible when HA URL is set;
 *   flips ha_enabled true and broadcasts SIGN_OUT for full reload
 * - Exit Dashie → finishAndRemoveTask, killing the activity / removing
 *   from recents (escape hatch when the user can't use HA either)
 */
class TrialExpiredOverlayManager(
    private val activity: Activity,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val visibilityGate: NativeWidgetVisibilityGate? = null
) {
    companion object {
        private const val TAG = "TrialExpiredOverlay"
        private val DATABASE_OPS_URL = BuildConfig.SUPABASE_URL + "/functions/v1/database-operations"
    }

    private var dialog: AlertDialog? = null
    private val subPrefs = SubscriptionPreferences(activity)

    // IO-bound coroutine scope for the convert_to_ha_only edge function
    // call. SupervisorJob so a failed call doesn't propagate-cancel
    // sibling work. Lives for the lifetime of the manager (which is
    // tied to MainActivity).
    private val convertScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Short-timeout HTTP client for the conversion call. The user is
    // staring at a "Switching to HA Only…" button — fail fast on a bad
    // network rather than spin for 30s, so they can retry or tap Exit.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun refresh() {
        activity.runOnUiThread {
            val status = subPrefs.subscriptionStatus
            val gateAllows = visibilityGate?.shouldShowFull() ?: true
            // Three statuses fire the modal:
            //   - trial_expired / canceled: standard paywall, server still
            //     allows sign-in but feature data is gone.
            //   - ha_only: the user previously opted into HA-only via this
            //     same modal, then signed in again. We surface the modal
            //     so they can confirm intent (continue to HA kiosk,
            //     manage account on the web, etc.) rather than silently
            //     forcing kiosk and confusing them. Cold-start with
            //     cached ha_only state is handled separately in
            //     MainActivity.onCreate so this modal doesn't fire there.
            val blocked = status == SubscriptionPreferences.STATUS_TRIAL_EXPIRED ||
                status == SubscriptionPreferences.STATUS_CANCELED ||
                status == SubscriptionPreferences.STATUS_HA_ONLY
            // Brand-split T4: never paywall the free HA edition. This modal is
            // setCancelable(false) with "Subscribe to restore Dashie Cloud features" as
            // the default-focused action — a hard, unskippable paywall, and on ha_only
            // it fired at exactly the devices T4 is making free. The ha_only branch
            // existed to let a returning user re-confirm intent; that intent is now the
            // product's default, so there is nothing to confirm.
            if (blocked && gateAllows && !com.dashieapp.Dashie.halite.HaEditionGate.isHaMode(activity)) {
                show()
            } else {
                dismiss()
            }
        }
    }

    private fun show() {
        if (dialog?.isShowing == true) return
        try {
            val dialogView = LayoutInflater.from(activity)
                .inflate(R.layout.view_trial_expired, null)

            val isAmazonFlavor = com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon"
            val status = subPrefs.subscriptionStatus

            // Status-aware copy — same modal + actions across all three
            // states (trial_expired / canceled / ha_only), but each gets
            // its own title and body. On amazon-like flavors, every body
            // is rewritten to drop subscribe-direction language (Amazon
            // Appstore IAP compliance — subscriptions happen on web).
            val (title, body) = when (status) {
                SubscriptionPreferences.STATUS_CANCELED -> "Subscription Canceled" to (
                    if (isAmazonFlavor) "Your Dashie subscription was canceled. Cloud features (calendar, photos, chores, family sharing) are no longer available."
                    else "Your Dashie subscription was canceled. Subscribe to keep using Dashie."
                )
                SubscriptionPreferences.STATUS_HA_ONLY -> "Home Assistant Only Mode" to (
                    if (isAmazonFlavor) "Your account is set to Home Assistant only. Cloud features (calendar, photos, chores, family sharing) are no longer available."
                    else "Your account is set to Home Assistant only. Subscribe to restore Dashie Cloud features."
                )
                // STATUS_TRIAL_EXPIRED default
                else -> "Trial Ended" to (
                    if (isAmazonFlavor) "Your Dashie trial has ended. Cloud features (calendar, photos, chores, family sharing) are no longer available."
                    else "Your Dashie trial has ended. Subscribe to keep using Dashie."
                )
            }
            dialogView.findViewById<android.widget.TextView>(R.id.textTrialTitle)?.text = title
            dialogView.findViewById<android.widget.TextView>(R.id.textTrialBody)?.text = body

            // Amazon-flavor IAP compliance: no in-app surface may direct
            // users to external payment, and even informational paths that
            // open payment-capable web pages (Manage Dashie Account → QR
            // to /console) are not permitted. Strip to two neutral actions:
            // Sign Out (lets the user re-enter with a different account if
            // they subscribed elsewhere) and Exit Dashie. Subscriptions for
            // Amazon users happen entirely off-device on dashieapp.com,
            // matching the Spotify / Netflix pattern.
            if (isAmazonFlavor) {
                dialogView.findViewById<Button>(R.id.buttonTrialSubscribe)?.visibility =
                    android.view.View.GONE
                dialogView.findViewById<Button>(R.id.buttonTrialManageAccount)?.visibility =
                    android.view.View.GONE
                dialogView.findViewById<Button>(R.id.buttonTrialHaOnly)?.visibility =
                    android.view.View.GONE
                dialogView.findViewById<Button>(R.id.buttonTrialDelete)?.visibility =
                    android.view.View.GONE
                dialogView.findViewById<Button>(R.id.buttonTrialSignOut)?.visibility =
                    android.view.View.VISIBLE
            }

            wireButtons(dialogView)

            // setCancelable(false) so back-press doesn't dismiss the modal
            // — the user must pick one of the explicit options to proceed.
            val d = AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            d.window?.let { w ->
                w.setBackgroundDrawableResource(android.R.color.transparent)
                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                w.setDimAmount(0.7f)
            }
            dialog = d
            d.show()

            // Default focus on the primary CTA: Subscribe on non-amazon
            // flavors, Sign Out on amazon (Subscribe + Manage Account are
            // hidden there per IAP compliance — Sign Out is the primary
            // action since the user has no other in-app path forward).
            // If neither is visible, fall back to whatever the first
            // visible button is.
            val defaultFocusBtnId =
                if (isAmazonFlavor)
                    R.id.buttonTrialSignOut
                else
                    R.id.buttonTrialSubscribe
            dialogView.findViewById<Button>(defaultFocusBtnId)?.let { btn ->
                if (btn.visibility == View.VISIBLE) btn.post { btn.requestFocus() }
                else dialogView.findViewById<Button>(R.id.buttonTrialExit)
                    ?.post { dialogView.findViewById<Button>(R.id.buttonTrialExit).requestFocus() }
            }
            Log.i(TAG, "Trial-expired dialog shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show trial-expired dialog: ${e.message}", e)
        }
    }

    private fun dismiss() {
        try {
            dialog?.takeIf { it.isShowing }?.dismiss()
        } catch (_: Exception) { /* non-fatal */ }
        dialog = null
    }

    private fun wireButtons(view: View) {
        val subscribe = view.findViewById<Button>(R.id.buttonTrialSubscribe)
        val manageAccount = view.findViewById<Button>(R.id.buttonTrialManageAccount)
        val haOnly = view.findViewById<Button>(R.id.buttonTrialHaOnly)
        val exit = view.findViewById<Button>(R.id.buttonTrialExit)
        val signOut = view.findViewById<Button>(R.id.buttonTrialSignOut)
        val delete = view.findViewById<Button>(R.id.buttonTrialDelete)

        subscribe.setOnClickListener {
            Log.i(TAG, "Subscribe Now tapped from trial-expired dialog")
            activity.sendBroadcast(
                Intent("com.dashieapp.Dashie.ACTION_DASHIE_TRIAL_EXPIRED_SUBSCRIBE").apply {
                    setPackage(activity.packageName)
                }
            )
        }

        // Manage Dashie Account — opens the Connect-to-Mobile QR modal
        // pointing the user's phone at the mobile site (Settings → Account),
        // where they can manage — or delete, with a 15-day recoverable
        // grace — their account from their own phone. Replaces the old
        // in-modal Delete Account button; also the Amazon IAP-compliant
        // path (no in-app payment direction).
        manageAccount.setOnClickListener {
            Log.i(TAG, "Manage Dashie Account tapped from trial-expired dialog")
            com.dashieapp.Dashie.sidebar.ConnectMobileDialog(activity).showManageAccount()
        }

        // HA-only mode visibility:
        //   - For trial_expired / canceled: only show when HA is already
        //     configured. Without it, this path drops the user into a
        //     kiosk shell with no HA URL — bad UX on a paywall dialog.
        //   - For ha_only: always show, even without HA configured. The
        //     user previously chose ha_only and signing back in is a
        //     legitimate moment to continue to HA setup.
        // Behavior on tap:
        //   - flip connection.haEnabled true
        //   - flip display.layoutMode = "kiosk" + account.forceKioskMode = true
        //     so determineInitialUrl() loads the kiosk shell, NOT the
        //     Dashie URL (which would just re-trigger the trial-expired
        //     modal because the account is still signed in but trial-
        //     expired). MainActivity.onCreate syncs forceKioskMode from
        //     layoutMode so we set both for consistency.
        //   - SWITCH_TO_KIOSK reload picks up the kiosk shell URL.
        val statusForHaButton = subPrefs.subscriptionStatus
        val showHaOnly = if (statusForHaButton == SubscriptionPreferences.STATUS_HA_ONLY) true
            else isHaConfigured()
        haOnly.visibility = if (showHaOnly) View.VISIBLE else View.GONE
        haOnly.setOnClickListener {
            Log.i(TAG, "Continue with HA Only tapped (haConfigured=${isHaConfigured()}, status=${subPrefs.subscriptionStatus})")
            // For an already-ha_only user (re-entering the modal after
            // signing back in), the server-side conversion is redundant
            // — skip it and go straight to applying local kiosk flags.
            val alreadyHaOnly = subPrefs.subscriptionStatus ==
                SubscriptionPreferences.STATUS_HA_ONLY
            if (alreadyHaOnly) {
                applyLocalHaOnlyKiosk()
                return@setOnClickListener
            }
            // Step 1: server-side conversion (purges feature data + flips
            // subscription_status to ha_only). Without this the next
            // launch hits check-subscription, sees 'trial_expired' in
            // user_profiles, and re-fires the modal — which is the
            // §8i bug. Disable the button while the network call is
            // in-flight so we don't double-fire on impatient taps.
            haOnly.isEnabled = false
            haOnly.text = "Switching to HA Only…"
            convertScope.launch {
                val ok = try {
                    convertToHaOnlyOnServer()
                } catch (e: Exception) {
                    Log.e(TAG, "convert_to_ha_only threw", e)
                    false
                }
                withContext(Dispatchers.Main) {
                    if (!ok) {
                        haOnly.isEnabled = true
                        haOnly.text = "Continue with Home Assistant Only"
                        Toast.makeText(
                            activity,
                            "Couldn't switch to HA Only — check your connection and try again.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }
                    // Step 2: now safe to flip local kiosk state. Once
                    // server says ha_only, every subsequent
                    // subscription-sync poll returns ha_only and the
                    // overlay logic skips firing the modal.
                    halitePrefsProvider()?.let { prefs ->
                        prefs.connection.haEnabled = true
                        prefs.display.layoutMode = "kiosk"
                        prefs.account.forceKioskMode = true
                        // Mark this kiosk lockdown as coming from the
                        // ha_only opt-in (vs a manual Settings toggle).
                        // The subscribe-back path checks this flag to
                        // know it should restore full-Dashie layout when
                        // the user later subscribes — without it we'd
                        // also undo a regular kiosk toggle on every
                        // active-status sync.
                        prefs.account.kioskFromHaOnly = true
                        // Mirror server-side status into the local
                        // SubscriptionPreferences cache so the Control
                        // Center reads ha_only on next render without
                        // waiting for a subscription-sync round-trip.
                        try {
                            SubscriptionPreferences(activity).subscriptionStatus = "ha_only"
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update local SubscriptionPreferences (non-fatal)", e)
                        }
                    }
                    activity.sendBroadcast(
                        Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_KIOSK").apply {
                            setPackage(activity.packageName)
                        }
                    )
                    dismiss()
                }
            }
        }

        exit.setOnClickListener {
            Log.i(TAG, "Exit Dashie tapped — finishing activity")
            dismiss()
            // Close the activity and remove from recents — clean exit.
            activity.finishAndRemoveTask()
        }

        // Sign Out: amazon-only path forward when the trial has expired.
        // Fires the same broadcast as the Settings sign-out path — wipes
        // per-account state, clears cookies, and navigates back to the
        // sign-in/kiosk shell so the user can re-enter with a different
        // account (e.g., one that subscribed externally on the web).
        signOut.setOnClickListener {
            Log.i(TAG, "Sign Out tapped from trial-expired dialog")
            dismiss()
            activity.sendBroadcast(
                Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_OUT").apply {
                    setPackage(activity.packageName)
                }
            )
        }

        // Delete button is visibility="gone" by default in view_trial_expired.xml
        // (the Delete Account on-device entry was removed everywhere — account
        // deletion happens via the web console). No click handler needed; the
        // findViewById is kept so the focus-chain list builder below can still
        // reference `delete` without a null check.

        // D-pad nav: chain through only the currently-visible buttons.
        // Order: Subscribe → ManageAccount → HA-Only → Exit → SignOut → Delete.
        // On amazon: Subscribe/ManageAccount/HA-Only/Delete are all hidden,
        // leaving Exit + SignOut. On non-amazon: SignOut is hidden; the
        // existing Subscribe/ManageAccount/HA-Only/Exit/Delete chain runs.
        listOf(subscribe, manageAccount, haOnly, exit, signOut, delete).forEach {
            it.isFocusable = true
        }
        val visibleButtons = listOf(subscribe, manageAccount, haOnly, exit, signOut, delete)
            .filter { it.visibility == View.VISIBLE }
        visibleButtons.forEachIndexed { i, btn ->
            btn.nextFocusUpId = visibleButtons.getOrNull(i - 1)?.id ?: btn.id
            btn.nextFocusDownId = visibleButtons.getOrNull(i + 1)?.id ?: btn.id
        }
    }

    /**
     * Apply the local kiosk lockdown for an already-ha_only user (no
     * server call needed — they're already ha_only on the server). Same
     * effect as the post-`convert_to_ha_only` block in the haOnly tap
     * handler, just without the redundant network round-trip. Used when
     * the user signs in to an ha_only account and taps "Continue with
     * Home Assistant Only" from the modal.
     */
    private fun applyLocalHaOnlyKiosk() {
        halitePrefsProvider()?.let { prefs ->
            prefs.connection.haEnabled = true
            prefs.display.layoutMode = "kiosk"
            prefs.account.forceKioskMode = true
            prefs.account.kioskFromHaOnly = true
        }
        activity.sendBroadcast(
            Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_KIOSK").apply {
                setPackage(activity.packageName)
            }
        )
        dismiss()
    }

    private fun isHaConfigured(): Boolean {
        return try {
            val prefs = halitePrefsProvider() ?: return false
            val url = prefs.connection.haUrl
            url.isNotBlank()
                && url != com.dashieapp.Dashie.halite.preferences.ConnectionPreferences.DEFAULT_HA_URL
        } catch (_: Exception) { false }
    }

    /**
     * Call the database-operations edge function's `convert_to_ha_only`
     * op. Server-side: purges Dashie cloud feature data and flips
     * subscription_status to ha_only. See handlers/account.ts for full
     * semantics.
     *
     * Auth: Supabase JWT from ConnectionPreferences (cached by
     * SupabaseTokenExtractor). Mirrors the call shape used by
     * SupabasePhotoSource.callEdgeFunction — JWT in Authorization +
     * jwtToken body field, anon key in apikey header.
     *
     * Returns true on success (response contains `converted: true`),
     * false on any failure (no JWT, network error, server error).
     */
    private fun convertToHaOnlyOnServer(): Boolean {
        val prefs = halitePrefsProvider() ?: run {
            Log.w(TAG, "convertToHaOnlyOnServer: halitePrefs unavailable")
            return false
        }
        val jwt = prefs.connection.supabaseJwt
        if (jwt.isEmpty()) {
            Log.w(TAG, "convertToHaOnlyOnServer: no Supabase JWT cached")
            return false
        }

        val body = JSONObject().apply {
            put("operation", "convert_to_ha_only")
            put("data", JSONObject())
            put("jwtToken", jwt)
        }

        val request = Request.Builder()
            .url(DATABASE_OPS_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $jwt")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e(TAG, "convert_to_ha_only HTTP ${response.code}: $errBody")
                    return@use false
                }
                val responseBody = response.body?.string() ?: return@use false
                val json = JSONObject(responseBody)
                val converted = json.optBoolean("converted", false)
                if (!converted) {
                    Log.e(TAG, "convert_to_ha_only returned converted=false: ${json.optString("error", "no error message")}")
                }
                converted
            }
        } catch (e: Exception) {
            Log.e(TAG, "convert_to_ha_only call failed", e)
            false
        }
    }
}
