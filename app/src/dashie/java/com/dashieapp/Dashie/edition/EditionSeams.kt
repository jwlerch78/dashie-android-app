package com.dashieapp.Dashie.edition

import android.app.Activity
import android.content.Context
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.dialogs.showAddCreditsWebQrDialog
import com.dashieapp.Dashie.halite.settings.pages.calendar.CloudCalendarFlows
import com.dashieapp.Dashie.halite.settings.pages.calendar.DashieCloudCalendarFlows
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schemas.AccountBillingSections
import com.dashieapp.Dashie.halite.voice.CreditUrls
import com.dashieapp.Dashie.halite.install.MonthlyCheckin
import com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences
import com.dashieapp.Dashie.halite.voice.AutorefillSettingsClient
import com.dashieapp.Dashie.halite.voice.CreditBalanceReader
import com.dashieapp.Dashie.halite.voice.StarterGrantClient
import com.dashieapp.Dashie.controlcenter.SubscriptionStateProvider
import com.dashieapp.Dashie.halite.billing.TrialCountdownChipManager
import com.dashieapp.Dashie.halite.billing.TrialExpiredOverlayManager
import com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate

/**
 * DASHIE edition — the real implementations.
 *
 * This object is the **single source-set-split symbol** in the whole edition boundary. Its
 * twin lives at `src/chickadeeStub/java/.../EditionSeams.kt` with the same fully-qualified
 * name and the same surface; Gradle picks one per flavor (`app/build.gradle.kts`, the
 * `dashieFlavors` / `chickadeeFlavors` source-set wiring).
 *
 * One split point rather than seven was deliberate: the split is the part most likely to be
 * got wrong, so there is exactly one of it. Everything `main/` sees is an interface visible
 * at compile time in both editions.
 *
 * ⚠️ **Keep the surface of the two files identical.** Drift is caught per-edition at compile
 * time — a member missing from one copy fails that edition's build the moment `main/` uses
 * it — but only for members `main/` actually calls. An unused extra member in one copy is
 * harmless; a missing one is not.
 */
object EditionSeams {

    /** Voice entitlement. Free since 2026-08-02 — see [DashieVoiceEntitlement]. */
    @Suppress("UNUSED_PARAMETER")
    fun voice(context: Context, prefs: HalitePreferences): VoiceEntitlement =
        DashieVoiceEntitlement

    /** Credit meter network surface. */
    fun credits(context: Context): CreditService =
        DashieCreditService(context.applicationContext)

    /** Subscription-derived feature access. */
    fun subscription(context: Context): SubscriptionState =
        DashieSubscriptionState(context.applicationContext)

    /** Phone-home telemetry. */
    fun telemetry(context: Context): EditionTelemetry =
        DashieEditionTelemetry(context.applicationContext)

    /** Paywall surfaces. Per-Activity: the implementations own views. */
    fun paywall(activity: Activity): PaywallUi = DashiePaywallUi(activity)

    /**
     * Do CLOUD calendar accounts (Google / Outlook / Apple) exist as a concept in this edition?
     *
     * A capability, not an edition sniff — the same shape the retired `hasLicensing` had:
     * callers use it to decide whether the option EXISTS, never to branch on a brand name.
     * `false` means the provider rows are absent from the schema, not present-and-failing.
     *
     * Home Assistant calendars are NOT covered by this and stay in both editions: they are the
     * household's own entities on the household's own box, which is Chickadee's calendar source.
     */
    val hasCloudCalendarAccounts: Boolean get() = true

    /**
     * Does this edition have Dashie ACCOUNTS at all?
     *
     * Broader than [hasCloudCalendarAccounts], and the two are not interchangeable: that one asks
     * whether *calendar provider* accounts exist, this one whether the product has a cloud
     * account concept — which is what governs the Supabase project, the session, and telemetry.
     *
     * Deliberately the same NAME as the kiosk overlay's `brand.hasAccounts` (`kiosk-overlay/js/
     * brand.js`). That is shared vocabulary, not a hand-mirrored value: each side computes it
     * from its own edition, and nothing has to stay byte-equal.
     *
     * 🔑 Fifth instance of one question John has now answered identically — first-run account
     * offer, cloud calendar accounts, the kiosk shell session bridge, the licence, and now the
     * Supabase credentials. The answer every time: **the option should not exist**, not
     * exist-and-fail.
     */
    val hasAccounts: Boolean get() = true

    /**
     * The cloud calendar-account FLOWS, or `null` in an edition that has none.
     *
     * Distinct from [hasCloudCalendarAccounts] on purpose, and the pair is not redundant: the
     * boolean answers *"should the rows exist?"* at runtime, while this answers *"do the
     * implementations exist in this build at all?"* — a compile-time fact. The runtime gate was
     * already correct and still left the flows compiled into the Chickadee artifact, because a
     * runtime `if` compiles its body. This member is what lets `main/` call them without
     * naming symbols that are absent from that edition.
     */
    val cloudCalendarFlows: CloudCalendarFlows? get() = DashieCloudCalendarFlows

    /**
     * The Account page's billing sections ("Dashie Plan" / "Subscribe to Dashie"), or an empty
     * list in an edition with nothing to subscribe to.
     *
     * Same shape and same reason as [cloudCalendarFlows]: the runtime gate on these rows was
     * already correct (`account.isLinked` is permanently false in the account-free edition), and
     * still left the brand prose compiled into that edition's artifact — a runtime `if` compiles
     * its body. Returning the sections from here is what lets `AccountPageSchema` in `main/`
     * assemble a page whose paid-product rows do not exist in the other build.
     */
    val accountBillingSections: List<SettingsSection> get() = AccountBillingSections.sections()

    /**
     * The shared-kiosk Disconnect hint — "Manage in <the add-on that serves this household>".
     *
     * A seam rather than a string in `main/` because the two add-on names are Dashie's, and the
     * `main/` copy was a **hand-mirror of [CreditUrls.managementSurface]**: same two names, same
     * `isPublishedHub` input, computed twice. Standing rule 1 — share the first rather than write
     * the second — so this delegates to that function instead of respelling it.
     */
    fun accountDisconnectHint(context: Context): String =
        "Manage in ${CreditUrls.managementSurface(context)}"
}

// ── Implementations ────────────────────────────────────────────────────────

/**
 * 🔴 Voice is FREE on Dashie as of 2026-08-02 (John) — the licence and its 30-day trial retired.
 *
 * This is now identical to the Chickadee stub, and that is the honest end state rather than an
 * oversight: with no licence there is nothing edition-specific left to answer. The interface
 * survives as the reserved landing spot for the next thing that CAN answer "no" (a hosted
 * metering option) — see [VoiceEntitlement]'s note.
 *
 * The subscription is untouched and is NOT a voice gate: it is the broader product, and its own
 * trial UI (`TrialCountdownChipManager` / `TrialExpiredOverlayManager`, keyed on
 * `subscriptionStatus`) is a different surface that survives intact. Metering is likewise
 * untouched — AI / TTS / LLM / tool consumption still spends credits via [CreditService].
 */
private object DashieVoiceEntitlement : VoiceEntitlement {
    override fun isVoiceAllowed(): Boolean = true
}

private class DashieCreditService(private val context: Context) : CreditService {

    /** Single definition of "this device's account JWT" — see CreditService's note on why
     *  there is no `AccountIdentity` seam. */
    override val hasMeteredService: Boolean get() = true

    override fun isCloudUsable(): Boolean =
        com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationState
            .isUsable(context, HalitePreferences(context))

    private fun jwt(): String = HalitePreferences(context).connection.supabaseJwt

    override fun refreshBalance(force: Boolean) {
        val token = jwt()
        if (token.isEmpty()) return
        runCatching { CreditBalanceReader.refreshAsync(token, force = force) }
    }

    override fun claimStarterGrant(onResult: (StarterGrantOutcome?) -> Unit) {
        StarterGrantClient.claim(jwt()) { result ->
            // null propagates as null: "don't know", not "no grant". `usable` is carried
            // through deliberately — it is the already-claimed-but-still-funded case, where
            // the correct behaviour is to say nothing and let the user through.
            onResult(result?.let {
                StarterGrantOutcome(granted = it.granted, amountUsd = it.amountUsd, usable = it.usable)
            })
        }
    }

    override fun setAutorefillEnabled(enabled: Boolean, onResult: (Boolean, String?) -> Unit) {
        AutorefillSettingsClient.setEnabled(context, enabled, onResult)
    }
}

private class DashieSubscriptionState(context: Context) : SubscriptionState {

    private val provider = SubscriptionStateProvider(context)
    private val prefs = SubscriptionPreferences(context)

    override fun hasFeatureAccess(featureId: String): Boolean =
        provider.hasFeatureAccess(featureId)

    override fun featureSubtext(featureId: String): String? =
        provider.getFeatureSubtext(featureId)

    override fun isTrialWarning(featureId: String): Boolean =
        provider.isTrialWarning(featureId)

    override fun statusDisplay(): String = provider.getStatusDisplay()

    override fun planDisplay(): String = provider.getPlanDisplay()

    override val hasActiveAccess: Boolean
        get() = prefs.hasActiveAccess

    override fun isHaOnlyPlan(): Boolean =
        prefs.subscriptionStatus == SubscriptionPreferences.STATUS_HA_ONLY
}

private class DashieEditionTelemetry(private val context: Context) : EditionTelemetry {
    override fun checkinIfDue() {
        runCatching { MonthlyCheckin(context).checkinIfDue() }
    }
}

private class DashiePaywallUi(private val activity: Activity) : PaywallUi {

    private var trialExpired: TrialExpiredOverlayManager? = null
    private var trialChip: TrialCountdownChipManager? = null

    override fun attachTrialSurfaces(
        halitePrefsProvider: () -> HalitePreferences?,
        visibilityGate: NativeWidgetVisibilityGate?,
    ) {
        trialExpired = TrialExpiredOverlayManager(
            activity = activity,
            halitePrefsProvider = halitePrefsProvider,
            visibilityGate = visibilityGate,
        )
        trialChip = TrialCountdownChipManager(
            activity = activity,
            visibilityGate = visibilityGate,
        )
    }

    /** Null-safe by design: callers refresh on every subscription sync, including before
     *  attach has run on a cold start. */
    override fun refreshTrialSurfaces() {
        trialExpired?.refresh()
        trialChip?.refresh()
    }

    override fun showTrialSignup(onHide: () -> Unit) =
        com.dashieapp.Dashie.halite.billing.TrialSignupUi(activity).showTrialSignupDialog(onHide)

    override fun showConnectMobile(titleOverride: String?, subtitleOverride: String?) {
        val d = com.dashieapp.Dashie.sidebar.ConnectMobileDialog(activity)
        if (titleOverride == null && subtitleOverride == null) d.show()
        else d.show(titleOverride = titleOverride, subtitleOverride = subtitleOverride)
    }

    override fun showManageAccount() {
        com.dashieapp.Dashie.sidebar.ConnectMobileDialog(activity).showManageAccount()
    }

    override fun showSubscribeFlow(
        scope: kotlinx.coroutines.CoroutineScope,
        onTriggerSubscriptionSync: (() -> Unit)?,
        onRefresh: () -> Unit,
    ) = com.dashieapp.Dashie.halite.billing.SubscribeUi(activity, scope, onTriggerSubscriptionSync)
        .showPurchaseSubscriptionFlow(onRefresh)

    private var creditCallbacks = CreditBoundaryCallbacks()

    private val creditUi by lazy {
        com.dashieapp.Dashie.halite.voice.CreditBoundaryUi(activity).apply {
            // Boolean pass-through, not discarded — false means "no free engine on this
            // device" and CreditBoundaryUi branches on it before claiming success to the user.
            onUseLocalVoice = { creditCallbacks.onUseLocalVoice() }
            onDeferToLocalVoice = { ms -> creditCallbacks.onDeferToLocalVoice(ms) }
        }
    }

    override fun attachCreditBoundary(callbacks: CreditBoundaryCallbacks) {
        creditCallbacks = callbacks
    }

    override fun showCreditPrompt(balance: Double?) { creditUi.showPrompt(balance) }
    override fun updateLowCreditPill() { creditUi.updateLowCreditPill() }
    override fun setCreditDegraded(degraded: Boolean) { creditUi.degraded = degraded }

    // Wired in Phase 2e with the settings-page split — see PaywallUi.
    override fun showCloudActivation(reason: CloudActivationReason) {
        val d = com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationDialog
        when (reason) {
            CloudActivationReason.NEEDS_ACCOUNT -> d.showNeedsAccount(activity)
            CloudActivationReason.NEEDS_CREDITS -> d.showNeedsCredits(activity, hasCredits = false)
            CloudActivationReason.SIGNUP_FORK -> d.showSignupFork(activity)
            // STARTER_GRANT needs an amount, which only the claim result carries — it is shown
            // from handleCloudPresetBlocked, not from this generic entry point.
            CloudActivationReason.STARTER_GRANT -> d.showNeedsAccount(activity)
        }
    }
    /**
     * Moved verbatim from `SettingsCallbackWiring`'s `onCloudPresetBlocked` handler (2f-3).
     * Only the closure boundary changed: the caller keeps `applyRememberedPreset`, this owns
     * the evaluation and the three dialogs.
     */
    override fun handleCloudPresetBlocked(tappedPreset: String, applyPreset: (String) -> Unit) {
        val prefs = HalitePreferences(activity)
        val conn = prefs.connection
        val remembered = tappedPreset.ifBlank { "cloud" }
        val ctx = activity

        when (com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationState.evaluate(ctx, prefs)) {
            com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationState.State.USABLE -> {
                // Row was stale (built before the account/credits synced) — cloud is actually
                // usable. Apply the tapped preset directly instead of making the user re-tap.
                if (conn.hasSupabaseJwt) EditionSeams.credits(ctx).refreshBalance(force = true)
                applyPreset(remembered)
            }
            com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationState.State.NEEDS_ACCOUNT -> {
                com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationDialog.showNeedsAccount(activity)
            }
            com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationState.State.NEEDS_CREDITS -> {
                // Starter grant (2026-07-29): a user's FIRST cloud/hybrid activation should
                // succeed, not hit the credits wall. The server decides — it is the only thing
                // that knows whether this account already claimed. Fall through to the ORIGINAL
                // dialog on anything other than a fresh grant: already claimed and still broke,
                // knob off, or a null result ("don't know", and we must not claim credits we
                // cannot substantiate). Only `granted` earns the disclosure dialog.
                val showAddCredits = {
                    com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationDialog.showNeedsCredits(activity, hasCredits = false) {
                        // Dismissed — the user may have just added credits. Force a fresh
                        // balance read; when it lands AND cloud is now usable, auto-apply.
                        // Gated on USABLE so an unrelated credit-state change (autorefill /
                        // degraded / billable all fire the same notifyChanged) does not apply
                        // prematurely.
                        if (conn.hasSupabaseJwt) {
                            com.dashieapp.Dashie.halite.voice.CreditStateHolder.addOnChanged("voiceai-credit-added") {
                                if (com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationState.evaluate(ctx, prefs)
                                    == com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationState.State.USABLE) {
                                    com.dashieapp.Dashie.halite.voice.CreditStateHolder.removeOnChanged("voiceai-credit-added")
                                    applyPreset(remembered)
                                }
                            }
                            EditionSeams.credits(ctx).refreshBalance(force = true)
                        }
                    }
                }

                if (!conn.hasSupabaseJwt) {
                    showAddCredits()
                } else {
                    EditionSeams.credits(ctx).claimStarterGrant { res ->
                        activity.runOnUiThread {
                            // Guard the whole UI path: the claim is a network round-trip and the
                            // user can leave Settings while it is in flight. Showing a dialog on
                            // a finishing Activity throws BadTokenException.
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            if (res != null && res.granted) {
                                com.dashieapp.Dashie.halite.settings.dialogs.CloudActivationDialog
                                    .showStarterGrant(activity, res.amountUsd) { applyPreset(remembered) }
                            } else if (res != null && res.usable) {
                                // Already claimed, still funded — the gate read a stale cache.
                                // Nothing to disclose; let them through.
                                applyPreset(remembered)
                            } else {
                                showAddCredits()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 🔴 **This was `= Unit` — a SILENT no-op in the PAYING edition's purchase path.**
     *
     * Found 2026-08-04 while moving the add-credits cluster behind this seam. It was not merely
     * dormant: [handleCloudPresetBlocked] above already calls `showAddCredits()` internally, so a
     * Dashie user whose cloud preset was blocked with no remembered preset to fall back on got
     * **nothing at all** — no dialog, no error, no log line. The one place the product asks to be
     * paid, doing nothing quietly.
     *
     * ⚠️ It is also the trap that was sitting directly in this refactor's path: the plan was to
     * route `main/`'s add-credits entry points through this seam, and had that landed against a
     * `= Unit` body, **add-credits would have silently died across the whole paying edition** —
     * while every gate stayed green, because a no-op compiles perfectly. Chickadee's side was
     * always correct here (it logs a loud `DROP:`); it was Dashie's that was quiet.
     *
     * The Amazon guard lives HERE rather than at each call site: Amazon rejects external-payment
     * UI outright, and a compliance rule enforced in one place cannot be forgotten by the next
     * caller wired to this seam. It returns silently on purpose — this is the one case where
     * showing nothing IS the correct behaviour, so it is stated rather than left to look like the
     * bug above.
     */
    override fun showAddCredits(source: String) {
        if (com.dashieapp.Dashie.BuildConfig.FLAVOR == "amazon") return
        activity.showAddCreditsWebQrDialog(source = source)
    }

    /**
     * ⚠️ A REAL body, deliberately — and the reason that is worth saying out loud is
     * [showAddCredits] directly above, which shipped as `= Unit` and silently swallowed the
     * paying edition's purchase prompt. A seam override that does nothing compiles, passes every
     * gate, and is invisible until a user reports the button doing nothing. If this ever needs to
     * become a no-op, it should log why.
     */
    override fun showConsoleSetup() {
        com.dashieapp.Dashie.halite.settings.dialogs.ConsoleSetupDialog.show(activity)
    }
}
