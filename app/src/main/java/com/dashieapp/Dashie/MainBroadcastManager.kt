package com.dashieapp.Dashie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.devicecontrols.DarkModeManager
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaliteScreenController
import com.dashieapp.Dashie.halite.registry.wireRtspFaceFrameCallback
import com.dashieapp.Dashie.halite.registry.clearRtspFaceFrameCallback
import com.dashieapp.Dashie.halite.voice.HaliteVoiceController
import com.dashieapp.Dashie.sidebar.NativeSidebarController

/**
 * Manages broadcast receiver registration for settings-driven actions
 * (zoom, timer reset, overlay toggle, sign-in/out, camera config, etc.).
 * Extracted from MainActivity to reduce its size.
 */
class MainBroadcastManager(
    private val context: Context,
    private val halitePrefsProvider: () -> HalitePreferences?,
    private val registryProvider: () -> HaliteComponentRegistry?,
    private val screenControllerProvider: () -> HaliteScreenController?,
    private val voiceControllerProvider: () -> HaliteVoiceController?,
    private val kioskControllerProvider: () -> MainKioskController?,
    private val sidebarControllerProvider: () -> NativeSidebarController?,
    private val voiceSetupProvider: () -> MainVoiceSetup?,
    private val urlHandlerProvider: () -> MainUrlHandler?,
    private val permissionDelegateProvider: () -> MainPermissionDelegate,
    private val webViewProvider: () -> WebView,
    private val setDashieViewport: () -> Unit,
    private val setHaliteViewport: () -> Unit,
    private val controlCenterProvider: () -> com.dashieapp.Dashie.controlcenter.ControlCenterOverlay? = { null },
    private val updateControllerProvider: () -> com.dashieapp.Dashie.halite.update.DashieUpdateController? = { null },
    private val orientationControllerProvider: () -> com.dashieapp.Dashie.halite.orientation.OrientationController? = { null },
    private val portraitLayoutManagerProvider: () -> com.dashieapp.Dashie.halite.orientation.PortraitLayoutManager? = { null }
) {
    companion object {
        private const val TAG = "MainActivity"
        private const val PREFIX = "com.dashieapp.Dashie"
    }

    private var receiver: BroadcastReceiver? = null
    private var dumpReceiver: BroadcastReceiver? = null
    private var pendingPhotoSettingsChanged = false

    /**
     * The Dashie web root, or `null` when this edition does not have one.
     *
     * ## Why this exists rather than two inline guards
     *
     * `BuildConfig.DASHIE_URL` is **blank in the account-free (Chickadee) flavors**, and
     * `account.dashieUrl` defaults to it. Two broadcast handlers in this file consumed it with a
     * NULL check but no EMPTY check, which is the difference between failing and failing
     * *silently*:
     *
     *  - `ACTION_DASHIE_SIGN_IN` did `dashieUrl?.trimEnd('/') + "/login"`. With a blank root that
     *    is **`"/login"`** — a syntactically valid RELATIVE url that throws nothing and resolves
     *    against whatever origin happens to be loaded. 🔴 And with a null *provider* it is worse:
     *    Kotlin's `String? + String` yields the literal **`"null/login"`**.
     *  - `ACTION_SWITCH_TO_FULL` guarded `if (dashieUrl != null)`, but `""?.trimEnd('/')` is `""`,
     *    which is non-null — so it passed the guard and loaded the empty string.
     *
     * Both are exactly the "silent `""` dying inside a catch" shape the credential cut was ruled
     * to avoid, so per standing rule 2 the miss is announced and the handler stops rather than
     * proceeding with a nonsense URL.
     *
     * `[expected]`, not `[unexpected]`: on a Chickadee device these broadcasts have no business
     * arriving (their senders live in `src/dashie/`), so if one does, ignoring it is correct
     * behaviour and not a defect to chase.
     */
    private fun dashieWebRootOrDrop(action: String): String? {
        val root = halitePrefsProvider()?.account?.dashieUrl?.trimEnd('/')
        if (root.isNullOrEmpty()) {
            Log.w(TAG, "DROP: [expected] $action needs a Dashie web root and this edition has " +
                "none (DASHIE_URL is blank in the account-free edition). Ignoring the broadcast.")
            return null
        }
        return root
    }

    /** Call from MainActivity.onResume to dispatch queued photo settings changes */
    fun onResume() {
        if (pendingPhotoSettingsChanged) {
            pendingPhotoSettingsChanged = false
            Log.i(TAG, "📸 Dispatching queued photo settings change to WebView")
            try {
                webViewProvider().evaluateJavascript(
                    "console.log('[KOTLIN] Dispatching native-settings-changed photos event'); window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'photos' } }));",
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dispatch photo settings to WebView", e)
            }
        }
    }

    /** Tell the WebView to re-read the bridge for [category] and upload to
     *  user_devices immediately (closes the snapshot-only reinstall-loss gap —
     *  see 20260620_SETTINGS_SYNC_AUDIT.md). No-op if the WebView isn't ready. */
    private fun dispatchNativeSettingsChanged(category: String) {
        try {
            webViewProvider().evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: '$category' } }));",
                null
            )
        } catch (e: Exception) {
            Log.d(TAG, "WebView not ready for $category settings dispatch")
        }
    }

    fun register() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    "$PREFIX.ACTION_DEVICE_NAME_CHANGED" -> {
                        // #8/M5 (build-plan 20260722_DEVICE_NAME_CONVERGENCE_PLAN.md): a native
                        // "Device Name" rename → write the cloud SSOT (user_devices.device_name)
                        // via the JS updateDeviceName path, so the native rename becomes a
                        // Console-equivalent rename and the existing Console reconciliation
                        // (devices-rename.js) carries it to HA. window.updateDeviceName is defined
                        // in device-registration.js (guarded — no-op if it hasn't loaded yet).
                        val newName = intent.getStringExtra("name")?.trim().orEmpty()
                        if (newName.isNotEmpty()) {
                            Log.i(TAG, "📛 Device renamed natively → pushing to cloud SSOT: '$newName'")
                            val nameJs = org.json.JSONObject.quote(newName)
                            try {
                                webViewProvider().evaluateJavascript(
                                    "if (window.updateDeviceName) window.updateDeviceName($nameJs);",
                                    null
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to push device name to WebView", e)
                            }
                        }
                    }
                    "$PREFIX.ACTION_APPLY_ZOOM" -> {
                        val zoom = halitePrefsProvider()?.display?.dashboardZoom ?: HalitePreferences.DEFAULT_DASHBOARD_ZOOM
                        Log.i(TAG, "🔍 Received HA zoom broadcast: applying $zoom%")
                        kioskControllerProvider()?.applyDashboardZoom(zoom)
                        // Notify JS so handleDisplayChanged re-reads the
                        // native zoom and propagates through settingsStore +
                        // user_devices.display (per SETTINGS_KEY_MAP). Without
                        // this, native zoom changes never reach Supabase or
                        // the JS Settings UI's getter.
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'display' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for zoom settings dispatch")
                        }
                    }
                    "$PREFIX.ACTION_APPLY_WIDGET_ZOOM" -> {
                        // SYNC-EXEMPT: the widget_zoom pref write already auto-syncs via
                        // SettingsSyncNotifier ('display' dispatch → handleDisplayChanged
                        // readback → saveDeviceSettings). This branch only applies the zoom
                        // to the running page (localStorage mirror + iframe re-apply).
                        val zoom = halitePrefsProvider()?.display?.widgetZoom
                            ?: com.dashieapp.Dashie.halite.preferences.DisplayPreferences.DEFAULT_WIDGET_ZOOM
                        Log.i(TAG, "🔍 Received widget zoom broadcast: applying $zoom%")
                        // Mirror to localStorage so iframes that load fresh
                        // pick up the new value, and broadcast a zoom-changed
                        // message so already-mounted iframes re-apply.
                        try {
                            webViewProvider().evaluateJavascript(
                                """
                                (function() {
                                    try { localStorage.setItem('dashie-widget-zoom', '$zoom'); } catch (e) {}
                                    document.querySelectorAll('iframe').forEach(function(f) {
                                        try {
                                            f.contentWindow && f.contentWindow.postMessage(
                                                { type: 'zoom-changed', target: 'widget', value: $zoom },
                                                '*'
                                            );
                                        } catch (e) {}
                                    });
                                })();
                                """.trimIndent(),
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for widget zoom dispatch")
                        }
                    }
                    "$PREFIX.ACTION_APPLY_WIDGET_FONT_SIZE" -> {
                        val pct = halitePrefsProvider()?.display?.widgetFontSize
                            ?: com.dashieapp.Dashie.halite.preferences.DisplayPreferences.DEFAULT_WIDGET_FONT_SIZE
                        Log.i(TAG, "🔠 Received font size broadcast: applying $pct%")
                        // Route through saveDeviceSettings → settingsStore
                        // ('interface.widgetFontSize') which fires SETTINGS_CHANGED;
                        // WidgetMessenger then broadcasts the new font size to all
                        // widgets (they apply --widget-font-scale). Also persists to
                        // Supabase. Mirrors the theme/dark-mode display broadcasts.
                        try {
                            webViewProvider().evaluateJavascript(
                                """
                                (function() {
                                    if (!window.settingsStore) return;
                                    if (window.saveDeviceSettings) {
                                        window.saveDeviceSettings('display', { widgetFontSize: $pct });
                                    } else {
                                        window.settingsStore.set('interface.widgetFontSize', $pct);
                                    }
                                })();
                                """.trimIndent(),
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for font size dispatch")
                        }
                    }
                    "$PREFIX.ACTION_RESET_TIMER" -> {
                        screenControllerProvider()?.screenDimmer?.resetTimer()
                        webViewProvider().evaluateJavascript(
                            "document.dispatchEvent(new Event('touchstart'));",
                            null
                        )
                    }
                    "$PREFIX.ACTION_TOGGLE_PERFORMANCE_OVERLAY" -> {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        Log.i(TAG, "📊 Received performance overlay broadcast: enabled=$enabled")
                        if (enabled) {
                            registryProvider()?.performanceOverlayManager?.show()
                        } else {
                            registryProvider()?.performanceOverlayManager?.hide()
                        }
                    }
                    "$PREFIX.ACTION_OPEN_DIAGNOSTICS" -> {
                        Log.i(TAG, "📋 Received open diagnostics broadcast")
                        registryProvider()?.dialogHost?.showDiagnosticsDialogFromBridge()
                    }
                    "$PREFIX.ACTION_REFRESH_THRESHOLDS" -> {
                        Log.i(TAG, "📊 Received refresh thresholds broadcast")
                        registryProvider()?.performanceOverlayManager?.refreshThresholds()
                    }
                    "$PREFIX.ACTION_WIFI_LOCK_CHANGED" -> {
                        val enabled = intent.getBooleanExtra("enabled", true)
                        Log.i(TAG, "📶 Received WiFi lock broadcast: enabled=$enabled")
                        registryProvider()?.wifiLockManager?.applyWifiLockPreference(enabled)
                    }
                    "$PREFIX.ACTION_SYNC_SAMPLE_COLLECTION" -> {
                        val enabled = halitePrefsProvider()?.voice?.sampleCollectionEnabled ?: false
                        Log.i(TAG, "🎤 Syncing wake word sample collection: $enabled")
                        voiceControllerProvider()?.setSampleCollectionEnabled(enabled)
                        voiceSetupProvider()?.getVoiceAssistant()?.setLiveSampleCollectionEnabled(enabled)
                    }
                    "$PREFIX.ACTION_TOGGLE_DASH_MENU" -> {
                        val enabled = halitePrefsProvider()?.display?.dashMenuEnabled ?: true
                        Log.i(TAG, "🔄 Toggling dash menu sidebar: $enabled")
                        webViewProvider().evaluateJavascript(
                            // WEBAPP-EXEMPT: dashieToggleSidebar — kiosk-overlay JS sidebar debug toggle; full mode uses the native sidebar
                            "if(window.dashieToggleSidebar) dashieToggleSidebar($enabled);",
                            null
                        )
                    }
                    "$PREFIX.ACTION_DASHIE_SIGN_IN" -> {
                        val tStart = System.currentTimeMillis()
                        val mode = intent?.getStringExtra("mode") ?: ""
                        // flow="voice" — the "just add voice, stay in kiosk" fork
                        // (CloudActivationDialog). The web /login reads it to create an
                        // ha_only account (no trial) and return to the kiosk shell after
                        // auth instead of building the dashboard.
                        val flow = intent?.getStringExtra("flow") ?: ""
                        val baseLoginUrl =
                            (dashieWebRootOrDrop("ACTION_DASHIE_SIGN_IN") ?: return) + "/login"
                        // Pass the device's resolved dark/light to the login page so it
                        // doesn't flash the wrong theme. The login page shares the
                        // dashboard origin, but on a fresh install (empty localStorage)
                        // or after a kiosk-origin (virtual-URL) session it has no/stale
                        // 'dashie-theme' — the ?theme param overrides it.
                        //
                        // First launch (storedPref == null) maps to light, matching the
                        // "first-launch always LIGHT" intent in MainDarkModeHandler.
                        // Previously this omitted ?theme on first launch and relied on
                        // login/index.html's empty→light fallback — but if dev origin's
                        // localStorage carried a stale '*-dark' value (e.g. from a
                        // previous logged-in session), the head-script honoured that and
                        // the sign-in screen rendered dark while the surrounding
                        // onboarding stayed light. Always passing the explicit URL param
                        // closes that hole. login/index.html prioritizes ?theme over
                        // localStorage in both its head-script and body-script.
                        val themeParam = when (DarkModeManager.getStoredPreference(context)) {
                            true -> "dark"
                            else -> "light"  // false or null (first launch) → light
                        }
                        val loginParams = buildList {
                            if (mode.isNotEmpty()) add("mode=$mode")
                            if (flow.isNotEmpty()) add("flow=$flow")
                            add("theme=$themeParam")
                        }
                        val loginUrl = if (loginParams.isNotEmpty())
                            "$baseLoginUrl?${loginParams.joinToString("&")}" else baseLoginUrl
                        Log.i(TAG, "🔐 [t=0ms] Sign in broadcast received — mode=$mode theme=$themeParam loginUrl=$loginUrl")
                        // Dismiss any Kotlin overlays so they don't block d-pad input on login page
                        registryProvider()?.dismissAllOverlays()
                        Log.i(TAG, "🔐 [t=${System.currentTimeMillis() - tStart}ms] overlays dismissed")
                        setDashieViewport()
                        Log.i(TAG, "🔐 [t=${System.currentTimeMillis() - tStart}ms] viewport set, calling loadUrl")
                        urlHandlerProvider()?.loadUrlWithWsProxyIfNeeded(loginUrl)
                        Log.i(TAG, "🔐 [t=${System.currentTimeMillis() - tStart}ms] loadUrl returned (navigation may still be in progress)")
                    }
                    "$PREFIX.ACTION_REFRESH_SIDEBAR_PIN" -> {
                        // Triggered by setHaEnabled(false) when the user
                        // commits to the non-HA path. Re-read pin pref and
                        // re-apply visibility so the live sidebar matches
                        // the new state without waiting for an app restart.
                        val sidebar = sidebarControllerProvider()
                        val pinned = halitePrefsProvider()?.display?.dashMenuPinned ?: false
                        Log.i(TAG, "🏠 Refresh sidebar pin → pinned=$pinned")
                        sidebar?.refreshPinFromPrefs(pinned)
                    }
                    "$PREFIX.ACTION_DASHIE_TRIAL_EXPIRED_SUBSCRIBE" -> {
                        // Trial-expired overlay's Subscribe Now button →
                        // open CC's QR purchase dialog. CC may not yet be
                        // visible; the dialog renders standalone at the
                        // activity window level so that's fine.
                        Log.i(TAG, "💳 Trial-expired Subscribe tapped — opening purchase flow")
                        controlCenterProvider()?.openPurchaseSubscriptionFlow()
                    }
                    "$PREFIX.ACTION_DASHIE_DELETE_ACCOUNT" -> {
                        // The Kotlin Account page already showed its own AlertDialog
                        // confirmation. Forward skipConfirmation=true so the JS
                        // flow doesn't re-prompt the user.
                        val skipConfirmation = intent?.getBooleanExtra("skipConfirmation", false) ?: false
                        Log.i(TAG, "🗑️ Delete account intent received (skipConfirmation=$skipConfirmation)")
                        webViewProvider().post {
                            // BUNDLE-EXEMPT: dashieDeleteAccount — KNOWN GAP on kiosk-real-login (webapp-only
                            // flow); guarded loudly below: the user is TOLD the deletion didn't happen.
                            webViewProvider().evaluateJavascript("""
                                (function() {
                                    if (window.dashieDeleteAccount) {
                                        window.dashieDeleteAccount({ skipConfirmation: $skipConfirmation });
                                        return 'dispatched';
                                    }
                                    return 'missing';
                                })()
                            """.trimIndent()) { result ->
                                if (result?.contains("dispatched") != true) {
                                    // The Account page already got a CONFIRMED deletion from the user —
                                    // silently doing nothing is the worst outcome. Be loud, on-screen.
                                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.error(TAG,
                                        "DELETE ACCOUNT DROPPED — window.dashieDeleteAccount missing " +
                                        "(kiosk/HA-mode WebView has no webapp JS); deletion NOT performed")
                                    android.widget.Toast.makeText(context,
                                        "Account deletion isn't available on this device — please use the Dashie web app",
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                    "$PREFIX.ACTION_DASHIE_ACCOUNT_DELETED_COMPLETE" -> {
                        // JS finished the delete-account flow (server delete +
                        // localStorage wipe + clearAccountDataLocal). Tear
                        // down all native UI, navigate to the kiosk shell
                        // FIRST so the JS dashboard stops re-registering
                        // widgets, then show a confirmation AlertDialog on
                        // top of the (now-loading) shell.
                        Log.i(TAG, "🗑️ Account deletion complete — cleaning up native UI")
                        registryProvider()?.dismissAllOverlays()
                        // Hide the Control Center overlay if the user opened
                        // it before navigating into Settings → Delete. CC
                        // lives privately in MainActivity, so we reach it
                        // through the controlCenterProvider lambda.
                        controlCenterProvider()?.hide()
                        // Tear down the native layout widgets (weather, photos)
                        // — dismissAllOverlays only covers transient overlays,
                        // not the persistent layout-canvas widgets that JS
                        // registered against the dashboard slots.
                        registryProvider()?.weatherDailyWidgetController?.removeWidget()
                        registryProvider()?.weatherHourlyWidgetController?.removeWidget()
                        registryProvider()?.photoWidgetController?.removeWidget()
                        // Un-pin sidebar so applyWebViewLeftMarginForKioskMode
                        // returns 0 — without this, hide(false) leaves a
                        // 124px gutter where the sidebar was, looking like
                        // it's still visible. Then fully hide.
                        halitePrefsProvider()?.display?.dashMenuPinned = false
                        sidebarControllerProvider()?.hide(false)
                        setHaliteViewport()

                        // Mirror ACTION_DASHIE_SIGN_OUT: clear account, and on a
                        // device with no real HA clear isSetupComplete (which
                        // clearAccountDataLocal preserves) so the shell shows the
                        // "Welcome to Dashie" sign-in card, not an unconfigured kiosk.
                        halitePrefsProvider()?.account?.clear()
                        halitePrefsProvider()?.connection?.let { conn ->
                            if (conn.getHaOrigin() == null) conn.isSetupComplete = false
                        }

                        // Load the kiosk shell IMMEDIATELY — without this the
                        // JS dashboard keeps calling setWidgetBounds and the
                        // layout system re-registers the widgets we just
                        // removed (race observed in logcat: 80ms after
                        // removeWidget, JS re-pushed bounds and the widgets
                        // came back). Loading shell URL kicks JS into the
                        // kiosk page where there are no Dashie slots.
                        val shellUrl = urlHandlerProvider()?.determineInitialUrl()
                        if (shellUrl != null) {
                            urlHandlerProvider()?.loadUrlWithWsProxyIfNeeded(shellUrl)
                        }

                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            activity.runOnUiThread {
                                showAccountDeletedDialog(activity)
                            }
                        } else {
                            Log.w(TAG, "🗑️ No Activity context — skipping dialog")
                        }
                    }
                    "$PREFIX.ACTION_DASHIE_SIGN_OUT" -> {
                        Log.i(TAG, "🔐 Sign out — wiping all per-account state and reloading kiosk shell")
                        // D.63 — clear isLinked FIRST so determineInitialUrl() returns
                        // the kiosk shell URL (welcome wizard for non-HA devices,
                        // HA iframe for HA-configured devices) instead of the
                        // dashieUrl/dashboard. Previously we computed the target
                        // URL before clearing, AND hardcoded "$dashieUrl/login"
                        // which dumped the user on Dashie's login page instead
                        // of the "Welcome to Dashie" kiosk-overlay welcome card.
                        halitePrefsProvider()?.account?.clear()
                        // If this device never actually configured HA (getHaOrigin()
                        // is null — i.e. the URL is still the http://192.168.1.x:8123
                        // placeholder), clear isSetupComplete on sign-out. Otherwise
                        // the post-logout kiosk shell sees isSetupComplete=true, skips
                        // the welcome card, and Kotlin loads the HA iframe at the
                        // placeholder URL ("tried to log me into HA at 192.168.1.x").
                        // A Dashie-cloud-only user should land on the welcome card to
                        // sign back in. Real-HA users (getHaOrigin() != null) keep
                        // isSetupComplete and return to their HA dashboard.
                        halitePrefsProvider()?.connection?.let { conn ->
                            if (conn.getHaOrigin() == null) {
                                Log.i(TAG, "🔐 Sign-out: no real HA configured — clearing isSetupComplete so shell shows welcome card")
                                conn.isSetupComplete = false
                            }
                        }
                        // D.59 — also clear the Kotlin-side cached Supabase JWT.
                        // Previously this lingered in ConnectionPreferences after
                        // sign-out; the next app start would have a valid-looking
                        // JWT cached in Kotlin, and background services (photo
                        // sources, trial overlay) could continue using the
                        // signed-out account's credentials until the cache expired.
                        halitePrefsProvider()?.connection?.clearSupabaseJwt()
                        com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(context).clear()
                        // Credits + cloud voice pipeline belonged to the signed-out account
                        // — wipe the cached balance and revert providers off cloud so the
                        // tablet stops showing credits / running cloud voice (backlog #1:
                        // "sign-out leaves Cloud configured"). Reinit picks it up immediately.
                        halitePrefsProvider()?.let { p ->
                            com.dashieapp.Dashie.halite.voice.CreditStateHolder.reset()
                            com.dashieapp.Dashie.halite.settings.schema.wiring.VoicePresetSeeder.revertOffCloud(p.voice)
                        }
                        context.sendBroadcast(
                            android.content.Intent("com.dashieapp.Dashie.ACTION_VOICE_PIPELINE_REINIT")
                                .apply { setPackage(context.packageName) }
                        )
                        // Clear control center cached counts so the next user
                        // doesn't see the previous account's family member count
                        // or calendar account count until JS pushes fresh values.
                        context.getSharedPreferences("cc_counts", android.content.Context.MODE_PRIVATE)
                            .edit().clear().apply()
                        // Reset in-memory CC state provider mirrors.
                        controlCenterProvider()?.resetForSignOut()
                        // Suppress the post-resume permission validator on the
                        // next onResume — the user is mid-transition to the
                        // kiosk shell, not actively using a feature, and the
                        // permissions for HA features should have already been
                        // granted at HA-enable / onboarding time.
                        context.getSharedPreferences("dashie_signout_state", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("skip_next_perm_validate", true).apply()

                        // NOW compute the target URL — account.clear() ran above,
                        // so isLinked=false and determineInitialUrl() picks the
                        // kiosk shell URL (which the kiosk-overlay then routes to
                        // the welcome card via the !isSetupComplete branch, or to
                        // the HA iframe if the device already completed HA setup).
                        val signOutTargetUrl = urlHandlerProvider()?.determineInitialUrl()
                        Log.i(TAG, "🔐 Sign-out target URL after clear: $signOutTargetUrl")

                        // D.59 — chain the cleanup callbacks so the target URL can NEVER load
                        // with stale auth still in place. Previous flow fired cookies,
                        // localStorage, and navigation in parallel; cookies are async
                        // with a fire-and-forget callback, so /login could open before
                        // the cookie store finished clearing → Supabase JS found a
                        // session in cookies + ran onDashieAuthComplete → isLinked was
                        // re-set to true → next app open auto-loaded the dashboard
                        // ("didn't sign out"). The chain is now:
                        //   1) JS storage clear (localStorage, sessionStorage, IDB)
                        //   2) WebStorage + WebViewDatabase clear (form data, HTTP auth)
                        //   3) Cookies clear (wait for the async callback)
                        //   4) Cookies flush to disk
                        //   5) Navigate to /login
                        webViewProvider().post {
                            webViewProvider().evaluateJavascript("""
                                (function() {
                                    try { localStorage.clear(); } catch (e) { console.warn('[SignOut] localStorage.clear failed', e); }
                                    try { sessionStorage.clear(); } catch (e) {}
                                    try {
                                        if (indexedDB.databases) {
                                            indexedDB.databases().then(function(dbs) {
                                                dbs.forEach(function(db) {
                                                    if (db.name) { try { indexedDB.deleteDatabase(db.name); } catch (_) {} }
                                                });
                                            });
                                        } else {
                                            ['DashieDataCache', 'DashieCache', 'dashie-cache'].forEach(function(n) {
                                                try { indexedDB.deleteDatabase(n); } catch (_) {}
                                            });
                                        }
                                    } catch (e) { console.warn('[SignOut] IndexedDB clear failed', e); }
                                    console.log('[SignOut] Cleared localStorage + sessionStorage');
                                    return 'cleared';
                                })()
                            """.trimIndent()) {
                                Log.i(TAG, "🔐 Sign-out: JS storage cleared — wiping WebStorage + cookies")
                                // Nuke the broader WebView session: WebStorage covers
                                // origin-quota'd storage (Service Worker registrations,
                                // any AppCache entries); WebViewDatabase covers form
                                // autofill and HTTP basic-auth credentials.
                                //
                                // EXCEPTION: when this device has Home Assistant
                                // configured, skip the all-origin WebStorage wipe. HA's
                                // frontend keeps its auth in the HA iframe's localStorage
                                // (hassTokens), a DIFFERENT origin than the Dashie/Supabase
                                // session. The main-page localStorage.clear() above + the
                                // cookie clear below already remove the Dashie/Google/
                                // Supabase session — that was the actual "didn't sign out"
                                // bug (D.59). Wiping ALL-origin storage additionally logged
                                // the device out of HA, which is device-level config and
                                // should survive a Dashie account logout.
                                val haConfigured = halitePrefsProvider()?.connection?.hasHaAccessToken == true
                                if (haConfigured) {
                                    Log.i(TAG, "🔐 Sign-out: HA configured — preserving WebStorage so HA stays logged in")
                                } else {
                                    try {
                                        android.webkit.WebStorage.getInstance().deleteAllData()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "WebStorage clear failed", e)
                                    }
                                }
                                try {
                                    val db = android.webkit.WebViewDatabase.getInstance(context)
                                    @Suppress("DEPRECATION")
                                    db.clearFormData()
                                    @Suppress("DEPRECATION")
                                    db.clearHttpAuthUsernamePassword()
                                } catch (e: Exception) {
                                    Log.w(TAG, "WebViewDatabase clear failed", e)
                                }
                                // Cookies LAST + wait for the callback before
                                // navigating. removeAllCookies is async — naive
                                // fire-and-forget lets /login open before it
                                // completes. The callback fires on the UI thread
                                // when the cookie store is actually empty.
                                android.webkit.CookieManager.getInstance().removeAllCookies { success ->
                                    Log.i(TAG, "🔐 Cookies cleared: $success — flushing + navigating to $signOutTargetUrl")
                                    android.webkit.CookieManager.getInstance().flush()
                                    if (!signOutTargetUrl.isNullOrEmpty()) {
                                        urlHandlerProvider()?.loadUrlWithWsProxyIfNeeded(signOutTargetUrl)
                                    }
                                }
                            }
                        }
                        // Dismiss all native Kotlin overlays before navigating to the
                        // post-sign-out surface (mirrors the sign-in handler). The
                        // login + welcome-wizard surfaces are web-only — leaving
                        // these visible shows stale dashboard chrome on top.
                        registryProvider()?.dismissAllOverlays()
                        // dismissAllOverlays covers music player / video feeds /
                        // performance overlay; sidebar strip + widget-icon list
                        // + hamburger popout need an explicit clear/hide. Mirrors
                        // ACTION_SWITCH_TO_KIOSK below — sign-out also drops the
                        // app into kiosk-shell mode, so the widget-mode sidebar
                        // icons (calendar, locations, chores, rewards, etc.)
                        // shouldn't linger.
                        sidebarControllerProvider()?.setEnabledViews(emptyList())
                        sidebarControllerProvider()?.hide(false)
                        // Drop the themed strip color the full dashboard pushed —
                        // otherwise it persists into kiosk mode and the backdrop
                        // strip stays the last account's brand color instead of
                        // reverting to neutral black/white.
                        sidebarControllerProvider()?.resetThemeToNeutral()
                        Log.i(TAG, "🔐 Sign-out: overlays dismissed, sidebar hidden, theme reset to neutral")
                        setHaliteViewport()
                        // Navigation to signOutTargetUrl happens in the
                        // storage-clear callback above — only after the
                        // Supabase session token is cleared from localStorage.
                    }
                    "$PREFIX.ACTION_SWITCH_TO_KIOSK" -> {
                        Log.i(TAG, "🔄 Switching to kiosk mode (account stays linked)")
                        // Clear sidebar view buttons (kiosk doesn't have widget views)
                        val sidebar = sidebarControllerProvider()
                        Log.i(TAG, "📐 Clearing sidebar views, controller=${sidebar != null}")
                        sidebar?.setEnabledViews(emptyList())
                        setHaliteViewport()
                        val shellUrl = urlHandlerProvider()?.determineInitialUrl()
                        if (shellUrl != null) {
                            urlHandlerProvider()?.loadUrlWithWsProxyIfNeeded(shellUrl)
                        }
                    }
                    "$PREFIX.ACTION_SWITCH_TO_FULL" -> {
                        Log.i(TAG, "🔄 Switching to full mode")
                        val dashieUrl = dashieWebRootOrDrop("ACTION_SWITCH_TO_FULL")
                        if (dashieUrl != null) {
                            setDashieViewport()
                            urlHandlerProvider()?.loadUrlWithWsProxyIfNeeded(dashieUrl)
                        }
                        // D.52 — the kiosk → widgets transition may have just
                        // auto-pinned the sidebar via SettingsCallbackWiring.
                        // Refresh from prefs so the visible state updates
                        // without waiting for the next launch.
                        sidebarControllerProvider()?.refreshPinnedFromPrefs()
                    }
                    "$PREFIX.ACTION_DASHIE_CHECK_UPDATE" -> {
                        Log.i(TAG, "🔄 Manual update check requested from Settings")
                        // User-initiated check: suppress the auto-banner so the
                        // CC "Software Update" card is the only surface. The
                        // user is in Settings/CC already and doesn't need a
                        // disruptive top banner pop-up.
                        updateControllerProvider()?.checkForUpdate(suppressBanner = true)
                    }
                    "$PREFIX.ACTION_CAMERA_ENABLED_CHANGED" -> {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        Log.i(TAG, "📹 Received camera enabled broadcast: $enabled")
                        permissionDelegateProvider().handleRtspEnabledChange(enabled)
                        // Upload the HA blob (camera section) to user_devices so
                        // the change survives reinstall, not just the snapshot.
                        dispatchNativeSettingsChanged("home_assistant_config")
                    }
                    "$PREFIX.ACTION_CAMERA_CONFIG_CHANGED" -> {
                        Log.i(TAG, "📹 Received camera config changed broadcast")
                        registryProvider()?.onRestartRtspServerIfNeeded?.invoke()
                        dispatchNativeSettingsChanged("home_assistant_config")
                    }
                    "$PREFIX.ACTION_HA_SENSOR_CONFIG_CHANGED" -> {
                        Log.i(TAG, "📡 Received HA sensor config changed broadcast")
                        registryProvider()?.jsBridge?.settingsDelegate?.onHaSensorConfigChanged?.invoke()
                        dispatchNativeSettingsChanged("home_assistant_config")
                    }
                    "$PREFIX.ACTION_SCREENSAVER_SETTINGS_CHANGED" -> {
                        val timeout = halitePrefsProvider()?.screensaver?.screensaverTimeout ?: 60
                        val mode = halitePrefsProvider()?.screensaver?.screensaverMode ?: "dim"
                        Log.i(TAG, "🖥️ Received screensaver settings changed broadcast - timeout=$timeout, mode=$mode")
                        screenControllerProvider()?.updateScreensaverSettings(timeout, mode)
                        // Persist to user_devices.screensaver now (was snapshot-only → lost on reinstall).
                        dispatchNativeSettingsChanged("screensaver")
                    }
                    "$PREFIX.ACTION_PHOTO_SOURCE_CHANGED",
                    "$PREFIX.ACTION_PHOTO_SETTINGS_CHANGED" -> {
                        Log.i(TAG, "📸 Received photo settings changed broadcast — queuing for WebView dispatch")
                        pendingPhotoSettingsChanged = true
                        // Also try immediate dispatch (works if MainActivity is visible)
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'photos' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for immediate dispatch, will dispatch on resume")
                        }
                        // Reset the screensaver's cached slideshow photos when
                        // the source changed so the widget's reconfigure path
                        // doesn't reuse them via PhotoSourceFactory's
                        // sharedPhotosProvider — without this the widget would
                        // silently keep showing photos from the previous source
                        // until the next app restart.
                        if (intent.action == "$PREFIX.ACTION_PHOTO_SOURCE_CHANGED") {
                            screenControllerProvider()?.screenDimmer?.resetPhotoSlideshowCacheForSourceChange()
                        }
                        // Notify native photo widget to reconfigure with new source
                        registryProvider()?.photoWidgetController?.reconfigure()
                    }
                    "$PREFIX.ACTION_WEATHER_CONFIG_CHANGED" -> {
                        Log.i(TAG, "🌤️ Received weather config changed broadcast — forcing re-fetch")
                        registryProvider()?.weatherDailyWidgetController?.refresh()
                        registryProvider()?.weatherHourlyWidgetController?.refresh()
                        // A useHa-for-weather toggle also rides this broadcast — sync
                        // the account-level prefs (weather.useHa) up to the cloud.
                        dispatchNativeSettingsChanged("account_prefs")
                    }
                    "$PREFIX.ACTION_LOCATION_CONFIG_CHANGED" -> {
                        Log.i(TAG, "📍 Received location config changed broadcast — syncing zip → family.zipCode + refreshing weather")
                        // Push the native zip (general.zipCode) up to the canonical
                        // account setting family.zipCode: the WebView listener reads
                        // DashieNative.getZipCode() and writes family.zipCode + saves,
                        // so a Preferences → Location edit reaches the cloud instead of
                        // reverting on reload. Also refresh native weather widgets.
                        dispatchNativeSettingsChanged("location")
                        registryProvider()?.weatherDailyWidgetController?.refresh()
                        registryProvider()?.weatherHourlyWidgetController?.refresh()
                    }
                    "$PREFIX.ACTION_TIME_CONFIG_CHANGED" -> {
                        Log.i(TAG, "🕒 Received time config changed broadcast — refreshing HA time provider")
                        screenControllerProvider()?.refreshHaTimeProvider()
                        // A useHa-for-time toggle also rides this broadcast — sync
                        // the account-level prefs (time.useHa) up to the cloud.
                        dispatchNativeSettingsChanged("account_prefs")
                    }
                    "$PREFIX.ACTION_ACCOUNT_PREFS_CHANGED" -> {
                        Log.i(TAG, "⚙️ Received account prefs changed broadcast — syncing language/useHa → user_settings")
                        dispatchNativeSettingsChanged("account_prefs")
                    }
                    "$PREFIX.ACTION_SLEEP_CONFIG_CHANGED" -> {
                        Log.i(TAG, "😴 Received sleep config changed broadcast")
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'sleep' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for sleep settings dispatch")
                        }
                    }
                    "$PREFIX.ACTION_LOCATIONS_SETTINGS_CHANGED" -> {
                        Log.i(TAG, "📍 Received locations settings changed broadcast")
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'locations' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for locations settings dispatch")
                        }
                    }
                    "$PREFIX.ACTION_CHORES_REWARDS_SETTINGS_CHANGED" -> {
                        Log.i(TAG, "✅ Received chores/rewards settings changed broadcast")
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'choresRewards' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for chores/rewards settings dispatch")
                        }
                    }
                    "$PREFIX.ACTION_CALENDAR_SETTINGS_CHANGED" -> {
                        Log.i(TAG, "📅 Received calendar settings changed broadcast")
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'calendar' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for calendar settings dispatch")
                        }
                    }
                    "$PREFIX.ACTION_DISPLAY_SETTINGS_CHANGED" -> {
                        Log.i(TAG, "🕒 Received display settings changed broadcast (24hr/dateFormat)")
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'display' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for display settings dispatch")
                        }
                        // Re-fetch weather so the hourly column time labels
                        // re-format with the new use24HourClock value. The
                        // weather provider bakes formatted strings into
                        // HourlyForecast.time at parse time and doesn't watch
                        // halitePrefs.use24HourClock — without a manual refresh
                        // here, the widget keeps the old format until the next
                        // 15-min poll cycle.
                        registryProvider()?.weatherDailyWidgetController?.refresh()
                        registryProvider()?.weatherHourlyWidgetController?.refresh()
                    }
                    "$PREFIX.ACTION_HA_ENABLED_CHANGED" -> {
                        Log.i(TAG, "🏠 Received HA enabled changed broadcast")
                        // 'home_assistant' → enable flag to account user_settings + layout reload.
                        dispatchNativeSettingsChanged("home_assistant")
                        // 'home_assistant_config' → full HA blob to user_devices (survives reinstall).
                        dispatchNativeSettingsChanged("home_assistant_config")
                        // D.80 — kick off the device-permission sweep
                        // immediately when HA gets enabled (camera/mic/device-
                        // admin/overlay/exact-alarm). Previously these only
                        // fired on the next app onResume, which felt like the
                        // toggle was a no-op. force=true so the user's earlier
                        // "skip for now" choice doesn't suppress the prompt
                        // when they explicitly opt INTO HA.
                        //
                        // Skip on Fire TV / Android TV — same reasoning as
                        // MainPermissionDelegate.requestAllOnboardingPermissions:
                        // no camera, mic is remote-only (requested on demand by
                        // voice setup), and device-admin/brightness steps no-op
                        // on TV. Bombarding a TV user with permission prompts
                        // when they enable HA isn't useful.
                        val isTvDevice = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
                            com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(context)
                        if (halitePrefsProvider()?.connection?.haEnabled == true && !isTvDevice) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                Log.i(TAG, "🔐 HA just enabled — kicking off API permission sweep")
                                permissionDelegateProvider().checkAndRequestApiPermissions(force = true)
                            }, 350)
                        } else if (isTvDevice) {
                            Log.i(TAG, "🔐 HA enabled on TV device — skipping permission sweep (D.80 / D.86)")
                        }
                    }
                    "$PREFIX.ACTION_HA_DISPLAY_CHANGED" -> {
                        // Audit 2026-07-04 §2B: this action was sent by the hide-sidebar/
                        // tabs/search/assistant + floating-back-button toggles but had NO
                        // receiver — the toggles neither applied live nor synced.
                        Log.i(TAG, "🏠 HA display config changed (hide*/back button)")
                        // Persist: hide* live in the Kotlin-owned HA blob — dispatch
                        // home_assistant_config so JS reads the blob back and saves it
                        // to user_devices (was: stale until next boot's snapshot upload).
                        dispatchNativeSettingsChanged("home_assistant_config")
                        // Live-apply: the hide flags are baked into the HA page at load
                        // time by the kiosk CSS interceptor, so a currently-visible HA
                        // page needs a reload to pick them up (a reload covers BOTH
                        // directions — injecting more CSS could only ever hide more).
                        // Only reload when the main WebView is actually on the HA host
                        // (kiosk / single-panel); in widgets mode the dashboard owns
                        // its HA iframe and re-reads settings via the dispatch above.
                        try {
                            val conn = halitePrefsProvider()?.connection
                            val current = webViewProvider().url
                            val haHost = listOf(conn?.haUrl, conn?.haBaseUrl)
                                .firstOrNull { !it.isNullOrEmpty() }
                                ?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
                            val currentHost = current?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
                            if (haHost != null && currentHost != null && haHost == currentHost) {
                                Log.i(TAG, "🏠 Reloading HA page so new hide flags apply")
                                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info(
                                    "REFRESH", "WebView reload: HA hide flags changed"
                                )
                                webViewProvider().reload()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "HA display live-apply failed: ${e.message}")
                        }
                    }
                    "$PREFIX.ACTION_API_CHANGED" -> {
                        // Audit 2026-07-04 §2B: sent by the Device API enable/password/
                        // port settings but had NO receiver — the NanoHTTPD server kept
                        // running with the old config until reboot.
                        Log.i(TAG, "🔌 Device API config changed — restarting API service")
                        registryProvider()?.dashieServiceManager?.restartService()
                        // apiEnabled/apiPort/discoveryEnabled ride the HA blob (password
                        // is excluded from it) — persist to user_devices now.
                        dispatchNativeSettingsChanged("home_assistant_config")
                    }
                    "$PREFIX.ACTION_HA_CONFIG_CHANGED" -> {
                        // Generic "HA blob changed, persist it" signal (audit §2B): used
                        // by the power-config schema, the HA URL editor, and the custom-
                        // URL / advanced-tablet-controls toggles — sub-domains whose
                        // writes previously never dispatched home_assistant_config, so
                        // the cloud blob (and the Console) lagged until next boot.
                        Log.i(TAG, "🏠 HA config changed — persisting blob")
                        dispatchNativeSettingsChanged("home_assistant_config")
                    }
                    "$PREFIX.ACTION_VIDEO_FEED_CONFIG_CHANGED" -> {
                        // MediaComponentWiring's own receiver refreshes the native video
                        // feed manager; this branch adds the missing sync half (audit
                        // §2B: videoFeed.* never dispatched home_assistant_config).
                        dispatchNativeSettingsChanged("home_assistant_config")
                    }
                    "$PREFIX.ACTION_SIDEBAR_ICON_SIZE" -> {
                        Log.i(TAG, "📐 Received sidebar icon size broadcast")
                        sidebarControllerProvider()?.applyIconSize()
                    }
                    "$PREFIX.ACTION_APPLY_DISPLAY_SIZE" -> {
                        val pct = halitePrefsProvider()?.display?.displaySize
                            ?: com.dashieapp.Dashie.halite.preferences.DisplayPreferences.DEFAULT_DISPLAY_SIZE
                        Log.i(TAG, "📐 Received display size broadcast: applying $pct%")
                        // Rebuild the sidebar (its effectiveMultiplier now includes
                        // Display Size). Music/video/timer/voice overlays read
                        // DisplaySizeScale on their next render/show.
                        sidebarControllerProvider()?.applyIconSize()
                        // Route through saveDeviceSettings → settingsStore
                        // ('interface.displaySize') so the value persists to
                        // Supabase (user_devices.display.displaySize) and is read by
                        // the Console + getCurrentDeviceSettings upload. Mirrors the
                        // font-size handler above.
                        try {
                            webViewProvider().evaluateJavascript(
                                """
                                (function() {
                                    if (!window.settingsStore) return;
                                    if (window.saveDeviceSettings) {
                                        window.saveDeviceSettings('display', { displaySize: $pct });
                                    } else {
                                        window.settingsStore.set('interface.displaySize', $pct);
                                    }
                                })();
                                """.trimIndent(),
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for display size dispatch")
                        }
                    }
                    "$PREFIX.ACTION_THEME_CHANGED" -> {
                        val family = intent.getStringExtra("themeFamily") ?: "default"
                        Log.i(TAG, "🎨 Theme family changed: $family")
                        // Persist via saveDeviceSettings (user_devices.display) so the
                        // choice survives reload. applyDeviceSettings reads darkMode +
                        // themeFamily on next launch; without that persistence the
                        // family reverts to 'default' on every reload.
                        // Resolve mode from the hydrated individual field first, then
                        // the localStorage mirror, then default. Avoids the same
                        // stale-state-combine bug that hit applyThemeFromNative.
                        webViewProvider().evaluateJavascript(
                            // BUNDLE-EXEMPT: themeApplier — debug theme broadcast into the webapp
                            """(function(){
                                if(!window.settingsStore) return;
                                var mode = window.settingsStore.get('interface.themeMode');
                                if (!mode) { try { mode = localStorage.getItem('dashie-theme-mode'); } catch (_) {} }
                                if (!mode) mode = 'dark';
                                var themeId = '$family' === 'default' ? mode : ('$family' + '-' + mode);
                                if (window.saveDeviceSettings) {
                                    window.saveDeviceSettings('display', {
                                        darkMode: mode === 'dark',
                                        themeFamily: '$family',
                                        themeMode: mode,
                                        theme: themeId
                                    });
                                } else {
                                    // Fallback: pre-Phase-4 JS without the writer (shouldn't occur post-deploy)
                                    window.settingsStore.set('interface.themeFamily', '$family');
                                    window.settingsStore.set('interface.theme', themeId);
                                }
                                if(window.themeApplier) window.themeApplier.applyTheme(themeId, true);
                            })()""".trimIndent(),
                            null
                        )
                    }
                    "$PREFIX.ACTION_DARK_MODE_CHANGED" -> {
                        val isDark = intent.getBooleanExtra("darkMode", true)
                        val mode = if (isDark) "dark" else "light"
                        Log.i(TAG, "🌓 Dark mode changed: $mode")
                        // Persist via saveDeviceSettings — same reasoning as ACTION_THEME_CHANGED.
                        // Family resolved with same fallback chain as applyThemeFromNative.
                        webViewProvider().evaluateJavascript(
                            """(function(){
                                if(!window.settingsStore) return;
                                var family = window.settingsStore.get('interface.themeFamily');
                                if (!family) { try { family = localStorage.getItem('dashie-theme-family'); } catch (_) {} }
                                if (!family) family = 'default';
                                var themeId = family === 'default' ? '$mode' : (family + '-' + '$mode');
                                if (window.saveDeviceSettings) {
                                    window.saveDeviceSettings('display', {
                                        darkMode: '$mode' === 'dark',
                                        themeFamily: family,
                                        themeMode: '$mode',
                                        theme: themeId
                                    });
                                } else {
                                    window.settingsStore.set('interface.themeMode', '$mode');
                                    window.settingsStore.set('interface.theme', themeId);
                                }
                                if(window.themeApplier) window.themeApplier.applyTheme(themeId, true);
                            })()""".trimIndent(),
                            null
                        )
                        // Update native Kotlin widgets with new theme
                        registryProvider()?.photoWidgetController?.applyTheme(isDark)
                        registryProvider()?.weatherDailyWidgetController?.applyTheme(isDark)
                        registryProvider()?.weatherHourlyWidgetController?.applyTheme(isDark)
                    }
                    "$PREFIX.ACTION_ANIMATIONS_ENABLED_CHANGED" -> {
                        val enabled = intent.getBooleanExtra("animationsEnabled", false)
                        Log.i(TAG, "✨ Animations enabled changed: $enabled")
                        // NOTE: write as 'animationsEnabled' (not 'themeAnimationsEnabled')
                        // because applyDeviceSettings on startup reads display.animationsEnabled.
                        // Both keys alias to the same settingsStore path.
                        webViewProvider().evaluateJavascript(
                            // BUNDLE-EXEMPT: themeOverlay — debug theme broadcast into the webapp
                            """(function(){
                                if (window.saveDeviceSettings) {
                                    window.saveDeviceSettings('display', { animationsEnabled: $enabled });
                                }
                                if (window.themeOverlay && window.themeApplier) {
                                    window.themeOverlay.setEnabled($enabled);
                                    if ($enabled) {
                                        var currentTheme = window.themeApplier.getCurrentTheme();
                                        window.themeOverlay.applyOverlay(currentTheme);
                                    }
                                }
                            })()""".trimIndent(),
                            null
                        )
                    }
                    "$PREFIX.ACTION_ANIMATION_LEVEL_CHANGED" -> {
                        val level = intent.getStringExtra("animationLevel") ?: "high"
                        Log.i(TAG, "✨ Animation level changed: $level")
                        webViewProvider().evaluateJavascript(
                            """(function(){
                                if (window.saveDeviceSettings) {
                                    window.saveDeviceSettings('display', { animationLevel: '$level' });
                                }
                                if (window.themeOverlay && window.themeApplier) {
                                    var currentTheme = window.themeApplier.getCurrentTheme();
                                    window.themeOverlay.applyOverlay(currentTheme);
                                }
                            })()""".trimIndent(),
                            null
                        )
                    }
                    "$PREFIX.ACTION_VOICE_SETTINGS_CHANGED" -> {
                        // Slice 2: device keys → user_devices.voice; pipeline keys
                        // (controlMethod/customizePipeline/sttProvider/ttsProvider) →
                        // user_settings.voice.* via settingsStore (FamilySettings pattern;
                        // not in SETTINGS_KEY_MAP.voice so they aren't blocklist-stripped).
                        // Account pipeline keys go via a TARGETED write
                        // (saveAccountSettings), NOT bulk settingsStore.save() — a bulk
                        // save re-uploads stale account keys and reverts other devices
                        // (verified on-device). See build-plan fix #1/#2.
                        val voicePrefs = com.dashieapp.Dashie.halite.preferences.VoicePreferences(context)
                        val devicePayload = org.json.JSONObject().apply {
                            put("enabled", voicePrefs.voiceEnabled)
                            put("responseHandling", voicePrefs.responseHandling)
                            put("displayFormat", voicePrefs.displayFormat)
                            put("confirmationToneType", voicePrefs.confirmationToneType)
                            put("confirmationToneVolume", voicePrefs.confirmationToneVolume.toDouble())
                        }.toString()
                        // Device voice keys only. The account pipeline keys (controlMethod/
                        // customize/stt/tts) are NOT uploaded here anymore — a pipeline change also
                        // fires ACTION_AI_VOICE_SETTINGS_CHANGED (the sole account uploader, with the
                        // user-changed key threaded), and firing the account round-trip here too caused
                        // a double upload + a latent sibling clobber on non-pipeline changes (tone /
                        // responseHandling also fire this broadcast). controlMethod is console-derived.
                        webViewProvider().evaluateJavascript(
                            """(function(){
                                try { if (window.saveDeviceSettings) window.saveDeviceSettings('voice', $devicePayload); } catch(e) { console.error('voice device save failed', e); }
                            })()""".trimIndent(),
                            null
                        )
                    }
                    "$PREFIX.ACTION_AI_VOICE_SETTINGS_CHANGED" -> {
                        // Same device/account split as ACTION_VOICE_SETTINGS_CHANGED:
                        // personalityId/voiceKey → user_devices.aiVoice (personality is
                        // per-room by design); model → user_settings.ai.model (account,
                        // not in SETTINGS_KEY_MAP.aiVoice so it isn't blocklist-stripped).
                        Log.i(TAG, "🗣️ AI voice settings changed — model→account, personality/voice→device")
                        val voicePrefs = com.dashieapp.Dashie.halite.preferences.VoicePreferences(context)
                        val aiPrefs = com.dashieapp.Dashie.halite.preferences.AiPreferences(context)
                        val devicePayload = org.json.JSONObject().apply {
                            put("personalityId", aiPrefs.personalityId)
                            put("voiceKey", voicePrefs.voiceKey)
                        }.toString()
                        // model + retrievePicturesEnabled + promptForFeedback + webSearchEnabled are account-scoped (ACCOUNT_AI_KEYS).
                        val acctPayload = org.json.JSONObject()
                            .put("model", aiPrefs.aiModel)
                            .put("retrievePicturesEnabled", aiPrefs.retrievePicturesEnabled)
                            .put("promptForFeedback", aiPrefs.promptForFeedback)
                            .put("webSearchEnabled", aiPrefs.webSearchEnabled)
                            .toString()
                        // DLG-6: voice.alwaysOpenDialog is account-scoped (ACCOUNT_VOICE_KEYS) — round-trip
                        // it to user_settings.voice on the same broadcast (mirrors the ai.* keys above).
                        // pipelinePreset/sttProvider/ttsProvider/customizePipeline are also account-scoped
                        // pipeline keys (ACCOUNT_VOICE_KEYS); firing them here closes the native→cloud upload
                        // gap so a tablet-side pipeline change reaches the console (fixed 2026-07-20). The JS
                        // saveAccountSettings echo-guard drops any of these that JS itself just pushed down
                        // (console→device), so a redundant download→upload can't loop.
                        val voiceAcctJson = org.json.JSONObject()
                            .put("alwaysOpenDialog", voicePrefs.alwaysOpenDialog)
                            .put("alwaysUseAI", aiPrefs.alwaysUseAI)   // account key; native pref lives on AiPreferences
                            .put("sttProvider", voicePrefs.sttProvider)
                            .put("ttsProvider", voicePrefs.ttsProvider)
                            .put("customizePipeline", voicePrefs.customizePipeline)
                            // Conversation-mode account keys (audit GAP 3): native writers exist
                            // (dialog toggle / Live-model picker) but these were absent from the
                            // upload payload → never persisted, reverted on reboot. conversationModel
                            // '' is the meaningful "off" value, so send it too; the dirty-diff skips
                            // it when unchanged.
                            .put("agentMode", voicePrefs.agentMode)
                            .put("conversationModel", voicePrefs.conversationModel)
                            .put("conversationAlways", voicePrefs.conversationAlways)
                            // GAP-2: Live (S2S) voice — account-scoped (user_settings.voice.liveVoiceName).
                            // Native picker → cloud upload. '' = engine default (Aoede); still send it.
                            .put("liveVoiceName", voicePrefs.conversationVoice)
                            // GAP-2 Piece A: engine voices (Piper/Kokoro) — account-scoped, but were only
                            // pushed console→native (SYNC_EXEMPT). Carry them so the native voice PICKER's
                            // selection propagates account-wide (the console echo-guards its own pushes).
                            .put("haTtsVoiceId", voicePrefs.haTtsVoiceId)
                            .put("localTtsVoiceId", voicePrefs.localTtsVoiceId)
                        // pipelinePreset is the CANONICAL preset selector the console reads to render the
                        // Cloud/Hybrid/Local/HA card (controlMethod is derived from it console-side, so we
                        // don't upload that). '' means "derive from controlMethod" — skip it so a device that
                        // never picked a preset can't clobber the account's preset with an empty string.
                        if (voicePrefs.pipelinePreset.isNotEmpty()) voiceAcctJson.put("pipelinePreset", voicePrefs.pipelinePreset)
                        val voiceAcctPayload = voiceAcctJson.toString()
                        // The ONE account key the user actually changed (e.g. "voice.ttsProvider"),
                        // set by the notify*Changed handler that fired this broadcast. It uploads
                        // unconditionally on the JS side so a genuine re-pick heals a DB that drifted
                        // behind the store; siblings still diff (no clobber). Absent = pure diff.
                        val changedKeyJs = intent.getStringExtra("changedKey")?.let { "'$it'" } ?: "undefined"
                        webViewProvider().evaluateJavascript(
                            """(function(){
                                try { if (window.saveDeviceSettings) window.saveDeviceSettings('aiVoice', $devicePayload); } catch(e) { console.error('aiVoice device save failed', e); }
                                try {
                                    // Dirty-tracked account write (audit GAP 1): only changed keys upload,
                                    // no sibling clobber; the user-changed key ($changedKeyJs) always uploads.
                                    // Falls back to the whole-blob paths on old JS.
                                    if (window.applyNativeVoiceAiAccountChange) window.applyNativeVoiceAiAccountChange({ ai: $acctPayload, voice: $voiceAcctPayload }, $changedKeyJs);
                                    else if (window.saveAccountSettings) window.saveAccountSettings({ ai: $acctPayload, voice: $voiceAcctPayload });
                                    else if (window.settingsStore) { var a=$acctPayload, vc=$voiceAcctPayload; window.settingsStore.set('ai.model', a.model); window.settingsStore.set('ai.retrievePicturesEnabled', a.retrievePicturesEnabled); window.settingsStore.set('ai.promptForFeedback', a.promptForFeedback); window.settingsStore.set('ai.webSearchEnabled', a.webSearchEnabled); window.settingsStore.set('voice.alwaysOpenDialog', vc.alwaysOpenDialog); window.settingsStore.set('voice.alwaysUseAI', vc.alwaysUseAI); window.settingsStore.set('voice.sttProvider', vc.sttProvider); window.settingsStore.set('voice.ttsProvider', vc.ttsProvider); window.settingsStore.set('voice.customizePipeline', vc.customizePipeline); if (vc.pipelinePreset !== undefined) window.settingsStore.set('voice.pipelinePreset', vc.pipelinePreset); window.settingsStore.save(); }
                                } catch(e) { console.error('ai account save failed', e); }
                            })()""".trimIndent(),
                            null
                        )
                    }
                    "$PREFIX.ACTION_REFRESH_MOTION_WAKE" -> {
                        Log.i(TAG, "🔧 Received refresh motion wake broadcast")
                        registryProvider()?.screenController?.refreshMotionWake()
                        // Re-sync the RTSP face-detection wiring on a wake-mode change.
                        // wireRtspFaceFrameCallback() is otherwise only called at RTSP start,
                        // so switching to face mode WHILE RTSP is already running never wired
                        // the HW-face wake callback (face wake silently did nothing).
                        registryProvider()?.let { reg ->
                            if (reg.dashieServiceManager?.isRtspServerRunning() == true) {
                                if (reg.prefs.screensaver.motionWakeMode == "face") {
                                    Log.i(TAG, "🔧 Re-wiring RTSP face detection (mode switched to face with RTSP running)")
                                    reg.wireRtspFaceFrameCallback()
                                } else {
                                    reg.clearRtspFaceFrameCallback()
                                }
                            }
                        }
                    }
                    "$PREFIX.ACTION_OPEN_WAKE_CALIBRATION" -> {
                        val mode = intent.getStringExtra("mode") ?: "camera"
                        Log.i(TAG, "🔧 Opening wake calibration dialog: mode=$mode")
                        registryProvider()?.dialogHost?.showWakeCalibrationDialog(mode) {
                            registryProvider()?.screenController?.refreshMotionWake()
                            // Notify SettingsActivity to refresh (threshold/distance may have changed)
                            context.sendBroadcast(
                                android.content.Intent("$PREFIX.ACTION_SETTINGS_REFRESH").apply {
                                    setPackage(context.packageName)
                                }
                            )
                        }
                    }
                    "$PREFIX.ACTION_LAYOUT_MODE_CHANGED" -> {
                        val mode = halitePrefsProvider()?.display?.layoutMode ?: "widgets"
                        Log.i(TAG, "📐 Received layout mode broadcast: $mode")
                        // Set root layout background for layout canvas modes
                        val isLayoutCanvas = mode == "widgets" || mode == "single_panel" || mode == "canvas"
                        val rootLayout = (context as? android.app.Activity)?.findViewById<android.view.View>(R.id.rootLayout)
                        rootLayout?.setBackgroundColor(
                            if (isLayoutCanvas) android.graphics.Color.parseColor("#1a1a2e")
                            else android.graphics.Color.WHITE
                        )
                        webViewProvider().evaluateJavascript(
                            // BUNDLE-EXEMPT: dashieOnLayoutModeChanged — layout mode is a full-mode dashboard concept
                            "if(window.dashieOnLayoutModeChanged) dashieOnLayoutModeChanged('$mode');",
                            null
                        )
                        // Re-apply orientation lock: it's only honored in
                        // widgets mode, so toggling away from widgets must
                        // release the lock (and vice versa).
                        orientationControllerProvider()?.applyLock()
                        portraitLayoutManagerProvider()?.reapply()
                    }
                    "$PREFIX.ACTION_ORIENTATION_LOCK_CHANGED" -> {
                        val lock = halitePrefsProvider()?.display?.orientationLock ?: "auto"
                        Log.i(TAG, "🧭 Received orientation lock broadcast: $lock")
                        orientationControllerProvider()?.applyLock()
                        // Push the new value to JS so device-settings-sync
                        // writes it to user_devices.display.
                        try {
                            webViewProvider().evaluateJavascript(
                                "window.dispatchEvent(new CustomEvent('native-settings-changed', { detail: { category: 'display' } }));",
                                null
                            )
                        } catch (e: Exception) {
                            Log.d(TAG, "WebView not ready for orientation lock dispatch")
                        }
                    }
                    "$PREFIX.ACTION_EDIT_LAYOUT" -> {
                        Log.i(TAG, "✏️ Entering layout edit mode")
                        // Hide native sidebar, show edit toolbar (keep WebView margin)
                        sidebarControllerProvider()?.hide(keepWebViewMargin = true)
                        val editToolbar = (context as? android.app.Activity)?.findViewById<android.view.View>(R.id.editToolbarRoot)
                        editToolbar?.visibility = android.view.View.VISIBLE
                        // Tint icons and labels to match sidebar theme
                        val isDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context) ?: false
                        val iconTint = if (isDark) android.graphics.Color.WHITE else 0xFF212121.toInt()
                        val labelColor = if (isDark) 0x99FFFFFF.toInt() else 0x99212121.toInt()
                        fun tintBtn(id: Int) {
                            val group = editToolbar?.findViewById<android.view.ViewGroup>(id) ?: return
                            for (i in 0 until group.childCount) {
                                when (val child = group.getChildAt(i)) {
                                    is android.widget.ImageView -> child.setColorFilter(iconTint, android.graphics.PorterDuff.Mode.SRC_IN)
                                    is android.widget.TextView -> child.setTextColor(labelColor)
                                }
                            }
                        }
                        listOf(R.id.editBtnSave, R.id.editBtnAdd, R.id.editBtnUndo, R.id.editBtnGrid, R.id.editBtnClose).forEach { tintBtn(it) }
                        // Apply correct background based on theme
                        editToolbar?.findViewById<android.view.View>(R.id.editToolbarPanel)?.setBackgroundResource(
                            if (isDark) R.drawable.bg_sidebar_strip else R.drawable.bg_sidebar_strip_light
                        )

                        // Wire edit toolbar buttons to JS functions
                        val wv = webViewProvider()
                        editToolbar?.findViewById<android.view.View>(R.id.editBtnSave)?.setOnClickListener {
                            // BUNDLE-EXEMPT: layoutEditSave — layout editor is full-mode only
                            wv.evaluateJavascript("if(window.layoutEditSave) layoutEditSave()", null)
                        }
                        editToolbar?.findViewById<android.view.View>(R.id.editBtnClose)?.setOnClickListener {
                            // BUNDLE-EXEMPT: layoutEditClose — layout editor is full-mode only
                            wv.evaluateJavascript("if(window.layoutEditClose) layoutEditClose()", null)
                        }
                        editToolbar?.findViewById<android.view.View>(R.id.editBtnUndo)?.setOnClickListener {
                            // BUNDLE-EXEMPT: layoutEditUndo — layout editor is full-mode only
                            wv.evaluateJavascript("if(window.layoutEditUndo) layoutEditUndo()", null)
                        }
                        var gridShowing = true
                        editToolbar?.findViewById<android.view.View>(R.id.editBtnGrid)?.setOnClickListener {
                            // BUNDLE-EXEMPT: layoutEditToggleGrid — layout editor is full-mode only
                            wv.evaluateJavascript("if(window.layoutEditToggleGrid) layoutEditToggleGrid()", null)
                            gridShowing = !gridShowing
                            // Dim icon when grid is off, label stays "Grid"
                            editToolbar.findViewById<android.widget.ImageView>(R.id.editBtnGridIcon)?.alpha =
                                if (gridShowing) 1f else 0.4f
                        }
                        editToolbar?.findViewById<android.view.View>(R.id.editBtnAdd)?.setOnClickListener {
                            // BUNDLE-EXEMPT: layoutEditAddWidget — layout editor is full-mode only
                            wv.evaluateJavascript("if(window.layoutEditAddWidget) layoutEditAddWidget()", null)
                        }

                        // Wire exit callback to restore sidebar + hide toolbar
                        registryProvider()?.jsBridge?.settingsDelegate?.onLayoutEditModeChanged = { enabled ->
                            if (!enabled) {
                                Log.i(TAG, "✏️ Edit mode exited — restoring sidebar")
                                editToolbar?.visibility = android.view.View.GONE
                                sidebarControllerProvider()?.show()
                            }
                        }

                        wv.evaluateJavascript(
                            // BUNDLE-EXEMPT: layoutCanvas — layout editor is full-mode only
                            "if(window.layoutCanvas) layoutCanvas.enterEditMode();",
                            null
                        )
                    }
                    "$PREFIX.ACTION_MQTT_CONFIG_CHANGED" -> {
                        Log.i(TAG, "📡 MQTT config changed")
                        val registry = registryProvider() ?: return
                        val mqttPrefs = halitePrefsProvider()?.mqtt ?: return
                        if (mqttPrefs.enabled) {
                            if (registry.mqttService == null) {
                                registry.startMqttService()
                            } else {
                                registry.mqttService?.reconnect()
                            }
                        } else {
                            registry.stopMqttService()
                        }
                    }
                    "$PREFIX.ACTION_MQTT_TEST_CONNECTION" -> {
                        Log.i(TAG, "📡 MQTT test connection requested")
                        val registry = registryProvider() ?: return
                        val mqttPrefs = halitePrefsProvider()?.mqtt ?: return
                        if (mqttPrefs.enabled) {
                            if (registry.mqttService == null) {
                                registry.startMqttService()
                            } else {
                                registry.mqttService?.reconnect()
                            }
                            // Show toast with result after a short delay for connection to complete
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                val mqtt = halitePrefsProvider()?.mqtt
                                val msg = when {
                                    mqtt == null -> "MQTT: Unknown error"
                                    mqtt.lastStatus == com.dashieapp.Dashie.halite.preferences.MqttPreferences.STATUS_CONNECTED ->
                                        "MQTT: Connected"
                                    mqtt.isError ->
                                        "MQTT: ${com.dashieapp.Dashie.halite.preferences.MqttPreferences.friendlyError(mqtt.lastError)}"
                                    else -> "MQTT: ${mqtt.statusDisplayText}"
                                }
                                android.widget.Toast.makeText(
                                    context, msg,
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }, 2000)
                        } else {
                            android.widget.Toast.makeText(
                                context, "MQTT is not enabled",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    "$PREFIX.ACTION_VOICE_ENABLED_CHANGED" -> {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        Log.i(TAG, "🎤 Voice enabled changed: $enabled")
                        if (enabled) {
                            voiceControllerProvider()?.checkPermissionAndInit()
                        } else {
                            voiceControllerProvider()?.shutdown()
                        }
                    }
                    "$PREFIX.ACTION_VOICE_PIPELINE_REINIT" -> {
                        // A pipeline-affecting voice setting changed natively
                        // (control method / STT / TTS / AI model / customize).
                        // Reinitialize so the running voice service switches
                        // immediately instead of only after a reload/restart.
                        Log.i(TAG, "🎤 Voice pipeline setting changed — reinitializing voice service")
                        voiceControllerProvider()?.reinitialize()
                    }
                    "$PREFIX.ACTION_MQTT_DISCOVERY_CHANGED" -> {
                        Log.i(TAG, "📡 MQTT discovery setting changed")
                        // Refresh in place: removeDiscovery() clears the retained
                        // configs (so turning discovery OFF actually removes the HA
                        // entities — previously reconnect() left them stale forever),
                        // then publishDiscovery() re-adds them when it's back ON.
                        registryProvider()?.refreshMqttDiscovery()
                    }
                    "$PREFIX.ACTION_MQTT_SENSORS_CHANGED" -> {
                        Log.i(TAG, "📡 MQTT sensor toggles changed")
                        // Re-sync discovery so a just-enabled sensor appears and a
                        // just-disabled one is removed immediately (was reconnect-only).
                        registryProvider()?.refreshMqttDiscovery()
                    }
                    "$PREFIX.ACTION_MQTT_BASE_TOPIC_CHANGED" -> {
                        Log.i(TAG, "📡 MQTT base topic changed")
                        // Reconnect: re-reads resolvedBaseTopic, re-subscribes the
                        // command topic, and republishes discovery with the new
                        // state/command topics embedded in the config payloads.
                        registryProvider()?.mqttService?.reconnect()
                    }
                    "$PREFIX.ACTION_STEALTH_REFRESH_CHANGED" -> {
                        Log.i(TAG, "🔄 Stealth refresh changed — reschedule/cancel alarm live (was reload-only)")
                        screenControllerProvider()?.refreshStealthReloadSettings()
                    }
                    // Receiver is NOT_EXPORTED with an explicit filter, so this only fires for a
                    // typo'd/renamed action constant or a stale test broadcast — still worth a shout.
                    else -> Log.w(TAG, "DROP: unhandled broadcast action: ${intent?.action}")
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("$PREFIX.ACTION_EDIT_LAYOUT")
            addAction("$PREFIX.ACTION_APPLY_ZOOM")
            addAction("$PREFIX.ACTION_APPLY_WIDGET_ZOOM")
            addAction("$PREFIX.ACTION_APPLY_WIDGET_FONT_SIZE")
            addAction("$PREFIX.ACTION_RESET_TIMER")
            addAction("$PREFIX.ACTION_TOGGLE_PERFORMANCE_OVERLAY")
            addAction("$PREFIX.ACTION_OPEN_DIAGNOSTICS")
            addAction("$PREFIX.ACTION_REFRESH_THRESHOLDS")
            addAction("$PREFIX.ACTION_WIFI_LOCK_CHANGED")
            addAction("$PREFIX.ACTION_SYNC_SAMPLE_COLLECTION")
            addAction("$PREFIX.ACTION_TOGGLE_DASH_MENU")
            addAction("$PREFIX.ACTION_DASHIE_SIGN_IN")
            addAction("$PREFIX.ACTION_DASHIE_SIGN_OUT")
            addAction("$PREFIX.ACTION_DASHIE_DELETE_ACCOUNT")
            addAction("$PREFIX.ACTION_DASHIE_ACCOUNT_DELETED_COMPLETE")
            addAction("$PREFIX.ACTION_DASHIE_CHECK_UPDATE")
            addAction("$PREFIX.ACTION_DASHIE_TRIAL_EXPIRED_SUBSCRIBE")
            addAction("$PREFIX.ACTION_REFRESH_SIDEBAR_PIN")
            addAction("$PREFIX.ACTION_SWITCH_TO_KIOSK")
            addAction("$PREFIX.ACTION_SWITCH_TO_FULL")
            addAction("$PREFIX.ACTION_CAMERA_ENABLED_CHANGED")
            addAction("$PREFIX.ACTION_CAMERA_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_HA_SENSOR_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_SCREENSAVER_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_PHOTO_SOURCE_CHANGED")
            addAction("$PREFIX.ACTION_PHOTO_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_SIDEBAR_ICON_SIZE")
            addAction("$PREFIX.ACTION_APPLY_DISPLAY_SIZE")
            addAction("$PREFIX.ACTION_THEME_CHANGED")
            addAction("$PREFIX.ACTION_DARK_MODE_CHANGED")
            addAction("$PREFIX.ACTION_ANIMATIONS_ENABLED_CHANGED")
            addAction("$PREFIX.ACTION_ANIMATION_LEVEL_CHANGED")
            addAction("$PREFIX.ACTION_REFRESH_MOTION_WAKE")
            addAction("$PREFIX.ACTION_OPEN_WAKE_CALIBRATION")
            addAction("$PREFIX.ACTION_LAYOUT_MODE_CHANGED")
            addAction("$PREFIX.ACTION_ORIENTATION_LOCK_CHANGED")
            addAction("$PREFIX.ACTION_SLEEP_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_WEATHER_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_LOCATION_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_TIME_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_ACCOUNT_PREFS_CHANGED")
            addAction("$PREFIX.ACTION_LOCATIONS_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_CHORES_REWARDS_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_CALENDAR_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_DISPLAY_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_HA_ENABLED_CHANGED")
            addAction("$PREFIX.ACTION_HA_DISPLAY_CHANGED")
            addAction("$PREFIX.ACTION_API_CHANGED")
            addAction("$PREFIX.ACTION_HA_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_VIDEO_FEED_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_MQTT_CONFIG_CHANGED")
            addAction("$PREFIX.ACTION_MQTT_DISCOVERY_CHANGED")
            addAction("$PREFIX.ACTION_MQTT_SENSORS_CHANGED")
            addAction("$PREFIX.ACTION_MQTT_BASE_TOPIC_CHANGED")
            addAction("$PREFIX.ACTION_MQTT_TEST_CONNECTION")
            addAction("$PREFIX.ACTION_STEALTH_REFRESH_CHANGED")
            addAction("$PREFIX.ACTION_VOICE_ENABLED_CHANGED")
            addAction("$PREFIX.ACTION_VOICE_PIPELINE_REINIT")
            addAction("$PREFIX.ACTION_VOICE_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_AI_VOICE_SETTINGS_CHANGED")
            addAction("$PREFIX.ACTION_DEVICE_NAME_CHANGED")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        // Diagnostic-only dump action. Registered as a separate EXPORTED receiver
        // so `adb shell am broadcast` (shell UID) can trigger it — the main
        // receiver above is NOT_EXPORTED to keep other settings broadcasts
        // same-app-only. Debug builds only: exported means ANY installed app can
        // fire it, so release builds shouldn't offer a third-party-triggerable
        // settings dump (output is logcat-only, but there's no reason to expose it).
        if (!BuildConfig.DEBUG) return
        dumpReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != "$PREFIX.ACTION_DUMP_SETTINGS") return
                Log.i(TAG, "🩺 Received dump-settings broadcast — evaluating JS")
                // evaluateJavascript can't await a Promise, so we fire-and-forget
                // window.dumpSettings() and let it hand the JSON back through the
                // DashieNative.onSettingsDump bridge (which logcats under the
                // DashieSettingsDump tag).
                webViewProvider().evaluateJavascript(
                    // BUNDLE-EXEMPT: dumpSettings — DEBUG-only settings dump; absent global reports a loud error string via onSettingsDump
                    """
                    (function(){
                        if (!window.dumpSettings || !window.DashieNative || !window.DashieNative.onSettingsDump) {
                            if (window.DashieNative && window.DashieNative.onSettingsDump) {
                                window.DashieNative.onSettingsDump('{"error":"window.dumpSettings not installed yet"}');
                            }
                            return;
                        }
                        window.dumpSettings()
                            .then(function(snapshot) {
                                window.DashieNative.onSettingsDump(JSON.stringify(snapshot));
                            })
                            .catch(function(e) {
                                window.DashieNative.onSettingsDump(JSON.stringify({ error: (e && e.message) || String(e) }));
                            });
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
        val dumpFilter = IntentFilter("$PREFIX.ACTION_DUMP_SETTINGS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(dumpReceiver, dumpFilter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(dumpReceiver, dumpFilter)
        }
    }

    /**
     * Show the Dashie-styled "Account Deleted" confirmation. On dismiss,
     * loads the kiosk shell URL so the user lands in the offline overlay
     * (the natural state for a device with no linked account).
     */
    private fun showAccountDeletedDialog(activity: android.app.Activity) {
        try {
            val dialogView = activity.layoutInflater.inflate(
                com.dashieapp.Dashie.R.layout.dialog_confirm, null
            )
            dialogView.findViewById<android.widget.TextView>(
                com.dashieapp.Dashie.R.id.dialogTitle
            ).text = "Account Deleted"
            dialogView.findViewById<android.widget.TextView>(
                com.dashieapp.Dashie.R.id.dialogMessage
            ).text = "Your Dashie account and all associated data have been " +
                "permanently deleted."

            val dialog = android.app.AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            // Hide the negative (cancel) button — only OK is meaningful here.
            dialogView.findViewById<android.widget.Button>(
                com.dashieapp.Dashie.R.id.buttonNegative
            ).visibility = android.view.View.GONE

            val okBtn = dialogView.findViewById<android.widget.Button>(
                com.dashieapp.Dashie.R.id.buttonPositive
            )
            okBtn.text = "OK"
            okBtn.setOnClickListener {
                // Shell URL was already loaded by the broadcast handler
                // (so JS stops re-registering widgets); just dismiss.
                dialog.dismiss()
            }

            dialog.show()
            try {
                com.dashieapp.Dashie.halite.SidebarSettingsHelper
                    .applyImmersiveModeToDialog(dialog)
                // setDefaultFocusOnCancel targets the negative button, which
                // is GONE in this dialog — focus would land on nothing and
                // d-pad users had to press DOWN to surface OK. Focus OK
                // explicitly instead.
                okBtn.isFocusable = true
                okBtn.post { okBtn.requestFocus() }
            } catch (e: Exception) {
                Log.w(TAG, "Helper styling not available: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🗑️ Failed to show account-deleted dialog: ${e.message}", e)
            // Fallback: still load the shell so the user isn't stranded
            val shellUrl = urlHandlerProvider()?.determineInitialUrl()
            if (shellUrl != null) {
                urlHandlerProvider()?.loadUrlWithWsProxyIfNeeded(shellUrl)
            }
        }
    }

    fun unregister() {
        receiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        dumpReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
        }
        dumpReceiver = null
    }
}
