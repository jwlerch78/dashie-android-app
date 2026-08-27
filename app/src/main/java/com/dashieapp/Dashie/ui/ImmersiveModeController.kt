package com.dashieapp.Dashie.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Controller for managing immersive fullscreen mode.
 * Handles enabling/disabling system UI visibility (status bar, navigation bar).
 *
 * Used for:
 * - Halite (Dashie Lite) kiosk mode
 * - Tablet devices running standard Dashie
 *
 * Note on Quick Settings panel:
 * Android allows users to swipe down from the top to access the notification shade
 * and Quick Settings panel. We use BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE so bars appear
 * as translucent overlays without stealing touch events from bottom-edge UI elements.
 * In kiosk mode, a WindowInsets listener detects when bars appear and re-hides them
 * after a short delay for a more locked-down experience.
 *
 * ⚠️ FIRE TABLET BUG: Changing window flags or attributes resets immersive mode
 * ==================================================================================
 * On Fire tablets (and possibly other devices), modifying window.addFlags/clearFlags
 * or window.attributes resets the WindowInsets behavior, causing the nav bar to
 * appear on every touch.
 *
 * AFFECTED OPERATIONS:
 * - window.addFlags(FLAG_KEEP_SCREEN_ON)
 * - window.clearFlags(FLAG_KEEP_SCREEN_ON)
 * - window.attributes = params (e.g., for screenBrightness)
 * - Any other window flag or attribute changes
 *
 * REQUIRED FIX: Always re-apply immersive mode after these operations:
 *   window.decorView.post {
 *       immersiveModeController.enable(kioskMode = true, fastHide = useFastHide)
 *   }
 *
 * See: MainActivity.applyKeepScreenOnSetting(), BrightnessManager.onWindowAttributesChanged
 */
class ImmersiveModeController(private val window: Window) {

    companion object {
        private const val TAG = "ImmersiveModeCtrl"
        private const val HIDE_DELAY_FAST_MS = 100L   // Fast re-hide (kiosk mode ON) - very aggressive
        private const val HIDE_DELAY_SLOW_MS = 3000L  // Slow re-hide (kiosk mode OFF)
        private const val RECHECK_DELAY_MS = 300L     // Extra re-check for Quick Settings dismissal

        // Fire tablets need special handling - their immersive mode doesn't auto-hide bars reliably
        private val IS_AMAZON_DEVICE = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isListenerSetup = false
    private var currentHideDelay = HIDE_DELAY_FAST_MS
    private var isKioskModeEnabled = false
    private var isImmersiveModeActive = false  // Track if immersive mode should be active
    private var enabledAtMs = 0L  // Timestamp when immersive mode was enabled
    private var lastNavBarBlockedMs = 0L  // Timestamp of last nav bar blocked notification

    // Grace period after enabling - don't show toast during startup/reload
    private val STARTUP_GRACE_PERIOD_MS = 2000L
    // Debounce period - don't spam the toast
    private val TOAST_DEBOUNCE_MS = 3000L

    // Callback for when nav bar is blocked (for showing toast)
    var onNavBarBlocked: (() -> Unit)? = null

    /**
     * Enable immersive fullscreen mode to hide system UI.
     * @param kioskMode If true, uses stricter behavior (harder to access system bars)
     * @param fastHide If true, re-hides bars after 250ms; if false, after 3 seconds
     */
    @Suppress("DEPRECATION")
    fun enable(kioskMode: Boolean = false, fastHide: Boolean = true) {
        currentHideDelay = if (fastHide) HIDE_DELAY_FAST_MS else HIDE_DELAY_SLOW_MS
        isKioskModeEnabled = kioskMode
        isImmersiveModeActive = true  // Mark as active
        enabledAtMs = System.currentTimeMillis()  // Track when enabled for grace period
        Log.i(TAG, "📱 enable() called: kioskMode=$kioskMode, fastHide=$fastHide, delay=${currentHideDelay}ms")

        // Handle display cutout (notch/camera hole) - extend content into cutout area
        // This eliminates the "unused space at top" on devices with notches like Redmi Pad
        // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES allows content to extend into cutout on portrait/landscape edges
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            Log.d(TAG, "📱 Set display cutout mode to SHORT_EDGES")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                // Hide both status bar and navigation bar
                controller.hide(
                    WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars()
                )

                // BEHAVIOR_DEFAULT: system bars appear on swipe and trigger onApplyWindowInsets,
                // allowing our fast re-hide listener to work. The downside is it reserves a
                // gesture zone at screen edges — test sidebar/menu interaction.
                // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: system controls hide timing (~1s),
                // our re-hide listener never fires, but no gesture zone conflict.
                // Use DEFAULT in kiosk+fastHide mode for aggressive re-hide; TRANSIENT otherwise.
                controller.systemBarsBehavior = if (kioskMode && fastHide) {
                    WindowInsetsController.BEHAVIOR_DEFAULT
                } else {
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                if (kioskMode) {
                    // In kiosk mode, also set up the insets listener to quickly re-hide
                    // any bars that appear via swipe gesture
                    setupInsetsListener()
                    Log.i(TAG, "📱 Using kiosk mode with transient bars + fast re-hide (amazon=$IS_AMAZON_DEVICE)")
                } else {
                    Log.i(TAG, "📱 Using standard immersive mode (non-kiosk)")
                }
            }
        } else {
            // Android 10 and below
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )

            // For older versions on Fire tablets, add visibility change listener to re-hide bars
            // Standard Android devices with IMMERSIVE_STICKY should auto-hide reliably
            if (kioskMode && IS_AMAZON_DEVICE) {
                setupVisibilityChangeListener()
            }
        }
        Log.i(TAG, "📱 Immersive fullscreen mode ENABLED (kiosk=$kioskMode, amazon=$IS_AMAZON_DEVICE)")
    }

    /**
     * Setup WindowInsets listener for Android 11+ to detect when bars appear
     */
    private fun setupInsetsListener() {
        if (isListenerSetup) return
        isListenerSetup = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                val statusBarsVisible = insets.isVisible(WindowInsets.Type.statusBars())
                val navBarsVisible = insets.isVisible(WindowInsets.Type.navigationBars())
                val imeVisible = insets.isVisible(WindowInsets.Type.ime())

                // Don't re-hide system bars while keyboard (IME) is visible
                // This prevents keyboard freezing issues
                if (imeVisible) {
                    Log.d(TAG, "📱 Keyboard visible - skipping re-hide")
                    handler.removeCallbacksAndMessages(null)
                    return@setOnApplyWindowInsetsListener view.onApplyWindowInsets(insets)
                }

                if ((statusBarsVisible || navBarsVisible) && isImmersiveModeActive) {
                    Log.d(TAG, "📱 System bars appeared - scheduling re-hide in ${currentHideDelay}ms")
                    // Notify that nav bar was blocked (for toast)
                    // Only show toast if:
                    // 1. Nav bar is visible (not just status bar)
                    // 2. Kiosk mode is enabled AND fastHide is enabled (Lock Nav Bar setting)
                    // 3. We're past the startup grace period (to avoid toast on app launch/reload)
                    // 4. We haven't shown the toast recently (debounce)
                    if (navBarsVisible && isKioskModeEnabled && currentHideDelay == HIDE_DELAY_FAST_MS) {
                        val now = System.currentTimeMillis()
                        val timeSinceEnabled = now - enabledAtMs
                        val timeSinceLastToast = now - lastNavBarBlockedMs
                        if (timeSinceEnabled > STARTUP_GRACE_PERIOD_MS && timeSinceLastToast > TOAST_DEBOUNCE_MS) {
                            lastNavBarBlockedMs = now
                            onNavBarBlocked?.invoke()
                        }
                    }
                    // Schedule re-hide after the configured delay
                    scheduleReHide(view)
                }

                view.onApplyWindowInsets(insets)
            }
        }
    }

    /**
     * Schedule re-hiding of system bars with an additional recheck for Quick Settings panel
     */
    private fun scheduleReHide(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                // Check if immersive mode is still active
                if (!isImmersiveModeActive) return@postDelayed

                // Double-check keyboard isn't visible before hiding
                val currentInsets = view.rootWindowInsets
                if (currentInsets == null || !currentInsets.isVisible(WindowInsets.Type.ime())) {
                    hideSystemBars()

                    // Schedule a second re-hide check in case Quick Settings panel was dismissed
                    // but the bars didn't auto-hide (common issue on some devices)
                    handler.postDelayed({
                        // Check if immersive mode is still active
                        if (!isImmersiveModeActive) return@postDelayed

                        val recheckInsets = view.rootWindowInsets
                        if (recheckInsets == null || !recheckInsets.isVisible(WindowInsets.Type.ime())) {
                            val stillVisible = recheckInsets?.isVisible(WindowInsets.Type.statusBars()) == true ||
                                    recheckInsets?.isVisible(WindowInsets.Type.navigationBars()) == true
                            if (stillVisible) {
                                Log.d(TAG, "📱 System bars still visible on recheck - forcing re-hide")
                                hideSystemBars()
                            }
                        }
                    }, RECHECK_DELAY_MS)
                } else {
                    Log.d(TAG, "📱 Skipped re-hide - keyboard still visible")
                }
            }, currentHideDelay)
        }
    }

    /**
     * Force hide system bars immediately
     */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                WindowInsets.Type.statusBars() or
                WindowInsets.Type.navigationBars()
            )
            Log.d(TAG, "📱 System bars re-hidden")
        }
    }

    /**
     * Call this when window gains focus to force re-hide system bars
     * Useful for dismissing Quick Settings panel that may linger
     */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus && isKioskModeEnabled && isImmersiveModeActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Small delay to let the focus transition complete
                handler.postDelayed({
                    // Double-check immersive mode is still active before hiding
                    if (!isImmersiveModeActive) return@postDelayed
                    val currentInsets = window.decorView.rootWindowInsets
                    val imeVisible = currentInsets?.isVisible(WindowInsets.Type.ime()) == true
                    if (!imeVisible) {
                        hideSystemBars()
                    }
                }, 100L)
            }
        }
    }

    /**
     * Setup visibility change listener for older Android versions
     */
    @Suppress("DEPRECATION")
    private fun setupVisibilityChangeListener() {
        if (isListenerSetup) return
        isListenerSetup = true

        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            // If system bars become visible, re-hide them after the configured delay
            if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                Log.d(TAG, "📱 System bars appeared (legacy) - scheduling re-hide in ${currentHideDelay}ms")
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
                    Log.d(TAG, "📱 System bars re-hidden (legacy)")
                }, currentHideDelay)
            }
        }
    }

    /**
     * Disable immersive fullscreen mode to show system UI.
     */
    @Suppress("DEPRECATION")
    fun disable() {
        // Mark as inactive first to prevent re-hide from triggering
        isImmersiveModeActive = false
        isKioskModeEnabled = false

        // Cancel any pending re-hide callbacks
        handler.removeCallbacksAndMessages(null)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or
                WindowInsets.Type.navigationBars()
            )
        } else {
            // Android 10 and below
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        Log.i(TAG, "📱 Immersive fullscreen mode DISABLED")
    }

    /**
     * Re-apply immersive mode after window flag/attribute changes.
     * Use this after any operation that modifies window.addFlags/clearFlags or window.attributes.
     *
     * This is a workaround for the Fire tablet bug where changing window flags resets
     * the WindowInsets behavior.
     *
     * Usage:
     *   window.addFlags(FLAG_KEEP_SCREEN_ON)
     *   immersiveModeController.reapplyAfterWindowChange()
     *
     * Or use the callback pattern:
     *   brightnessManager.onWindowAttributesChanged = { immersiveModeController.reapplyAfterWindowChange() }
     */
    fun reapplyAfterWindowChange() {
        if (!isImmersiveModeActive) return

        window.decorView.post {
            enable(kioskMode = isKioskModeEnabled, fastHide = currentHideDelay == HIDE_DELAY_FAST_MS)
            Log.d(TAG, "📱 Immersive mode re-applied after window change")
        }
    }

    /**
     * Check if immersive mode is currently enabled.
     * @param isKioskFlavor True if this is the Halite/kiosk flavor
     * @param isTablet True if device is a tablet
     */
    @Suppress("DEPRECATION")
    fun isEnabled(isKioskFlavor: Boolean, isTablet: Boolean): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // On Android 11+, we can't directly query the state
            // Return true if we're on a tablet or halite (where we enable it)
            isKioskFlavor || isTablet
        } else {
            // On older versions, check the flags
            val flags = window.decorView.systemUiVisibility
            (flags and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0
        }
    }
}
