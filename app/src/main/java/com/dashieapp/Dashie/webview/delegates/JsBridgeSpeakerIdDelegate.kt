package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray

/**
 * JS-bridge delegate for speaker-ID / voice enrollment (dev-gated feature —
 * BuildConfig.SPEAKER_ID_ENABLED flavors only; the controller no-ops
 * elsewhere, and old/prod JS never calls these methods thanks to
 * feature-detection on the JS side).
 *
 * The JS action handler passes the family-member list INTO
 * startVoiceEnrollment (JS owns familyService) so Kotlin needs no member
 * fetch path. Wired to VoiceEnrollmentController in VoiceComponentWiring,
 * same triad as JsBridgeScheduleDelegate.
 */
class JsBridgeSpeakerIdDelegate(private val webView: WebView) {

    /** (members: List<id to displayName>) -> show the enrollment wizard. */
    var onStartEnrollment: ((members: List<Pair<String, String>>) -> Unit)? = null

    /** Wired to VoiceEnrollmentController.isAvailable. */
    var speakerIdAvailabilityProvider: (() -> Boolean)? = null

    /**
     * membersJson: [{"id":"<family_member uuid>","name":"John"}, ...]
     * (empty array allowed — the wizard shows its no-members message).
     */
    @JavascriptInterface
    fun startVoiceEnrollment(membersJson: String) {
        val members = try {
            val arr = JSONArray(membersJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name")
                if (id.isNotEmpty() && name.isNotEmpty()) id to name else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bad membersJson for startVoiceEnrollment", e)
            emptyList()
        }
        webView.post { onStartEnrollment?.invoke(members) }
    }

    /** JS feature probe: bridge method may exist while the model is absent. */
    @JavascriptInterface
    fun isSpeakerIdAvailable(): Boolean =
        speakerIdAvailabilityProvider?.invoke() ?: false

    companion object {
        private const val TAG = "JsBridgeSpeakerId"
    }
}
