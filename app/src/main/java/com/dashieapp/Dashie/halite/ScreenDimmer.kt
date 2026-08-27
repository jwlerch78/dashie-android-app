package com.dashieapp.Dashie.halite

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.halite.screensaver.HaMediaPhotoSource
import com.dashieapp.Dashie.halite.screensaver.LocalPhotoSource
import kotlinx.coroutines.withContext
import com.dashieapp.Dashie.halite.screensaver.MotionDetector
import com.dashieapp.Dashie.halite.screensaver.PhotoExplorerOverlay
import com.dashieapp.Dashie.halite.screensaver.PhotoItem
import com.dashieapp.Dashie.halite.screensaver.PhotoPreviewOnWake
import com.dashieapp.Dashie.halite.screensaver.PhotoSlideshow
import com.dashieapp.Dashie.halite.screensaver.PhotoSlideshowView
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manages screen dimming/screensaver functionality for Dashie Lite kiosk mode.
 *
 * After a configurable timeout of inactivity, the screen dims to save power
 * and reduce burn-in. Any touch interaction wakes the screen back up.
 *
 * Supports four modes:
 * - "dim" (default): Darkens screen to 85% opacity
 * - "black": Full black screen (100% opacity)
 * - "url": Display a webpage in a WebView (e.g., clock + weather)
 * - "photos": Photo slideshow with optional motion wake
 */
class ScreenDimmer(
    private val context: Context,
    private val dimOverlay: ViewGroup,
    private val photoContainer: FrameLayout,
    private val prefs: ScreensaverPreferences,
    private var timeoutSeconds: Int,
    private var screensaverMode: String = "dim",
    private val onDimStateChanged: ((isDimmed: Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "ScreenDimmer"
        private const val BLACK_ALPHA = 1.0f // Black mode: completely black screen
        private const val PHOTOS_ALPHA = 1.0f // Photos mode: full opacity
        private const val URL_ALPHA = 1.0f   // URL mode: full opacity (WebView shows content)
        private const val APP_ALPHA = 0f     // App mode: transparent (app launches, overlay hidden)
        private const val HA_PAGE_ALPHA = 0f  // HA Page mode: transparent (main WebView shows HA page)
        private const val ANIMATION_DURATION = 500L  // ms for fade animation
    }

    /**
     * Get the target alpha based on current screensaver mode
     */
    private fun getTargetAlpha(): Float {
        return when (screensaverMode) {
            "black", "off", "weather" -> BLACK_ALPHA  // "off" uses black overlay before hardware off
            "photos" -> PHOTOS_ALPHA
            "url" -> URL_ALPHA
            "app" -> APP_ALPHA
            "ha_page" -> HA_PAGE_ALPHA
            else -> {
                // Dim mode: convert brightness percentage to alpha opacity
                // brightness 15% = alpha 0.85 (85% opaque overlay)
                val brightness = prefs.dimBrightness
                1.0f - (brightness / 100f)
            }
        }
    }

    // Hardware screen-control (device admin, lockNow, wake, brightness reduce/restore)
    private val hardwareController = com.dashieapp.Dashie.halite.screensaver.ScreenHardwareController(
        context = context,
        brightnessPauseCallbackProvider = { onBrightnessPauseChanged }
    )

    /** Check if Dashie has Device Admin privileges (required for hardware screen off). */
    fun isDeviceAdminEnabled(): Boolean = hardwareController.isDeviceAdminEnabled()

    /** Request Device Admin privileges — returns an Intent to launch system settings. */
    fun getDeviceAdminEnableIntent(): Intent = hardwareController.getDeviceAdminEnableIntent()

    /** Turn off screen hardware via DevicePolicyManager.lockNow. Returns false if Device Admin not enabled. */
    fun turnOffScreenHardware(): Boolean = hardwareController.turnOffScreenHardware()

    private val handler = Handler(Looper.getMainLooper())
    private var isDimmed = false
    private var isEnabled = true
    private var currentAnimator: ValueAnimator? = null

    // Screen off state - completely black, no timer-based wake
    // Different from screensaver - this is a true "display off" state
    private var isScreenOff = false
    private var modeBeforeScreenOff: String? = null  // Saves mode to restore after screenOn

    // External app screensaver mode — owns isExternalAppActive flag + return button
    private val externalAppController = com.dashieapp.Dashie.halite.screensaver.ExternalAppController(
        context = context,
        prefs = prefs,
        dimOverlay = dimOverlay,
        backgroundViewProvider = { backgroundView }
    )

    // Photo slideshow components
    private var slideshowView: PhotoSlideshowView? = null
    private var slideshow: PhotoSlideshow? = null
    // Photo source setup is delegated to PhotoSourceFactory (which handles
    // Local, HA Media, Immich, Unsplash, Google Drive, and Dashie Cloud /
    // Supabase). The factory was originally written for the photo widget
    // and was the only path with a SUPABASE case wired up — pre-step-6,
    // ScreenDimmer had a duplicate dispatcher missing the SUPABASE case,
    // which is what made Dashie Cloud silently broken in the screensaver.
    private var photoSourceFactory: com.dashieapp.Dashie.halite.widgets.PhotoSourceFactory? = null
    // Separate HaMediaPhotoSource used only by the on-startup prewarm path
    // (kept here so the prewarm can run before the factory is created and
    // before the screensaver activates). The factory creates its own
    // HaMediaPhotoSource at activation time; both share the disk cache.
    private var haMediaPhotoSource: HaMediaPhotoSource? = null
    private var motionDetector: MotionDetector? = null

    // Last photo data for "preview on wake" feature
    private var lastWakePhoto: PhotoItem? = null
    private var lastWakePhotoList: List<PhotoItem> = emptyList()
    private var photoPreviewOnWake: PhotoPreviewOnWake? = null
    private var photoExplorerOverlay: PhotoExplorerOverlay? = null

    /**
     * Get the current screensaver's photo list for sharing with the photo widget.
     * Returns the slideshow's photos if active, or the last wake photos if not.
     */
    fun getSharedPhotos(): List<PhotoItem> {
        return slideshow?.getPhotos()?.takeIf { it.isNotEmpty() }
            ?: lastWakePhotoList.takeIf { it.isNotEmpty() }
            ?: emptyList()
    }

    // Coroutine scope for async operations (HA Media photo fetching)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Track if destroyed to prevent async callbacks from crashing
    private var isDestroyed = false

    // WebView reference for extracting HA tokens (set by caller)
    private var webViewRef: WebView? = null

    /**
     * Callback invoked when hardware wake is needed (from screenOn()).
     * The parent component (HaliteScreenController) should set this to apply
     * Activity-level window flags that reliably wake the device.
     *
     * This is more reliable than PowerManager wake locks on modern Android,
     * especially on Samsung devices with aggressive power management.
     */
    var onHardwareWakeRequested: (() -> Unit)? = null

    /**
     * Callback to navigate the main WebView to an HA page path (ha_page mode).
     * Set by HaliteScreenController. Called with the page path on activation,
     * and with null on deactivation (meaning: navigate back to home dashboard).
     */
    var onHaPageNavigate: ((pagePath: String?) -> Unit)? = null

    // Tracks whether ha_page mode navigation is active (for cleanup on wake)
    private var isHaPageActive = false

    // HalitePreferences for token caching
    private var halitePrefs: HalitePreferences? = null

    /** Called when screen is dimmed/woken so auto-brightness can pause/resume. */
    var onBrightnessPauseChanged: ((paused: Boolean) -> Unit)? = null

    // URL/WebView screensaver mode
    private val urlController = com.dashieapp.Dashie.halite.screensaver.UrlScreensaverController(
        context = context,
        photoContainer = photoContainer,
        prefs = prefs,
        onTouchForwarded = { event -> onTouchEvent(event) }
    )

    // Scale factor matching PhotoSlideshowView for consistent clock/date sizing
    private val uiScaleFactor: Float by lazy {
        val dm = context.resources.displayMetrics
        val shortSidePx = minOf(dm.widthPixels, dm.heightPixels)
        val normalizedShortSide = shortSidePx / dm.density
        val baseNormalizedSize = 1080f
        (normalizedShortSide / baseNormalizedSize).coerceIn(0.5f, 1.2f)
    }

    // Background view (black) - needs to be hidden for URL/photos modes
    private var backgroundView: View? = null

    // RTSP streaming state - when true, skip camera-based motion detection
    // because RTSP is already using the camera and handles motion detection
    private var isRtspStreaming = false

    // Foreground tracking - prevents screensaver from triggering when app is in background
    private var isInForeground = true

    // Clock + date overlay (clock tick, anchor logic, anti-burn-in flip
    // timer, HA time provider). Forward-declared so weatherController can
    // capture it in its provider lambdas. Explicit type annotation breaks
    // the cyclic-reference type-inference loop (clockController <-> weatherController).
    private val clockController: com.dashieapp.Dashie.halite.screensaver.ClockOverlayController =
        com.dashieapp.Dashie.halite.screensaver.ClockOverlayController(
        context = context,
        dimOverlay = dimOverlay,
        prefs = prefs,
        halitePrefsProvider = { halitePrefs },
        screensaverModeProvider = { screensaverMode },
        isVideoFeedModeProvider = { isVideoFeedMode },
        uiScaleFactorProvider = { uiScaleFactor },
        slideshowViewProvider = { slideshowView },
        weatherOverlayActive = { weatherController.isOverlayActive() },
        applyWeatherCardForClockAnchor = { anchor ->
            weatherController.applyCardForClockAnchor(anchor, clockController.computeClockZonePx())
        },
        applyWeatherPosition = { weatherController.applyPosition() }
    )

    // Weather overlay (forecast card + inline current weather)
    private val weatherController: com.dashieapp.Dashie.halite.screensaver.WeatherOverlayController =
        com.dashieapp.Dashie.halite.screensaver.WeatherOverlayController(
        context = context,
        dimOverlay = dimOverlay,
        prefs = prefs,
        halitePrefsProvider = { halitePrefs },
        screensaverModeProvider = { screensaverMode },
        weatherTextViewProvider = { clockController.getWeatherTextView() },
        slideshowViewProvider = { slideshowView },
        clockTextViewProvider = { clockController.getClockTextView() },
        clockOnRightProvider = { clockController.clockOnRight },
        uiScaleFactorProvider = { uiScaleFactor },
        effectiveClockFontSizeProvider = { clockController.getEffectiveClockFontSize() },
        applyClockPositionCallback = { clockController.applyClockPosition() }
    )

    private val dimRunnable = Runnable {
        if (isEnabled && !isDimmed && isInForeground) {
            dimScreen()
        } else if (!isInForeground) {
            Log.d(TAG, "dimRunnable skipped - app is in background")
        }
    }

    init {
        // Start with overlay invisible
        dimOverlay.alpha = 0f
        dimOverlay.visibility = View.GONE

        // Start the inactivity timer
        if (timeoutSeconds > 0) {
            resetTimer()
        }

        Log.i(TAG, "ScreenDimmer initialized with ${timeoutSeconds}s timeout, mode=$screensaverMode")
    }

    /**
     * Reset the inactivity timer. Call this on any user interaction.
     * Note: Does NOT wake from screen off state - only screenOn() can do that.
     */
    fun resetTimer() {
        // If screen is in "off" state, ignore timer reset (don't wake)
        if (isScreenOff) {
            Log.d(TAG, "resetTimer ignored - screen is off")
            return
        }

        handler.removeCallbacks(dimRunnable)

        if (isDimmed) {
            wakeScreen()
        }

        if (isEnabled && timeoutSeconds > 0) {
            handler.postDelayed(dimRunnable, timeoutSeconds * 1000L)
        }
    }

    /**
     * Handle touch events - wake screen and reset timer
     * Returns true if the touch was consumed (screen was dimmed), false otherwise
     * Note: Does NOT wake from screen off state - only screenOn() can do that.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // If screen is in "off" state (via API screenOff), wake it up
            // Note: If hardware screen off is active, touch events won't reach us anyway
            if (isScreenOff) {
                Log.i(TAG, "Touch detected while screen off - waking screen")
                screenOn()
                return true
            }

            if (isDimmed) {
                // Wake up and consume the touch
                resetTimer()
                return true
            } else {
                // Just reset the timer, don't consume
                resetTimer()
            }
        }
        return false
    }

    /**
     * D.45 — key-press analogue of [onTouchEvent]. The dashboard on Fire TV
     * is d-pad-only (no touch screen), so without this any remote button
     * was a no-op against a dimmed screen. Same semantics as touch:
     * - screen off (API)  → screenOn(), consume (key only wakes).
     * - dimmed            → resetTimer (wakes), consume.
     * - awake             → resetTimer (re-arm), don't consume.
     * Hardware screen-off via Device Admin lockNow doesn't deliver keys to
     * the app, so there's no recovery from that here — only Power wakes it.
     */
    fun onKeyEvent(isDown: Boolean): Boolean {
        if (!isDown) return false
        Log.d(TAG, "🔑 onKeyEvent: isScreenOff=$isScreenOff isDimmed=$isDimmed mode=$screensaverMode")
        if (isScreenOff) {
            Log.i(TAG, "Key while screen off - waking screen")
            screenOn()
            return true
        }
        if (isDimmed) {
            resetTimer()
            return true
        }
        resetTimer()
        return false
    }

    /**
     * Dim the screen with a fade animation
     */
    private fun dimScreen() {
        if (isDimmed) return

        // Screensaver start ends the settings-PIN grace window (kiosk lock)
        LockGate.clearGrace()

        // RECOVERY: Detect stale screen-off state where screensaverMode is "black" but we're not
        // actually in screen-off mode. This can happen if the JS sleep timer was reinitialized
        // (e.g., WebView restart) after overnight sleep ended, so screenOn() was never called.
        // If the user's preference is different from "black", restore it.
        if (screensaverMode == "black" && !isScreenOff && modeBeforeScreenOff == null) {
            val preferredMode = prefs.screensaverMode
            if (preferredMode != "black" && preferredMode != "dim") {
                Log.i(TAG, "🔧 Recovering from stale screen-off state: restoring mode from 'black' to '$preferredMode'")
                screensaverMode = preferredMode
            }
        }

        val targetAlpha = getTargetAlpha()
        Log.i(TAG, "Dimming screen (mode=$screensaverMode, alpha=$targetAlpha)")
        isDimmed = true

        // Cancel any existing animation
        currentAnimator?.cancel()

        // Setup for content-based modes
        when (screensaverMode) {
            "photos" -> {
                // Hide black background so photos show through
                backgroundView?.visibility = View.GONE
                setupPhotoSlideshow()
            }
            "url" -> {
                // Hide black background so WebView content shows through
                backgroundView?.visibility = View.GONE
                setupUrlScreensaver()
            }
            "app" -> {
                // Launch external app - hide everything, app takes over
                backgroundView?.visibility = View.GONE
                photoContainer.visibility = View.GONE
                launchExternalApp()
            }
            "ha_page" -> {
                // Navigate main WebView to configured HA page path
                backgroundView?.visibility = View.GONE
                photoContainer.visibility = View.GONE
                val pagePath = prefs.haPagePath
                if (pagePath.isNotEmpty()) {
                    isHaPageActive = true
                    onHaPageNavigate?.invoke(pagePath)
                    Log.i(TAG, "HA Page screensaver: navigating to path '$pagePath'")
                } else {
                    Log.w(TAG, "HA Page screensaver: no page path configured, falling back to dim")
                    backgroundView?.visibility = View.VISIBLE
                    setupClockOverlay()
                }
            }
            "off" -> {
                // Screen off mode (screensaver setting) - ALWAYS turns off display hardware
                // This is different from the API screenOff() which respects the hardwareScreenOff preference
                backgroundView?.visibility = View.VISIBLE
                photoContainer.visibility = View.GONE
                // Set isScreenOff BEFORE lockNow() so wake attempts are blocked
                isScreenOff = true
                // Schedule hardware screen off after the fade animation completes
                handler.postDelayed({
                    if (isDimmed && screensaverMode == "off" && isScreenOff) {
                        if (!turnOffScreenHardware()) {
                            Log.w(TAG, "Hardware screen off failed - falling back to black overlay")
                            isScreenOff = false  // Reset since hardware off failed
                            // Show toast to inform user
                            Toast.makeText(
                                context,
                                "Screen off requires Device Admin permission",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }, ANIMATION_DURATION + 100)  // Wait for fade animation to complete
            }
            "black", "dim" -> {
                // Show black background for dim/black modes
                backgroundView?.visibility = View.VISIBLE
                // Ensure photo container is hidden for dim/black modes
                photoContainer.visibility = View.GONE
                // Show clock overlay if enabled
                setupClockOverlay()
            }
            "weather" -> {
                // Full-screen Weather & Time mode: black bg, centered
                // borderless forecast card, clock pinned to top.
                backgroundView?.visibility = View.VISIBLE
                photoContainer.visibility = View.GONE
                setupClockOverlay(forceShow = true)
            }
            else -> {
                // Show black background (default)
                backgroundView?.visibility = View.VISIBLE
                // Ensure photo container is hidden
                photoContainer.visibility = View.GONE
            }
        }

        // Setup weather based on mode (works on photos, dim, black, weather modes)
        val weatherMode = prefs.weatherMode
        val forecastModes = listOf("photos", "dim", "black")
        if (screensaverMode == "weather") {
            // Weather & Time mode always shows the full forecast card,
            // regardless of weatherMode setting. Use dark styling. Start
            // the flip timer so the clock cycles through 6 anchors and
            // the card re-centers in the opposite half each tick.
            weatherController.setupOverlay()
            weatherController.setDarkMode(true)
            // Apply initial card-position now that overlay exists.
            clockController.getWeatherClockAnchor()?.let { applyWeatherCardForClockAnchor(it) }
            startFlipTimer()
        } else if (weatherMode != "disabled" && screensaverMode in forecastModes) {
            if (weatherMode == "forecast") {
                weatherController.setupOverlay()
                // Use dark mode for dim/black screensaver to match music strip styling
                if (screensaverMode in listOf("dim", "black")) {
                    weatherController.setDarkMode(true)
                }
            }
            // For "current" mode, weather is shown inline via setupCurrentWeatherInline()
            if (weatherMode == "current") {
                weatherController.setupInlineCurrent()
            }
            // Start anti-burn-in flip timer (flips clock + weather sides every 15 min)
            startFlipTimer()
        } else if (prefs.clockPosition == "random" && prefs.showClock) {
            // No weather, but the clock wants to bounce around — start the
            // same flip timer so randomClockAnchor cycles every 15 min.
            startFlipTimer()
        }

        dimOverlay.visibility = View.VISIBLE

        currentAnimator = ValueAnimator.ofFloat(0f, targetAlpha).apply {
            duration = ANIMATION_DURATION
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                dimOverlay.alpha = animator.animatedValue as Float
            }
            start()
        }

        // Reduce hardware brightness for black/off modes if enabled
        if (screensaverMode in listOf("black", "off") && prefs.reduceBrightnessOnBlack) {
            reduceBrightness()
        }

        onDimStateChanged?.invoke(true)
    }

    /**
     * Setup the photo slideshow for photos mode. Photo source creation is
     * delegated to [PhotoSourceFactory] (shared with the photo widget).
     */
    private fun setupPhotoSlideshow() {
        Log.i(TAG, "Setting up photo slideshow, source type: ${prefs.photoSourceType}")

        // Create slideshow view if needed. Scaling follows the photoFit
        // pref ("fit" = letterbox-with-blur, the default; "fill" = CENTER_CROP).
        // Setting it at construction time means the first photo renders
        // correctly without waiting for the alone signal from
        // ScreensaverPanelCoordinator; it's re-read after configure() below
        // so a live Fit↔Fill change lands on the next screensaver activation.
        val view = slideshowView ?: PhotoSlideshowView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            fitLandscapePhotos = prefs.photoFit != "fill"
            photoContainer.addView(this)
        }
        slideshowView = view

        // Configure slideshow settings
        view.configure(
            showMetadata = prefs.showMetadata,
            showClock = prefs.showClock,
            use24Hour = halitePrefs?.display?.use24HourClock ?: false,
            clockPosition = prefs.clockPosition,
            clockFontSize = getEffectiveClockFontSize(),
            showDate = prefs.showDate,
            weatherMode = prefs.weatherMode,
            useDmyDateFormat = halitePrefs?.display?.dateFormat == "dmy"
        )
        // Re-read scaling on every (re)setup so a live Fit↔Fill change applies
        // when the screensaver next activates. "fill" → CENTER_CROP, else letterbox.
        view.fitLandscapePhotos = prefs.photoFit != "fill"
        // Sync clock side with ScreenDimmer's current flip state
        view.setClockSide(clockController.clockOnRight)

        // Show photo container
        photoContainer.visibility = View.VISIBLE

        // Delegate source setup to PhotoSourceFactory — handles all 6
        // source types (Local, HA Media, Immich, Unsplash, Google Drive,
        // Dashie Cloud / Supabase) via a single consistent path.
        val factory = photoSourceFactory ?: com.dashieapp.Dashie.halite.widgets.PhotoSourceFactory(
            context = context,
            prefs = prefs,
            halitePrefs = halitePrefs
        ).also { photoSourceFactory = it }

        factory.setup(
            view = view,
            scope = scope,
            webViewProvider = { webViewRef },
            onDestroyed = { isDestroyed },
            onReady = { newSlideshow -> slideshow = newSlideshow }
        )

        // Setup motion detection if camera mode is enabled.
        // Camera-mode only — face mode uses MotionWakeManager's two-stage pipeline.
        if (prefs.motionWakeMode == ScreensaverPreferences.MOTION_WAKE_CAMERA) {
            setupMotionDetection()
        }
    }

    /**
     * Pre-warm the HA Media photo cache on app startup.
     * This initializes the photo source and syncs/prefetches photos in the background
     * so they're ready immediately when the screensaver activates.
     *
     * Should be called after HA login completes and credentials are available.
     */
    fun prewarmPhotoCache() {
        val prefs = halitePrefs ?: return

        // Only prewarm if screensaver mode is photos
        if (screensaverMode != "photos") {
            Log.d(TAG, "🖼️ Prewarm skipped - screensaver mode is $screensaverMode")
            return
        }

        // Only prewarm if we already have a valid, non-expired token
        // Don't trigger token refresh on startup - this causes HA login notifications
        // The token will be refreshed naturally when the screensaver actually activates
        if (!prefs.connection.hasHaAccessToken || prefs.connection.isHaTokenExpired) {
            Log.d(TAG, "🖼️ Prewarm skipped - no valid token yet (will prewarm when screensaver activates)")
            return
        }

        val baseUrl = prefs.connection.haBaseUrl.ifEmpty {
            prefs.connection.haUrl.substringBefore("?").trimEnd('/')
        }
        if (baseUrl.isNotEmpty()) {
            prewarmWithCredentials(prefs.connection.haAccessToken, baseUrl)
        } else {
            Log.d(TAG, "🖼️ Prewarm skipped - no HA base URL")
        }
    }

    /**
     * Clear the photo cache (both disk and memory).
     * Called when user requests a full cache clear from sidebar.
     */
    /**
     * Reset the in-memory photo state shared with the photo widget.
     * Called from MainBroadcastManager when the user changes
     * `screensaver.photoSourceType` so the widget's reconfigure path
     * doesn't reuse photos from the previous source via
     * [getSharedPhotos] (which is what the
     * `PhotoSourceFactory.sharedPhotosProvider` reads). After this
     * call, getSharedPhotos returns empty and the next dim cycle's
     * setupPhotoSlideshow re-creates the factory with the new source.
     */
    fun resetPhotoSlideshowCacheForSourceChange() {
        slideshow?.stop()
        slideshow = null
        lastWakePhoto = null
        lastWakePhotoList = emptyList()
        photoSourceFactory?.cleanup()
        photoSourceFactory = null
        // Keep haMediaPhotoSource alive — the prewarm path manages it
        // and HA Media disk cache is shared across source-type switches.
        Log.i(TAG, "🖼️ Photo slideshow cache reset (source-type change)")
    }

    // Note: a public `clearPhotoCache()` used to live here, called from
    // the legacy HaliteSidebarController via SidebarCacheManager. Both
    // of those are dead code now (the new NativeSidebarController has
    // no clear-cache action and HaliteSidebarController is never
    // instantiated). The method has been removed; if a future "wipe
    // everything" UI is added, prefer
    // resetPhotoSlideshowCacheForSourceChange() and clear individual
    // disk caches via the source classes directly.

    /**
     * Prewarm photo cache with given credentials.
     */
    private fun prewarmWithCredentials(accessToken: String, baseUrl: String) {
        Log.i(TAG, "🖼️ Pre-warming photo cache from $baseUrl, folder=${prefs.haMediaFolder}")

        // Create HaMediaPhotoSource if not already created
        if (haMediaPhotoSource == null) {
            haMediaPhotoSource = HaMediaPhotoSource(context, prefs).also {
                it.setCredentials(accessToken, baseUrl)
                // Token provider reads fresh from halitePrefs each time
                it.tokenProvider = { halitePrefs?.connection?.haAccessToken?.ifEmpty { null } }
                // Set up token refresh callback
                it.onTokenRefreshNeeded = {
                    withContext(Dispatchers.IO) {
                        val halitePrefsLocal = halitePrefs
                        if (halitePrefsLocal != null) {
                            val result = HaTokenExtractor.refreshTokenSync(halitePrefsLocal)
                            if (result.success) {
                                Log.i(TAG, "🔑 Token refreshed successfully")
                                result.accessToken
                            } else {
                                Log.w(TAG, "🔑 Token refresh failed")
                                null
                            }
                        } else {
                            Log.w(TAG, "🔑 No halitePrefs available for token refresh")
                            null
                        }
                    }
                }
            }
        }

        // Check if we already have cached photos from disk
        val source = haMediaPhotoSource ?: return
        val existingCache = source.getPhotos()
        if (existingCache.isNotEmpty()) {
            Log.i(TAG, "🖼️ Prewarm: ${existingCache.size} photos already cached from disk")
        }

        // Sync and prefetch in background
        scope.launch {
            if (isDestroyed) return@launch
            val result = source.sync()
            if (result.success) {
                Log.i(TAG, "🖼️ Prewarm complete: ${result.photosFound} photos found, ${result.photosNew} newly cached")
            } else {
                Log.w(TAG, "🖼️ Prewarm sync failed: ${result.error}")
            }
        }
    }

    private fun setupUrlScreensaver() {
        urlController.setup()
        // Camera-mode motion detection runs alongside the URL screensaver
        // (face mode uses MotionWakeManager's two-stage pipeline instead).
        if (prefs.motionWakeMode == ScreensaverPreferences.MOTION_WAKE_CAMERA) {
            setupMotionDetection()
        }
    }

    private fun cleanupUrlScreensaver() {
        urlController.deactivate()
        // Hide container if no other content is showing
        if (slideshow == null) {
            photoContainer.visibility = View.GONE
        }
    }

    private fun launchExternalApp() = externalAppController.launch()

    /** Returns true while an external app is in front of Dashie (screensaver "app" mode). */
    fun isExternalAppActive(): Boolean = externalAppController.isActive()

    /**
     * Called from MainActivity.onResume when Dashie returns to the
     * foreground after an external app was running. Clears the active
     * flag, hides the return button, then wakes the screen + restarts the
     * inactivity timer.
     */
    fun onReturnFromExternalApp() {
        if (externalAppController.isActive()) {
            Log.i(TAG, "Returning from external app - waking screen")
            externalAppController.markReturned()
            wakeScreen()
            if (isEnabled && timeoutSeconds > 0) {
                handler.postDelayed(dimRunnable, timeoutSeconds * 1000L)
            }
        }
    }

    /**
     * Trigger return from external app mode via motion detection or API call.
     * Brings Dashie to foreground via the floating button — state cleanup is
     * handled by [onReturnFromExternalApp] driven from onResume so the
     * URL-change check that would cause a page reload is skipped.
     */
    fun triggerReturnFromExternalApp() = externalAppController.triggerReturn()

    /** Set the clock TextView for displaying time in black/dim modes. */
    fun setClockView(textView: TextView?) = clockController.setClockView(textView)

    /** Set the date TextView for displaying date below clock. */
    fun setDateView(textView: TextView?) = clockController.setDateView(textView)

    /** Set the weather TextView for displaying inline current weather. */
    fun setWeatherView(textView: TextView?) = clockController.setWeatherView(textView)

    /**
     * Set the background View (black overlay).
     * This needs to be hidden for URL/photos modes so content shows through.
     */
    fun setBackgroundView(view: View?) {
        backgroundView = view
    }

    // ── Video Feed Mode ──────────────────────────────────────────────

    private var isVideoFeedMode = false
    private var videoFeedAreaWidth = 0

    /**
     * Resize screensaver content for split-view with video feeds.
     * When active, constrains photo slideshow and clock to the left 2/3
     * of the screen so the right 1/3 can show PiP camera feeds.
     */
    fun setVideoFeedMode(active: Boolean, feedAreaWidth: Int = 0) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val newFeedWidth = if (active) {
            if (feedAreaWidth > 0) feedAreaWidth else screenWidth / 3
        } else 0
        if (isVideoFeedMode == active && videoFeedAreaWidth == newFeedWidth) return
        isVideoFeedMode = active
        videoFeedAreaWidth = newFeedWidth

        // Photo container stays full-width — blur extends behind the sidebar.
        // Only foreground photos and metadata are constrained to the left portion.
        val containerLp = photoContainer.layoutParams as? FrameLayout.LayoutParams
        if (containerLp != null) {
            containerLp.width = FrameLayout.LayoutParams.MATCH_PARENT
            containerLp.gravity = android.view.Gravity.NO_GRAVITY
            photoContainer.layoutParams = containerLp
        }
        photoContainer.setPadding(0, 0, 0, 0)
        slideshowView?.setVideoFeedMode(active, videoFeedAreaWidth)

        // Reposition clock and weather to avoid overlap when sidebar changes
        if (clockController.getClockTextView()?.visibility == View.VISIBLE) {
            applyClockPosition()
        }
        if (weatherController.isOverlayActive()) {
            weatherController.applyPosition()
        }

        Log.i(TAG, "Video feed mode: $active (feed area=${videoFeedAreaWidth}px)")
    }

    /**
     * Narrow-portrait screensaver: shrink foreground photos to the top portion
     * so they fit above the bottom card band. Passes through to the slideshow
     * view. Pass 0 to restore full-height photos.
     */
    fun setBottomPanelInset(bottomPanelHeightPx: Int) {
        slideshowView?.setBottomPanelInset(bottomPanelHeightPx)
    }

    /**
     * Screensaver photo scaling follows the user's photoFit pref:
     * "fit" (default) letterboxes both dimensions with a blurred fill
     * behind — like the photo widget and explorer; "fill" CENTER_CROPs
     * to fill the frame (edges cropped). Until 2026-05-26 this was
     * hardcoded to always-fit after user feedback; it's now a setting
     * (Settings > Photos > Slideshow > Scaling) so users who prefer a
     * full-bleed screensaver can opt into fill. The `alone` parameter is
     * retained for API stability — scaling no longer depends on it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setScreensaverPhotoAlone(alone: Boolean) {
        slideshowView?.fitLandscapePhotos = prefs.photoFit != "fill"
    }

    /**
     * Set WebView reference for extracting HA tokens (needed for HA Media photo source).
     */
    fun setWebView(webView: WebView?, prefs: HalitePreferences?) {
        webViewRef = webView
        halitePrefs = prefs
    }

    // ── Clock + flip-timer forwarders ─────────────────────────────────
    // Internal callers (dimScreen, screenOff, wakeScreen, setVideoFeedMode,
    // setupPhotoSlideshow) keep calling these names; impls live in
    // clockController.

    private fun setupClockOverlay(forceShow: Boolean = false) =
        clockController.setupClockOverlay(forceShow)
    private fun applyClockPosition() = clockController.applyClockPosition()
    private fun stopClockOverlay() = clockController.stopClockOverlay()
    private fun getEffectiveClockFontSize() = clockController.getEffectiveClockFontSize()
    private fun applyWeatherCardForClockAnchor(anchor: String) =
        weatherController.applyCardForClockAnchor(anchor, clockController.computeClockZonePx())
    private fun startFlipTimer() = clockController.startFlipTimer()
    private fun stopFlipTimer() = clockController.stopFlipTimer()

    private fun cleanupWeatherOverlay() {
        weatherController.cleanup()
        clockController.stopFlipTimer()
    }

    /**
     * If the screensaver is currently active and the forecast overlay is
     * visible, tear it down and rebuild so the new forecastCardSize /
     * weatherMode settings take effect without waiting for the next dim cycle.
     */
    fun refreshWeatherOverlayIfActive() {
        weatherController.refreshIfActive(onSetupForFlipMode = { clockController.startFlipTimer() })
    }

    /** Public — called from HaliteScreenController on "Use HA for time" toggle. */
    fun refreshHaTimeProvider() = clockController.refreshHaTimeProvider()

    /**
     * Setup motion detection to wake from screensaver.
     * Note: If RTSP streaming is active, skip camera-based motion detection
     * because RTSP already has the camera and handles motion detection via
     * RtspCameraServer's built-in motion detection.
     */
    private fun setupMotionDetection() {
        if (motionDetector != null) return

        // Skip camera motion detection if RTSP is streaming
        // RTSP handles motion detection via its own frame analysis
        if (isRtspStreaming) {
            Log.i(TAG, "Skipping camera motion detection - RTSP is streaming (uses RTSP motion detection)")
            return
        }

        Log.i(TAG, "Setting up motion detection (sensitivity: ${prefs.motionSensitivity})")
        motionDetector = MotionDetector(
            context = context,
            sensitivityTenths = prefs.motionSensitivity,
            onMotionDetected = {
                Log.i(TAG, "Motion detected - waking screen")
                handler.post { resetTimer() }
            }
        )
        motionDetector?.start()
    }

    /**
     * Cleanup photo slideshow components
     */
    private fun cleanupPhotoSlideshow() {
        // Capture current photo data before stopping (for preview-on-wake feature)
        lastWakePhoto = slideshow?.getCurrentPhoto()
        lastWakePhotoList = slideshow?.getPhotos() ?: emptyList()

        slideshow?.stop()
        slideshow = null

        motionDetector?.stop()
        motionDetector = null

        // Stop watching local source if applicable. Reach into the factory
        // (it owns the live photoSource reference now) so LocalPhotoSource's
        // FileObserver isn't left running after the slideshow stops.
        (photoSourceFactory?.photoSource as? LocalPhotoSource)?.stopWatching()
        // Note: We deliberately do NOT clear haMediaPhotoSource or call
        // photoSourceFactory.cleanup() here — keeping them alive across dim
        // cycles preserves the prewarmed photo cache so the next screensaver
        // activation shows photos instantly.

        // Only hide container if not showing URL screensaver
        if (!urlController.isActive()) {
            photoContainer.visibility = View.GONE
        }
    }

    /**
     * Show a thumbnail of the last screensaver photo in the top-right corner.
     * Tapping the thumbnail opens a full-screen photo explorer.
     * Tapping the X or waiting 4 seconds dismisses it.
     */
    private fun showPhotoPreviewOnWake() {
        val photo = lastWakePhoto ?: return
        // Use the root layout (dimOverlay's parent), NOT photoContainer's parent,
        // because photoContainer is inside dimOverlay which gets faded to GONE on wake
        val rootContainer = dimOverlay.parent as? ViewGroup ?: return

        // Dismiss any existing preview/explorer
        photoPreviewOnWake?.dismiss()
        photoExplorerOverlay?.dismiss()

        val preview = PhotoPreviewOnWake(context)
        preview.onOpenExplorer = { openPhoto, allPhotos ->
            openPhotoExplorer(openPhoto, allPhotos, rootContainer)
        }
        photoPreviewOnWake = preview
        preview.show(photo, lastWakePhotoList, rootContainer)
    }

    /**
     * Open the full-screen photo explorer overlay.
     */
    private fun openPhotoExplorer(photo: PhotoItem, allPhotos: List<PhotoItem>, rootContainer: ViewGroup) {
        photoExplorerOverlay?.dismiss()

        val explorer = PhotoExplorerOverlay(context, prefs)
        photoExplorerOverlay = explorer
        explorer.show(photo, allPhotos, rootContainer)
    }

    /**
     * Wake the screen with a fade animation
     * Note: This does NOT wake from hardware screen off state - use screenOn() for that.
     */
    private fun wakeScreen() {
        if (!isDimmed) return

        // Don't wake if screen is in hardware off state - only screenOn() can do that
        if (isScreenOff) {
            Log.d(TAG, "wakeScreen() ignored - screen is in hardware off state, use screenOn()")
            return
        }

        Log.i(TAG, "Waking screen")
        isDimmed = false
        // Note: Do NOT clear isExternalAppActive here!
        // It must remain true until onReturnFromExternalApp() is called by MainActivity.onResume()
        // This ensures the URL change check is properly skipped when returning from external app.

        // Cancel any existing animation
        currentAnimator?.cancel()

        // Cleanup photo slideshow if active
        cleanupPhotoSlideshow()

        // Cleanup URL screensaver if active
        cleanupUrlScreensaver()

        // Navigate back from HA page if active
        if (isHaPageActive) {
            isHaPageActive = false
            onHaPageNavigate?.invoke(null)
            Log.i(TAG, "HA Page screensaver: navigating back to home dashboard")
        }

        // Stop clock overlay if active
        stopClockOverlay()

        // Stop weather overlay if active
        cleanupWeatherOverlay()

        // Restore background view visibility for next dim cycle
        backgroundView?.visibility = View.VISIBLE

        // Restore hardware brightness if it was reduced
        restoreBrightness()

        // Show photo preview thumbnail if waking from photos mode.
        // Suppressed in widgets layout — the photo widget on the dashboard
        // already gives the user access to the slideshow's photos, so the
        // corner thumbnail is redundant there. (The Settings toggle is
        // hidden in widgets mode for the same reason.)
        val isWidgetsMode = halitePrefs?.display?.layoutMode == "widgets"
        if (screensaverMode == "photos" && prefs.showPreviewOnWake && !isWidgetsMode && lastWakePhoto != null) {
            showPhotoPreviewOnWake()
        }

        currentAnimator = ValueAnimator.ofFloat(dimOverlay.alpha, 0f).apply {
            duration = ANIMATION_DURATION / 2  // Wake up faster
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                dimOverlay.alpha = animator.animatedValue as Float
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dimOverlay.visibility = View.GONE
                }
            })
            start()
        }

        onDimStateChanged?.invoke(false)
    }

    /**
     * Enable or disable the screen dimmer
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            handler.removeCallbacks(dimRunnable)
            if (isDimmed) {
                wakeScreen()
            }
        } else if (timeoutSeconds > 0) {
            resetTimer()
        }
        Log.i(TAG, "ScreenDimmer ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Update the timeout value
     */
    fun setTimeoutSeconds(seconds: Int) {
        timeoutSeconds = seconds
        handler.removeCallbacks(dimRunnable)
        if (seconds > 0 && isEnabled) {
            handler.postDelayed(dimRunnable, seconds * 1000L)
        }
    }

    /**
     * Update the screensaver mode
     * @param mode "dim" for darkened screen, "black" for completely black screen,
     *             "url" for webpage display, "photos" for photo slideshow
     */
    fun setMode(mode: String) {
        screensaverMode = mode
        Log.i(TAG, "Screensaver mode set to: $mode")
    }

    /**
     * Check if screen is currently dimmed
     */
    fun isDimmed(): Boolean = isDimmed

    /**
     * Check if screen is in "off" state (completely black, no timer wake)
     */
    fun isScreenOff(): Boolean = isScreenOff

    /**
     * True when the active screensaver shows nothing to dock the shared panel
     * onto — the screen is off/asleep, or the live mode is a fully-black overlay
     * ("black"/"off"). In these states the music/video/timer cards must NOT
     * migrate to the screensaver panel: there's nothing visible to overlay them
     * on, so a docked card just produces a stray strip over a black screen.
     */
    fun isBlankScreensaver(): Boolean =
        isScreenOff || screensaverMode == "black" || screensaverMode == "off"

    /**
     * True if the device display is actually off, regardless of how it got that way:
     * either our own hardware screen-off (isScreenOff, via lockNow) OR the system turning
     * the display off without us — most notably the user pressing the physical power button,
     * for which we never set isScreenOff. Face/motion wake uses this (instead of isScreenOff)
     * so a detection turns the display back on no matter how it was switched off.
     */
    fun isDisplayOff(): Boolean {
        if (isScreenOff) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return pm?.isInteractive == false
    }

    /**
     * Check if screen off was triggered by the sleep schedule (screenOff() API),
     * as opposed to the screensaver "off" mode (dimScreen with mode=off).
     * Used to decide whether motionWakeForSleep preference applies.
     */
    fun isSleepScreenOff(): Boolean = isScreenOff && modeBeforeScreenOff != null

    /**
     * Turn the screen off - uses hardware screen off if user has enabled the toggle AND
     * Device Admin is granted. Otherwise uses a black overlay (no clock, true screen off look).
     * This is different from screensaver - it's a true "display off" state.
     * Only screenOn() can wake from this state.
     *
     * The behavior depends on the hardwareScreenOff preference in HalitePreferences:
     * - If enabled AND Device Admin granted: Actually turns off display hardware via lockNow()
     * - If enabled but Device Admin not granted: Black overlay (user opted in but no permission yet)
     * - If disabled: Black overlay (user prefers overlay mode)
     *
     * Note: Clock overlay is NEVER shown for screenOff - this is meant to be a true "off" state,
     * not a screensaver. Use startScreensaver for screensaver with clock.
     */
    fun screenOff(methodOverride: String? = null) {
        val useHardwareScreenOff = when (methodOverride) {
            "hardware" -> true
            "overlay" -> false
            else -> halitePrefs?.sleep?.hardwareScreenOff ?: false  // null = use default preference
        }
        Log.i(TAG, "Screen OFF requested (methodOverride=$methodOverride, useHardwareScreenOff=$useHardwareScreenOff)")
        isScreenOff = true
        handler.removeCallbacks(dimRunnable)

        // Sleep ends the settings-PIN grace window like screensaver start does
        // (both mean the person who entered the PIN walked away)
        LockGate.clearGrace()

        // Save current mode to restore later
        modeBeforeScreenOff = screensaverMode

        // Weather overlay belongs to the "weather" screensaver mode — tear it down
        // for sleep like photos/URL below. Without this it stayed visible above the
        // black overlay (it's added as dimOverlay's LAST child, so it z-orders on
        // top) and kept its 15-min forecast poll running all night. The next dim
        // cycle re-creates it from the restored mode.
        cleanupWeatherOverlay()

        // Check if user has opted into hardware screen off AND has Device Admin permission
        if (useHardwareScreenOff && isDeviceAdminEnabled()) {
            Log.i(TAG, "Hardware screen off enabled - turning off display hardware")
            // Show black overlay first, then turn off hardware
            screensaverMode = "black"
            if (!isDimmed) {
                // Force immediate black overlay before lockNow (NO clock for screenOff)
                backgroundView?.visibility = View.VISIBLE
                photoContainer.visibility = View.GONE
                stopClockOverlay()  // Ensure no clock
                dimOverlay.alpha = 1f
                dimOverlay.visibility = View.VISIBLE
                isDimmed = true
            } else {
                cleanupPhotoSlideshow()
                cleanupUrlScreensaver()
                stopClockOverlay()  // Ensure no clock
                backgroundView?.visibility = View.VISIBLE
                photoContainer.visibility = View.GONE
            }
            // Turn off hardware after brief delay
            handler.postDelayed({
                if (isScreenOff) {
                    turnOffScreenHardware()
                }
            }, 100)
        } else {
            // User has not enabled hardware screen off OR doesn't have permission
            // Use black overlay - this is still a "screen off" state, just without hardware off
            Log.i(TAG, "Using black overlay for screen off (hardwareScreenOff=$useHardwareScreenOff, deviceAdmin=${isDeviceAdminEnabled()})")
            screensaverMode = "black"

            // Check if user wants clock shown during sleep mode
            val showClockDuringSleep = halitePrefs?.sleep?.sleepShowClock ?: false
            Log.i(TAG, "Sleep show clock: $showClockDuringSleep")

            if (!isDimmed) {
                // Show black overlay immediately
                backgroundView?.visibility = View.VISIBLE
                photoContainer.visibility = View.GONE
                // Show or hide clock based on user preference
                if (showClockDuringSleep) {
                    setupClockOverlay(forceShow = true)
                } else {
                    stopClockOverlay()
                }
                dimOverlay.alpha = 1f
                dimOverlay.visibility = View.VISIBLE
                isDimmed = true
            } else {
                cleanupPhotoSlideshow()
                cleanupUrlScreensaver()
                // Show or hide clock based on user preference
                if (showClockDuringSleep) {
                    setupClockOverlay(forceShow = true)
                } else {
                    stopClockOverlay()
                }
                backgroundView?.visibility = View.VISIBLE
                photoContainer.visibility = View.GONE
            }
        }

        // Reduce hardware brightness for black overlay sleep if enabled
        val reduceBrightness = halitePrefs?.sleep?.reduceBrightnessOnSleep ?: true
        if (!useHardwareScreenOff && reduceBrightness) {
            reduceBrightness()
        }

        onDimStateChanged?.invoke(true)
    }

    /**
     * Turn the screen on - wakes from screen off state.
     * If the screen was turned off with lockNow(), this will wake the device hardware.
     * Restores normal screensaver timer behavior and original screensaver mode.
     */
    fun screenOn() {
        Log.i(TAG, "Screen ON requested")

        // Clear the flag that tells DashieApiService to respond to SCREEN_ON broadcasts
        HalitePreferences(context).sleep.wasInScreenOffMode = false

        // Wake the device if the display is actually off — our own hardware screen-off
        // (isScreenOff) OR a system screen-off we didn't initiate (e.g. the power button).
        // Checked before clearing isScreenOff so the lockNow case still wakes.
        if (isDisplayOff()) {
            wakeDeviceHardware()
        }

        isScreenOff = false

        // Restore original screensaver mode
        modeBeforeScreenOff?.let {
            screensaverMode = it
            modeBeforeScreenOff = null
        }

        wakeScreen()
        // Restart the timer for normal screensaver behavior
        if (isEnabled && timeoutSeconds > 0) {
            handler.postDelayed(dimRunnable, timeoutSeconds * 1000L)
        }
    }

    /**
     * Wake the device hardware from sleep/lock state.
     *
     * Uses a multi-pronged approach for maximum reliability:
     * 1. Primary: Activity-based wake via callback (setTurnScreenOn, window flags)
     * 2. Fallback: PowerManager wake lock (SCREEN_BRIGHT_WAKE_LOCK)
     *
     * The Activity-based approach is more reliable on modern Android devices,
     * especially Samsung with aggressive power management. The deprecated
     * FULL_WAKE_LOCK often gets ignored by these devices.
     */
    @Suppress("DEPRECATION")
    private fun wakeDeviceHardware() {
        hardwareController.wakeDeviceHardware(onHardwareWakeRequested)
    }

    /**
     * Immediately dim the screen (starts screensaver)
     * Note: This starts the screensaver, not screen off. Use screenOff() for true off.
     */
    fun dimNow() {
        handler.removeCallbacks(dimRunnable)
        if (!isDimmed) {
            dimScreen()
        }
    }

    /**
     * Set RTSP streaming state.
     * When RTSP is streaming, the camera-based motion detection is skipped
     * because RTSP already has the camera and handles motion detection.
     *
     * @param streaming true if RTSP is actively streaming
     */
    fun setRtspStreaming(streaming: Boolean) {
        if (isRtspStreaming == streaming) return

        isRtspStreaming = streaming
        Log.i(TAG, "RTSP streaming state set to: $streaming")

        // If RTSP just started and we have an active motion detector, stop it
        // to release the camera for RTSP
        if (streaming && motionDetector != null) {
            Log.i(TAG, "Stopping motion detector - camera needed for RTSP streaming")
            motionDetector?.stop()
            motionDetector = null
        }
    }

    /**
     * Check if RTSP streaming is active
     */
    fun isRtspStreaming(): Boolean = isRtspStreaming

    /**
     * Called when the activity goes to background.
     * Cancels the pending screensaver timer to prevent it from triggering
     * while the user is in another app.
     */
    fun onActivityPaused() {
        isInForeground = false
        handler.removeCallbacks(dimRunnable)
        Log.d(TAG, "Activity paused - screensaver timer cancelled")
    }

    /**
     * Called when the activity returns to foreground.
     * Restarts the screensaver timer if enabled and not already dimmed.
     */
    fun onActivityResumed() {
        isInForeground = true
        if (isEnabled && timeoutSeconds > 0 && !isDimmed && !isScreenOff) {
            handler.removeCallbacks(dimRunnable)
            handler.postDelayed(dimRunnable, timeoutSeconds * 1000L)
            Log.d(TAG, "Activity resumed - screensaver timer restarted (${timeoutSeconds}s)")
        }
    }

    /**
     * Reduce window brightness to minimum (0) for power savings during black overlay.
     * Saves both window and system brightness for restore on wake.
     * Pauses auto-brightness to prevent the light sensor from overriding during sleep.
     */
    private fun reduceBrightness() = hardwareController.reduceBrightness()

    private fun restoreBrightness() = hardwareController.restoreBrightness()

    /**
     * Clean up resources
     */
    fun destroy() {
        // Mark as destroyed FIRST to stop any pending async callbacks
        isDestroyed = true

        // Cancel all pending coroutines
        scope.cancel()

        handler.removeCallbacks(dimRunnable)
        currentAnimator?.cancel()

        // Cleanup photo slideshow. Detach the view from the shared photoContainer —
        // the container outlives this dimmer (it's an activity-layout view passed
        // into every ScreenDimmer), so nulling the field alone leaked one full
        // slideshow view tree per dimmer recreation (HA login, permission flows).
        cleanupPhotoSlideshow()
        slideshowView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        slideshowView = null

        urlController.destroy()
        externalAppController.destroy()

        // Stop clock overlay
        stopClockOverlay()

        // Cleanup weather overlay
        cleanupWeatherOverlay()

        // Restore any reduced sleep brightness. destroy() runs on ScreenDimmer
        // recreation (HA login completion, camera-permission flows); without this,
        // a dimmer destroyed mid-sleep left window brightness stuck at 0 with no
        // restore path — and the NEXT dimmer's reduceBrightness() snapshotted the
        // broken 0 and faithfully "restored" it. No-op when nothing is saved.
        restoreBrightness()

        Log.i(TAG, "ScreenDimmer destroyed")
    }
}
