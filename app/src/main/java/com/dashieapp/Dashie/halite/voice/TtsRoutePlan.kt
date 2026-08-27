package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.preferences.VoicePreferences

/**
 * The reply-lane TTS decision: which engine actually speaks a response, and what to call it
 * in a log.
 *
 * Extracted 2026-08-26 with the HA-Assist/cascade unification. Until then the cascade grew a
 * ROUTER ([NativeResponseTts]) while HA-Assist called a LEAF (`speakDeviceTts`) directly, so
 * every capability added to the router was inherited by one lane and silently missed by the
 * other — the gap widened by itself and surfaced only when a human noticed by ear. Both reply
 * lanes now go through the router, and this is the ONE place that turns
 * (user setting × degraded override × what is actually configured) into a branch.
 *
 * 🔴 Why the override lives in the same function as the setting, and not one layer away:
 * `voice.ttsProvider` and the WS-D.1 degraded override are **the same decision**. Route a lane
 * through the router WITHOUT carrying the override and that lane gains the ability to speak
 * with a BILLED cloud voice while the account is out of credits — a billing hole opened by
 * fixing a TTS bug. Keeping them in one resolve() with one test makes that separation
 * unrepresentable rather than merely discouraged.
 * See `.reference/HA_VOICE_PATHWAYS.md` → "HA-Assist vs cascade" for the audit this came from.
 *
 * Pure: no Android, no prefs, no I/O — the caller supplies the facts, so it is JVM-testable.
 */
object TtsRoutePlan {

    /** The engine family that will render this reply. */
    enum class Engine { CLOUD, LOCAL_URL, HA_ENGINE, HA_PIPELINE, DEVICE }

    data class Route(
        val engine: Engine,
        /** The provider id in force — the override when one applies, else the user's setting. */
        val resolvedProvider: String,
        /** What the user actually picked. Kept so a log can name a substitution. */
        val configuredProvider: String,
        /** True when [resolvedProvider] came from the degraded override AND differs from the setting. */
        val substituted: Boolean,
        /** Set when [engine] is DEVICE only because the picked engine has no config on this
         *  device — a fallback, not the user's choice. Null on an honoured choice. */
        val unconfigured: Engine? = null,
    )

    /**
     * @param configuredProvider `voice.ttsProvider`.
     * @param degradedOverride the WS-D.1 free-engine id to use INSTEAD (null = honour the setting).
     * @param haEngineConfigured an HA TTS engine id AND an HA origin are both present.
     * @param localTtsUrlConfigured `voice.localTtsUrl` is non-blank.
     * @param haPipelineAvailable an [HaTtsSynthesizer] exists on this lane. It does NOT on
     *   HA-Assist (it is built by `initializeVoicePipeline`), which is correct: there, HA speaks
     *   its own pipeline voice and this router is never asked to.
     */
    fun resolve(
        configuredProvider: String,
        degradedOverride: String?,
        haEngineConfigured: Boolean,
        localTtsUrlConfigured: Boolean,
        haPipelineAvailable: Boolean,
    ): Route {
        val resolved = degradedOverride ?: configuredProvider
        val substituted = degradedOverride != null && degradedOverride != configuredProvider
        fun route(engine: Engine, unconfigured: Engine? = null) =
            Route(engine, resolved, configuredProvider, substituted, unconfigured)

        return when (resolved) {
            VoicePreferences.TTS_DASHIE_CLOUD -> route(Engine.CLOUD)
            VoicePreferences.TTS_LOCAL_URL ->
                if (localTtsUrlConfigured) route(Engine.LOCAL_URL)
                else route(Engine.DEVICE, unconfigured = Engine.LOCAL_URL)
            VoicePreferences.TTS_HA_ENGINE ->
                if (haEngineConfigured) route(Engine.HA_ENGINE)
                else route(Engine.DEVICE, unconfigured = Engine.HA_ENGINE)
            VoicePreferences.TTS_VA_DEFAULT ->
                if (haPipelineAvailable) route(Engine.HA_PIPELINE)
                else route(Engine.DEVICE, unconfigured = Engine.HA_PIPELINE)
            else -> route(Engine.DEVICE)   // android_voice and anything unrecognised
        }
    }

    /**
     * A command acknowledgement ("turning off the lights") plays the confirmation tone instead
     * of being spoken, when the user has one selected. Informational answers are always spoken.
     *
     * 📌 This gate used to live INSIDE `speakDeviceTts`, i.e. below the branch — so it was
     * reachable only when the device engine happened to be the one speaking, and the router's
     * own device lambda hardcoded `isCommand = false` on top of that. Hoisting it above the
     * branch is what lets a tone-configured user keep the beep no matter which engine the
     * reply would otherwise have used, and it is a precondition for routing HA-Assist (the
     * only lane that has ever passed `isCommand = true`) through the router at all.
     */
    fun speaksToneInsteadOfWords(isCommand: Boolean, confirmationToneEnabled: Boolean): Boolean =
        isCommand && confirmationToneEnabled

    /** The SETTING VALUE of the engine that actually renders — never the raw preference. */
    private fun settingValueOf(engine: Engine): String = when (engine) {
        Engine.CLOUD -> VoicePreferences.TTS_DASHIE_CLOUD
        Engine.LOCAL_URL -> VoicePreferences.TTS_LOCAL_URL
        Engine.HA_ENGINE -> VoicePreferences.TTS_HA_ENGINE
        Engine.HA_PIPELINE -> VoicePreferences.TTS_VA_DEFAULT
        Engine.DEVICE -> VoicePreferences.TTS_ANDROID_VOICE
    }

    /**
     * Name the engine that WILL RUN, never the preference — the same rule as the `STT stage =`
     * line in HaVoiceService, and for the same reason: that line printed the pref and so read
     * as affirmatively false whenever the two differed. A substitution says BOTH.
     *
     * This is the oracle for "is `voice.ttsProvider` honoured on this lane?" — the question
     * that could not be answered from a log before, because the only TTS line printed the
     * STAGE (`device` vs `HA pipeline`), not the engine.
     *
     * 🔒 **MARKER CONTRACT — pre-agreed with Thread T (s47 cont.1) before this line was
     * written, and enforced by `npm run lint:markers`.** Rendered by the caller as
     * `TTS = device (<this>)`, so the load-bearing tokens are:
     *
     *   - the setting value FIRST, immediately after `TTS = device (` — T greps the prefix
     *     `TTS = device (ha_engine` as a fixed string
     *   - the bare token `SUBSTITUTED`
     *   - `user picked ` followed by the setting value
     *
     * Everything after the setting value is **decorative and free to enrich** — which is why
     * the concrete engine (`tts.piper`, `com.google.android.tts`) rides in the suffix. T wants
     * it precisely because the setting value alone cannot see defect 63's SECOND failure mode:
     * `ttsProvider=ha_engine` honoured while `haTtsEngineId` is unset or wrong, so
     * engine-direct speaks in a voice the user never picked.
     *
     * 🔴 A degraded override MUST print as a substitution. T's leg scores an ANNOUNCED
     * contradiction as correct behaviour and a SILENT one as a defect — so without the token,
     * the billing-hole guard this unit exists to install would be untestable by any leg.
     */
    fun describe(
        route: Route,
        haEngineId: String?,
        haVoiceId: String?,
        localTtsUrl: String?,
        deviceEngineId: String?,
    ): String {
        val detail = when (route.engine) {
            Engine.CLOUD -> ""      // the voice is per-personality, logged by CloudTtsRouter at speak
            Engine.LOCAL_URL -> ": " + (localTtsUrl?.takeIf { it.isNotBlank() } ?: "?")
            Engine.HA_ENGINE -> ": " + (haEngineId?.takeIf { it.isNotBlank() } ?: "?") +
                (haVoiceId?.takeIf { it.isNotBlank() }?.let { "/$it" } ?: "")
            Engine.HA_PIPELINE -> ""
            Engine.DEVICE -> ": " + (deviceEngineId?.takeIf { it.isNotBlank() } ?: "?")
        }
        // "falling back to device TTS" is the same phrase the runtime failure paths log, so one
        // ANNOUNCE token covers a decline whether it was decided here or hit at speak time.
        val fellBack = route.unconfigured?.let {
            " — falling back to device TTS, ${settingValueOf(it)} not configured"
        } ?: ""
        val substituted = if (route.substituted) {
            " — SUBSTITUTED (degraded), user picked ${route.configuredProvider}"
        } else ""
        return settingValueOf(route.engine) + detail + fellBack + substituted
    }
}
