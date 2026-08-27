package com.dashieapp.Dashie.halite.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.QrCodeGenerator
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper

/**
 * CR2/CR4 credit-boundary UI (20260630_CREDIT_PREFLIGHT_DEGRADE_SPEC.md, FB25 v2).
 *
 * MVP stance (decided 2026-06-30): at $0 a cloud-configured user is ASKED TO CHOOSE —
 * add credits (stay cloud) or deliberately switch to local voice — never silently
 * degraded to a worse pipeline. So this is a prompt + two CTAs, not engine-swap routing.
 *
 * - [showPrompt] — CR4 State 2 / CR2: the prompt-to-choose, house-styled
 *   ([R.layout.dialog_credit_prompt], cloned from the voice-license/subscribe purchase
 *   dialogs). "Add credits" reveals the QR in-place (standard QrCodeGenerator treatment;
 *   refill is account-holder-only — no on-tablet purchase). The QR/URL land on the
 *   mobile add-credits page with the account identity pre-filled (subscribe-QR pattern).
 *   "Voice & AI settings" opens the native settings page for the local-switch decision.
 *   On the amazon flavor the Add-credits CTA/QR are hidden entirely (Appstore IAP
 *   compliance — no in-app path to web billing; HamburgerPopout precedent).
 * - [showUnavailableToast] — CR4 State 3: STT can't run at all (cloud STT + not
 *   spendable). Mirrors the existing mic-muted toast pattern.
 *
 * State 1 (subtle low-balance heads-up) lives on the voice indicator's sidebar metadata
 * line, not here — see VoiceSidebarPanelView.
 */
class CreditBoundaryUi(private val activity: Activity) {
    companion object {
        private const val TAG = "CreditBoundaryUi"
        // FB27: low-credit pill shows while the cached balance is below this. Single-sourced from
        // CreditStateHolder, which needs it to re-arm a dismissal when the balance climbs back out.
        private const val PILL_THRESHOLD = CreditStateHolder.PILL_USD
        // FB27: while the pill is up, re-poll the balance so a top-up from ANOTHER device
        // (mobile add-credits) is picked up and the pill auto-closes — without opening the CC.
        private const val PILL_POLL_MS = 60_000L
        // Punch #5: "Not now" falls back to local voice for this long, then re-arms the chooser.
        private const val DEFER_LOCAL_VOICE_MS = 24 * 60 * 60 * 1000L

    }

    private var dialog: AlertDialog? = null
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pollRunnable: Runnable? = null

    /** Which notice the single dashboard pill is currently showing. ONE pill by design
     *  (John, 2026-07-20): a payment failure OUTRANKS low balance — it's the actionable
     *  cause, and stacking two amber pills over the dashboard reads as noise. */
    private enum class PillMode { NONE, DEGRADED, FAILURE, LOW }

    /** WS-D.1: set by the pipeline when the turn ran on free engines because credits are out.
     *  Persistent + visible is the whole point — never degrade silently. */
    var degraded: Boolean = false
        set(value) {
            // A fresh $0 episode (false→true) re-arms the dismissible pill so it shows again.
            if (value && !field) CreditStateHolder.rearmDegradedPill()
            field = value; updateLowCreditPill()
        }

    /** Punch #5: PERSIST the free config ("Use local voice" — permanent, no auto-restore).
     *  Returns false when this device has no free engine (so the caller can say so honestly). */
    var onUseLocalVoice: (() -> Boolean)? = null

    /** Punch #5: the TEMPORARY runtime fallback for "Not now" (pass 24h) and "Don't show again"
     *  (pass 0 = no expiry). In-memory DegradedVoiceMode override; auto-restores on credit return,
     *  restart, or expiry. Returns false when no free engine exists. */
    var onDeferToLocalVoice: ((expiresInMs: Long) -> Boolean)? = null

    /** FB27 + WS-L.3: show/hide the dismissible dashboard pill. Failure wins over low; each has
     *  its own dismissal (failure keyed on the streak timestamp, low re-armed when the balance
     *  climbs back above [PILL_THRESHOLD]). Safe from any thread. */
    fun updateLowCreditPill() {
        activity.runOnUiThread {
            val pill = activity.findViewById<LinearLayout>(R.id.creditLowPill) ?: return@runOnUiThread
            val bal = CreditStateHolder.balance
            // Only for a billable (cloud) pipeline — a local/HA/voice-assistant config never bills,
            // so the pill auto-hides when the user switches to it (billable flips → onChanged).
            val billable = CreditStateHolder.billable
            val mode = when {
                // Degraded outranks both: it's the state the user is LIVING in right now
                // ("why does Dashie sound different?"). Dismissible per episode (John 2026-07-21) —
                // re-armed when degraded re-enters; a failure/low notice underneath would describe
                // the same root cause twice.
                billable && degraded && !CreditStateHolder.degradedPillDismissed -> PillMode.DEGRADED
                billable && CreditStateHolder.showAutorefillFailure -> PillMode.FAILURE
                billable && bal != null && bal < PILL_THRESHOLD && !CreditStateHolder.pillDismissed -> PillMode.LOW
                else -> PillMode.NONE
            }

            // Re-wire on EVERY mode change — the two modes have different tap/dismiss actions, and
            // a one-shot `pillWired` latch would leave the failure pill running the low pill's
            // handlers (dismissing the wrong flag).
            if (mode != PillMode.NONE) {
                val amazon = BuildConfig.FLAVOR == "amazon"
                activity.findViewById<TextView>(R.id.creditLowPillText)?.text = when (mode) {
                    // Says WHAT changed and WHY, so "Dashie sounds different" is self-explaining.
                    PillMode.DEGRADED -> "Using local voice — out of credits"
                    // Informational on every flavor. The repair (buy a pack → saves the new card)
                    // lives behind the tap, which is amazon-gated below.
                    PillMode.FAILURE -> "Auto-refill failed"
                    // "No Credits" once effectively empty (< $0.02), else "Credits low".
                    else -> if ((bal ?: 0.0) < 0.02) "No Credits" else "Credits low"
                }
                // Amazon IAP compliance: the failure pill is informational text ONLY — no tap-through
                // to the purchase chooser (20260714_TABLET_AUTOREFILL_UX §3). The low pill's chooser
                // already self-gates its Add-credits CTA, so it stays tappable everywhere.
                if ((mode == PillMode.FAILURE || mode == PillMode.DEGRADED) && amazon) {
                    pill.setOnClickListener(null)
                    pill.isClickable = false
                } else {
                    pill.isClickable = true
                    pill.setOnClickListener {
                        when (mode) {
                            PillMode.FAILURE -> showPrompt(bal, failure = true)
                            PillMode.DEGRADED -> showPrompt(bal, lowNudge = false)  // out-of-credits chooser
                            else -> showPrompt(CreditStateHolder.balance, lowNudge = true)
                        }
                    }
                }
                val close = activity.findViewById<ImageView>(R.id.creditLowPillClose)
                // All three pills are dismissible (DEGRADED became dismissible per-episode 2026-07-21).
                close?.visibility = View.VISIBLE
                close?.setOnClickListener {
                    when (mode) {
                        PillMode.FAILURE -> CreditStateHolder.dismissFailurePill()
                        PillMode.DEGRADED -> CreditStateHolder.dismissDegradedPill()
                        else -> CreditStateHolder.dismissPill()
                    }
                    pill.visibility = View.GONE
                    Log.i(TAG, "💳 ${mode.name.lowercase()} pill dismissed")
                }
            }
            pill.visibility = if (mode != PillMode.NONE) View.VISIBLE else View.GONE

            // Poll while the credit state is LOW — deliberately NOT "while the pill is visible".
            // Two gaps that caused: (a) the "Credits low" badge's threshold ($1) is wider than the
            // pill's ($0.50), so between $0.50 and $1 the badge showed with NO refresh running at
            // all; (b) dismissing the pill used to stop the only background refresh, so a top-up
            // from another device never landed and the stale low state stuck indefinitely.
            // WS-L.3: also poll while a failure streak is live, so a repair made elsewhere (buying
            // a pack on a phone saves a new card → the next charge succeeds → last_error clears)
            // takes the pill down on its own, without anyone touching the tablet.
            val shouldPoll = CreditStateHolder.billable &&
                (CreditStateHolder.low || CreditStateHolder.autorefillFailed)
            if (shouldPoll) startBalancePoll() else stopBalancePoll()
        }
    }

    /** FB27: poll the balance while the pill is up so a top-up from another device is picked up
     *  and the pill auto-closes (a new balance → CreditStateHolder.update → onChanged → this). */
    private fun startBalancePoll() {
        if (pollRunnable != null) return
        pollRunnable = object : Runnable {
            override fun run() {
                try {
                    val jwt = com.dashieapp.Dashie.halite.HalitePreferences(activity).connection.supabaseJwt
                    if (jwt.isNotEmpty()) CreditBalanceReader.refreshAsync(jwt)
                } catch (_: Exception) { /* best-effort */ }
                pollHandler.postDelayed(this, PILL_POLL_MS)
            }
        }
        pollHandler.postDelayed(pollRunnable!!, PILL_POLL_MS)
    }

    private fun stopBalancePoll() {
        pollRunnable?.let { pollHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    /** Mobile add-credits page (FB25.3), env-branched like the subscribe QR
     *  (ControlCenterOverlay), with the account identity pre-filled so the phone
     *  doesn't have to type it (it still signs in to check out). */
    private fun buyCreditsUrl(): String = CreditUrls.addCreditsUrl(activity)

    /** CR2 prompt-to-choose. Safe to call from any thread. Shows EVERY out-of-credits wake
     *  (FB27 — no cooldown) until the user picks "Don't show this again", after which it falls
     *  back to the toast for this episode (re-arms when credits go positive). */
    fun showPrompt(balance: Double? = null, lowNudge: Boolean = false, failure: Boolean = false) {
        // Out-of-credits path honors "Don't show this again"; a deliberate tap on the low-credit
        // or failure pill always shows the chooser.
        if (!lowNudge && !failure && CreditStateHolder.promptSuppressed) { showUnavailableToast(); return }
        activity.runOnUiThread {
            if (dialog?.isShowing == true) return@runOnUiThread   // don't stack on rapid re-fire

            val view = activity.layoutInflater.inflate(R.layout.dialog_credit_prompt, null)
            val title = view.findViewById<TextView>(R.id.dialogTitle)
            val message = view.findViewById<TextView>(R.id.textCreditMessage)
            val qrSection = view.findViewById<LinearLayout>(R.id.qrSection)
            val qrImage = view.findViewById<ImageView>(R.id.imageQrCode)
            val notNowButton = view.findViewById<Button>(R.id.buttonNotNow)
            val localVoiceButton = view.findViewById<Button>(R.id.buttonLocalVoice)
            val ownKeysButton = view.findViewById<Button>(R.id.buttonOwnKeys)
            val addCreditsButton = view.findViewById<Button>(R.id.buttonAddCredits)
            val dontShowAgainLink = view.findViewById<TextView>(R.id.textDontShowAgain)

            // FB33: no balance figure in the chooser — "out of credits (balance $0.04)" reads as a
            // contradiction near the floor. The Account card owns balance display.
            // FB27: same chooser (buttons + QR), different title/message for the low-credit nudge.
            if (failure) {
                // WS-L.3: state the notify-only contract (voice still works), name the decline,
                // and point at the ONE repair that works from a tablet: buying a pack saves the
                // new card, which auto-refill then uses. Turning auto-refill off is console-side
                // until the native Credits section (P2) lands.
                title.text = "Auto-Refill Failed"
                val reason = CreditStateHolder.autorefillError?.trim()?.trimEnd('.')
                // Only append a reason that ADDS a specific cause. Stripe's generic decline_message
                // ("Your card was declined") — and the test sentinel — merely restate the prefix, so
                // appending it read as "…was declined — …was declined". A reason that itself mentions
                // "declined" is that restatement; specific causes ("insufficient funds", "expired")
                // don't, and still show.
                val addsCause = !reason.isNullOrBlank() && !reason.lowercase().contains("declined")
                message.text = buildString {
                    append("Your saved card was declined")
                    if (addsCause) append(" — ").append(reason)
                    append(". Voice keeps working until your credits run out. ")
                    append("Add credits to save a new card, or turn auto-refill off in the ")
                    append(CreditUrls.managementSurface(activity)).append(".")
                }
            } else if (lowNudge) {
                title.text = "Credits Low"
                // Brand-split T4: name all three paths, not just the paid one. Cloud
                // inference costs money; running it is optional. Copy mirrors the
                // button row below it (local / own keys / add credits).
                message.text = "You're running low on ${CreditUrls.CREDITS_NAME}. Cloud voice " +
                    "is billed as you use it — add credits, switch to a local model, or use " +
                    "your own AI API keys."
            } else {
                title.text = "Add Credits"
                message.text = "You're out of ${CreditUrls.CREDITS_NAME}. Cloud voice is billed " +
                    "as you use it — add credits, switch to a local model, or use your own AI " +
                    "API keys. Local and own-key setups are free."
            }

            val d = AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(true)
                .create()
            d.window?.setBackgroundDrawableResource(android.R.color.transparent)

            notNowButton.setOnClickListener {
                d.dismiss()
                // Punch #5: at $0, "Not now" falls back to local voice TEMPORARILY (24h), then
                // re-arms the chooser — voice keeps working meanwhile. In-memory: a restart or a
                // credit return also re-arms. On the low-credit / failure notices there are still
                // credits, so "Not now" just dismisses.
                if (!lowNudge && !failure) onDeferToLocalVoice?.invoke(DEFER_LOCAL_VOICE_MS)
            }
            // Punch #5: "Use local voice" SWITCHES, it doesn't navigate. It used to call
            // openVoiceSettings(), which on a locked kiosk walks the user straight into the PIN
            // wall — a trap, since the whole point is that nobody is standing at the tablet. It
            // now PERSISTS the free config (writes the preset + rounds it up to the account) and
            // does NOT auto-restore on credit return — the user stays local until they switch back
            // in Settings, and this needs no PIN to invoke.
            localVoiceButton.setOnClickListener {
                d.dismiss()
                val switched = onUseLocalVoice?.invoke() ?: false
                if (switched) {
                    // One-time confirmation (John 2026-07-21: a toast, no standing pill — the
                    // config is genuinely free now, not "degraded"). Says how to undo it.
                    Toast.makeText(
                        activity,
                        "Switched to local voice — you can change it back anytime in Voice & AI settings.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // No free engine exists on this device — be honest instead of pretending we
                    // switched to something. Settings is still the (PIN-gated) place to set one up.
                    Toast.makeText(
                        activity,
                        "No local voice is set up on this device — add Home Assistant or a local Whisper box in Voice & AI settings.",
                        Toast.LENGTH_LONG
                    ).show()
                    openVoiceSettings()
                }
            }
            // Brand-split T4: the BYOK path. API keys are entered in the Dashie Console
            // (the HA add-on) — there is deliberately no on-device key entry, so unlike
            // "Use local voice" this can't switch anything in place; it explains where
            // to go. Shown on every flavor including amazon: it directs to a free
            // alternative, not to payment.
            ownKeysButton.setOnClickListener {
                d.dismiss()
                com.dashieapp.Dashie.halite.settings.dialogs.ConsoleSetupDialog.show(activity)
            }
            // From the low-credit PILL this chooser hides the PILL ("Hide notification"); from the
            // out-of-credits gate it suppresses the CR2 prompt ("Don't show this again").
            if (lowNudge || failure) {
                dontShowAgainLink.text = "Hide notification"
                dontShowAgainLink.setOnClickListener {
                    if (failure) CreditStateHolder.dismissFailurePill() else CreditStateHolder.dismissPill()
                    updateLowCreditPill()
                    Log.i(TAG, "💳 ${if (failure) "failure" else "low-credit"} pill hidden")
                    d.dismiss()
                }
            } else {
                dontShowAgainLink.text = "Don't show this again"
                dontShowAgainLink.setOnClickListener {
                    // Punch #5: stop nagging AND fall back to local voice silently (no expiry) —
                    // voice keeps working on free engines until credits return (runtime override,
                    // auto-restores; a restart re-arms the chooser).
                    CreditStateHolder.suppressPrompt()
                    onDeferToLocalVoice?.invoke(0L)
                    Log.i(TAG, "💳 CR2 suppressed + fell back to local voice for this episode (don't show again)")
                    d.dismiss()
                }
            }

            // Amazon Appstore IAP compliance: no in-app path to web billing — hide the
            // purchase CTA (and thus the QR) entirely; the settings CTA remains.
            // Precedent: HamburgerPopout Connect-Mobile gate, subscribe-flow no-op.
            if (BuildConfig.FLAVOR == "amazon") {
                addCreditsButton.visibility = View.GONE
            } else {
                addCreditsButton.setOnClickListener {
                    if (qrSection.visibility == View.VISIBLE) { d.dismiss(); return@setOnClickListener }
                    val url = buyCreditsUrl()
                    // Dashie-orange QR, matching the subscribe/voice-license QR treatment.
                    val qr = QrCodeGenerator.generateQrCode(url, 512, foregroundColor = 0xFFFF9500.toInt())
                    if (qr != null) qrImage.setImageBitmap(qr) else qrImage.visibility = View.GONE
                    qrSection.visibility = View.VISIBLE
                    addCreditsButton.text = "Done"
                    Log.i(TAG, "💳 CR2 QR revealed → $url")
                }
            }

            d.show()
            DialogHelper.applyImmersiveModeToDialog(d)   // AFTER show() — no DecorView before (NPE)
            dialog = d
            Log.i(TAG, "💳 CR2 prompt shown (balance=$balance)")
        }
    }

    /** CR4 State 3 — voice unavailable (mirrors the mic-muted toast). Any thread. */
    fun showUnavailableToast() {
        activity.runOnUiThread {
            Toast.makeText(activity, "Voice unavailable — out of ${CreditUrls.CREDITS_NAME}", Toast.LENGTH_SHORT).show()
        }
    }

    /** "Voice & AI settings" CTA: the deliberate cloud-vs-local decision lives there. */
    private fun openVoiceSettings() {
        try {
            activity.startActivity(
                Intent(activity, SettingsActivity::class.java).putExtra("navigate_to", "voice_ai")
            )
        } catch (e: Exception) {
            Log.w(TAG, "open Voice & AI settings failed: ${e.message}")
        }
    }
}
