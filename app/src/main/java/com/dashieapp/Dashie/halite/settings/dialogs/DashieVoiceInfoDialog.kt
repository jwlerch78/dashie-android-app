package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.edition.brandName

/**
 * "Dashie Voice & AI" explainer — reached from the Voice & AI Setup picker's
 * "Dashie Details & Setup" lead-in row. A short explainer + the three next-step
 * CTAs (create account, add credits, set up the add-on). Reuses
 * [CloudDialogScaffold] and the existing flows so nothing here re-implements
 * account/credit plumbing.
 *
 * Was `ChickadeeInfoDialog` until T2h (2026-07-31). The Chickadee brand is
 * retired: one brand, two editions (T2b). This dialog is shown on EVERY device
 * regardless of edition, so it carries no edition branding of its own — only its
 * "set up" button lands on an edition-specific surface ([HaEditionSetupDialog]).
 */
object DashieVoiceInfoDialog {

    fun show(activity: Activity) {
        // Amazon Appstore IAP compliance: no credits/external-billing CTA on
        // that flavor (same rule CloudActivationDialog / CreditBoundaryUi follow).
        val amazon = BuildConfig.FLAVOR == "amazon"
        // Already signed in → the account already exists; drop the create CTA.
        val signedIn = com.dashieapp.Dashie.halite.preferences.AccountPreferences(activity).isLinked

        // 🔴 EDITION GATE ADDED (A, 2026-08-05, sweep pass 2). These two CTAs were UNCONDITIONAL,
        // so on the account-free edition they rendered and then did nothing: both land on
        // ChickadeePaywallUi stubs whose own KDoc grades them `DROP: [unexpected]` — "reaching
        // this means a commercial entry point survived option-level gating." They had. This is
        // the component/outcome trap in its purest form: the seam was correct (the call no-ops)
        // and the OUTCOME was still a dead Dashie-branded button on a Chickadee settings screen.
        val hasAccounts = com.dashieapp.Dashie.edition.EditionSeams.hasAccounts
        val sellsCredits = com.dashieapp.Dashie.edition.EditionSeams.credits(activity).hasMeteredService

        val message = buildString {
            append("Voice & AI · Plug-and-play · Cloud & Local\n\n")
            // Neutral, not brand-resolved: "the <brand> add-on" would name a product that does
            // not exist for the published edition's step-1 beta (app + integration, no add-on).
            append("• Configure local voice & AI engines in the Home Assistant add-on\n")
            if (!amazon && sellsCredits) {
                append("• Cloud services with your own keys or voice credits\n")
            } else {
                append("• Cloud services with your own API keys\n")
            }
            append("• Live Speech-to-Speech (S2S) with Gemini supported")
        }

        val scaffold = CloudDialogScaffold(
            activity,
            title = "${activity.brandName()} Voice & AI",
            message = message
        )

        // Create account — the same signup fork the Account page uses (full
        // Dashie vs voice-only). Hidden once the user is signed in.
        if (hasAccounts && !signedIn) {
            scaffold.addButton("Create ${activity.brandName()} Account", primary = true) {
                com.dashieapp.Dashie.edition.EditionSeams.paywall(activity)
                    .showCloudActivation(com.dashieapp.Dashie.edition.CloudActivationReason.SIGNUP_FORK)
            }
        }

        // Add credits — the SAME modal the Account page uses: the web add-credits QR.
        // Brand-split T4: the hub-specific branch that had the TABLET mint a Stripe
        // Checkout is gone. The APK does not sell digital goods in-app on any hub; it
        // hands off to the web, where the phone signs in and pays. That dialog carries
        // the BYOK/local alternative inline, so the free path travels with it.
        if (!amazon && sellsCredits) {
            scaffold.addButton("Add Credits", primary = signedIn) {
                com.dashieapp.Dashie.edition.EditionSeams.paywall(activity)
                    .showAddCredits(source = "voice-info")
            }
        }

        // Set up the add-on — the PUBLISHED edition's setup explainer ("Dashie for
        // Home Assistant"), NOT the full edition's ConsoleSetupDialog.
        scaffold.addButton("Set up the Add-on") { HaEditionSetupDialog.show(activity) }

        scaffold.addButton("Close") { /* dismiss only */ }
        scaffold.show()
    }
}
