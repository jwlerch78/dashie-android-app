package com.dashieapp.Dashie.edition

import android.view.View
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate

/**
 * Surfaces that ask the user for money: the trial-expired modal, the trial countdown chip,
 * cloud (paid) activation, the add-credits QR, and the licence row.
 *
 * Obtained per-Activity (`EditionSeams.paywall(activity)`) because the implementations are
 * view-owning and stateful — same lifetime as the Activity that hosts them.
 *
 * Chickadee implements all of it as no-ops. Most is **unreachable** rather than suppressed:
 * [VoiceEntitlement.isVoiceAllowed] is always true and there is no subscription, so nothing
 * ever needs to offer a purchase. A `DROP:` from here on a Chickadee device therefore means
 * the seam was cut in the wrong place, which is why the stub logs loudly.
 *
 * @see com.dashieapp.Dashie.halite.HaEditionGate — the older RUNTIME gate that suppresses
 *   paywalls in HA mode. It stays: Dashie has its own HA mode and still needs it. This is the
 *   compile-time counterpart — suppression vs. absence.
 */
interface PaywallUi {

    // ── Wired in Phase 2c commit 6 ─────────────────────────────────────

    /**
     * Create the trial-expired overlay and the trial countdown chip and hold them for this
     * Activity. Called once during setup. Chickadee: no-op — neither surface exists.
     */
    fun attachTrialSurfaces(
        halitePrefsProvider: () -> HalitePreferences?,
        visibilityGate: NativeWidgetVisibilityGate?,
    )

    /**
     * Re-evaluate the attached trial surfaces after a subscription-state change. Safe to call
     * before [attachTrialSurfaces] (no-op) and on every sync. Chickadee: no-op.
     */
    fun refreshTrialSurfaces()

    // ── Wired in Phase 2e-2a: the voice-licence dialogs ────────────────

    /**
     * The Control Center's trial-signup dialog ("To start your trial you'll need a … Account").
     *
     * Chickadee: no-op + `DROP:` — it has no accounts and no trial, so reaching this means a
     * commercial CC action survived the option-level gating in 2f-1/2/3 and is worth a loud line
     * rather than a silent nothing.
     *
     * @param onHide dismiss the Control Center overlay. Passed in because `main/` owns the
     *   overlay lifecycle; the dialog itself must not learn what a Control Center is.
     */
    fun showTrialSignup(onHide: () -> Unit)

    /**
     * The Control Center's "Subscribe to …" QR flow, its activation polling and success dialog.
     *
     * Chickadee: no-op + `DROP:` — it sells nothing, so reaching this means a commercial entry
     * point (a CC action, an `upsell-*`, or the trial-expired broadcast) survived gating.
     *
     * @param onRefresh redraw the Control Center after a successful activation. Passed in for
     *   the same reason as [showTrialSignup]'s `onHide`: `main/` owns the overlay.
     */
    fun showSubscribeFlow(
        scope: kotlinx.coroutines.CoroutineScope,
        onTriggerSubscriptionSync: (() -> Unit)?,
        onRefresh: () -> Unit,
    )

    /**
     * The "connect your phone" QR dialog — subscription management, chores-on-mobile, and the
     * generic account hub link.
     *
     * ⚠️ **Not strictly a paywall**, and worth saying so rather than quietly widening the word.
     * This interface has already become *commercial surfaces* broadly (it holds cloud activation
     * and the credit prompt). A QR to the account hub belongs with those: it is meaningless
     * without an account, which is exactly the axis this seam splits. An eighth seam interface
     * for two methods would be worse than one honest note here.
     *
     * Chickadee: no-op + `DROP:` — no accounts, no hub, nothing to connect a phone to.
     */
    fun showConnectMobile(titleOverride: String? = null, subtitleOverride: String? = null)

    /** The account-management variant of [showConnectMobile]. Chickadee: no-op + `DROP:`. */
    fun showManageAccount()

    // ── Wired in Phase 2e-2b: the credit-boundary surface ──────────────

    /**
     * Supply the free-engine actions the credit-boundary UI offers. Same carry-a-value shape
     * as the retired `attachVoiceUiCallbacks` had, and for the same reason.
     *
     * ⚠️ These two are the **trigger/mechanism seam**. `DegradedVoiceMode` — the thing they
     * drive — is NOT commercial and stays in `main/`: it holds a free-engine plan
     * (`sttChain`, `ttsProvider`, `brainAllowed`), which is Chickadee's *normal* operating
     * mode, not a degraded one. Only the credit *trigger* that enters it is commercial. 2a
     * classified `DegradedVoiceMode` as a MOVE; reading its surface says otherwise.
     */
    fun attachCreditBoundary(callbacks: CreditBoundaryCallbacks)

    /** Out-of-credits prompt. [balance] is the last known balance, or null if unknown. */
    fun showCreditPrompt(balance: Double?)

    /** Re-evaluate the low-credit pill. Called on every credit-state change. */
    fun updateLowCreditPill()

    /** Reflect degraded (free-engine) mode in the credit UI. */
    fun setCreditDegraded(degraded: Boolean)

    // ── Declared, wired in Phase 2e with the settings-page split ───────

    /** Cloud (paid) activation fork. See [CloudActivationReason]. */
    fun showCloudActivation(reason: CloudActivationReason)

    /**
     * A greyed Cloud/Hybrid preset row was tapped. Evaluate why the metered cloud is not
     * usable and run the appropriate flow — create account, claim the starter grant, or add
     * credits — then call [applyPreset] with [tappedPreset] once the blocker clears.
     *
     * The tri-state evaluation and all three dialogs live behind this seam so that
     * `CloudActivationState` and `CloudActivationDialog` leave `main/` entirely. The caller
     * keeps only what it owns: applying the preset and refreshing its own fragment.
     *
     * Chickadee: unreachable — with no metered service `VoiceAiOptions` omits the Cloud and
     * Hybrid rows (2f-2), so nothing can set `onDisabledTap` to reach here. Logs `DROP:`.
     */
    fun handleCloudPresetBlocked(tappedPreset: String, applyPreset: (String) -> Unit)

    /** Add-credits QR dialog. [source] tags the entry point for attribution. */
    fun showAddCredits(source: String)

    /**
     * The "use your own keys or a local model" setup explainer.
     *
     * Behind the seam because the dialog it opens carries a `dashieapp.com` guide URL, and that
     * endpoint has no business shipping in the account-free artifact. Keys and local engines are
     * configured in the Console (the HA add-on) — there is deliberately no on-device API-key
     * entry — so this is an explainer, not an in-place switch.
     *
     * ⚠️ Chickadee has its own console and its own BYOK story; this seam is about *Dashie's*
     * guide URL, not about the capability. If Chickadee ever wants an on-device explainer it
     * gets its own, rather than this one rebranded.
     */
    fun showConsoleSetup()
}

/**
 * ✅ **RESOLVED in 2e-2b — the credit-boundary surface IS on [PaywallUi] now.**
 *
 * The note below explains why it was deferred in 2c commit 6, and what changed. Kept because
 * the reasoning is the reusable part.
 *
 * ⚠️ **Originally deferred: the credit-boundary surface.**
 *
 * `CreditBoundaryUi` (the out-of-credits prompt, the low-credit pill, the "voice unavailable"
 * toast) looked like three more methods here. It is not: its real surface is a settable
 * `degraded` property plus two callbacks (`onUseLocalVoice`, `onDeferToLocalVoice`) that
 * `HaliteVoiceController` wires at five points, and those are entangled with
 * `DegradedVoiceMode` — the `$0` → free-engines fallback.
 *
 * Modelling that as fire-and-forget methods would have been a fiction that compiled. And the
 * degraded path is not purely commercial: running on free engines is Chickadee's NORMAL mode,
 * so the seam there is the credit *trigger*, not the mechanism.
 *
 * It gets its own seam design in Phase 2e, alongside moving `CreditBoundaryUi` and
 * `DegradedVoiceMode` — where the trigger and the mechanism can be separated properly.
 * Until then `HaliteVoiceController` keeps calling it directly, which is inert on Chickadee
 * for the reasons in [CreditService]'s note (`isBillableVoice` is false, so it is never armed).
 */
private object CreditBoundarySeamNote_RESOLVED

/** The four entry points of the cloud-activation flow. */
enum class CloudActivationReason {
    /** Cloud voice selected but the device has no Dashie account. */
    NEEDS_ACCOUNT,

    /** Signed in, but the balance is below the floor for a cloud turn. */
    NEEDS_CREDITS,

    /** The one-time starter grant is available to claim. */
    STARTER_GRANT,

    /** First-run fork: sign in, or continue without an account. */
    SIGNUP_FORK,
}



/**
 * The free-engine actions the credit-boundary UI offers when the user is out of credits.
 *
 * Carried across the boundary as a value. These are the **mechanism**
 * half of the trigger/mechanism split: `DegradedVoiceMode` stays in `main/` because running on
 * free engines is what Chickadee does normally, and only the credit condition that triggers it
 * is commercial.
 *
 * ⚠️ **Both return `Boolean`, and the value is load-bearing:** `false` means *this device has
 * no free engine to fall back to*. `CreditBoundaryUi` branches on it to decide whether to tell
 * the user the switch happened — return `Unit` here and the dialog would claim "switched to
 * local voice" on a device that could not. Defaults are `false`, the honest answer for a
 * caller that supplies nothing.
 *
 * @property onUseLocalVoice persist the free config; false = no free engine on this device.
 * @property onDeferToLocalVoice run free for `expiresInMs` then re-ask; false = no free engine.
 */
class CreditBoundaryCallbacks(
    val onUseLocalVoice: () -> Boolean = { false },
    val onDeferToLocalVoice: (expiresInMs: Long) -> Boolean = { false },
)
