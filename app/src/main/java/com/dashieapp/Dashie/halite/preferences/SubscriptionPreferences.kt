package com.dashieapp.Dashie.halite.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Subscription and billing state preferences.
 *
 * Caches the user's subscription status from the server (user_profiles table).
 * Populated by the WebView JS layer via JS bridge on app load, and updated
 * via Supabase real-time listener when status changes.
 *
 * Values:
 * - subscription_status: trialing, trial_expired, active, past_due, canceled, complimentary
 * - tier: basic, core, plus
 * - tier_expires_at: epoch millis (0 = no expiry)
 * - trial_reason: standard, voice_license_purchase, voice_license_retroactive, admin_grant
 * - subscription_plan: dashie_monthly, dashie_annual, null
 * - stripe_customer_id: Stripe customer ID for checkout flows
 */
class SubscriptionPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "dashie_subscription_prefs"

        private const val KEY_STATUS = "subscription_status"
        private const val KEY_TIER = "tier"
        private const val KEY_TIER_EXPIRES_AT = "tier_expires_at"
        private const val KEY_TRIAL_REASON = "trial_reason"
        private const val KEY_PLAN = "subscription_plan"
        private const val KEY_STRIPE_CUSTOMER_ID = "stripe_customer_id"
        // D.76 — user tapped "I'm not interested in a trial right now" on the
        // CC trial-offer dialog. Suppresses the CC trial-offer promo tile
        // until the next sign-in (reset in JsBridgeSubscriptionDelegate
        // when a signed-in subscription state syncs).
        private const val KEY_TRIAL_OFFER_DISMISSED = "trial_offer_dismissed"

        const val STATUS_TRIALING = "trialing"
        const val STATUS_TRIAL_EXPIRED = "trial_expired"
        const val STATUS_ACTIVE = "active"
        const val STATUS_PAST_DUE = "past_due"
        const val STATUS_CANCELED = "canceled"
        const val STATUS_COMPLIMENTARY = "complimentary"
        // User on a trial-expired account picked "Continue with HA Only".
        // Server-side persistent state — feature data has been purged,
        // user keeps HA dashboard access. Trial-expired overlay never
        // re-fires for this status. Subscribe path back to 'active'
        // restores full Dashie dashboard.
        const val STATUS_HA_ONLY = "ha_only"

        const val TIER_BASIC = "basic"
        const val TIER_CORE = "core"
        const val TIER_PLUS = "plus"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Status fields ───────────────────────────────────────────────

    var subscriptionStatus: String
        get() = prefs.getString(KEY_STATUS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_STATUS, value).commit() }

    var tier: String
        get() = prefs.getString(KEY_TIER, TIER_BASIC) ?: TIER_BASIC
        set(value) { prefs.edit().putString(KEY_TIER, value).commit() }

    /** Epoch millis when tier expires, 0 = no expiry */
    var tierExpiresAt: Long
        get() = prefs.getLong(KEY_TIER_EXPIRES_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_TIER_EXPIRES_AT, value).commit() }

    var trialReason: String
        get() = prefs.getString(KEY_TRIAL_REASON, "") ?: ""
        set(value) { prefs.edit().putString(KEY_TRIAL_REASON, value).commit() }

    var subscriptionPlan: String
        get() = prefs.getString(KEY_PLAN, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PLAN, value).commit() }

    var stripeCustomerId: String
        get() = prefs.getString(KEY_STRIPE_CUSTOMER_ID, "") ?: ""
        set(value) { prefs.edit().putString(KEY_STRIPE_CUSTOMER_ID, value).commit() }

    /** D.76 — see KEY_TRIAL_OFFER_DISMISSED comment. */
    var trialOfferDismissed: Boolean
        get() = prefs.getBoolean(KEY_TRIAL_OFFER_DISMISSED, false)
        set(value) { prefs.edit().putBoolean(KEY_TRIAL_OFFER_DISMISSED, value).commit() }

    // ── Computed helpers ────────────────────────────────────────────

    /** Days remaining in trial, or -1 if not trialing / no expiry set */
    val daysRemaining: Int
        get() {
            if (subscriptionStatus != STATUS_TRIALING || tierExpiresAt == 0L) return -1
            val remaining = tierExpiresAt - System.currentTimeMillis()
            if (remaining <= 0) return 0
            // Ceil-divide so a tier_expires_at "3 days from now minus a few
            // seconds elapsed" reads as 3 days left, not 2. Floor division
            // (the previous behavior) flipped the chip to N-1 the moment
            // it was set, which felt off-by-one to users.
            val msPerDay = 24L * 60 * 60 * 1000
            return ((remaining + msPerDay - 1) / msPerDay).toInt()
        }

    /** Whether the user has active access (trial, subscription, or complimentary) */
    val hasActiveAccess: Boolean
        get() = subscriptionStatus in listOf(STATUS_TRIALING, STATUS_ACTIVE, STATUS_COMPLIMENTARY)

    // ── Bulk import from JS bridge ──────────────────────────────────

    /**
     * Import subscription state from a JS-originated JSON string.
     * Called by the JS bridge when the WebView syncs account data.
     */
    /** Clear all subscription state (called on sign-out) */
    fun clear() {
        prefs.edit().clear().commit()
    }

    fun importFromJson(json: String) {
        val obj = org.json.JSONObject(json)
        prefs.edit()
            .putString(KEY_STATUS, obj.optString("subscription_status", ""))
            .putString(KEY_TIER, obj.optString("tier", TIER_BASIC))
            .putLong(KEY_TIER_EXPIRES_AT, obj.optLong("tier_expires_at", 0L))
            .putString(KEY_TRIAL_REASON, obj.optString("trial_reason", ""))
            .putString(KEY_PLAN, obj.optString("subscription_plan", ""))
            .putString(KEY_STRIPE_CUSTOMER_ID, obj.optString("stripe_customer_id", ""))
            .commit()
    }
}
