package com.dashieapp.Dashie.halite.voice

import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType

/**
 * WS-D.1 — the runtime "$0 → free engines" override, kept OUT of VoicePipelineCoordinator.
 *
 * [FreeVoicePlan] decides *what* is free; this owns the small amount of *state* around it —
 * which plan is active, whether we've announced it, and entering/leaving the mode. Same
 * rationale as SttProviderFactory/BrainToolResolver: the coordinator is at its size budget,
 * and this is policy + state, not orchestration.
 *
 * The load-bearing property: **nothing here is persisted.** No pref is written when we
 * degrade, so there is nothing to restore when credits return — [clear] is the whole
 * "undo", and a wall-mounted kiosk never needs the Settings PIN to get back.
 */
class DegradedVoiceMode(
    private val prefs: HalitePreferences,
    /** Wall clock, injectable so the "Not now" 24h expiry is deterministic in tests. */
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Is this STT provider usable on this device right now? (SttProviderManager availability.) */
    private val isProviderAvailable: (ProviderType) -> Boolean,
) {
    companion object { private const val TAG = "DegradedVoice" }

    private var plan: FreeVoicePlan.Plan? = null
    private var announced = false
    /** Expiry for a time-boxed temp fallback ("Not now" → 24h). 0 = no expiry ("Don't show
     *  again" / "Use local voice"-era). In-memory only — a restart re-arms the chooser anyway. */
    private var expiresAtMs: Long = 0L

    /** Fired when degraded mode turns on/off — drives the persistent pill + control-center card. */
    var onChanged: ((degraded: Boolean) -> Unit)? = null
    /** Speaks the one-time reason. Never degrade silently: a tablet that quietly gets worse
     *  reads as broken, and the user never learns credits are the fix. */
    var onAnnounce: ((String) -> Unit)? = null

    val isActive: Boolean get() = plan != null
    /** True once a time-boxed temp fallback ("Not now", 24h) has elapsed — the wake gate then
     *  [clear]s it and re-arms the chooser. A no-expiry degrade never expires. */
    fun isExpired(): Boolean = plan != null && expiresAtMs != 0L && now() >= expiresAtMs
    /** The FULL free STT chain to install as the provider priority while degraded — NOT a single
     *  lead. Load-bearing: if we forced just a lead and it failed, SttProviderManager's error-
     *  fallback would drop to the CONFIGURED priority, which includes the billed Deepgram → 402
     *  (found on-device 2026-07-20). Installing the whole free chain keeps the fallback free-only. */
    val sttChain: List<ProviderType> get() = plan?.sttPriority ?: emptyList()
    /** Free TTS to speak with, or null to honor the user's setting. */
    val ttsProvider: String? get() = plan?.ttsProvider
    /** May we still call a brain? False ⇒ device-control lane only. */
    val brainAllowed: Boolean get() = plan?.useBrain ?: true

    /**
     * Enter degraded mode using the best free engines this device actually has.
     * @return false when nothing free exists — the caller should then block + prompt, which is
     *         the honest outcome (it cannot be made bulletproof if local was never set up).
     */
    fun enter(reason: String, expiresInMs: Long = 0L): Boolean {
        val wasActive = plan != null
        val resolved = FreeVoicePlan.resolve(
            prefs.voice,
            prefs.connection.getHaOrigin(),
            prefs.connection.haAccessToken,
            // Only offer the Android recognizer where it actually exists (absent on Fire OS /
            // Lineage), so a dead end stays a dead end instead of promising a rung that can't run.
            androidRecognizerAvailable = isProviderAvailable(ProviderType.ANDROID_NATIVE),
        )
        // Keep every AVAILABLE free engine, in order — the fallback chain, not just the lead.
        // An offline-but-configured box (isAvailable can't tell) then falls to the NEXT free
        // engine instead of dead-ending or leaking to a billed one.
        val freeChain = resolved.sttPriority.filter(isProviderAvailable)
        if (freeChain.isEmpty()) return false
        plan = resolved.copy(sttPriority = freeChain)
        // Set the expiry only on a FRESH entry (a per-turn re-enter preserves the original 24h
        // window rather than sliding it forward each turn).
        if (!wasActive) expiresAtMs = if (expiresInMs > 0L) now() + expiresInMs else 0L
        Log.i(TAG, "💳 $reason — degrading to free engines (stt=$freeChain tts=${resolved.ttsProvider} brain=${resolved.useBrain}" +
            (if (expiresAtMs != 0L) " expiresInMs=$expiresInMs" else "") + ")")
        if (!announced) {
            announced = true
            onChanged?.invoke(true)
            resolved.announcement?.let { onAnnounce?.invoke(it) }
        }
        return true
    }

    /** Leave degraded mode (credits returned, or a probe is about to retry the paid path).
     *  @param full true = credits are actually back, so re-arm the announcement too. */
    fun clear(full: Boolean) {
        if (plan == null && !(full && announced)) return
        plan = null
        expiresAtMs = 0L
        if (full) {
            announced = false
            Log.i(TAG, "💳 credits restored — leaving degraded mode")
            onChanged?.invoke(false)
        }
    }
}
