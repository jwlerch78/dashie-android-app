package com.dashieapp.Dashie.halite.voice.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Probes the configured local Kokoro (OpenAI-compatible) TTS box for its voices:
 *   GET {localTtsUrl}/v1/audio/voices  → [{id,name}]  (also tolerates {voices:[...]} / {data:[...]}
 *   and bare id-string arrays, since kokoro-fastapi shapes have varied). Simpler than Piper — a
 *   plain HTTP GET, no auth. voice ids (af_heart, bf_lily) map 1:1 to voice.localTtsVoiceId.
 * Callbacks on the MAIN thread, exactly one fires. GAP-2 voice Piece A (Kokoro). Endpoint shape
 * documented in memory reference_mac_local_voice_services; validate live once the box is up.
 */
class KokoroVoiceProbe(private val context: Context) {

    data class Voice(val id: String, val name: String)

    companion object { private const val TAG = "KokoroVoiceProbe" }

    private val main = Handler(Looper.getMainLooper())
    private val http = LocalHostsTrustingHttpClient.builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun probe(onVoices: (List<Voice>) -> Unit, onError: (String) -> Unit) {
        val base = HalitePreferences(context).voice.localTtsUrl.trim().trimEnd('/')
        if (base.isEmpty()) { main.post { onError("No local TTS box configured") }; return }

        val fired = AtomicBoolean(false)
        val fail: (String) -> Unit = { e -> if (fired.compareAndSet(false, true)) main.post { onError(e) } }
        val ok: (List<Voice>) -> Unit = { v -> if (fired.compareAndSet(false, true)) main.post { onVoices(v) } }

        val req = Request.Builder().url("$base/v1/audio/voices").get().build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { fail("Could not reach the local TTS box: ${e.message}") }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) { fail("Local TTS box returned HTTP ${it.code}"); return }
                        ok(parse(it.body?.string().orEmpty()))
                    }
                } catch (e: Exception) { fail("Voice list parse error: ${e.message}") }
            }
        })
    }

    private fun parse(body: String): List<Voice> {
        val trimmed = body.trim()
        val arr: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val o = JSONObject(trimmed)
                o.optJSONArray("voices") ?: o.optJSONArray("data") ?: JSONArray()
            }
            else -> JSONArray()
        }
        val out = mutableListOf<Voice>()
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is String -> if (item.isNotBlank()) out.add(Voice(item, item))
                is JSONObject -> {
                    val id = item.optString("id").ifBlank { item.optString("voice_id") }
                    if (id.isNotBlank()) out.add(Voice(id, item.optString("name").ifBlank { id }))
                }
            }
        }
        Log.i(TAG, "Probed ${out.size} Kokoro voices")
        return out
    }
}
