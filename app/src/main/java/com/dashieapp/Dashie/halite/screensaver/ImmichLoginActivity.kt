package com.dashieapp.Dashie.halite.screensaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*

/**
 * WebView-based login activity for Immich.
 *
 * On launch:
 * 1. Try auto-detect Immich on the HA host (ports 2283, 8080, 3001)
 * 2. If found → go straight to Immich's login page in a WebView
 * 3. If not found → show a dialog asking for the server URL
 * 4. After user logs in on Immich's page, capture the immich_access_token
 *    cookie (polled every 500ms since Immich is a SPA)
 * 5. Save token locally and return RESULT_OK
 */
class ImmichLoginActivity : Activity() {

    companion object {
        private const val TAG = "ImmichLoginActivity"
        const val EXTRA_SERVER_URL = "immich_server_url"
        const val EXTRA_HA_BASE_URL = "ha_base_url"
        private const val IMMICH_COOKIE_NAME = "immich_access_token"
        private const val COOKIE_POLL_INTERVAL_MS = 500L
        // 10 minutes — covers slow credential entry, 2FA, OIDC redirects.
        // Polling stops automatically when the activity is finishing.
        private const val COOKIE_POLL_MAX_ATTEMPTS = 1200
        private val IMMICH_PORTS = listOf(2283, 8080, 3001)

        fun createIntent(context: Context, serverUrl: String = "", haBaseUrl: String = ""): Intent {
            return Intent(context, ImmichLoginActivity::class.java).apply {
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_HA_BASE_URL, haBaseUrl)
            }
        }
    }

    private lateinit var rootContainer: FrameLayout
    private var webView: WebView? = null
    private var immichServerUrl: String = ""
    private var tokenCaptured = false

    // Cookie polling
    private val pollHandler = Handler(Looper.getMainLooper())
    private var pollAttempts = 0
    private val cookiePollRunnable = object : Runnable {
        override fun run() {
            if (tokenCaptured || isFinishing) return
            pollAttempts++
            if (pollAttempts > COOKIE_POLL_MAX_ATTEMPTS) {
                Log.w(TAG, "Cookie poll timed out after $pollAttempts attempts")
                return
            }
            checkForImmichToken()
            pollHandler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.dashieapp.Dashie.halite.settings.SettingsTrace.shown("Activity/ImmichLoginActivity")

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1A2E.toInt())
        }
        setContentView(rootContainer)

        // If we already have a URL, go straight to WebView
        val prefilledUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
        val prefs = ScreensaverPreferences(this)
        val existingUrl = prefilledUrl.ifEmpty { prefs.immichServerUrl }

        if (existingUrl.isNotEmpty()) {
            showWebViewLogin(existingUrl)
        } else {
            // Try auto-detect, then either go to WebView or show URL dialog
            autoDetectAndLaunch()
        }
    }

    // ── Auto-Detection ──────────────────────────────────────────────

    private fun autoDetectAndLaunch() {
        val haBaseUrl = intent.getStringExtra(EXTRA_HA_BASE_URL) ?: ""
        val haHost = if (haBaseUrl.isNotEmpty()) {
            try { Uri.parse(haBaseUrl).host } catch (_: Exception) { null }
        } else null

        if (haHost == null) {
            showUrlDialog()
            return
        }

        // Show a loading indicator while we probe
        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val label = TextView(this).apply {
            text = "Looking for Immich..."
            setTextColor(0xFFA0A0B0.toInt())
            textSize = 14f
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(progressBar)
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() })
        }
        rootContainer.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        Log.i(TAG, "Auto-detecting Immich on host: $haHost")

        Thread {
            var foundUrl: String? = null
            for (port in IMMICH_PORTS) {
                val testUrl = "http://$haHost:$port"
                try {
                    val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder()
                        .url("$testUrl/api/server/ping")
                        .build()
                    val response = client.newCall(request).execute()
                    val body = response.body?.string() ?: ""
                    if (response.isSuccessful && body.contains("pong", ignoreCase = true)) {
                        foundUrl = testUrl
                        Log.i(TAG, "Found Immich at $testUrl")
                        break
                    }
                } catch (_: Exception) { }
            }

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                rootContainer.removeAllViews()
                if (foundUrl != null) {
                    showWebViewLogin(foundUrl)
                } else {
                    showUrlDialog()
                }
            }
        }.start()
    }

    // ── URL Dialog (fallback) ───────────────────────────────────────

    private fun showUrlDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val textPrimary = 0xFFE8E8E8.toInt()
        val textSecondary = 0xFFA0A0B0.toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.dialog_background)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // Title
        TextView(this).apply {
            text = "Connect to Immich"
            setTextColor(textPrimary)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            container.addView(this)
        }

        // Spacer
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            container.addView(this)
        }

        // Message
        TextView(this).apply {
            text = "Immich was not detected automatically.\nEnter your server address below."
            setTextColor(textSecondary)
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            container.addView(this)
        }

        // Spacer
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
            container.addView(this)
        }

        // URL input
        val urlInput = EditText(this).apply {
            hint = "http://192.168.1.100:2283"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(textPrimary)
            setHintTextColor(textSecondary and 0x80FFFFFF.toInt())
            setBackgroundColor(0xFF1E1E35.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
            isSingleLine = true
        }
        container.addView(urlInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ))

        // Spacer
        View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            container.addView(this)
        }

        // Buttons
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(true)
            .setOnCancelListener { setResult(RESULT_CANCELED); finish() }
            .create()

        // Cancel
        Button(this).apply {
            text = "Cancel"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            setTextColor(textSecondary)
            textSize = 14f
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { dialog.dismiss(); setResult(RESULT_CANCELED); finish() }
            buttons.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { marginEnd = dp(12) })
        }

        // Connect
        Button(this).apply {
            text = "Connect"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_primary)
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                val url = urlInput.text.toString().trim().trimEnd('/')
                if (url.isEmpty()) {
                    Toast.makeText(this@ImmichLoginActivity, "Enter a server URL", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                showWebViewLogin(url)
            }
            buttons.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ))
        }

        container.addView(buttons)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    // ── WebView Login ───────────────────────────────────────────────

    /**
     * Auto-prepend a scheme when the user enters a bare host (e.g. "10.0.0.5"
     * or "nas:2283"). LAN-looking targets (IPv4, localhost, single-label
     * hostnames) default to http; anything with dots defaults to https.
     */
    private fun normalizeImmichUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        val lower = trimmed.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
        val host = trimmed.substringBefore('/').substringBefore(':')
        val isLan = host == "localhost" ||
            host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) ||
            !host.contains('.')
        val scheme = if (isLan) "http" else "https"
        return "$scheme://$trimmed"
    }

    private fun showWebViewLogin(serverUrl: String) {
        immichServerUrl = normalizeImmichUrl(serverUrl).trimEnd('/')
        rootContainer.removeAllViews()

        // Clear Immich cookies for a fresh login
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "DashieTablet/1.0"
        }
        webView = wv

        val progressBar = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            isIndeterminate = true
        }

        // Close button
        val closeButton = ImageView(this).apply {
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
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 8f * resources.displayMetrics.density
            isClickable = true
            isFocusable = true
            setOnClickListener { setResult(RESULT_CANCELED); finish() }
        }

        rootContainer.addView(wv)
        rootContainer.addView(progressBar)
        rootContainer.addView(closeButton)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progressBar.visibility = View.GONE
                // Start polling for the token cookie once the page is loaded
                startCookiePolling()
            }

            // Immich is a SPA — the login submit is followed by a
            // history.pushState navigation that does NOT trigger
            // onPageFinished. Restart polling here so we catch the cookie
            // on the post-login transition (and on any other SPA route
            // change such as an OIDC redirect chain).
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!tokenCaptured) {
                    Log.d(TAG, "doUpdateVisitedHistory: $url — restarting cookie poll")
                    startCookiePolling()
                }
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        val loginUrl = "$immichServerUrl/auth/login"
        Log.i(TAG, "Loading Immich login: $loginUrl")
        wv.loadUrl(loginUrl)
    }

    // ── Token Capture (polling) ─────────────────────────────────────

    private fun startCookiePolling() {
        pollAttempts = 0
        pollHandler.removeCallbacks(cookiePollRunnable)
        pollHandler.postDelayed(cookiePollRunnable, COOKIE_POLL_INTERVAL_MS)
    }

    private fun stopCookiePolling() {
        pollHandler.removeCallbacks(cookiePollRunnable)
    }

    private fun checkForImmichToken() {
        if (tokenCaptured) return

        // Check cookie (CookieManager CAN read HttpOnly cookies on Android)
        val cookies = CookieManager.getInstance().getCookie(immichServerUrl) ?: ""
        val tokenFromCookie = cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("$IMMICH_COOKIE_NAME=") }
            ?.substringAfter("=")

        if (!tokenFromCookie.isNullOrEmpty()) {
            Log.i(TAG, "Got Immich token from cookie (${tokenFromCookie.length} chars)")
            tokenCaptured = true
            stopCookiePolling()
            saveAndFinish(tokenFromCookie)
            return
        }

        // Fallback: check localStorage
        webView?.evaluateJavascript(
            "try { JSON.parse(localStorage.getItem('immich') || '{}').accessToken || '' } catch(e) { '' }"
        ) { result ->
            if (tokenCaptured) return@evaluateJavascript
            val token = result?.trim('"') ?: ""
            if (token.isNotEmpty() && token != "null") {
                Log.i(TAG, "Got Immich token from localStorage (${token.length} chars)")
                tokenCaptured = true
                stopCookiePolling()
                saveAndFinish(token)
            }
        }
    }

    private fun saveAndFinish(accessToken: String) {
        val prefs = ScreensaverPreferences(this)
        prefs.immichServerUrl = immichServerUrl
        prefs.immichAccessToken = accessToken
        Log.i(TAG, "Saved Immich credentials locally (server=$immichServerUrl)")

        Toast.makeText(this, "Immich connected", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopCookiePolling()
        webView?.destroy()
        super.onDestroy()
    }
}
