package com.dashieapp.Dashie.halite.settings

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.fragments.BaseSettingsFragment
import com.dashieapp.Dashie.halite.settings.pages.checkMaTokenRole
import com.dashieapp.Dashie.halite.settings.pages.createAccountFragment
import com.dashieapp.Dashie.halite.settings.pages.calendar.createCalendarFragment
import com.dashieapp.Dashie.halite.settings.pages.createBatteryChargingFragment
import com.dashieapp.Dashie.halite.settings.pages.createFamilyFragment
import com.dashieapp.Dashie.halite.settings.pages.createHomeAssistantFragment
import com.dashieapp.Dashie.halite.settings.pages.createMusicFragment
import com.dashieapp.Dashie.halite.settings.pages.createVideoFeedsFragment
import com.dashieapp.Dashie.halite.settings.pages.createPhotosFragment
import com.dashieapp.Dashie.halite.settings.pages.createScreensaverFragment
import com.dashieapp.Dashie.halite.settings.pages.stopCameraStatusPoll
import com.dashieapp.Dashie.halite.settings.pages.createVoiceAiFragment
import com.dashieapp.Dashie.halite.settings.pages.createVoiceAssistantFragment
import com.dashieapp.Dashie.halite.settings.pages.createWakeModeFragment
import com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring

/**
 * Native settings activity with iOS-style modal appearance.
 * Replaces the WebView overlay settings modal for better compatibility
 * with older WebView variants that don't render transparency correctly.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /** Result code: re-open Control Center after settings closes */
        const val RESULT_REOPEN_CC = RESULT_FIRST_USER
        /** Result code: open the auto-brightness settings dialog */
        const val RESULT_OPEN_BRIGHTNESS_SETTINGS = RESULT_FIRST_USER + 1
        /** Result code: close settings without re-opening Control Center (e.g. sign-in navigation) */
        const val RESULT_CLOSE_NO_CC = RESULT_FIRST_USER + 2

        /** Weak reference to MainActivity's WebView for HA queries (speaker picker, etc.) */
        var webViewRef: java.lang.ref.WeakReference<android.webkit.WebView>? = null

        /** Reference to the JS bridge for settings data delegate access (family, calendar) */
        var jsBridgeRef: com.dashieapp.Dashie.webview.DashieJSBridge? = null

        /** Provider for RTSP server running state (set by MainActivity before launch) */
        var isRtspRunning: (() -> Boolean)? = null

        /** Provider for RTSP server failed state (set by MainActivity before launch) */
        var hasRtspFailed: (() -> Boolean)? = null

        /** Provider for RTSP failure reason (set by MainActivity before launch) */
        var getRtspFailureReason: (() -> String?)? = null

        /** Provider for RTSP stream URL (set by MainActivity before launch) */
        var getRtspStreamUrl: (() -> String)? = null

        /** Provider for RTSP client count (set by MainActivity before launch) */
        var getRtspClientCount: (() -> Int)? = null

        /** Provider for camera preview frame (JPEG bytes from RTSP server) */
        var getCameraPreviewFrame: (() -> ByteArray?)? = null

        /** Callback to notify the power watchdog of a manual switch toggle.
         *  overrideTarget: null = normal toggle, 100 = charge to full, 5 = discharge to 5%. */
        var onPowerManualToggle: ((turnedOn: Boolean, overrideTarget: Int?) -> Unit)? = null

        // ── Power management state (mutable, updated by toggle callback + battery poll) ──
        @Volatile var powerSwitchState: Boolean = false
        @Volatile var powerSwitchReachable: Boolean? = null // null = not yet checked
        @Volatile var currentBatteryLevel: Int? = null
        @Volatile var currentBatteryCharging: Boolean? = null
    }

    /** Schema context (value provider + callbacks) — initialized once, shared across fragments */
    lateinit var schemaContext: SettingsSchemaWiring.SchemaContext
        private set

    /** Shared ViewHolder pool across all settings fragments — survives fragment swaps */
    val sharedViewPool = androidx.recyclerview.widget.RecyclerView.RecycledViewPool()

    /** Whether this was launched as a deep-link from CC (no root fragment) */
    private var isDeepLinked = false

    /** Throttle touch broadcasts to avoid flooding the main thread */
    private var lastTouchBroadcastTime = 0L
    private val TOUCH_BROADCAST_THROTTLE_MS = 500L

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsTrace.shown("Activity/SettingsActivity intent.navigate_to=${intent?.getStringExtra("navigate_to")}")
        val onCreateToken = SettingsProfiler.start("Activity.onCreate (total)")

        // Apply dark mode preference before super.onCreate so DayNight theme is correct
        // Uses AppCompat variant since SettingsActivity extends AppCompatActivity
        SettingsProfiler.measure("Activity.applyDarkMode") {
            com.dashieapp.Dashie.devicecontrols.DarkModeManager.applyStoredPreferenceAppCompat(this)
        }
        SettingsProfiler.measure("Activity.super.onCreate") {
            super.onCreate(savedInstanceState)
        }

        // Allow settings to show over the *Android lockscreen* (same as
        // MainActivity) — unrelated to the kiosk lockSettings PIN gate below.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        // Inflate layout and start slide animation FIRST for immediate visual feedback
        SettingsProfiler.measure("Activity.setContentView") {
            setContentView(R.layout.activity_settings)
        }

        enableImmersiveMode()

        // Handle backdrop click to close
        val rootView = findViewById<View>(R.id.settingsRoot)
        rootView.setOnClickListener { finishWithReopenCC() }
        rootView.isFocusable = false

        // Prevent clicks on container from closing
        val containerView = findViewById<View>(R.id.settingsContainer)
        containerView.setOnClickListener { }
        containerView.isFocusable = false

        // Kiosk lock (lockSettings): SettingsActivity is Intent-reachable from
        // many callers (credit prompt "Use local voice", calendar reauth pill,
        // music sign-in, JS bridge, CC cards) — guard on entry so every path,
        // including ones added later, is covered. Within the LockGate grace
        // window (e.g. the Control Center just prompted) this passes
        // synchronously. While the PIN prompt is up, the content stays hidden
        // behind the dimmed backdrop; cancel/failure closes the activity.
        val lockGatePassed = com.dashieapp.Dashie.halite.LockGate.isAllowedNow(this)
        if (!lockGatePassed) {
            containerView.visibility = View.INVISIBLE
            com.dashieapp.Dashie.halite.LockGate.requirePin(
                this,
                onDenied = { finish() }
            ) {
                containerView.visibility = View.VISIBLE
                if (savedInstanceState == null) {
                    animateContainerIn()
                }
            }
        }

        // Match Control Center sizing
        applyResponsiveLayout()

        // Start slide animation immediately — heavy init happens during the animation
        if (savedInstanceState == null && lockGatePassed) {
            animateContainerIn()
        }

        // Initialize schema wiring (maps setting keys → SharedPreferences)
        // Runs while slide animation is playing so the delay is masked
        schemaContext = SettingsProfiler.measure("SchemaWiring.create (all prefs + registration)") {
            SettingsSchemaWiring.create(this)
        }

        // Load initial fragment (schemaContext is now ready).
        // SettingsActivity is only ever launched deep-linked from Control Center with a
        // navigate_to extra. Finish defensively if that contract is ever broken.
        if (savedInstanceState == null) {
            val navigateTo = intent?.getStringExtra("navigate_to")
            if (navigateTo == null) {
                finish()
                return
            }
            isDeepLinked = true
            navigateToPage(navigateTo)
        }

        SettingsProfiler.end(onCreateToken)

        // Measure time from onCreate to first frame drawn
        val firstFrameToken = SettingsProfiler.start("Activity.onCreate → firstFrame")
        window.decorView.post {
            SettingsProfiler.end(firstFrameToken)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        val navigateTo = intent?.getStringExtra("navigate_to") ?: return

        // Clean up any running polls from the previous page
        stopCameraStatusPoll()

        // Clear back stack so back from the new page closes settings
        supportFragmentManager.popBackStackImmediate(
            null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
        )

        isDeepLinked = true
        navigateToPage(navigateTo)
    }

    /**
     * Some feature pages stay reachable even when the synced feature-visibility
     * cache marks them hidden. Voice & AI is a *core* feature in HA-only kiosk
     * mode (HA voice assistant, timers, HA control) — the `ai_voice` tier gate
     * only makes sense for cloud accounts. So don't block it when HA is
     * configured or the device has alpha access. Mirrors the requiresHaOrAlpha
     * rule in the JS settings menu, and is robust against a stale/never-synced
     * feature cache (e.g. a kiosk device that last synced as a non-alpha tier).
     */
    private fun isFeatureGateBypassed(
        navigateTo: String,
        featurePrefs: com.dashieapp.Dashie.halite.preferences.FeatureVisibilityPreferences
    ): Boolean {
        if (navigateTo != "voice_ai") return false
        val conn = com.dashieapp.Dashie.halite.HalitePreferences(this).connection
        val haConfigured = conn.haEnabled && conn.buildFullUrl().let {
            it.isNotEmpty() &&
                it != com.dashieapp.Dashie.halite.preferences.ConnectionPreferences.DEFAULT_HA_URL
        }
        // Cloud Voice/AI moved alpha→beta (2026-07-03 access-tier restructure) → beta+ bypasses.
        val isBeta = featurePrefs.hasBetaAccess()
        return haConfigured || isBeta
    }

    /**
     * Navigate to a deep-linked settings page by tag name.
     * Used by both onCreate() and onNewIntent().
     */
    private fun navigateToPage(navigateTo: String) {
        SettingsTrace.shown("SettingsActivity/navigate_to=$navigateTo")

        // Reject deep-links to features the JS featureAccessService has
        // told us are hidden for this user (rollout-status / access-level
        // gates — e.g. Locations / Ask Dashie for default beta users).
        // Defense for direct intent routing; the Control Center already
        // omits these cards. See §1.6 of cloud beta plan.
        // Map page tag → JS feature_id. Tags not in this map are not
        // gated by feature visibility (e.g. account, preferences, advanced).
        val featureIdForPage: String? = when (navigateTo) {
            "calendar" -> "calendar"
            "photos" -> "photos"
            "chores_rewards" -> "chores"
            "locations" -> "gps"
            "voice_ai" -> "ai_voice"
            "video_feeds" -> "video_feeds"
            else -> null
        }
        if (featureIdForPage != null) {
            val featurePrefs = com.dashieapp.Dashie.halite.preferences.FeatureVisibilityPreferences(this)
            if (featurePrefs.isHidden(featureIdForPage) && !isFeatureGateBypassed(navigateTo, featurePrefs)) {
                android.widget.Toast.makeText(
                    this,
                    "Feature not available",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                return
            }
        }

        val fragment = when (navigateTo) {
            "voice_ai" -> createVoiceAiFragment()
            "voice_assistant" -> createVoiceAssistantFragment()
            "preferences" -> com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
                schemaProvider = com.dashieapp.Dashie.halite.settings.schemas.PreferencesPageSchema::create,
                valueProvider = schemaContext.valueProvider,
                callbackRegistry = schemaContext.callbackRegistry
            )
            "home_assistant" -> createHomeAssistantFragment()
            // Deep-link straight to the URL config fragment (skipping the
            // parent HA settings page). Reserved for callers that want
            // to drop the user directly into URL setup.
            "home_assistant_url" -> com.dashieapp.Dashie.halite.settings.fragments.SettingsHaUrlFragment()
            "music" -> createMusicFragment()
            "photos" -> createPhotosFragment()
            "camera" -> createCameraFragment()
            "battery_charging" -> createBatteryChargingFragment()
            "video_feeds" -> createVideoFeedsFragment()
            "screensaver" -> createScreensaverFragment()
            "sleep" -> createSleepFragment()
            "advanced" -> createAdvancedFragment()
            "account" -> createAccountFragment()
            "display" -> createDisplayFragment()
            "locations" -> createLocationsFragment()
            "chores_rewards" -> createChoresRewardsFragment()
            "family" -> createFamilyFragment()
            "calendar" -> createCalendarFragment()
            else -> return
        }
        showFragment(fragment, navigateTo, addToBackStack = false)
    }


    /** Cached MA user display names (ma_user_id → display_name) for music profile UI */
    internal var maUserNames: Map<String, String> = emptyMap()
    /** Current MA token user info (checked on music page load) */
    internal var maCurrentUserName: String? = null
    internal var maIsAdmin: Boolean? = null  // null = still checking

    internal var cameraStatusPollHandler: android.os.Handler? = null
    internal var cameraStatusPollRunnable: Runnable? = null

    // ── Family (JS bridge-backed) ───────────────────────────────────

    /** In-memory cache of family members loaded from Supabase via JS bridge */
    internal var familyMembers: List<com.dashieapp.Dashie.halite.settings.data.FamilyMember> = emptyList()
    internal var familyLoading: Boolean = true

    /** Currently editing member ID (null = adding new member) */
    internal var editingMemberId: String? = null

    // ── Calendar (JS bridge-backed) ────────────────────────────────

    internal var calendarAccounts: List<com.dashieapp.Dashie.halite.settings.data.CalendarAccount> = emptyList()
    internal var calendarLoading: Boolean = true


    // ── Categorize & Assign ────────────────────────────────────────

    /** Assignment types, tags, display names, and color overrides for the categorization screen */
    internal var calendarAssignmentTypes: MutableMap<String, String> = mutableMapOf()
    internal var calendarTags: MutableMap<String, List<String>> = mutableMapOf()
    internal var calendarDisplayNames: MutableMap<String, String> = mutableMapOf()
    internal var calendarColorOverrides: MutableMap<String, String> = mutableMapOf()

    internal var categorizeDataLoading = true


    // ── Sleep / Wake ─────────────────────────────────────────────────────

    /**
     * Navigate to a new fragment with slide animation.
     */
    fun showFragment(
        fragment: Fragment,
        tag: String,
        addToBackStack: Boolean = true
    ) {
        val token = SettingsProfiler.start("showFragment($tag)")
        SettingsTrace.shown("SettingsActivity.showFragment/${fragment::class.simpleName} tag=$tag")

        val transaction = supportFragmentManager.beginTransaction()

        if (addToBackStack) {
            transaction.setCustomAnimations(
                R.anim.settings_slide_in_right,
                R.anim.settings_slide_out_left,
                R.anim.settings_slide_in_left,
                R.anim.settings_slide_out_right
            )
            transaction.addToBackStack(tag)
        }

        transaction.replace(R.id.settingsFragmentContainer, fragment, tag)
        transaction.commit()

        SettingsProfiler.end(token)
    }

    /**
     * Navigate to a schema-driven settings page.
     */
    fun showSchemaPage(
        schemaProvider: () -> com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema,
        tag: String
    ) {
        val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
            schemaProvider = schemaProvider,
            valueProvider = schemaContext.valueProvider,
            callbackRegistry = schemaContext.callbackRegistry
        )
        showFragment(fragment, tag)
    }

    /**
     * Navigate back to the previous fragment.
     * Delegates to the current fragment's navigateBack() so it can intercept
     * (e.g. to show a reload prompt after URL/display changes).
     * Returns true if there was a fragment to go back to.
     */
    /**
     * Navigate back, delegating to the current fragment's navigateBack() so it can
     * intercept (e.g. to show a reload prompt). Called by hardware back key.
     */
    fun navigateBack(): Boolean {
        val current = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
        if (supportFragmentManager.backStackEntryCount > 0) {
            if (current is BaseSettingsFragment) {
                current.navigateBack()
            } else {
                supportFragmentManager.popBackStack()
            }
            return true
        }
        // Deep-linked (no back stack) — still let the fragment intercept
        if (isDeepLinked && current is BaseSettingsFragment) {
            current.navigateBack()
            return true
        }
        return false
    }

    /**
     * Pop the back stack directly, bypassing fragment interception.
     * Used by BaseSettingsFragment.navigateBack() to avoid infinite recursion.
     */
    fun popBackStackDirect(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        return false
    }

    /**
     * Check if we can navigate back (not at root).
     */
    fun canNavigateBack(): Boolean = supportFragmentManager.backStackEntryCount > 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (!navigateBack()) {
                    finishWithReopenCC()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Use navigateBack() instead")
    override fun onBackPressed() {
        if (!navigateBack()) {
            finishWithReopenCC()
        }
    }

    /**
     * Finish settings and signal MainActivity to re-open the Control Center.
     * Checks if any visible fragment has an exit interceptor (e.g. reload prompt).
     */
    fun finishWithReopenCC() {
        // Check the current fragment and any in the back stack for exit interceptors
        val current = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
        if (current is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment &&
            current.interceptExit()) return

        // Also check fragments in the back stack (e.g. HA page while on URL sub-screen)
        for (fragment in supportFragmentManager.fragments) {
            if (fragment !== current &&
                fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment &&
                fragment.interceptExit()) return
        }

        doFinishWithReopenCC()
    }

    /**
     * Actually finish with reopen CC result (no interceptor check).
     */
    fun doFinishWithReopenCC() {
        setResult(RESULT_REOPEN_CC)
        animateContainerOut { finish() }
    }

    /**
     * Finish settings and signal MainActivity to show the brightness settings dialog.
     */
    fun finishWithBrightnessSettings() {
        setResult(RESULT_OPEN_BRIGHTNESS_SETTINGS)
        animateContainerOut { finish() }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        // Complete pending music enable after returning from MA login
        com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.checkPendingMusicEnable(
            com.dashieapp.Dashie.halite.HalitePreferences(this), this
        )
        // Complete pending Immich login after returning from ImmichLoginActivity
        com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.checkPendingImmichLogin(
            com.dashieapp.Dashie.halite.HalitePreferences(this), this
        )
        // Re-check MA token role (e.g., after returning from MA re-login)
        checkMaTokenRole()
    }

    /**
     * Size the settings modal to match the Control Center overlay:
     * 80% of screen width (max 690dp), 92% of screen height (max 680dp).
     * On narrow screens (<600dp), go full-screen.
     */
    private fun applyResponsiveLayout() {
        val container = findViewById<CardView>(R.id.settingsContainer) ?: return
        val root = findViewById<FrameLayout>(R.id.settingsRoot) ?: return
        val screenWidthDp = resources.configuration.screenWidthDp

        if (screenWidthDp < 600) {
            // Phone-style: full-screen
            val params = container.layoutParams as FrameLayout.LayoutParams
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.marginStart = 0
            params.marginEnd = 0
            params.topMargin = 0
            params.bottomMargin = 0
            container.layoutParams = params
            container.radius = 0f

            root.setOnClickListener(null)
            root.isClickable = false
        } else {
            // Match Control Center sizing: 80% width max 690dp, 92% height max 680dp
            val dm = resources.displayMetrics
            val density = dm.density

            val screenWidth = dm.widthPixels
            val screenHeight = dm.heightPixels

            val maxWidthPx = (690 * density).toInt()
            val maxHeightPx = (680 * density).toInt()

            val targetWidth = minOf((screenWidth * 0.80).toInt(), maxWidthPx)
            val targetHeight = minOf((screenHeight * 0.92).toInt(), maxHeightPx)

            container.layoutParams = FrameLayout.LayoutParams(targetWidth, targetHeight).apply {
                gravity = android.view.Gravity.CENTER
            }
        }
    }

    // ── Container slide animations ────────────────────────────────────

    private fun animateContainerIn() {
        val container = findViewById<CardView>(R.id.settingsContainer) ?: return
        val root = findViewById<View>(R.id.settingsRoot) ?: return

        // Start container off-screen to the right
        container.translationX = resources.displayMetrics.widthPixels.toFloat()
        root.alpha = 0f

        container.post {
            val slideIn = ObjectAnimator.ofFloat(container, View.TRANSLATION_X, 0f)
            val fadeIn = ObjectAnimator.ofFloat(root, View.ALPHA, 1f)

            AnimatorSet().apply {
                playTogether(slideIn, fadeIn)
                duration = 300
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    private var isAnimatingOut = false

    private fun animateContainerOut(onEnd: () -> Unit) {
        if (isAnimatingOut) return
        isAnimatingOut = true

        val container = findViewById<CardView>(R.id.settingsContainer)
        val root = findViewById<View>(R.id.settingsRoot)

        if (container == null || root == null) {
            onEnd()
            return
        }

        val slideOut = ObjectAnimator.ofFloat(
            container, View.TRANSLATION_X,
            resources.displayMetrics.widthPixels.toFloat()
        )
        val fadeOut = ObjectAnimator.ofFloat(root, View.ALPHA, 0f)

        AnimatorSet().apply {
            playTogether(slideOut, fadeOut)
            duration = 250
            interpolator = DecelerateInterpolator()
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Keep screen on while settings are open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Reset the screensaver/sleep timer on any touch activity.
     * This prevents the screen from dimming while the user is interacting with settings.
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            // Throttle broadcast to avoid flooding MainActivity with evaluateJavascript calls
            val now = System.currentTimeMillis()
            if (now - lastTouchBroadcastTime >= TOUCH_BROADCAST_THROTTLE_MS) {
                lastTouchBroadcastTime = now
                sendBroadcast(
                    Intent("com.dashieapp.Dashie.ACTION_RESET_TIMER").apply {
                        setPackage(packageName)
                    }
                )
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        stopCameraStatusPoll()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        // Suppress default activity transition — we animate the container ourselves
        overridePendingTransition(0, 0)
    }

}
