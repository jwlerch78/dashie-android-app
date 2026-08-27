package com.dashieapp.Dashie.edition

/**
 * Phone-home telemetry.
 *
 * Chickadee's audience is privacy-motivated, self-hosting HA users, and "works offline
 * forever" is load-bearing in the positioning. The published edition has no account and
 * reports to nothing, so this is a no-op there — not a suppressed heartbeat, an absent one.
 *
 * ## Note on what this replaced
 *
 * 2a listed this seam as `EntitlementSync`, on the strength of `MonthlyCheckin`'s doc
 * comment ("Also receives account entitlements... for linked devices"). Reading the code,
 * **that half is dead**: `MonthlyCheckin` reads and writes SharedPreferences file
 * `dashie_subscription`, while `SubscriptionPreferences` uses `dashie_subscription_prefs`.
 * Nothing else touches the former, and nothing reads the `household_voice_entitled` key it
 * writes. So `subscriptionStatus` in the check-in payload has always been null, and the
 * cached entitlement has never been read back.
 *
 * The only live duty is telemetry — install token, app version, network fingerprint,
 * install source — hence the name.
 *
 * ✅ **The orphan-prefs bug this paragraph logged for a Dashie-side thread was FIXED 2026-08-04**
 * (Thread A, assigned by John). Leaving the description above as written, because the diagnosis
 * was correct and is what made the fix findable — but do not go hunting: `getSubscriptionStatus`
 * now reads through `SubscriptionPreferences`, and the write-only entitlement cache is gone.
 *
 * 🔴 The fix also found a **third** orphan the note above missed: `isUserLoggedIn` read a
 * `supabase_auth` file that no writer has ever created, so the payload reported **every device as
 * logged out** — confidently wrong rather than merely absent. Worth keeping as the general
 * lesson: when one pref-file name turns out to be orphaned, check its NEIGHBOURS in the same
 * payload, because the cause is a class of mistake, not a typo.
 */
interface EditionTelemetry {

    /**
     * Send the 30-day active-device heartbeat if one is due. Safe to call on every launch —
     * returns immediately when not due. Never throws.
     *
     * Chickadee: no-op + `DROP:`.
     */
    fun checkinIfDue()
}
