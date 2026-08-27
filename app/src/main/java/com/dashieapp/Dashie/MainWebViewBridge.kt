package com.dashieapp.Dashie

import android.util.Base64
import android.util.Log
import android.webkit.WebView

/**
 * Handles WebView ↔ JavaScript communication for MainActivity.
 * Extracted to reduce MainActivity size and improve maintainability.
 *
 * Responsibilities:
 * - Sending voice events to web app
 * - Sending audio data/levels to web app
 * - Sending wake word heartbeats
 * - Sending live audio samples for training
 * - Forwarding key events to JS
 * - Notifying volume changes
 */
class MainWebViewBridge(
    private val webViewProvider: () -> WebView
) {
    companion object {
        private const val TAG = "MainWebViewBridge"
    }

    private val webView: WebView
        get() = webViewProvider()

    /**
     * Send a voice event to the web app.
     * @param event Event name (e.g., "listening", "speechResult", "error")
     * @param data Event data (will be escaped for JS)
     */
    fun notifyWebView(event: String, data: String) {
        val escapedData = data.replace("'", "\\'")
        // BUNDLE-EXEMPT: onDashieVoiceEvent — JS voice lane callback; kiosk voice is the native pipeline
        val jsCode = """
            if (typeof window.onDashieVoiceEvent === 'function') {
                window.onDashieVoiceEvent('$event', '$escapedData');
            }
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    /**
     * Send raw audio data to the web app for processing/visualization.
     * @param audioData Raw audio bytes
     */
    fun sendAudioToWebView(audioData: ByteArray) {
        // Convert audio data to base64 for transmission to JavaScript
        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

        // BUNDLE-EXEMPT: onDashieAudioData — JS voice lane callback; kiosk voice is the native pipeline
        val jsCode = """
            if (typeof window.onDashieAudioData === 'function') {
                window.onDashieAudioData('$base64Audio');
            }
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    /**
     * Send real-time audio level for visualization.
     * @param amplitude Audio amplitude (0.0 to 1.0)
     */
    fun sendAudioLevelToWebView(amplitude: Float) {
        // BUNDLE-EXEMPT: onDashieAudioLevel — JS voice lane callback; kiosk voice is the native pipeline
        val jsCode = """
            if (typeof window.onDashieAudioLevel === 'function') {
                window.onDashieAudioLevel($amplitude);
            }
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    /**
     * Send wake word detection heartbeat (2x per second).
     * @param confidence Detection confidence
     * @param volume Current volume level
     */
    fun sendHeartbeatToWebView(confidence: Float, volume: Float) {
        // BUNDLE-EXEMPT: onDashieWakeWordHeartbeat — JS voice lane callback; kiosk voice is the native pipeline
        val jsCode = """
            if (typeof window.onDashieWakeWordHeartbeat === 'function') {
                window.onDashieWakeWordHeartbeat($confidence, $volume);
            }
        """.trimIndent()

        webView.post {
            webView.evaluateJavascript(jsCode, null)
        }
    }

    /**
     * Send live-collected wake word sample to WebView for upload.
     * Part of beta testing feature for collecting training data.
     * @param wavData WAV audio data
     * @param metadata JSON metadata about the sample
     */
    fun sendLiveSampleToWebView(wavData: ByteArray, metadata: String) {
        Log.d(TAG, "📤 Sending sample to WebView (${wavData.size} bytes)")

        // Convert WAV data to base64 for JavaScript
        val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)

        // Escape metadata JSON for JavaScript
        val escapedMetadata = metadata.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")

        Log.d(TAG, "📤 Base64 audio length: ${base64Audio.length}, metadata: ${escapedMetadata.take(100)}...")

        webView.post {
            Log.d(TAG, "📤 Evaluating JavaScript callback")
            webView.evaluateJavascript(
                "if (window.onLiveWakeWordSampleCollected) { console.log('[LiveSample] Callback invoked from Android'); window.onLiveWakeWordSampleCollected('$base64Audio', '$escapedMetadata'); } else { console.log('[LiveSample] ERROR: window.onLiveWakeWordSampleCollected not defined'); }",
                null
            )
        }
    }

    /**
     * Forward a remote/keyboard key event to JavaScript.
     * @param keyCode Android KeyEvent keyCode
     */
    fun forwardKeyToJs(keyCode: Int) {
        webView.evaluateJavascript("handleRemoteInput($keyCode);", null)
    }

    /**
     * Forward a long-press key event to JavaScript (currently used for the
     * d-pad center to enable widget-level "select-hold" actions, e.g. the
     * calendar's Navigate Days mode).
     * @param keyCode Android KeyEvent keyCode
     */
    fun forwardKeyToJsLongPress(keyCode: Int) {
        // BUNDLE-EXEMPT: handleRemoteInputLongPress — guarded; select-hold affordance exists only on full-mode webapp widgets (kiosk defines handleRemoteInput only)
        webView.evaluateJavascript("if (window.handleRemoteInputLongPress) handleRemoteInputLongPress($keyCode);", null)
    }

    /**
     * Notify web app of volume change from hardware buttons.
     * @param level The new volume level (0-10)
     */
    fun notifyVolumeChange(level: Int) {
        val isMuted = level == 0
        Log.d(TAG, "🔊 Notifying web app: level=$level, muted=$isMuted")
        webView.post {
            webView.evaluateJavascript(
                "if (window.onNativeVolumeChange) { window.onNativeVolumeChange($level, $isMuted); }",
                null
            )
        }
    }

    /**
     * Notify the web app about a color scheme change.
     * @param isDark true for dark mode, false for light mode
     */
    fun notifyColorSchemeChanged(isDark: Boolean) {
        webView.evaluateJavascript(
            "if (window.onColorSchemeChanged) window.onColorSchemeChanged($isDark);",
            null
        )
    }

    /**
     * Call speech result handler (for wake word training UI).
     * @param text Speech result text
     */
    fun notifySpeechResult(text: String) {
        val escapedText = text.replace("'", "\\'")
        webView.evaluateJavascript(
            "if (window.onDashieSpeechResult) window.onDashieSpeechResult('$escapedText');",
            null
        )
    }
}
