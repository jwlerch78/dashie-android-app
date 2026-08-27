package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity

/**
 * "Set up the Dashie Console" dialog (kiosk cloud/voice onboarding, phase 1).
 *
 * The Console — where a household configures local STT/TTS/LLM engines and enters
 * AI provider API keys — is the Home Assistant ADD-ON, served under HA's own
 * ingress (a per-session token, so there's no stable on-device deep link into the
 * running Console UI). So this dialog is instructional:
 *   1. what the Console is (opening paragraph),
 *   2. the quick install path for HA users familiar with custom add-ons, with the
 *      add-on repository URL shown as plain blue text to type into HA, and
 *   3. a QR to the full tutorial page for everyone else.
 *
 * The tutorial QR is a PLAIN https link to dashieapp.com (opens in a normal
 * browser). We deliberately avoid the my.home-assistant.io/redirect deep link and
 * a repo-README QR — iOS hands the former to the HA companion app (dead "Open
 * link" button, field report) and the latter dumps a dev README on the user.
 *
 * Reached from CloudActivationDialog's "Set up Dashie Console" button.
 */
object ConsoleSetupDialog {

    // Repo the user adds under HA → Settings → Apps → ⋮ → Repositories (HA
    // renamed "Add-ons" → "Apps" in 2026). Shown as plain text, not a QR.
    private const val ADDON_REPO_URL = "github.com/jwlerch78/dashie-ha-app"
    // Full install + usage tutorial — the QR target.
    private const val TUTORIAL_URL = "https://dashieapp.com/guides/dashie-console"

    fun show(activity: Activity) {
        val scaffold = CloudDialogScaffold(
            activity,
            title = "Set up the Dashie Console",
            message = "The Dashie Console runs as a Home Assistant app — that's where " +
                "you set up local AI & voice engines or enter your own AI API keys.\n\n" +
                "Install it from Settings → Apps → ⋮ (top right) → Repositories, then " +
                "add the URL below."
        )
        scaffold.setPrimaryUrl(ADDON_REPO_URL)
        scaffold.showQr(
            url = TUTORIAL_URL,
            urlLabel = "dashieapp.com/guides/dashie-console",
            intro = "For detailed instructions go here:"
        )
        scaffold.addButton("Done", primary = true) { /* dismiss only */ }
        scaffold.show()
    }
}
