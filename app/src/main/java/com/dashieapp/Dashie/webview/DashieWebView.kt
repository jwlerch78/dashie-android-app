package com.dashieapp.Dashie.webview

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

/**
 * Custom WebView that fixes keyboard input issues on Fire tablets.
 *
 * Fire tablets have a known issue where the InputConnection between
 * the keyboard and WebView becomes "inactive", causing keyboard input
 * to stop working. This manifests as:
 * - "IInputConnectionWrapper: getTextBeforeCursor on inactive InputConnection"
 * - "RichInputConnection: Unable to connect to the editor to retrieve text"
 *
 * This custom WebView implements workarounds for these issues.
 */
class DashieWebView : WebView {

    companion object {
        private const val TAG = "DashieWebView"
        // Amazon Fire tablet manufacturer check
        private val IS_FIRE_TABLET = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        // Debounce time for InputConnection recreation (ms)
        private const val INPUT_CONNECTION_DEBOUNCE_MS = 100L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastInputConnectionTime = 0L
    private var isKeyboardVisible = false
    private var keyboardListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    // Cache the last InputConnection to avoid rapid recreation
    private var cachedInputConnection: InputConnection? = null
    private var cachedInputType: Int = 0

    constructor(context: Context) : super(context) { init() }
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) { init() }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) { init() }

    private fun init() {
        // Ensure focusable settings are correct for input
        isFocusable = true
        isFocusableInTouchMode = true

        // On Fire tablets, set up keyboard visibility detection
        if (IS_FIRE_TABLET) {
            Log.i(TAG, "Fire tablet detected - enabling keyboard workarounds")
            setupKeyboardVisibilityListener()
        }
    }

    /**
     * Set up a listener to detect when the keyboard shows/hides.
     * On Fire tablets, we need to ensure the WebView maintains proper focus
     * when the keyboard is visible.
     */
    private fun setupKeyboardVisibilityListener() {
        keyboardListener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.height
            val keypadHeight = screenHeight - rect.bottom

            val wasKeyboardVisible = isKeyboardVisible
            isKeyboardVisible = keypadHeight > screenHeight * 0.15

            if (isKeyboardVisible != wasKeyboardVisible) {
                if (isKeyboardVisible) {
                    Log.d(TAG, "Keyboard SHOWN")
                    notifyKeyboardVisibility(true)
                } else {
                    Log.d(TAG, "Keyboard HIDDEN")
                    notifyKeyboardVisibility(false)
                }
            }
        }
        viewTreeObserver.addOnGlobalLayoutListener(keyboardListener)
    }

    // Callback for Activity to disable/enable immersive mode
    var onKeyboardVisibilityChanged: ((Boolean) -> Unit)? = null

    /**
     * Fires synchronously the moment any Kotlin caller invokes [reload]
     * — BEFORE the native reload kicks off. Intended for hiding native
     * UI (widgets, sidebar backdrop, fullscreen tokens) instantly so
     * stale Kotlin views don't sit on top of the loading screen.
     *
     * Wired by MainActivity to FullscreenModeManager.notifyPageUnloading().
     * Note: this only catches Kotlin-driven reloads — JS-driven
     * reloads (location.reload(), settings save) go through a separate
     * `pagehide` listener in js/utils/webview-lifecycle.js.
     */
    var onReloadStarting: (() -> Unit)? = null

    private fun notifyKeyboardVisibility(visible: Boolean) {
        Log.d(TAG, "Notifying keyboard visibility: $visible")
        onKeyboardVisibilityChanged?.invoke(visible)
    }

    /**
     * Override onCreateInputConnection to customize how the WebView
     * creates its connection to the input method (keyboard).
     *
     * On Fire tablets, we cache the InputConnection to prevent rapid recreation
     * which causes the "inactive InputConnection" issue.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val now = System.currentTimeMillis()
        val timeSinceLastCall = now - lastInputConnectionTime

        // On Fire tablets, if we already have a cached connection for the same input type
        // and it was created recently, return the cached one to prevent rapid recreation
        if (IS_FIRE_TABLET && cachedInputConnection != null && timeSinceLastCall < 500) {
            // Apply the same outAttrs modifications
            outAttrs.inputType = cachedInputType
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN
            Log.d(TAG, "Returning cached InputConnection (${timeSinceLastCall}ms since last)")
            return cachedInputConnection
        }

        lastInputConnectionTime = now
        val inputConnection = super.onCreateInputConnection(outAttrs)

        if (inputConnection != null) {
            // Only log non-rapid calls to reduce log spam
            if (timeSinceLastCall > 500) {
                Log.d(TAG, "onCreateInputConnection: inputType=${outAttrs.inputType}")
            }

            // Remove problematic flags that can interfere with Fire tablet keyboard
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN

            // On Fire tablets, wrap with our custom InputConnection and cache it
            if (IS_FIRE_TABLET) {
                val wrapped = DashieInputConnection(inputConnection, true)
                cachedInputConnection = wrapped
                cachedInputType = outAttrs.inputType
                return wrapped
            }

            return inputConnection
        }

        Log.w(TAG, "onCreateInputConnection returned null")
        return null
    }

    /**
     * Override onCheckIsTextEditor to always return true.
     */
    override fun onCheckIsTextEditor(): Boolean {
        return true
    }

    /**
     * Handle key events - ensure they go to the WebView properly.
     * D-pad key interception is handled in MainInputHandler.handleDispatchKeyEvent().
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        return super.dispatchKeyEvent(event)
    }

    /**
     * Force restart the input connection if it appears to be stuck.
     * Call this if keyboard input stops working.
     */
    fun restartInputConnection() {
        Log.d(TAG, "restartInputConnection called")
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.restartInput(this)
    }

    /**
     * Override to ensure focus is handled correctly.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && isFocused) {
            Log.d(TAG, "Window focus gained, WebView is focused")
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        keyboardListener?.let {
            viewTreeObserver.removeOnGlobalLayoutListener(it)
        }
    }

    /**
     * Override to fire the [onReloadStarting] callback before the native
     * reload begins. Catches every Kotlin-side `webView.reload()` site
     * (HaConnectionMonitor, AuthDelegate, SidebarCacheManager, etc.)
     * without instrumenting each one. Synchronous — the hide actions
     * happen on this thread BEFORE super.reload() returns.
     */
    override fun reload() {
        try {
            onReloadStarting?.invoke()
        } catch (e: Throwable) {
            Log.w(TAG, "onReloadStarting callback threw — proceeding with reload", e)
        }
        super.reload()
    }
}
