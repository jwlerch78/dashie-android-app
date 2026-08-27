package com.dashieapp.Dashie.halite

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import org.json.JSONObject

/**
 * Extracts Supabase JWT from WebView localStorage for use by background
 * Kotlin services (e.g., GoogleDrivePhotoSource calling edge functions).
 *
 * localStorage key: 'dashie-supabase-jwt'
 * Structure: { jwt: string, expiry: number (unix ms), userId: string, userEmail: string }
 *
 * Follows the same pattern as HaTokenExtractor.
 */
object SupabaseTokenExtractor {

    private const val TAG = "SupabaseTokenExtractor"

    data class TokenResult(
        val jwt: String,
        val expiry: Long,
        val userId: String,
        val userEmail: String
    )

    /**
     * Extract Supabase JWT from WebView localStorage.
     * Must be called on or dispatched to the main thread.
     */
    fun extract(webView: WebView?, callback: (TokenResult?) -> Unit) {
        if (webView == null) {
            callback(null)
            return
        }

        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("""
                (function() {
                    try {
                        var data = localStorage.getItem('dashie-supabase-jwt');
                        if (data) return data;
                        return null;
                    } catch (e) { return null; }
                })();
            """.trimIndent()) { result ->
                try {
                    if (result != null && result != "null") {
                        val cleanResult = result
                            .trim('"')
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")

                        val json = JSONObject(cleanResult)
                        val jwt = json.optString("jwt", "")
                        val expiry = json.optLong("expiry", 0L)
                        val userId = json.optString("userId", "")
                        val userEmail = json.optString("userEmail", "")

                        if (jwt.isNotEmpty()) {
                            callback(TokenResult(jwt, expiry, userId, userEmail))
                            return@evaluateJavascript
                        }
                    }
                    callback(null)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse Supabase JWT from WebView", e)
                    callback(null)
                }
            }
        }
    }

    /**
     * Extract Supabase JWT and cache it in ConnectionPreferences.
     */
    fun extractAndCache(
        webView: WebView?,
        prefs: ConnectionPreferences,
        callback: (Boolean) -> Unit
    ) {
        extract(webView) { result ->
            if (result != null) {
                prefs.supabaseJwt = result.jwt
                prefs.supabaseJwtExpiry = result.expiry
                prefs.supabaseUserId = result.userId
                Log.i(TAG, "Supabase JWT cached (user: ${result.userEmail}, expires: ${(result.expiry - System.currentTimeMillis()) / 60000}min)")
                callback(true)
            } else {
                Log.w(TAG, "No Supabase JWT found in WebView")
                callback(false)
            }
        }
    }

    /**
     * Get a valid Supabase JWT, extracting from WebView if needed.
     * Returns null if no JWT is available.
     */
    fun ensureToken(
        webView: WebView?,
        prefs: ConnectionPreferences,
        callback: (Boolean) -> Unit
    ) {
        // Check if cached token is still valid (with 5-min buffer)
        if (prefs.hasSupabaseJwt && !prefs.isSupabaseJwtExpired) {
            callback(true)
            return
        }

        // Extract fresh from WebView
        extractAndCache(webView, prefs, callback)
    }
}
