package com.dashieapp.Dashie

// BUNDLE-EXEMPT: calendarService — KNOWN GAP: CC count seeding silently skips on kiosk-real-login (cards stay 'Loading…'); webapp-only service. Tracked in VOICE_SINGLE_PATH triage 2026-07-18.
// BUNDLE-EXEMPT: familyService — KNOWN GAP: same CC count seeding skip as calendarService (see above).
// BUNDLE-EXEMPT: dashieSyncSubscription — KNOWN GAP: CC 'Check Subscription' silently no-ops on kiosk-real-login; webapp-only global. Tracked in VOICE_SINGLE_PATH triage 2026-07-18.

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import com.dashieapp.Dashie.controlcenter.ControlCenterOverlay
import com.dashieapp.Dashie.controlcenter.ControlCenterStateProvider
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.NativeDialogHost
import com.dashieapp.Dashie.api.DashieServiceManager
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.webview.DashieJSBridge

/**
 * Builds the native Control Center overlay + state provider.
 * Extracted from MainActivity.initializeControlCenter() so the activity
 * doesn't carry the CC-page-id → settings-page-id mapping or the
 * count-fetch JS template inline.
 */
object MainControlCenterFactory {

    private const val TAG = "MainControlCenterFactory"

    fun create(
        activity: Activity,
        halitePrefsProvider: () -> HalitePreferences?,
        haliteRegistryProvider: () -> HaliteComponentRegistry?,
        haliteScreenControllerProvider: () -> HaliteScreenController?,
        dashieServiceManagerProvider: () -> DashieServiceManager?,
        dialogHostProvider: () -> NativeDialogHost?,
        webViewProvider: () -> WebView?,
        jsBridgeProvider: () -> DashieJSBridge?,
        settingsActivityLauncher: ActivityResultLauncher<Intent>,
        updateControllerProvider: () -> com.dashieapp.Dashie.halite.update.DashieUpdateController? = { null }
    ): Pair<ControlCenterOverlay, ControlCenterStateProvider>? {
        halitePrefsProvider() ?: return null

        val wakeWordModelManager by lazy {
            com.dashieapp.Dashie.wakeword.models.WakeWordModelManager(activity)
        }

        val stateProvider = ControlCenterStateProvider(
            context = activity,
            prefsProvider = halitePrefsProvider,
            rtspRunningProvider = { dashieServiceManagerProvider()?.isRtspServerRunning() ?: false },
            wakeWordModelManagerProvider = { wakeWordModelManager },
            apiBindFailedProvider = {
                val mgr = dashieServiceManagerProvider()
                Pair(mgr?.isApiBindFailed ?: false, mgr?.apiServerPort ?: 2323)
            }
        )

        val overlay = ControlCenterOverlay(
            activity = activity,
            stateProvider = stateProvider,
            visibilityGate = haliteRegistryProvider()?.nativeWidgetVisibilityGate,
            // Kiosk-lock settings gate — reuse the dialog host's LockDialogs so
            // PIN attempt/lockout counters are shared with the sidebar flows.
            lockDialogsProvider = { dialogHostProvider()?.lockDialogs },
            onNavigateToSettings = { pageId ->
                routeNavigateToSettings(
                    pageId,
                    activity,
                    webViewProvider,
                    dashieServiceManagerProvider,
                    haliteScreenControllerProvider,
                    jsBridgeProvider,
                    settingsActivityLauncher
                )
            },
            onOpenNativeDialog = { action ->
                activity.runOnUiThread {
                    when (action) {
                        "wake-mode" -> dialogHostProvider()?.showMotionWakePickerFromBridge()
                        "system-details" -> dialogHostProvider()?.showSystemDetailsFromBridge()
                        "auto-brightness" -> dialogHostProvider()?.showAutoBrightnessSettingsFromBridge()
                    }
                }
            },
            onResetScreenTimers = {
                haliteScreenControllerProvider()?.screenDimmer?.resetTimer()
                haliteScreenControllerProvider()?.resetSleepInactivityTimer()
            },
            onDismiss = {
                haliteScreenControllerProvider()?.screenDimmer?.resetTimer()
                haliteScreenControllerProvider()?.resetSleepInactivityTimer()
            },
            onRequestCounts = {
                webViewProvider()?.evaluateJavascript(REQUEST_COUNTS_JS, null)
            },
            // Subscribe dialog "Check Subscription" button → trigger JS-side
            // check-subscription sync, which writes back via the JS bridge.
            onTriggerSubscriptionSync = {
                webViewProvider()?.evaluateJavascript(
                    "if (window.dashieSyncSubscription) { try { window.dashieSyncSubscription(); } catch (e) { console.error('dashieSyncSubscription failed', e); } }",
                    null
                )
            },
            // Software-update card — visible only after the update banner
            // was dismissed to Control Center; tapping opens the larger
            // update-details modal (banner is for auto-fire only).
            isUpdatePending = { updateControllerProvider()?.isUpdatePendingInControlCenter() ?: false },
            onUpdateAction = { updateControllerProvider()?.showUpdateModal() },
            updateVersionName = { updateControllerProvider()?.availableVersionName },
            isUpdateDownloading = { updateControllerProvider()?.isUpdateDownloading() ?: false },
            isUpdateFailed = { updateControllerProvider()?.lastDownloadFailed() ?: false },
            isUpdateInstalling = { updateControllerProvider()?.isUpdateInstalling() ?: false },
            installingSummaryText = { updateControllerProvider()?.postInstallReopenHint() }
        )

        // Wire persistent count callbacks from JS bridge → CC state provider.
        // Each callback also repaints the overlay immediately (if showing) —
        // without that, fresh counts sat in the cache until the next 3s poll
        // tick, which is what made the "Not configured" flash linger.
        jsBridgeProvider()?.settingsDataDelegate?.let { delegate ->
            delegate.onFamilyCountUpdated = { count ->
                stateProvider.cachedFamilyMemberCount = count
                overlay.refreshContentIfVisible()
            }
            delegate.onCalendarCountsUpdated = { accounts, active, total ->
                stateProvider.cachedCalendarAccountCount = accounts
                stateProvider.cachedCalendarActiveCount = active
                stateProvider.cachedCalendarTotalCount = total
                // Marks the zero/nonzero result as authoritative — the card
                // shows "Loading…" instead of "Not configured" until this
                // first lands (ControlCenterStateProvider.buildCalendarCard).
                stateProvider.calendarCountsLoaded = true
                overlay.refreshContentIfVisible()
            }
        }

        // Auth-invalid count — flips the Calendar CC card to WARN with a
        // "Sign-in required" summary. Reuses the same JS bridge call that
        // drives the in-widget pill (CalendarReauthOverlayManager owns the
        // primary listener; this is an additional subscriber).
        jsBridgeProvider()?.reauthDelegate?.addStateListener { count, soleEmail ->
            stateProvider.cachedCalendarAuthInvalidCount = count
            stateProvider.cachedCalendarAuthInvalidEmail = soleEmail
            overlay.refreshContentIfVisible()
        }

        overlay.initialize()
        Log.i(TAG, "🎛️ Native Control Center overlay initialized")
        return Pair(overlay, stateProvider)
    }

    private fun routeNavigateToSettings(
        pageId: String,
        activity: Activity,
        webViewProvider: () -> WebView?,
        dashieServiceManagerProvider: () -> DashieServiceManager?,
        haliteScreenControllerProvider: () -> HaliteScreenController?,
        jsBridgeProvider: () -> DashieJSBridge?,
        settingsActivityLauncher: ActivityResultLauncher<Intent>
    ) {
        val nativePage = NATIVE_SETTINGS_PAGE_MAP[pageId]
        if (nativePage == null) {
            // Every pageId the CC emits is in the map (17/17 as of 2026-07). The old JS route
            // (window.openSettingsToPage) died with the kiosk settings bundle — an unmapped id
            // here means a NEW CC page shipped without a native mapping. Be loud, don't no-op.
            Log.e(TAG, "DROP: CC settings pageId '$pageId' has no NATIVE_SETTINGS_PAGE_MAP entry — " +
                "navigation dropped; add the mapping (JS settings route is retired)")
            return
        }

        // Share WebView reference and RTSP state providers for native settings
        SettingsActivity.webViewRef = java.lang.ref.WeakReference(webViewProvider())
        SettingsActivity.isRtspRunning = { dashieServiceManagerProvider()?.isRtspServerRunning() ?: false }
        SettingsActivity.hasRtspFailed = { dashieServiceManagerProvider()?.hasRtspFailed() ?: false }
        SettingsActivity.getRtspFailureReason = { dashieServiceManagerProvider()?.getRtspFailureReason() }
        SettingsActivity.getRtspStreamUrl = { dashieServiceManagerProvider()?.getRtspStreamUrl() ?: "" }
        SettingsActivity.getRtspClientCount = { dashieServiceManagerProvider()?.getRtspClientCount() ?: 0 }
        SettingsActivity.getCameraPreviewFrame = { dashieServiceManagerProvider()?.getCameraPreviewFrame() }
        SettingsActivity.onPowerManualToggle = { turnedOn, overrideTarget ->
            haliteScreenControllerProvider()?.powerWatchdog?.onManualToggle(turnedOn, overrideTarget)
        }
        SettingsActivity.jsBridgeRef = jsBridgeProvider()

        val intent = Intent(activity, SettingsActivity::class.java).putExtra("navigate_to", nativePage)
        settingsActivityLauncher.launch(intent)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }

    private val NATIVE_SETTINGS_PAGE_MAP = mapOf(
        "cc-preferences" to "preferences",
        "cc-voice-ai" to "voice_ai",
        "cc-ha" to "home_assistant",
        "cc-music" to "music",
        "cc-photos" to "photos",
        "cc-camera" to "camera",
        "cc-battery-charging" to "battery_charging",
        "cc-video-feeds" to "video_feeds",
        "cc-screensaver" to "screensaver",
        "cc-sleep" to "sleep",
        "cc-advanced" to "advanced",
        "cc-account" to "account",
        "cc-display" to "display",
        "cc-family" to "family",
        "cc-locations" to "locations",
        "cc-chores" to "chores_rewards",
        "cc-calendar" to "calendar"
    )

    private val REQUEST_COUNTS_JS = """
        (async () => {
            try {
                const svc = window.familyService;
                if (svc) {
                    const members = await svc.listMembers({ forceRefresh: false });
                    window.DashieNative.onFamilyMembersLoaded(JSON.stringify(members));
                }
            } catch (e) { console.warn('[CC] Family count load error:', e); }
            try {
                const cs = window.calendarService;
                if (cs && typeof cs.enumerateAccountsWithCalendars === 'function') {
                    const result = await cs.enumerateAccountsWithCalendars();
                    // Push zero-account results too: a successful enumerate
                    // returning [] is an authoritative "no calendars", and
                    // suppressing it left the Kotlin cache permanently
                    // unseeded (the CC card could never distinguish loading
                    // from genuinely-none). Errors still skip the push.
                    const summary = result.map(a => a.accountType + '=' + a.calendars.length + 'cals').join(',');
                    console.log('[CC] pushing onCalendarDataLoaded: ' + result.length + ' accounts (' + summary + ')');
                    window.DashieNative.onCalendarDataLoaded(JSON.stringify(result));
                } else {
                    console.warn('[CC] calendarService.enumerateAccountsWithCalendars unavailable — skipping count update');
                }
            } catch (e) { console.warn('[CC] Calendar count load error:', e?.message || e); }
        })()
    """.trimIndent()
}
