package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity

/**
 * Create the Account schema fragment with sign in/out and PIN ext: handlers.
 */
internal fun SettingsActivity.createAccountFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
    // Opening the Account page checks for a fresh credit balance (e.g. after a
    // phone add-credits purchase), so the credit dot/line reflects it without
    // waiting for a voice turn. Throttled (30s) inside the reader.
    halitePrefs.connection.supabaseJwt.takeIf { it.isNotEmpty() }
        ?.let { com.dashieapp.Dashie.halite.voice.CreditBalanceReader.refreshAsync(it) }
    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.AccountPageSchema.create()
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when (target) {
                "ext:sign_in" -> {
                    sendBroadcast(
                        android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_IN").apply {
                            setPackage(packageName)
                        }
                    )
                    setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
                    finish()
                    true
                }
                "ext:sign_up" -> {
                    // Fork: full Dashie (calendar/widgets + trial) vs voice-only
                    // (stay in kiosk, add cloud voice — beta). Each button fires the
                    // ACTION_DASHIE_SIGN_IN broadcast with the right flow and closes
                    // Settings; the dialog stays on top until the user picks.
                    com.dashieapp.Dashie.edition.EditionSeams.paywall(this)
                        .showCloudActivation(com.dashieapp.Dashie.edition.CloudActivationReason.SIGNUP_FORK)
                    true
                }
                "ext:sign_out" -> {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
                    dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Sign Out"
                    dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage).text =
                        "Sign out of your Dashie account? The device will return to kiosk mode."

                    val dialog = android.app.AlertDialog.Builder(this)
                        .setView(dialogView)
                        .setCancelable(true)
                        .create()
                    dialog.window?.let { w ->
                        w.setBackgroundDrawableResource(android.R.color.transparent)
                        // Explicit dim — without this, the dialog renders flat
                        // on top of the SettingsActivity content with no visual
                        // separation, looking like part of the settings page.
                        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        w.setDimAmount(0.6f)
                    }

                    val negativeBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonNegative)
                    negativeBtn.setOnClickListener { dialog.dismiss() }
                    val positiveBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonPositive)
                    positiveBtn.apply {
                        text = "Sign Out"
                        setOnClickListener {
                            dialog.dismiss()
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            sendBroadcast(
                                android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_OUT").apply {
                                    setPackage(packageName)
                                }
                            )
                            setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
                            finish()
                        }
                    }

                    dialog.show()
                    // Item 21: focus the Cancel button on open so d-pad
                    // immediately operates on the dialog (no hidden first-
                    // press needed to land focus). Cancel is the safer
                    // default for a destructive action.
                    //
                    // isFocusable (not isFocusableInTouchMode): d-pad devices
                    // run in non-touch mode where isFocusable + requestFocus()
                    // is enough. Setting isFocusableInTouchMode makes the
                    // FIRST tap on a touchscreen only grab focus — the click
                    // lands on the second tap. Leaving it off gives touch
                    // devices a normal single-tap activation.
                    negativeBtn.isFocusable = true
                    positiveBtn.isFocusable = true
                    negativeBtn.nextFocusRightId = R.id.buttonPositive
                    positiveBtn.nextFocusLeftId = R.id.buttonNegative
                    negativeBtn.post { negativeBtn.requestFocus() }
                    true
                }
                "ext:set_pin" -> {
                    val lockDialogs = com.dashieapp.Dashie.halite.sidebar.dialogs.LockDialogs(this, halitePrefs)
                    lockDialogs.startPinSetupFlow(isChange = halitePrefs.lock.hasPinSet) {
                        supportFragmentManager.fragments
                            .filterIsInstance<com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>()
                            .firstOrNull()?.refresh()
                    }
                    true
                }
                "ext:subscribe" -> {
                    showSubscribeFlow()
                    true
                }
                "ext:manage_subscription" -> {
                    showManageSubscriptionFlow()
                    true
                }
                else -> false
            }
        }
    )

    // Cold credit cache on first open: the whole Credits section is gated on
    // `credits.hasCredits` == (CreditStateHolder.balance != null), and the
    // `credits.balance` getter is read-through — it returns the CACHE and kicks the
    // fetch for the NEXT render. So a linked account with no cached balance renders
    // with no Credits header, no Balance, no Auto-Refill, and nothing redraws when
    // the fetch lands; the user has to leave the page and come back. Guaranteed
    // after any sign-out → re-provision, because endSession() calls
    // CreditStateHolder.reset() (balance = null).
    //
    // Same one-shot re-render VoiceAiSettings uses for its cloud-preset gate. Only
    // armed when the cache is actually cold, so a warm open costs nothing.
    if (com.dashieapp.Dashie.halite.voice.CreditStateHolder.balance == null &&
        halitePrefs.connection.supabaseJwt.isNotEmpty()
    ) {
        com.dashieapp.Dashie.halite.voice.CreditStateHolder.addOnChanged("account-credit-balance") {
            com.dashieapp.Dashie.halite.voice.CreditStateHolder.removeOnChanged("account-credit-balance")
            // The user may have navigated away before the balance landed — refreshing a
            // detached fragment throws. (VoiceAiSettings' equivalent predates this guard.)
            runOnUiThread { if (fragment.isAdded) fragment.refresh() }
        }
    }

    return fragment
}

internal fun SettingsActivity.showSubscribeFlow() {
    val accountPrefs = com.dashieapp.Dashie.halite.preferences.AccountPreferences(this)
    val authUserId = accountPrefs.authUserId
    if (authUserId.isEmpty()) {
        android.widget.Toast.makeText(this, "Please sign in first", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    // Hand off to the existing QR purchase dialog instead of navigating
    // the dashboard's WebView to subscribe.html. The previous implementation
    // called wv.loadUrl(subscribeUrl) which replaced the dashboard URL with
    // the subscribe page in the same WebView — sidebar and all dashboard
    // navigation broke until the user manually navigated back. The trial-
    // expired overlay's Subscribe Now button already broadcasts this action;
    // MainBroadcastManager routes it to ControlCenterOverlay
    // .openPurchaseSubscriptionFlow() which renders a QR dialog at the
    // activity window level (same as voice-license purchase) — dashboard
    // chrome stays put, user scans on phone, dismisses on success.
    finish()
    sendBroadcast(
        android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_TRIAL_EXPIRED_SUBSCRIBE").apply {
            setPackage(packageName)
        }
    )
}

/** FB27: "Add Credits" from the Account page — the same orange-QR add-credits modal as the
 *  CR2 prompt (mobile add-credits page, account identity pre-filled). */
internal fun SettingsActivity.showAddCreditsFlow() {
    // Amazon IAP compliance: no external-payment QR. The Account schema's
    // "Add Credits" item is already hidden on amazon; this guard prevents a
    // regression if another caller is ever wired to this handler.
    if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon") return
    val accountPrefs = com.dashieapp.Dashie.halite.preferences.AccountPreferences(this)
    if (accountPrefs.authUserId.isEmpty()) {
        android.widget.Toast.makeText(this, "Please sign in first", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    // Brand-split T4: the APK does NOT sell credits in-app. This used to branch to
    // a hub-specific credits dialog on a published hub, which had the TABLET mint a Stripe
    // Checkout (CreditCheckoutClient.createCheckout) and QR the checkout URL — the
    // device transacting for digital goods, which is what Play's billing policy
    // attaches to and what Amazon rejects outright. Every hub now takes the same web
    // hand-off: the QR points at the web page, the phone signs in and pays there.
    //
    // Through the EDITION SEAM, not a direct call: the dialog and CreditUrls now live in
    // src/dashie/ so their dashieapp.com endpoints do not ship in the account-free artifact.
    // The implementation is unchanged (AddCreditsWebQrDialog.kt, still the single one, still
    // carrying the T4 requirement that this surface show the BYOK/local alternative in the
    // same view) — only who reaches it changed.
    com.dashieapp.Dashie.edition.EditionSeams.paywall(this).showAddCredits(source = "account")
}

internal fun SettingsActivity.showManageSubscriptionFlow() {
    // Amazon IAP compliance: no subscription-management QR / external link.
    if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon") return
    val subPrefs = com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(this)
    val customerId = subPrefs.stripeCustomerId
    if (customerId.isEmpty()) {
        android.widget.Toast.makeText(this, "No subscription found", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    // Subscription management lives on the Dashie mobile/web app, not on the
    // device — there's no on-dashboard cancel. Show the same "scan to open
    // Dashie on your phone" QR as the hamburger "Connect to Mobile" entry,
    // with copy pointing the user to Settings → Account. The mobile site
    // requires a login, so this also keeps a kid from cancelling off the
    // open dashboard. (Earlier this did wv.loadUrl(subscribe.html), which
    // trapped the user in a full-screen page with no back — D.24.)
    com.dashieapp.Dashie.edition.EditionSeams.paywall(this).showConnectMobile(
        titleOverride = "Manage Subscription",
        subtitleOverride = "Scan with your phone, then open Settings → Account to manage or cancel your subscription."
    )
}
