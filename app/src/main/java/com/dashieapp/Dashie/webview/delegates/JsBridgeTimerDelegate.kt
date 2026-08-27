package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.WebView

/**
 * Delegate handling timer overlay JavaScript bridge methods.
 * Manages bidirectional communication between Kotlin timer overlay
 * and JavaScript timer service via postMessage.
 *
 * JavaScript → Kotlin: Timer state updates (created, updated, completed, cancelled)
 * Kotlin → JavaScript: Timer commands (pause, resume, cancel, minimize, expand)
 */
class JsBridgeTimerDelegate(
    private val webView: WebView
) {
    companion object {
        private const val TAG = "JsBridgeTimer"
    }

    // Callbacks for timer events (JavaScript → Kotlin)
    var onTimerCreated: ((String) -> Unit)? = null
    var onTimerUpdated: ((String) -> Unit)? = null
    var onTimerCompleted: ((String) -> Unit)? = null
    var onTimerCancelled: ((String) -> Unit)? = null

    // ============================================
    // Timer State Updates (JavaScript → Kotlin)
    // Called from JavaScript when timer state changes
    // ============================================

    /**
     * Called from JavaScript when a timer is created.
     * Forwards to MainActivity to create Kotlin timer overlay.
     */
    fun onTimerCreated(timerJson: String) {
        Log.wtf(TAG, "onTimerCreated() ENTRY POINT - timerJson length: ${timerJson.length}")
        Log.i(TAG, "onTimerCreated() called from JavaScript")
        Log.d(TAG, "Timer JSON: $timerJson")
        try {
            webView.post {
                Log.i(TAG, "Posting to WebView thread")
                onTimerCreated?.invoke(timerJson)
                Log.i(TAG, "Callback completed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ERROR in onTimerCreated", e)
        }
    }

    /**
     * Called from JavaScript when a timer is updated (every second).
     * Forwards to MainActivity to update Kotlin timer overlay.
     */
    fun onTimerUpdated(timerJson: String) {
        Log.v(TAG, "onTimerUpdated() called from JavaScript")
        webView.post {
            onTimerUpdated?.invoke(timerJson)
        }
    }

    /**
     * Called from JavaScript when a timer completes.
     * Forwards to MainActivity to update Kotlin timer overlay state.
     */
    fun onTimerCompleted(timerJson: String) {
        Log.i(TAG, "onTimerCompleted() called from JavaScript")
        webView.post {
            onTimerCompleted?.invoke(timerJson)
        }
    }

    /**
     * Called from JavaScript when a timer is cancelled.
     * Forwards to MainActivity to remove Kotlin timer overlay.
     */
    fun onTimerCancelled(timerId: String) {
        Log.i(TAG, "onTimerCancelled() called from JavaScript: $timerId")
        webView.post {
            onTimerCancelled?.invoke(timerId)
        }
    }

    // ============================================
    // Timer Commands (Kotlin → JavaScript)
    // Called from Kotlin timer card UI or Fully Kiosk API
    // ============================================

    /**
     * Create a new timer via the overlay iframe's TimerService.
     * Called from Fully Kiosk API (external HA addon timer creation).
     */
    fun createTimer(durationSeconds: Int, description: String?) {
        Log.i(TAG, "Creating timer: ${durationSeconds}s, description=$description")
        val escapedDesc = description?.replace("'", "\\'")
        val descArg = if (escapedDesc != null) "'$escapedDesc'" else "undefined"
        webView.post {
            webView.evaluateJavascript(
                // BUNDLE-EXEMPT: dashieTimerCommand — kiosk path falls back to postMessage into the dashie-overlay iframe (kiosk-services timer handler)
                """
                (function() {
                    if (typeof window.dashieTimerCommand === 'function') {
                        window.dashieTimerCommand('create', { durationSeconds: $durationSeconds, description: $descArg });
                        console.log('Sent create timer command via dashieTimerCommand: ${durationSeconds}s');
                        return;
                    }
                    var iframe = document.getElementById('dashie-overlay');
                    if (iframe && iframe.contentWindow) {
                        iframe.contentWindow.postMessage({
                            source: 'dashie-parent',
                            type: 'timer-command',
                            command: 'create',
                            durationSeconds: $durationSeconds,
                            description: $descArg
                        }, '*');
                        console.log('Sent create timer command via postMessage: ${durationSeconds}s');
                    } else {
                        console.log('ERROR: no dashieTimerCommand and no overlay iframe for timer creation');
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    /**
     * Pause a running timer (called from Kotlin timer card UI).
     * Sends command to JavaScript to pause the timer.
     */
    fun pauseTimer(timerId: String) {
        sendTimerCommand("pause", timerId)
    }

    /**
     * Resume a paused timer (called from Kotlin timer card UI).
     * Sends command to JavaScript to resume the timer.
     */
    fun resumeTimer(timerId: String) {
        sendTimerCommand("resume", timerId)
    }

    /**
     * Cancel a timer (called from Kotlin timer card UI).
     * Sends command to JavaScript to cancel the timer.
     */
    fun cancelTimer(timerId: String) {
        sendTimerCommand("cancel", timerId)
    }

    /**
     * Minimize a timer (called from Kotlin timer card UI).
     * Sends command to JavaScript to minimize the timer.
     */
    fun minimizeTimer(timerId: String) {
        Log.i(TAG, "Calling JavaScript minimize for timer $timerId")
        sendTimerCommand("minimize", timerId)
    }

    /**
     * Expand a minimized timer (called from Kotlin timer card UI).
     * Sends command to JavaScript to expand the timer.
     */
    fun expandTimer(timerId: String) {
        Log.i(TAG, "Calling JavaScript expand for timer $timerId")
        sendTimerCommand("expand", timerId)
    }

    // ============================================
    // Internal Helpers
    // ============================================

    /**
     * Send a timer command to JavaScript via postMessage.
     * The overlay iframe listens for these commands and updates timer state.
     */
    private fun sendTimerCommand(command: String, timerId: String) {
        webView.post {
            webView.evaluateJavascript(
                """
                (function() {
                    if (typeof window.dashieTimerCommand === 'function') {
                        window.dashieTimerCommand('$command', { timerId: '$timerId' });
                        console.log('Sent $command via dashieTimerCommand for $timerId');
                        return;
                    }
                    var iframe = document.getElementById('dashie-overlay');
                    if (iframe && iframe.contentWindow) {
                        iframe.contentWindow.postMessage({
                            source: 'dashie-parent',
                            type: 'timer-command',
                            command: '$command',
                            timerId: '$timerId'
                        }, '*');
                        console.log('Sent $command command via postMessage for $timerId');
                    } else {
                        console.log('ERROR: no dashieTimerCommand and no iframe for $command');
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }
}
