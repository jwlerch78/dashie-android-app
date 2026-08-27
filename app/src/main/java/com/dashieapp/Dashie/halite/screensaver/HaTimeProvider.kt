package com.dashieapp.Dashie.halite.screensaver

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.HalitePreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Reads the HA server's current wall time from the HTTP `Date` response
 * header (every HA HTTP response carries one) and exposes a millisecond
 * offset that callers can add to System.currentTimeMillis() to get
 * HA-corrected time.
 *
 * Use case: Fire tablets that block Amazon's NTP servers drift. HA usually
 * has accurate time from the HA host, so deriving Dashie's clock from HA
 * keeps the displayed time correct even when the OS clock is wrong.
 *
 * This is display-only — Android does not let user-space apps set the
 * system clock without root, so we never touch SystemClock.
 */
class HaTimeProvider(
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "HaTimeProvider"
        private const val POLL_INTERVAL_MS = 10 * 60 * 1000L  // 10 min
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    @Volatile
    private var offsetMs: Long = 0L
    @Volatile
    private var hasSync = false

    // Accepts self-signed certs for LAN HA URLs.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val pollRunnable = object : Runnable {
        override fun run() {
            syncOnce()
            if (isRunning) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    var onSync: ((offsetMs: Long) -> Unit)? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread { syncOnce() }.start()
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        Log.i(TAG, "Started polling HA time every ${POLL_INTERVAL_MS / 60000}min")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        Log.i(TAG, "Stopped polling HA time")
    }

    /** Current epoch millis adjusted to HA wall time, or device time if no sync yet. */
    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    fun hasSynced(): Boolean = hasSync

    fun getOffsetMs(): Long = offsetMs

    private fun syncOnce() {
        Thread {
            val (baseUrl, token) = HaTokenExtractor.getValidCredentialsSync(halitePrefs) ?: run {
                Log.w(TAG, "No HA credentials — cannot sync time")
                return@Thread
            }
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/")
                    .get()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "HA /api/ returned ${response.code}")
                        return@use
                    }
                    val dateHeader = response.header("Date") ?: run {
                        Log.w(TAG, "HA response missing Date header")
                        return@use
                    }
                    // RFC 1123: "Sun, 27 Apr 2026 13:31:43 GMT"
                    val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("GMT")
                    }
                    val haTime = parser.parse(dateHeader) ?: run {
                        Log.w(TAG, "Failed to parse Date header: $dateHeader")
                        return@use
                    }
                    val newOffset = haTime.time - System.currentTimeMillis()
                    offsetMs = newOffset
                    hasSync = true
                    Log.i(TAG, "HA time sync: offset=${newOffset}ms (HA=${haTime}, device=${Date()})")
                    handler.post { onSync?.invoke(newOffset) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "HA time sync failed: ${e.message}")
            }
        }.start()
    }
}
