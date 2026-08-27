package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * SampleUploader for DashieLite
 *
 * Handles direct upload of wake word samples to the anonymous-wake-word-upload edge function.
 * No authentication required - uses device hash for rate limiting and consent tracking.
 *
 * Features:
 * - Queue-based upload (doesn't block wake word detection)
 * - Rate limit tracking (respects server limits)
 * - Consent version tracking
 * - Automatic retry on failure
 *
 * Usage:
 * 1. Create instance with context
 * 2. Call setEnabled(true) after user consents
 * 3. Call queueSample() when LiveSampleCollector produces samples
 */
class SampleUploader(private val context: Context) {
    companion object {
        private const val TAG = "SampleUploader"

        // Upload endpoint path (appended to SUPABASE_URL from BuildConfig)
        private const val UPLOAD_ENDPOINT_PATH = "/functions/v1/anonymous-wake-word-upload"

        // Current consent version - must match edge function
        const val CONSENT_VERSION = "1.0"

        // Preferences keys
        private const val PREFS_NAME = "dashie_sample_uploader"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CONSENT_GIVEN = "consent_given"
        private const val KEY_CONSENT_VERSION = "consent_version"
        private const val KEY_SAMPLES_UPLOADED = "samples_uploaded"
        private const val KEY_DEVICE_HASH = "device_hash"
        private const val KEY_RATE_LIMIT_PAUSED_UNTIL = "rate_limit_paused_until"
        private const val KEY_RATE_LIMIT_REENABLE_DONE = "rate_limit_reenable_v1"
    }

    // HTTP client
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)  // Large uploads
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Upload queue
    private val uploadQueue = ConcurrentLinkedQueue<QueuedSample>()
    private val isProcessingQueue = AtomicBoolean(false)

    // State
    private var enabled = false
    private var consentGiven = false
    private var deviceHash: String? = null
    private var rateLimitPausedUntil = 0L

    // Statistics
    private val samplesQueued = AtomicInteger(0)
    private val samplesUploaded = AtomicInteger(0)
    private val samplesFailed = AtomicInteger(0)

    // Callbacks
    var onUploadSuccess: ((samplesToday: Int, samplesTotal: Int) -> Unit)? = null
    var onUploadError: ((error: String) -> Unit)? = null
    var onRateLimitReached: ((message: String) -> Unit)? = null

    // Handler for callbacks on main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // Preferences
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Restore saved state
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        consentGiven = prefs.getBoolean(KEY_CONSENT_GIVEN, false)
        deviceHash = prefs.getString(KEY_DEVICE_HASH, null) ?: generateDeviceHash()
        rateLimitPausedUntil = prefs.getLong(KEY_RATE_LIMIT_PAUSED_UNTIL, 0L)

        // One-time repair: older builds permanently disabled the uploader on any
        // 429 (including the daily cap). Consent intact + uploader disabled is that
        // legacy state — the user-facing toggle gates collection upstream via
        // VoicePreferences, so re-enabling here can't override an intentional off.
        if (!prefs.getBoolean(KEY_RATE_LIMIT_REENABLE_DONE, false)) {
            if (consentGiven && !enabled) {
                enabled = true
                prefs.edit().putBoolean(KEY_ENABLED, true).commit()
                Log.i(TAG, "Re-enabled uploader that was permanently disabled by legacy 429 handling")
            }
            prefs.edit().putBoolean(KEY_RATE_LIMIT_REENABLE_DONE, true).commit()
        }

        Log.d(TAG, "SampleUploader initialized: enabled=$enabled, consent=$consentGiven, hash=${deviceHash?.take(16)}...")
    }

    /**
     * Data class for queued samples
     */
    data class QueuedSample(
        val wavData: ByteArray,
        val metadata: JSONObject,
        val retryCount: Int = 0
    )

    /**
     * Generate a stable device hash for anonymous identification
     * Uses: manufacturer + model + Android ID (hashed)
     */
    private fun generateDeviceHash(): String {
        val stableId = com.dashieapp.Dashie.util.StableDeviceId.read(context).ifEmpty { "unknown" }

        val deviceString = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}_$stableId"

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(deviceString.toByteArray())
            .joinToString("") { "%02x".format(it) }

        // Save for consistency
        prefs.edit().putString(KEY_DEVICE_HASH, hash).commit()

        Log.d(TAG, "Generated device hash: ${hash.take(16)}...")
        return hash
    }

    /**
     * Check if sample collection is enabled
     */
    fun isEnabled(): Boolean =
        enabled && consentGiven && System.currentTimeMillis() >= rateLimitPausedUntil

    /**
     * Check if user has given consent
     */
    fun hasConsent(): Boolean = consentGiven

    /**
     * Record user consent and enable collection
     */
    fun giveConsent() {
        consentGiven = true
        prefs.edit()
            .putBoolean(KEY_CONSENT_GIVEN, true)
            .putString(KEY_CONSENT_VERSION, CONSENT_VERSION)
            .commit()
        Log.i(TAG, "User consent recorded for version $CONSENT_VERSION")
    }

    /**
     * Revoke consent and disable collection
     */
    fun revokeConsent() {
        consentGiven = false
        enabled = false
        prefs.edit()
            .putBoolean(KEY_CONSENT_GIVEN, false)
            .putBoolean(KEY_ENABLED, false)
            .commit()

        // Clear queue
        uploadQueue.clear()

        Log.i(TAG, "User consent revoked, collection disabled")
    }

    /**
     * Enable or disable sample collection
     * Requires consent to be given first
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !consentGiven) {
            Log.w(TAG, "Cannot enable without consent")
            return
        }

        this.enabled = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()

        Log.i(TAG, "Sample collection ${if (enabled) "ENABLED" else "DISABLED"}")

        if (!enabled) {
            uploadQueue.clear()
        }
    }

    /**
     * Pause uploads until just after the next UTC midnight, when the edge
     * function's daily rate-limit window resets.
     */
    private fun pauseForRateLimit() {
        val dayMs = 24 * 60 * 60 * 1000L
        val nextUtcMidnight = (System.currentTimeMillis() / dayMs + 1) * dayMs
        rateLimitPausedUntil = nextUtcMidnight + 5 * 60 * 1000L
        prefs.edit().putLong(KEY_RATE_LIMIT_PAUSED_UNTIL, rateLimitPausedUntil).commit()
        uploadQueue.clear()

        Log.w(TAG, "Rate limited - uploads paused until ${java.util.Date(rateLimitPausedUntil)}")
    }

    /**
     * Queue a sample for upload
     * Called from LiveSampleCollector callback
     *
     * @param wavData The WAV audio data
     * @param metadataJson The metadata JSON string from LiveSampleCollector
     */
    fun queueSample(wavData: ByteArray, metadataJson: String) {
        if (!isEnabled()) {
            Log.d(TAG, "Sample ignored - collection disabled")
            return
        }

        try {
            val metadata = JSONObject(metadataJson)

            // Add DashieLite-specific fields
            metadata.put("device_hash", deviceHash)
            metadata.put("consent_version", CONSENT_VERSION)
            metadata.put("app_flavor", "halite")
            metadata.put("app_version", BuildConfig.VERSION_NAME)

            val sample = QueuedSample(wavData, metadata)
            uploadQueue.offer(sample)
            samplesQueued.incrementAndGet()

            Log.d(TAG, "Sample queued (queue size: ${uploadQueue.size})")

            // Start processing if not already running
            processQueue()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue sample: ${e.message}")
        }
    }

    /**
     * Process the upload queue
     */
    private fun processQueue() {
        if (!isProcessingQueue.compareAndSet(false, true)) {
            // Already processing
            return
        }

        Thread {
            try {
                // 🔴 Checked ONCE per drain, not per sample. A missing endpoint is not a transient
                // failure — retrying cannot fix it — so the queue is cleared rather than re-queued,
                // and the DROP is logged once with the count instead of three times per sample
                // (the loop below retries twice). Same reasoning as ModelInstaller's digest
                // mismatch: a permanent cause must not be dressed as a retry.
                if (!uploadEndpointConfigured()) {
                    uploadQueue.clear()
                    return@Thread
                }
                while (uploadQueue.isNotEmpty() && isEnabled()) {
                    val sample = uploadQueue.poll() ?: break

                    val outcome = uploadSample(sample)

                    if (outcome == UploadOutcome.PERMANENT) {
                        // Refused for a reason more attempts cannot change (the 401 case). Drop the
                        // whole queue and stop, exactly like the missing-endpoint check above —
                        // draining 20 clips through 3 attempts each against a gateway that will
                        // refuse every one is noise, not resilience.
                        val abandoned = queueSizeForLog()
                        uploadQueue.clear()
                        Log.w(TAG, "DROP: [expected] abandoning $abandoned further queued " +
                            "sample(s) — the upload was permanently refused, see the line above")
                        break
                    }

                    if (outcome == UploadOutcome.RETRYABLE && sample.retryCount < 2) {
                        // Retry up to 2 times
                        uploadQueue.offer(sample.copy(retryCount = sample.retryCount + 1))
                        Log.d(TAG, "Sample re-queued for retry (attempt ${sample.retryCount + 1})")
                    }

                    // Small delay between uploads
                    Thread.sleep(500)
                }
            } finally {
                isProcessingQueue.set(false)
            }
        }.start()
    }

    /**
     * Upload a single sample to the edge function
     *
     * @return how the drain loop should treat this sample — see [UploadOutcome].
     */
    /**
     * Is there anywhere to send samples in this build?
     *
     * 🔴 Added 2026-08-04. `BuildConfig.SUPABASE_URL` is **blank in every Chickadee flavor** (the
     * account-free credential cut), so `uploadUrl` became the bare relative path
     * `"/functions/v1/anonymous-wake-word-upload"`, OkHttp rejected it, and the exception died in
     * the catch below — **silently**. Clips were captured, queued, and discarded forever with
     * nothing in the log to say so.
     *
     * That matters more than a normal dead path: John's beta-as-training-data ruling makes the
     * returned samples the POINT of shipping the Chickadee wake model, and a silent drop meant the
     * device paid the recall cost while returning nothing. It is also exactly the caught-and-silent
     * shape `WakeWordModelManager`'s KDoc warns about for its manifest URL.
     *
     * `[expected]` because in an edition with no ingest endpoint this is correct behaviour, not a
     * defect — but it must SAY so. ⚠️ The endpoint itself needs no account: it is
     * `anonymous-wake-word-upload`, device-hash rate-limited by design, so wiring one for this
     * edition is a URL decision rather than a credential one.
     */
    private fun uploadEndpointConfigured(): Boolean {
        // The DESTINATION half is now answered by WakeSampleIngest — an account-free edition falls
        // back to the public ingest host instead of building a bare relative path. What can still
        // be missing is the gateway's key requirement, and the reason string says which.
        val reason = WakeSampleIngest.uploadUnavailableReason() ?: return true
        Log.w(TAG, "DROP: [expected] wake-sample upload unavailable — $reason. " +
            "${queueSizeForLog()} captured sample(s) are being discarded. Consent and capture are " +
            "working. Target: ${WakeSampleIngest.uploadUrl()}")
        return false
    }

    private fun queueSizeForLog(): Int = try { uploadQueue.size } catch (e: Exception) { -1 }

    /**
     * What the drain loop should do next.
     *
     * 🔴 This replaced a Boolean on 2026-08-04, because a Boolean could not express the case that
     * matters: a 401 is a FAILURE that must NOT be retried. With two values the only ways to encode
     * it were to return "success" for something that failed, or to retry a request that is
     * guaranteed to fail twice more. Both are the "permanent cause dressed as a retry" shape this
     * file already rejected once at the drain check.
     */
    private enum class UploadOutcome {
        /** Uploaded and acknowledged. */
        SUCCEEDED,
        /** Failed in a way another attempt might fix (network, 5xx, malformed reply). */
        RETRYABLE,
        /** Refused for a reason no retry can change. Stop draining; the queue is cleared. */
        PERMANENT,
    }

    private fun uploadSample(sample: QueuedSample): UploadOutcome {
        try {
            // Build request body
            val requestBody = JSONObject().apply {
                put("audioBase64", Base64.encodeToString(sample.wavData, Base64.NO_WRAP))
                put("metadata", sample.metadata)
            }

            // Destination comes from WakeSampleIngest so a credential-free edition has one at all.
            val uploadUrl = WakeSampleIngest.uploadUrl()
            val anonKey = BuildConfig.SUPABASE_ANON_KEY

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .build()

            Log.d(TAG, "Uploading sample (${sample.wavData.size} bytes)...")

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && responseBody != null) {
                val json = JSONObject(responseBody)

                if (json.optBoolean("success", false)) {
                    samplesUploaded.incrementAndGet()

                    val samplesToday = json.optInt("samples_today", 0)
                    val samplesTotal = json.optInt("samples_total", 0)

                    Log.i(TAG, "✅ Sample uploaded (today: $samplesToday, total: $samplesTotal)")

                    // Update local stats
                    prefs.edit().putInt(KEY_SAMPLES_UPLOADED, samplesUploaded.get()).commit()

                    mainHandler.post {
                        onUploadSuccess?.invoke(samplesToday, samplesTotal)
                    }

                    return UploadOutcome.SUCCEEDED
                } else {
                    val error = json.optString("error", "Unknown error")
                    Log.e(TAG, "Upload failed: $error")
                    samplesFailed.incrementAndGet()

                    mainHandler.post {
                        onUploadError?.invoke(error)
                    }
                }
            } else if (response.code == 429) {
                // Rate limit reached
                val json = responseBody?.let { JSONObject(it) }
                val error = json?.optString("error", "Rate limit reached") ?: "Rate limit reached"

                Log.w(TAG, "Rate limit reached: $error")

                mainHandler.post {
                    onRateLimitReached?.invoke(error)
                }

                // Pause until the server's daily window resets (UTC midnight + buffer)
                // instead of disabling permanently — one busy day must not kill
                // collection forever. Lifetime-cap 429s retry once per day, which is
                // cheap and self-heals when the server cap is raised.
                pauseForRateLimit()

            } else if (response.code == 401 || response.code == 403) {
                // 🔴 PERMANENT, not transient. The Supabase gateway refuses the request before the
                // function runs — measured 2026-08-04: keyless → UNAUTHORIZED_NO_AUTH_HEADER,
                // bogus key → UNAUTHORIZED_INVALID_JWT_FORMAT. Retrying re-sends the same rejected
                // request, so this must not be dressed as a retry (same reasoning as
                // ModelInstaller's digest mismatch and the missing-endpoint drain check above).
                //
                // The realistic cause is the one WakeSampleIngest documents: the endpoint is still
                // deployed verify_jwt=true, so an account-free edition cannot post at all. Naming
                // it here means the log says what to FIX, not merely that something failed.
                Log.w(TAG, "DROP: [expected] wake-sample upload REFUSED at the gateway — " +
                    "HTTP ${response.code} from ${WakeSampleIngest.uploadUrl()}. Not retrying: " +
                    "this is an authorization decision, not a transient failure. " +
                    (WakeSampleIngest.uploadUnavailableReason()
                        ?: "this build sends a key, so the key or the endpoint's verify_jwt " +
                           "setting is wrong") + ". Response: $responseBody")
                samplesFailed.incrementAndGet()
                mainHandler.post {
                    onUploadError?.invoke("HTTP ${response.code}")
                }
                return UploadOutcome.PERMANENT
            } else {
                Log.e(TAG, "Upload failed: HTTP ${response.code} - $responseBody")
                samplesFailed.incrementAndGet()

                mainHandler.post {
                    onUploadError?.invoke("HTTP ${response.code}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}", e)
            samplesFailed.incrementAndGet()

            mainHandler.post {
                onUploadError?.invoke(e.message ?: "Unknown error")
            }
        }

        return UploadOutcome.RETRYABLE
    }

    /**
     * Get current statistics
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "enabled" to enabled,
            "consentGiven" to consentGiven,
            "queueSize" to uploadQueue.size,
            "samplesQueued" to samplesQueued.get(),
            "samplesUploaded" to samplesUploaded.get(),
            "samplesFailed" to samplesFailed.get()
        )
    }

    /**
     * Get the device hash (for display in UI)
     */
    fun getDeviceHashPrefix(): String {
        return deviceHash?.take(16) ?: "unknown"
    }

    /**
     * Clear queue and reset session stats
     */
    fun resetSession() {
        uploadQueue.clear()
        samplesQueued.set(0)
        Log.d(TAG, "Session reset")
    }
}
