package com.dashieapp.Dashie.edition

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.pages.calendar.CloudCalendarFlows
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * CHICKADEE edition — no account, no credits, no metered service.
 *
 * Twin of `src/dashie/java/.../EditionSeams.kt`: same fully-qualified name, same surface,
 * one chosen per flavor at compile time. Chickadee ships this file and NOT the commercial
 * implementations — the code is absent from the published tree, not merely unreachable.
 *
 * ## Every stub logs a `DROP:` — and most of them should never fire
 *
 * CLAUDE.md standing rule 2: every dispatch fallthrough is loud. A silent no-op here would
 * be indistinguishable from the surface simply not being reached, which is exactly the
 * failure mode this whole phase exists to prevent.
 *
 * The markers are graded, and that grading is the point:
 *
 *  - `DROP: [expected]` — fires in normal operation. Only [EditionTelemetry.checkinIfDue].
 *  - `DROP: [unexpected]` — reaching it means a CALL SITE asked a commercial question on a
 *    path Chickadee actually runs, i.e. **the seam was cut in the wrong place**. These are
 *    the ones to grep for after a device run; see the 2c verification matrix.
 *
 * So `adb logcat | grep DROP:` on a Chickadee build should show the telemetry line and
 * nothing else on a healthy voice turn.
 */
/** Shared by every stub below, so a single `grep DROP:` catches all of them. */
private const val TAG = "EditionSeams"

object EditionSeams {

    @Suppress("UNUSED_PARAMETER")
    fun voice(context: Context, prefs: HalitePreferences): VoiceEntitlement =
        ChickadeeVoiceEntitlement

    @Suppress("UNUSED_PARAMETER")
    fun credits(context: Context): CreditService = ChickadeeCreditService

    @Suppress("UNUSED_PARAMETER")
    fun subscription(context: Context): SubscriptionState = ChickadeeSubscriptionState

    @Suppress("UNUSED_PARAMETER")
    fun telemetry(context: Context): EditionTelemetry = ChickadeeEditionTelemetry

    @Suppress("UNUSED_PARAMETER")
    fun paywall(activity: Activity): PaywallUi = ChickadeePaywallUi

    /**
     * Twin of the Dashie declaration — see it for the contract.
     *
     * `false`: Chickadee is account-free, so Google / Outlook / Apple calendar accounts do not
     * exist as an option (John's call, 2026-08-02). The provider rows are ABSENT from the
     * schema rather than present-and-failing — the same shape as the first-run screen that no
     * longer offers "Create a Dashie Account", and as the retired `hasLicensing` was.
     *
     * 🔴 No `DROP:` here on purpose. Unlike the stubs below, this is not a dispatch
     * fallthrough — it is a question with a real answer, asked on a path Chickadee genuinely
     * runs (every time the calendar page builds). A marker would fire constantly and train the
     * reader to ignore the ones that matter, the same reasoning as the CC summary note.
     *
     * Home Assistant calendars are unaffected and remain offered: they are the household's own
     * entities, and they are Chickadee's calendar source.
     */
    val hasCloudCalendarAccounts: Boolean get() = false

    /**
     * `false` — Chickadee has no Dashie account, and therefore no Supabase project, no session
     * and no telemetry endpoint. Twin of the Dashie declaration; see it for the contract.
     *
     * 🔴 No `DROP:` here, same reasoning as [hasCloudCalendarAccounts]: this is a question with a
     * real answer asked on paths this edition genuinely runs, not a dispatch fallthrough. The
     * markers belong at the CALL SITES that would otherwise have dispensed something — see
     * `JsBridgeDeviceDelegate.getSupabaseConfig`, which logs one.
     */
    val hasAccounts: Boolean get() = false

    /**
     * `null` — the cloud calendar-account flows are not compiled into this edition at all.
     *
     * Not a stub object returning no-ops: `null` is what makes the IMPLEMENTATIONS absent from
     * the artifact, which is the whole point. A no-op stub would satisfy the type and leave the
     * Apple credential dialog and `dashieapp.com` in the Chickadee APK, i.e. exactly the
     * unreachable-but-present state this move exists to end.
     */
    val cloudCalendarFlows: CloudCalendarFlows? get() = null

    /**
     * Empty — this edition has no paid plan, so the Account page has no billing sections.
     *
     * Not "the sections, hidden": the rows named the plan ("Dashie Plan", "Subscribe to Dashie")
     * and their gate (`account.isLinked`) is permanently false here, so they were already
     * unreachable — but they still compiled into this artifact. `emptyList()` is what makes them
     * ABSENT, which is the point, and it is the same choice as [cloudCalendarFlows] returning
     * null rather than a no-op stub.
     *
     * 🔴 No `DROP:` marker: a question with a real answer on a path this edition genuinely runs
     * (every Account page build), not a dispatch fallthrough — same reasoning as [hasAccounts].
     */
    val accountBillingSections: List<SettingsSection> get() = emptyList()

    /**
     * Empty string — the hint names a Dashie add-on, and there is no Dashie account to
     * disconnect from in this edition.
     *
     * 🔴 No `DROP:` marker, and this one is worth stating because it is the closest call on this
     * object: the value IS consumed (`account.disconnectHint` is a registered value key). But
     * its only reader is the kiosk account section, which is gated on `account.isLinked` —
     * permanently false here — so the empty string is never rendered. If that gate ever changes,
     * the failure is a visibly blank row rather than a wrong brand name, which is the safer of
     * the two and is loud in the place a user would report.
     */
    fun accountDisconnectHint(context: Context): String = ""
}

// ── No-op implementations ──────────────────────────────────────────────────

/**
 * Twin of `src/dashie`'s implementation — and since 2026-08-02 the two are IDENTICAL, because
 * voice became free on Dashie too. Kept as separate files rather than hoisted to `main/`: the
 * surface must stay mirrored per the note above, and this is the file that will diverge again
 * the moment either edition gains something that can answer "no".
 *
 * Note the answer is NOT reported as "ACTIVE"/"licensed": the interface has no such vocabulary,
 * precisely so a reader of this repo is not told the device holds a paid licence it does not
 * have. That was the founding argument for the thin interface and it still holds.
 */
private object ChickadeeVoiceEntitlement : VoiceEntitlement {
    override fun isVoiceAllowed(): Boolean = true
}

private object ChickadeeCreditService : CreditService {

    /** One [refreshBalance] drop line per this interval — see its KDoc. */
    private const val BALANCE_DROP_LOG_INTERVAL_MS = 5 * 60_000L
    private var lastBalanceDropLogMs = -BALANCE_DROP_LOG_INTERVAL_MS

    /** No metered service — Dashie Cloud engine rows are omitted, not disabled. */
    override val hasMeteredService: Boolean get() = false

    /** No metered cloud exists, so it is never usable. Silent: this is asked on every Voice &
     *  AI schema rebuild, and a marker would be noise rather than signal. */
    override fun isCloudUsable(): Boolean = false

    /**
     * 🔑 `[expected]`, and RATE-LIMITED — re-graded from `[unexpected]` by O's ruling
     * (2026-08-02) on the criterion **"does the caller BRANCH on the answer?"**
     *
     * It does not. This is idempotent badge bookkeeping: `handleWakeWordAccepted()` freshens the
     * credit cache so a "Credits low" badge is not stale, and nothing reads a result. A no-op
     * here IS the design working, so the call site is right to stay edition-blind — guarding it
     * would put edition-awareness at exactly the sites the seam exists to keep clean. Contrast
     * the retired `VoiceEntitlement.refresh()`, whose answer decided whether voice enabled:
     * that one was genuinely `[unexpected]`, and it is gone with the licence.
     *
     * Rate-limited because it fires on EVERY wake word. An `[expected]` marker at that rate is
     * log spam that teaches the reader to skim the whole `DROP:` channel — which would cost more
     * than the line is worth. Same 5-minute shape as the brain-route absence report
     * (`1649dc57`); the first one after a quiet period still tells you the path is live.
     */
    override fun refreshBalance(force: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBalanceDropLogMs < BALANCE_DROP_LOG_INTERVAL_MS) return
        lastBalanceDropLogMs = now
        Log.i(TAG, "DROP: [expected] CreditService.refreshBalance(force=$force) — " +
            "Chickadee has no metered service and no balance to read. Rate-limited to one " +
            "line per ${BALANCE_DROP_LOG_INTERVAL_MS / 60_000} min; fires once per wake word.")
    }

    override fun claimStarterGrant(onResult: (StarterGrantOutcome?) -> Unit) {
        Log.i(TAG, "DROP: [unexpected] CreditService.claimStarterGrant() — no account, " +
            "no grant. Reporting 'unknown' so the caller takes its existing null branch.")
        // null means "don't know", which every caller already handles safely. Reporting
        // 0.0 would assert "no grant available", which is a different and wronger claim.
        onResult(null)
    }

    override fun setAutorefillEnabled(enabled: Boolean, onResult: (Boolean, String?) -> Unit) {
        Log.i(TAG, "DROP: [unexpected] CreditService.setAutorefillEnabled($enabled) — " +
            "no metered service to replenish.")
        onResult(false, "Not available in this edition.")
    }
}

private object ChickadeeSubscriptionState : SubscriptionState {

    /** Nothing Chickadee ships is subscription-gated. */
    override fun hasFeatureAccess(featureId: String): Boolean = true

    override fun featureSubtext(featureId: String): String? = null

    override fun isTrialWarning(featureId: String): Boolean = false

    override fun statusDisplay(): String = ""

    override fun planDisplay(): String = ""

    override val hasActiveAccess: Boolean get() = true

    /**
     * TRUE, and this is the one stub answer that is not simply "the generous one".
     *
     * `isHaOnlyPlan()` feeds MainActivity's kiosk-mode recovery, not a paywall. Chickadee IS
     * always an HA-display device, so true is the honest answer — and answering false to
     * "be permissive" would make the recovery heuristic misread the device.
     */
    override fun isHaOnlyPlan(): Boolean = true
}

private object ChickadeeEditionTelemetry : EditionTelemetry {
    override fun checkinIfDue() {
        // EXPECTED on every launch — the one DROP: a healthy Chickadee device logs.
        Log.i(TAG, "DROP: [expected] EditionTelemetry.checkinIfDue() — the published " +
            "edition reports to nothing.")
    }
}

private object ChickadeePaywallUi : PaywallUi {

    /**
     * No-op, and NOT logged as a drop. Chickadee has no trial, so "attach the trial surfaces"
     * is a question that is correct to ask and correct to answer with nothing — MainActivity
     * calls it unconditionally during setup on every edition. A DROP: here would fire on every
     * launch and train the eye to ignore the marker that matters.
     */
    override fun attachTrialSurfaces(
        halitePrefsProvider: () -> com.dashieapp.Dashie.halite.HalitePreferences?,
        visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate?,
    ) = Unit

    /** Same reasoning as [attachTrialSurfaces] — called on every subscription sync. */
    override fun refreshTrialSurfaces() = Unit

    


    override fun showTrialSignup(onHide: () -> Unit) {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showTrialSignup() — no accounts, no trial. " +
            "Reaching this means a commercial Control Center action survived option-level gating.")
    }

    override fun showConnectMobile(titleOverride: String?, subtitleOverride: String?) {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showConnectMobile() — no accounts and no hub, " +
            "so there is nothing to connect a phone to.")
    }

    override fun showManageAccount() {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showManageAccount() — no account to manage.")
    }

    override fun showSubscribeFlow(
        scope: kotlinx.coroutines.CoroutineScope,
        onTriggerSubscriptionSync: (() -> Unit)?,
        onRefresh: () -> Unit,
    ) {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showSubscribeFlow() — this edition sells " +
            "nothing. Reaching this means a commercial entry point survived gating.")
    }

    
    /** Accepted and discarded — same reasoning as [attachVoiceUiCallbacks]. */
    override fun attachCreditBoundary(callbacks: CreditBoundaryCallbacks) = Unit

    override fun showCreditPrompt(balance: Double?) {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showCreditPrompt(balance=$balance) — no " +
            "metered service, so there is no out-of-credits state to prompt about.")
    }

    /** Silent no-op. Called on EVERY credit-state change and at voice init, so a marker here
     *  would be noise; CreditStateHolder is inert in this edition by construction. */
    override fun updateLowCreditPill() = Unit

    /** Silent no-op. Free engines are this edition's NORMAL mode, so "degraded" is not a
     *  state it can meaningfully display. */
    override fun setCreditDegraded(degraded: Boolean) = Unit

    override fun showCloudActivation(reason: CloudActivationReason) {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showCloudActivation($reason) — Chickadee has " +
            "no cloud tier to activate and no account to activate it against.")
    }

    override fun handleCloudPresetBlocked(tappedPreset: String, applyPreset: (String) -> Unit) {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.handleCloudPresetBlocked('$tappedPreset') — " +
            "this edition omits the Cloud/Hybrid preset rows entirely, so nothing should be " +
            "able to tap a blocked one.")
    }

    override fun showAddCredits(source: String) {
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showAddCredits(source=$source) — no metered " +
            "service, nothing to buy.")
    }

    override fun showConsoleSetup() {
        // [unexpected]: the row that reaches this (`byok_or_local`) lives inside the credits
        // section, which never renders in the account-free edition — so arriving here means a
        // gate moved, not that a user did something odd.
        Log.i(TAG, "DROP: [unexpected] PaywallUi.showConsoleSetup() — Dashie's console guide " +
            "does not apply to this edition; keys are configured in its own console.")
    }

    }
