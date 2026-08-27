package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity
import android.content.Intent
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.voice.CreditUrls

/**
 * Cloud-activation popups shown when a not-yet-cloud-ready device taps the grayed
 * Cloud / Hybrid voice preset (kiosk cloud/voice onboarding, phase 1). Reached
 * from SettingsCallbackWiring "onCloudPresetBlocked", which computes the
 * account/credit state and calls the matching variant.
 *
 *  - [showNeedsAccount] — no Dashie account on the device:
 *        [Create a Dashie account with Google] [Set up Dashie Console] [Go Back]
 *
 *  - [showNeedsCredits] — account linked but not cloud-ready (no credits / BYO
 *    key), and the post-account-creation next-steps prompt. Add-credits is hidden
 *    when credits already exist (e.g. beta-comped) — then it's just console + Done:
 *        [Add cloud credits]? [Set up Dashie Console] [Done]
 */
object CloudActivationDialog {

    fun showNeedsAccount(activity: Activity) {
        // Cloud Voice & AI is in BETA — lead with that so a user selecting Cloud/Hybrid
        // knows the feature set is still gated (they can still create an account; the
        // cloud path just needs beta access to complete downstream).
        val betaNotice = "Cloud Voice & AI is currently in beta.\n\n"
        // Amazon Appstore IAP compliance: no mention of credits/replenishment (there's
        // no in-app path to web billing on that flavor). Steer to the local/Console options.
        val message = betaNotice + if (BuildConfig.FLAVOR == "amazon") {
            "Cloud capabilities require an account. You can also set up local AI & voice " +
                "engines or enter AI API keys in the Dashie Console in Home Assistant."
        } else {
            "Cloud capabilities require an account and credits. You can also " +
                "set up local AI & voice engines or enter AI API keys in the Dashie " +
                "Console in Home Assistant."
        }
        val scaffold = CloudDialogScaffold(
            activity,
            title = "Cloud Voice & AI",
            message = message
        )
        scaffold.addButton("Create a Dashie account with Google", primary = true) {
            startAccountCreation(activity)
        }
        // Brand-split T4: the free alternative is a peer button, not a footnote in the
        // message body. Relabeled from "Set up Dashie Console" — that named the tool,
        // not the outcome, so it didn't read as an alternative to creating an account.
        scaffold.addButton("Use your own keys or a local model") { ConsoleSetupDialog.show(activity) }
        scaffold.addButton("Go Back") { /* dismiss only */ }
        scaffold.show()
    }

    /**
     * First-activation starter grant: "cloud voice runs on credits, and we've put $X in your
     * account to try it." Shown INSTEAD of [showNeedsCredits] the first time a user activates
     * cloud/hybrid Voice & AI (2026-07-29 decision).
     *
     * The disclosure is the point, not a courtesy. Silently funding an account would mean the
     * user's first metered feature starts spending without them ever being told it is metered
     * — they'd discover it at the empty-balance wall instead, which is precisely the moment
     * we're trying to design away.
     *
     * [amountUsd] is the SERVER's figure, passed in from the claim response. Never hardcode
     * it: the amount lives in `runtime_config.starter_grant`, and a number frozen into an APK
     * keeps asserting itself on every tablet whose owner hasn't updated.
     *
     * No turn-count estimate in the copy on purpose — the same $0.50 is ~35 cloud turns or
     * ~237 hybrid turns depending on the pipeline, so any single number would be wrong for
     * half the audience.
     */
    fun showStarterGrant(activity: Activity, amountUsd: Double, onDone: () -> Unit = {}) {
        // Amazon Appstore IAP compliance: this flavor drops ALL mention of credits and
        // replenishment, not just the purchase CTA — the rule showNeedsCredits and
        // CreditBoundaryUi already follow. The grant itself still happens (voice works); the
        // user just isn't told about the meter here. Following the established convention
        // rather than re-litigating it per-dialog; if the rule is later judged to cover only
        // external-payment surfaces, this is the one line to revisit.
        if (BuildConfig.FLAVOR == "amazon") { onDone(); return }
        val amount = formatUsd(amountUsd)
        val scaffold = CloudDialogScaffold(
            activity,
            title = "Cloud Voice & AI",
            message = "Cloud voice & AI runs on ${CreditUrls.CREDITS_NAME}, which are billed as " +
                "you use them.\n\nWe've added $amount to your account so you can try it out. " +
                "You can add more any time — or run it free with your own AI API keys or a " +
                "local model.",
        )
        scaffold.setOnDismiss(onDone)
        scaffold.addButton("Start using voice", primary = true) { /* dismiss → onDone */ }
        // Brand-split T4: this dialog's whole purpose is disclosing that the feature is
        // metered, so the free alternatives belong here too — this is the moment the
        // user decides whether to be a paying customer, and it should read as a choice.
        // An actionable button, not just the sentence above: the requirement is the free
        // path in the same VIEW, and a route the user can't take from here isn't one.
        scaffold.addButton("Use your own keys or a local model") { ConsoleSetupDialog.show(activity) }
        scaffold.show()
    }

    /** "$0.50", "$1", "$1.25" — trailing ".00" reads like a price tag on a free thing. */
    private fun formatUsd(v: Double): String =
        if (v == Math.floor(v)) "$" + v.toInt() else "$" + String.format("%.2f", v)

    fun showNeedsCredits(activity: Activity, hasCredits: Boolean, onDismiss: () -> Unit = {}) {
        // Amazon Appstore IAP compliance: hide the Add-credits CTA/QR (an external web-billing
        // surface) AND drop any mention of credits/replenishment — same rule CreditBoundaryUi
        // follows. On Amazon the dialog steers to local AI/voice + Console only.
        val amazon = BuildConfig.FLAVOR == "amazon"
        val message = when {
            hasCredits ->
                "You’re all set for cloud voice & AI. You can also set up local AI & voice " +
                    "engines or enter AI API keys in the Dashie Console in Home Assistant."
            amazon ->
                "Set up local AI & voice engines or enter AI API keys in the Dashie Console " +
                    "in Home Assistant."
            else ->
                // Brand-split T4: lead with the fact that this is metered inference, not
                // a locked feature, and give the two free paths equal billing with the
                // paid one — they are peer buttons below.
                "Cloud voice & AI is billed as you use it. Add credits, or run it free by " +
                    "pointing Dashie at a local model or entering your own AI API keys in " +
                    "the Dashie Console in Home Assistant."
        }
        val scaffold = CloudDialogScaffold(activity, title = "Cloud Voice & AI", message = message)
        // On dismiss, the caller re-reads the balance + re-renders Voice & AI so
        // Cloud/Hybrid un-gray right after the user adds credits (no back-and-return).
        scaffold.setOnDismiss(onDismiss)
        if (!hasCredits && !amazon) {
            val addBtn = scaffold.addButton("Add cloud credits", primary = true, dismissFirst = false) {}
            addBtn.setOnClickListener {
                // Reveal the add-credits QR in place (refill is account-holder-only —
                // no on-tablet checkout), then the button becomes Done.
                scaffold.showQr(
                    url = CreditUrls.addCreditsUrl(activity),
                    urlLabel = CreditUrls.addCreditsDisplayHost(activity)
                )
                addBtn.text = "Done"
                addBtn.setOnClickListener { scaffold.dismiss() }
            }
        }
        // The free path, always present and always adjacent to the paid one (T4).
        // Console is the primary CTA whenever there's no Add-credits button (all-set or amazon).
        scaffold.addButton("Use your own keys or a local model", primary = hasCredits || amazon) {
            ConsoleSetupDialog.show(activity)
        }
        scaffold.addButton("Done") { /* dismiss only */ }
        scaffold.show()
    }

    /**
     * Account-creation fork shown from the Account menu ("Create a Dashie Account")
     * on an anonymous device. Lets the user pick how to onboard THIS device:
     *   - Full Dashie — calendar, widgets, photos + the 30-day trial (mode=create,
     *     no flow → onDashieAuthComplete builds the dashboard).
     *   - Just for Voice & AI — stay in the HA kiosk and add cloud voice (flow=voice,
     *     the same path CloudActivationDialog.showNeedsAccount kicks off). Cloud
     *     voice is BETA — NOT hard-gated here (a non-beta account simply can't finish
     *     the cloud setup downstream, where the preset gate hides Cloud/Hybrid/Local),
     *     but the copy says so up front so the user isn't surprised.
     */
    fun showSignupFork(activity: Activity) {
        val scaffold = CloudDialogScaffold(
            activity,
            title = "Create a Dashie Account",
            message = "How do you want to use Dashie on this device?\n\n" +
                "Voice & AI is currently in beta — you'll need beta access to finish " +
                "cloud voice setup."
        )
        scaffold.addButton("Calendar, Widgets & more", primary = true) {
            startFullAccountCreation(activity)
        }
        scaffold.addButton("Just for Voice & AI (Beta)") {
            startAccountCreation(activity)
        }
        scaffold.addButton("Go Back") { /* dismiss only */ }
        scaffold.show()
    }

    /**
     * Full-dashboard Google signup: mode=create with NO flow, so the web /login
     * builds the dashboard + starts the 30-day trial (onDashieAuthComplete) — the
     * counterpart to [startAccountCreation]'s voice-only (flow=voice) path.
     */
    private fun startFullAccountCreation(activity: Activity) {
        activity.sendBroadcast(
            Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_IN").apply {
                setPackage(activity.packageName)
                putExtra("mode", "create")
            }
        )
        if (activity is SettingsActivity) {
            activity.setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
            activity.finish()
        }
    }

    /**
     * Kick off Google account creation for the "just add voice, stay in kiosk"
     * fork (entry-point-decides). Fires the sign-in broadcast with flow="voice",
     * which the web /login flow reads to: create an ha_only account (device_type
     * ha_app → NO 30-day trial), keep home_assistant.enabled, and on success call
     * DashieNative.returnToKioskAfterSignIn() (haOnlyDisplay=true → stays on the HA
     * kiosk shell, no setup wizard) instead of building the full dashboard.
     * mode=create still drives the signup card; flow=voice diverts the post-auth path.
     */
    private fun startAccountCreation(activity: Activity) {
        activity.sendBroadcast(
            Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_IN").apply {
                setPackage(activity.packageName)
                putExtra("mode", "create")
                putExtra("flow", "voice")
            }
        )
        if (activity is SettingsActivity) {
            activity.setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
            activity.finish()
        }
    }
}
