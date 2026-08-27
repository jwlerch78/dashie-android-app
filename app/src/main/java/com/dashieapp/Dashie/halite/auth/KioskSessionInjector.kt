package com.dashieapp.Dashie.halite.auth

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import org.json.JSONObject

/**
 * Pushes this device's account session INTO the kiosk shell's WebView — the exact reverse of
 * [com.dashieapp.Dashie.halite.SupabaseTokenExtractor] (Kiosk Real Login, Phase 2).
 *
 * ## Why the direction is inverted
 *
 * On a full-app device the dashboard JS owns the credential: it signs in, refreshes, and stores
 * the JWT in `localStorage['dashie-supabase-jwt']`; `SupabaseTokenExtractor` SCRAPES it out so
 * native services can use it.
 *
 * A kiosk has no dashboard. Its WebView shows Home Assistant, so there is nobody in there to sign
 * in — which is exactly why [KioskSessionProvisioner] and [KioskJwtRefresher] exist and do it
 * natively over OkHttp. **Native owns the credential.** An appliance must not depend on its
 * browser to stay signed in.
 *
 * But Phase 2 puts the real settings stack inside the kiosk shell, and that stack's `EdgeClient`
 * reads its JWT from `localStorage['dashie-supabase-jwt']`. So we write the same blob, in the same
 * shape, and EdgeClient works **unmodified**.
 *
 * ## Two properties that are not decoration
 *
 * **1. It's a LATCH, not a call.** We set `window.__dashieKioskSession` *and* call
 * `window.dashieKioskSetSession(...)` if it exists. `onPageFinished` can fire before the shell's ES
 * module has evaluated, so a call-only push would land on `undefined` and be lost — leaving a
 * provisioned kiosk sitting inert with a perfectly good session and nothing in the log to say so.
 * Whichever side runs first, the other picks it up.
 *
 * **2. Both directions are wired.** [clear] pushes `null`, which tears the JS stack down and drops
 * the stored credential. Clearing `connection.supabaseJwt` in Kotlin does NOT stop a JS EdgeClient
 * that already holds the token in memory: it would keep reading calendars and spending credits
 * until the page happened to reload. That is §10 bug 4 (a revoked kiosk with full account access
 * for up to 72h) reintroduced one layer up. And the ON direction matters just as much — §10 bug 8
 * was precisely a push whose off-half was wired and whose on-half was not.
 */
object KioskSessionInjector {

    private const val TAG = "KioskSessionInject"

    /**
     * How we reach the shell's WebView. Set once at wiring time (MainHaliteSetup) rather than
     * threaded through the provisioner/refresher, both of which are background-thread OkHttp
     * singletons with no view of the UI.
     *
     * A PROVIDER, not a WebView: `recreateWebView()` (nightly memory recovery) replaces the
     * instance, and a captured reference would go stale and silently inject into a destroyed view.
     */
    @Volatile
    private var webViewProvider: (() -> WebView?)? = null

    fun attach(provider: () -> WebView?) {
        webViewProvider = provider
        Log.i(TAG, "Attached to the shell WebView provider")
    }

    /**
     * Push the current session into the shell. No-ops when there is nothing to push.
     *
     * Safe and cheap to call often — the JS side is idempotent (it starts the settings stack once,
     * and treats a repeat push of a refreshed token as an in-place credential update).
     *
     * Call it on: shell page load (covers WebView recreation), successful provisioning, and
     * successful native refresh.
     */
    fun push(prefs: HalitePreferences) {
        val conn = prefs.connection
        val jwt = conn.supabaseJwt
        if (jwt.isEmpty()) {
            Log.d(TAG, "No session to push (device is anonymous)")
            return
        }

        val blob = JSONObject().apply {
            put("jwt", jwt)
            put("expiry", conn.supabaseJwtExpiry)
            put("userId", conn.supabaseUserId)
            // The JS side surfaces this; the Account page reads the same value from AccountPreferences.
            put("userEmail", prefs.account.email)
        }.toString()

        evaluate(blob, "push")
    }

    /**
     * End the JS session: tear the settings stack down and drop the stored credential.
     *
     * Called when the server disowns us (D5 device removal, D6 sharing-off sweep, account
     * deletion). Kotlin has already cleared its own copy by this point — this is the half that
     * would otherwise be forgotten.
     */
    fun clear() {
        evaluate("null", "clear")
    }

    /**
     * Both halves of the latch, in one evaluation.
     *
     * Order matters: write the latch FIRST, so that even if `dashieKioskSetSession` is not yet
     * defined (module still parsing), the value is waiting for it when it installs itself.
     */
    private fun evaluate(sessionJsonOrNull: String, reason: String) {
        val provider = webViewProvider
        if (provider == null) {
            Log.w(TAG, "Not attached to a WebView — cannot $reason the session")
            return
        }

        Handler(Looper.getMainLooper()).post {
            val webView = provider()
            if (webView == null) {
                Log.w(TAG, "No WebView available — cannot $reason the session")
                return@post
            }

            // WEBAPP-EXEMPT: dashieKioskSetSession — kiosk-overlay session hand-off; no kiosk shell in full mode
            val js = """
                (function() {
                  try {
                    var s = $sessionJsonOrNull;
                    window.__dashieKioskSession = s;
                    if (typeof window.dashieKioskSetSession === 'function') {
                      window.dashieKioskSetSession(s);
                      return 'delivered';
                    }
                    return 'latched';
                  } catch (e) { return 'error: ' + e.message; }
                })();
            """.trimIndent()

            webView.evaluateJavascript(js) { result ->
                // Positive assertion, deliberately: "nothing happened" is indistinguishable from
                // "silently did nothing" otherwise (§10). 'latched' means the shell module hadn't
                // run yet and will pick it up; 'delivered' means it acted on it now.
                Log.i(TAG, "Session $reason → $result")
            }
        }
    }
}
