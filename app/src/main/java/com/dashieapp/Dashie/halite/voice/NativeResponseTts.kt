package com.dashieapp.Dashie.halite.voice

import android.app.Activity
import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.voice.tts.CloudTtsRouter

/**
 * Routes a native-owned voice response to the TTS engine the user picked in
 * `voice.ttsProvider` (see [TtsRoutePlan] for the decision itself):
 *
 *   - `dashie_cloud` → ElevenLabs (natural cloud voice)
 *   - `local_url`    → the user's own OpenAI-compatible TTS box, direct
 *   - `ha_engine`    → HA engine-direct (Piper etc.), no Assist pipeline
 *   - `va_default`   → HA pipeline voice via [HaTtsSynthesizer] — the FB26 "cloud brain +
 *                      free/fast HA voice" combo
 *   - anything else  → Android device TextToSpeech
 *
 * 🔀 **Both reply lanes go through here** (2026-08-26). It used to serve only the
 * cascade/brain reply while the HA-Assist reply called the device engine directly, so
 * `ttsProvider` was honoured on one lane and silently ignored on the other — John found the
 * Piper case by ear, after two other instances of the same class in a week. The structural
 * cure is the same one applied to `stopAllSpeech` and `isTtsSpeaking`: collapse the owners
 * behind one router, so a capability added here is inherited by both lanes instead of by one.
 *
 * ⚠️ **This class does NOT own turn-end.** [onSpeechEnd] is a per-call argument, not a
 * constructor dependency, because the two lanes end a turn differently: the cascade drives
 * `onTtsSpeechEnd()` (indicator dismiss timer + cascade re-arm), while on HA-Assist
 * `HaVoiceService` owns the lifecycle and the caller's [onDone] alone must end the turn.
 * Making it a constructor lambda would have double-handled turn-end on HA-Assist — the exact
 * class of the three defects fixed at vc188–vc190 — so the call site always says who owns it.
 *
 * Dependencies are injected as lambdas so this stays decoupled from the controller's internals
 * (it was extracted from HaliteVoiceController to keep that file under the size budget).
 */
class NativeResponseTts(
    private val context: Context,
    private val prefs: HalitePreferences,
    private val cloudTts: CloudTtsRouter,
    private val localTts: () -> com.dashieapp.Dashie.halite.voice.tts.LocalTtsClient?,
    private val haTts: () -> HaTtsSynthesizer?,
    private val deviceTts: (text: String, onStart: () -> Unit, onComplete: () -> Unit) -> Unit,
    // WS-F.0b: AEC render-reference hookup for the HA engine-direct player. The controller's
    // AEC lives on the single mic seam and is shared by BOTH lanes (built in
    // initializeAfterRtspRelease, before either pipeline is chosen) despite its "cascade" name.
    private val aecControllerProvider: (() -> com.dashieapp.Dashie.halite.voice.aec.CascadeAecController?)? = null,
    /** WS-D.1: returns a TTS provider id to use INSTEAD of the configured one (free-engine
     *  degradation at $0), or null to honor the user's setting. Never mutates prefs. */
    private val ttsOverride: (() -> String?)? = null,
    /** Play the confirmation chime for a command acknowledgement. Gated ABOVE the engine
     *  branch — see [TtsRoutePlan.speaksToneInsteadOfWords]. */
    private val onPlayConfirmationTone: (() -> Unit)? = null,
    /** The Android TTS engine package that would speak (`com.google.android.tts`), for the
     *  resolved-engine log line. Null when the engine isn't initialized. */
    private val deviceEngineId: (() -> String?)? = null,
) {
    companion object { private const val TAG = "NativeResponseTts" }

    // HA engine-direct TTS client (ttsProvider=ha_engine) — owned here rather than
    // in the over-budget controller. Single lazy instance; HA creds + engine/voice
    // are read from prefs per-speak (like localTts/Kokoro).
    private val haEngineTts by lazy {
        com.dashieapp.Dashie.halite.voice.tts.HaTtsEngineDirectClient(context).apply {
            aecControllerProvider = this@NativeResponseTts.aecControllerProvider
        }
    }
    private var haEngineTtsUsed = false   // only stop() what was actually created (lazy)

    /** Cut any in-flight HA-side playback (barge-in / wake-word interrupt). The
     *  cloud/Kokoro/device players are stopped by the controller directly, but the
     *  HA clients live only here — without this, a barge-in re-armed the mic while
     *  Piper kept talking, and the dialog transcribed Dashie's own reply as the
     *  user's next turn (self-hearing, 2026-07-12). */
    fun stop() {
        if (haEngineTtsUsed) runCatching { haEngineTts.stop() }
        runCatching { haTts()?.stop() }
    }

    private fun haOrigin(): String =
        prefs.connection.haBaseUrl.ifEmpty { prefs.connection.getHaOrigin() ?: "" }

    /** The engine that WOULD speak right now, resolved exactly as [speak] resolves it. */
    private fun route(): TtsRoutePlan.Route = TtsRoutePlan.resolve(
        configuredProvider = prefs.voice.ttsProvider,
        degradedOverride = ttsOverride?.invoke(),
        haEngineConfigured = prefs.voice.haTtsEngineId.isNotBlank() && haOrigin().isNotBlank(),
        localTtsUrlConfigured = prefs.voice.localTtsUrl.isNotBlank(),
        haPipelineAvailable = haTts() != null,
    )

    /**
     * #64: the resolved engine, for the per-turn settings log. Derived from the SAME [route]
     * the next reply will take, so the line cannot claim one engine while another speaks.
     */
    fun describeResolvedEngine(): String = TtsRoutePlan.describe(
        route = route(),
        haEngineId = prefs.voice.haTtsEngineId,
        haVoiceId = prefs.voice.haTtsVoiceId,
        localTtsUrl = prefs.voice.localTtsUrl,
        deviceEngineId = runCatching { deviceEngineId?.invoke() }.getOrNull(),
    )

    /**
     * Speak [text] with the configured provider.
     *
     * @param isCommand this is a device/HA action acknowledgement, not an answer — it becomes
     *   the confirmation tone when the user has one selected, whichever engine is configured.
     * @param onSpeechEnd the LANE's speech-end hook (see the class doc — the caller owns
     *   turn-end, this class does not). Pass `{}` when another owner drives the lifecycle.
     * @param onDone fires when playback ends, or immediately if speaking is suppressed.
     * @param brainVoiceId / [brainVoiceProvider] the personality's resolved voice + owning
     *   vendor for the cloud path (null → defaults).
     */
    fun speak(
        text: String,
        brainVoiceId: String?,
        brainVoiceProvider: String? = null,
        sessionId: String? = null,
        isCommand: Boolean = false,
        onSpeechEnd: () -> Unit,
        onDone: () -> Unit,
    ) {
        val rh = prefs.voice.responseHandling
        if (rh == "display_only" || rh == "none" || !prefs.voice.readResponsesAloud) {
            Log.i(TAG, "🔊 Native response TTS skipped (responseHandling=$rh, readAloud=${prefs.voice.readResponsesAloud})")
            onDone()
            return
        }
        // One shared completion: fire the lane's speech-end, then the caller's re-arm.
        val complete: () -> Unit = {
            (context as? Activity)?.runOnUiThread { onSpeechEnd(); onDone() }
        }
        // Hoisted above the engine branch: a command ack beeps regardless of which engine
        // would have spoken it. Below the branch (where it used to live) it was reachable
        // only on the device path, and the router's own device lambda suppressed it.
        if (TtsRoutePlan.speaksToneInsteadOfWords(isCommand, prefs.voice.confirmationToneEnabled)) {
            Log.i(TAG, "🔊 Command — playing confirmation tone instead of speaking")
            onPlayConfirmationTone?.invoke()
            complete()
            return
        }
        // Wave 2 stage timing: every client below fires onStart at REAL first audio, so
        // tts_ms = speak() entry → onStart, attributed to the engine that played.
        val synthReqAt = System.currentTimeMillis()
        val started: (String) -> Unit = { engine ->
            VoiceStageTiming.reportTts(System.currentTimeMillis() - synthReqAt, engine)
        }
        // WS-D.1: while degraded (out of credits), speak with the free engine the plan chose
        // instead of the configured cloud voice. Runtime-only — prefs are untouched, so this
        // reverts by itself the moment credits return. Resolved TOGETHER with the user's
        // setting (see TtsRoutePlan): separating them is the billing hole.
        val route = route()
        val deviceEngineName = { runCatching { deviceEngineId?.invoke() }.getOrNull() ?: "device" }
        when (route.engine) {
            TtsRoutePlan.Engine.CLOUD -> {
                cloudTts.speak(
                    text = text,
                    brainVoiceId = brainVoiceId,
                    brainVoiceProvider = brainVoiceProvider,
                    sessionId = sessionId,   // groups this TTS row with its AI turn in the console
                    onStart = { started(cloudTts.engineId(brainVoiceProvider)); Log.i(TAG, "🔊 Cloud TTS ${cloudTts.describe(brainVoiceId, brainVoiceProvider)} speaking: '${text.take(50)}'") },
                    onDone = complete
                )
            }
            TtsRoutePlan.Engine.LOCAL_URL -> {
                // The user's own OpenAI-compatible TTS box, direct — no cloud, no HA hop.
                // The engine behind the URL is THEIR choice (Kokoro, Piper via the shim,
                // speaches…), so the reported engine is the transport, "local" — reporting
                // "kokoro" made the console's usage view claim Kokoro for every local box.
                val client = localTts()
                val url = prefs.voice.localTtsUrl
                if (client != null) {
                    client.speak(
                        text = text,
                        baseUrl = url,
                        voice = prefs.voice.localTtsVoiceId,
                        onStart = { started("local"); Log.i(TAG, "🔊 Local TTS ($url, voice=${prefs.voice.localTtsVoiceId.ifBlank { "default" }}) speaking: '${text.take(50)}'") },
                        onDone = complete
                    )
                } else {
                    Log.w(TAG, "DROP: local_url TTS but no client on this lane — falling back to device TTS")
                    deviceTts(text, { started(deviceEngineName()) }, complete)
                }
            }
            TtsRoutePlan.Engine.HA_ENGINE -> {
                // HA engine-direct (Piper etc.) — hit the detected engine, no Assist pipeline.
                // Engine + voice come from prefs (transport id carries neither).
                val engineId = prefs.voice.haTtsEngineId
                haEngineTtsUsed = true
                haEngineTts.speak(
                    text = text,
                    haBaseUrl = haOrigin(),
                    haToken = prefs.connection.haAccessToken,
                    engineId = engineId,
                    voice = prefs.voice.haTtsVoiceId,
                    onStart = { started(engineId); Log.i(TAG, "🔊 HA engine-direct TTS ($engineId, voice=${prefs.voice.haTtsVoiceId.ifBlank { "default" }}) speaking: '${text.take(50)}'") },
                    onDone = complete,
                    onError = { e ->
                        Log.w(TAG, "🔊 HA engine-direct TTS failed ($e) — falling back to device TTS")
                        (context as? Activity)?.runOnUiThread {
                            deviceTts(text, { started(deviceEngineName()) }, complete)
                        }
                    }
                )
            }
            TtsRoutePlan.Engine.HA_PIPELINE -> {
                val synth = haTts()
                if (synth != null) {
                    synth.speak(
                        text = text,
                        onStart = { started("ha-pipeline"); Log.i(TAG, "🔊 HA TTS speaking (pipeline voice): '${text.take(50)}'") },
                        onDone = complete,
                        onError = { e ->
                            Log.w(TAG, "🔊 HA TTS failed ($e) — falling back to device TTS")
                            (context as? Activity)?.runOnUiThread {
                                deviceTts(text, { started(deviceEngineName()) }, complete)
                            }
                        }
                    )
                } else {
                    deviceTts(text, { started(deviceEngineName()) }, complete)
                }
            }
            TtsRoutePlan.Engine.DEVICE -> {
                // A fallback the PLAN made (picked engine not configured) is loud — the silent
                // version of exactly this is why ha_engine looked like it was working.
                // "falling back to device TTS" is the same phrase the runtime failure paths use,
                // so one ANNOUNCE token covers a decline whether it was decided in the plan or
                // hit at speak time (T's leg scores an announced contradiction as correct
                // behaviour and a silent one as a defect).
                route.unconfigured?.let {
                    Log.w(TAG, "DROP: ${route.resolvedProvider} TTS selected but nothing is " +
                        "configured for it — falling back to device TTS")
                }
                deviceTts(text, { started(deviceEngineName()) }, complete)
            }
        }
    }
}
