package com.dashieapp.Dashie.halite.mqtt

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * Handles incoming MQTT commands from the broker.
 *
 * Subscribes to `{baseTopic}/command` and dispatches JSON command payloads
 * to existing Dashie capabilities via lambda callbacks.
 *
 * Command format: JSON object with one or more command keys.
 * Multiple commands in a single payload are supported.
 *
 * Compatible with WallPanel command format for migration ease.
 */
class MqttCommandHandler {
    companion object {
        private const val TAG = "MqttCommandHandler"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Callbacks wired by HaliteComponentWiring to existing app capabilities
    var onWakeScreen: (() -> Unit)? = null
    var onSleepScreen: (() -> Unit)? = null
    var onSetBrightness: ((Int) -> Unit)? = null
    var onSetVolume: ((Int) -> Unit)? = null
    var onLoadUrl: ((String) -> Unit)? = null
    var onReload: (() -> Unit)? = null
    var onRelaunch: (() -> Unit)? = null
    var onSpeak: ((String) -> Unit)? = null
    var onPlayAudio: ((String) -> Unit)? = null
    var onClearCache: (() -> Unit)? = null
    var onStartCamera: (() -> Unit)? = null
    var onStopCamera: (() -> Unit)? = null

    /**
     * Process an incoming MQTT message on the command topic.
     * Called by DashieMqttService.onMessageReceived when topic matches `{base}/command`.
     */
    fun handleMessage(topic: String, payload: String) {
        try {
            val json = JSONObject(payload)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                handleCommand(key, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command: ${e.message} — payload: ${payload.take(200)}")
        }
    }

    private fun handleCommand(command: String, json: JSONObject) {
        Log.d(TAG, "Command: $command")

        mainHandler.post {
            try {
                when (command) {
                    "wake" -> {
                        if (json.optBoolean("wake", true)) onWakeScreen?.invoke()
                        else onSleepScreen?.invoke()
                    }
                    "screenOn" -> {
                        if (json.optBoolean("screenOn", true)) onWakeScreen?.invoke()
                        else onSleepScreen?.invoke()
                    }
                    "brightness" -> {
                        val level = json.optInt("brightness", -1)
                        if (level in 0..255) onSetBrightness?.invoke(level)
                    }
                    "volume" -> {
                        val level = json.optInt("volume", -1)
                        if (level in 0..100) onSetVolume?.invoke(level)
                    }
                    "url" -> {
                        val url = json.optString("url", "")
                        if (url.isNotEmpty()) onLoadUrl?.invoke(url)
                    }
                    "reload" -> {
                        if (json.optBoolean("reload", false)) onReload?.invoke()
                    }
                    "relaunch" -> {
                        if (json.optBoolean("relaunch", false)) onRelaunch?.invoke()
                    }
                    "speak" -> {
                        val text = json.optString("speak", "")
                        if (text.isNotEmpty()) onSpeak?.invoke(text)
                    }
                    "audio" -> {
                        val url = json.optString("audio", "")
                        if (url.isNotEmpty()) onPlayAudio?.invoke(url)
                    }
                    "clearCache" -> {
                        if (json.optBoolean("clearCache", false)) onClearCache?.invoke()
                    }
                    "camera" -> {
                        if (json.optBoolean("camera", false)) onStartCamera?.invoke()
                        else onStopCamera?.invoke()
                    }
                    else -> Log.w(TAG, "Unknown command: $command")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing '$command': ${e.message}")
            }
        }
    }

    fun destroy() {
        onWakeScreen = null
        onSleepScreen = null
        onSetBrightness = null
        onSetVolume = null
        onLoadUrl = null
        onReload = null
        onRelaunch = null
        onSpeak = null
        onPlayAudio = null
        onClearCache = null
        onStartCamera = null
        onStopCamera = null
    }
}
