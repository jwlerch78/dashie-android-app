package com.dashieapp.Dashie.halite.music

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import com.dashieapp.Dashie.edition.brandName

/**
 * Activity that shows the Music Assistant login page in a WebView.
 *
 * Flow:
 * 1. Load http://{ma_url}/login?device_name=Dashie+Tablet
 * 2. Auto-click the "Login with Home Assistant" button to skip MA's landing page
 * 3. User authenticates with HA credentials
 * 4. MA redirects to /?code={token}
 * 5. We intercept the redirect, extract the token, save it, finish with RESULT_OK
 *
 * Launch with MaLoginActivity.createIntent(context, maUrl).
 */
class MaLoginActivity : Activity() {
    companion object {
        private const val TAG = "MaLoginActivity"
        private const val EXTRA_MA_URL = "ma_url"

        fun createIntent(context: Context, maUrl: String): Intent {
            return Intent(context, MaLoginActivity::class.java).apply {
                putExtra(EXTRA_MA_URL, maUrl)
            }
        }
    }

    private lateinit var webView: WebView
    private lateinit var connectionPrefs: ConnectionPreferences
    private var maUrl: String = ""
    private var autoClickAttempted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.dashieapp.Dashie.halite.settings.SettingsTrace.shown("Activity/MaLoginActivity")

        connectionPrefs = ConnectionPreferences(this)
        maUrl = intent.getStringExtra(EXTRA_MA_URL) ?: ""

        if (maUrl.isEmpty()) {
            Log.e(TAG, "No MA URL provided")
            Toast.makeText(this, "Music Assistant URL not configured", Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val container = FrameLayout(this)
        container.setBackgroundColor(0xFF1A1A2E.toInt())

        // Clear only MA-domain cookies (not HA dashboard cookies) when re-logging in.
        // CookieManager is global — clearing all cookies logs out the HA dashboard too.
        val clearCookies = intent.getBooleanExtra("clear_all_webview_data", false)
            || intent.getBooleanExtra("skip_auto_login", false)
        if (clearCookies) {
            Log.i(TAG, "Clearing MA-domain cookies for fresh login")
            clearCookiesForDomain(maUrl)
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "DashieTablet/1.0"
        }

        val progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            isIndeterminate = true
        }

        // Floating close button (upper-right)
        val backButton = ImageView(this).apply {
            val size = (48 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = (16 * resources.displayMetrics.density).toInt()
                topMargin = (16 * resources.displayMetrics.density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC333333.toInt())
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )
            elevation = 8f * resources.displayMetrics.density
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val tokenSaved = connectionPrefs.maApiToken.isNotEmpty()
                Log.i(TAG, "X button pressed — closing with RESULT_CANCELED (token saved: $tokenSaved)")
                PersistentLog.info("MA_LOGIN", "closed via X button (token saved: $tokenSaved)")
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        container.addView(webView)
        container.addView(progressBar)
        container.addView(backButton)
        setContentView(container)

        // Show a centered modal dialog before the user starts interacting with the
        // WebView, explaining that they should sign in with their HA admin account.
        // Uses dialog_simple_message with a single OK button (hide the Cancel button),
        // matching the onboarding-tip modal style for consistency. Reduces support
        // calls from users who pick a non-admin account.
        showAdminSignInInstructionDialog()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return handleUrlRedirect(request.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = android.view.View.GONE
                Log.i(TAG, "onPageFinished: $url")
                PersistentLog.info("MA_LOGIN", "pageFinished: $url")

                // Clear MA localStorage on first page load if reset was requested
                if (clearCookies && url != null && url.contains("/login")) {
                    view.evaluateJavascript("localStorage.removeItem('auth_token')", null)
                    Log.i(TAG, "Cleared localStorage['auth_token'] for fresh login")
                }

                // Check URL redirect first
                if (url != null) {
                    try {
                        if (handleUrlRedirect(Uri.parse(url))) return
                    } catch (e: Exception) {
                        Log.w(TAG, "handleUrlRedirect exception: ${e.message}")
                    }
                }

                // Auto-click the HA login button on MA's landing page to skip the extra tap.
                // MA's login page has an anchor/button that links to HA OAuth.
                // Disabled when skipAutoLogin is set (re-login as different user).
                val skipAutoLogin = intent.getBooleanExtra("skip_auto_login", false)
                if (!skipAutoLogin && !autoClickAttempted && url != null && url.contains("/login")) {
                    autoClickAttempted = true
                    view.evaluateJavascript("""
                        (function() {
                            // Try clicking any link/button that leads to HA auth
                            var links = document.querySelectorAll('a[href*="auth/authorize"], a[href*="auth/login"], button');
                            for (var i = 0; i < links.length; i++) {
                                var el = links[i];
                                var text = (el.textContent || '').toLowerCase();
                                var href = (el.href || '').toLowerCase();
                                if (text.indexOf('home assistant') >= 0 || text.indexOf('login') >= 0
                                    || href.indexOf('auth/authorize') >= 0) {
                                    el.click();
                                    return 'clicked';
                                }
                            }
                            // Shadow DOM fallback: MA uses web components
                            var shadows = document.querySelectorAll('*');
                            for (var j = 0; j < shadows.length; j++) {
                                var sr = shadows[j].shadowRoot;
                                if (!sr) continue;
                                var innerLinks = sr.querySelectorAll('a, button');
                                for (var k = 0; k < innerLinks.length; k++) {
                                    var el2 = innerLinks[k];
                                    var t2 = (el2.textContent || '').toLowerCase();
                                    if (t2.indexOf('home assistant') >= 0 || t2.indexOf('login') >= 0) {
                                        el2.click();
                                        return 'clicked-shadow';
                                    }
                                }
                            }
                            return 'not-found';
                        })()
                    """.trimIndent()) { result ->
                        Log.i(TAG, "Auto-click HA login button: $result")
                    }
                }

                // Fallback: oauth_callback.html stores token in localStorage.
                // Only check on non-login pages — on the login page there's no valid
                // token yet, and a stale token from a previous session would cause
                // an immediate close before the user can authenticate.
                if (url != null && !url.contains("/login")) {
                    view.evaluateJavascript("localStorage.getItem('auth_token')") { result ->
                        val token = result?.trim('"') ?: ""
                        val probeResult = if (token.isEmpty() || token == "null") "empty" else "${token.length} chars, hasJwtDots=${token.contains(".")}"
                        Log.i(TAG, "localStorage['auth_token'] probe: $probeResult")
                        PersistentLog.info("MA_LOGIN", "localStorage probe: $probeResult")
                        if (token.isNotEmpty() && token != "null" && token.contains(".")) {
                            Log.i(TAG, "Got MA JWT from localStorage (${token.length} chars)")
                            PersistentLog.info("MA_LOGIN", "JWT from localStorage (${token.length} chars)")
                            saveTokenAndFinish(token)
                        }
                    }
                }
            }
        }

        val loginUrl = "${maUrl.trimEnd('/')}/login?device_name=Dashie+Tablet"
        Log.i(TAG, "Loading MA login: $loginUrl")
        webView.loadUrl(loginUrl)
    }

    /**
     * Show a centered modal before the WebView login form explaining that the user
     * should sign in with their Home Assistant admin account. Uses dialog_simple_message
     * with a single OK button (negative button hidden) to match the onboarding-tip
     * modal style. Reduces support calls from users who pick a non-admin account.
     */
    private fun showAdminSignInInstructionDialog() {
        val dialogView = layoutInflater.inflate(
            com.dashieapp.Dashie.R.layout.dialog_simple_message, null
        )
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle)?.text =
            "Sign in with your HA admin account"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage)?.text =
            "Please use your Home Assistant admin account to sign in on the next screen.\n\n" +
            "Admin access gives ${brandName()} long-term tokens so you won't need to log in again, " +
            "and enables profile switching across your Music Assistant users.\n\n" +
            "Non-admin accounts work for a short time but will expire."
        // Hide Cancel — this is an informational dialog, not a decision point.
        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative)?.visibility = View.GONE

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val okButton = dialogView.findViewById<android.widget.Button>(
            com.dashieapp.Dashie.R.id.buttonPositive
        )
        okButton?.apply {
            text = "OK"
            // Let weight=1 (from the XML) provide horizontal fill; the full-width button
            // matches the onboarding-tip style.
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnConfirm(dialogView)
    }

    /**
     * Check if a URL contains the auth token redirect.
     * MA redirects to /?code={jwt_token} on successful login.
     * Only intercept the final redirect at root path, not intermediate OAuth callbacks.
     */
    private fun handleUrlRedirect(uri: Uri): Boolean {
        val path = uri.path ?: ""
        val code = uri.getQueryParameter("code")

        if (code != null && code.isNotEmpty()) {
            val msg = "redirect: path='$path', code=${code.length} chars, hasDots=${code.contains(".")}"
            Log.i(TAG, "handleUrlRedirect: $msg, first20=${code.take(20)}")
            PersistentLog.info("MA_LOGIN", msg)
            if (path == "/" || path.isEmpty()) {
                if (code.contains(".")) {
                    Log.i(TAG, "Got MA JWT token (${code.length} chars)")
                    PersistentLog.info("MA_LOGIN", "JWT captured (${code.length} chars)")
                    saveTokenAndFinish(code)
                    return true
                } else {
                    Log.w(TAG, "Got code but not a JWT (${code.length} chars, no dots) — ignoring")
                    PersistentLog.warn("MA_LOGIN", "code rejected: not a JWT (${code.length} chars, no dots)")
                }
            } else {
                Log.i(TAG, "Code found but path is '$path' (not root) — skipping")
            }
        }
        return false
    }

    private fun saveTokenAndFinish(token: String) {
        Log.i(TAG, "saveTokenAndFinish: saving ${token.length}-char token for $maUrl")
        PersistentLog.info("MA_LOGIN", "saveToken: ${token.length} chars for $maUrl")
        connectionPrefs.maApiToken = token
        connectionPrefs.maApiUrl = maUrl.trimEnd('/')
        connectionPrefs.maTokenExpired = false  // fresh token — clear any prior expired state

        // Toast uses app context so it can still show after this activity finishes
        // (the upgrade below runs async and completes post-finish).
        val appContext = applicationContext

        // Try to upgrade to a long-lived token (~10 years) so it doesn't expire
        Thread {
            var longLived = false
            try {
                val client = MaApiClient(maUrl.trimEnd('/'), token)
                val args = org.json.JSONObject().apply {
                    put("name", "Dashie (${android.os.Build.MODEL})")
                    put("is_long_lived", true)
                }
                // Try new endpoint name first (MA 2.8.4+), fall back to old name
                var response = client.sendRequest("auth/token/create", args)
                if (response == null || response.has("error") || response.has("error_code")) {
                    response = client.sendRequest("auth/create_token", args)
                }
                // auth/token/create returns the JWT as a RAW STRING, which
                // MaApiClient.sendRequest wraps as {"result":"<jwt>"} — NOT as
                // {"result":{"token":…}}. The old optJSONObject("result") therefore
                // always returned null, so EVERY account (admin included) silently
                // fell back to a short-lived token that expired days later = the MA
                // re-login trap. Parse it the same way createTokenForUser() does.
                val longLivedToken = response?.optString("result", "")
                    ?.takeIf { it.isNotEmpty() && it.contains(".") }
                if (!longLivedToken.isNullOrEmpty()) {
                    connectionPrefs.maApiToken = longLivedToken
                    longLived = true
                    Log.i(TAG, "Upgraded to long-lived MA token (${longLivedToken.length} chars)")
                } else {
                    Log.w(TAG, "Could not create long-lived token, using short-lived")
                }

                // Save to central HA store so all Dashie devices share this token
                val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this@MaLoginActivity)
                com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.saveCentralMaToken(prefs)
            } catch (e: Exception) {
                Log.w(TAG, "Long-lived token upgrade failed: ${e.message}")
            }

            // Record longevity and, if we only got a short-lived (non-admin) token,
            // tell the user NOW — otherwise it silently expires days later and they
            // hit the MA re-login trap with no idea why.
            connectionPrefs.maTokenLongLived = longLived
            if (!longLived) {
                PersistentLog.warn("MA_LOGIN", "short-lived token — non-admin account or unsupported MA")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(
                        appContext,
                        "Connected to Music Assistant, but this is a temporary session that will " +
                            "expire. Sign in with an admin Music Assistant account to stay connected.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()

        Toast.makeText(this, "Music Assistant connected", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        // Fire the pending-music-enable check directly so the Enable Music Player Switch
        // reverts on cancel regardless of which host activity launched us. SettingsActivity
        // also runs this from its onResume, which is fine — the check clears the pending
        // callbacks so it's idempotent if called twice.
        try {
            val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
            com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.checkPendingMusicEnable(
                prefs, null
            )
        } catch (e: Exception) {
            Log.w(TAG, "checkPendingMusicEnable from onDestroy failed: ${e.message}")
        }
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Clear cookies only for the MA domain, leaving HA dashboard cookies intact.
     * CookieManager is global across all WebViews, so we must be selective.
     */
    private fun clearCookiesForDomain(maUrl: String) {
        try {
            val uri = Uri.parse(maUrl)
            val host = uri.host ?: return
            val cm = android.webkit.CookieManager.getInstance()
            // CookieManager.getCookie returns all cookies for a URL as "key=val; key2=val2"
            val cookies = cm.getCookie(maUrl) ?: return
            for (cookie in cookies.split(";")) {
                val name = cookie.trim().split("=").firstOrNull()?.trim() ?: continue
                // Set each cookie to expired to clear it
                cm.setCookie(maUrl, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Domain=$host; Path=/")
            }
            cm.flush()
            Log.i(TAG, "Cleared cookies for MA domain: $host")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear MA cookies: ${e.message}")
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val tokenSaved = connectionPrefs.maApiToken.isNotEmpty()
            Log.i(TAG, "Back pressed — closing with RESULT_CANCELED (token saved: $tokenSaved)")
            PersistentLog.info("MA_LOGIN", "closed via back (token saved: $tokenSaved)")
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }
}
