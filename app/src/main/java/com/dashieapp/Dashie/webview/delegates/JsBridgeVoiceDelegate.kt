package com.dashieapp.Dashie.webview.delegates

import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.voice.VoiceAssistantCoordinator
import com.dashieapp.Dashie.voice.tts.TextToSpeechManager
import com.dashieapp.Dashie.wakeword.models.WakeWordModel
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager

/**
 * Delegate handling voice-related JavaScript bridge methods.
 * Includes: TTS, speech recognition, wake word detection, AGC, cloud STT,
 * wake word sample recording, and voice/AI settings.
 */
class JsBridgeVoiceDelegate(
    private val context: Context,
    private val webView: WebView,
    private val voiceAssistant: () -> VoiceAssistantCoordinator?,
    private val halitePrefs: () -> HalitePreferences?,
    private val wakeWordModelManager: WakeWordModelManager
) {
    companion object {
        private const val TAG = "JsBridgeVoice"
    }

    // Callbacks for voice-related actions
    var onRecordWakeWordSample: (() -> Unit)? = null
    var onToggleVoiceEnabled: (() -> Unit)? = null
    var onOpenMotionWakeSettings: (() -> Unit)? = null
    var onVoicePipelineModeChanged: (() -> Unit)? = null
    var onResponseHandlingChanged: ((showResponses: Boolean) -> Unit)? = null
    var onShowHaPipelinePicker: ((callback: (id: String, name: String) -> Unit) -> Unit)? = null
    var onRestartApp: (() -> Unit)? = null
    var onMicMutedChanged: ((Boolean) -> Unit)? = null
    var onSampleCollectionChanged: ((Boolean) -> Unit)? = null
    var onShowVoiceNotice: ((message: String, detail: String) -> Unit)? = null
    var onShowHaCommandResult: ((message: String, command: String) -> Unit)? = null

    // VoiceOverlayBridge delegate for overlay NLP responses
    var voiceOverlayBridge: com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge? = null

    // Standalone Android TTS for the JS speak() bridge. In cloud/JS-voice mode the
    // VoiceAssistantCoordinator (and its TTS) is never created, so DashieNative.speak()
    // — used when the account TTS engine is android_voice — had nothing to play
    // through. This always-available engine fills that gap, independent of the voice
    // pipeline. Pre-warmed on construction so the first utterance has TTS ready.
    private val bridgeTts: TextToSpeechManager =
        TextToSpeechManager(context).also { it.initialize() }

    // ============================================
    // Voice Weather Tool (device-fulfilled)
    // ============================================

    /**
     * Device-fulfilled weather for the JS voice tool: the latest on-device reading
     * (honoring the HA↔Open-Meteo toggle) as normalized JSON. Logic lives in
     * [com.dashieapp.Dashie.halite.voice.VoiceWeatherSnapshot] to keep this file thin.
     * (`query` is unused — the JS template does the timeframe slicing; kept for compat.)
     */
    fun getVoiceWeather(@Suppress("UNUSED_PARAMETER") query: String): String =
        com.dashieapp.Dashie.halite.voice.VoiceWeatherSnapshot.build(
            halitePrefs()?.general?.useHaForWeather == true
        )

    // ============================================
    // Credit boundary (CR2/CR4)
    // ============================================

    /**
     * Credit snapshot from a JS brain turn (`metadata.credit` + `degraded`). Updates the
     * static [com.dashieapp.Dashie.halite.voice.CreditStateHolder] — deliberately NOT a
     * per-instance callback (those die on nightly WebView recreation); the CR2 prompt
     * fires via the holder's controller-registered onExhausted.
     */
    fun onVoiceCreditState(json: String) {
        try {
            val o = org.json.JSONObject(json)
            val spendable = if (o.has("spendable") && !o.isNull("spendable")) o.optBoolean("spendable") else null
            val low = if (o.has("low") && !o.isNull("low")) o.optBoolean("low") else null
            val balance = if (o.has("balance") && !o.isNull("balance")) o.optDouble("balance") else null
            com.dashieapp.Dashie.halite.voice.CreditStateHolder.update(spendable, low, balance)
            if (o.optString("degraded") == "insufficient_credits") {
                com.dashieapp.Dashie.halite.voice.CreditStateHolder.markExhausted(balance)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onVoiceCreditState parse failed: ${e.message}")
        }
    }

    // ============================================
    // Voice Overlay Bridge Methods
    // ============================================

    fun onVoiceResponse(requestId: String, jsonResponse: String) {
        Log.d(TAG, "onVoiceResponse: requestId=$requestId")
        voiceOverlayBridge?.onVoiceResponse(requestId, jsonResponse)
            ?: Log.w(TAG, "onVoiceResponse: VoiceOverlayBridge not set")
    }

    fun onVoiceError(requestId: String, error: String) {
        Log.e(TAG, "onVoiceError: requestId=$requestId, error=$error")
        voiceOverlayBridge?.onVoiceError(requestId, error)
            ?: Log.w(TAG, "onVoiceError: VoiceOverlayBridge not set")
    }

    // Live brain progress ("Searching the web…", "Finalizing…") for the thinking
    // indicator. The brain owns the copy; this just forwards it. Wired by MainActivity.
    var onVoiceProgress: ((status: String) -> Unit)? = null

    fun onVoiceProgress(status: String) {
        Log.d(TAG, "onVoiceProgress: $status")
        onVoiceProgress?.invoke(status)
    }

    // ── Cascade conversation overlay (Dialog mode) ──────────────────────────────
    // The JS conversation loop drives the SAME native overlay Live uses (bottom bar +
    // transcript + cards), via these forwards → callbacks wired to the
    // VoiceIndicatorController conversation API. Build plan 20260628 §2.
    var onStartConversationOverlay: (() -> Unit)? = null
    var onSetConversationListening: ((Boolean) -> Unit)? = null
    var onConversationTranscript: ((String, String) -> Unit)? = null
    var onConversationCard: ((String) -> Unit)? = null
    var onEndConversationOverlay: ((Boolean) -> Unit)? = null

    fun startConversationOverlay() = onStartConversationOverlay?.invoke() ?: Unit
    fun setConversationListening(listening: Boolean) = onSetConversationListening?.invoke(listening) ?: Unit
    fun conversationTranscript(speaker: String, text: String) = onConversationTranscript?.invoke(speaker, text) ?: Unit
    fun conversationCard(cardJson: String) = onConversationCard?.invoke(cardJson) ?: Unit
    fun endConversationOverlay(leaveResponseUp: Boolean) = onEndConversationOverlay?.invoke(leaveResponseUp) ?: Unit

    /**
     * Play the user-configured confirmation tone (type + volume from VoicePreferences)
     * for a cloud-mode command acknowledgement. Returns true if a tone played, false
     * when the setting is "Disabled" — in which case the JS side speaks the response
     * instead. Has Context + prefs directly, so no MainActivity wiring is needed.
     */
    fun playConfirmationTone(): Boolean {
        val v = halitePrefs()?.voice ?: return false
        if (!v.confirmationToneEnabled) return false   // "Disabled" → JS speaks instead
        com.dashieapp.Dashie.halite.audio.ConfirmationTonePlayer.play(
            context, v.confirmationToneType, v.confirmationToneVolume
        )
        return true
    }

    // Callback for TTS speech end (set by HaliteVoiceController)
    var onTtsSpeechEnd: (() -> Unit)? = null

    fun onTtsSpeechEnd() {
        Log.d(TAG, "onTtsSpeechEnd: TTS finished speaking")
        onTtsSpeechEnd?.invoke()
    }

    // ============================================
    // TTS Methods
    // ============================================

    fun speak(text: String) {
        webView.post {
            val va = voiceAssistant()
            if (va != null) va.speak(text) else bridgeTts.speak(text)
        }
    }

    fun speakWithParams(text: String, rate: Float, pitch: Float) {
        webView.post {
            val va = voiceAssistant()
            if (va != null) va.speak(text, rate, pitch) else bridgeTts.speak(text, rate, pitch)
        }
    }

    fun stopSpeaking() {
        webView.post {
            val va = voiceAssistant()
            if (va != null) va.stopSpeaking() else bridgeTts.stopSpeaking()
        }
    }

    fun isSpeaking(): Boolean {
        return voiceAssistant()?.isSpeaking() ?: bridgeTts.isSpeaking()
    }

    // ============================================
    // Speech Recognition Methods
    // ============================================

    fun startListening() {
        webView.post {
            voiceAssistant()?.startListening()
        }
    }

    fun stopListening() {
        webView.post {
            voiceAssistant()?.stopListening()
        }
    }

    fun cancelListening() {
        webView.post {
            voiceAssistant()?.cancelListening()
        }
    }

    fun isListening(): Boolean {
        return voiceAssistant()?.isCurrentlyListening() ?: false
    }

    // ============================================
    // Wake Word Detection Methods
    // ============================================

    fun startWakeWordDetection() {
        webView.post {
            voiceAssistant()?.startWakeWordDetection()
        }
    }

    fun stopWakeWordDetection() {
        webView.post {
            voiceAssistant()?.stopWakeWordDetection()
        }
    }

    fun isWakeWordActive(): Boolean {
        return voiceAssistant()?.isWakeWordActive() ?: false
    }

    fun setWakeWordConfig(autoRecord: Boolean, duration: Int) {
        webView.post {
            voiceAssistant()?.setWakeWordConfig(autoRecord, duration)
        }
    }

    fun getWakeWordConfig(): String {
        return voiceAssistant()?.getWakeWordConfig() ?: "{}"
    }

    /** D5: the device's ACTUAL wake-word model id, from WakeWordModelManager (the
     *  per-device source of truth in overlay mode — unlike getWakeWordConfig, which
     *  routes through the legacy voiceAssistant and is empty on a kiosk). JS reports
     *  this UP in getCurrentDeviceSettings so the console reflects the real value. */
    fun getActiveWakeWord(): String {
        return try { wakeWordModelManager.getActiveModel().modelId } catch (e: Exception) { "" }
    }

    /** Account's voice-controllable entity source (dashboard | assist) for the kiosk overlay's
     *  buildHaVoiceContext — so a shared-account kiosk honors the account's pick instead of always
     *  using the exposed list (webapp/kiosk divergence, 20260717). "" → the kiosk defaults to
     *  'assist' (exposed). Mirrored from the account voice-config by KioskAccountVoiceApplier. */
    fun getVoiceEntitySource(): String {
        return try { halitePrefs()?.voice?.entitySource ?: "" } catch (e: Exception) { "" }
    }

    /** WS-G §13.2: the wake-word model ids this APK bundles, as a JSON array.
     *  JS (ai-voice-resolution.js) feature-detects this to decide whether the
     *  account-default wake word can be honored on this device — an id missing
     *  here → the device keeps its current word and reports
     *  aiVoice.defaultWakeWordUnavailable (no-silent-degradation rule). */
    fun getAvailableWakeWords(): String {
        return try {
            org.json.JSONArray(WakeWordModel.availableModelIds()).toString()
        } catch (e: Exception) { "[]" }
    }

    fun changeWakeWord(modelName: String, threshold: Float) {
        webView.post {
            voiceAssistant()?.changeWakeWord(modelName, threshold)
        }
    }

    fun setWakeWordThreshold(threshold: Float) {
        Log.i(TAG, "setWakeWordThreshold: $threshold")
        webView.post {
            voiceAssistant()?.setWakeWordThreshold(threshold)
        }
    }

    fun getWakeWordThreshold(): Float {
        return voiceAssistant()?.getWakeWordThreshold() ?: 0.55f
    }

    // ============================================
    // AGC (Automatic Gain Control) Methods
    // ============================================

    fun setAGCGain(gain: Float) {
        webView.post {
            voiceAssistant()?.setAGCGain(gain)
        }
    }

    fun getAGCGain(): Float {
        return voiceAssistant()?.getAGCGain() ?: 12.0f
    }

    fun startMicrophoneCalibration(durationSeconds: Int, onComplete: (String) -> Unit) {
        webView.post {
            voiceAssistant()?.startMicrophoneCalibration(durationSeconds) { stats ->
                onComplete(stats)
            }
        }
    }

    // ============================================
    // Cloud STT Capture Methods
    // ============================================

    fun startCloudSTTCapture(durationSeconds: Int, silenceThresholdSeconds: Float) {
        webView.post {
            voiceAssistant()?.startCloudSTTCapture(durationSeconds, silenceThresholdSeconds)
        }
    }

    fun stopCloudSTTCapture() {
        webView.post {
            voiceAssistant()?.stopCloudSTTCapture()
        }
    }

    fun setStreamingSTT(enabled: Boolean) {
        webView.post {
            voiceAssistant()?.setStreamingSTT(enabled)
        }
    }

    fun isStreamingSTT(): Boolean {
        return voiceAssistant()?.isStreamingSTT() ?: true
    }

    fun setSilenceThreshold(silenceMs: Int) {
        webView.post {
            voiceAssistant()?.setSilenceThreshold(silenceMs)
        }
    }

    fun getSilenceThreshold(): Int {
        return voiceAssistant()?.getSilenceThreshold() ?: 1000
    }

    fun startManualRecording(maxDurationSeconds: Int, silenceThresholdSeconds: Float) {
        webView.post {
            voiceAssistant()?.startManualRecording(maxDurationSeconds, silenceThresholdSeconds)
        }
    }

    // ============================================
    // Wake Word Sample Recording
    // ============================================

    fun recordWakeWordSample() {
        Log.i(TAG, "recordWakeWordSample() called")
        webView.post {
            onRecordWakeWordSample?.invoke()
        }
    }

    fun getWakeWordSampleStats(): String {
        return org.json.JSONObject(mapOf(
            "count" to 0,
            "sizeMB" to 0.0,
            "note" to "Samples now stream directly to webapp (no local storage)"
        )).toString()
    }

    fun clearWakeWordSamples(): String {
        Log.d(TAG, "clearWakeWordSamples called but file storage is disabled")
        return org.json.JSONObject(mapOf(
            "success" to true,
            "deleted" to 0,
            "note" to "File storage disabled - samples stream directly"
        )).toString()
    }

    fun exportWakeWordSamplesADB(): String {
        return org.json.JSONObject(mapOf(
            "success" to false,
            "error" to "Local file storage disabled. Samples now stream directly to webapp for upload."
        )).toString()
    }

    fun getWakeWordSampleFileList(): String {
        return org.json.JSONObject(mapOf(
            "success" to false,
            "files" to emptyList<String>(),
            "note" to "File storage disabled - samples stream directly"
        )).toString()
    }

    fun getWakeWordSampleFileData(filename: String): String {
        return org.json.JSONObject(mapOf(
            "success" to false,
            "error" to "File storage disabled - samples stream directly to webapp"
        )).toString()
    }

    // ============================================
    // Live Wake Word Sample Collection (Beta)
    // ============================================

    fun setLiveSampleCollectionEnabled(enabled: Boolean) {
        Log.i(TAG, "setLiveSampleCollectionEnabled($enabled)")
        // Standard Dashie path only — Halite path uses setSampleCollectionEnabled()
        // Do NOT write to halitePrefs here: Kotlin prefs are source of truth
        // and this method is called on webapp startup with potentially stale values
        webView.post {
            voiceAssistant()?.setLiveSampleCollectionEnabled(enabled)
        }
    }

    fun isLiveSampleCollectionEnabled(): Boolean {
        return voiceAssistant()?.isLiveSampleCollectionEnabled() ?: false
    }

    fun getLiveSampleCollectionStats(): String {
        return voiceAssistant()?.getLiveSampleCollectionStats() ?: "not initialized"
    }

    // ============================================
    // STT Provider Settings
    // ============================================

    fun getSttProvider(): String {
        val prefs = halitePrefs() ?: return "ha_assist"
        return if (prefs.voice.preferDeepgramStt && prefs.voice.hasDeepgramStt) "deepgram" else "ha_assist"
    }

    // ============================================
    // Voice & AI Settings (Drawer menu)
    // ============================================

    fun getVoiceSettings(): String {
        val prefs = halitePrefs()
        return org.json.JSONObject().apply {
            put("voiceEnabled", prefs?.voice?.voiceEnabled ?: false)
            put("responseHandling", prefs?.voice?.responseHandling ?: "read_and_display")
            put("pipelineMode", prefs?.voice?.voicePipelineMode ?: "ha")
            put("aiModel", prefs?.voice?.aiModel ?: "ollama")
            put("webSearchBackend", prefs?.voice?.webSearchBackend ?: "disabled")
            put("sttProvider", getSttProvider())
            put("hasDeepgramApiKey", prefs?.voice?.hasDeepgramStt ?: false)
            put("micMuted", prefs?.voice?.micMuted ?: false)
            put("sampleCollectionEnabled", prefs?.voice?.sampleCollectionEnabled ?: false)
            put("sampleCollectionConsent", prefs?.voice?.sampleCollectionConsent ?: false)
        }.toString()
    }

    /**
     * DEVICE-scoped voice keys in SETTINGS_KEY_MAP.voice shape (Phase 4.1
     * category unification). getVoiceSettings above is drawer-shaped (mixes
     * account pipeline keys, different field names); this one is the readback
     * for the 'voice' native-settings-changed branch + the boot snapshot's
     * equality-skip (_nativeGet). Field names MUST match the map keys.
     */
    fun getVoiceDeviceSettings(): String {
        val v = halitePrefs()?.voice
        return org.json.JSONObject().apply {
            put("enabled", v?.voiceEnabled ?: false)
            put("responseHandling", v?.responseHandling ?: "read_and_display")
            put("displayFormat", v?.displayFormat ?: "")
            put("confirmationToneType", v?.confirmationToneType
                ?: com.dashieapp.Dashie.halite.preferences.VoicePreferences.DEFAULT_CONFIRMATION_TONE)
            put("confirmationToneVolume", (v?.confirmationToneVolume
                ?: com.dashieapp.Dashie.halite.preferences.VoicePreferences.DEFAULT_CONFIRMATION_TONE_VOLUME).toDouble())
        }.toString()
    }

    /**
     * DEVICE-scoped aiVoice keys in SETTINGS_KEY_MAP.aiVoice shape — readback
     * for the 'aiVoice' native-settings-changed branch (closes census N2/N6:
     * wake-word picker + control-method personality switch now live-sync
     * instead of only healing at boot). voiceId is intentionally absent —
     * Kotlin doesn't track it (see setAiVoiceSettings).
     */
    fun getAiVoiceSettings(): String {
        // Emit personalityId/voiceKey ONLY when explicitly set: the property
        // getters fall back to install defaults ('dashie'/'BELLA'), and
        // persisting a default pins user_devices.aiVoice, shadowing the
        // account default in the brain's resolution chain (audit F7). A
        // device that never chose stays absent from the payload.
        val raw = context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
        return org.json.JSONObject().apply {
            if (raw.contains("ai_personality_id")) {
                put("personalityId", com.dashieapp.Dashie.halite.preferences.AiPreferences(context).personalityId)
            }
            if (raw.contains("voice_key")) {
                put("voiceKey", halitePrefs()?.voice?.voiceKey ?: "")
            }
            val wakeWord = try { wakeWordModelManager.getActiveModel().modelId } catch (e: Exception) { "" }
            if (wakeWord.isNotBlank()) put("wakeWord", wakeWord)
        }.toString()
    }

    /** DEVICE-scoped developer keys (SETTINGS_KEY_MAP.developer shape) —
     *  readback for the 'developer' branch (census N9). */
    fun getDeveloperSettings(): String {
        return org.json.JSONObject().apply {
            put("liveSampleCollection", halitePrefs()?.voice?.sampleCollectionEnabled ?: false)
        }.toString()
    }

    fun isVoiceEnabled(): Boolean {
        return halitePrefs()?.voice?.voiceEnabled ?: false
    }

    fun toggleVoiceEnabled() {
        Log.i(TAG, "toggleVoiceEnabled()")
        webView.post {
            onToggleVoiceEnabled?.invoke()
        }
    }

    // ============================================
    // Voice & AI Settings (Direct Setters)
    // ============================================

    /**
     * Restore voice pipeline settings from a cloud snapshot. Mirrors the
     * payload shape that ACTION_VOICE_SETTINGS_CHANGED ships to JS in
     * MainBroadcastManager — the round-trip closes here so user_devices.voice
     * can repopulate Kotlin VoicePreferences after a storage wipe.
     *
     * Partial payloads are fine — fields not present leave existing values
     * untouched.
     */
    /**
     * The account push just CHANGED a voice-engine selection this device was already running.
     *
     * Measured 2026-08-21 on a Fire: the device boots on its stored STT snapshot and the account
     * correction lands **+0.7 s** in one instance and **+4.5 s** in another (load-dependent, not a
     * constant). Anyone speaking inside that window silently gets the pre-account selection, and
     * the loss direction is toward the METERED provider — on-device was selected, `dashie_cloud`
     * ran. The device picker offers a choice the account overwrites with no indication on either
     * surface: the "dead selection" class, third instance.
     *
     * This is the loud half, and it lands first on purpose — making the override VISIBLE is
     * cheap and safe, while changing who wins is a behavioural change to boot-time provider
     * selection and needs its own unit. Until then a log answers "why did it run the cloud
     * provider when the picker said on-device?" in one grep instead of a DB read plus a probe.
     *
     * Not always a defect — a genuine console change comes through here too, and the device
     * cannot tell the two apart. The message says so rather than crying wolf.
     */
    private fun logAccountOverride(key: String, was: String, now: String) {
        Log.w(TAG, "DROP: account push OVERRODE device $key '$was' -> '$now'. " +
            "If the user picked '$was' on this device, that selection is now dead and any " +
            "utterance before this point used it. Expected when the account genuinely changed; " +
            "the device cannot distinguish the two.")
    }

    fun setVoiceSettings(json: String) {
        Log.i(TAG, "🎤 setVoiceSettings")
        val v = halitePrefs()?.voice ?: return
        try {
            val obj = org.json.JSONObject(json)
            if (obj.has("enabled")) v.voiceEnabled = obj.getBoolean("enabled")
            // Equality-gate the pipeline-affecting keys so a changed cloud/console
            // apply reinits the running pipeline live (mirrors the native menu's
            // requestVoicePipelineReinit) while a no-op boot restore does not.
            var pipelineChanged = false
            if (obj.has("controlMethod")) { val n = obj.getString("controlMethod"); if (n != v.voiceControlMethod) pipelineChanged = true; v.voiceControlMethod = n }
            if (obj.has("customizePipeline")) { val n = obj.getBoolean("customizePipeline"); if (n != v.customizePipeline) pipelineChanged = true; v.customizePipeline = n }
            if (obj.has("sttProvider")) { val n = obj.getString("sttProvider"); if (n != v.sttProvider) { logAccountOverride("sttProvider", v.sttProvider, n); pipelineChanged = true }; v.sttProvider = n }
            if (obj.has("ttsProvider")) { val n = obj.getString("ttsProvider"); if (n != v.ttsProvider) { logAccountOverride("ttsProvider", v.ttsProvider, n); pipelineChanged = true }; v.ttsProvider = n }
            // INTERNAL A/B toggle (not a user setting): "elevenlabs" | "inworld". Flip with
            // DashieNative.setVoiceSettings(JSON.stringify({cloudTtsVendor:'inworld'})).
            if (obj.has("cloudTtsVendor")) v.cloudTtsVendor = obj.getString("cloudTtsVendor")
            // Local TTS box (Kokoro) — used when ttsProvider=local_url. Account-configured.
            if (obj.has("localTtsUrl")) v.localTtsUrl = obj.getString("localTtsUrl")
            if (obj.has("localTtsVoiceId")) v.localTtsVoiceId = obj.getString("localTtsVoiceId")
            // Own-box Whisper URL — used when sttProvider=local_stt_url. Account-configured.
            if (obj.has("localSttUrl")) { val n = obj.getString("localSttUrl"); if (n != v.localSttUrl) pipelineChanged = true; v.localSttUrl = n }
            // HA engine-direct (ha_engine transport) — which engine + voice to hit.
            // Engine changes affect the running pipeline, so equality-gate them.
            if (obj.has("haTtsEngineId")) { val n = obj.getString("haTtsEngineId"); if (n != v.haTtsEngineId) pipelineChanged = true; v.haTtsEngineId = n }
            if (obj.has("haTtsVoiceId")) v.haTtsVoiceId = obj.getString("haTtsVoiceId")
            if (obj.has("haSttEngineId")) { val n = obj.getString("haSttEngineId"); if (n != v.haSttEngineId) pipelineChanged = true; v.haSttEngineId = n }
            // Open Brain preset (console top-level selector) — display/state key;
            // the pipeline-affecting change arrives via controlMethod/providers.
            if (obj.has("pipelinePreset")) v.pipelinePreset = obj.getString("pipelinePreset")
            // "My own AI" (BYO model): endpoint + model name. The API key NEVER
            // syncs to the device — only the derived key-set boolean for the
            // settings-UI indicator (managed in the Dashie Console).
            if (obj.has("localLlmUrl")) v.localLlmUrl = obj.getString("localLlmUrl")
            if (obj.has("localLlmModel")) v.localLlmModel = obj.getString("localLlmModel")
            if (obj.has("localLlmKeySet")) v.localLlmKeySet = obj.getBoolean("localLlmKeySet")
            // Local Engines registry (console-owned): [{id,name,kind,url,model?}]. Stored raw so
            // the settings UI can show "Piper on the Mac" instead of an IP. DISPLAY ONLY — the
            // runtime keeps reading the resolved flat keys above; we never re-derive them from
            // this array (that mapping lives once, in the console). Arrives as a JSON array, so
            // stringify it rather than getString().
            if (obj.has("localEngines")) {
                v.localEngines = obj.optJSONArray("localEngines")?.toString() ?: ""
            }
            if (obj.has("conversationModel")) v.conversationModel = obj.getString("conversationModel")
            if (obj.has("liveVoiceName")) v.conversationVoice = obj.getString("liveVoiceName")
            // BYOK-for-Live: use the household's own Gemini key for Live (device brokers an
            // ephemeral token from the box). Account-level, pushed console→native.
            // `liveByok` deliberately ignored (2026-08-01): BYOK-for-Live is no longer gated on
            // a stored flag — the device always attempts the mint and the box decides. Accepting
            // the field again would re-introduce a switch that can disagree with reality.
            if (obj.has("conversationAlways")) v.conversationAlways = obj.getBoolean("conversationAlways")
            if (obj.has("alwaysOpenDialog")) v.alwaysOpenDialog = obj.getBoolean("alwaysOpenDialog")
            // voice.alwaysUseAI is account-scoped (ACCOUNT_VOICE_KEYS) but its native
            // pref lives on AiPreferences — apply it so the native toggle reflects the
            // account value the fast-path reads.
            if (obj.has("alwaysUseAI")) {
                com.dashieapp.Dashie.halite.preferences.AiPreferences(context).alwaysUseAI = obj.getBoolean("alwaysUseAI")
            }
            if (obj.has("agentMode")) v.agentMode = obj.getString("agentMode")
            if (obj.has("responseHandling")) v.responseHandling = obj.getString("responseHandling")
            if (obj.has("displayFormat")) v.displayFormat = obj.getString("displayFormat")
            if (obj.has("confirmationToneType")) v.confirmationToneType = obj.getString("confirmationToneType")
            if (obj.has("confirmationToneVolume")) v.confirmationToneVolume = obj.getDouble("confirmationToneVolume").toFloat()
            // Reinit the running pipeline live on a real change (→ HaliteVoiceController.reinitialize()).
            if (pipelineChanged) {
                Log.i(TAG, "🎤 Voice pipeline key changed via cloud apply — requesting reinit")
                context.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_VOICE_PIPELINE_REINIT").apply {
                        setPackage(context.packageName)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse voice settings JSON", e)
        }
    }

    /**
     * Restore aiVoice (personality + ElevenLabs voice key) from a cloud
     * snapshot. Pair to ACTION_AI_VOICE_SETTINGS_CHANGED, which already
     * pushes Kotlin → cloud — closing the round-trip so a wiped device
     * gets the user's personality and voice back on next login.
     */
    fun setAiVoiceSettings(json: String) {
        Log.i(TAG, "🗣️ setAiVoiceSettings")
        val hp = halitePrefs() ?: return
        try {
            val obj = org.json.JSONObject(json)
            val aiPrefs by lazy { com.dashieapp.Dashie.halite.preferences.AiPreferences(context) }
            if (obj.has("personalityId")) {
                aiPrefs.personalityId = obj.getString("personalityId")
            }
            // Display name is a cache of the personality id (Kotlin has no
            // personalities list to resolve id→name). The native picker sets both
            // together, but a cloud→native id change came through with only the id
            // — leaving the Voice & AI summary showing a STALE name (the "shows
            // Dashie while actually cowboy" bug). JS now carries the resolved name
            // on every personality change (personality-changed → setAiVoiceSettings),
            // so keep the cached display name in lockstep with the id.
            if (obj.has("personalityDisplayName")) {
                aiPrefs.personalityDisplayName = obj.getString("personalityDisplayName")
            }
            // GAP-2: inherit flag so the Voice & AI summary can show "Default (<name>)". JS is the
            // only side that knows it (device-override mirror empty = inheriting the account default).
            if (obj.has("personalityInheriting")) {
                aiPrefs.personalityInheriting = obj.getBoolean("personalityInheriting")
            }
            // GAP-2: wake-word inherit hint + account default model id (for the "Default (<name>)"
            // summary/picker row). JS is the only side that knows the inherit state.
            if (obj.has("wakeWordInheriting")) {
                aiPrefs.wakeWordInheriting = obj.getBoolean("wakeWordInheriting")
            }
            if (obj.has("wakeWordDefaultId")) {
                aiPrefs.wakeWordDefaultId = obj.getString("wakeWordDefaultId")
            }
            // GAP-2 Piece B: cloud-voice inherit hint + account default voice KEY (for the
            // personality picker's Voice line "Default (<name>)" seed). JS is the only side
            // that knows the inherit state (device-override mirror empty = inheriting).
            if (obj.has("voiceKeyInheriting")) {
                aiPrefs.voiceKeyInheriting = obj.getBoolean("voiceKeyInheriting")
            }
            if (obj.has("voiceKeyDefaultKey")) {
                aiPrefs.voiceKeyDefaultKey = obj.getString("voiceKeyDefaultKey")
            }
            if (obj.has("model")) {
                aiPrefs.aiModel = obj.getString("model")
            }
            // ai.retrievePicturesEnabled — account-scoped (ACCOUNT_AI_KEYS); gates
            // image search in BOTH cascade (brain) and Live (relay tool). Cloud →
            // native so a console/web toggle reaches AiPreferences (Live reads it).
            if (obj.has("retrievePicturesEnabled")) {
                aiPrefs.retrievePicturesEnabled = obj.getBoolean("retrievePicturesEnabled")
            }
            // ai.promptForFeedback — account-scoped (ACCOUNT_AI_KEYS); the voice-feedback
            // thumbs prompt toggle. Cloud → native so a web/console toggle reaches
            // AiPreferences and the native Voice & AI settings reflect it.
            if (obj.has("promptForFeedback")) {
                aiPrefs.promptForFeedback = obj.getBoolean("promptForFeedback")
            }
            // ai.webSearchEnabled — account-scoped (ACCOUNT_AI_KEYS); the edge brain
            // reads user_settings.ai.webSearchEnabled. Cloud → native so the native
            // Voice & AI toggle reflects the account value.
            if (obj.has("webSearchEnabled")) {
                aiPrefs.webSearchEnabled = obj.getBoolean("webSearchEnabled")
            }
            if (obj.has("voiceKey")) hp.voice.voiceKey = obj.getString("voiceKey")
            // voiceId is the JS-side ElevenLabs voice identifier — Kotlin
            // doesn't track it independently, so it's intentionally ignored
            // here even if the cloud payload includes it.
            // wakeWord (D5) — device-scoped, owned by WakeWordModelManager (the legacy
            // changeWakeWord path is dead in overlay mode). Persist the selection; it
            // takes effect on the NEXT app launch (v1 scope — the console tells the user
            // to restart). Only write when it actually changed, to avoid churning prefs on
            // every settings push. Unknown ids fall back to the bundled model (fromId).
            if (obj.has("wakeWord")) {
                val id = obj.getString("wakeWord")
                if (id.isNotBlank() && id != wakeWordModelManager.getActiveModel().modelId) {
                    // WS-G §13.2 model-gap hardening: only apply ids this APK
                    // bundles — fromId's silent Hey Dashie fallback would swap
                    // the user's word (no-silent-degradation). JS gates on
                    // getAvailableWakeWords first; this guards old-JS pushes.
                    val known = WakeWordModel.availableModelIds().contains(id) || id == "mww_hey_dashie"
                    if (known) {
                        wakeWordModelManager.selectModelById(id)
                        Log.i(TAG, "Wake word set to '$id' (applies on next restart)")
                    } else {
                        Log.w(TAG, "Ignoring unknown wake word id '$id' — not bundled in this APK")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse aiVoice settings JSON", e)
        }
    }

    fun setVoicePipelineMode(mode: String) {
        val oldMode = halitePrefs()?.voice?.voicePipelineMode
        halitePrefs()?.voice?.voicePipelineMode = mode
        if (oldMode != mode) {
            Log.i(TAG, "Voice pipeline mode changed: $oldMode -> $mode")
            onVoicePipelineModeChanged?.invoke()
        }
    }

    fun getVoicePipelineMode(): String {
        return halitePrefs()?.voice?.voicePipelineMode ?: HalitePreferences.VOICE_PIPELINE_MODE_HA
    }

    fun setResponseHandling(mode: String) {
        halitePrefs()?.voice?.responseHandling = mode
        // Notify voice controller of the change
        val showResponses = (mode == "read_and_display" || mode == "display_only")
        onResponseHandlingChanged?.invoke(showResponses)
        Log.i(TAG, "Response handling set: $mode (showResponses=$showResponses)")
    }

    // ============================================
    // HA Pipeline Settings (for HA Assist STT)
    // ============================================

    fun getHaPipelineInfo(): String {
        val prefs = halitePrefs()
        return org.json.JSONObject().apply {
            put("id", prefs?.voice?.voicePipelineId ?: "")
            put("name", prefs?.voice?.voicePipelineName ?: "")
            put("displayName", prefs?.voice?.voicePipelineDisplayName ?: "Preferred")
        }.toString()
    }

    fun showHaPipelinePicker() {
        Log.i(TAG, "showHaPipelinePicker() called")
        webView.post {
            onShowHaPipelinePicker?.invoke { id, name ->
                Log.i(TAG, "HA pipeline selected: $id ($name)")
                // Notify webapp of the selection via CustomEvent
                val escapedId = id.replace("'", "\\'")
                val escapedName = name.replace("'", "\\'")
                val js = "window.dispatchEvent(new CustomEvent('ha-pipeline-selected', { detail: { id: '$escapedId', name: '$escapedName' } }))"
                webView.evaluateJavascript(js, null)
            }
        }
    }

    // ============================================
    // Microphone Mute (Halite voice control)
    // ============================================

    fun setMicMuted(muted: Boolean) {
        halitePrefs()?.voice?.micMuted = muted
        onMicMutedChanged?.invoke(muted)
        Log.i(TAG, "setMicMuted($muted)")
    }

    fun isMicMuted(): Boolean = halitePrefs()?.voice?.micMuted ?: false

    /** FB28: route a JS voice-feedback toast to the native card (see DashieJSBridge). */
    fun showVoiceNotice(message: String, detail: String) {
        onShowVoiceNotice?.invoke(message, detail)
    }

    /** Route the JS HA action handler's confirmation ("Turned on String Lights") to a native
     *  card that draws over the voice overlay (the JS toast was trapped behind it in the WebView). */
    fun showHaCommandResult(message: String, command: String) {
        onShowHaCommandResult?.invoke(message, command)
    }

    // ============================================
    // Sample Collection (Halite wake word training)
    // ============================================

    fun setSampleCollectionEnabled(enabled: Boolean) {
        halitePrefs()?.voice?.sampleCollectionEnabled = enabled
        onSampleCollectionChanged?.invoke(enabled)
        Log.i(TAG, "setSampleCollectionEnabled($enabled)")
    }

    fun isSampleCollectionEnabled(): Boolean = halitePrefs()?.voice?.sampleCollectionEnabled ?: false

    fun openMotionWakeSettings() {
        Log.i(TAG, "openMotionWakeSettings()")
        webView.post {
            onOpenMotionWakeSettings?.invoke()
        }
    }

    // ============================================
    // Voice License Management
    // ============================================


    /**
     * Report the ACTIVE wake model to the settings UI.
     *
     * 🔴 The name comes from `model.wakeWordName`, not a literal. This used to hard-code
     * "Hey Dashie" **while holding the model object that knows its own name** — so the UI told
     * every user the same phrase regardless of which model was loaded. Harmless while one model
     * shipped; an outright lie the moment chickadeeDev defaults to "Chickadee", and the exact
     * class of silent wrongness that had a tester reporting dead voice when voice was fine.
     */
    fun getActiveWakeWordModel(): String {
        return try {
            val model = wakeWordModelManager.getActiveModel()
            org.json.JSONObject().apply {
                put("name", model.wakeWordName)
                put("version", "v${model.version}")
                put("displayName", "${model.wakeWordName} v${model.version}")
            }.toString()
        } catch (e: Exception) {
            // Even the failure path must not name the wrong phrase — fall back to this
            // EDITION's default rather than to Dashie's.
            val fallback = wakeWordModelManager.defaultModelForEdition().wakeWordName
            org.json.JSONObject().apply {
                put("name", fallback)
                put("version", "")
                put("displayName", fallback)
            }.toString()
        }
    }

    fun showVoicePurchaseDialog() {
        webView.post {
            onToggleVoiceEnabled?.invoke()
        }
    }

    // ============================================
    // Microphone Calibration (with JS callback)
    // ============================================

    fun startMicrophoneCalibrationWithCallback(durationSeconds: Int) {
        webView.post {
            voiceAssistant()?.startMicrophoneCalibration(durationSeconds) { stats ->
                // BUNDLE-EXEMPT: onCalibrationComplete — response callback to the webapp voice-calibration page that initiated the call
                webView.evaluateJavascript("if (window.onCalibrationComplete) window.onCalibrationComplete($stats);", null)
            }
        }
    }

    // ============================================
    // Wake Word Model Management
    // ============================================

    fun listAvailableWakeWordModels(): String {
        return try {
            val models = org.json.JSONArray()
            val currentVersion = wakeWordModelManager.getCurrentVersion()
            val activeModel = wakeWordModelManager.getActiveModel()

            // Add bundled model
            val bundledVersion = com.dashieapp.Dashie.wakeword.models.WakeWordModel.BUNDLED.version
            models.put(org.json.JSONObject().apply {
                put("id", "bundled")
                put("version", bundledVersion)
                put("displayName", "Hey Dashie v$bundledVersion")
                put("subtitle", "Built-in (stable)")
                put("isBundled", true)
                put("isActive", activeModel.isBundled)
                put("needsDownload", false)
            })

            // Check for test version in manifest — cached read only. This method is a
            // synchronous JS bridge call: the WebView's JS thread blocks on the return
            // value, so it must never hit the network (a runBlocking fetch here froze
            // all page JS for up to the HTTP timeouts). The accessor refreshes the
            // cache in the background; the next query sees fresh data.
            try {
                val testVersion = wakeWordModelManager.getCachedTestVersionAndRefresh()
                if (testVersion != null) {
                    val cleanVersion = testVersion.replace("T", "")
                    val isTestActive = currentVersion == cleanVersion
                    models.put(org.json.JSONObject().apply {
                        put("id", "test")
                        put("version", cleanVersion)
                        put("displayName", "Hey Dashie v$cleanVersion")
                        put("subtitle", "Beta (testing)")
                        put("isBundled", false)
                        put("isActive", isTestActive)
                        put("needsDownload", !isTestActive)
                    })
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check for test version", e)
            }

            // Add currently downloaded model if different from bundled and test
            if (!activeModel.isBundled) {
                models.put(0, org.json.JSONObject().apply {
                    put("id", "downloaded")
                    put("version", currentVersion)
                    put("displayName", "Hey Dashie v$currentVersion")
                    put("subtitle", "Downloaded")
                    put("isBundled", false)
                    put("isActive", true)
                    put("needsDownload", false)
                })
            }

            models.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error listing wake word models", e)
            org.json.JSONArray().toString()
        }
    }

    fun downloadWakeWordModel(version: String) {
        Log.i(TAG, "Downloading wake word model v$version")

        webView.post {
            android.widget.Toast.makeText(context, "Downloading wake word model v$version...", android.widget.Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                val downloadedVersion = kotlinx.coroutines.runBlocking {
                    wakeWordModelManager.downloadTestModel()
                }

                webView.post {
                    if (downloadedVersion != null) {
                        Log.i(TAG, "Wake word model v$downloadedVersion downloaded successfully")
                        showWakeWordRestartPrompt("Hey Dashie v$downloadedVersion")

                        val script = """
                        window.dispatchEvent(new CustomEvent('wake-word-download-complete', {
                            detail: { success: true, version: '$downloadedVersion' }
                        }));
                        """
                        webView.evaluateJavascript(script, null)
                    } else {
                        android.widget.Toast.makeText(context, "Download failed", android.widget.Toast.LENGTH_SHORT).show()

                        val script = """
                        window.dispatchEvent(new CustomEvent('wake-word-download-complete', {
                            detail: { success: false, error: 'Download failed' }
                        }));
                        """
                        webView.evaluateJavascript(script, null)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading wake word model", e)
                webView.post {
                    android.widget.Toast.makeText(context, "Download error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()

                    val script = """
                    window.dispatchEvent(new CustomEvent('wake-word-download-complete', {
                        detail: { success: false, error: '${e.message}' }
                    }));
                    """
                    webView.evaluateJavascript(script, null)
                }
            }
        }.start()
    }

    fun showWakeWordRestartPrompt(modelName: String) {
        webView.post {
            val dialogView = (context as android.app.Activity).layoutInflater.inflate(
                com.dashieapp.Dashie.R.layout.dialog_simple_message, null
            )

            dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Restart Required"
            dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text =
                "Switched to $modelName.\n\nThe new wake word model will take effect after restarting the app.\n\nRestart now?"

            dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative).text = "Later"
            dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive).text = "Restart Now"

            val dialog = android.app.AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative).setOnClickListener {
                dialog.dismiss()
            }

            dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive).setOnClickListener {
                dialog.dismiss()
                onRestartApp?.invoke()
            }

            dialog.show()
            com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
            com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
        }
    }
}
