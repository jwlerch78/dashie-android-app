package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * The Account page's BILLING sections — the Dashie Plan subscription rows.
 *
 * ## Why this file is in `src/dashie/` rather than `main/`
 *
 * These rows name the paid product: the "Dashie Plan" header, and a "Subscribe to Dashie"
 * navigation row. The account-free edition has no plan to subscribe to, so on that build the
 * rows were unreachable anyway (`subscriptionGate` requires `account.isLinked`, which is
 * permanently false there) — but "unreachable" is not "absent": the strings still compiled
 * into the published artifact, where the surface gate sees them.
 *
 * Reached from `main/` through `EditionSeams.accountBillingSections`, whose Chickadee twin
 * returns an empty list. Same rule the sign-in section already follows in `AccountPageSchema`:
 * for an edition that lacks the concept, the section must not EXIST rather than exist-and-be-
 * hidden. `AccountPageEditionTest` pins both directions per variant.
 *
 * ⚠️ Deliberately NOT moved with it: `creditsSection` ("Dashie Cloud"). That header is
 * edition-NEUTRAL by decision (John, 2026-07-30/08-03) — the credit meter is the same product
 * on both editions, and its `byok_or_local` row is Dashie-only by design, not by accident.
 * Moving it here would quietly reverse a recorded ruling. See the notes on `creditsSection`.
 */
internal object AccountBillingSections {

    private val isLinked = Condition.IsTrue("account.isLinked")

    /** Every billing section of the Account page, in page order. Empty on editions
     *  without accounts — see the Chickadee twin of `EditionSeams`. */
    fun sections(): List<SettingsSection> = listOf(subscriptionSection())

    /**
     * Signed in, but NOT a self-provisioned kiosk (D2).
     *
     * A kiosk is signed into the HOUSEHOLD's account, so plan/credits/billing are the account
     * owner's business, not this wall tablet's — and worse, the subscription state shown here is
     * the kiosk's own stale/never-fetched copy, so it renders the WRONG plan. Billing belongs on
     * a device the owner actually signs into, or the console. Hide it on kiosks.
     */
    private val linkedNotKiosk: Condition =
        Condition.And(listOf(
            isLinked,
            Condition.IsFalse("account.haOnlyDisplay"),
            // Brand-split T4: forceKioskMode was MISSING here, so the canonical
            // `showsDashboard` definition (isLinked && !forceKioskMode &&
            // !haOnlyDisplay) and this local copy disagreed on one term. The gap was
            // reachable: sign in fully, then Settings → Account → "Use Kiosk Mode"
            // (the toggle two sections below this one) and you land in HA mode with
            // "Subscribe to Dashie" still on the page. Third term added.
            Condition.IsFalse("account.forceKioskMode")
        ))

    private val subscriptionGate: Condition =
        if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon")
            Condition.Never
        else
            linkedNotKiosk

    // Named "Dashie Plan", not "Subscription" — see creditsSection: two independent
    // entitlements on one identity, so each is named after the product it actually gates.
    // This one gates the FAMILY product (calendar/chores/photos), never the voice meter.
    private fun subscriptionSection() = SettingsSection(
        header = "Dashie Plan",
        visibleWhen = subscriptionGate,
        items = listOf(
            SchemaItem.Info(
                id = "subscription_plan",
                label = "Plan",
                valueKey = "subscription.planDisplay"
            )
        ) + (
            // Amazon Appstore IAP compliance: the Amazon flavor cannot direct
            // users to any external payment method for in-app digital content
            // (per Amazon's Developer Services Agreement). Subscribe / Manage
            // account both route to externally-paid / payment-capable flows, so
            // they're hidden on this flavor (subscriptions happen on the web).
            if (com.dashieapp.Dashie.BuildConfig.FLAVOR != "amazon") listOf(
                SchemaItem.Navigation(
                    id = "subscribe",
                    label = "Subscribe to Dashie",
                    navigateTo = "ext:subscribe",
                    visibleWhen = Condition.IsFalse("subscription.isPaidSubscriber")
                ),
                // FB27: "Manage account" moved here from its own section (and the old
                // "Manage Subscription" item removed). Orange Action → mobile-site QR.
                SchemaItem.Action(
                    id = "manage_account",
                    label = "Manage account",
                    action = "accountManageAccount",
                    destructive = false
                )
            ) else emptyList()
        )
    )
}
