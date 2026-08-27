package com.dashieapp.Dashie.devtools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.voice.realtime.ConversationEngine
import com.dashieapp.Dashie.voice.realtime.GeminiLiveEngine
import com.dashieapp.Dashie.voice.realtime.RealtimeConfig

/**
 * Dev-only RT Audio Test — now exercises the PRODUCTION realtime path: the
 * shared [GeminiLiveEngine] talking to Gemini Live *through the conversation-relay*
 * (build plan §3.3), authenticated with the device's cached Supabase JWT. The
 * relay keeps the key server-side and meters usage per turn (#6) — no device key.
 *
 * This screen is just a thin harness over the engine (start/stop + status/usage/
 * log views) so we can judge latency / echo / barge-in on real hardware before
 * the real voice trigger + lifecycle (#3) land. Staging/local flavors only.
 */
class RtAudioTestActivity : AppCompatActivity() {

    companion object {
        private const val REQ_MIC = 7001
    }

    private lateinit var statusView: TextView
    private lateinit var usageView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var toggle: Button

    private val engine: ConversationEngine by lazy { GeminiLiveEngine(this) }

    private val listener = object : ConversationEngine.Listener {
        override fun onStateChanged(state: ConversationEngine.State) {
            status(when (state) {
                ConversationEngine.State.CONNECTING -> "Connecting…"
                ConversationEngine.State.LISTENING -> "Listening…"
                ConversationEngine.State.SPEAKING -> "Speaking…"
                ConversationEngine.State.INTERRUPTED -> "Listening…"
                ConversationEngine.State.CLOSED -> "Idle"
                ConversationEngine.State.ERROR -> "Error"
            })
            if (state == ConversationEngine.State.CLOSED) toggle.text = "Start"
        }
        override fun onTranscript(speaker: ConversationEngine.Speaker, text: String, isFinal: Boolean) {
            // Show the finalized line per turn (partials would spam the log).
            if (isFinal && text.isNotBlank()) {
                val who = if (speaker == ConversationEngine.Speaker.USER) "🧑 You" else "💬 Dashie"
                log("$who: $text")
            }
        }
        override fun onUsage(summary: String) { usageView.text = summary }
        override fun onLog(line: String) { log(line) }
        override fun onError(message: String) { status("Error: $message"); log("⚠️ $message") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rt_audio_test)
        statusView = findViewById(R.id.rtStatus)
        usageView = findViewById(R.id.rtUsage)
        logView = findViewById(R.id.rtLog)
        logScroll = findViewById(R.id.rtLogScroll)
        toggle = findViewById(R.id.rtToggle)
        toggle.setOnClickListener { if (engine.isActive) stopSession() else startSession() }
    }

    override fun onDestroy() { engine.stop(); super.onDestroy() }

    // ── session control ──────────────────────────────────────────────────

    private fun startSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        // Resolve the relay config from the cached Supabase JWT (populated by the
        // app's WebView after sign-in). This dev screen has no WebView to refresh
        // from, so a missing/expired token means "open the app + sign in first".
        val config = RealtimeConfig.fromCachedJwt(this)
        if (config == null) {
            status("No valid Supabase login — open the dashboard and sign in first")
            log("⚠️ no cached Supabase JWT (or expired); relay needs a logged-in session")
            return
        }
        usageView.text = "Usage: —"
        toggle.text = "Stop"
        log("starting realtime session via relay (model=${config.model}, session=${config.sessionId})")
        engine.start(config, listener)
    }

    private fun stopSession() {
        toggle.text = "Start"
        engine.stop()
    }

    // ── permissions + ui helpers ─────────────────────────────────────────

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        if (rc == REQ_MIC && r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) startSession()
        else if (rc == REQ_MIC) status("Mic permission denied")
    }

    private fun status(s: String) { statusView.text = s }

    private fun log(s: String) {
        logView.append("$s\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
