package com.dashieapp.Dashie.edition

/**
 * Subscription-derived feature access and status display.
 *
 * Dashie gates Calendar / Locations / Chores behind an active subscription and shows
 * trial-expiry subtext in the control centre. Chickadee has no subscription, so every
 * feature it ships is simply available.
 *
 * ⚠️ **Not every member here is a paywall question.** [isHaOnlyPlan] and [hasActiveAccess]
 * are also read by `MainActivity`'s kiosk-mode recovery heuristics, which need a TRUTHFUL
 * answer rather than a generous one. See each member.
 */
interface SubscriptionState {

    /**
     * Does this device have access to [featureId] (`calendar`, `locations`, `chores`, …)?
     *
     * Chickadee: always `true` — nothing it ships is subscription-gated.
     */
    fun hasFeatureAccess(featureId: String): Boolean

    /**
     * Subscription-aware subtext for a control-centre feature card ("Trial: 3 days left",
     * "Requires subscription"), or `null` for no subtext.
     *
     * Chickadee: always `null`.
     */
    fun featureSubtext(featureId: String): String?

    /**
     * Should [featureSubtext] be styled as a warning (trial expiring soon)?
     *
     * Chickadee: always `false`.
     */
    fun isTrialWarning(featureId: String): Boolean

    /** Human-readable subscription status for the Account page. Chickadee: `""` → hide the row. */
    fun statusDisplay(): String

    /** Human-readable plan name. Chickadee: `""` → hide the row. */
    fun planDisplay(): String

    /**
     * Does the cached plan currently grant full access (active, trialing, or complimentary)?
     *
     * ⚠️ Also read by `MainActivity`'s kiosk-flag recovery, not only by paywalls.
     * Chickadee: `true` — it is never in a degraded-entitlement state.
     */
    val hasActiveAccess: Boolean

    /**
     * Is this device on the "HA display only" plan — i.e. showing Home Assistant rather than
     * the Dashie dashboard?
     *
     * ⚠️ **This is the one member where the generous answer would be WRONG.** It is a
     * kiosk-mode recovery heuristic, not a paywall, so the stub must answer honestly.
     *
     * Chickadee: `true` — it is *always* an HA-display device, by design.
     */
    fun isHaOnlyPlan(): Boolean
}
