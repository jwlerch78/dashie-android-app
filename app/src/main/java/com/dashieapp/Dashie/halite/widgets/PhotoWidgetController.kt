package com.dashieapp.Dashie.halite.widgets
// BRIDGE-DYNAMIC: window.$jsCoordinatorName — per-widget JS d-pad coordinator protocol (full-mode layout canvas)
// BUNDLE-EXEMPT: photosMenu, dashieGenerateUploadPairingCode, __dashieLastPairingResult — photo menu + upload pairing live in the full-mode webapp photo widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.QrCodeGenerator
import com.dashieapp.Dashie.halite.screensaver.PhotoExplorerOverlay
import com.dashieapp.Dashie.halite.screensaver.PhotoItem
import com.dashieapp.Dashie.halite.screensaver.PhotoSlideshow
import com.dashieapp.Dashie.halite.screensaver.PhotoSlideshowView
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Controller for the native Kotlin photo widget in the layout system.
 *
 * Manages a PhotoSlideshowView positioned at the layout slot coordinates.
 * Uses PhotoSourceFactory for photo source creation and lifecycle.
 *
 * Features:
 *   - Swipe left/right to advance/go-back
 *   - Tap to maximize (full screen), tap again to minimize
 *   - Frosted glass metadata ribbon with date + location
 *   - Metadata scales between widget mode (0.5x) and full-screen mode (1.0x)
 */
class PhotoWidgetController(
    private val context: Context,
    private val rootContainer: ViewGroup,
    private val screensaverPrefs: ScreensaverPreferences,
    private val halitePrefs: HalitePreferences?,
    webViewProvider: () -> WebView?,
    private val sharedPhotosProvider: (() -> List<PhotoItem>)? = null,
    private val visibilityGate: NativeWidgetVisibilityGate? = null
) : JsCoordinatedDpadWidget(webViewProvider) {
    companion object {
        private const val TAG = "PhotoWidgetCtrl"
        private const val WIDGET_METADATA_SCALE = 0.5f
    }

    // JsCoordinatedDpadWidget abstract overrides — d-pad input dispatches
    // to window.photosMenu (see js/widgets/photos/photos-menu.js).
    override val widgetId: String = "photos"
    override val jsCoordinatorName: String = "photosMenu"
    override val widgetContainer: FrameLayout? get() = _widgetContainer

    private var _widgetContainer: FrameLayout? = null
    private var slideshowView: PhotoSlideshowView? = null
    private var slideshow: PhotoSlideshow? = null
    private var sourceFactory: PhotoSourceFactory? = null
    // photoSourceType the slideshow was BUILT with (set when PhotoSourceFactory is
    // (re)created). Comparing the BUILT source (not the pref) self-heals drift the
    // pref-vs-pref guards miss: sync equality-skip, or a sign-in creation-race.
    private var builtSourceType: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isDestroyed = false
    private var photoExplorer: PhotoExplorerOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Re-paint on every palette push — fixes the sync race between
    // setSidebarThemeColors and setDarkMode (see WeatherWidgetController for
    // the full rationale). applyTheme is paint-only; no config-change risk.
    private val themeListener: () -> Unit = {
        val isDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context) ?: false
        applyTheme(isDark)
    }

    init {
        com.dashieapp.Dashie.halite.theming.NativeThemeManager.addListener(themeListener)
    }

    // Current slot bounds (pixels when gridCols==0, grid units otherwise)
    private var slotX = 0
    private var slotY = 0
    private var slotW = 0
    private var slotH = 0
    private var gridCols = 48
    private var gridRows = 32

    /**
     * Called from JS bridge when the layout canvas finds a Kotlin photo slot.
     */
    fun setWidgetBounds(x: Int, y: Int, w: Int, h: Int, gridColumns: Int, gridRowsTotal: Int) {
        Log.i(TAG, "setWidgetBounds: x=$x y=$y w=$w h=$h grid=${gridColumns}x${gridRowsTotal}")

        slotX = x
        slotY = y
        slotW = w
        slotH = h
        gridCols = gridColumns
        gridRows = gridRowsTotal

        mainHandler.post {
            if (isDestroyed) return@post
            if (_widgetContainer == null) {
                createWidget()
            } else {
                repositionWidget()
            }
        }
    }

    private fun createWidget() {
        if (isDestroyed) return

        Log.i(TAG, "Creating photo widget")

        val density = context.resources.displayMetrics.density
        val radiusPx = 8f * density
        val borderPx = (4f * density).toInt()

        // Card container: themed border with rounded corners. Prefer the active
        // webapp palette (via NativeThemeManager.bgPrimary) so the photo card
        // matches the web widgets next to it; fall back to the original
        // hardcoded values when no theme has been pushed yet.
        val isDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context) ?: false
        val borderColor = com.dashieapp.Dashie.halite.theming.NativeThemeManager
            .getBgPrimaryOr(if (isDark) Color.parseColor("#2A2A3E") else Color.WHITE)
        val cardContainer = FrameLayout(context).apply {
            val bg = GradientDrawable().apply {
                setColor(borderColor)
                cornerRadius = radiusPx
            }
            background = bg
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
            clipToOutline = true
        }

        // Slideshow view with inner radius
        val view = PhotoSlideshowView(context).apply {
            val innerRadius = radiusPx - borderPx
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, innerRadius)
                }
            }
            clipToOutline = true
        }
        slideshowView = view

        val innerParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            setMargins(borderPx, borderPx, borderPx, borderPx)
        }
        cardContainer.addView(view, innerParams)

        // Gesture handling
        setupGestures(cardContainer)

        // Position in root container
        val params = calculateLayoutParams()
        rootContainer.addView(cardContainer, params)
        _widgetContainer = cardContainer
        visibilityGate?.register(cardContainer)

        // Configure the view (metadata etc.). Re-applied on reconfigure() so a
        // live "Show Info" toggle takes effect without a dashboard reload.
        applyViewConfig(view)

        // Start photo source (shared with screensaver when available)
        builtSourceType = screensaverPrefs.photoSourceType   // remember what we built with (self-heal on drift)
        sourceFactory = PhotoSourceFactory(context, screensaverPrefs, halitePrefs).also {
            it.sharedPhotosProvider = sharedPhotosProvider
        }
        sourceFactory?.setup(
            view = view,
            scope = scope,
            webViewProvider = { webViewProvider() },
            onDestroyed = { isDestroyed },
            onReady = { ss ->
                slideshow = ss
                Log.i(TAG, "Photo widget slideshow started")
            }
        )
    }

    private fun repositionWidget() {
        val container = widgetContainer ?: return
        val params = calculateLayoutParams()
        container.layoutParams = params
        visibilityGate?.register(container)
        Log.d(TAG, "Repositioned photo widget")
    }

    /**
     * Calculate pixel-based FrameLayout.LayoutParams.
     * When gridCols == 0, values are physical pixels from JS getBoundingClientRect.
     */
    private fun calculateLayoutParams(): FrameLayout.LayoutParams {
        if (gridCols == 0) {
            // slotX/slotY/slotW/slotH are physical px from JS
            // getBoundingClientRect() × devicePixelRatio. Since the JS
            // layout canvas already offsets itself to the right of the
            // native sidebar via a CSS var (see
            // js/core/native-sidebar-layout.js), slotX is in real
            // screen coordinates — no scaleX/pivot compensation needed.
            Log.d(TAG, "Layout (pixel mode): content($slotX,$slotY ${slotW}x$slotH)")
            return FrameLayout.LayoutParams(slotW, slotH).apply {
                leftMargin = slotX
                topMargin = slotY
            }
        }

        // Grid mode fallback
        val containerW = rootContainer.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val containerH = rootContainer.height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val canvasInset = 4; val clipInset = 4
        val areaW = containerW - canvasInset * 2; val areaH = containerH - canvasInset * 2

        val pixelX = canvasInset + clipInset + (slotX.toFloat() / gridCols * areaW).toInt()
        val pixelY = canvasInset + clipInset + (slotY.toFloat() / gridRows * areaH).toInt()
        val pixelW = (slotW.toFloat() / gridCols * areaW).toInt() - clipInset * 2
        val pixelH = (slotH.toFloat() / gridRows * areaH).toInt() - clipInset * 2

        return FrameLayout.LayoutParams(pixelW, pixelH).apply {
            leftMargin = pixelX; topMargin = pixelY
        }
    }

    // ========================================================================
    // Touch gestures
    // ========================================================================

    private fun setupGestures(container: FrameLayout) {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Route tap to the JS photos-menu coordinator. JS picks
                // open vs close based on its current state (toggleForTouch),
                // so a tap on the photo cell while the menu is already
                // open closes it.
                //
                // We pass slot bounds explicitly because the JS
                // coordinator's default bounds lookup querySelectors a
                // .dashboard-grid__cell that may not exist when the native
                // photo widget owns the slot.
                val webView = webViewProvider()
                Log.i(TAG, "onSingleTapConfirmed: webView=${webView != null} slot=($slotX,$slotY ${slotW}x$slotH) gridCols=$gridCols")
                if (webView != null) {
                    val (px, py, pw, ph) = if (gridCols == 0) {
                        intArrayOf(slotX, slotY, slotW, slotH).let { listOf(it[0], it[1], it[2], it[3]) }
                    } else {
                        listOf(-1, -1, -1, -1)
                    }
                    val js = if (px >= 0) {
                        "window.photosMenu && window.photosMenu.toggleForTouch && window.photosMenu.toggleForTouch('photos', $px, $py, $pw, $ph)"
                    } else {
                        "window.photosMenu && window.photosMenu.toggleForTouch && window.photosMenu.toggleForTouch('photos')"
                    }
                    Log.d(TAG, "Dispatching to JS: $js")
                    webView.evaluateJavascript(js) { result ->
                        Log.d(TAG, "evaluateJavascript callback: result=$result")
                    }
                } else {
                    Log.w(TAG, "WebView null — falling back to openExplorer")
                    openExplorer()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                showUploadQRDialog()
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(e2.y - e1.y) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX < 0) slideshow?.next() else slideshow?.previous()
                    return true
                }
                return false
            }
        })

        container.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    /** Resolve the host for upload links based on build flavor. */
    private fun mobileUploadHost(): String {
        val pkg = context.packageName
        val isStaging = pkg.contains("staging") || pkg.contains("local")
        return if (isStaging) "https://dev.dashieapp.com" else "https://app.dashieapp.com"
    }

    /**
     * Build the QR-encoded URL the user's phone visits. Encodes the
     * pairing code so /photos auto-submits on load (one-tap from QR).
     * Code is null while it's being fetched async — show fallback URL
     * (just /photos) so a user could still type the code manually if
     * something goes wrong with the fetch.
     */
    private fun buildPairingUrl(code: String?): String {
        val host = mobileUploadHost()
        return if (code != null) "$host/photos?code=$code" else "$host/photos"
    }

    /**
     * Build the user-visible short URL displayed below the QR for users who
     * can't scan. Points to the Dashie Console (`/console`) — a desktop
     * upload path that doesn't need a pairing code (users sign into the
     * console directly). The QR itself still encodes the `/photos` mobile
     * pairing URL (see buildPairingUrl).
     */
    private fun buildDisplayUrl(): String {
        return mobileUploadHost().removePrefix("https://") + "/console"
    }

    /**
     * "or go to {displayUrl} to upload through the Dashie Console" with the
     * URL portion styled orange (#FF9500) + bold. Single TextView lets the
     * line wrap naturally; spans preserve the URL highlighting across the
     * wrap.
     */
    private fun buildUrlRowSpannable(displayUrl: String): android.text.SpannableString {
        val prefix = "or go to "
        val suffix = " to upload through the Dashie Console"
        val full = "$prefix$displayUrl$suffix"
        val spannable = android.text.SpannableString(full)
        val urlStart = prefix.length
        val urlEnd = urlStart + displayUrl.length
        spannable.setSpan(
            android.text.style.ForegroundColorSpan(0xFFFF9500.toInt()),
            urlStart, urlEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
            urlStart, urlEnd,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }

    /**
     * Long-press handler — fetches a paired upload code from JS, then
     * shows a QR + the visible code so the user can scan or type to pair
     * their phone for a 30-min upload session. Limited to 250MB per code.
     *
     * Uses the same XML-driven dialog pattern as the voice license QR
     * (see dialog_purchase_voice.xml + VoiceLicenseUI) so the QR-based
     * pairing flows feel consistent across the app.
     *
     * See .reference/build-plans/20260426_PHOTO_UPLOAD_AUTH_FLOW.md.
     */
    fun showUploadQRDialog() {
        val activity = context as? Activity ?: run {
            Log.w(TAG, "Cannot show upload QR — context is not an Activity")
            return
        }

        Log.i(TAG, "Long-press detected — generating upload pairing code")

        val displayUrl = buildDisplayUrl()
        val dialogView = activity.layoutInflater.inflate(
            com.dashieapp.Dashie.R.layout.dialog_photo_upload_qr, null
        )

        dialogView.findViewById<TextView>(com.dashieapp.Dashie.R.id.photoUploadDialogTitle)?.text =
            activity.getString(com.dashieapp.Dashie.R.string.photo_upload_title,
                activity.getString(com.dashieapp.Dashie.R.string.brand_name))
        val loadingView = dialogView.findViewById<TextView>(com.dashieapp.Dashie.R.id.photoUploadLoading)
        val qrImage = dialogView.findViewById<ImageView>(com.dashieapp.Dashie.R.id.photoUploadQrImage)
        val urlRow = dialogView.findViewById<TextView>(com.dashieapp.Dashie.R.id.photoUploadUrlRow)
        val codeBox = dialogView.findViewById<LinearLayout>(com.dashieapp.Dashie.R.id.photoUploadCodeBox)
        val codeText = dialogView.findViewById<TextView>(com.dashieapp.Dashie.R.id.photoUploadCodeText)
        val instructionsView = dialogView.findViewById<TextView>(com.dashieapp.Dashie.R.id.photoUploadInstructions)
        val doneButton = dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.photoUploadDoneButton)

        // "or go to <displayUrl> to upload through the Dashie Console" — URL
        // portion rendered orange-bold via SpannableString so the line wraps
        // cleanly inside a single TextView.
        urlRow.text = buildUrlRowSpannable(displayUrl)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        doneButton.setOnClickListener { dialog.dismiss() }
        // When the dialog dismisses, re-open the photos menu bar so the
        // user lands back in a visible state instead of an empty
        // focused widget with no UI to interact with.
        dialog.setOnDismissListener {
            val webView = webViewProvider()
            val container = _widgetContainer
            if (webView != null && container != null) {
                val loc = IntArray(2)
                container.getLocationInWindow(loc)
                val js = "window.photosMenu && window.photosMenu.activateAt && " +
                    "window.photosMenu.activateAt('photos', ${loc[0]}, ${loc[1]}, ${container.width}, ${container.height})"
                webView.evaluateJavascript(js, null)
            }
        }
        dialog.show()

        fetchUploadPairingCode { code, error ->
            if (!dialog.isShowing) return@fetchUploadPairingCode

            if (error != null || code == null) {
                Log.w(TAG, "Pairing code fetch failed ($error) — showing error in dialog")
                loadingView.text = "Could not generate code:\n$error\n\nVisit $displayUrl on your phone to enter one manually."
                loadingView.setTextColor(Color.parseColor("#CC3300"))
                return@fetchUploadPairingCode
            }

            renderQRDialogContent(
                code = code,
                loadingView = loadingView,
                qrImage = qrImage,
                urlRow = urlRow,
                codeBox = codeBox,
                codeText = codeText,
                instructionsView = instructionsView
            )
        }
    }

    /**
     * Populate the inflated dialog views with the QR and pairing code.
     * Toggles visibility from the loading placeholder to the populated
     * QR / URL row / code box / instructions views.
     */
    private fun renderQRDialogContent(
        code: String,
        loadingView: TextView,
        qrImage: ImageView,
        urlRow: View,
        codeBox: LinearLayout,
        codeText: TextView,
        instructionsView: TextView
    ) {
        val orangeColor = 0xFFFF9500.toInt()
        val qrUrl = buildPairingUrl(code)
        val qrBitmap = QrCodeGenerator.generateQrCode(
            url = qrUrl,
            size = 512,
            foregroundColor = orangeColor
        )
        if (qrBitmap == null) {
            Log.w(TAG, "QR generation failed")
            loadingView.text = "Could not generate QR. Please try again."
            return
        }

        qrImage.setImageBitmap(qrBitmap)
        codeText.text = formatCodeForDisplay(code)

        loadingView.visibility = View.GONE
        qrImage.visibility = View.VISIBLE
        urlRow.visibility = View.VISIBLE
        codeBox.visibility = View.VISIBLE
        instructionsView.visibility = View.VISIBLE
    }

    /** Insert a hyphen mid-code for readability — "FXKW7H2P" → "FXKW-7H2P". */
    private fun formatCodeForDisplay(code: String): String {
        if (code.length != 8) return code
        return "${code.substring(0, 4)}-${code.substring(4)}"
    }

    /**
     * Call into the JS-side bridge installed at
     * window.dashieGenerateUploadPairingCode (see
     * js/widgets/photos/upload-pairing-bridge.js) to mint a paired upload
     * code.
     *
     * The bridge is intentionally NOT a Promise-returning async function:
     * Android WebView's evaluateJavascript doesn't await Promises (it just
     * serializes the Promise object, which yields `{}`). Instead the bridge
     * kicks off async work and writes the result into
     * `window.__dashieLastPairingResult`. We poll that variable until it
     * becomes non-null (or we time out). All callbacks run on main thread.
     */
    private fun fetchUploadPairingCode(callback: (code: String?, error: String?) -> Unit) {
        val webView = webViewProvider() ?: run {
            mainHandler.post { callback(null, "no_webview") }
            return
        }

        webView.post {
            webView.evaluateJavascript("typeof window.dashieGenerateUploadPairingCode") { typeResult ->
                Log.i(TAG, "Bridge function typeof: $typeResult")
                if (typeResult == null || !typeResult.contains("function")) {
                    mainHandler.post { callback(null, "bridge_not_installed (typeof=$typeResult)") }
                    return@evaluateJavascript
                }

                // Kick off the async work — bridge returns "kicked-off" sync.
                webView.evaluateJavascript("window.dashieGenerateUploadPairingCode()") { kickResult ->
                    Log.i(TAG, "Bridge kick result: $kickResult")
                    pollForPairingResult(webView, attempt = 0, callback = callback)
                }
            }
        }
    }

    /**
     * Poll window.__dashieLastPairingResult until it's non-null (success or
     * error JSON) or we hit the timeout. Polls at 200ms intervals up to ~10s.
     */
    private fun pollForPairingResult(
        webView: android.webkit.WebView,
        attempt: Int,
        callback: (code: String?, error: String?) -> Unit
    ) {
        val maxAttempts = 50  // 50 * 200ms = 10s
        val pollIntervalMs = 200L

        if (attempt >= maxAttempts) {
            mainHandler.post { callback(null, "timeout_waiting_for_bridge") }
            return
        }

        webView.evaluateJavascript("window.__dashieLastPairingResult") { rawResult ->
            // evaluateJavascript wraps strings in JSON quotes and escapes
            // contents — unwrap one layer to get the raw JSON the bridge wrote.
            if (rawResult == null || rawResult == "null") {
                // Not yet ready — schedule a retry.
                mainHandler.postDelayed({
                    pollForPairingResult(webView, attempt + 1, callback)
                }, pollIntervalMs)
                return@evaluateJavascript
            }

            try {
                val unwrapped = if (rawResult.startsWith("\"") && rawResult.endsWith("\"")) {
                    org.json.JSONTokener(rawResult).nextValue() as? String ?: rawResult
                } else rawResult
                val json = JSONObject(unwrapped)
                if (json.has("error")) {
                    val err = json.optString("error")
                    val detail = if (json.has("detail")) " (${json.optString("detail")})" else ""
                    Log.w(TAG, "Pairing code error: $err$detail")
                    mainHandler.post { callback(null, "$err$detail") }
                } else {
                    val code = json.optString("code", "").takeIf { it.isNotBlank() }
                    if (code != null) {
                        Log.i(TAG, "Got pairing code (attempt $attempt): $code")
                        mainHandler.post { callback(code, null) }
                    } else {
                        mainHandler.post { callback(null, "no_code_in_response") }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse pairing-code response: $rawResult", e)
                mainHandler.post { callback(null, "parse_error: ${e.message}") }
            }
        }
    }

    /**
     * Open the full-screen photo explorer overlay.
     * Uses the rootLayout (above sidebar) so it renders on top of everything.
     *
     * Public so the photos-menu coordinator (JS) can invoke this via the
     * DashieNative.openPhotosExplorer bridge when the user picks the
     * "Full Screen" tab.
     */
    fun openExplorer() {
        val ss = slideshow ?: return
        val currentPhoto = ss.getCurrentPhoto() ?: return
        val allPhotos = ss.getPhotos()
        if (allPhotos.isEmpty()) return

        // rootContainer is widgetContainer — go up to rootLayout for proper z-ordering
        val appRoot = rootContainer.parent as? ViewGroup ?: return

        photoExplorer?.dismiss()
        val explorer = PhotoExplorerOverlay(context, screensaverPrefs)
        photoExplorer = explorer
        // Pass the widget slideshow's streaming fetcher so the explorer can
        // pull fresh photos when the cached list points to evicted files
        // (Immich/HA-media rolling cache — see PhotoSlideshow.next() fallback).
        explorer.show(currentPhoto, allPhotos, appRoot, fetchNextPhoto = ss.fetchNextPhoto)
        Log.i(TAG, "Opened photo explorer from widget tap")
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    fun pause() { slideshow?.pause() }
    fun resume() { slideshow?.resume() }

    /** Manually advance the slideshow. Used by the JS photos-menu
     *  coordinator's 'activated' state — left/right cycles photos. */
    fun next() { slideshow?.next() }
    fun previous() { slideshow?.previous() }

    fun setVisible(visible: Boolean) {
        mainHandler.post {
            // Honor the gate's full visibility rules — auth state, fullscreen
            // suppression, etc. A rotation tick that fires while a JS modal
            // is up, dashboard is reloading, or the user isn't authenticated
            // MUST NOT pop the widget on top. Use shouldShowFull() rather
            // than partial flag checks to keep this in sync with the gate's
            // authoritative rule (FULL_ONLY kind). Mirrors the same fix
            // applied to WeatherWidgetController.setVisible.
            val gate = visibilityGate
            val container = _widgetContainer
            if (!visible) {
                // D.50 — JS layout doesn't include this widget. Unregister
                // from the gate so a later setUiMode(FULL) → applyVisibility
                // pass doesn't override our hide back to VISIBLE. JS layout
                // is the source of truth for whether this widget belongs in
                // the current layout; the gate only owns the gross "should
                // any FULL_ONLY widget be visible right now" axis (auth /
                // reload). Re-register on next setVisible(true).
                container?.let { gate?.unregister(it) }
                container?.visibility = View.GONE
                return@post
            }
            // Showing: re-register so subsequent gate mode flips apply.
            container?.let { gate?.register(it) }
            if (gate != null && !gate.shouldShowFull()) {
                container?.visibility = View.GONE
                Log.d(TAG, "setVisible(true) suppressed by gate (suppressed=${gate.isFullscreenSuppressed()}, mode=${gate.getMode()})")
                return@post
            }
            container?.visibility = View.VISIBLE
            reconfigureIfSourceChanged()   // catch source drift (sync equality-skip / sign-in race) on show
        }
    }

    /**
     * Apply theme changes (border color adapts to dark/light mode).
     */
    fun applyTheme(isDark: Boolean) {
        mainHandler.post {
            val container = widgetContainer ?: return@post
            val borderColor = com.dashieapp.Dashie.halite.theming.NativeThemeManager
                .getBgPrimaryOr(if (isDark) Color.parseColor("#2A2A3E") else Color.WHITE)
            val bg = container.background
            if (bg is GradientDrawable) {
                bg.setColor(borderColor)
                container.invalidate()
            }
            // Refresh placeholder text colors inside the slideshow view — its
            // "Click to add photos" text was hardcoded white and went
            // invisible against the now-themed light bg.
            slideshowView?.refreshThemedColors(isDark)
            Log.d(TAG, "Applied theme: isDark=$isDark")
        }
    }

    /** Read the active source type used by the slideshow. JS uses this to
     *  populate the menu's Source label since the native widget's value
     *  is the canonical truth on Android. */
    fun currentSourceType(): String = screensaverPrefs.photoSourceType

    /** True when the slideshow is currently rendering the Dashie Cloud
     *  "Click to add photos" empty state. JS reads this when the user
     *  d-pads onto the widget so the photos menu can skip the activated/
     *  hint state and open the Upload bar directly. Returns false for any
     *  source other than supabase or whenever real photos are showing. */
    fun isShowingDashieCloudEmptyState(): Boolean =
        slideshowView?.isShowingDashieCloudEmptyState() ?: false

    /** Update the source type and reconfigure the slideshow so the change
     *  takes effect immediately. Must be called on the UI thread. */
    fun setSourceType(sourceType: String) {
        // Keep the pref current, then rebuild iff the BUILT source differs (guarding on
        // the pref would no-op when the pref already matches but the widget is stale).
        screensaverPrefs.photoSourceType = sourceType
        if (builtSourceType == sourceType) return
        Log.i(TAG, "setSourceType: built=$builtSourceType -> $sourceType")
        reconfigure()
    }

    /** Self-heal: rebuild if the current photoSourceType pref no longer matches the
     *  source the widget was BUILT with — covers the drift that ACTION_PHOTO_SOURCE_CHANGED
     *  misses (sync equality-skip, sign-in race). Called on setVisible(true). */
    fun reconfigureIfSourceChanged() {
        mainHandler.post {
            if (isDestroyed || slideshowView == null) return@post
            val current = screensaverPrefs.photoSourceType
            if (builtSourceType != current) {
                Log.i(TAG, "Photo source drift (built=$builtSourceType, pref=$current) — self-healing reconfigure")
                reconfigure()
            }
        }
    }

    /** Apply the view-level config (metadata etc.). Called at setup and on every
     *  reconfigure so a live "Show Info" toggle (ACTION_PHOTO_SETTINGS_CHANGED →
     *  reconfigure) re-reads ScreensaverPreferences.showMetadata without a reload. */
    private fun applyViewConfig(view: PhotoSlideshowView) {
        view.configure(
            showMetadata = screensaverPrefs?.showMetadata ?: true,
            showClock = false,
            use24Hour = halitePrefs?.display?.use24HourClock ?: false,
            showDate = false,
            weatherMode = "disabled"
        )
        view.setMetadataScale(WIDGET_METADATA_SCALE)
        // Scaling follows photoFit ("fit" letterbox default | "fill" CENTER_CROP).
        // applyViewConfig runs on every reconfigure (incl. ACTION_PHOTO_SETTINGS_CHANGED),
        // so a live Fit↔Fill change re-applies to the widget without a reload.
        view.fitLandscapePhotos = screensaverPrefs.photoFit != "fill"
    }

    fun reconfigure() {
        Log.i(TAG, "Reconfiguring photo widget")
        slideshow?.stop(); slideshow = null; sourceFactory?.cleanup()
        val view = slideshowView ?: return
        // The VIEW is reused across a source switch — only the slideshow and factory are rebuilt.
        // So any empty state the OUTGOING source put up is still on screen, and the incoming
        // source has no reason to touch it. That is how "Click to add photos" ended up on top of
        // a live Immich photo (2026-08-25). Clear it here so each source starts from a
        // blank view; PhotoSlideshowView.displayPhoto enforces the same invariant as a backstop.
        view.hideEmptyState()
        applyViewConfig(view)   // re-read showMetadata etc. on live config change
        builtSourceType = screensaverPrefs.photoSourceType   // keep in sync with what we rebuild for
        sourceFactory = PhotoSourceFactory(context, screensaverPrefs, halitePrefs).also {
            it.sharedPhotosProvider = sharedPhotosProvider
        }
        sourceFactory?.setup(
            view = view, scope = scope,
            webViewProvider = { webViewProvider() },
            onDestroyed = { isDestroyed },
            onReady = { ss -> slideshow = ss; Log.i(TAG, "Photo widget reconfigured") }
        )
    }

    fun removeWidget() {
        Log.i(TAG, "Removing photo widget")
        slideshow?.stop(); slideshow = null
        sourceFactory?.cleanup(); sourceFactory = null
        _widgetContainer?.let { rootContainer.removeView(it) }
        _widgetContainer = null; slideshowView = null
    }

    fun destroy() {
        Log.i(TAG, "Destroying photo widget controller")
        isDestroyed = true
        // Tell JS to tear down hint / bar / settings if any are showing.
        photoExplorer?.dismiss()
        photoExplorer = null
        removeWidget(); scope.cancel()
    }

    // ========================================================================
    // NativeDpadWidget — d-pad input is owned by JsCoordinatedDpadWidget
    // base class; it dispatches everything to window.photosMenu (the JS
    // coordinator), which drives the shared widget overlay (hint + bar +
    // settings).
    //
    // We intercept here only when the photo explorer (Kotlin-owned
    // fullscreen overlay) is open — in that mode keys belong to the
    // explorer, not the menu.
    // ========================================================================

    override fun onNativeNavKey(action: String): Boolean? {
        val explorer = photoExplorer ?: return null  // null → forward to JS
        return when (action) {
            "left"   -> { explorer.previous(); true }
            "right"  -> { explorer.next(); true }
            "enter", "escape" -> {
                explorer.dismiss()
                photoExplorer = null
                // After dismissing the explorer, re-open the menu bar so
                // the user lands in a visible state instead of an empty
                // focused widget with no UI to interact with.
                val webView = webViewProvider()
                val container = widgetContainer
                if (webView != null && container != null) {
                    val loc = IntArray(2)
                    container.getLocationInWindow(loc)
                    val js = "window.$jsCoordinatorName && window.$jsCoordinatorName.activateAt && " +
                        "window.$jsCoordinatorName.activateAt('$widgetId', ${loc[0]}, ${loc[1]}, ${container.width}, ${container.height})"
                    webView.evaluateJavascript(js, null)
                }
                true
            }
            else -> true  // consume up/down while explorer is open
        }
    }
}
