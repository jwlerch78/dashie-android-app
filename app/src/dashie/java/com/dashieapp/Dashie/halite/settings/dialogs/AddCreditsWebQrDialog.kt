package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity
import com.dashieapp.Dashie.halite.voice.CreditUrls

/**
 * Web add-credits QR: a QR to the add-credits page where the phone signs in and checks
 * out. The APK never runs the checkout itself — Play's billing policy attaches to
 * digital goods and Amazon rejects external-payment UI, so the device surfaces balance
 * and hands off to the web.
 *
 * **Brand-split T4 — this is a METER, not a paywall.** Cloud inference genuinely costs
 * money, so billing it is legitimate even in the free Dashie-for-HA edition (the Nabu
 * Casa pattern: HA itself ships a Cloud page with a subscribe button). What makes it a
 * meter rather than a paywall is that the free alternatives are *visible right here* —
 * BYO API keys or a local model, one tap away in the same dialog. A purchase prompt
 * with no alternative in view reads as a paywall regardless of intent, which is why the
 * "Use your own keys or a local model" button below is not optional decoration.
 *
 * Built on [CloudDialogScaffold] (T4) rather than hand-rolled views: the scaffold
 * already does house chrome + stacked buttons + in-place QR, and hand-rolling left no
 * room for a second action. Also now the single implementation — AccountSettings had a
 * near-identical private copy, which its own comment flagged for consolidation.
 */
fun Activity.showAddCreditsWebQrDialog(source: String = "account") {
    val scaffold = CloudDialogScaffold(
        this,
        title = "Add ${CreditUrls.CREDITS_NAME}",
        message = "Cloud voice & AI is billed as you use it. Scan to add " +
            "${CreditUrls.CREDITS_NAME} on your phone — or run it free with your own AI " +
            "API keys or a local model."
    )
    scaffold.showQr(
        url = CreditUrls.addCreditsUrl(this, source = source),
        urlLabel = CreditUrls.addCreditsDisplayHost(this)
    )
    // The free path, in the same view as the paid one. Shown on every flavor including
    // amazon — it directs to an alternative, not to payment.
    scaffold.addButton("Use your own keys or a local model") { ConsoleSetupDialog.show(this) }
    scaffold.addButton("Done", primary = true) { /* dismiss only */ }
    scaffold.show()
}
