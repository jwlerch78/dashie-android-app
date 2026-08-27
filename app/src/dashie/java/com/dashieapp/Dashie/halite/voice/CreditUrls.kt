package com.dashieapp.Dashie.halite.voice

import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.AccountPreferences

/**
 * Single source for the mobile add-credits page URL. Shared by the credit-
 * boundary UI ([CreditBoundaryUi]), the cloud-activation dialog
 * (CloudActivationDialog, kiosk cloud/voice onboarding phase 1) and the
 * Account page's Add Credits modal (AccountSettings.showAddCreditsFlow) so
 * the flows can't drift on the env branch, the identity query params, or the
 * hub-brand skin.
 */
object CreditUrls {

    /** The credit meter's product name. ONE brand, one meter (T2b, 2026-07-30):
     *  the same metered AI/voice service backs the family app and the published
     *  "Dashie for Home Assistant" edition (Nabu Casa model), so every surface
     *  says this regardless of edition. Matches the web add-credits page. */
    const val CREDITS_NAME = "Dashie Cloud credits"

    /** True when the household gateway is served by the PUBLISHED edition's
     *  integration (status probe's `hub` field, cached in VoicePreferences).
     *  Brands the MANAGEMENT-surface references (which add-on to open) — the
     *  credits noun itself is unconditional ([CREDITS_NAME]). */
    fun isPublishedHub(context: Context): Boolean =
        HalitePreferences(context).voice.isPublishedHub

    /** Where credit/voice settings are managed for this household — the add-on's
     *  name as it appears in HA, so "manage in ..." names something findable.
     *  The two editions ship two add-ons: "Dashie for Home Assistant" (published,
     *  slug `dashie_ha`) and "Dashie Console" (full, slug `dashie`). */
    fun managementSurface(context: Context): String =
        if (isPublishedHub(context)) "Dashie for Home Assistant" else "Dashie Console"

    /** Mobile add-credits page (FB25.3), env-branched like the subscribe QR,
     *  with the account identity pre-filled so the phone doesn't have to type
     *  it (it still signs in to check out). [source] tags the entry point
     *  (credit-prompt | account). No `&brand=` param: it was speculative ("so
     *  the page can skin itself") and add-credits.html never read one — and
     *  since the 2026-07-30 consolidation the page is one brand anyway. */
    fun addCreditsUrl(context: Context, source: String = "credit-prompt"): String {
        val isStaging = context.packageName.contains("staging") || context.packageName.contains("local")
        val base = if (isStaging) "https://dev.dashieapp.com" else "https://app.dashieapp.com"
        val prefs = AccountPreferences(context)
        val identity = buildString {
            if (prefs.authUserId.isNotEmpty()) append("&user=${prefs.authUserId}")
            if (prefs.email.isNotEmpty()) append("&email=${prefs.email}")
        }
        return "$base/add-credits.html?source=$source$identity"
    }

    /** Human-readable host shown under the QR (no scheme / query). */
    fun addCreditsDisplayHost(context: Context): String {
        val isStaging = context.packageName.contains("staging") || context.packageName.contains("local")
        return if (isStaging) "dev.dashieapp.com/add-credits" else "app.dashieapp.com/add-credits"
    }

    // CREDITS_RETURN_BASE / creditsReturnUrl removed with CreditCheckoutClient
    // (brand-split T4): they were the success/cancel landing for a checkout the TABLET
    // minted, and the APK no longer transacts — it QRs the web add-credits page, which
    // owns its own return URLs. Nothing else read them.
}
