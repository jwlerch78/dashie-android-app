package com.dashieapp.Dashie.edition

/**
 * The metered-credit NETWORK surface: balance reads, the starter grant, auto-replenish.
 *
 * ## What is deliberately NOT behind this seam
 *
 * [com.dashieapp.Dashie.halite.voice.CreditStateHolder] stays in `main/` and is not wrapped.
 * Three reasons (2c proposal §2):
 *
 *  1. Its defaults are **fail-open** — `spendable = true`, `balance = null`. A build that
 *     never calls `update()` behaves exactly as it should.
 *  2. It is **inert by configuration** in Chickadee: every surface it drives is armed by
 *     `setBillable(voice.isBillableVoice)`, and that is false unless a *Dashie-cloud* engine
 *     is selected. Chickadee never selects one.
 *  3. It is pure in-memory state with 15 referrers, most of which stay.
 *
 * Wrapping it would be a large refactor for zero behavioural difference. What genuinely has
 * to disappear from the published tree is the code that TALKS TO THE BILLING SERVER — which
 * is exactly, and only, what this interface covers.
 *
 * Note there is no `jwt` parameter anywhere below. The Dashie implementation resolves it
 * from `HalitePreferences(context).connection.supabaseJwt` itself. That is what removes the
 * need for a separate `AccountIdentity` seam.
 */
interface CreditService {

    /**
     * Does this edition have a metered cloud service AT ALL?
     *
     * A capability question like the retired `VoiceEntitlement.hasLicensing`, used the same way: to
     * decide whether a Dashie-Cloud engine *option* should exist, not to branch on brand.
     *
     * Distinct from "are there credits right now" — the existing `cloudAvailable` flag already
     * answers that, and answering it `false` leaves the Cloud/Hybrid rows visible-but-disabled
     * with a tappable "add credits" explainer. That is the correct behaviour for a Dashie user
     * who is out of credits, and exactly the wrong one for Chickadee, where the rows describe a
     * service that does not exist. Absence, not suppression.
     *
     * Chickadee: `false`.
     */
    val hasMeteredService: Boolean

    /**
     * Is the metered cloud pipeline usable RIGHT NOW — account valid, and entitled or funded?
     *
     * The runtime counterpart to [hasMeteredService]: that asks whether the service exists in
     * this edition at all, this asks whether it can be used at this moment. Chickadee answers
     * `false` to both, but for different reasons, and collapsing them would lose the
     * distinction that 2f-2 depends on.
     */
    fun isCloudUsable(): Boolean

    /**
     * Refresh the cached balance into `CreditStateHolder`. Throttled inside the reader, so
     * calling it on every voice turn is cheap. Asynchronous; never throws.
     *
     * Chickadee: no-op + `DROP:`.
     *
     * @param force bypass the throttle (use after a top-up, not on a hot path).
     */
    fun refreshBalance(force: Boolean = false)

    /**
     * Claim — or discover we already claimed — the one-time starter credit grant.
     *
     * [onResult] receives `null` for **"don't know"** (network error, unparseable body, or
     * no account). Null is emphatically NOT "no grant": callers must fall back to their
     * existing behaviour rather than telling the user anything about credits they cannot
     * substantiate.
     *
     * Chickadee: `onResult(null)` + `DROP:` — "don't know" is the branch every caller
     * already handles safely. Reporting a zero grant would assert something stronger and
     * falser.
     *
     * Callback may fire on a background thread; marshal before touching views.
     */
    fun claimStarterGrant(onResult: (StarterGrantOutcome?) -> Unit)

    /**
     * Toggle credit auto-replenish for the account.
     *
     * Enabling can legitimately fail server-side (e.g. no saved card); the server's error
     * text is passed through verbatim so the UI can show the real reason.
     *
     * Chickadee: `onResult(false, …)` + `DROP:`.
     */
    fun setAutorefillEnabled(enabled: Boolean, onResult: (ok: Boolean, error: String?) -> Unit)
}

/**
 * The outcome of a starter-grant claim.
 *
 * ⚠️ **Three outcomes, not two.** The original design of this seam returned a nullable
 * amount, which silently lost the middle case — and the middle case is the one that matters
 * most to the user, because it is the "you are actually fine" branch.
 *
 *  - `granted` — a grant was just applied; disclose [amountUsd] to the user.
 *  - `!granted && usable` — already claimed, but the balance still funds a cloud turn. The
 *    gate was reading a stale cache. Let the user through and disclose **nothing**; showing
 *    an add-credits prompt here is the bug.
 *  - neither — genuinely out of credits; the add-credits path is correct.
 *
 * A `null` outcome is a fourth, separate state: "don't know". See [CreditService.claimStarterGrant].
 */
data class StarterGrantOutcome(
    val granted: Boolean,
    val amountUsd: Double,
    val usable: Boolean,
)
