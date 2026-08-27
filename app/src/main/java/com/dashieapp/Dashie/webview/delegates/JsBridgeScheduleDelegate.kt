package com.dashieapp.Dashie.webview.delegates

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView

/**
 * Bridges the scheduled-actions feature between JS and native, both directions.
 *
 * JS → native (commands):
 *  - `scheduleReminder(...)`  — Dashie Cloud voice path creates a reminder.
 *  - `cancelReminder(id)`     — console (via Realtime) cancels one.
 *  - `updateReminder(...)`    — console (via Realtime) edits text / fire time.
 *
 * Native → JS (lifecycle): [emitReminderEvent] dispatches `window.dashieReminderEvent`
 * on create/fire/cancel/update so `reminder-sync.js` can mirror to Supabase.
 *
 * Callbacks are wired in VoiceComponentWiring → ScheduledActionManager, keeping Kotlin
 * the single device-owned source of truth across both voice paths + the console.
 */
class JsBridgeScheduleDelegate(private val webView: WebView) {

    private val main = Handler(Looper.getMainLooper())

    var onScheduleReminder: ((notifyText: String, delaySeconds: Int, vernacular: String) -> Unit)? = null
    var onCancelReminder: ((id: String) -> Unit)? = null
    var onUpdateReminder: ((id: String, notifyText: String, fireAtEpochMs: Long, vernacular: String) -> Unit)? = null
    var onCreateConditionAlert: ((entityPhrase: String, op: String, value: Double) -> Unit)? = null

    fun createConditionAlert(entityPhrase: String, op: String, value: Double) {
        Log.i(TAG, "createConditionAlert(\"$entityPhrase\" $op $value)")
        webView.post { onCreateConditionAlert?.invoke(entityPhrase, op, value) }
    }

    fun scheduleReminder(notifyText: String, delaySeconds: Int, vernacular: String) {
        Log.i(TAG, "scheduleReminder(\"$notifyText\", ${delaySeconds}s, $vernacular)")
        webView.post { onScheduleReminder?.invoke(notifyText, delaySeconds, vernacular) }
    }

    fun cancelReminder(id: String) {
        Log.i(TAG, "cancelReminder($id)")
        webView.post { onCancelReminder?.invoke(id) }
    }

    /** fireAtEpochMs comes across as a String to avoid JS-number→long marshaling issues. */
    fun updateReminder(id: String, notifyText: String, fireAtEpochMs: String, vernacular: String) {
        val fireAt = fireAtEpochMs.toLongOrNull()
        if (fireAt == null) {
            Log.w(TAG, "updateReminder($id): bad fireAtEpochMs '$fireAtEpochMs' — ignoring")
            return
        }
        Log.i(TAG, "updateReminder($id, fireAt=$fireAt)")
        webView.post { onUpdateReminder?.invoke(id, notifyText, fireAt, vernacular) }
    }

    /**
     * Native → JS lifecycle push. [actionJson] is a ScheduledAction.toJson() string.
     *
     * Retries until `window.dashieReminderEvent` exists. An alarm can fire while the app is
     * restarting (memory-killed on constrained tablets) — the WebView is then up but the JS
     * hasn't registered the handler yet. Emitting once would silently drop the mirror, and
     * nothing heals it afterwards (reminder-sync's reconcile is cloud→device only), leaving
     * the cloud row stuck 'scheduled' forever while the device shows FIRED. Same retry budget
     * as ScheduledActionManager's fire-time paths.
     */
    fun emitReminderEvent(type: String, actionJson: String, attempt: Int = 0, onDropped: (() -> Unit)? = null) {
        webView.post {
            webView.evaluateJavascript(
                // BUNDLE-EXEMPT: dashieReminderEvent — contract #31 residue — kiosk uses the native schedule engine; emit falls back to the native mirror on retry exhaustion
                """(function() {
                    try {
                        if (typeof window.dashieReminderEvent === 'function') {
                            window.dashieReminderEvent('$type', $actionJson);
                            return true;
                        }
                        return false;
                    } catch (e) { console.error('dashieReminderEvent failed', e); return false; }
                })();""".trimIndent()
            ) { result ->
                if (result == "true") {
                    if (attempt > 0) Log.i(TAG, "emitReminderEvent($type) delivered on attempt ${attempt + 1}")
                } else if (attempt < MAX_EMIT_RETRIES) {
                    Log.i(TAG, "dashieReminderEvent not ready ($type) — retry ${attempt + 1} in ${EMIT_RETRY_MS}ms")
                    main.postDelayed({ emitReminderEvent(type, actionJson, attempt + 1, onDropped) }, EMIT_RETRY_MS)
                } else if (onDropped != null) {
                    Log.w(TAG, "dashieReminderEvent never ready — falling back to the native mirror for '$type'")
                    onDropped.invoke()
                } else {
                    Log.w(TAG, "dashieReminderEvent never ready — dropping '$type' mirror")
                }
            }
        }
    }

    companion object {
        private const val TAG = "JsBridgeScheduleDelegate"
        private const val MAX_EMIT_RETRIES = 6
        private const val EMIT_RETRY_MS = 8_000L
    }
}
