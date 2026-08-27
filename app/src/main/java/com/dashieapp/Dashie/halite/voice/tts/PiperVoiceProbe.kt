package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Probes the configured HA Piper engine for its available voices via the HA WebSocket API,
 * reusing the device's stored HA base URL + access token (the same creds HaTtsEngineDirectClient
 * uses). Sequence (validated 2026-07-23 against ha.dashieapp.com):
 *   auth_required → {type:auth} → auth_ok
 *   → tts/engine/list (only if no engine configured) → pick a "piper" engine
 *   → tts/engine/voices {engine_id, language} → result.voices[] = {voice_id, name}
 * `voice_id` is exactly the voice.haTtsVoiceId format (en_US-amy-low) — no mapping.
 * One-shot: opens a WS, resolves, closes. Callbacks fire on the MAIN thread; exactly one of
 * onVoices/onError fires. GAP-2 voice Piece A.
 */
class PiperVoiceProbe(private val context: Context) {

    data class Voice(val id: String, val name: String)

    companion object {
        private const val TAG = "PiperVoiceProbe"
        private const val DEFAULT_LANGUAGE = "en_US"
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null

    fun probe(onVoices: (List<Voice>) -> Unit, onError: (String) -> Unit) {
        val prefs = HalitePreferences(context)
        val base = prefs.connection.haBaseUrl.trimEnd('/')
        val token = prefs.connection.haAccessToken
        // May be blank (engine not explicitly configured) → we discover a piper engine via list.
        val configuredEngine = prefs.voice.haTtsEngineId
        if (base.isEmpty() || token.isEmpty()) { main.post { onError("Home Assistant not connected") }; return }

        val fired = AtomicBoolean(false)
        val fail: (String) -> Unit = { e ->
            if (fired.compareAndSet(false, true)) main.post { onError(e) }
            ws?.close(1000, null)
        }
        val succeed: (List<Voice>) -> Unit = { v ->
            if (fired.compareAndSet(false, true)) main.post { onVoices(v) }
            ws?.close(1000, null)
        }

        var msgId = 0
        var listId = -1
        var voicesId = -1
        var resolvedEngine = configuredEngine

        val req = Request.Builder().url("$base/api/websocket").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val o = JSONObject(text)
                    when (o.optString("type")) {
                        "auth_required" ->
                            webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                        "auth_invalid" -> fail("HA rejected the access token")
                        "auth_ok" -> {
                            if (resolvedEngine.isNotBlank()) {
                                voicesId = ++msgId
                                webSocket.send(voicesRequest(voicesId, resolvedEngine))
                            } else {
                                listId = ++msgId
                                webSocket.send(JSONObject().put("id", listId).put("type", "tts/engine/list").toString())
                            }
                        }
                        "result" -> {
                            val id = o.optInt("id", -1)
                            if (!o.optBoolean("success", false)) {
                                fail("HA: ${o.optJSONObject("error")?.optString("message") ?: "request failed"}"); return
                            }
                            val result = o.optJSONObject("result")
                            when (id) {
                                listId -> {
                                    val providers = result?.optJSONArray("providers")
                                    var pick = ""
                                    if (providers != null) for (i in 0 until providers.length()) {
                                        val eid = providers.optJSONObject(i)?.optString("engine_id") ?: continue
                                        if (eid.contains("piper", ignoreCase = true)) { pick = eid; break }
                                    }
                                    if (pick.isBlank()) { fail("No Piper engine found on this Home Assistant"); return }
                                    resolvedEngine = pick
                                    voicesId = ++msgId
                                    webSocket.send(voicesRequest(voicesId, pick))
                                }
                                voicesId -> {
                                    val arr = result?.optJSONArray("voices")
                                    val list = mutableListOf<Voice>()
                                    if (arr != null) for (i in 0 until arr.length()) {
                                        val vo = arr.optJSONObject(i) ?: continue
                                        val vid = vo.optString("voice_id")
                                        if (vid.isBlank()) continue
                                        list.add(Voice(vid, vo.optString("name").ifBlank { vid }))
                                    }
                                    Log.i(TAG, "Probed ${list.size} Piper voices from '$resolvedEngine'")
                                    succeed(list)
                                }
                            }
                        }
                    }
                } catch (e: Exception) { fail("Voice probe parse error: ${e.message}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail("Could not reach Home Assistant: ${t.message}")
            }
        })
    }

    private fun voicesRequest(id: Int, engineId: String): String =
        JSONObject().put("id", id).put("type", "tts/engine/voices")
            .put("engine_id", engineId).put("language", DEFAULT_LANGUAGE).toString()
}
