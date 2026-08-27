package com.dashieapp.Dashie.halite

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.AccountPreferences

/**
 * "Is this device running the free Dashie-for-Home-Assistant edition?"
 *
 * Dashie for Home Assistant is free — no account, no subscription (brand-split plan
 * 20260730, T4). That claim has to be true IN THE PRODUCT, so every **paywall** surface
 * — anything that gates a *feature* behind payment (the voice license, the 30-day trial
 * promo, Subscribe-to-Dashie, the trial-expired modal) — must be unreachable when the
 * device is in HA/kiosk mode.
 *
 * This is the mode analogue of the `BuildConfig.FLAVOR == "amazon"` compliance gate
 * (memory `reference_amazon_iap_compliance`), and it composes with it: amazon hides
 * external-payment surfaces on every mode, this hides paywalls on every flavor.
 *
 * ## What this gate does NOT hide
 *
 * **Meters stay.** Credits for cloud inference bill a consumable that genuinely costs
 * money and has a free alternative (BYO API keys, or a local model) — the Nabu Casa
 * pattern, and explicitly kept by the decision of record ("free software, not free
 * inference"). Do not wrap `CreditUrls` / `CreditBoundaryUi` / the Account credits
 * section in this gate. They carry their own requirement instead: every credits surface
 * must show the BYOK/local alternative **in the same view**.
 *
 * Paywall → gate here. Meter → leave alone, show the free path inline.
 */
object HaEditionGate {

    private const val TAG = "HaEditionGate"

    /**
     * True when this device shows Home Assistant rather than the Dashie dashboard.
     *
     * Delegates to [AccountPreferences.showsDashboard] — the single definition of "does
     * this device show the Dashie dashboard?" (`isLinked && !forceKioskMode &&
     * !haOnlyDisplay`). Deliberately NOT a fourth hand-rolled copy of that predicate:
     * it was already centralized once to end a 7-way mirror, and re-deriving it here is
     * how the tablet ends up half-believing it's a dashboard.
     *
     * Answers **true** for all three HA-edition shapes:
     *  - never signed in (the free HA default),
     *  - signed in but `forceKioskMode` (Settings → Account → Use Kiosk Mode),
     *  - signed in for voice only, `haOnlyDisplay` (Kiosk Real Login).
     */
    fun isHaMode(context: Context): Boolean =
        !AccountPreferences(context).showsDashboard

    /**
     * Guard for a paywall surface. Logs a grep-able DROP marker and returns true when the
     * caller should bail — a silent no-op here would be indistinguishable from the
     * surface simply not firing, and the standing rule is that every dispatch
     * fallthrough is loud (CLAUDE.md "No silent drops").
     *
     * ```
     * if (HaEditionGate.blockPaywall(activity, "voice-license purchase")) return
     * ```
     */
    fun blockPaywall(context: Context, surface: String): Boolean {
        // EDITION first, and this axis is not redundant with the mode check below.
        //
        // An edition with no metered service has nothing a paywall could sell, so the answer
        // cannot depend on device state. Chickadee *happens* to be covered by isHaMode today —
        // it can never be `isLinked`, so `showsDashboard` is always false — but that is
        // INCIDENTAL, resting on "the account-free edition never signs in" staying true
        // forever. Using a device-state predicate as a proxy for an edition capability is the
        // same shape as the Account-page bug Thread M diagnosed (a sticky display flag standing
        // in for session origin), and it fails the same way: silently, when the two diverge.
        //
        // Belt and braces on purpose. The primary defence is that these surfaces do not EXIST
        // in the published edition (2e/2f move them out of main/); this is the runtime backstop
        // for anything that has not moved yet.
        if (!com.dashieapp.Dashie.edition.EditionSeams.credits(context).hasMeteredService) {
            Log.i(TAG, "DROP: paywall surface '$surface' suppressed — this EDITION has no " +
                "metered service, so there is nothing to sell (structural, not device state)")
            return true
        }
        if (!isHaMode(context)) return false
        Log.i(TAG, "DROP: paywall surface '$surface' suppressed — HA edition is free (no account, no subscription)")
        return true
    }
}
