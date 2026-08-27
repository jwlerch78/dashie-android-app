package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity

/**
 * "Set up Dashie for Home Assistant" dialog — the PUBLISHED edition's counterpart
 * to [ConsoleSetupDialog], reached from [DashieVoiceInfoDialog]'s "Set up the
 * Dashie Add-on" button. The two editions ship two different add-ons from two
 * different repositories, which is the whole reason this dialog exists separately:
 * "Dashie for Home Assistant" (`jwlerch78/dashie-ha`, the open/inspectable core)
 * vs the family "Dashie Console" (`jwlerch78/dashie-ha-app`).
 *
 * It runs as a Home Assistant ADD-ON (its console is served under HA's own
 * ingress, so there's no stable on-device deep link into the running UI) — so this
 * is instructional: what it is, the add-on repository URL to type into HA (plain
 * blue text, not a QR), and a QR to the project page for everyone else.
 *
 * Was `ChickadeeSetupDialog` until T2h (2026-07-31): the brand is retired, the
 * repo moved (`jwlerch78/chickadee` → `jwlerch78/dashie-ha`), and the retired
 * brand's domain is no longer the landing page. ⚠️ Unlike [ConsoleSetupDialog] — whose field
 * notes warn off repo-README QRs because they "dump a dev README on the user" —
 * the QR here is deliberately the repository: `dashie-ha`'s README IS the public
 * front door (install badge, screenshots, Releases), and the HA edition has no
 * dashieapp.com tutorial page yet. Re-point it when one exists.
 */
object HaEditionSetupDialog {

    // Repo the user adds under HA → Settings → Apps → ⋮ → Repositories.
    private const val ADDON_REPO_URL = "github.com/jwlerch78/dashie-ha"
    // Project page — the QR target (plain https, opens in a browser).
    private const val LANDING_URL = "https://github.com/jwlerch78/dashie-ha"

    fun show(activity: Activity) {
        val scaffold = CloudDialogScaffold(
            activity,
            title = "Set up the Home Assistant Add-on",
            message = "Local AI & voice engines are configured in a Home Assistant add-on — " +
                "that's where you set them up or enter your own AI API keys.\n\n" +
                "Install it from Settings → Apps → ⋮ (top right) → Repositories, then " +
                "add the URL below."
        )
        scaffold.setPrimaryUrl(ADDON_REPO_URL)
        scaffold.showQr(
            url = LANDING_URL,
            urlLabel = "github.com/jwlerch78/dashie-ha",
            intro = "Learn more:"
        )
        scaffold.addButton("Done", primary = true) { /* dismiss only */ }
        scaffold.show()
    }
}
