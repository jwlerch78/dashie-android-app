package com.dashieapp.Dashie.controlcenter

import android.content.Context
import com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences

/**
 * Provides subscription state and feature access checks for the native UI.
 *
 * Used by ControlCenterStateProvider to determine whether gated features
 * (Calendar, Locations, Chores & Rewards) should show subscription-aware
 * subtext and upsell prompts.
 *
 * Reads from SubscriptionPreferences (SharedPreferences cache populated
 * by the WebView JS layer on app load).
 */
class SubscriptionStateProvider(context: Context) {

    private val subPrefs = SubscriptionPreferences(context)

    companion object {
        /** Features that require a subscription (core tier) */
        private val GATED_FEATURES = setOf("calendar", "locations", "chores")

        /** Features always available regardless of subscription */
        private val UNGATED_FEATURES = setOf(
            "photos", "ha", "video_feeds", "camera", "music", "power"
        )

        /** Trial warning threshold — show "X days left" when fewer than this many days remain */
        const val TRIAL_WARNING_DAYS = 5
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Whether the user has access to a specific feature.
     *
     * Ungated features always return true.
     * Gated features require active access (trialing, active, or complimentary).
     * Voice is special — accessible to voice license holders at basic tier.
     */
    fun hasFeatureAccess(featureId: String): Boolean {
        if (featureId in UNGATED_FEATURES) return true
        if (featureId == "ai_voice") return true // Voice license handled separately by VoiceLicenseManager
        if (featureId !in GATED_FEATURES) return true // Unknown features default to accessible
        return subPrefs.hasActiveAccess
    }

    /**
     * Get subscription-aware subtext for a feature card in the control center.
     *
     * Returns null for ungated features or when the user has full access.
     * Returns a descriptive string for gated features based on subscription state.
     */
    fun getFeatureSubtext(featureId: String): String? {
        if (featureId !in GATED_FEATURES) return null
        if (subPrefs.hasActiveAccess) {
            // Show trial warning if nearing expiry
            val days = subPrefs.daysRemaining
            if (subPrefs.subscriptionStatus == SubscriptionPreferences.STATUS_TRIALING
                && days in 0..TRIAL_WARNING_DAYS) {
                val label = if (subPrefs.trialReason == "voice_license_purchase")
                    "Voice bonus" else "Trial"
                return "$label: $days day${if (days != 1) "s" else ""} left"
            }
            return null // Full access, no special subtext
        }
        // No active access
        return "Requires subscription"
    }

    /**
     * Whether the subtext indicates a warning state (trial expiring soon).
     * Used for styling the subtext in orange vs gray.
     */
    fun isTrialWarning(featureId: String): Boolean {
        if (featureId !in GATED_FEATURES) return false
        if (!subPrefs.hasActiveAccess) return false
        val days = subPrefs.daysRemaining
        return subPrefs.subscriptionStatus == SubscriptionPreferences.STATUS_TRIALING
                && days in 0..TRIAL_WARNING_DAYS
    }

    // ── Status display helpers ──────────────────────────────────────

    /** Human-readable subscription status for the Account page */
    fun getStatusDisplay(): String {
        return when (subPrefs.subscriptionStatus) {
            SubscriptionPreferences.STATUS_TRIALING -> {
                val days = subPrefs.daysRemaining
                if (days >= 0) "Trial — $days day${if (days != 1) "s" else ""} remaining"
                else "Trial"
            }
            SubscriptionPreferences.STATUS_TRIAL_EXPIRED -> "Trial Ended — Subscribe to continue"
            SubscriptionPreferences.STATUS_ACTIVE -> {
                val plan = when (subPrefs.subscriptionPlan) {
                    "dashie_monthly" -> "$2.99/month"
                    "dashie_annual" -> "$29.99/year"
                    else -> ""
                }
                if (plan.isNotEmpty()) "Active — $plan" else "Active"
            }
            SubscriptionPreferences.STATUS_PAST_DUE -> "Payment issue — update payment method"
            SubscriptionPreferences.STATUS_CANCELED -> "Canceled"
            SubscriptionPreferences.STATUS_COMPLIMENTARY -> "Complimentary Access"
            else -> "Not subscribed"
        }
    }

    /** Human-readable plan name */
    fun getPlanDisplay(): String {
        return when (subPrefs.subscriptionPlan) {
            "dashie_monthly" -> "Dashie Monthly"
            "dashie_annual" -> "Dashie Annual"
            else -> "None"
        }
    }
}
