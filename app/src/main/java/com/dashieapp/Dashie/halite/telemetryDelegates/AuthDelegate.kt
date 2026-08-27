package com.dashieapp.Dashie.halite.telemetryDelegates

import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import org.json.JSONObject

/**
 * Delegate handling HA authentication events and proactive token refresh.
 * Manages token lifecycle to prevent auth_invalid storms and IP bans.
 */
class AuthDelegate(
    private val webView: WebView,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "AuthDelegate"
    }

    // Debounce auth_invalid handling to prevent multiple rapid-fire calls
    private var lastAuthInvalidTime = 0L
    private val AUTH_INVALID_DEBOUNCE_MS = 10_000L  // 10 seconds

    // Proactive token refresh timer
    private val tokenRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // Guaranteed cushion before expiry = EARLY - CHECK_INTERVAL. With a 6-min early
    // window and a 2-min poll, the refresh always fires at least ~4 min before expiry
    // (and typically 4-6 min before). Keeping EARLY > CHECK_INTERVAL is what avoids the
    // old 5min/5min config, where a poll landing just outside the window pushed the
    // refresh to the next tick — right at expiry (Mio refreshed only ~5s early).
    private val TOKEN_REFRESH_CHECK_INTERVAL_MS = 2 * 60 * 1000L  // Check every 2 minutes
    private val TOKEN_REFRESH_EARLY_MS = 6 * 60 * 1000L           // Refresh when ≤6 min before expiry

    private val tokenRefreshRunnable = object : Runnable {
        override fun run() {
            checkAndRefreshToken()
            tokenRefreshHandler.postDelayed(this, TOKEN_REFRESH_CHECK_INTERVAL_MS)
        }
    }

    /**
     * Handle authentication flow events from JavaScript.
     * Events: auth_required, auth_ok, auth_invalid, custom
     */
    fun onAuthEvent(jsonData: String) {
        try {
            val data = JSONObject(jsonData)
            val event = data.optString("event", "unknown")
            val eventData = data.optJSONObject("data")
            val message = eventData?.optString("message", "") ?: ""
            val haVersion = eventData?.optString("ha_version", "") ?: ""

            when (event) {
                "auth_required" -> {
                    Log.i(TAG, "Auth flow: auth_required (HA v$haVersion)")
                    DiagnosticBuffer.info("HA_AUTH", "auth_required: HA v$haVersion")
                }
                "auth_ok" -> {
                    Log.i(TAG, "Auth flow: auth_ok (HA v$haVersion)")
                    DiagnosticBuffer.info("HA_AUTH", "auth_ok: HA v$haVersion - authenticated successfully")
                    startProactiveTokenRefresh()
                }
                "auth_invalid" -> {
                    Log.e(TAG, "Auth flow: auth_invalid - $message")
                    DiagnosticBuffer.error("HA_AUTH", "auth_invalid: $message")
                }
                else -> {
                    Log.d(TAG, "Auth flow: $event - $message")
                    DiagnosticBuffer.info("HA_AUTH", "$event: $message")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse auth event: ${e.message}")
        }
    }

    /**
     * Handle expired/invalid auth tokens from JavaScript.
     * Pauses WebView JS, refreshes token, then reloads.
     */
    fun onAuthInvalid(jsonData: String) {
        try {
            val data = JSONObject(jsonData)
            val message = data.optString("message", "Unknown")

            // Debounce: ignore rapid-fire auth_invalid signals
            val now = System.currentTimeMillis()
            if (now - lastAuthInvalidTime < AUTH_INVALID_DEBOUNCE_MS) {
                Log.w(TAG, "AUTH INVALID (debounced, ignoring): $message")
                return
            }
            lastAuthInvalidTime = now

            Log.e(TAG, "AUTH INVALID: $message - pausing WebView and attempting token refresh")
            DiagnosticBuffer.error("HA_AUTH", "Auth invalid: $message - pausing JS, attempting refresh")
            PersistentLog.error("AUTH", "Auth invalid: $message")

            // 1. IMMEDIATELY pause WebView JS to prevent HA frontend from retrying
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Log.i(TAG, "  -> Pausing WebView timers to stop reconnection attempts")
                webView.pauseTimers()
            }

            // 2. Try to refresh token in background
            Thread {
                Log.i(TAG, "  -> Attempting token refresh before reload...")
                val refreshResult = HaTokenExtractor.refreshTokenSync(halitePrefs)

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (refreshResult.success) {
                        Log.i(TAG, "Token refreshed after auth_invalid - updating localStorage and reloading")
                        DiagnosticBuffer.info("HA_AUTH", "Token refreshed - reloading with fresh token")
                        PersistentLog.info("AUTH", "Token refreshed after auth_invalid, reloading")

                        HaTokenExtractor.updateWebViewTokens(webView, refreshResult.accessToken, refreshResult.refreshToken, halitePrefs.connection.haTokenExpiry)
                        webView.resumeTimers()
                        webView.reload()
                    } else {
                        Log.w(TAG, "Token refresh failed - clearing tokens and reloading for re-login")
                        DiagnosticBuffer.warn("HA_AUTH", "Token refresh failed - reloading for re-login")
                        PersistentLog.warn("AUTH", "Token refresh failed, reloading")

                        halitePrefs.connection.clearHaTokens()
                        webView.evaluateJavascript("""
                            (function() {
                                console.log('[DashieLite] Clearing hassTokens due to auth_invalid (refresh failed)');
                                localStorage.removeItem('hassTokens');
                            })();
                        """.trimIndent(), null)

                        webView.resumeTimers()
                        webView.reload()
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle auth invalid: ${e.message}")
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    webView.resumeTimers()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Start the proactive token refresh timer.
     * Called when HA authenticates successfully (auth_ok).
     */
    fun startProactiveTokenRefresh() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable)
        Log.i(TAG, "Starting proactive token refresh timer (check every ${TOKEN_REFRESH_CHECK_INTERVAL_MS / 60000}min, refresh ${TOKEN_REFRESH_EARLY_MS / 60000}min before expiry)")
        DiagnosticBuffer.info("HA_AUTH", "Proactive refresh timer started")

        HaTokenExtractor.extractAndCache(webView, halitePrefs) { success ->
            if (success) {
                val expiry = halitePrefs.connection.haTokenExpiry
                val now = System.currentTimeMillis()
                val timeUntilExpiry = if (expiry > 0) expiry - now else Long.MAX_VALUE
                Log.i(TAG, "Tokens extracted for proactive refresh (expires in ${timeUntilExpiry / 1000}s)")

                if (timeUntilExpiry in 1..TOKEN_REFRESH_EARLY_MS) {
                    Log.i(TAG, "Token already within refresh window - refreshing NOW")
                    checkAndRefreshToken()
                }
            }
        }

        tokenRefreshHandler.postDelayed(tokenRefreshRunnable, TOKEN_REFRESH_CHECK_INTERVAL_MS)
    }

    /**
     * Stop the proactive token refresh timer.
     */
    fun stopProactiveTokenRefresh() {
        tokenRefreshHandler.removeCallbacks(tokenRefreshRunnable)
    }

    /**
     * Check if token is near expiry and proactively refresh it.
     */
    private fun checkAndRefreshToken() {
        val expiry = halitePrefs.connection.haTokenExpiry
        if (expiry == 0L) {
            Log.d(TAG, "No token expiry set, skipping proactive check")
            return
        }

        val now = System.currentTimeMillis()
        val timeUntilExpiry = expiry - now

        if (timeUntilExpiry <= 0) {
            Log.w(TAG, "Token already expired (${-timeUntilExpiry / 1000}s ago), skipping proactive refresh")
            return
        }

        if (timeUntilExpiry > TOKEN_REFRESH_EARLY_MS) {
            Log.d(TAG, "Token valid for ${timeUntilExpiry / 1000}s, no proactive refresh needed")
            return
        }

        Log.i(TAG, "Token expires in ${timeUntilExpiry / 1000}s - proactively refreshing")
        DiagnosticBuffer.info("HA_AUTH", "Proactive refresh: token expires in ${timeUntilExpiry / 1000}s")

        Thread {
            val result = HaTokenExtractor.refreshTokenSync(halitePrefs)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (result.success) {
                    Log.i(TAG, "Proactive token refresh successful")
                    DiagnosticBuffer.info("HA_AUTH", "Proactive refresh: success")
                    PersistentLog.info("AUTH", "Proactive token refresh successful")
                    HaTokenExtractor.updateWebViewTokens(webView, result.accessToken, result.refreshToken, halitePrefs.connection.haTokenExpiry)
                } else {
                    Log.w(TAG, "Proactive token refresh failed - will retry next cycle")
                    DiagnosticBuffer.warn("HA_AUTH", "Proactive refresh: failed (will retry)")
                }
            }
        }.start()
    }

    fun destroy() {
        stopProactiveTokenRefresh()
    }
}
