package com.dashieapp.Dashie.halite.billing

import android.app.Activity
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The Control Center's "Subscribe to Dashie" QR flow, its activation polling, and the success
 * dialog.
 *
 * ## Why it is here and not guarded in main/
 *
 * Chickadee sells nothing, so a subscribe flow describes a thing that does not exist. A guarded
 * `if` at the call site would still REFERENCE this code and its two layouts, so both would keep
 * shipping inside the published APK — the same reason 2f-3's `onCloudPresetBlocked` and 2f-4's
 * trial dialog had to move rather than be wrapped.
 *
 * Carried across VERBATIM from `ControlCenterOverlay` (2026-08-02). Two boundary changes only:
 * the overlay's `refreshContent()` arrives as [onRefresh], and it is threaded through the
 * activation path so the CC still redraws itself after a successful subscribe. The amazon-IAP
 * early return, the `HaEditionGate` paywall gate, the per-user QR URL, the polling and its
 * previous-status comparison are unchanged.
 *
 * ⚠️ Both early returns are KEPT even though this file only exists in the Dashie edition. They
 * guard different axes — amazon flavour (store compliance) and HA/kiosk mode (a Dashie device
 * displaying HA is still free) — and neither is implied by the edition split.
 */
internal class SubscribeUi(
    private val activity: Activity,
    /**
     * The Control Center's own scope. Passed in rather than created here so the activation
     * POLLING dies with the overlay exactly as it did before the move — a scope owned by this
     * short-lived object would outlive the dialog it belongs to and keep polling after the CC
     * closed. The compiler found this dependency; a grep for view/`R.` references did not.
     */
    private val scope: CoroutineScope,
    /** Ask the host to run a subscription sync. Overlay-owned; null when nothing is wired. */
    private val onTriggerSubscriptionSync: (() -> Unit)?,
) {

    private companion object {
        const val TAG = "CCSubscribeUi"
    }

    fun showPurchaseSubscriptionFlow(onRefresh: () -> Unit) {
        // Defense-in-depth: gate the QR builder too so all entry points
        // (purchase-subscription action, upsell-*, the wrapper) get the
        // IAP-compliant no-op on the amazon flavor.
        if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon") {
            android.util.Log.i(
                TAG,
                "Amazon flavor — showPurchaseSubscriptionFlow gated for IAP compliance; no-op."
            )
            return
        }
        // Brand-split T4: same double early-return as amazon, on the mode axis.
        if (com.dashieapp.Dashie.halite.HaEditionGate.blockPaywall(activity, "subscribe QR builder")) return
        val accountPrefs = com.dashieapp.Dashie.halite.preferences.AccountPreferences(activity)
        val authUserId = accountPrefs.authUserId
        val email = accountPrefs.email
        if (authUserId.isEmpty()) {
            android.widget.Toast.makeText(activity, "Please sign in first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val isStaging = activity.packageName.contains("staging") || activity.packageName.contains("local")
        val baseUrl = if (isStaging) "https://dev.dashieapp.com" else "https://app.dashieapp.com"
        val subscribeUrl = "$baseUrl/subscribe.html?user=$authUserId&email=$email"

        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_subscribe_dashie, null)

        val qrCodeImage = dialogView.findViewById<android.widget.ImageView>(com.dashieapp.Dashie.R.id.imageQrCode)
        val cancelButton = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonCancel)
        val checkButton = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonCheckSubscription)
        // No typeable URL caption: the QR encodes a per-user URL that
        // can't be reproduced by typing without a pairing code. Plan to
        // restore once §9c "Connect to Mobile" token system lands.

        // QR code → subscribe.html (auth_user_id + email pre-filled).
        // Foreground colored Dashie orange to match brand. The visible URL
        // caption (textSubscribeUrl) is hardcoded in the layout to
        // "dashieapp.com/subscribe" — matches voice-license pattern.
        val qrBitmap = com.dashieapp.Dashie.halite.QrCodeGenerator.generateQrCode(
            url = subscribeUrl,
            size = 512,
            foregroundColor = 0xFFFF9500.toInt()
        )
        if (qrBitmap != null) {
            qrCodeImage.setImageBitmap(qrBitmap)
        } else {
            qrCodeImage.visibility = android.view.View.GONE
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        // Check Subscription: trigger JS to call check-subscription, wait for
        // SubscriptionPreferences to update, then surface a status-aware
        // toast in every branch (the silent-dismiss case bit us once).
        checkButton.setOnClickListener {
            val sync = onTriggerSubscriptionSync
            val previousStatus = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity).subscriptionStatus

            if (sync == null) {
                // No JS bridge wired — best we can do is read local cache.
                handleSubscriptionCheckResult(dialog, checkButton, previousStatus, onRefresh)
                return@setOnClickListener
            }

            checkButton.isEnabled = false
            checkButton.text = "Checking…"
            sync.invoke()

            // Give JS ~2.5s to call check-subscription and write back via the
            // JS bridge delegate.
            scope.launch {
                kotlinx.coroutines.delay(2500)
                activity.runOnUiThread {
                    handleSubscriptionCheckResult(dialog, checkButton, previousStatus, onRefresh)
                }
            }
        }

        // D-pad nav: focus Check Subscription by default (primary action)
        cancelButton.isFocusable = true
        checkButton.isFocusable = true
        cancelButton.nextFocusRightId = com.dashieapp.Dashie.R.id.buttonCheckSubscription
        checkButton.nextFocusLeftId = com.dashieapp.Dashie.R.id.buttonCancel

        // Auto-poll while dialog is open: re-sync subscription state every
        // 5s. If status flips to ACTIVE during that window (webhook fired
        // after Stripe Checkout completed on the user's phone), we surface
        // the same activation flow as the manual button — toast + restart.
        val pollJob = scope.launch {
            while (dialog.isShowing) {
                kotlinx.coroutines.delay(5000)
                if (!dialog.isShowing) break
                val sync = onTriggerSubscriptionSync ?: continue
                val before = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity).subscriptionStatus
                sync.invoke()
                kotlinx.coroutines.delay(2000)
                if (!dialog.isShowing) break
                val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity)
                if (subPrefs.subscriptionStatus == com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_ACTIVE
                    && before != com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_ACTIVE) {
                    activity.runOnUiThread { onSubscriptionActivated(dialog, onRefresh) }
                    break
                }
            }
        }
        dialog.setOnDismissListener { pollJob.cancel() }

        dialog.show()
        checkButton.post { checkButton.requestFocus() }
    }

    /**
     * Successful subscription activation — dismiss the subscribe dialog
     * and show a celebratory success modal mirroring the
     * voice-license-success.html page (orange check, "Thank You!", plan
     * info, Continue). No app restart: the next onRefresh() reads
     * the updated SubscriptionPreferences automatically, and the JS
     * layer already re-syncs via subscription-sync.js.
     */
    private fun onSubscriptionActivated(
        subscribeDialog: android.app.AlertDialog,
        onRefresh: () -> Unit,
    ) {
        Log.i(TAG, "Subscription activated — showing success dialog")
        subscribeDialog.dismiss()
        showSubscribeSuccessDialog(onRefresh)
    }

    private fun showSubscribeSuccessDialog(onRefresh: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_subscribe_success, null)
        val planText = dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.textPlan)
        val continueButton = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonContinue)

        // Plan label sourced from SubscriptionPreferences (written by
        // syncSubscriptionState immediately before this dialog opens).
        // Plan name only — no dollar amount. Keeps the post-purchase
        // confirmation price-agnostic so pricing can change in Stripe
        // without a code update; the price still shows on the Stripe
        // checkout page the user just completed.
        val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity)
        planText.text = when (subPrefs.subscriptionPlan) {
            "dashie_annual" -> "Annual plan"
            "dashie_monthly" -> "Monthly plan"
            else -> "Active"
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        continueButton.setOnClickListener {
            dialog.dismiss()
            // Refresh CC so gated cards (Calendar, Locations, Chores)
            // pick up the new subscription state immediately.
            onRefresh()
        }
        dialog.setOnDismissListener { onRefresh() }

        continueButton.isFocusable = true

        dialog.show()
        continueButton.post { continueButton.requestFocus() }
    }

    /**
     * Decide what to show after a Check Subscription tap. Always surfaces a
     * toast; only dismisses on a real status flip into active access (so a
     * user mid-trial isn't bounced when there's no new subscription).
     */
    private fun handleSubscriptionCheckResult(
        dialog: android.app.AlertDialog,
        checkButton: android.widget.Button,
        previousStatus: String,
        onRefresh: () -> Unit,
    ) {
        val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity)
        val newStatus = subPrefs.subscriptionStatus
        val active = subPrefs.hasActiveAccess
        val flipped = newStatus != previousStatus

        // Status flipped INTO active — webhook fired between previous read
        // and this one. Mirror voice-license activation: dismiss + toast +
        // restart. Skip the rest of this function.
        if (flipped && newStatus == com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_ACTIVE) {
            onSubscriptionActivated(dialog, onRefresh)
            return
        }

        val message: String
        val shouldDismiss: Boolean

        when {
            // Already active subscriber, nothing changed.
            active && newStatus == com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences.STATUS_ACTIVE -> {
                message = "You're already subscribed"
                shouldDismiss = true
            }
            // Trialing or complimentary — has access but didn't flip. Don't
            // dismiss; the user is presumably trying to convert their trial
            // to a paid plan and the purchase hasn't landed yet.
            active -> {
                message = "No new subscription detected. You're still in your trial."
                shouldDismiss = false
            }
            // No access at all (trial expired, never signed up).
            else -> {
                message = "No active subscription found. Complete checkout on your phone first."
                shouldDismiss = false
            }
        }

        android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()

        if (shouldDismiss) {
            dialog.dismiss()
            onRefresh()
        } else {
            checkButton.isEnabled = true
            checkButton.text = "Check Subscription"
        }
    }
}
