package com.dashieapp.Dashie.halite.billing

import android.app.Activity

/**
 * The Control Center's trial-signup dialog and its "hide this offer" confirmation.
 *
 * ## Why it lives in src/dashie rather than being guarded in main/
 *
 * Chickadee has no accounts and no trial, so "To start your trial you'll need a Dashie
 * Account." describes something that does not exist. A guarded `if` in `ControlCenterOverlay`
 * would not have been enough: a guarded call still REFERENCES the dialog code and its layout,
 * so both would keep shipping inside the published APK. `lint:chickadee-surface` reads the
 * artifact, and it was reading exactly that string out of `dialog_trial_signup.xml`.
 *
 * Carried across VERBATIM from `ControlCenterOverlay` (2026-08-02). The only boundary change is
 * that the overlay's own `hide()` arrives as [onHide] — `main/` keeps ownership of the overlay
 * lifecycle and this file never learns what a Control Center is. Broadcast actions, the D-pad
 * focus chain, the underline-on-focus spans and the D.76 dismiss semantics are unchanged.
 *
 * `dialog_confirm` deliberately stays in `main/res`: it is a generic confirmation layout shared
 * with sign-out and cancel-trial, not a paywall surface.
 */
internal class TrialSignupUi(private val activity: Activity) {

    fun showTrialSignupDialog(onHide: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_trial_signup, null)

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            // Without FLAG_DIM_BEHIND the CC grid under the dialog stays
            // fully visible — looks like the modal is semi-transparent.
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        val cancelBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative)
        val positiveBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive)
        val signInLink = dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.linkSignIn)
        val dismissLink = dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.linkDismissTrial)

        // Cancel button
        cancelBtn.setOnClickListener { dialog.dismiss() }

        // Create Account button (orange) — routes to /login?mode=create so
        // the login page opens the sign-up overlay (Sign up with Google /
        // create-account flow) rather than the sign-in card.
        positiveBtn.setOnClickListener {
            dialog.dismiss()
            activity.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_IN").apply {
                    setPackage(activity.packageName)
                    putExtra("mode", "create")
                }
            )
            onHide()
        }

        // "I already have an account" link — sign-in flow (no mode hint).
        // TextView has no built-in focused-state visual; toggle underline
        // via SpannableString on focus change so d-pad users can see when
        // the link is selected.
        val signInLinkText = signInLink.text.toString()
        signInLink.setOnClickListener {
            dialog.dismiss()
            activity.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_IN").apply {
                    setPackage(activity.packageName)
                }
            )
            onHide()
        }
        signInLink.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val s = android.text.SpannableString(signInLinkText)
                s.setSpan(android.text.style.UnderlineSpan(), 0, signInLinkText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                signInLink.text = s
            } else {
                signInLink.text = signInLinkText
            }
        }

        // D.76 — "I'm not interested in a trial right now" link. Confirms
        // intent (dialog_confirm pattern, matching sign-out / cancel-trial
        // style) then sets trialOfferDismissed=true so the CC promo tile
        // disappears. Flag resets on next sign-in.
        val dismissLinkText = dismissLink.text.toString()
        dismissLink.setOnClickListener {
            showTrialOfferDismissConfirm(onHide) { confirmed ->
                if (confirmed) {
                    com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(activity)
                        .trialOfferDismissed = true
                    dialog.dismiss()
                    onHide()
                }
            }
        }
        dismissLink.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val s = android.text.SpannableString(dismissLinkText)
                s.setSpan(android.text.style.UnderlineSpan(), 0, dismissLinkText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                dismissLink.text = s
            } else {
                dismissLink.text = dismissLinkText
            }
        }

        // D-pad nav: link buttons + auto-focus the create-account button
        // (the primary action). Cancel ↔ Create row, then DOWN through
        // the sign-in link to the dismiss link.
        cancelBtn.isFocusable = true
        positiveBtn.isFocusable = true
        signInLink.isFocusable = true
        dismissLink.isFocusable = true
        cancelBtn.nextFocusRightId = com.dashieapp.Dashie.R.id.buttonPositive
        positiveBtn.nextFocusLeftId = com.dashieapp.Dashie.R.id.buttonNegative
        cancelBtn.nextFocusDownId = com.dashieapp.Dashie.R.id.linkSignIn
        positiveBtn.nextFocusDownId = com.dashieapp.Dashie.R.id.linkSignIn
        signInLink.nextFocusUpId = com.dashieapp.Dashie.R.id.buttonPositive
        signInLink.nextFocusDownId = com.dashieapp.Dashie.R.id.linkDismissTrial
        dismissLink.nextFocusUpId = com.dashieapp.Dashie.R.id.linkSignIn

        dialog.show()
        positiveBtn.post { positiveBtn.requestFocus() }
    }

    /**
     * D.76 — confirmation dialog shown when the user taps "I'm not
     * interested in a trial right now" on the trial-signup dialog.
     * Matches the sign-out / cancel-trial confirmation style (dialog_confirm
     * layout, Cancel + primary buttons, default focus on Cancel for the
     * safer choice on a dismiss-style action).
     */
    private fun showTrialOfferDismissConfirm(
        onHide: () -> Unit,
        onResult: (confirmed: Boolean) -> Unit,
    ) {
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text =
            "Hide trial offer?"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text =
            "We'll stop showing the free-trial offer in Control Center. " +
            "You can start a trial later from the Account menu in Control Center."

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        val negativeBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative)
        val positiveBtn = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive)
        negativeBtn.setOnClickListener {
            dialog.dismiss()
            onResult(false)
        }
        positiveBtn.apply {
            text = "Hide"
            setOnClickListener {
                dialog.dismiss()
                onResult(true)
            }
        }

        negativeBtn.isFocusable = true
        positiveBtn.isFocusable = true
        negativeBtn.nextFocusRightId = com.dashieapp.Dashie.R.id.buttonPositive
        positiveBtn.nextFocusLeftId = com.dashieapp.Dashie.R.id.buttonNegative

        dialog.show()
        // Default focus on Cancel — safer for a dismiss-style action
        // (mirrors the sign-out dialog pattern).
        negativeBtn.post { negativeBtn.requestFocus() }
    }
}
