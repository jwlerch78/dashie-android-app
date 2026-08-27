package com.dashieapp.Dashie.halite.voice

import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor

/**
 * One attempt to recover from an HA auth failure mid-voice-turn: refresh the access token, or
 * re-authenticate from stored credentials, then retry the connection once.
 *
 * Extracted from HaVoiceService so the service reads as a voice pipeline rather than a token
 * manager (it was over the 800-line ceiling). The one-shot guard lives here, which is the point:
 * the original bug class is an infinite refresh→fail→refresh loop.
 */
class HaAssistAuthRecovery(
    private val halitePrefs: HalitePreferences,
    private val webViewProvider: () -> WebView,
) {

    companion object {
        private const val TAG = "HaAssistAuth"
    }

    /** True while a recovery is in flight, so the caller can suppress the disconnect it causes. */
    @Volatile
    var isRecovering: Boolean = false
        private set

    private var hasTried = false

    /** Reset the one-shot guard. Call at the start of each voice interaction. */
    fun resetForNewInteraction() {
        hasTried = false
    }

    /**
     * @param onRecovered new access + refresh token; the caller should reconnect.
     * @param onGiveUp a user-facing reason; no further attempt will be made this turn.
     */
    fun attempt(
        onRecovered: (accessToken: String?, refreshToken: String?) -> Unit,
        onGiveUp: (reason: String) -> Unit,
    ) {
        if (hasTried) {
            Log.e(TAG, "❌ Already tried token recovery, giving up")
            onGiveUp("Authentication failed")
            return
        }

        val hasRefreshToken = halitePrefs.connection.haRefreshToken.isNotEmpty()
        val hasCredentials = halitePrefs.connection.shouldAutoLogin()
        Log.i(TAG, "🔑 Auth failed, attempting recovery... " +
            "(refreshToken=$hasRefreshToken, storedCredentials=$hasCredentials)")

        if (!hasRefreshToken && !hasCredentials) {
            Log.e(TAG, "❌ No recovery options available (no refresh token, no stored credentials)")
            onGiveUp("Session expired - please log in to Home Assistant")
            return
        }

        hasTried = true
        isRecovering = true   // suppress the disconnect error this causes
        HaTokenExtractor.refreshTokenWithResult(halitePrefs, webViewProvider()) { result ->
            isRecovering = false
            if (result.success) {
                Log.i(TAG, "✅ Token recovery successful via HaTokenExtractor")
                onRecovered(result.accessToken, result.refreshToken)
            } else {
                Log.e(TAG, "❌ Token recovery failed - both refresh and credential re-auth failed")
                onGiveUp("Session expired - please log in again")
            }
        }
    }
}
