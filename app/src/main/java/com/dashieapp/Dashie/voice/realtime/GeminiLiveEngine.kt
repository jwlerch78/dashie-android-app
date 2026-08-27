package com.dashieapp.Dashie.voice.realtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.voice.CreditStateHolder
import com.dashieapp.Dashie.voice.realtime.ConversationEngine.State
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Engine A — cloud speech-to-speech via Gemini Live, tunneled through the
 * conversation-relay edge fn (build plan §3.2/§3.3). The relay keeps the Gemini
 * key server-side and meters usage per turn (#6); this client authenticates with
 * the user's Supabase JWT and speaks the Gemini Live protocol *through* the relay
 * (sends `setup`, streams mic, handles `toolCall`).
 *
 * Productionized from the RtAudioTestActivity spike: same setup/handle loop, but
 * relay transport + no device key + no client-side billing. Audio I/O and tool
 * execution are delegated to [RealtimeAudioIo] / [RealtimeToolDispatcher].
 */
class GeminiLiveEngine(
    ctx: Context,
    webViewProvider: () -> WebView? = { null },
) : ConversationEngine {

    companion object {
        private const val TAG = "GeminiLiveEngine"
        // Local barge-in on the cleaned mic. Measured: echo-cancelled floor (user silent) ~15
        // RMS; user double-talk ~1.2–3.7k but choppy (dips between words). A decay counter
        // (+2 per loud chunk, −1 per quiet) tolerates the dips and fires after ~3 loud chunks.
        // enr/energy barge-in thresholds — retained for the DISABLED moderate-volume experiment
        // (sendMicChunk now logs enr/dn but does NOT auto-fire; wake word is the trigger). On
        // device 2026-07-03 this gate false-fired: enr separates echo from non-echo but background
        // noise is also non-echo (collapses enr like speech), and loud-speaker distortion produces
        // residual that looks like near-end (enr≈0) yet is LOUDER than the user's voice — so no
        // energy+ratio threshold separates them. Kept in case we cap conversation volume later.
        private const val ENERGY_FLOOR = 1500.0
        private const val BARGE_ENR = 2.0      // echo/near-end low-freq ratio below this ⇒ near-end
        private const val BARGE_TRIGGER = 5    // sustained loud chunks before firing
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = RealtimeAudioIo(app)
    private val tools = RealtimeToolDispatcher(app, webViewProvider)
    // Wake-word barge-in (primary): "hey dashie" while Dashie speaks → interrupt. Volume-robust,
    // unlike the enr/energy detector (which false-fires on noise + loud-speaker distortion).
    private val wakeBarge = RealtimeWakeWordBargeIn(app)

    private var ws: WebSocket? = null
    private var listener: ConversationEngine.Listener? = null
    private var config: RealtimeConfig? = null
    /** One-shot guard for the 1008 stale-model retry (reset per start). */
    private var retriedDefaultModel = false

    @Volatile private var active = false
    @Volatile private var setupDone = false
    // Speculative connect: when true, setupComplete connects silently (no mic, no
    // state) and waits for beginWithText() to commit.
    @Volatile private var deferMic = false
    private var pendingBegin = false
    private var pendingBeginText: String? = null

    // Display-only usage tally (authoritative billing is server-side, #6).
    private var turns = 0
    private var totalTokens = 0

    // Screen-less lifecycle (§3.7): idle → silent close, hard cap, end_conversation tool.
    private val idleTimer = Runnable { onIdleTimeout() }
    private val capTimer = Runnable { log("max session duration reached — closing"); stop() }

    // Per-turn transcript accumulators (Gemini streams transcription in chunks).
    private val sbUser = StringBuilder()
    private val sbModel = StringBuilder()

    override val isActive: Boolean get() = active

    @Volatile override var closedByIdle: Boolean = false
        private set

    override fun start(config: RealtimeConfig, listener: ConversationEngine.Listener, deferMic: Boolean) {
        if (active) return
        this.config = config
        retriedDefaultModel = false
        this.listener = listener
        this.deferMic = deferMic
        pendingBegin = false; pendingBeginText = null
        closedByIdle = false
        active = true; setupDone = false; turns = 0; totalTokens = 0
        if (!deferMic) emit(State.CONNECTING)   // speculative connect stays silent until committed
        openSocket(config)
    }

    /** Commit a speculative (deferMic) session: start the mic + send [text] as turn 1.
     *  Ordered against setupComplete — runs immediately if already connected, else
     *  the setupComplete handler runs it. */
    override fun beginWithText(text: String?): Boolean {
        // Re-check liveness HERE rather than trusting the caller's earlier isActive read: the
        // relay can close in between, and that close is handled on a posted task. Returning
        // false lets the caller open a fresh session instead of talking to a dead socket.
        if (!active) {
            Log.w(TAG, "DROP: beginWithText on a dead Live session — socket closed between the " +
                "caller's liveness check and commit. Returning false so the caller falls back " +
                "to a fresh session; delivering it here would be silent dead air.")
            return false
        }
        main.post {
            if (!active) {
                Log.w(TAG, "DROP: Live session died before the deferred commit ran — the " +
                    "utterance was not delivered.")
                return@post
            }
            pendingBeginText = text
            pendingBegin = true
            if (setupDone) commitDeferred()
        }
        return true
    }

    private fun commitDeferred() {
        if (!pendingBegin) return
        pendingBegin = false
        activate(pendingBeginText)   // already connected — go straight to listening
        pendingBeginText = null
    }

    /** Start the mic + optionally send the first turn. Shared by the immediate and
     *  the deferred (speculative) paths. */
    private fun activate(firstText: String?) {
        if (!audio.startCapture { pcm -> sendMicChunk(pcm) }) {
            err("microphone unavailable"); stop(); return
        }
        wakeBarge.init()   // wake-word barge-in on the cleaned mic (inert if the model won't load)
        firstText?.takeIf { it.isNotBlank() }?.let {
            log("sending initial command: \"${it.take(60)}\"")
            sendUserText(it)
            // Text input isn't transcribed back by the model, so surface it ourselves
            // as the user's turn — otherwise the sidebar shows no prompt for turn 1.
            main.post { listener?.onTranscript(ConversationEngine.Speaker.USER, it, true) }
        }
        armIdle()
        emit(State.LISTENING)
    }

    override fun stop() {
        if (!active && ws == null) return
        active = false; setupDone = false
        main.removeCallbacks(idleTimer)
        main.removeCallbacks(capTimer)
        wakeBarge.close()
        audio.stop()
        try { ws?.close(1000, "stopped") } catch (_: Exception) {}
        ws = null
        emit(State.CLOSED)
    }

    // ── relay socket ─────────────────────────────────────────────────────

    private fun openSocket(cfg: RealtimeConfig) {
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        val url = buildString {
            append("${cfg.relayUrl}?model=${enc(cfg.model)}&session_id=${enc(cfg.sessionId)}")
            cfg.timezone?.takeIf { it.isNotBlank() }?.let { append("&tz=${enc(it)}") }   // → relay tool ctx (tz-correct times)
            cfg.location?.takeIf { it.isNotBlank() }?.let { append("&loc=${enc(it)}") }
            if (!cfg.retrievePictures) append("&retrieve_pictures=0")   // gate off → relay drops the image_search tool
        }
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${cfg.jwt}")   // user JWT → gateway verify + usage attribution
            .addHeader("apikey", cfg.anonKey)                  // gateway routing
            // BYOK-for-Live: hand the relay a Live-only ephemeral token (never the raw key, never
            // in the URL). Present → relay opens the BYOK upstream + skips the AI debit; absent →
            // Dashie's key as today. Passed as a header so it stays out of logs/query strings.
            .apply { cfg.liveToken?.takeIf { it.isNotBlank() }?.let { addHeader("x-dashie-live-token", it) } }
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            // Socket-identity guard: this engine instance is REUSED across conversations, so a
            // late event from a previous conversation's socket must not stop() or inject stale
            // content into the current one. onOpen sends setup on ITS OWN socket, so it needs
            // no guard (a stale open writes to the dying socket, not ours). Master plan WS-B.4b.
            private fun isStale(webSocket: WebSocket) = webSocket !== this@GeminiLiveEngine.ws

            override fun onOpen(webSocket: WebSocket, response: Response) {
                log("relay socket open — sending setup")
                webSocket.send(setupMessage(cfg))
            }
            override fun onMessage(webSocket: WebSocket, text: String) { if (!isStale(webSocket)) handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { if (!isStale(webSocket)) handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (isStale(webSocket)) return
                log("relay closing $code $reason")
                // 1008 "Requested entity was not found" at setup = the pinned Gemini Live
                // MODEL no longer exists (Google retires preview aliases; a stale
                // conversationModel pref then killed every session SILENTLY — found live
                // 2026-07-20, pref pinned gemini-2.5-flash-native-audio-latest). Self-heal:
                // retry ONCE with the current default model — no model allowlist to rot.
                val staleCfg = config
                if (code == 1008 && reason.contains("not found", ignoreCase = true) &&
                    staleCfg != null && staleCfg.model != RealtimeConfig.DEFAULT_MODEL && !retriedDefaultModel) {
                    retriedDefaultModel = true
                    Log.w(TAG, "DROP→retry: Live model '${staleCfg.model}' rejected (1008) — retrying with default '${RealtimeConfig.DEFAULT_MODEL}'. Fix the conversationModel setting.")
                    main.post {
                        if (!active) { emit(State.CLOSED); return@post }
                        val healed = staleCfg.copy(model = RealtimeConfig.DEFAULT_MODEL)
                        config = healed
                        openSocket(healed)
                    }
                    return
                }
                main.post { if (active) stop() else emit(State.CLOSED) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (isStale(webSocket)) { log("stale socket failure ignored: ${t.message}"); return }
                Log.w(TAG, "WS failure: ${t.message}")
                main.post { err(t.message ?: "connection failed"); if (active) stop() }
            }
        })
    }

    private fun setupMessage(cfg: RealtimeConfig): String {
        val tools = JSONArray()
            .put(JSONObject().put("googleSearch", JSONObject()))   // live web grounding (§3.4)
            .put(JSONObject().put("functionDeclarations", RealtimeToolDispatcher.functionDeclarationsFor(cfg.clientTools)))
        val setup = JSONObject()
            .put("model", "models/${cfg.model}")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                // Pin the user-selected Google voice (voice.liveVoiceName), else the
                // default — so it doesn't vary per session. Resolved in RealtimeConfig.
                .put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", cfg.voiceName)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", cfg.systemContext))))
            .put("tools", tools)
            // Conservative server VAD. The mic is muted while Dashie speaks (half-duplex), so the
            // server only ever sees user audio between turns; LOW sensitivity + longer windows
            // keep any residual room echo on turn boundaries from self-triggering. §echo-mitigation
            .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject()
                .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                .put("prefixPaddingMs", 300)
                .put("silenceDurationMs", 800)))
            // Stream text transcripts of both sides for on-screen display + relay
            // logging to ai_interactions (empty object = enable).
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    // ── inbound frames (relay control + Gemini protocol) ─────────────────

    private fun handle(raw: String) {
        val msg = try { JSONObject(raw) } catch (_: Exception) { return }

        // Relay control frames (text): readiness + cap warning (§3.3).
        when (msg.optString("type")) {
            "relay_ready" -> { log("relay_ready (${msg.optString("model")})"); return }
            "relay_expiring" -> { log("relay_expiring — session nearing edge-fn cap"); return } // #3: reconnect/resume
            // Structured relay reject (CR2 Live boundary): the relay gates on credits at
            // connection accept and delivers this frame before close(1008). Route it to the
            // credit machinery (markExhausted → onExhausted → prompt-to-choose), NOT the
            // generic error line — same treatment as the STT WS-reject (VPC precedent).
            "error" -> {
                if (msg.optString("code") == "insufficient_credits") {
                    log("relay rejected: insufficient_credits — raising CR2 prompt")
                    val bal = msg.optDouble("balance", Double.NaN)
                    CreditStateHolder.markExhausted(bal.takeIf { it.isFinite() })
                    main.post { if (active) stop() }
                } else {
                    err(msg.optString("message").ifEmpty { "relay error" })
                }
                return
            }
            // A registry tool the relay ran server-side produced a card (e.g. sports).
            "tool_card" -> { msg.optJSONObject("card")?.let { c -> main.post { listener?.onCard(c) } }; return }
            // Diagnostic mirror of a relay-side tool call (edge-fn logs aren't CLI-readable).
            "tool_debug" -> { log("TOOL ${msg.optString("name")} args=${msg.optJSONObject("args")} found=${msg.optBoolean("found")} voice=\"${msg.optString("voice")}\""); return }
        }

        if (msg.has("setupComplete")) {
            setupDone = true
            log("setupComplete")
            audio.startPlayback()
            config?.let { main.postDelayed(capTimer, it.maxSessionMs) }   // hard safety cap
            if (deferMic) {
                // Speculative connect: stay silent until beginWithText() commits.
                if (pendingBegin) commitDeferred()
            } else {
                // Immediate path: start the mic + send the pre-captured first command
                // (so a command spoken before the channel was ready isn't lost).
                activate(config?.initialText)
            }
            return
        }

        msg.optJSONObject("usageMetadata")?.let { updateUsage(it) }

        if (msg.has("toolCall")) { handleToolCall(msg.getJSONObject("toolCall")); return }

        val sc = msg.optJSONObject("serverContent") ?: return

        // Live transcript (both sides) for on-screen display. Streams in chunks;
        // emit the running text now and a final on turnComplete.
        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            cancelIdle()   // user is speaking a follow-up → don't time out mid-utterance
            if (sbUser.isEmpty()) log("input transcription started")
            // Client-side barge-in. Gemini generates the whole reply faster than real-time and
            // marks the turn complete while seconds of its audio are still buffered locally — so
            // the server won't send `interrupted`, and the user's voice would just be transcribed
            // while Dashie keeps talking. When we see the user speaking over still-playing audio,
            // flush it ourselves so Dashie stops the moment the user talks.
            if (audio.playbackRemainingMs() > 150) {
                log("barge-in (client)"); audio.flushPlayback(); emit(State.LISTENING)
                // The barged-into turn may never get turnComplete — finalize + clear the
                // per-turn transcript state NOW, or the next turn's text appends onto the
                // dead turn's ("…find a picture thatThat's a really interesting…" display
                // bug + stale YOU-SAID bubble, 2026-07-12). The incoming chunk below then
                // starts the new user bubble clean.
                finalizeTurnTranscripts()
            }
            sbUser.append(it); val s = sbUser.toString(); main.post { listener?.onTranscript(ConversationEngine.Speaker.USER, s, false) }
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            sbModel.append(it); val s = prettifyTimes(sbModel.toString()); main.post { listener?.onTranscript(ConversationEngine.Speaker.DASHIE, s, false) }
        }

        if (sc.optBoolean("interrupted", false)) {
            log("server interrupted")
            audio.flushPlayback()
            setSuppress(false)   // server killed the old turn; fresh audio may now play
            // An interrupted turn gets NO turnComplete — do the same per-turn transcript
            // reset here, or the next turn's text appends onto the dead turn's.
            finalizeTurnTranscripts()
            emit(State.LISTENING)
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val b64 = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data", "") ?: ""
                if (b64.isNotEmpty()) {
                    // After a local barge-in, drop the interrupted reply's still-streaming audio
                    // until the server catches up (interrupted/turnComplete) — otherwise it keeps
                    // refilling the buffer and Dashie talks over the user.
                    if (suppressOutput) { if (!loggedDrop) { log("model audio dropped (suppressed)"); loggedDrop = true }; continue }
                    loggedDrop = false
                    // Audio output means a turn is in progress → cancel any pending idle close.
                    cancelIdle()
                    emit(State.SPEAKING)
                    audio.writePlayback(Base64.decode(b64, Base64.DEFAULT))
                }
            }
        }
        if (sc.optBoolean("turnComplete", false)) {
            log("turnComplete")
            setSuppress(false)
            finalizeTurnTranscripts()
            emit(State.LISTENING); armIdle()
        }
    }

    /** End-of-turn transcript housekeeping: emit finals for whatever streamed in, then
     *  clear the accumulators. Called on turnComplete AND on both interruption paths
     *  (server `interrupted`, local barge-in) — an interrupted turn never gets a
     *  turnComplete, and stale buffers make the next turn's text append onto the dead
     *  turn's on screen. */
    private fun finalizeTurnTranscripts() {
        if (sbUser.isNotEmpty()) { val s = sbUser.toString(); main.post { listener?.onTranscript(ConversationEngine.Speaker.USER, s, true) } }
        if (sbModel.isNotEmpty()) { val s = prettifyTimes(sbModel.toString()); main.post { listener?.onTranscript(ConversationEngine.Speaker.DASHIE, s, true) } }
        sbUser.clear(); sbModel.clear()
    }

    /** Clean spoken-style times in the model's output transcription for on-screen display:
     *  "4 30 p m" → "4:30 PM", "1 p m" → "1 PM". Gemini's audio transcription drops the
     *  colon and spells out "p m"; the spoken audio is unaffected — this is display-only. */
    private fun prettifyTimes(s: String): String = s
        .replace(Regex("\\b(\\d{1,2}) (\\d{2}) ([AaPp]) ?[Mm]\\b")) { m ->
            "${m.groupValues[1]}:${m.groupValues[2]} ${m.groupValues[3].uppercase()}M"
        }
        .replace(Regex("\\b(\\d{1,2}) ([AaPp]) ?[Mm]\\b")) { m ->
            "${m.groupValues[1]} ${m.groupValues[2].uppercase()}M"
        }

    private fun handleToolCall(toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: JSONArray()
        val responses = JSONArray()
        var endRequested = false
        for (i in 0 until calls.length()) {
            val fc = calls.getJSONObject(i)
            val name = fc.optString("name")
            val id = fc.optString("id")
            log("tool: $name")
            if (name == RealtimeToolDispatcher.END_CONVERSATION) endRequested = true
            val out = tools.dispatch(name, fc.optJSONObject("args") ?: JSONObject())
            // A tool that started music or put a feed on screen closes the conversation after
            // the reply — parity with cascade (2026-07-21). Same close path as end_conversation.
            if (out.endAfterReply) { log("tool '$name' started media/screen — closing after reply"); endRequested = true }
            val resp = JSONObject().put("name", name).put("response", JSONObject().put("result", out.model))
            if (id.isNotEmpty()) resp.put("id", id)
            responses.put(resp)
            // A device tool (e.g. calendar) may also return a card to render on screen.
            out.card?.let { c -> main.post { listener?.onCard(c) } }
        }
        try { ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString()) } catch (_: Exception) {}
        // The model asked to wrap up — let its closing reply play briefly, then close.
        if (endRequested) { log("end_conversation — closing"); main.postDelayed({ stop() }, 2500) }
    }

    // ── lifecycle timers (§3.7) ──────────────────────────────────────────

    private fun armIdle() {
        val cfg = config ?: return
        main.removeCallbacks(idleTimer)
        // Start the idle window only AFTER the queued reply finishes playing, so
        // the user gets the full idle window after Dashie stops talking (not from
        // when the audio finished arriving, which is much earlier).
        main.postDelayed(idleTimer, audio.playbackRemainingMs() + cfg.idleMs)
    }

    private fun cancelIdle() = main.removeCallbacks(idleTimer)

    /** Silence after a reply → just close (no "still there?" prompt — a voice
     *  exchange should time out quietly when there's no more dialogue). */
    private fun onIdleTimeout() {
        if (!active) return
        log("idle — closing conversation")
        closedByIdle = true   // UI leaves the last response up rather than tearing down
        stop()
    }

    /** Inject a synthetic user turn (used for the captured first command). */
    private fun sendUserText(text: String) {
        val frame = JSONObject().put("clientContent", JSONObject()
            .put("turns", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", text)))))
            .put("turnComplete", true))
        try { ws?.send(frame.toString()) } catch (_: Exception) {}
    }

    // ── outbound mic ─────────────────────────────────────────────────────

    // After a local barge-in, DROP the rest of the interrupted reply's audio (it streams in
    // near-real-time, so flushing the ~300 ms buffer alone doesn't stop it). Cleared when the
    // server acknowledges (interrupted) or the turn completes — OR by a safety timeout, since
    // the barged-into turn may have already sent turnComplete (model generates ahead), leaving
    // nothing to clear it and muting every later reply.
    @Volatile private var suppressOutput = false
    private var loggedDrop = false   // throttle the "model audio dropped" diagnostic
    private val clearSuppress = Runnable {
        if (suppressOutput) { suppressOutput = false; log("suppress cleared (timeout)") }
    }
    private fun setSuppress(on: Boolean) {
        suppressOutput = on
        main.removeCallbacks(clearSuppress)
        if (on) main.postDelayed(clearSuppress, 1500)   // never stay muted beyond this
    }

    private fun sendMicChunk(pcm: ByteArray) {
        if (!setupDone) return
        // Wake-word barge-in (primary, volume-robust). The server's interrupt and
        // inputTranscription land only when its VAD commits the user's turn (≈ when the user
        // stops) — too late to stop a reply mid-stream. So detect locally: feed the AEC3-cleaned
        // mic to the "hey dashie" spotter every chunk (continuous → its streaming state stays
        // warm), and when it fires while Dashie is still playing, flush + suppress, which opens
        // the mic gate so the real utterance flows to the server. Unlike the enr/energy detector
        // this survives loud playback (recognizes the word THROUGH the residual echo).
        if (wakeBarge.feed(pcm) && audio.playbackRemainingMs() > 200 && !suppressOutput) {
            log("barge-in (wake word)"); setSuppress(true); audio.flushPlayback(); emit(State.LISTENING)
        }
        var out = pcm
        if (audio.playbackRemainingMs() > 200 && !suppressOutput) {
            // Mute the outbound mic while Dashie speaks (send silence → no self-hearing); the
            // wake word above is what re-opens the gate. The enr/dn ratio + rms are LOGGED ONLY
            // (auto-fire disabled): they false-trigger on background noise and loud-speaker
            // distortion (20260703_BARGEIN_ESCALATION_HANDOFF). Kept for the moderate-volume
            // experiment and on-device comparison; revisit if we cap conversation volume.
            val rms = chunkRms(pcm)
            val st = audio.aecStats()
            val rel = st?.getOrNull(0) ?: -1.0
            val erle = st?.getOrNull(1) ?: -1.0
            val dn = st?.getOrNull(3) ?: -1.0
            val enr = st?.getOrNull(4) ?: -1.0
            val snr = st?.getOrNull(5) ?: -1.0
            Log.i(TAG, "rt-mic rms=%.0f rel=%.2f erle=%.1f dn=%.0f enr=%.3f snr=%.1f"
                .format(rms, rel, erle, dn, enr, snr))
            out = ByteArray(pcm.size)   // muted until a barge-in opens the gate
        }
        val b64 = Base64.encodeToString(out, Base64.NO_WRAP)
        val frame = JSONObject().put("realtimeInput", JSONObject().put(
            "audio", JSONObject()
                .put("mimeType", "audio/pcm;rate=${RealtimeAudioIo.IN_RATE}")
                .put("data", b64)))
        try { ws?.send(frame.toString()) } catch (_: Exception) {}
    }

    /** RMS of a PCM16 little-endian chunk (for local barge-in energy detection). */
    private fun chunkRms(pcm: ByteArray): Double {
        var sum = 0.0; var n = 0; var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            sum += (s.toDouble() * s.toDouble()); n++; i += 2
        }
        return if (n > 0) Math.sqrt(sum / n) else 0.0
    }

    // ── usage display (server bills; this is informational) ──────────────

    private fun updateUsage(u: JSONObject) {
        fun tok(key: String, modality: String): Int {
            val arr = u.optJSONArray(key) ?: return 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("modality").equals(modality, true)) return o.optInt("tokenCount", 0)
            }
            return 0
        }
        val aIn = tok("promptTokensDetails", "AUDIO"); val tIn = tok("promptTokensDetails", "TEXT")
        val aOut = tok("responseTokensDetails", "AUDIO"); val tOut = tok("responseTokensDetails", "TEXT")
        val turnTotal = u.optInt("totalTokenCount", aIn + tIn + aOut + tOut)
        turns++; totalTokens += turnTotal
        val summary = "Turn %d: %,d tok (aIn %,d/aOut %,d · tIn %,d/tOut %,d) · session %,d tok"
            .format(turns, turnTotal, aIn, aOut, tIn, tOut, totalTokens)
        main.post { listener?.onUsage(summary) }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun emit(state: State) = main.post { listener?.onStateChanged(state) }
    private fun log(s: String) { Log.i(TAG, s); main.post { listener?.onLog(s) } }
    private fun err(s: String) { Log.w(TAG, s); main.post { listener?.onError(s) } }
}
