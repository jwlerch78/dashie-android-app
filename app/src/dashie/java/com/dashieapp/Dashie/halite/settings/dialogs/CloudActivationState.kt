package com.dashieapp.Dashie.halite.settings.dialogs

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.preferences.FeatureVisibilityPreferences
import com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences
import com.dashieapp.Dashie.halite.voice.CreditStateHolder

/**
 * The SINGLE source of truth for "can this device use Dashie cloud voice/AI".
 *
 * Both the Voice & AI preset gate (VoiceAiSettings.cloudAvailable — whether the
 * Cloud/Hybrid rows are selectable) and the blocked-tap handler
 * (SettingsCallbackWiring.onCloudPresetBlocked — what popup, if any, to show) MUST
 * agree, or you get a contradiction: the gate grays Cloud (needs credits) while the
 * tap handler thinks the account is fully set up and shows the useless
 * "set up console / Done" popup — Cloud looks blocked but there's nothing to do.
 * (Field report, John 2026-07-22: "if we're not prompting for an account or credit
 * add, cloud can be activated.")
 *
 * Cloud is USABLE when the account can actually PAY for it AND authenticate:
 *   - a beta subscription (isLoggedIn && beta), OR
 *   - household sharing (the add-on holds the account), OR
 *   - a VALID account credential (Supabase JWT, not expired) WITH credits or a BYO
 *     AI key — the HA-voice pay-per-use model.
 *
 * When NOT usable, [evaluate] says WHAT's missing so the tap handler prompts for
 * exactly that (account vs credits) — never the "you're all set" popup.
 */
object CloudActivationState {

    private const val TAG = "CloudActivation"

    enum class State { USABLE, NEEDS_ACCOUNT, NEEDS_CREDITS }

    fun evaluate(context: Context, prefs: HalitePreferences): State {
        val conn = prefs.connection
        val hasValidAccount = conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired

        val subPrefs = SubscriptionPreferences(context)
        val isLoggedIn = subPrefs.subscriptionStatus in listOf(
            SubscriptionPreferences.STATUS_TRIALING,
            SubscriptionPreferences.STATUS_ACTIVE,
            SubscriptionPreferences.STATUS_COMPLIMENTARY
        )
        val isBeta = FeatureVisibilityPreferences(context).hasBetaAccess()
        val entitled = (isLoggedIn && isBeta) || prefs.voice.cloudShareAvailable

        val hasCredits = CreditStateHolder.balance?.let { it >= CreditStateHolder.FLOOR_USD } == true
        val hasByoKey = prefs.voice.localLlmKeySet

        val state = when {
            entitled || (hasValidAccount && (hasCredits || hasByoKey)) -> State.USABLE
            !hasValidAccount -> State.NEEDS_ACCOUNT
            else -> State.NEEDS_CREDITS
        }
        Log.i(TAG, "evaluate=$state (validAccount=$hasValidAccount loggedIn=$isLoggedIn beta=$isBeta " +
            "share=${prefs.voice.cloudShareAvailable} credits=$hasCredits(bal=${CreditStateHolder.balance}) byoKey=$hasByoKey)")
        return state
    }

    fun isUsable(context: Context, prefs: HalitePreferences): Boolean =
        evaluate(context, prefs) == State.USABLE
}
