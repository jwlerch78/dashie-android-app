package com.dashieapp.Dashie.halite

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebView-based HA login for non-Fire devices.
 * Loads HA's standard OAuth page, captures the auth callback,
 * and exchanges the auth code for tokens.
 *
 * Used on Samsung and other tablets where HA's web login works well.
 * Fire tablets use HaLoginActivity (custom Kotlin UI) instead.
 */
class HaWebLoginActivity : Activity() {

    // Activity-scoped coroutines, cancelled in onDestroy — the token-exchange
    // coroutine must not outlive the Activity (it finishes/navigates on completion).
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "HaWebLoginActivity"
        const val EXTRA_HA_URL = "ha_url"
        const val RESULT_ACCESS_TOKEN = "access_token"
        const val RESULT_REFRESH_TOKEN = "refresh_token"
        const val RESULT_TOKEN_EXPIRY = "token_expiry"
        const val RESULT_RECONFIGURE = "reconfigure"
    }

    private lateinit var webView: WebView
    private var haUrl: String = ""
    private var clientId: String = ""
    private var redirectUri: String = ""
    private var hasHandledCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        haUrl = intent.getStringExtra(EXTRA_HA_URL) ?: ""
        if (haUrl.isEmpty()) {
            Log.e(TAG, "No HA URL provided")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        clientId = "$haUrl/"
        redirectUri = "$haUrl/?auth_callback=1"

        Log.i(TAG, "Starting WebView HA login for: $haUrl")

        // Hide system bars (immersive sticky mode)
        hideSystemBars()

        // Ensure the on-screen IME shows when the embedded HA login form
        // gets focus, even when Android has detected a "hardware keyboard"
        // (Fire TV phone-remote app, paired BT keyboard, etc. — all register
        // as KeyboardType: 2 and would otherwise suppress IME).
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // WebView fills the screen
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true

            // Clear cookies for fresh login
            CookieManager.getInstance().removeAllCookies(null)

            // Bridge for the JS focus listener to force-show the IME when
            // the user focuses an <input>/<textarea> inside the HA page.
            addJavascriptInterface(ImeBridge(), "DashieImeBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return handleUrl(url)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return handleUrl(url ?: return false)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "onPageFinished: ${url?.take(120)}")
                    injectImeFocusBridge(view)
                }
            }
        }

        rootLayout.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Back button — use a FrameLayout wrapper for larger touch target
        // The wrapper intercepts all touches within its bounds so the WebView doesn't steal them
        val backTouchTarget = FrameLayout(this).apply {
            // Intercept all touches within this area
            setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    v.performClick()
                }
                true // Consume touch — don't pass to WebView below
            }
            setOnClickListener {
                Log.i(TAG, "⬅️ Back button tapped — returning to config")
                val resultIntent = Intent().apply { putExtra(RESULT_RECONFIGURE, true) }
                setResult(RESULT_CANCELED, resultIntent)
                finish()
            }
            isClickable = true
            isFocusable = true
            // D-pad focus highlight — orange outline when focused
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.alpha = 1.0f
                } else {
                    v.alpha = 0.7f
                }
            }
            alpha = 0.7f
        }

        val backIcon = createBackIcon()
        val iconSize = dpToPx(40)
        backTouchTarget.addView(backIcon, FrameLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER
        })

        // Touch target is larger than the visible icon for easy tapping
        val touchSize = dpToPx(64)
        rootLayout.addView(backTouchTarget, FrameLayout.LayoutParams(touchSize, touchSize).apply {
            gravity = Gravity.START or Gravity.TOP
            leftMargin = dpToPx(8)
            topMargin = dpToPx(8)
        })

        setContentView(rootLayout)

        // Load HA auth page
        val authUrl = "$haUrl/auth/authorize?client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
                "&redirect_uri=${java.net.URLEncoder.encode(redirectUri, "UTF-8")}" +
                "&response_type=code"
        Log.i(TAG, "Loading auth URL: $authUrl")
        webView.loadUrl(authUrl)
    }

    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
        // Also use WindowInsetsController for Android 11+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun handleUrl(url: String): Boolean {
        Log.d(TAG, "shouldOverrideUrlLoading: ${url.take(120)}")

        // Check for auth callback
        if (url.contains("auth_callback=1") && url.contains("code=") && !hasHandledCallback) {
            hasHandledCallback = true
            Log.i(TAG, "Auth callback detected: ${url.take(80)}...")

            // Stop any further WebView navigation and show loading state
            webView.stopLoading()
            webView.loadUrl("about:blank")

            // Extract the auth code from the URL
            val uri = android.net.Uri.parse(url)
            val authCode = uri.getQueryParameter("code")

            if (authCode != null) {
                Log.i(TAG, "Got auth code (${authCode.length} chars), exchanging for tokens...")
                exchangeCodeForToken(authCode)
            } else {
                Log.e(TAG, "No auth code in callback URL")
                notifyFailed()
            }
            return true
        }

        // Block all navigation after callback has been handled (prevent refresh loops)
        if (hasHandledCallback) {
            Log.d(TAG, "Blocking post-callback navigation: ${url.take(80)}")
            return true
        }

        return false
    }

    private fun exchangeCodeForToken(authCode: String) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$haUrl/auth/token")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val formData = buildString {
                    append("grant_type=authorization_code")
                    append("&code=").append(java.net.URLEncoder.encode(authCode, "UTF-8"))
                    append("&client_id=").append(java.net.URLEncoder.encode(clientId, "UTF-8"))
                }

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(formData)
                    writer.flush()
                }

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.optString("refresh_token", "")
                    val expiresIn = json.optLong("expires_in", 1800L)

                    Log.i(TAG, "Token exchange successful (access=${accessToken.length} chars, refresh=${refreshToken.length} chars)")

                    withContext(Dispatchers.Main) {
                        val resultIntent = Intent().apply {
                            putExtra(RESULT_ACCESS_TOKEN, accessToken)
                            putExtra(RESULT_REFRESH_TOKEN, refreshToken)
                            putExtra(RESULT_TOKEN_EXPIRY, expiresIn)
                        }
                        setResult(RESULT_OK, resultIntent)
                        finish()
                    }
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText()
                    Log.e(TAG, "Token exchange failed: ${connection.responseCode} - $errorBody")
                    connection.disconnect()
                    withContext(Dispatchers.Main) { notifyFailed() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange error: ${e.message}", e)
                withContext(Dispatchers.Main) { notifyFailed() }
            }
        }
    }

    private fun notifyFailed() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun createBackIcon(): ImageView {
        return ImageView(this).apply {
            val backArrow = object : Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                override fun draw(canvas: Canvas) {
                    val w = bounds.width().toFloat()
                    val h = bounds.height().toFloat()
                    val cx = w / 2; val cy = h / 2
                    val size = minOf(w, h) * 0.3f
                    canvas.drawLine(cx + size * 0.3f, cy - size, cx - size * 0.3f, cy, paint)
                    canvas.drawLine(cx - size * 0.3f, cy, cx + size * 0.3f, cy + size, paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
                override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            }
            setImageDrawable(backArrow)
            val bgCircle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            background = bgCircle
        }
    }

    /**
     * Inject a focus listener that calls back into native to force-show the
     * IME whenever an editable element in the HA page gains focus. Without
     * this, Android suppresses the on-screen keyboard whenever a phone-remote
     * app or BT keyboard is paired (registers as KeyboardType: 2).
     */
    private fun injectImeFocusBridge(view: WebView?) {
        val js = """
            (function() {
                if (window.__dashieImeBound) return;
                window.__dashieImeBound = true;
                document.addEventListener('focusin', function(e) {
                    var t = e.target;
                    if (!t) return;
                    var tag = (t.tagName || '').toUpperCase();
                    if (tag === 'INPUT' || tag === 'TEXTAREA' || t.isContentEditable) {
                        try { DashieImeBridge.requestIme(); } catch(err) {}
                    }
                }, true);
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private inner class ImeBridge {
        @JavascriptInterface
        fun requestIme() {
            webView.post {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                @Suppress("DEPRECATION")
                imm.showSoftInput(webView, InputMethodManager.SHOW_FORCED)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val resultIntent = Intent().apply { putExtra(RESULT_RECONFIGURE, true) }
            setResult(RESULT_CANCELED, resultIntent)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        webView.destroy()
        super.onDestroy()
    }
}
