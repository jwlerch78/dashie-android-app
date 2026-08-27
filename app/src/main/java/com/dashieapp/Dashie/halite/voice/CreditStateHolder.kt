package com.dashieapp.Dashie.halite.voice

import android.util.Log

/**
 * Cached voice-credit state (CR2/CR4 — 20260630_CREDIT_PREFLIGHT_DEGRADE_SPEC.md).
 *
 * The AUTHORITATIVE gate is server-side (the brain's pre-flight check + the STT/TTS
 * proxy gates). This cache exists for exactly one moment: when the wake word fires,
 * "do we open STT or show 'Voice unavailable'?" must be answered instantly — a blocking
 * balance read there would add latency to every turn and a new failure mode (CR-b,
 * decided 2026-07-04: cached). Freshness is a side effect of normal turns: every brain
 * response carries `metadata.credit {balance, spendable, low}`, pushed here from BOTH
 * brain paths (native BrainConverseClient; JS voice-command-router via the bridge).
 *
 * Staleness is safe by construction: stale-positive → the server rejects the turn and
 * the CR2 prompt shows (correct outcome, one wasted STT open); stale-negative clears on
 * the next turn/refill signal. Default state = spendable (fail-open, matching the
 * server's `voice_credit_enforce` default-OFF posture).
 */
object CreditStateHolder {
    private const val TAG = "CreditState"

    /** FB27: display a balance as "$X.XX", but anything below $0.02 as "$0" (a fraction of a
     *  cent reads as confusing — it's effectively empty). */
    fun formatBalance(b: Double): String = if (b < 0.02) "$0" else "$" + String.format("%.2f", b)

    /** UX threshold: at/below this the user counts as "low". Mirrors the server's
     *  voice_credit_low default; CreditBalanceReader.CREDIT_LOW reads from here so the two
     *  can't drift. Purely presentational — the server gate stays authoritative for spend. */
    const val LOW_USD = 1.0

    /** "Effectively empty" floor: at/below this the account cannot run a cloud turn.
     *  Lives here for the same reason as [LOW_USD] — it was independently hardcoded as
     *  `0.02` in CreditBalanceReader.CREDIT_FLOOR and again in CloudActivationState's
     *  hasCredits check, so a retune would have moved one and not the others. Mirrors the
     *  server's voice_credit_floor; the server gate stays authoritative for spend. */
    const val FLOOR_USD = 0.02

    /** Dashboard-pill threshold (narrower than [LOW_USD] — the pill is the loud surface, the
     *  in-conversation badge is the quiet one). Lives here, not in CreditBoundaryUi, because
     *  [update] needs it to re-arm a dismissal when the balance climbs back out. */
    const val PILL_USD = 0.50

    @Volatile var spendable: Boolean = true
        private set
    @Volatile var balance: Double? = null
        private set

    // The server's last reported low flag. Consulted ONLY before any balance is known — see [low].
    @Volatile private var lowReported: Boolean = false

    /** Low on credits — DERIVED from the cached balance so every surface (the pill, the
     *  "Credits low" badge on voice turns, the CR2 prompt) agrees, and one balance refresh
     *  clears them all at once.
     *
     *  This used to be an independently-cached boolean set alongside `balance`. That let the
     *  two disagree: after a top-up on another device, a stale low=true stuck around while
     *  `balance` was already fresh, so the "Credits low" badge kept showing on every voice
     *  turn. Deriving it makes that state unrepresentable. Falls back to the server's flag
     *  only while the balance is still unknown (app start, before the first read). */
    val low: Boolean
        get() = balance?.let { it <= LOW_USD } ?: lowReported

    // FB27: user chose "Don't show this again" on the CR2 prompt for THIS out-of-credits
    // episode — show the toast instead of the dialog. Auto-re-arms when credits go positive
    // again (see update()), so a fresh depletion re-shows the prompt.
    @Volatile var promptSuppressed: Boolean = false
        private set

    fun suppressPrompt() { promptSuppressed = true }

    // FB27: user dismissed the low-credit PILL for this episode. Separate from promptSuppressed
    // (that's the CR2 out-of-credits chooser). Re-arms when the CONDITION clears — see [update].
    @Volatile var pillDismissed: Boolean = false
        private set
    fun dismissPill() { pillDismissed = true }

    // WS-D.1 #1 (2026-07-21): the DEGRADED "using local voice" pill is dismissible too. Re-armed
    // each time degraded RE-ENTERS (CreditBoundaryUi.degraded false→true), so a fresh $0 episode shows it.
    @Volatile var degradedPillDismissed: Boolean = false
        private set
    fun dismissDegradedPill() { degradedPillDismissed = true }
    fun rearmDegradedPill() { degradedPillDismissed = false }

    // ── WS-L.3: auto-refill payment failure (the saved card was declined) ──────────
    // Read from get_credit_balance (CreditBalanceReader), so an idle wall tablet learns about
    // it without a voice turn. This outranks the low-balance pill — one pill, failure wins.
    @Volatile var autorefillFailed: Boolean = false
        private set
    @Volatile var autorefillError: String? = null
        private set
    /** Server timestamp of the current failure streak. Doubles as the pill's dismissal KEY. */
    @Volatile var autorefillErrorAt: String? = null
        private set

    // Which failure the user dismissed. Keyed on the streak timestamp rather than a boolean:
    // dismissing silences THIS failure, a NEW decline (new timestamp) re-shows automatically,
    // and a successful charge clears last_error server-side so the pill goes away on its own.
    // This is why the failure pill can't inherit the low-pill's re-arm bug.
    @Volatile private var dismissedErrorAt: String? = null
    fun dismissFailurePill() { dismissedErrorAt = autorefillErrorAt ?: "" }

    /** Show the failure pill? True while a streak is live and THIS streak wasn't dismissed. */
    val showAutorefillFailure: Boolean
        get() = autorefillFailed && (autorefillErrorAt ?: "") != dismissedErrorAt

    // Auto-refill on/off + the rule, cached from the same balance read. The SERVER row is the
    // only source of truth (there is deliberately no local pref to drift from), so the native
    // Credits toggle renders from here and writes through AutorefillSettingsClient.
    @Volatile var autorefillEnabled: Boolean = false
        private set
    @Volatile var autorefillThreshold: Double? = null
        private set
    @Volatile var autorefillTopup: Double? = null
        private set

    /** Optimistic local apply after a CONFIRMED server write, so the settings row reflects the
     *  new state before the next balance read lands. */
    fun setAutorefillEnabledLocal(value: Boolean) {
        if (autorefillEnabled == value) return
        autorefillEnabled = value
        if (!value) { autorefillFailed = false; dismissedErrorAt = null }  // off ⇒ no failure to show
        notifyChanged()
    }

    /** Update the auto-refill settings + failure state from a balance read. */
    fun updateAutorefill(
        failed: Boolean,
        error: String?,
        errorAt: String?,
        enabled: Boolean = autorefillEnabled,
        threshold: Double? = autorefillThreshold,
        topup: Double? = autorefillTopup,
    ) {
        val changed = failed != autorefillFailed || errorAt != autorefillErrorAt ||
            enabled != autorefillEnabled
        autorefillEnabled = enabled
        autorefillThreshold = threshold
        autorefillTopup = topup
        autorefillFailed = failed
        autorefillError = error
        autorefillErrorAt = errorAt
        // Streak resolved (a charge succeeded) → forget the dismissal so the NEXT failure shows.
        if (!failed) dismissedErrorAt = null
        if (changed) {
            Log.i(TAG, "autorefill state: failed=$failed at=$errorAt err=${error?.take(60)}")
            notifyChanged()
        }
    }

    // Fired on any credit-state change (balance/low/spendable/autorefill) so observers refresh.
    // A LIST, not a single slot: it used to be one nullable lambda, so a second observer
    // silently replaced the first (the low-credit pill would have stopped refreshing the
    // moment the failure pill registered). Registered controller-lifetime, recreation-safe —
    // [addOnChanged] de-dupes by key so a WebView/controller recreation re-registering the
    // same observer doesn't stack duplicates.
    private val changeListeners = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    fun addOnChanged(key: String, listener: () -> Unit) { changeListeners[key] = listener }
    fun removeOnChanged(key: String) { changeListeners.remove(key) }

    private fun notifyChanged() {
        for (l in changeListeners.values) {
            try { l() } catch (t: Throwable) { Log.w(TAG, "credit onChanged listener threw", t) }
        }
    }

    // FB27: is the effective voice pipeline billable against credits (cloud STT/AI/TTS)? Set by
    // HaliteVoiceController from VoicePreferences.isBillableVoice. Credit UI (badge/pill/card)
    // is suppressed when false — a local/HA/voice-assistant pipeline never bills credits.
    @Volatile var billable: Boolean = true
        private set
    fun setBillable(value: Boolean) {
        if (billable == value) return
        billable = value
        notifyChanged()
    }

    // WS-D.1: voice is currently running on FREE engines because the account is out of credits.
    // Runtime-only (no pref is written), so it simply clears when credits return. Read by the
    // control-center card so it doesn't keep claiming a hard "No credits" error while voice is
    // in fact still working locally.
    @Volatile var degraded: Boolean = false
        private set
    fun setDegraded(value: Boolean) {
        if (degraded == value) return
        degraded = value
        notifyChanged()
    }

    // Fired when a server signal declines a turn (brain degraded / TTS 402 / STT reject) —
    // registered ONCE by HaliteVoiceController (activity-lifetime, so it survives WebView
    // recreation, unlike per-jsBridge-instance callbacks). Drives the CR2 prompt.
    @Volatile var onExhausted: ((balance: Double?) -> Unit)? = null

    /** Update from a brain turn's `metadata.credit` (either brain path). */
    fun update(spendable: Boolean?, low: Boolean?, balance: Double?) {
        if (spendable == null && low == null && balance == null) return
        val was = this.spendable
        spendable?.let {
            if (it && !this.spendable) { promptSuppressed = false }  // refill from $0 → re-arm the CR2 prompt
            this.spendable = it
        }
        low?.let { this.lowReported = it }   // fallback only; [low] derives from balance
        balance?.let { this.balance = it }
        // Re-arm the dismissed PILL when its own condition clears — i.e. the balance climbed back
        // above the pill threshold. This used to hang off the `spendable` false→true edge, which
        // only fires on a refill from ~$0: a user at $0.30 who dismissed, topped up to $5, then
        // drained back to $0.40 never saw the pill again for the life of the process (`spendable`
        // was true throughout, so the edge never fired). Keying on the condition — the same shape
        // CalendarReauthOverlayManager uses when its count hits 0 — makes that unrepresentable.
        if (pillDismissed && (this.balance ?: 0.0) >= PILL_USD) {
            pillDismissed = false
            Log.i(TAG, "low-credit pill re-armed (balance ${this.balance} ≥ $PILL_USD)")
        }
        if (was != this.spendable || (low == true)) {
            Log.i(TAG, "credit state: spendable=${this.spendable} low=${this.low} balance=${this.balance}")
        }
        notifyChanged()
    }

    /** A server gate said no (brain degraded turn / TTS 402 / STT reject). */
    fun markExhausted(balance: Double? = null) {
        update(spendable = false, low = true, balance = balance)
        onExhausted?.invoke(balance ?: this.balance)
    }

    /**
     * Wipe the cached credit state on sign-out / device de-authorization. The
     * balance is a per-ACCOUNT value — leaving it cached after the account is
     * signed out (or the household kiosk is revoked) makes the tablet keep showing
     * the ex-account's credits (field report 2026-07-22: "disconnected sharing but
     * it kept the credits going"). Resets to the fresh-process defaults (balance
     * unknown, fail-open spendable) so no credit UI renders until a new account's
     * balance loads. Change listeners keep their registrations (controller-lifetime).
     */
    fun reset() {
        balance = null
        spendable = true
        lowReported = false
        promptSuppressed = false
        pillDismissed = false
        degradedPillDismissed = false
        autorefillFailed = false
        autorefillError = null
        autorefillErrorAt = null
        dismissedErrorAt = null
        autorefillEnabled = false
        autorefillThreshold = null
        autorefillTopup = null
        degraded = false
        Log.i(TAG, "credit state reset (sign-out / de-authorized)")
        notifyChanged()
    }
}
