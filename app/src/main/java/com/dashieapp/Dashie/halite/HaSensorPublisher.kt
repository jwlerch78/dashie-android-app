package com.dashieapp.Dashie.halite

import com.dashieapp.Dashie.edition.ApiPaths

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tracks motion and face detection state for publishing to Home Assistant
 * via the Dashie HA custom integration.
 *
 * Uses a dual approach for maximum responsiveness:
 * 1. **Push (instant):** POSTs state changes to /api/dashie/sensor_push on the HA instance
 *    for near-instant binary_sensor updates (~50-200ms).
 * 2. **Poll (fallback):** DeviceInfoHandler still exposes these values in deviceInfo JSON,
 *    so the HA integration's 5-second poll picks up changes even if push fails.
 *
 * State machine per sensor:
 *   off -> detection fires -> immediately set "on", start cooldown timer
 *   on  -> detection fires again -> reset cooldown timer (stay "on")
 *   on  -> cooldown expires with no new detection -> set "off"
 */
class HaSensorPublisher {
    companion object {
        private const val TAG = "HaSensorPublisher"
        private const val MOTION_COOLDOWN_MS = 10_000L
        private const val FACE_COOLDOWN_MS = 5_000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    private val handler = Handler(Looper.getMainLooper())

    // Accepts self-signed certs for LAN HA URLs.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    // Public readable state (read by DeviceInfoHandler via callbacks)
    @Volatile var motionDetected: Boolean = false
        private set
    @Volatile var faceDetected: Boolean = false
        private set

    // Internal tracking
    private var motionCooldownRunnable: Runnable? = null
    private var faceCooldownRunnable: Runnable? = null

    private var motionEnabled = false
    private var faceEnabled = false
    private var started = false

    // Push configuration (set by caller)
    private var haBaseUrl: String = ""
    private var deviceId: String = ""

    // -- Public API --------------------------------------------------------

    /**
     * Configure the push endpoint for instant sensor updates.
     * @param haBaseUrl HA base URL (e.g., "http://192.168.1.50:8123")
     * @param deviceId Device UUID for identifying this device to HA
     */
    fun configurePush(haBaseUrl: String, deviceId: String) {
        this.haBaseUrl = haBaseUrl.trimEnd('/')
        this.deviceId = deviceId
        Log.i(TAG, "Push configured: $haBaseUrl, device=$deviceId")
    }

    /**
     * Send a lightweight check-in to HA so the integration knows the device
     * is online and resets any backoff on the polling coordinator.
     * Called once at app startup after configurePush().
     */
    fun pushCheckIn() {
        if (haBaseUrl.isEmpty() || deviceId.isEmpty()) return

        val url = "$haBaseUrl${ApiPaths.HA}/sensor_push"
        val json = JSONObject().apply {
            put("deviceId", deviceId)
        }

        val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "Check-in failed: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    Log.d(TAG, "Check-in sent (${it.code})")
                }
            }
        })
    }

    fun start(motionEnabled: Boolean, faceEnabled: Boolean) {
        this.motionEnabled = motionEnabled
        this.faceEnabled = faceEnabled
        started = true
        Log.i(TAG, "Started: motion=$motionEnabled, face=$faceEnabled")
    }

    fun stop() {
        started = false
        motionEnabled = false
        faceEnabled = false

        // Clear active states and push final "off" values
        val wasMotion = motionDetected
        val wasFace = faceDetected
        if (wasMotion) {
            motionDetected = false
            Log.d(TAG, "Motion -> off (stopped)")
        }
        if (wasFace) {
            faceDetected = false
            Log.d(TAG, "Face -> off (stopped)")
        }
        if (wasMotion || wasFace) {
            pushState()
        }

        // Cancel pending cooldowns
        motionCooldownRunnable?.let { handler.removeCallbacks(it) }
        faceCooldownRunnable?.let { handler.removeCallbacks(it) }
        motionCooldownRunnable = null
        faceCooldownRunnable = null

        Log.i(TAG, "Stopped")
    }

    fun destroy() {
        stop()
    }

    /** Called when RTSP motion detection fires (motion exceeds threshold) */
    fun onMotionDetected() {
        if (!started || !motionEnabled) return

        // Cancel any pending "off" cooldown
        motionCooldownRunnable?.let { handler.removeCallbacks(it) }

        // Set "on" if not already
        if (!motionDetected) {
            motionDetected = true
            Log.d(TAG, "Motion -> on")
            pushState()
        }

        // Schedule cooldown to set "off"
        val runnable = Runnable {
            if (motionDetected) {
                motionDetected = false
                Log.d(TAG, "Motion -> off (cooldown)")
                pushState()
            }
        }
        motionCooldownRunnable = runnable
        handler.postDelayed(runnable, MOTION_COOLDOWN_MS)
    }

    /** Called with face detection result (true = face detected within distance threshold) */
    fun onFaceResult(detected: Boolean) {
        if (!started || !faceEnabled) return

        if (detected) {
            // Cancel any pending "off" cooldown
            faceCooldownRunnable?.let { handler.removeCallbacks(it) }

            if (!faceDetected) {
                faceDetected = true
                Log.d(TAG, "Face -> on")
                pushState()
            }

            // Schedule cooldown
            val runnable = Runnable {
                if (faceDetected) {
                    faceDetected = false
                    Log.d(TAG, "Face -> off (cooldown)")
                    pushState()
                }
            }
            faceCooldownRunnable = runnable
            handler.postDelayed(runnable, FACE_COOLDOWN_MS)
        }
        // Don't set "off" on each non-detection frame -- cooldown handles it
    }

    // -- Push to HA --------------------------------------------------------

    private fun pushState() {
        if (haBaseUrl.isEmpty() || deviceId.isEmpty()) return

        val url = "$haBaseUrl${ApiPaths.HA}/sensor_push"
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("motionDetected", motionDetected)
            put("faceDetected", faceDetected)
            // Include the enabled flags so the integration's binary_sensor
            // can render unavailable when the toggle is off — without
            // waiting for the 5s coordinator poll to catch up.
            put("motionDetectionEnabled", motionEnabled)
            put("faceDetectionEnabled", faceEnabled)
        }

        // Fire-and-forget on OkHttp's thread pool
        val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.w(TAG, "Push failed (will retry on next poll): ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.w(TAG, "Push returned ${it.code}")
                    } else {
                        Log.d(TAG, "Push OK: motion=$motionDetected, face=$faceDetected")
                    }
                }
            }
        })
    }
}
