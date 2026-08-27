package com.dashieapp.Dashie.webview

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView

import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.devicecontrols.DeviceControlsCoordinator
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.rtsp.RtspPlayerManager
import com.dashieapp.Dashie.voice.VoiceAssistantCoordinator
import com.dashieapp.Dashie.webview.delegates.JsBridgeDeviceDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeHaDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeAuthStateDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeLayoutDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeReauthDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeMusicDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgePerformanceDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeSettingsDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeSettingsDataDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeTimerDelegate
import com.dashieapp.Dashie.webview.delegates.JsBridgeVoiceDelegate

/**
 * JavaScript bridge for WebView communication.
 * Provides @JavascriptInterface methods callable from web app.
 *
 * This class is registered with WebView as "DashieNative" and handles:
 * - Voice control (TTS, speech recognition, wake word)
 * - Device controls (volume, brightness, settings)
 * - App lifecycle (restart, exit, cache clear)
 * - Halite/kiosk mode features
 * - Boot settings and preferences
 */
class DashieJSBridge(
    private val context: Context,
    private val webView: WebView,
    private val voiceAssistant: () -> VoiceAssistantCoordinator?,
    private val deviceControls: DeviceControlsCoordinator,
    private val halitePrefs: () -> HalitePreferences?,
    private val callbacks: Callbacks,
    private val rtspPlayerManager: () -> RtspPlayerManager? = { null }
) {
    companion object {
        private const val TAG = "DashieJSBridge"
    }

    // Track if overlay has captured keyboard focus (settings modal open, etc.)
    // Delegated to haliteDelegate for state storage
    val overlayHasKeyboardFocus: Boolean
        get() = haliteDelegate.overlayHasKeyboardFocus

    /**
     * Callbacks for actions that require MainActivity involvement.
     * Decomposed into domain-specific sub-interfaces for maintainability.
     * The composed [Callbacks] interface extends all sub-interfaces for backward compatibility.
     */
    interface CoreCallbacks {
        fun onExitApp()
        fun onRestartApp()
        fun onSoftRestartApp()
        fun onRecordWakeWordSample()
        fun isTabletDevice(): Boolean
        fun enableImmersiveMode()
        fun disableImmersiveMode()
        fun isKioskModeEnabled(): Boolean
        fun openWifiSetup()
        fun setDarkMode(isDark: Boolean)
        fun recreateWebViewForDarkMode() {}
        fun onForceKioskModeChanged(enabled: Boolean) {}
        fun onInjectTouch(x: Float, y: Float) {}
        // Called from JS (LayoutCanvasInputHandler) when d-pad LEFT hits the
        // leftmost slot in Dashie dashboard mode — yields focus to the sidebar.
        fun focusSidebar() {}
        // Portrait widgets mode: JS calls this (instead of focusSidebar) when
        // d-pad DOWN hits the bottom slot — focuses the native bottom bar.
        fun focusBottomBar() {}
        // Native Kotlin focus ring — JS passes slot bounds in PHYSICAL pixels
        // (CSS px × devicePixelRatio) and a state ("focused" or "activated").
        // Ring paints above WebView + native widgets, below the sidebar.
        fun setFocusRingBounds(x: Int, y: Int, w: Int, h: Int, state: String) {}
        fun hideFocusRing() {}
        // Activate a native Kotlin widget for d-pad input. While active,
        // MainInputHandler routes d-pad keys to NativeWidgetFocusManager
        // instead of forwarding to JS. Called from JS LayoutCanvasInputHandler
        // when the user presses Enter over a slot whose widget is native.
        fun enterNativeWidget(widgetId: String) {}
        // Symmetric to enterNativeWidget — JS coordinator (e.g. photosMenu)
        // calls this when its menu state goes idle so Kotlin's
        // NativeWidgetFocusManager releases d-pad focus back to the JS
        // layout canvas.
        fun releaseNativeWidget() {}
        // Mirrors DisplayPreferences.showUiTips. JS widgets (e.g. calendar
        // TV focus overlay) query this to decide whether to render their
        // on-screen hint strips.
        fun areUiTipsEnabled(): Boolean = true
    }

    interface DialogCallbacks {
        fun openAutoBrightnessSettings()
        fun showLockDialog()
        fun showPinRecoveryDialog()
        fun showExitConfirmation()
        /** Item 23: 3-button dialog for X on the sign-in screen when HA
         *  is configured. Default-implemented so existing callers don't
         *  need to define it. */
        fun showSignInCloseConfirmation() {}
        // Dismiss any currently-visible native overlays (control center,
        // sidebar popouts) so a WebView overlay (e.g. OAuth QR modal) shows
        // without something drawn on top of it.
        fun dismissSystemOverlays() {}
        fun onToggleVoiceEnabled()
        fun onScreensaverSettingsChanged() {}
        fun onOpenMotionWakeSettings() {}
        fun onShowHaMediaFolderPicker() {}
        fun onOpenAutoBrightnessSettings() {}
        fun onOpenSystemDetails() {}
        fun onShowPinDialog() {}
        fun onShowRecoveryEmailDialog() {}
        fun onShowRestartRequired(message: String) {}
        fun onShowDiagnosticsDialog() {}
        // Remote-trigger headless variants — Console publishes a command on
        // the per-device realtime channel, the webapp listener calls these.
        fun onSendDiagnosticsHeadless() {}
        fun onSendPendingCrashReportHeadless() {}
        fun onShowHaPipelinePicker(callback: (id: String, name: String) -> Unit) {}
    }

    interface SleepWakeCallbacks {
        fun onSleepNow() {}
        fun onWakeNow() {}
        fun onSleepSettingsChanged() {}
    }

    /**
     * Refcounted fullscreen-mode coordination for JS callers (auth flows,
     * voice overlay, photo widget fullscreen, modals). See
     * [com.dashieapp.Dashie.webview.FullscreenModeManager] for semantics.
     * Replaces the older one-way [DialogCallbacks.dismissSystemOverlays].
     *
     * Also handles JS-driven page-lifecycle signals: notifyPageUnloading
     * fires from a JS `pagehide` listener so native widgets / overlays
     * respond instantly to reloads instead of waiting for the slower
     * WebViewClient.onPageStarted lifecycle hook.
     */
    interface FullscreenCallbacks {
        fun enterFullscreen(token: String) {}
        fun exitFullscreen(token: String) {}
        fun notifyPageUnloading() {}
    }

    interface VoiceCallbacks {
        fun onVoicePipelineModeChanged() {}
        fun onResponseHandlingChanged(showResponses: Boolean) {}
        fun onMicMutedChanged(muted: Boolean) {}
        fun onSampleCollectionChanged(enabled: Boolean) {}
        fun onRequestMicrophonePermission() {}
        // FB28: JS asks native to show a voice-feedback toast (e.g. "didn't understand")
        // so it renders through the SAME native card as the no-speech notice and paints
        // over native overlays (music player). detail = optional subline (command heard).
        // ABSTRACT (no default body) — same reason as onShowHaCommandResult: a default `{}`
        // here is NOT forwarded by the Callbacks object's `by`-delegation, so it would run the
        // empty default instead of JsBridgeVoiceCallbacks' override and the toast never shows.
        fun onShowVoiceNotice(message: String, detail: String)
        // HA command confirmation ("Turned on String Lights") — the JS 'ha' toast, ported to a
        // native card so it draws OVER the voice overlay instead of behind it in the WebView.
        // ABSTRACT (no default body): Kotlin `by`-delegation only forwards abstract interface
        // members to the delegate — a method with a default `{}` here would run the empty default
        // instead of JsBridgeVoiceCallbacks' override (the card would silently never show).
        fun onShowHaCommandResult(message: String, command: String)
    }

    interface TimerCallbacks {
        fun onTimerCreated(timerJson: String) {}
        fun onTimerUpdated(timerJson: String) {}
        fun onTimerCompleted(timerJson: String) {}
        fun onTimerCancelled(timerId: String) {}
    }

    interface MusicCallbacks {
        fun onToggleMusicPlayer() {}
        fun isMusicPlayerVisible(): Boolean = false
        fun isMusicPlayerEnabled(): Boolean = false
        fun onMusicPlayerEnabledChanged(enabled: Boolean) {}
        fun onMusicPlayerEntityChanged(entityId: String) {}
        fun onMusicPlayerShowWithScreensaverChanged(enabled: Boolean) {}
    }

    interface VideoFeedCallbacks {
        fun isVideoFeedsEnabled(): Boolean = false
        fun areVideoFeedsPaused(): Boolean = false
        fun pauseVideoFeeds() {}
        fun resumeVideoFeeds() {}
        fun getActiveVideoFeedRuleIds(): String = "[]"
        fun showVideoFeedByRuleId(ruleId: String) {}
        fun dismissVideoFeedByRuleId(ruleId: String) {}
        // ABSTRACT on purpose: Kotlin by-delegation (MainActivity.createJsBridgeCallbacks)
        // only generates forwarders for abstract members — a default `{}` body here would
        // silently run the empty default instead of JsBridgeVideoFeedCallbacks' override.
        /** Voice "show me the pool camera" → large centered focal card (NOT the PiP pop-alert). */
        fun showVideoFeedFocal(ruleId: String)
        /** Voice "hide the pool camera" → closes focal drawer OR PiP card, whichever is up. */
        fun dismissVideoFeedByVoice(ruleId: String)
        /** Voice "show me the pool camera from 10 minutes ago" → full-screen Frigate playback, seeked. */
        fun showVideoFeedPlaybackAt(ruleId: String, timestampSec: Double)
        fun areVideoFeedAlertsMuted(): Boolean = false
        fun setVideoFeedAlertsMuted(muted: Boolean) {}
        fun getVideoFeedLayout(): String = "grid"
        fun cycleVideoFeedLayout(): String = "grid"
        fun setVideoFeedMenuOpen(open: Boolean) {}
    }

    interface SidebarSyncCallbacks {
        fun closeSidebar() {}
        fun dismissNativeSidebar() {}
        fun revealNativeSidebar() {}
        fun stopSidebarAutoHide() {}
        fun openHamburgerPopout() {}
        fun getSidebarHamburgerY(): Int = -1
        fun getControlCenterItemBounds(): String = "{}"
        fun onDashBarPinChanged(pinned: Boolean) {}
        fun onSetEnabledViews(viewIds: List<String>) {}
        fun onSetActiveView(viewId: String) {}
        fun onSetSidebarAccentColor(color: String) {}
    }

    interface NavigationCallbacks {
        fun openHaLogin(haUrl: String)
        fun onReloadDashboard() {}
        fun onOpenSettings() {}
        // Open native Settings activity directly to a specific page (e.g.
        // "calendar"). Same Intent extra ("navigate_to") used by control
        // center routing in MainActivity.
        fun onOpenSettingsPage(pageId: String) {}
        fun onOpenControlCenter() {}
        fun onOpenDrawer() {}
        fun onRtspEnabledChanged(enabled: Boolean) {}
        fun onReturnHomeTimeoutChanged(seconds: Int) {}
        fun onApiEnabledChanged(enabled: Boolean) {}
        fun onRequestDeviceAdmin() {}
        fun isRtspRunning(): Boolean = false
        fun hasRtspFailed(): Boolean = false
        fun getRtspFailureReason(): String? = null
        fun onHaUrlChanged(url: String) {}
        fun onHaIframeLoaded() {}
    }

    interface OnboardingCallbacks {
        fun onOnboardingComplete() {}
        fun onRequestPermissionByType(type: String) {}
        fun onRequestAllOnboardingPermissions() {}
    }

    // Composed master interface — backward compatible with all existing callers
    interface Callbacks :
        CoreCallbacks,
        DialogCallbacks,
        SleepWakeCallbacks,
        FullscreenCallbacks,
        VoiceCallbacks,
        TimerCallbacks,
        MusicCallbacks,
        VideoFeedCallbacks,
        SidebarSyncCallbacks,
        NavigationCallbacks,
        OnboardingCallbacks


    // Lazy-initialized DashieApiPreferences for standard Dashie HA token storage
    private val dashieApiPrefs by lazy {
        com.dashieapp.Dashie.api.DashieApiPreferences(context)
    }

    // Lazy-initialized managers for Voice & AI drawer section
    private val wakeWordModelManager by lazy {
        com.dashieapp.Dashie.wakeword.models.WakeWordModelManager(context)
    }

    // VoiceOverlayBridge delegate for overlay NLP responses
    // Set by HaliteVoiceController when VoicePipelineCoordinator is initialized
    var voiceOverlayBridge: com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge? = null
        set(value) {
            field = value
            // Propagate to the voice delegate so it can route JS responses
            voiceDelegate.voiceOverlayBridge = value
        }

    // Callback when JS signals TTS speech has ended
    var onTtsSpeechEndCallback: (() -> Unit)? = null
        set(value) {
            field = value
            voiceDelegate.onTtsSpeechEnd = value
        }

    // Callback when JS reports live brain progress (status copy for the thinking UI)
    var onVoiceProgressCallback: ((status: String) -> Unit)? = null
        set(value) {
            field = value
            voiceDelegate.onVoiceProgress = value
        }

    // Cascade conversation overlay (Dialog mode) — JS loop drives Live's overlay (§2).
    var onStartConversationOverlayCallback: (() -> Unit)? = null
        set(value) { field = value; voiceDelegate.onStartConversationOverlay = value }
    var onSetConversationListeningCallback: ((Boolean) -> Unit)? = null
        set(value) { field = value; voiceDelegate.onSetConversationListening = value }
    var onConversationTranscriptCallback: ((String, String) -> Unit)? = null
        set(value) { field = value; voiceDelegate.onConversationTranscript = value }
    var onConversationCardCallback: ((String) -> Unit)? = null
        set(value) { field = value; voiceDelegate.onConversationCard = value }
    var onEndConversationOverlayCallback: ((Boolean) -> Unit)? = null
        set(value) { field = value; voiceDelegate.onEndConversationOverlay = value }

    // Light sensor controller provider for custom auto-brightness
    // Set by MainActivity after HaliteComponentRegistry is initialized
    var lightSensorProvider: (() -> com.dashieapp.Dashie.halite.LightSensorBrightnessController?)? = null

    // Native sidebar provider — used by isDashBarPinned / getDashBarWidthPx
    // so JS layout doesn't reserve space for a sidebar that the visibility
    // gate has hidden via authSuppressed.
    var nativeSidebarProvider: (() -> com.dashieapp.Dashie.sidebar.NativeSidebarController?)? = null

    // Sync-by-default notifier (Phase 2). The webapp brackets its cloud→Kotlin
    // native-setter loop (pushDeviceSettingsToNative) with beginSettingsApply/
    // endSettingsApply so the notifier suppresses the resulting pref-change
    // echoes instead of dispatching them back out.
    var settingsSyncNotifierProvider: (() -> com.dashieapp.Dashie.halite.preferences.SettingsSyncNotifier?)? = null

    // Layout delegate for native Kotlin widget positioning
    val layoutDelegate by lazy { JsBridgeLayoutDelegate() }

    // Reauth delegate — JS reports calendar/agenda widget bounds and the
    // global auth_invalid count so the Kotlin overlay can show a "Sign in
    // required" pill on the affected widget(s).
    val reauthDelegate by lazy { JsBridgeReauthDelegate() }

    // Auth state delegate — JS reports auth + HA-configured signals so the
    // visibility gate can hide rotator/widgets/sidebar in unauthenticated states.
    val authStateDelegate by lazy { JsBridgeAuthStateDelegate() }

    // Schedule delegate — JS (Dashie Cloud voice path) → native reminders.
    // Callback wired in VoiceComponentWiring → ScheduledActionManager.
    val scheduleDelegate by lazy {
        com.dashieapp.Dashie.webview.delegates.JsBridgeScheduleDelegate(webView)
    }

    // Speaker-ID delegate — JS voice path → guided voice-enrollment wizard.
    // Callback wired in VoiceComponentWiring → VoiceEnrollmentController.
    val speakerIdDelegate by lazy {
        com.dashieapp.Dashie.webview.delegates.JsBridgeSpeakerIdDelegate(webView)
    }

    // Performance delegate for diagnostics and performance settings
    private val performanceDelegate by lazy {
        JsBridgePerformanceDelegate(context, webView, halitePrefs).also { delegate ->
            delegate.onPerformanceOverlayChanged = { enabled -> onPerformanceOverlayChanged?.invoke(enabled) }
            delegate.onThresholdsChanged = { onThresholdsChanged?.invoke() }
            delegate.onStealthRefreshSettingsChanged = { onStealthRefreshSettingsChanged?.invoke() }
        }
    }

    // Halite delegate for kiosk-specific features
    val haliteDelegate by lazy {
        com.dashieapp.Dashie.webview.delegates.JsBridgeHaliteDelegate(
            context = context,
            webView = webView,
            halitePrefs = halitePrefs,
            rtspPlayerManager = rtspPlayerManager
        ).also { delegate ->
            delegate.onOpenSystemDetails = { callbacks.onOpenSystemDetails() }
            delegate.onShowPinDialog = { callbacks.onShowPinDialog() }
            delegate.onSendDiagnosticsHeadless = { callbacks.onSendDiagnosticsHeadless() }
            delegate.onSendPendingCrashReportHeadless = { callbacks.onSendPendingCrashReportHeadless() }
            delegate.onOnboardingComplete = { callbacks.onOnboardingComplete() }
            delegate.onRequestPermissionByType = { type -> callbacks.onRequestPermissionByType(type) }
            delegate.onRequestAllOnboardingPermissions = { callbacks.onRequestAllOnboardingPermissions() }
        }
    }

    // HA delegate for Home Assistant proxy, login, tokens, and display prefs
    private val haDelegate by lazy {
        JsBridgeHaDelegate(context, webView, halitePrefs).also { delegate ->
            delegate.onOpenHaLogin = { haUrl -> callbacks.openHaLogin(haUrl) }
        }
    }

    // Timer delegate for timer overlay postMessage bridge
    private val timerDelegate by lazy {
        JsBridgeTimerDelegate(webView).also { delegate ->
            delegate.onTimerCreated = { timerJson -> callbacks.onTimerCreated(timerJson) }
            delegate.onTimerUpdated = { timerJson -> callbacks.onTimerUpdated(timerJson) }
            delegate.onTimerCompleted = { timerJson -> callbacks.onTimerCompleted(timerJson) }
            delegate.onTimerCancelled = { timerId -> callbacks.onTimerCancelled(timerId) }
        }
    }

    // Music delegate for music player overlay bridge
    private val musicDelegate by lazy {
        JsBridgeMusicDelegate(webView).also { delegate ->
            delegate.onMusicPlayerUpdate = { musicJson -> onMusicPlayerUpdate?.invoke(musicJson) }
            delegate.onMusicPlayerHide = { onMusicPlayerHide?.invoke() }
            delegate.onMusicPlayerEntityNotFound = { entityId -> onMusicPlayerEntityNotFound?.invoke(entityId) }
            delegate.onRecentlyPlayedUpdate = { json -> onRecentlyPlayedUpdate?.invoke(json) }
        }
    }

    // HA connection provider for direct REST API music calls
    // Provider for the native dashboard-health coordinator (forwarded to haDelegate
    // so JS iframe-health signals reach it). Provider survives WebView recreation.
    var dashboardHealthCoordinatorProvider:
        (() -> com.dashieapp.Dashie.halite.DashboardHealthCoordinator?)? = null
        set(value) {
            field = value
            haDelegate.dashboardHealthCoordinatorProvider = value
        }

    var musicHaConnectionProvider: (() -> JsBridgeMusicDelegate.HaConnectionInfo?)? = null
        set(value) {
            field = value
            musicDelegate.haConnectionProvider = value
        }

    // Callbacks for music player events (wired by MainActivity or HaliteComponentWiring)
    var onMusicPlayerUpdate: ((String) -> Unit)? = null
    var onMusicPlayerHide: (() -> Unit)? = null
    var onMusicPlayerEntityNotFound: ((String) -> Unit)? = null
    var onRecentlyPlayedUpdate: ((String) -> Unit)? = null

    /**
     * When set, sendMusicCommand routes through MA REST API instead of HA iframe CustomEvents.
     * Wired by HaliteComponentWiring when MA API credentials are available.
     */
    var onVoiceMusicCommand: ((String, String) -> Unit)?
        get() = musicDelegate.onVoiceMusicCommand
        set(value) { musicDelegate.onVoiceMusicCommand = value }

    // Video feed trigger callback: entityId, newState
    var onVideoFeedTrigger: ((String, String) -> Unit)? = null

    // Device delegate for device info, lifecycle, kiosk mode, dark mode, auto-brightness
    private val deviceDelegate by lazy {
        JsBridgeDeviceDelegate(context, webView, halitePrefs, deviceControls).also { delegate ->
            delegate.onExitApp = { callbacks.onExitApp() }
            delegate.onRestartApp = { callbacks.onRestartApp() }
            delegate.onSoftRestartApp = { callbacks.onSoftRestartApp() }
            delegate.onShowExitConfirmation = { callbacks.showExitConfirmation() }
            delegate.onShowSignInClose = { callbacks.showSignInCloseConfirmation() }
            delegate.onEnableImmersiveMode = { callbacks.enableImmersiveMode() }
            delegate.onDisableImmersiveMode = { callbacks.disableImmersiveMode() }
            delegate.isKioskModeEnabled = { callbacks.isKioskModeEnabled() }
            delegate.onSetDarkMode = { isDark -> callbacks.setDarkMode(isDark) }
            delegate.onOpenWifiSetup = { callbacks.openWifiSetup() }
            delegate.onOpenAutoBrightnessSettings = { callbacks.openAutoBrightnessSettings() }
            delegate.onRequestMicrophonePermission = { callbacks.onRequestMicrophonePermission() }
            delegate.lightSensorProvider = lightSensorProvider
        }
    }

    // Settings delegate for display, RTSP, sleep/wake, boot, and advanced settings
    internal val settingsDelegate by lazy {
        JsBridgeSettingsDelegate(context, webView, halitePrefs).also { delegate ->
            delegate.onRtspEnabledChanged = { enabled -> callbacks.onRtspEnabledChanged(enabled) }
            delegate.onReturnHomeTimeoutChanged = { seconds -> callbacks.onReturnHomeTimeoutChanged(seconds) }
            delegate.onRequestDeviceAdmin = { callbacks.onRequestDeviceAdmin() }
            delegate.onSleepNow = { callbacks.onSleepNow() }
            delegate.onWakeNow = { callbacks.onWakeNow() }
            delegate.onSleepSettingsChanged = { callbacks.onSleepSettingsChanged() }
            delegate.onScreensaverSettingsChanged = { callbacks.onScreensaverSettingsChanged() }
            delegate.onOpenMotionWakeSettings = { callbacks.onOpenMotionWakeSettings() }
            delegate.onShowHaMediaFolderPicker = { callbacks.onShowHaMediaFolderPicker() }
            delegate.isRtspRunning = { callbacks.isRtspRunning() }
            delegate.hasRtspFailed = { callbacks.hasRtspFailed() }
            delegate.getRtspFailureReason = { callbacks.getRtspFailureReason() }
        }
    }

    // Voice delegate for TTS, STT, wake word, AGC, and voice/AI settings
    private val voiceDelegate by lazy {
        JsBridgeVoiceDelegate(context, webView, voiceAssistant, halitePrefs, wakeWordModelManager).also { delegate ->
            delegate.onRecordWakeWordSample = { callbacks.onRecordWakeWordSample() }
            delegate.onToggleVoiceEnabled = { callbacks.onToggleVoiceEnabled() }
            delegate.onOpenMotionWakeSettings = { callbacks.onOpenMotionWakeSettings() }
            delegate.onVoicePipelineModeChanged = { callbacks.onVoicePipelineModeChanged() }
            delegate.onResponseHandlingChanged = { showResponses -> callbacks.onResponseHandlingChanged(showResponses) }
            delegate.onShowHaPipelinePicker = { callback -> callbacks.onShowHaPipelinePicker(callback) }
            delegate.onRestartApp = { callbacks.onRestartApp() }
            delegate.onMicMutedChanged = { muted -> callbacks.onMicMutedChanged(muted) }
            delegate.onSampleCollectionChanged = { enabled -> callbacks.onSampleCollectionChanged(enabled) }
            delegate.onShowVoiceNotice = { message, detail -> callbacks.onShowVoiceNotice(message, detail) }
            delegate.onShowHaCommandResult = { message, command -> callbacks.onShowHaCommandResult(message, command) }
            delegate.voiceOverlayBridge = voiceOverlayBridge
        }
    }

    // ============================================
    // Voice Overlay Bridge Methods (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun onVoiceResponse(requestId: String, jsonResponse: String) = voiceDelegate.onVoiceResponse(requestId, jsonResponse)

    @JavascriptInterface
    fun onVoiceError(requestId: String, error: String) = voiceDelegate.onVoiceError(requestId, error)

    @JavascriptInterface
    fun onVoiceProgress(status: String) = voiceDelegate.onVoiceProgress(status)

    /** Play the configured confirmation tone+volume; returns false if set to "Disabled". */
    @JavascriptInterface
    fun playConfirmationTone(): Boolean = voiceDelegate.playConfirmationTone()

    @JavascriptInterface
    fun onTtsSpeechEnd() = voiceDelegate.onTtsSpeechEnd()

    /** Device-fulfilled weather: return the latest on-device reading (honoring the
     *  HA↔Open-Meteo toggle) as normalized JSON for the JS voice weather tool. */
    @JavascriptInterface
    fun getVoiceWeather(query: String): String = voiceDelegate.getVoiceWeather(query)

    // ============================================
    // App Lifecycle Methods (delegated to deviceDelegate)
    // ============================================

    @JavascriptInterface
    fun exitApp() = deviceDelegate.exitApp()

    @JavascriptInterface
    fun showExitConfirmation() = deviceDelegate.showExitConfirmation()
    @android.webkit.JavascriptInterface
    fun showSignInCloseConfirmation() = deviceDelegate.showSignInCloseConfirmation()

    @JavascriptInterface
    fun signIn() = settingsDelegate.onDashieSignIn("")

    /**
     * Open Dashie sign-in flow with an explicit mode hint.
     * mode = "create" → /login?mode=create (sets create-account UI hints)
     * mode = "signin" → /login?mode=signin (default sign-in expectation)
     * mode = "" → /login (no mode; legacy path)
     */
    @JavascriptInterface
    fun signIn(mode: String) = settingsDelegate.onDashieSignIn(mode)

    @JavascriptInterface
    fun signOut() = settingsDelegate.onDashieSignOut()

    /**
     * Login-page hook: user clicked "Connect to Home Assistant".
     * Sets a one-shot pref flag and triggers the existing sign-out flow.
     * Kiosk-overlay onboarding consumes the flag on its next init.
     */
    @JavascriptInterface
    fun requestHaSetup() = settingsDelegate.requestHaSetup()

    @JavascriptInterface
    fun getPendingHaSetup(): Boolean = settingsDelegate.getPendingHaSetup()

    @JavascriptInterface
    fun clearPendingHaSetup() = settingsDelegate.clearPendingHaSetup()

    /**
     * Set the per-device home_assistant.enabled flag from JS. Used post-
     * OAuth when the user signed up via create-account mode so the
     * fresh-install default of true doesn't leave HA UI visible for
     * non-HA users.
     */
    @JavascriptInterface
    fun setHaEnabled(enabled: Boolean) = settingsDelegate.setHaEnabled(enabled)

    /**
     * Set display.layoutMode ("widgets" | "single_panel") from JS.
     * Used by the welcome-wizard Display Mode picker on tablets.
     */
    @JavascriptInterface
    fun setLayoutMode(mode: String) = settingsDelegate.setLayoutMode(mode)

    /**
     * D.70 — set `display.permissionPromptDeclined` from JS. Called when
     * the user picks "Skip for Now" on the welcome wizard's device-
     * permissions step (or the kiosk onboarding equivalent). Without
     * this, the next onResume triggers
     * MainServiceManager.validateApiPermissionsOnResume →
     * checkAndRequestApiPermissions on HA-enabled tablets and re-
     * prompts, ignoring the user's "later" choice.
     */
    @JavascriptInterface
    fun setPermissionPromptDeclined(declined: Boolean) =
        settingsDelegate.setPermissionPromptDeclined(declined)

    /**
     * Wipe per-user Kotlin SharedPrefs after a server-side account delete.
     * Called from the JS delete-account flow.
     */
    @JavascriptInterface
    fun clearAccountDataLocal() = settingsDelegate.clearAccountDataLocal()

    /**
     * Signal from JS that the delete-account flow has finished. Triggers
     * Kotlin-side cleanup (dismiss overlays, hide sidebar, kiosk viewport)
     * and shows an AlertDialog confirmation. Only called by the Kotlin-
     * driven Settings → Danger Zone path (skipConfirmation=true).
     */
    @JavascriptInterface
    fun onAccountDeleted() = settingsDelegate.onAccountDeleted()

    /**
     * Account-delete progress + result dialogs rendered native-side so
     * they layer above the Control Center overlay (web modals can't).
     */
    @JavascriptInterface
    fun showAccountDeleteProgress() = settingsDelegate.showAccountDeleteProgress()

    @JavascriptInterface
    fun dismissAccountDeleteProgress() = settingsDelegate.dismissAccountDeleteProgress()

    @JavascriptInterface
    fun showAccountDeleteResult(success: Boolean, message: String) =
        settingsDelegate.showAccountDeleteResult(success, message)

    // ============================================
    // Device Type & Version Info (delegated to deviceDelegate)
    // ============================================

    @JavascriptInterface
    fun getDeviceType(): String = deviceDelegate.getDeviceType()

    /** Physical-to-CSS pixel ratio (Android display density). See
     *  JsBridgeDeviceDelegate.getDisplayDensity for rationale. */
    @JavascriptInterface
    fun getDisplayDensity(): Float = deviceDelegate.getDisplayDensity()

    /**
     * Orientation-lock setting: "auto" | "landscape" | "portrait". Only
     * honored in widgets layout mode. Default "auto" if prefs unavailable.
     */
    @JavascriptInterface
    fun getOrientationLock(): String =
        halitePrefs()?.display?.orientationLock
            ?: com.dashieapp.Dashie.halite.preferences.DisplayPreferences.ORIENTATION_LOCK_AUTO

    /**
     * Cloud → Kotlin push of `display.orientationLock`. Invoked by
     * pushDeviceSettingsToNative on every applyDeviceSettings. Triggers
     * an ACTION_ORIENTATION_LOCK_CHANGED broadcast so OrientationController
     * picks up the new value live (no app restart).
     */
    @JavascriptInterface
    fun setOrientationLock(value: String) {
        val prefs = halitePrefs() ?: return
        val normalized = when (value) {
            "auto",
            "landscape", "landscape_reverse",
            "portrait", "portrait_reverse" -> value
            else -> "auto"
        }
        if (prefs.display.orientationLock == normalized) return
        prefs.display.orientationLock = normalized
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_ORIENTATION_LOCK_CHANGED")
                .apply { setPackage(context.packageName) }
        )
    }

    /**
     * Effective current screen orientation: "landscape" | "portrait".
     * Reflects what the WebView is currently rendered in, not what the
     * user set as the lock (those can differ during a rotation animation,
     * or when the lock pref is "auto" and the sensor flipped the device).
     */
    @JavascriptInterface
    fun getCurrentOrientation(): String {
        val cfg = context.resources.configuration
        return if (cfg.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
            "portrait" else "landscape"
    }

    @JavascriptInterface
    fun getAppVersion(): String = deviceDelegate.getAppVersion()

    @JavascriptInterface
    fun getAppVersionInfo(): String = deviceDelegate.getAppVersionInfo()

    /** Distribution channel — JS hides the App Reviewer Access link when "sideload". */
    @JavascriptInterface
    fun getBuildChannel(): String = deviceDelegate.getBuildChannel()

    /** Widevine DRM capability JSON — "will Netflix HD work here?" (L1 vs L3). */
    @JavascriptInterface
    fun getDrmInfo(): String = deviceDelegate.getDrmInfo()

    @JavascriptInterface
    fun isDebugBuild(): Boolean = BuildConfig.DEBUG

    @JavascriptInterface
    fun isForceKioskMode(): Boolean = halitePrefs()?.account?.forceKioskMode ?: false

    @JavascriptInterface
    fun setForceKioskMode(enabled: Boolean) {
        val prefs = halitePrefs()
        if (prefs == null) {
            Log.e(TAG, "❌ setForceKioskMode: halitePrefs is null!")
            return
        }
        prefs.account.forceKioskMode = enabled
        val verified = prefs.account.forceKioskMode
        Log.i(TAG, "🔄 setForceKioskMode($enabled) — verified read-back: $verified")
        webView.post { callbacks.onForceKioskModeChanged(enabled) }
    }

    // ============================================
    // Cache & Restart Methods (delegated to deviceDelegate)
    // ============================================

    @JavascriptInterface
    fun clearCache() = deviceDelegate.clearCache()

    @JavascriptInterface
    fun restartApp() = deviceDelegate.restartApp()

    @JavascriptInterface
    fun softRestartApp() = deviceDelegate.softRestartApp()

    // ============================================
    // TTS Methods (delegated to voiceDelegate)
    // ============================================

    // Cascade conversation overlay (Dialog mode) — JS loop drives Live's overlay (§2).
    @JavascriptInterface
    fun startConversationOverlay() = voiceDelegate.startConversationOverlay()

    @JavascriptInterface
    fun setConversationListening(listening: Boolean) = voiceDelegate.setConversationListening(listening)

    @JavascriptInterface
    fun conversationTranscript(speaker: String, text: String) = voiceDelegate.conversationTranscript(speaker, text)

    @JavascriptInterface
    fun conversationCard(cardJson: String) = voiceDelegate.conversationCard(cardJson)

    @JavascriptInterface
    fun endConversationOverlay(leaveResponseUp: Boolean) = voiceDelegate.endConversationOverlay(leaveResponseUp)

    @JavascriptInterface
    fun speak(text: String) = voiceDelegate.speak(text)

    @JavascriptInterface
    fun speakWithParams(text: String, rate: Float, pitch: Float) = voiceDelegate.speakWithParams(text, rate, pitch)

    @JavascriptInterface
    fun stopSpeaking() = voiceDelegate.stopSpeaking()

    @JavascriptInterface
    fun isSpeaking(): Boolean = voiceDelegate.isSpeaking()

    @JavascriptInterface
    fun scheduleReminder(notifyText: String, delaySeconds: Int, vernacular: String) =
        scheduleDelegate.scheduleReminder(notifyText, delaySeconds, vernacular)

    @JavascriptInterface
    fun cancelReminder(id: String) = scheduleDelegate.cancelReminder(id)

    @JavascriptInterface
    fun updateReminder(id: String, notifyText: String, fireAtEpochMs: String, vernacular: String) =
        scheduleDelegate.updateReminder(id, notifyText, fireAtEpochMs, vernacular)

    @JavascriptInterface
    fun createConditionAlert(entityPhrase: String, op: String, value: Double) =
        scheduleDelegate.createConditionAlert(entityPhrase, op, value)

    /** A scheduled HA command (kind='command') ran directly — show its confirmation
     *  in the HA-branded scheduled-action card. Parked until the user isn't mid-conversation
     *  (the command itself already ran on time — only the card waits). */
    @JavascriptInterface
    fun showScheduledHaCard(message: String) =
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.showScheduledHaCard(message)

    /** A scheduled HA command FAILED — small dismissible corner card, not the success modal.
     *  New method: JS feature-detects it and falls back to showScheduledHaCard on old APKs. */
    @JavascriptInterface
    fun showScheduledHaError(message: String) =
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.showScheduledHaError(message)

    /** The device's scheduled-action store as a JSON array — the DEVICE's truth, read by
     *  reminder-sync's mirror repair pass so a lost upsert (network blip) can be healed.
     *  New method: JS feature-detects it, so an old APK simply skips the repair. */
    @JavascriptInterface
    fun getScheduledActions(): String =
        com.dashieapp.Dashie.halite.schedule.ScheduleActionDirective.allActionsJson()

    @JavascriptInterface
    fun startVoiceEnrollment(membersJson: String) =
        speakerIdDelegate.startVoiceEnrollment(membersJson)

    @JavascriptInterface
    fun isSpeakerIdAvailable(): Boolean = speakerIdDelegate.isSpeakerIdAvailable()

    // ============================================
    // Speech Recognition Methods (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun startListening() = voiceDelegate.startListening()

    @JavascriptInterface
    fun stopListening() = voiceDelegate.stopListening()

    @JavascriptInterface
    fun cancelListening() = voiceDelegate.cancelListening()

    @JavascriptInterface
    fun isListening(): Boolean = voiceDelegate.isListening()

    // ============================================
    // Wake Word Detection Methods (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun startWakeWordDetection() = voiceDelegate.startWakeWordDetection()

    @JavascriptInterface
    fun stopWakeWordDetection() = voiceDelegate.stopWakeWordDetection()

    @JavascriptInterface
    fun isWakeWordActive(): Boolean = voiceDelegate.isWakeWordActive()

    @JavascriptInterface
    fun setWakeWordConfig(autoRecord: Boolean, duration: Int) = voiceDelegate.setWakeWordConfig(autoRecord, duration)

    @JavascriptInterface
    fun getWakeWordConfig(): String = voiceDelegate.getWakeWordConfig()

    // D5: the device's actual wake-word model id (WakeWordModelManager). JS reports it
    // up in getCurrentDeviceSettings so the console reflects the real per-device value.
    @JavascriptInterface
    fun getActiveWakeWord(): String = voiceDelegate.getActiveWakeWord()

    // Kiosk voice-controllable entity source (dashboard | assist) → the kiosk overlay's
    // buildHaVoiceContext honors the account's pick instead of always using the exposed list
    // (webapp/kiosk divergence fix, 20260717). "" → kiosk defaults to 'assist'.
    @JavascriptInterface
    fun getVoiceEntitySource(): String = voiceDelegate.getVoiceEntitySource()

    // WS-G §13.2: JSON array of the wake-word model ids this APK bundles — JS
    // feature-detects it to gate applying the account-default wake word.
    @JavascriptInterface
    fun getAvailableWakeWords(): String = voiceDelegate.getAvailableWakeWords()

    @JavascriptInterface
    fun changeWakeWord(modelName: String, threshold: Float) = voiceDelegate.changeWakeWord(modelName, threshold)

    @JavascriptInterface
    fun setWakeWordThreshold(threshold: Float) = voiceDelegate.setWakeWordThreshold(threshold)

    @JavascriptInterface
    fun getWakeWordThreshold(): Float = voiceDelegate.getWakeWordThreshold()

    // ============================================
    // AGC (Automatic Gain Control) Methods (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun setAGCGain(gain: Float) = voiceDelegate.setAGCGain(gain)

    @JavascriptInterface
    fun getAGCGain(): Float = voiceDelegate.getAGCGain()

    @JavascriptInterface
    fun startMicrophoneCalibration(durationSeconds: Int) =
        voiceDelegate.startMicrophoneCalibrationWithCallback(durationSeconds)

    // ============================================
    // Cloud STT Methods (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun startCloudSTTCapture(durationSeconds: Int, silenceThresholdSeconds: Float) =
        voiceDelegate.startCloudSTTCapture(durationSeconds, silenceThresholdSeconds)

    @JavascriptInterface
    fun stopCloudSTTCapture() = voiceDelegate.stopCloudSTTCapture()

    @JavascriptInterface
    fun setStreamingSTT(enabled: Boolean) = voiceDelegate.setStreamingSTT(enabled)

    @JavascriptInterface
    fun isStreamingSTT(): Boolean = voiceDelegate.isStreamingSTT()

    @JavascriptInterface
    fun setSilenceThreshold(silenceMs: Int) = voiceDelegate.setSilenceThreshold(silenceMs)

    @JavascriptInterface
    fun getSilenceThreshold(): Int = voiceDelegate.getSilenceThreshold()

    @JavascriptInterface
    fun startManualRecording(maxDurationSeconds: Int, silenceThresholdSeconds: Float) =
        voiceDelegate.startManualRecording(maxDurationSeconds, silenceThresholdSeconds)

    // ============================================
    // Device Info & Permissions (delegated to deviceDelegate)
    // ============================================

    @JavascriptInterface
    fun getDeviceInfo(): String = deviceDelegate.getDeviceInfo()

    /** Which EDITION this build is ("dashie" / "chickadee") — `JS_KOTLIN_CONTRACTS #64`.
     *  The kiosk-overlay bundle is shared by every flavor, so it asks at runtime. */
    @JavascriptInterface
    fun getEdition(): String = deviceDelegate.getEdition()

    /** This build's HA integration API prefix ("/api/dashie" / "/api/chickadee") —
     *  `JS_KOTLIN_CONTRACTS #63`, `ApiPaths.HA` verbatim. The overlay reads the ONE derivation
     *  rather than keeping a second edition→prefix table of its own. */
    @JavascriptInterface
    fun getHaApiPrefix(): String = deviceDelegate.getHaApiPrefix()

    /**
     * The Supabase project this APK targets, from the BUILD FLAVOR — not from the page's
     * hostname. A kiosk's page is served by the user's HA box, so hostname-sniffing
     * (auth-config.js) would send a staging/local kiosk to the PRODUCTION database.
     * See JsBridgeDeviceDelegate.getSupabaseConfig(). Kiosk Real Login, Phase 2.
     */
    @JavascriptInterface
    fun getSupabaseConfig(): String = deviceDelegate.getSupabaseConfig()

    @JavascriptInterface
    fun setDeviceName(name: String) = deviceDelegate.setDeviceName(name)

    @JavascriptInterface
    fun getDeviceFriendlyName(): String = deviceDelegate.getDeviceFriendlyName()

    @JavascriptInterface
    fun hasMicrophonePermission(): Boolean = deviceDelegate.hasMicrophonePermission()

    @JavascriptInterface
    fun requestMicrophonePermission() = deviceDelegate.requestMicrophonePermission()

    // ============================================
    // HA Iframe URL Tracking (for crash/OOM recovery)
    // ============================================

    /**
     * Called by kiosk-shell.js whenever the HA iframe navigates to a new URL.
     * Persists the URL so crash recovery can restore the correct dashboard tab,
     * and notifies the API service so currentPage reflects the HA iframe URL.
     */
    @JavascriptInterface
    fun onHaUrlChanged(url: String) {
        halitePrefs()?.performance?.lastHaIframeUrl = url
        callbacks.onHaUrlChanged(url)
    }

    /**
     * Called by kiosk-shell.js when the HA iframe finishes loading (and passes
     * the not-an-error-page check). Drives event-based injection of the
     * post-HA-load scripts (WS monitor, telemetry, music) instead of a blind
     * postDelayed timer that raced the iframe load.
     */
    @JavascriptInterface
    fun onHaIframeLoaded() {
        callbacks.onHaIframeLoaded()
    }

    /**
     * Set an ingress_session cookie via CookieManager (app-level, not partitioned).
     * Called by kiosk-shell.js which receives the cookie from the HA iframe via postMessage.
     * Fixes ingress 401s caused by cross-origin iframe cookie partitioning in newer WebView.
     */
    @JavascriptInterface
    fun setIngressCookie(origin: String, cookie: String) = haDelegate.setIngressCookie(origin, cookie)

    // ============================================
    // Exact Alarm Permission (Android 12+) - delegated to deviceDelegate
    // ============================================

    @JavascriptInterface
    fun canScheduleExactAlarms(): Boolean = deviceDelegate.canScheduleExactAlarms()

    @JavascriptInterface
    fun needsExactAlarmPermission(): Boolean = deviceDelegate.needsExactAlarmPermission()

    @JavascriptInterface
    fun setUseExactAlarms(enabled: Boolean): Boolean = deviceDelegate.setUseExactAlarms(enabled)

    // ============================================
    // Diagnostic Toast Control (delegated to deviceDelegate)
    // ============================================

    @JavascriptInterface
    fun enableDiagnosticToasts() = deviceDelegate.enableDiagnosticToasts()

    @JavascriptInterface
    fun disableDiagnosticToasts() = deviceDelegate.disableDiagnosticToasts()

    @JavascriptInterface
    fun isDiagnosticToastsEnabled(): Boolean = deviceDelegate.isDiagnosticToastsEnabled()

    // ============================================
    // Wake Word Sample Recording (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun recordWakeWordSample() = voiceDelegate.recordWakeWordSample()

    @JavascriptInterface
    fun getWakeWordSampleStats(): String = voiceDelegate.getWakeWordSampleStats()

    @JavascriptInterface
    fun clearWakeWordSamples(): String = voiceDelegate.clearWakeWordSamples()

    @JavascriptInterface
    fun exportWakeWordSamplesADB(): String = voiceDelegate.exportWakeWordSamplesADB()

    @JavascriptInterface
    fun getWakeWordSampleFileList(): String = voiceDelegate.getWakeWordSampleFileList()

    @JavascriptInterface
    fun getWakeWordSampleFileData(filename: String): String = voiceDelegate.getWakeWordSampleFileData(filename)

    @JavascriptInterface
    fun getDeviceId(): String = deviceDelegate.getDeviceId()

    // ============================================
    // Live Wake Word Sample Collection (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun setLiveSampleCollectionEnabled(enabled: Boolean) = voiceDelegate.setLiveSampleCollectionEnabled(enabled)

    @JavascriptInterface
    fun isLiveSampleCollectionEnabled(): Boolean = voiceDelegate.isLiveSampleCollectionEnabled()

    @JavascriptInterface
    fun getLiveSampleCollectionStats(): String = voiceDelegate.getLiveSampleCollectionStats()

    // ============================================
    // General Preferences (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getZipCode(): String = settingsDelegate.getZipCode()

    @JavascriptInterface
    fun onDashieAuthComplete(email: String) = settingsDelegate.onDashieAuthComplete(email)

    @JavascriptInterface
    fun returnToKioskAfterSignIn(email: String) = settingsDelegate.returnToKioskAfterSignIn(email)

    @JavascriptInterface
    fun isDashieAccountLinked(): Boolean = settingsDelegate.isDashieAccountLinked()

    @JavascriptInterface
    fun getDashieAccountEmail(): String = settingsDelegate.getDashieAccountEmail()

    /** Diagnostic: JSON snapshot of Kotlin's view of device settings. Consumed
     *  by js/utils/settings-snapshot.js to diff against localStorage /
     *  settingsStore / Supabase. */
    @JavascriptInterface
    fun getAllDeviceSettings(): String = settingsDelegate.getAllDeviceSettings()

    /** Diagnostic: JS pushes the completed dump JSON back through here so
     *  Kotlin can logcat it. evaluateJavascript can't await a Promise, so
     *  the ACTION_DUMP_SETTINGS broadcast hands off via this bridge. */
    @JavascriptInterface
    fun onSettingsDump(json: String) = settingsDelegate.onSettingsDump(json)

    // ============================================
    // Voice & AI Settings (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun getVoiceSettings(): String = voiceDelegate.getVoiceSettings()

    // Category readbacks for the native-settings-changed listener branches
    // (Phase 4.1 unification — voice/aiVoice/developer now round-trip live).
    @JavascriptInterface
    fun getVoiceDeviceSettings(): String = voiceDelegate.getVoiceDeviceSettings()

    @JavascriptInterface
    fun getAiVoiceSettings(): String = voiceDelegate.getAiVoiceSettings()

    @JavascriptInterface
    fun getDeveloperSettings(): String = voiceDelegate.getDeveloperSettings()

    /**
     * Voice pipeline cloud-restore: applyDeviceSettings on the JS side calls
     * this with the user_devices.voice blob to repopulate Kotlin VoicePreferences
     * after a storage wipe. Pair to the ACTION_VOICE_SETTINGS_CHANGED → JS
     * saveDeviceSettings('voice', ...) push that fires when Kotlin-originated
     * changes happen — closes the round-trip.
     */
    @JavascriptInterface
    fun setVoiceSettings(json: String) = voiceDelegate.setVoiceSettings(json)

    /**
     * aiVoice cloud-restore — paired with ACTION_AI_VOICE_SETTINGS_CHANGED.
     * Lets a wiped device restore personality + voiceKey from user_devices.aiVoice.
     */
    @JavascriptInterface
    fun setAiVoiceSettings(json: String) = voiceDelegate.setAiVoiceSettings(json)

    /**
     * CR2/CR4 credit state from the JS brain path — voice-command-router pushes the turn's
     * `metadata.credit` snapshot (+ degraded flag) here so the native cache/wake-time gate
     * stays fresh on logged-in tablets too. Writes the static CreditStateHolder directly
     * (recreation-safe — no per-instance callback to rewire nightly).
     */
    @JavascriptInterface
    fun onVoiceCreditState(json: String) = voiceDelegate.onVoiceCreditState(json)

    @JavascriptInterface
    fun isVoiceEnabled(): Boolean = voiceDelegate.isVoiceEnabled()

    @JavascriptInterface
    fun toggleVoiceEnabled() = voiceDelegate.toggleVoiceEnabled()

    @JavascriptInterface
    fun getActiveWakeWordModel(): String = voiceDelegate.getActiveWakeWordModel()

    @JavascriptInterface
    fun listAvailableWakeWordModels(): String = voiceDelegate.listAvailableWakeWordModels()

    @JavascriptInterface
    fun downloadWakeWordModel(version: String) = voiceDelegate.downloadWakeWordModel(version)

    // Voice License (delegated to voiceDelegate)

    @JavascriptInterface
    fun showVoicePurchaseDialog() = voiceDelegate.showVoicePurchaseDialog()


    // ============================================
    // Voice & AI Settings (Direct Setters - delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun setVoicePipelineMode(mode: String) = voiceDelegate.setVoicePipelineMode(mode)

    @JavascriptInterface
    fun setResponseHandling(mode: String) = voiceDelegate.setResponseHandling(mode)
    // ============================================
    // HA Pipeline Settings (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun getHaPipelineInfo(): String = voiceDelegate.getHaPipelineInfo()

    @JavascriptInterface
    fun showHaPipelinePicker() = voiceDelegate.showHaPipelinePicker()

    // ============================================
    // Microphone Mute (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun setMicMuted(muted: Boolean) = voiceDelegate.setMicMuted(muted)

    @JavascriptInterface
    fun isMicMuted(): Boolean = voiceDelegate.isMicMuted()

    // FB28: show a voice-feedback toast through the native card (see VoiceCallbacks).
    // detail is optional (empty string when the caller has no subline).
    @JavascriptInterface
    fun showVoiceNotice(message: String, detail: String) = voiceDelegate.showVoiceNotice(message, detail)

    /** JS HA action handler → native HA confirmation card (over the overlay). */
    @JavascriptInterface
    fun showHaCommandResult(message: String, command: String) = voiceDelegate.showHaCommandResult(message, command)

    // ============================================
    // Sample Collection (delegated to voiceDelegate)
    // ============================================

    @JavascriptInterface
    fun setSampleCollectionEnabled(enabled: Boolean) = voiceDelegate.setSampleCollectionEnabled(enabled)

    @JavascriptInterface
    fun isSampleCollectionEnabled(): Boolean = voiceDelegate.isSampleCollectionEnabled()

    // ============================================
    // Display Settings (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getDashboardZoom(): Int = settingsDelegate.getDashboardZoom()

    // BOTH widget-zoom methods were delegate-only until 2026-07-05 — the map's
    // native:/nativeGet: references silently feature-detect-skipped, so
    // widgetZoom had no cloud→Kotlin push, no equality-skip, and no display
    // readback. Caught live by the Phase 4.1 device test + tightened check I1
    // (which now requires @JavascriptInterface on THIS class).
    @JavascriptInterface
    fun getWidgetZoom(): Int = settingsDelegate.getWidgetZoom()

    @JavascriptInterface
    fun setWidgetZoom(zoom: Int) = settingsDelegate.setWidgetZoom(zoom)

    // Kotlin-canonical readback for the broadcast-synced display settings —
    // getCurrentDeviceSettings prefers these over settingsStore so the upload
    // can't clobber the cloud value with an un-hydrated default.
    @JavascriptInterface
    fun getWidgetFontSize(): Int = settingsDelegate.getWidgetFontSize()

    @JavascriptInterface
    fun getDisplaySize(): Int = settingsDelegate.getDisplaySize()

    // Console-honesty setters (audit F2/H9): user_devices edits from the web
    // Console now reach the device instead of being write-only traps.
    @JavascriptInterface
    fun setWidgetFontSize(size: Int) = settingsDelegate.setWidgetFontSize(size)

    @JavascriptInterface
    fun setDisplaySize(size: Int) = settingsDelegate.setDisplaySize(size)

    @JavascriptInterface
    fun getWeatherEntityId(): String = settingsDelegate.getWeatherEntityId()

    @JavascriptInterface
    fun getAutoBrightness(): Boolean = deviceDelegate.getAutoBrightness()

    @JavascriptInterface
    fun setDashboardZoom(zoom: Int) = settingsDelegate.setDashboardZoom(zoom)

    @JavascriptInterface
    fun getScreensaverDescription(): String = settingsDelegate.getScreensaverDescription()

    @JavascriptInterface
    fun getScreensaverSettings(): String = settingsDelegate.getScreensaverSettings()

    @JavascriptInterface
    fun setScreensaverTimeout(seconds: Int) = settingsDelegate.setScreensaverTimeout(seconds)

    @JavascriptInterface
    fun setScreensaverMode(mode: String) = settingsDelegate.setScreensaverMode(mode)

    @JavascriptInterface
    fun setDimBrightness(percent: Int) = settingsDelegate.setDimBrightness(percent)

    @JavascriptInterface
    fun setScreensaverShowClock(enabled: Boolean) = settingsDelegate.setScreensaverShowClock(enabled)

    @JavascriptInterface
    fun setScreensaverShowDate(enabled: Boolean) = settingsDelegate.setScreensaverShowDate(enabled)

    @JavascriptInterface
    fun setReduceBrightnessOnBlack(enabled: Boolean) = settingsDelegate.setReduceBrightnessOnBlack(enabled)

    @JavascriptInterface
    fun setClockPosition(position: String) = settingsDelegate.setClockPosition(position)

    @JavascriptInterface
    fun setClockSize(size: String) = settingsDelegate.setClockSize(size)

    @JavascriptInterface
    fun setClockFontSize(pt: Int) = settingsDelegate.setClockFontSize(pt)

    @JavascriptInterface
    fun setSlideshowInterval(seconds: Int) = settingsDelegate.setSlideshowInterval(seconds)

    @JavascriptInterface
    fun setScreensaverShowMetadata(enabled: Boolean) = settingsDelegate.setScreensaverShowMetadata(enabled)

    @JavascriptInterface
    fun setPhotoSourceType(type: String) = settingsDelegate.setPhotoSourceType(type)

    @JavascriptInterface
    fun setHaMediaFolder(folder: String) = settingsDelegate.setHaMediaFolder(folder)

    @JavascriptInterface
    fun setUnsplashQuery(query: String) = settingsDelegate.setUnsplashQuery(query)

    @JavascriptInterface
    fun setUnsplashArtistHyperlinks(enabled: Boolean) = settingsDelegate.setUnsplashArtistHyperlinks(enabled)

    @JavascriptInterface
    fun setWeatherOverlayEnabled(enabled: String) = settingsDelegate.setWeatherOverlayEnabled(enabled)

    /** Boolean-accepting variant for SETTINGS_KEY_MAP native-push path. */
    @JavascriptInterface
    fun setWeatherOverlayEnabledBool(enabled: Boolean) = settingsDelegate.setWeatherOverlayEnabledBool(enabled)

    @JavascriptInterface
    fun setWeatherEntityId(entityId: String) = settingsDelegate.setWeatherEntityId(entityId)

    // ============================================
    // Setters added for SETTINGS_KEY_MAP native-push
    // Called from window.DashieNative.<name>(value) — either directly via
    // saveDeviceSetting's native: path, or from applyDeviceSettings'
    // pushDeviceSettingsToNative() on startup / cross-device sync.
    // ============================================

    @JavascriptInterface
    fun setAnimationsEnabled(enabled: Boolean) = settingsDelegate.setAnimationsEnabled(enabled)

    @JavascriptInterface
    fun setAnimationLevel(level: String) = settingsDelegate.setAnimationLevel(level)

    @JavascriptInterface
    fun setShowUiTips(enabled: Boolean) = settingsDelegate.setShowUiTips(enabled)

    @JavascriptInterface
    fun setThemeFamily(family: String) = settingsDelegate.setThemeFamily(family)

    @JavascriptInterface
    fun setScreensaverHaPagePath(path: String) = settingsDelegate.setScreensaverHaPagePath(path)

    @JavascriptInterface
    fun setSlideshowTransition(transition: String) = settingsDelegate.setSlideshowTransition(transition)

    @JavascriptInterface
    fun setSlideshowShuffle(enabled: Boolean) = settingsDelegate.setSlideshowShuffle(enabled)

    @JavascriptInterface
    fun setPhotoFit(mode: String) = settingsDelegate.setPhotoFit(mode)

    @JavascriptInterface
    fun setScreensaverApp(packageName: String, label: String) = settingsDelegate.setScreensaverApp(packageName, label)

    @JavascriptInterface
    fun getInstalledApps(): String = settingsDelegate.getInstalledApps()

    @JavascriptInterface
    fun getMotionWakeDescription(): String = settingsDelegate.getMotionWakeDescription()

    @JavascriptInterface
    fun getMotionWakeMode(): String = settingsDelegate.getMotionWakeMode()

    @JavascriptInterface
    fun getSidebarIconSize(): String = settingsDelegate.getSidebarIconSize()

    @JavascriptInterface
    fun showHaMediaFolderPicker() = settingsDelegate.showHaMediaFolderPicker()

    @JavascriptInterface
    fun openMotionWakeSettings() = settingsDelegate.openMotionWakeSettings()

    @JavascriptInterface
    fun getLayoutDescription(): String = settingsDelegate.getLayoutDescription()

    @JavascriptInterface
    fun getLayoutMode(): String = settingsDelegate.getLayoutMode()

    @JavascriptInterface
    fun setLayoutEditMode(enabled: Boolean) = settingsDelegate.setLayoutEditMode(enabled)

    @JavascriptInterface
    fun setKotlinWidgetBounds(type: String, x: Int, y: Int, w: Int, h: Int, gridColumns: Int, gridRows: Int) =
        layoutDelegate.setKotlinWidgetBounds(type, x, y, w, h, gridColumns, gridRows)

    // Reauth pill overlay — JS pushes bounds (per calendar/agenda slot)
    // and global state (count + sole email) so Kotlin can render the
    // amber "Sign in required" pill in the bottom-left of the widget.
    @JavascriptInterface
    fun setReauthPillBounds(slotId: String, widgetType: String, x: Int, y: Int, w: Int, h: Int) =
        reauthDelegate.setReauthPillBounds(slotId, widgetType, x, y, w, h)

    @JavascriptInterface
    fun removeReauthPill(slotId: String) = reauthDelegate.removeReauthPill(slotId)

    @JavascriptInterface
    fun setReauthPillState(count: Int, soleEmail: String) =
        reauthDelegate.setReauthPillState(count, soleEmail)

    @JavascriptInterface
    fun removeKotlinWidget(type: String) = layoutDelegate.removeKotlinWidget(type)

    @JavascriptInterface
    fun hideKotlinWidget(type: String) = layoutDelegate.hideKotlinWidget(type)

    @JavascriptInterface
    fun showKotlinWidget(type: String) = layoutDelegate.showKotlinWidget(type)

    @JavascriptInterface
    fun onDashboardReady() = layoutDelegate.onDashboardReady()

    @JavascriptInterface
    fun setUiMode(mode: String) = authStateDelegate.setUiMode(mode)

    @JavascriptInterface
    fun dismissContextualOverlays(provider: String, email: String) =
        authStateDelegate.dismissContextualOverlays(provider, email)

    @JavascriptInterface
    fun notifyAuthFlowStarted() = authStateDelegate.notifyAuthFlowStarted()

    @JavascriptInterface
    fun notifyAuthFlowEnded() = authStateDelegate.notifyAuthFlowEnded()

    @JavascriptInterface
    fun setNativeWidgetsDimmed(dimmed: Boolean) = authStateDelegate.setNativeWidgetsDimmed(dimmed)

    @JavascriptInterface
    fun notifySupabaseJwtSaved() = authStateDelegate.notifySupabaseJwtSaved()

    @JavascriptInterface
    fun focusSidebar() = callbacks.focusSidebar()

    @JavascriptInterface
    fun focusBottomBar() = callbacks.focusBottomBar()

    @JavascriptInterface
    fun setFocusRingBounds(x: Int, y: Int, w: Int, h: Int, state: String) =
        callbacks.setFocusRingBounds(x, y, w, h, state)

    @JavascriptInterface
    fun hideFocusRing() = callbacks.hideFocusRing()

    @JavascriptInterface
    fun enterNativeWidget(widgetId: String) = callbacks.enterNativeWidget(widgetId)

    @JavascriptInterface
    fun releaseNativeWidget() = callbacks.releaseNativeWidget()

    @JavascriptInterface
    fun areUiTipsEnabled(): Boolean = callbacks.areUiTipsEnabled()

    @JavascriptInterface
    fun resetNativeWidgetGate() = layoutDelegate.resetNativeWidgetGate()

    @JavascriptInterface
    fun setRotatorBarBounds(slotId: String, x: Int, y: Int, w: Int, h: Int) =
        layoutDelegate.setRotatorBarBounds(slotId, x, y, w, h)

    @JavascriptInterface
    fun removeRotatorBar(slotId: String) = layoutDelegate.removeRotatorBar(slotId)

    @JavascriptInterface
    fun signalRotatorTouch(slotId: String) = layoutDelegate.signalRotatorTouch(slotId)

    @JavascriptInterface
    fun setRotatorPaused(slotId: String, paused: Boolean) =
        layoutDelegate.setRotatorPaused(slotId, paused)

    @JavascriptInterface
    fun setRotatorViews(slotId: String, viewsJson: String, activeWidgetType: String?) =
        layoutDelegate.setRotatorViews(slotId, viewsJson, activeWidgetType)

    @JavascriptInterface
    fun setRotatorActiveView(slotId: String, activeWidgetType: String?) =
        layoutDelegate.setRotatorActiveView(slotId, activeWidgetType)

    @JavascriptInterface
    fun setRotatorFrozenView(slotId: String, frozenWidgetType: String?) =
        layoutDelegate.setRotatorFrozenView(slotId, frozenWidgetType)

    @JavascriptInterface
    fun swapKotlinWidgets(showType: String, hideType: String) =
        layoutDelegate.swapKotlinWidgets(showType, hideType)

    @JavascriptInterface
    fun showRotatorPauseIndicator(slotId: String, x: Int, y: Int, w: Int, h: Int) =
        layoutDelegate.showRotatorPauseIndicator(slotId, x, y, w, h)

    @JavascriptInterface
    fun hideRotatorPauseIndicator(slotId: String) =
        layoutDelegate.hideRotatorPauseIndicator(slotId)

    // ============================================
    // Photos widget — domain-specific bridge methods (full screen, upload,
    // source type). Menu rendering goes through the generic widget
    // overlay bridge below.
    // ============================================

    @JavascriptInterface
    fun openPhotosExplorer(widgetId: String) =
        layoutDelegate.openPhotosExplorer(widgetId)

    @JavascriptInterface
    fun openPhotosUpload(widgetId: String) =
        layoutDelegate.openPhotosUpload(widgetId)

    @JavascriptInterface
    fun nextPhoto(widgetId: String) =
        layoutDelegate.nextPhoto(widgetId)

    @JavascriptInterface
    fun previousPhoto(widgetId: String) =
        layoutDelegate.previousPhoto(widgetId)

    @JavascriptInterface
    fun getPhotosSourceType(): String =
        layoutDelegate.getPhotosSourceType()

    @JavascriptInterface
    fun setPhotosSourceType(sourceType: String) =
        layoutDelegate.setPhotosSourceType(sourceType)

    @JavascriptInterface
    fun isPhotosShowingDashieCloudEmpty(): Boolean =
        layoutDelegate.isPhotosShowingDashieCloudEmpty()

    // ============================================================================
    // Generic widget overlay bridge (go-forward pattern)
    // ============================================================================

    @JavascriptInterface
    fun showWidgetHint(widgetId: String, x: Int, y: Int, w: Int, h: Int, text: String) =
        layoutDelegate.showWidgetHint(widgetId, x, y, w, h, text)

    @JavascriptInterface
    fun hideWidgetHint(widgetId: String) =
        layoutDelegate.hideWidgetHint(widgetId)

    @JavascriptInterface
    fun showWidgetMenu(
        widgetId: String, jsCoordinator: String,
        x: Int, y: Int, w: Int, h: Int,
        itemsJson: String, selectedIndex: Int
    ) = layoutDelegate.showWidgetMenu(widgetId, jsCoordinator, x, y, w, h, itemsJson, selectedIndex)

    @JavascriptInterface
    fun setWidgetMenuSelection(widgetId: String, index: Int) =
        layoutDelegate.setWidgetMenuSelection(widgetId, index)

    @JavascriptInterface
    fun setWidgetMenuItem(widgetId: String, index: Int, label: String, iconKey: String) =
        layoutDelegate.setWidgetMenuItem(widgetId, index, label, iconKey)

    @JavascriptInterface
    fun hideWidgetMenu(widgetId: String) =
        layoutDelegate.hideWidgetMenu(widgetId)

    @JavascriptInterface
    fun showWidgetSettings(
        widgetId: String, jsCoordinator: String, title: String,
        x: Int, y: Int, w: Int, h: Int,
        itemsJson: String, selectedIndex: Int
    ) = layoutDelegate.showWidgetSettings(widgetId, jsCoordinator, title, x, y, w, h, itemsJson, selectedIndex)

    @JavascriptInterface
    fun setWidgetSettingsSelection(widgetId: String, index: Int) =
        layoutDelegate.setWidgetSettingsSelection(widgetId, index)

    @JavascriptInterface
    fun setWidgetSettingsItemChecked(widgetId: String, index: Int, checked: Boolean) =
        layoutDelegate.setWidgetSettingsItemChecked(widgetId, index, checked)

    @JavascriptInterface
    fun hideWidgetSettings(widgetId: String) =
        layoutDelegate.hideWidgetSettings(widgetId)

    @JavascriptInterface
    fun isDashMenuEnabled(): Boolean = settingsDelegate.isDashMenuEnabled()

    @JavascriptInterface
    fun setDashMenuEnabled(enabled: Boolean) = settingsDelegate.setDashMenuEnabled(enabled)

    // ============================================
    // Video Feed PiP Settings (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getVideoFeedSettings(): String = settingsDelegate.getVideoFeedSettings()

    @JavascriptInterface
    fun saveVideoFeedConfig(configJson: String) = settingsDelegate.saveVideoFeedConfig(configJson)

    @JavascriptInterface
    fun previewVideoFeedChime(soundName: String) = settingsDelegate.previewVideoFeedChime(soundName)

    @JavascriptInterface
    fun getAlertVolume(): Int = settingsDelegate.getAlertVolume()

    @JavascriptInterface
    fun setAlertVolume(percent: Int) = settingsDelegate.setAlertVolume(percent)

    @JavascriptInterface
    fun onVideoFeedTrigger(json: String) {
        Log.i(TAG, "onVideoFeedTrigger received: $json")
        try {
            val obj = org.json.JSONObject(json)
            val entityId = obj.optString("entityId", "")
            val newState = obj.optString("newState", "")
            if (entityId.isNotEmpty() && newState.isNotEmpty()) {
                Log.i(TAG, "Video feed trigger: entity=$entityId, newState=$newState, callback=${onVideoFeedTrigger != null}")
                onVideoFeedTrigger?.invoke(entityId, newState)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse video feed trigger: $json", e)
        }
    }

    // ============================================
    // Video Feed PiP Controls (dash-menu camera popout)
    // ============================================

    @JavascriptInterface
    fun isVideoFeedsEnabled(): Boolean = callbacks?.isVideoFeedsEnabled() ?: false

    @JavascriptInterface
    fun areVideoFeedsPaused(): Boolean = callbacks?.areVideoFeedsPaused() ?: false

    @JavascriptInterface
    fun pauseVideoFeeds() {
        Log.i(TAG, "pauseVideoFeeds called from JS")
        callbacks?.pauseVideoFeeds()
    }

    @JavascriptInterface
    fun resumeVideoFeeds() {
        Log.i(TAG, "resumeVideoFeeds called from JS")
        callbacks?.resumeVideoFeeds()
    }

    @JavascriptInterface
    fun getActiveVideoFeedRuleIds(): String = callbacks?.getActiveVideoFeedRuleIds() ?: "[]"

    @JavascriptInterface
    fun showVideoFeedByRuleId(ruleId: String) {
        Log.i(TAG, "showVideoFeedByRuleId: $ruleId")
        callbacks?.showVideoFeedByRuleId(ruleId)
    }

    @JavascriptInterface
    fun dismissVideoFeedByRuleId(ruleId: String) {
        Log.i(TAG, "dismissVideoFeedByRuleId: $ruleId")
        callbacks?.dismissVideoFeedByRuleId(ruleId)
    }

    /** Voice show: the large centered focal card (playback-clickable), not the PiP pop-alert. */
    @JavascriptInterface
    fun showVideoFeedFocal(ruleId: String) {
        Log.i(TAG, "showVideoFeedFocal: $ruleId")
        callbacks?.showVideoFeedFocal(ruleId)
    }

    /** Voice hide: closes the focal drawer or the PiP card, whichever is showing. */
    @JavascriptInterface
    fun dismissVideoFeedByVoice(ruleId: String) {
        Log.i(TAG, "dismissVideoFeedByVoice: $ruleId")
        callbacks?.dismissVideoFeedByVoice(ruleId)
    }

    /** Voice playback: full-screen Frigate timeline, seeked to a past wall-clock time.
     *  timestampSec is a Double — JS numbers are doubles, and epoch seconds outlive Int. */
    @JavascriptInterface
    fun showVideoFeedPlaybackAt(ruleId: String, timestampSec: Double) {
        Log.i(TAG, "showVideoFeedPlaybackAt: $ruleId @ $timestampSec")
        callbacks?.showVideoFeedPlaybackAt(ruleId, timestampSec)
    }

    @JavascriptInterface
    fun areVideoFeedAlertsMuted(): Boolean = callbacks?.areVideoFeedAlertsMuted() ?: false

    @JavascriptInterface
    fun setVideoFeedAlertsMuted(muted: Boolean) {
        Log.i(TAG, "setVideoFeedAlertsMuted: $muted")
        callbacks?.setVideoFeedAlertsMuted(muted)
    }

    @JavascriptInterface
    fun getVideoFeedLayout(): String {
        val layout = callbacks?.getVideoFeedLayout() ?: "grid"
        Log.i(TAG, "getVideoFeedLayout: $layout")
        return layout
    }

    @JavascriptInterface
    fun cycleVideoFeedLayout(): String {
        val next = callbacks?.cycleVideoFeedLayout() ?: "grid"
        Log.i(TAG, "cycleVideoFeedLayout: → $next")
        return next
    }

    @JavascriptInterface
    fun setVideoFeedMenuOpen(open: Boolean) {
        callbacks?.setVideoFeedMenuOpen(open)
    }

    // ============================================
    // Camera Streaming Settings (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getRtspSettings(): String = settingsDelegate.getRtspSettings()

    @JavascriptInterface
    fun getEncoderCapabilities(): String = settingsDelegate.getEncoderCapabilities()

    @JavascriptInterface
    fun setRtspEnabled(enabled: Boolean) = settingsDelegate.setRtspEnabled(enabled)

    @JavascriptInterface
    fun setRtspResolution(resolution: String) = settingsDelegate.setRtspResolution(resolution)

    @JavascriptInterface
    fun setRtspCustomWidth(width: Int) = settingsDelegate.setRtspCustomWidth(width)

    @JavascriptInterface
    fun setRtspCustomHeight(height: Int) = settingsDelegate.setRtspCustomHeight(height)

    @JavascriptInterface
    fun setRtspFps(fps: Int) = settingsDelegate.setRtspFps(fps)

    @JavascriptInterface
    fun setRtspSoftwareEncoding(enabled: Boolean) = settingsDelegate.setRtspSoftwareEncoding(enabled)

    @JavascriptInterface
    fun setRtspDisableMirrorCorrection(enabled: Boolean) = settingsDelegate.setRtspDisableMirrorCorrection(enabled)

    @JavascriptInterface
    fun setHaSensorEnabled(enabled: Boolean) = settingsDelegate.setHaSensorEnabled(enabled)

    @JavascriptInterface
    fun setHaSensorMotionEnabled(enabled: Boolean) = settingsDelegate.setHaSensorMotionEnabled(enabled)

    @JavascriptInterface
    fun setHaSensorFaceEnabled(enabled: Boolean) = settingsDelegate.setHaSensorFaceEnabled(enabled)

    @JavascriptInterface
    fun isDeviceAdminActive(): Boolean = settingsDelegate.isDeviceAdminActive()

    @JavascriptInterface
    fun requestDeviceAdmin() = settingsDelegate.requestDeviceAdmin()

    // ============================================
    // Sleep/Wake Timer Settings (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getPhotoSettings(): String = settingsDelegate.getPhotoSettings()

    @JavascriptInterface
    fun getSleepSettings(): String = settingsDelegate.getSleepSettings()

    @JavascriptInterface
    fun getSleepDescription(): String = settingsDelegate.getSleepDescription()

    @JavascriptInterface
    fun setSleepEnabled(enabled: Boolean) = settingsDelegate.setSleepEnabled(enabled)

    @JavascriptInterface
    fun setSleepMethod(method: String) = settingsDelegate.setSleepMethod(method)

    @JavascriptInterface
    fun setSleepTime(time: String) = settingsDelegate.setSleepTime(time)

    @JavascriptInterface
    fun setWakeTime(time: String) = settingsDelegate.setWakeTime(time)

    @JavascriptInterface
    fun setResleepTimeout(minutes: Int) = settingsDelegate.setResleepTimeout(minutes)

    @JavascriptInterface
    fun setInactivityTimeout(seconds: Int) = settingsDelegate.setInactivityTimeout(seconds)

    @JavascriptInterface
    fun setMotionWakeForSleep(enabled: Boolean) = settingsDelegate.setMotionWakeForSleep(enabled)

    @JavascriptInterface
    fun getMotionWakeForSleep(): Boolean = settingsDelegate.getMotionWakeForSleep()

    @JavascriptInterface
    fun setSleepShowClock(enabled: Boolean) = settingsDelegate.setSleepShowClock(enabled)

    @JavascriptInterface
    fun getSleepShowClock(): Boolean = settingsDelegate.getSleepShowClock()

    @JavascriptInterface
    fun setReduceBrightnessOnSleep(enabled: Boolean) = settingsDelegate.setReduceBrightnessOnSleep(enabled)

    // ============================================
    // Display Preferences (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getUse24HourClock(): Boolean = settingsDelegate.getUse24HourClock()

    @JavascriptInterface
    fun setUse24HourClock(enabled: Boolean) = settingsDelegate.setUse24HourClock(enabled)

    // Alias for webapp compatibility (webapp calls set24HourClock, kiosk uses setUse24HourClock)
    @JavascriptInterface
    fun set24HourClock(enabled: Boolean) = settingsDelegate.setUse24HourClock(enabled)

    @JavascriptInterface
    fun getDateFormat(): String = settingsDelegate.getDateFormat()

    @JavascriptInterface
    fun setDateFormat(format: String) = settingsDelegate.setDateFormat(format)

    @JavascriptInterface
    fun getTemperatureUnit(): String = settingsDelegate.getTemperatureUnit()

    @JavascriptInterface
    fun setTemperatureUnit(unit: String) = settingsDelegate.setTemperatureUnit(unit)

    @JavascriptInterface
    fun setZipCode(zipCode: String) = settingsDelegate.setZipCode(zipCode)

    /**
     * Coordinates the web resolver geocoded for [locationKey] (a US zip, a
     * "City, Country", or a "City STATE" string). Native weather prefers these
     * over re-geocoding the string itself. See JsBridgeDisplayDelegate.
     */
    @JavascriptInterface
    fun setWeatherCoordinates(latitude: Double, longitude: Double, locationKey: String) =
        settingsDelegate.setWeatherCoordinates(latitude, longitude, locationKey)

    // Account-level preference readbacks (general.language / weather.useHa /
    // time.useHa) — the JS account_prefs handler writes them to user_settings.
    @JavascriptInterface
    fun getLanguage(): String = settingsDelegate.getLanguage()

    @JavascriptInterface
    fun getUseHaForWeather(): Boolean = settingsDelegate.getUseHaForWeather()

    @JavascriptInterface
    fun getUseHaForTime(): Boolean = settingsDelegate.getUseHaForTime()

    /** Cloud→Kotlin half of the account-level weather/time source toggles
     *  (user_settings.weather.useHa / time.useHa) — missing until 2026-07-06,
     *  which made the "account-wide" toggles behave device-local. Pushed by
     *  the webapp's account-keys converge, feature-detected (old APKs no-op). */
    @JavascriptInterface
    fun setUseHaForWeather(enabled: Boolean) = settingsDelegate.setUseHaForWeather(enabled)

    @JavascriptInterface
    fun setUseHaForTime(enabled: Boolean) = settingsDelegate.setUseHaForTime(enabled)

    /** Sync-by-default suppression (Phase 2). The webapp calls these around its
     *  cloud→Kotlin native-setter loop so the resulting SharedPreferences changes
     *  aren't dispatched back out as native-settings-changed (echo). Ref-counted;
     *  safe to no-op if the notifier isn't wired yet (old-JS/new-APK or vice versa). */
    @JavascriptInterface
    fun beginSettingsApply() { settingsSyncNotifierProvider?.invoke()?.beginApply() }

    @JavascriptInterface
    fun endSettingsApply() { settingsSyncNotifierProvider?.invoke()?.endApply() }

    /** Canonical IANA timezone the webapp resolved from the configured location
     *  (family.timezone). The realtime voice relay prefers it over the device
     *  default so a misconfigured device tz doesn't skew spoken times. */
    @JavascriptInterface
    fun setResolvedTimezone(timezone: String) = settingsDelegate.setResolvedTimezone(timezone)

    @JavascriptInterface
    fun getWeatherOverlayEnabled(): Boolean = settingsDelegate.getWeatherOverlayEnabled()

    @JavascriptInterface
    fun getScreenOffBehavior(): String = settingsDelegate.getScreenOffBehavior()

    @JavascriptInterface
    fun setScreenOffBehavior(behavior: String) = settingsDelegate.setScreenOffBehavior(behavior)

    @JavascriptInterface
    fun sleepNow() = settingsDelegate.sleepNow()

    @JavascriptInterface
    fun wakeNow() = settingsDelegate.wakeNow()

    @JavascriptInterface
    fun setReturnHomeTimeout(seconds: Int) = settingsDelegate.setReturnHomeTimeout(seconds)

    @JavascriptInterface
    fun setMemoryRecoveryEnabled(enabled: Boolean) = settingsDelegate.setMemoryRecoveryEnabled(enabled)

    // Callback for when performance overlay setting changes (so MainActivity can notify overlay)
    var onPerformanceOverlayChanged: ((Boolean) -> Unit)? = null

    // Callback for when thresholds change (so performance overlay can update tick marks)
    var onThresholdsChanged: (() -> Unit)? = null

    // Callback for when stealth refresh settings change (so HaliteScreenController can update)
    var onStealthRefreshSettingsChanged: (() -> Unit)? = null

    // ============================================
    // Performance & Diagnostics Settings
    // ============================================

    // ============================================
    // Performance Settings (delegated to JsBridgePerformanceDelegate)
    // ============================================

    @JavascriptInterface
    fun getPerformanceSettings(): String = performanceDelegate.getPerformanceSettings()

    @JavascriptInterface
    fun setStealthRefreshEnabled(enabled: Boolean) = performanceDelegate.setStealthRefreshEnabled(enabled)

    @JavascriptInterface
    fun setStealthRefreshIntervalMinutes(minutes: Int) = performanceDelegate.setStealthRefreshIntervalMinutes(minutes)

    @JavascriptInterface
    fun setDailyRefreshHour(hour: Int) = performanceDelegate.setDailyRefreshHour(hour)

    @JavascriptInterface
    fun setDailyRefreshHours(hoursJson: String) = performanceDelegate.setDailyRefreshHours(hoursJson)

    @JavascriptInterface
    fun setEmergencyRecoveryEnabled(enabled: Boolean) = performanceDelegate.setEmergencyRecoveryEnabled(enabled)

    @JavascriptInterface
    fun setEmergencyThresholdMode(mode: String) = performanceDelegate.setEmergencyThresholdMode(mode)

    @JavascriptInterface
    fun setEmergencyThresholdMb(mb: Int) = performanceDelegate.setEmergencyThresholdMb(mb)

    @JavascriptInterface
    fun setIdleRamPercent(percent: Int) = performanceDelegate.setIdleRamPercent(percent)

    @JavascriptInterface
    fun setIdleHeapPercent(percent: Int) = performanceDelegate.setIdleHeapPercent(percent)

    @JavascriptInterface
    fun setCriticalRamPercent(percent: Int) = performanceDelegate.setCriticalRamPercent(percent)

    @JavascriptInterface
    fun setCriticalHeapPercent(percent: Int) = performanceDelegate.setCriticalHeapPercent(percent)

    @JavascriptInterface
    fun setEnhancedLoggingEnabled(enabled: Boolean) = performanceDelegate.setEnhancedLoggingEnabled(enabled)

    @JavascriptInterface
    fun getSystemMetrics(): String = performanceDelegate.getSystemMetrics()

    @JavascriptInterface
    fun setDiagnosticsModeEnabled(enabled: Boolean) = performanceDelegate.setDiagnosticsModeEnabled(enabled)

    /** Injected HA kiosk script reports its hide-UI apply outcome here so it lands in the
     *  captured diagnostic (DiagnosticBuffer "KIOSK_UI"). See MainHaKioskCssInjector. */
    @JavascriptInterface
    fun reportKioskUi(detail: String?) = performanceDelegate.reportKioskUi(detail)

    @JavascriptInterface
    fun setPerformanceOverlayEnabled(enabled: Boolean) = performanceDelegate.setPerformanceOverlayEnabled(enabled)

    @JavascriptInterface
    fun setPerformanceOverlayPosition(position: String) = performanceDelegate.setPerformanceOverlayPosition(position)

    @JavascriptInterface
    fun setDashboardTelemetryEnabled(enabled: Boolean) = performanceDelegate.setDashboardTelemetryEnabled(enabled)

    @JavascriptInterface
    fun setAutoReloadMinutes(minutes: Int) = performanceDelegate.setAutoReloadMinutes(minutes)

    @JavascriptInterface
    fun sendTelemetryNow(): String = performanceDelegate.sendTelemetryNow()

    @JavascriptInterface
    fun openSystemDetails() = haliteDelegate.openSystemDetails()

    /** Remote command from Console (broadcast over user_devices realtime
     *  channel). Webapp listener calls this on receipt; no UI dialog. */
    @JavascriptInterface
    fun sendDiagnosticsHeadless() = haliteDelegate.sendDiagnosticsHeadless()

    /** Same remote-trigger pattern for pending crash report uploads. */
    @JavascriptInterface
    fun sendPendingCrashReportHeadless() = haliteDelegate.sendPendingCrashReportHeadless()

    // ============================================
    // Account Settings (delegated to haliteDelegate)
    // ============================================

    @JavascriptInterface
    fun showPinDialog() = haliteDelegate.showPinDialog()

    @JavascriptInterface
    fun hasLockPin(): Boolean = halitePrefs()?.lock?.hasPinSet ?: false

    // ============================================
    // System Settings (delegated to haliteDelegate/deviceDelegate)
    // ============================================

    // ============================================
    // API Settings (delegated to haliteDelegate)
    // ============================================

    @JavascriptInterface
    fun getApiSettings(): String = haliteDelegate.getApiSettings()

    @JavascriptInterface
    fun setApiEnabled(enabled: Boolean) = haliteDelegate.setApiEnabled(enabled)

    @JavascriptInterface
    fun setApiPort(port: Int) = haliteDelegate.setApiPort(port)

    @JavascriptInterface
    fun setApiPassword(password: String) = haliteDelegate.setApiPassword(password)

    // ============================================
    // Halite URL Management (delegated to haliteDelegate)
    // ============================================

    @JavascriptInterface
    fun saveHaUrl(url: String) = haliteDelegate.saveHaUrl(url)

    @JavascriptInterface
    fun isAppLocked(): Boolean = haliteDelegate.isAppLocked()

    @JavascriptInterface
    fun showLockDialog() = callbacks.showLockDialog()

    @JavascriptInterface
    fun showPinRecoveryDialog() = callbacks.showPinRecoveryDialog()

    @JavascriptInterface
    fun dismissSystemOverlays() = callbacks.dismissSystemOverlays()

    /**
     * Refcounted fullscreen-mode entry: hide native overlays so the
     * WebView can take over the screen. Pair with [exitFullscreen].
     * See FullscreenModeManager and js/utils/fullscreen-mode.js.
     */
    @JavascriptInterface
    fun enterFullscreen(token: String) = callbacks.enterFullscreen(token)

    /** Pair with [enterFullscreen]. Last exit restores native overlays. */
    @JavascriptInterface
    fun exitFullscreen(token: String) = callbacks.exitFullscreen(token)

    /**
     * JS-driven page-unload signal — fires from a `pagehide` listener
     * before the new page begins loading. Use this to hide native
     * widgets / clear fullscreen tokens / neutralize the sidebar
     * backdrop instantly, instead of waiting for
     * WebViewClient.onPageStarted (which can lag noticeably on Fire TV
     * and older tablets, leaving stale widgets visible on the loading
     * screen).
     */
    @JavascriptInterface
    fun notifyPageUnloading() = callbacks.notifyPageUnloading()

    @JavascriptInterface
    fun closeSidebar() = callbacks.closeSidebar()

    @JavascriptInterface
    fun dismissNativeSidebar() = callbacks.dismissNativeSidebar()

    @JavascriptInterface
    fun revealNativeSidebar() = callbacks.revealNativeSidebar()

    @JavascriptInterface
    fun stopSidebarAutoHide() = callbacks.stopSidebarAutoHide()

    @JavascriptInterface
    fun openHamburgerPopout() = callbacks.openHamburgerPopout()

    @JavascriptInterface
    fun getSidebarHamburgerY(): Int = callbacks.getSidebarHamburgerY()

    @JavascriptInterface
    fun getControlCenterItemBounds(): String = callbacks.getControlCenterItemBounds()

    @JavascriptInterface
    fun setOverlayKeyboardFocus(captured: Boolean) = haliteDelegate.setOverlayKeyboardFocus(captured)

    @JavascriptInterface
    fun injectTouch(x: Float, y: Float) = haliteDelegate.injectTouch(x, y)

    // ============================================
    // Onboarding (delegated to haliteDelegate)
    // ============================================

    @JavascriptInterface
    fun isSetupComplete(): Boolean = haliteDelegate.isSetupComplete()

    @JavascriptInterface
    fun startNetworkScan() = haliteDelegate.startNetworkScan()

    @JavascriptInterface
    fun onOnboardingComplete() = haliteDelegate.onOnboardingComplete()

    @JavascriptInterface
    fun requestAllOnboardingPermissions() = haliteDelegate.requestAllOnboardingPermissions()

    @JavascriptInterface
    fun hasPendingLoginSuccess(): Boolean = haliteDelegate.hasPendingLoginSuccess()

    @JavascriptInterface
    fun clearPendingLoginSuccess() = haliteDelegate.clearPendingLoginSuccess()

    @JavascriptInterface
    fun hasStoredHaCredentials(): Boolean = haliteDelegate.hasStoredHaCredentials()

    @JavascriptInterface
    fun areOnboardingPermissionsGranted(): Boolean = haliteDelegate.areOnboardingPermissionsGranted()

    // ============================================
    // Dash Menu (delegated to callbacks)
    // ============================================

    @JavascriptInterface
    fun toggleMusicPlayer() = callbacks.onToggleMusicPlayer()

    /** AI `music` tool (now_playing | search | play) — the cascade lane's device
     *  fulfillment (music-tool.js). Synchronous JSON-in/JSON-out; see the delegate. */
    @JavascriptInterface
    fun musicToolQuery(argsJson: String): String = musicDelegate.musicToolQuery(argsJson)

    @JavascriptInterface
    fun isMusicPlayerVisible(): Boolean = callbacks.isMusicPlayerVisible()

    @JavascriptInterface
    fun isMusicPlayerEnabled(): Boolean = callbacks.isMusicPlayerEnabled()

    @JavascriptInterface
    fun getMusicSettings(): String {
        val prefs = halitePrefs()
        val conn = prefs?.connection
        return org.json.JSONObject().apply {
            put("enabled", conn?.musicPlayerEnabled ?: false)
            put("fullScreenOnPlay", conn?.musicPlayerFullScreenOnPlay ?: false)
            put("showWithScreensaver", conn?.musicPlayerShowWithScreensaver ?: false)
            put("entityId", conn?.musicPlayerEntityId ?: "")
            put("defaultEntityId", conn?.musicPlayerDefaultEntityId ?: "")
            put("recentEntityIds", org.json.JSONArray(
                conn?.getRecentMusicPlayers() ?: emptyList<String>()
            ))
        }.toString()
    }

    @JavascriptInterface
    fun setMusicPlayerEnabled(enabled: Boolean) {
        halitePrefs()?.connection?.musicPlayerEnabled = enabled
        callbacks.onMusicPlayerEnabledChanged(enabled)
    }

    @JavascriptInterface
    fun setMusicPlayerFullScreenOnPlay(enabled: Boolean) {
        halitePrefs()?.connection?.musicPlayerFullScreenOnPlay = enabled
    }

    @JavascriptInterface
    fun setMusicPlayerShowWithScreensaver(enabled: Boolean) {
        halitePrefs()?.connection?.musicPlayerShowWithScreensaver = enabled
        callbacks.onMusicPlayerShowWithScreensaverChanged(enabled)
    }

    @JavascriptInterface
    fun setMusicPlayerEntity(entityId: String) {
        halitePrefs()?.connection?.musicPlayerEntityId = entityId
        halitePrefs()?.connection?.addRecentMusicPlayer(entityId)
        // Dispatch switch event so injected music JS uses the new entity
        musicDelegate.sendSwitchEntity(entityId)
        callbacks.onMusicPlayerEntityChanged(entityId)
    }

    @JavascriptInterface
    fun setMusicPlayerEntityWithName(entityId: String, displayName: String) {
        halitePrefs()?.connection?.setMusicPlayerEntityAndName(entityId, displayName)
        musicDelegate.sendSwitchEntity(entityId)
        callbacks.onMusicPlayerEntityChanged(entityId)
    }

    @JavascriptInterface
    fun setDefaultMusicPlayerEntity(entityId: String) {
        val conn = halitePrefs()?.connection
        conn?.musicPlayerDefaultEntityId = entityId
        conn?.musicPlayerEntityId = entityId
        conn?.addRecentMusicPlayer(entityId)
        // Dispatch switch event so injected music JS uses the new entity
        musicDelegate.sendSwitchEntity(entityId)
    }

    @JavascriptInterface
    fun setDefaultMusicPlayerEntityWithName(entityId: String, displayName: String) {
        val conn = halitePrefs()?.connection ?: return
        conn.musicPlayerDefaultEntityId = entityId
        conn.setMusicPlayerEntityAndName(entityId, displayName)
        musicDelegate.sendSwitchEntity(entityId)
    }

    @JavascriptInterface
    fun setMusicPlayerDisplayName(displayName: String) {
        halitePrefs()?.connection?.musicPlayerDisplayName = displayName
    }

    @JavascriptInterface
    fun openSettings() = callbacks.onOpenSettings()

    @JavascriptInterface
    fun openSettingsPage(pageId: String) = callbacks.onOpenSettingsPage(pageId)

    @JavascriptInterface
    fun openControlCenter() = callbacks.onOpenControlCenter()

    @JavascriptInterface
    fun openDrawer() = callbacks.onOpenDrawer()

    @JavascriptInterface
    fun setDashBarPinned(pinned: Boolean) {
        halitePrefs()?.display?.dashMenuPinned = pinned
        webView.post { callbacks.onDashBarPinChanged(pinned) }
    }

    @JavascriptInterface
    fun isDashBarPinned(): Boolean {
        // If the visibility gate has hidden the sidebar (auth-suppressed,
        // disabled, etc), report not-pinned so JS doesn't shift the layout.
        nativeSidebarProvider?.invoke()?.let { sidebar ->
            if (!sidebar.occupiesLayoutSpace()) return false
        }
        return halitePrefs()?.display?.dashMenuPinned ?: true
    }

    @JavascriptInterface
    fun hasNativeSidebar(): Boolean = true

    /**
     * Current effective native-sidebar width in physical pixels (respects
     * device-class boost from SidebarScaling). Returns 0 when the sidebar
     * is not pinned, so JS callers can use the result directly as an
     * offset without having to check isDashBarPinned() separately.
     *
     * Used by js/core/native-sidebar-layout.js to offset the layout
     * canvas to the right of the native sidebar strip.
     */
    @JavascriptInterface
    fun getDashBarWidthPx(): Int {
        val prefs = halitePrefs() ?: return 0
        if (!prefs.display.dashMenuPinned) return 0
        // Sidebar is gated off (auth, enabled, etc) — return 0 so JS layout
        // canvas doesn't reserve space for a hidden sidebar.
        nativeSidebarProvider?.invoke()?.let { sidebar ->
            if (!sidebar.occupiesLayoutSpace()) return 0
        }
        val ctx = webView.context
        val boost = com.dashieapp.Dashie.sidebar.SidebarScaling.computeDeviceBoost(ctx)
        val dp = com.dashieapp.Dashie.sidebar.NativeSidebarController.SIDEBAR_WIDTH_DP * boost
        return (dp * ctx.resources.displayMetrics.density).toInt()
    }

    /**
     * Called by JS when the enabled dashboard views change (on init and settings change).
     * @param viewsJson JSON array of enabled view IDs, e.g. ["calendar","chores","rewards"]
     */
    @JavascriptInterface
    fun setEnabledViews(viewsJson: String) {
        try {
            val arr = org.json.JSONArray(viewsJson)
            val viewIds = (0 until arr.length()).map { arr.getString(it) }
            webView.post { callbacks.onSetEnabledViews(viewIds) }
        } catch (e: Exception) {
            android.util.Log.w("DashieJSBridge", "setEnabledViews: invalid JSON: $viewsJson")
        }
    }

    @JavascriptInterface
    fun setActiveView(viewId: String) {
        webView.post { callbacks.onSetActiveView(viewId) }
    }

    /** Push theme colors from the webapp to the native sidebar. */
    @JavascriptInterface
    fun setSidebarThemeColors(colorsJson: String) {
        webView.post { callbacks.onSetSidebarAccentColor(colorsJson) }
    }

    @JavascriptInterface
    fun reloadDashboard() {
        webView.post { callbacks.onReloadDashboard() }
    }

    // ============================================
    // Boot Settings (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getBootSettings(): String = settingsDelegate.getBootSettings()

    @JavascriptInterface
    fun setStartOnBoot(enabled: Boolean) = settingsDelegate.setStartOnBoot(enabled)

    @JavascriptInterface
    fun setAutoReloadOnCrash(enabled: Boolean) = settingsDelegate.setAutoReloadOnCrash(enabled)

    @JavascriptInterface
    fun setPinAppWhenLocked(enabled: Boolean) = settingsDelegate.setPinAppWhenLocked(enabled)

    @JavascriptInterface
    fun isDeviceOwnerApp(): Boolean = settingsDelegate.isDeviceOwnerApp()

    @JavascriptInterface
    fun relinquishDeviceOwner(): Boolean = settingsDelegate.relinquishDeviceOwner()

    @JavascriptInterface
    fun showRelinquishDeviceOwnerConfirmation() = settingsDelegate.showRelinquishDeviceOwnerConfirmation()

    // ============================================
    // Locations / Chores / Calendar Settings (delegated to settingsDelegate)
    // ============================================

    @JavascriptInterface
    fun getLocationsSettings(): String = settingsDelegate.getLocationsSettings()

    @JavascriptInterface
    fun setLocationsSettings(json: String) = settingsDelegate.setLocationsSettings(json)

    @JavascriptInterface
    fun getChoresRewardsSettings(): String = settingsDelegate.getChoresRewardsSettings()

    @JavascriptInterface
    fun setChoresRewardsSettings(json: String) = settingsDelegate.setChoresRewardsSettings(json)

    @JavascriptInterface
    fun getCalendarSettings(): String = settingsDelegate.getCalendarSettings()

    @JavascriptInterface
    fun setCalendarSettings(json: String) = settingsDelegate.setCalendarSettings(json)

    // ============================================
    // Kiosk Mode / Immersive Mode (delegated to deviceDelegate)
    // ============================================

    @JavascriptInterface
    fun isKioskModeEnabled(): Boolean = deviceDelegate.isKioskModeActive()

    // ============================================
    // Dark Mode Control (delegated to deviceDelegate)
    // ============================================

    @JavascriptInterface
    fun setDarkMode(isDark: Boolean) = deviceDelegate.setDarkMode(isDark)

    // ============================================
    // Volume Control (delegated to DeviceControlsCoordinator)
    // ============================================

    @JavascriptInterface fun getVolume(): Int = deviceControls.getVolume()
    @JavascriptInterface fun setVolume(level: Int) = deviceControls.setVolume(level)
    @JavascriptInterface fun volumeUp(amount: Int): Int = deviceControls.volumeUp(amount)
    @JavascriptInterface fun volumeDown(amount: Int): Int = deviceControls.volumeDown(amount)
    @JavascriptInterface fun isMuted(): Boolean = deviceControls.isMuted()
    @JavascriptInterface fun mute() = deviceControls.mute()
    @JavascriptInterface fun unmute() = deviceControls.unmute()
    @JavascriptInterface fun toggleMute(): Boolean = deviceControls.toggleMute()
    @JavascriptInterface fun getVolumeInfo(): String = deviceControls.getVolumeInfo()

    // ============================================
    // Brightness Control (delegated to DeviceControlsCoordinator)
    // ============================================

    @JavascriptInterface fun getBrightness(): Int = deviceControls.getBrightness()
    @JavascriptInterface fun setBrightness(level: Int) = deviceControls.setBrightness(level)
    @JavascriptInterface fun brightnessUp(): Int = deviceControls.brightnessUp()
    @JavascriptInterface fun brightnessDown(): Int = deviceControls.brightnessDown()
    @JavascriptInterface fun canWriteSettings(): Boolean = deviceControls.canWriteSettings()
    @JavascriptInterface fun requestWriteSettingsPermission() = deviceControls.requestWriteSettingsPermission()

    // Auto-brightness (delegated to deviceDelegate)
    @JavascriptInterface
    fun getBrightnessInfo(): String = deviceDelegate.getBrightnessInfo()

    @JavascriptInterface
    fun setAutoBrightness(enabled: Boolean) = deviceDelegate.setAutoBrightness(enabled)

    @JavascriptInterface
    fun getAutoBrightnessSettings(): String = deviceDelegate.getAutoBrightnessSettings()

    @JavascriptInterface
    fun setAutoBrightnessSettings(min: Int, max: Int, curve: String) = deviceDelegate.setAutoBrightnessSettings(min, max, curve)

    @JavascriptInterface
    fun openAutoBrightnessSettings() = deviceDelegate.openAutoBrightnessSettings()

    // ============================================
    // Settings & Timezone (delegated to DeviceControlsCoordinator)
    // ============================================

    @JavascriptInterface fun getDeviceTimezone(): String = deviceControls.getDeviceTimezone()
    @JavascriptInterface fun openSystemSettings() = deviceControls.openSystemSettings()
    @JavascriptInterface fun openDateTimeSettings() = deviceControls.openDateTimeSettings()
    @JavascriptInterface fun openLocationSettings() = deviceControls.openLocationSettings()
    @JavascriptInterface fun isLocationEnabled(): Boolean = deviceControls.isLocationEnabled()
    @JavascriptInterface fun openAppSettings() = deviceControls.openAppSettings()
    @JavascriptInterface fun hasLocationPermission(): Boolean = deviceControls.hasLocationPermission()

    // ============================================
    // WiFi/Connectivity (delegated to DeviceControlsCoordinator)
    // ============================================

    @JavascriptInterface fun isConnectedToInternet(): Boolean = deviceControls.isConnectedToInternet()
    @JavascriptInterface fun getWifiStatus(): String = deviceControls.getWifiStatus()
    @JavascriptInterface fun openWifiSettings() = deviceControls.openWifiSettings()

    @JavascriptInterface
    fun openWifiSetup() = deviceDelegate.openWifiSetup()

    // ============================================
    // System Dark Mode Control (delegated to DeviceControlsCoordinator)
    // Uses Settings.Secure.ui_night_mode to control device-wide dark mode
    // Requires WRITE_SECURE_SETTINGS permission granted via ADB
    // ============================================

    /**
     * Check if system-wide dark mode is enabled
     */
    @JavascriptInterface
    fun isSystemDarkMode(): Boolean = deviceControls.isSystemDarkMode()

    /**
     * Set system-wide dark mode.
     * This changes the device's dark mode setting, which affects prefers-color-scheme
     * for all WebView content including iframes.
     * @param enabled true for dark mode, false for light mode
     * @return true if successful, false if permission denied
     */
    @JavascriptInterface
    fun setSystemDarkMode(enabled: Boolean): Boolean {
        Log.i(TAG, "🌓 setSystemDarkMode($enabled) called from JavaScript")
        // DarkModeManager writes Settings.Secure and calls AppCompatDelegate.setDefaultNightMode().
        // Since uiMode is in configChanges, this triggers onConfigurationChanged() on the activity,
        // which recreates the WebView with the correct prefers-color-scheme.
        return deviceControls.setSystemDarkMode(enabled)
    }

    /**
     * Toggle system-wide dark mode
     * @return the new state (true = dark, false = light)
     */
    @JavascriptInterface
    fun toggleSystemDarkMode(): Boolean = deviceControls.toggleSystemDarkMode()

    /**
     * Check if we have WRITE_SECURE_SETTINGS permission
     * If false, user needs to grant via ADB:
     * adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS
     */
    @JavascriptInterface
    fun canWriteSecureSettings(): Boolean = deviceControls.canWriteSecureSettings()

    /**
     * Get system dark mode info as JSON string
     */
    @JavascriptInterface
    fun getSystemDarkModeInfo(): String = deviceControls.getSystemDarkModeInfo()

    // ============================================
    // Home Assistant cloud-restorable settings blob (delegated to JsBridgeHaDelegate)
    // Round-trips through user_devices.settings.home_assistant via the JS unified
    // push helper (SETTINGS_KEY_MAP _native shape: 'json'). Restores HA core,
    // device API, camera/RTSP, video feeds, power, and alert prefs after a
    // storage wipe. Tokens/credentials/per-device hardware are excluded.
    // ============================================

    @JavascriptInterface
    fun getHomeAssistantSettings(): String = haDelegate.getHomeAssistantSettings()

    @JavascriptInterface
    fun setHomeAssistantSettings(json: String) = haDelegate.setHomeAssistantSettings(json)

    // ============================================
    // Home Assistant API Proxy (delegated to JsBridgeHaDelegate)
    // ============================================

    // ============================================
    // HA iframe health (kiosk-shell.js → DashboardHealthCoordinator)
    // ============================================

    /** JS detected the HA dashboard iframe is on an error page (404/5xx/blank). */
    @JavascriptInterface
    fun onHaIframeError(url: String) = haDelegate.reportHaIframeError(url)

    /** JS confirmed the HA dashboard iframe loaded healthy. */
    @JavascriptInterface
    fun onHaIframeHealthy() = haDelegate.reportHaIframeHealthy()

    @JavascriptInterface
    fun isHAProxyAvailable(): Boolean = haDelegate.isHAProxyAvailable()

    @JavascriptInterface
    fun callHAProxy(requestId: String, url: String, token: String, method: String, body: String?) =
        haDelegate.callHAProxy(requestId, url, token, method, body)

    // ============================================
    // Home Assistant Login (delegated to JsBridgeHaDelegate)
    // ============================================

    @JavascriptInterface
    fun openHaLogin(haUrl: String) = haDelegate.openHaLogin(haUrl)

    /**
     * Generic sink for short auth-related diagnostic messages from JS.
     * Routes to PersistentLog with the AUTH category so we get a single
     * cross-process audit trail without piping every stage through a
     * dedicated callback.
     */
    @JavascriptInterface
    fun logAuthEvent(stage: String, detail: String) {
        com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
            "AUTH", "JS:$stage $detail"
        )
    }

    @JavascriptInterface
    fun getHaToken(): String = haDelegate.getHaToken()

    @JavascriptInterface
    fun getHaBaseUrl(): String = haDelegate.getHaBaseUrl()

    @JavascriptInterface
    fun hasHaToken(): Boolean = haDelegate.hasHaToken()

    // ============================================
    // Home Assistant UI Display Settings (delegated to JsBridgeHaDelegate)
    // ============================================

    @JavascriptInterface
    fun getHaHideSidebar(): Boolean = haDelegate.getHaHideSidebar()

    @JavascriptInterface
    fun setHaHideSidebar(hide: Boolean) = haDelegate.setHaHideSidebar(hide)

    @JavascriptInterface
    fun getHaHideTabs(): Boolean = haDelegate.getHaHideTabs()

    @JavascriptInterface
    fun setHaHideTabs(hide: Boolean) = haDelegate.setHaHideTabs(hide)

    @JavascriptInterface
    fun getHaHideSearch(): Boolean = haDelegate.getHaHideSearch()

    @JavascriptInterface
    fun setHaHideSearch(hide: Boolean) = haDelegate.setHaHideSearch(hide)

    @JavascriptInterface
    fun getHaHideAssistant(): Boolean = haDelegate.getHaHideAssistant()

    @JavascriptInterface
    fun setHaHideAssistant(hide: Boolean) = haDelegate.setHaHideAssistant(hide)

    @JavascriptInterface
    fun getHaDisplayPrefs(): String = haDelegate.getHaDisplayPrefs()

    @JavascriptInterface
    fun getHaConnectionSettings(): String = haDelegate.getHaConnectionSettings()

    @JavascriptInterface
    fun setHaBaseUrl(url: String) = haDelegate.setHaBaseUrl(url)

    @JavascriptInterface
    fun setHaDashboard(dashboard: String) = haDelegate.setHaDashboard(dashboard)

    @JavascriptInterface
    fun setHaAutoBuild(autoBuild: Boolean) = haDelegate.setHaAutoBuild(autoBuild)

    // ============================================
    // Native RTSP Camera Playback (delegated to haliteDelegate)
    // ============================================

    @JavascriptInterface
    fun isNativeRtspSupported(): Boolean = haliteDelegate.isNativeRtspSupported()

    @JavascriptInterface
    fun startRtspStream(id: String, rtspUrl: String, x: Int, y: Int, width: Int, height: Int) =
        haliteDelegate.startRtspStream(id, rtspUrl, x, y, width, height)

    @JavascriptInterface
    fun stopRtspStream(id: String) = haliteDelegate.stopRtspStream(id)

    @JavascriptInterface
    fun updateRtspStreamPosition(id: String, x: Int, y: Int, width: Int, height: Int) =
        haliteDelegate.updateRtspStreamPosition(id, x, y, width, height)

    // ============================================================================
    // Timer Overlay Bridge Methods (delegated to JsBridgeTimerDelegate)
    // ============================================================================

    @JavascriptInterface
    fun onTimerCreated(timerJson: String) = timerDelegate.onTimerCreated(timerJson)

    @JavascriptInterface
    fun onTimerUpdated(timerJson: String) = timerDelegate.onTimerUpdated(timerJson)

    @JavascriptInterface
    fun onTimerCompleted(timerJson: String) = timerDelegate.onTimerCompleted(timerJson)

    @JavascriptInterface
    fun onTimerCancelled(timerId: String) = timerDelegate.onTimerCancelled(timerId)

    // Timer Control Methods (Kotlin → JavaScript)
    fun createTimer(durationSeconds: Int, description: String?) = timerDelegate.createTimer(durationSeconds, description)
    fun pauseTimer(timerId: String) = timerDelegate.pauseTimer(timerId)
    fun resumeTimer(timerId: String) = timerDelegate.resumeTimer(timerId)
    fun cancelTimer(timerId: String) = timerDelegate.cancelTimer(timerId)
    fun minimizeTimer(timerId: String) = timerDelegate.minimizeTimer(timerId)
    fun expandTimer(timerId: String) = timerDelegate.expandTimer(timerId)

    // ============================================================================
    // Music Player Overlay Bridge Methods (delegated to JsBridgeMusicDelegate)
    // ============================================================================

    /**
     * Update music player state from JavaScript.
     * Called when HA media_player entity state changes.
     * @param musicJson JSON with track info, playback state, etc.
     */
    @JavascriptInterface
    fun updateMusicPlayer(musicJson: String) = musicDelegate.updateMusicPlayer(musicJson)

    /**
     * Hide the music player from JavaScript.
     * Called when media_player goes to idle/off state.
     */
    @JavascriptInterface
    fun hideMusicPlayer() = musicDelegate.hideMusicPlayer()

    /**
     * Called from JavaScript when the configured music player entity is not found in HA states.
     */
    @JavascriptInterface
    fun onMusicPlayerEntityNotFound(entityId: String) = musicDelegate.onMusicPlayerEntityNotFound(entityId)

    /**
     * Called from JavaScript with recently played items from Music Assistant.
     */
    @JavascriptInterface
    fun updateRecentlyPlayed(json: String) = musicDelegate.updateRecentlyPlayed(json)

    /**
     * Called from JavaScript (HA iframe) with the result of a media players query.
     * Used by the shell-relay query path when HA is in an iframe.
     */
    @JavascriptInterface
    fun onMediaPlayersQueried(json: String) {
        com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector.handleMediaPlayersQueryResult(json)
    }

    // Music Control Methods (Kotlin → JavaScript)
    fun sendMusicPlayPause() = musicDelegate.sendPlayPause()
    fun sendMusicNext() = musicDelegate.sendNext()
    fun sendMusicPrevious() = musicDelegate.sendPrevious()
    fun sendMusicStop() = musicDelegate.sendStop()
    fun sendMusicToggleMinimize() = musicDelegate.sendToggleMinimize()
    fun sendMusicVolumeSet(volumeLevel: Float) = musicDelegate.sendVolumeSet(volumeLevel)
    fun sendMusicSwitchEntity(entityId: String) = musicDelegate.sendSwitchEntity(entityId)

    /**
     * Generic music command handler callable from JavaScript.
     * Used by voice commands to control music playback.
     *
     * @param command The command type: play_media, play, pause, next, previous, play_pause
     * @param paramsJson JSON string with command parameters (e.g., { "mediaId": "Taylor Swift" })
     */
    @JavascriptInterface
    fun sendMusicCommand(command: String, paramsJson: String) = musicDelegate.sendMusicCommand(command, paramsJson)

    /**
     * Play media using Music Assistant's smart search.
     * Auto-detects whether query is artist, track, album, etc.
     *
     * @param query The search query (artist name, song title, etc.)
     * @param artist Optional artist filter to narrow search
     */
    @JavascriptInterface
    fun sendMusicPlayMedia(query: String, artist: String?) = musicDelegate.sendPlayMedia(query, artist)

    /** Play a recently played item by its MA URI. Called from Kotlin, not from JS. */
    fun sendPlayRecentItem(uri: String) = musicDelegate.sendPlayRecentItem(uri)

    /**
     * Show music player with mock data for testing/development.
     * Call this to verify the music player UI renders correctly.
     */
    @JavascriptInterface
    fun showMockMusicPlayer() {
        Log.i(TAG, "🎵 showMockMusicPlayer called")
        musicDelegate.showMockPlayer()
    }

    // ============================================
    // Settings Data Bridge (Family, Calendar — delegated to settingsDataDelegate)
    // ============================================

    /** Settings data delegate for async data callbacks from JS services */
    val settingsDataDelegate = JsBridgeSettingsDataDelegate()

    @JavascriptInterface
    fun onFamilyMembersLoaded(json: String) = settingsDataDelegate.onFamilyMembersLoaded(json)

    @JavascriptInterface
    fun onFamilyMemberSaved(json: String) = settingsDataDelegate.onFamilyMemberSaved(json)

    @JavascriptInterface
    fun onFamilyMemberDeleted(memberId: String) = settingsDataDelegate.onFamilyMemberDeleted(memberId)

    @JavascriptInterface
    fun onFamilyError(message: String) = settingsDataDelegate.onFamilyError(message)

    @JavascriptInterface
    fun onFamilyNameLoaded(name: String) = settingsDataDelegate.onFamilyNameLoaded(name)

    @JavascriptInterface
    fun onChoresParticipantsLoaded(json: String) = settingsDataDelegate.onChoresParticipantsLoaded(json)

    @JavascriptInterface
    fun onCalendarDataLoaded(json: String) = settingsDataDelegate.onCalendarDataLoaded(json)

    @JavascriptInterface
    fun onCalendarMetadataLoaded(json: String) = settingsDataDelegate.onCalendarMetadataLoaded(json)

    @JavascriptInterface
    fun onCalendarAssignmentDataLoaded(json: String) = settingsDataDelegate.onCalendarAssignmentDataLoaded(json)

    @JavascriptInterface
    fun onCalendarToggled() = settingsDataDelegate.onCalendarToggled()

    @JavascriptInterface
    fun onCalendarError(message: String) = settingsDataDelegate.onCalendarError(message)

    @JavascriptInterface
    fun onCalendarImportSuccess(message: String) = settingsDataDelegate.onCalendarImportSuccess(message)

    @JavascriptInterface
    fun onPersonalitiesLoaded(json: String) = settingsDataDelegate.onPersonalitiesLoaded(json)

    @JavascriptInterface
    fun onPersonalitiesError(message: String) = settingsDataDelegate.onPersonalitiesError(message)

    // ── Subscription State Sync ──────────────────────────────────────
    // ============================================

    /** Subscription delegate for syncing state from JS to native */
    val subscriptionDelegate: com.dashieapp.Dashie.webview.delegates.JsBridgeSubscriptionDelegate by lazy {
        com.dashieapp.Dashie.webview.delegates.JsBridgeSubscriptionDelegate(context)
    }

    @JavascriptInterface
    fun syncSubscriptionState(json: String) = subscriptionDelegate.syncSubscriptionState(json)

    @JavascriptInterface
    fun showTrialConfirmation(days: Int) = subscriptionDelegate.showTrialConfirmation(days)

    @JavascriptInterface
    fun showAccountAlreadyExistsNotice() = subscriptionDelegate.showAccountAlreadyExistsNotice()

    @JavascriptInterface
    fun getAndroidId(): String = subscriptionDelegate.getAndroidId()

    // ── Feature Visibility Sync ──────────────────────────────────────
    // ============================================

    /** Feature visibility delegate — pushed by JS featureAccessService on
     *  init/refresh so Kotlin Control Center can hide alpha-rollout features
     *  from default beta users. JS authoritative; Kotlin follower.
     *  Plan: dashieapp_staging .reference/build-plans/20260508_DASHIE_CLOUD_BETA_READINESS.md §1.6 */
    val featureVisibilityDelegate: com.dashieapp.Dashie.webview.delegates.JsBridgeFeatureVisibilityDelegate by lazy {
        com.dashieapp.Dashie.webview.delegates.JsBridgeFeatureVisibilityDelegate(context)
    }

    @JavascriptInterface
    fun syncFeatureVisibility(json: String) = featureVisibilityDelegate.syncFeatureVisibility(json)

    // ============================================
    // Debug: MWW WAV File Test
    // ============================================

    @JavascriptInterface
    fun testMwwWav(filename: String) {
        // If filename is a full path, use as-is. Otherwise look in app's filesDir.
        val wavPath = if (filename.startsWith("/")) filename
            else java.io.File(context.filesDir, filename).absolutePath
        android.util.Log.i("DashieJSBridge", "testMwwWav: resolved=$wavPath exists=${java.io.File(wavPath).exists()}")
        Thread {
            com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordWavTest.processWavFile(
                context, wavPath
            )
        }.start()
    }

}
