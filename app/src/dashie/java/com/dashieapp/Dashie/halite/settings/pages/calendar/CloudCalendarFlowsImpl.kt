package com.dashieapp.Dashie.halite.settings.pages.calendar

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.showAppleCalendarQrPrompt

/**
 * DASHIE edition — the real cloud calendar-account flows.
 *
 * Moved out of `main/`'s `CalendarAccountFlows.kt` on 2026-08-02 so they are ABSENT from the
 * Chickadee artifact rather than merely unreachable in it. See [CloudCalendarFlows] for why the
 * runtime gate that already existed was not sufficient, and for the two functions that
 * deliberately stayed behind.
 *
 * There is no `chickadeeStub` twin of this file, by design — the seam returns `null` there.
 */
object DashieCloudCalendarFlows : CloudCalendarFlows {

    override fun registerAddAccountCallbacks(activity: SettingsActivity) {
        with(activity) {
            schemaContext.callbackRegistry.register("addGoogleCalendarAccount") { addCalendarAccount("google") }
            schemaContext.callbackRegistry.register("addMicrosoftCalendarAccount") { addCalendarAccount("microsoft") }
            schemaContext.callbackRegistry.register("addAppleCalendarAccount") { addAppleCalendarAccount() }
        }
    }

    override fun registerReauthCallback(
        activity: SettingsActivity,
        provider: String,
        accountType: String,
        email: String,
    ) {
        with(activity) {
            schemaContext.callbackRegistry.register(
                "reAuthCalendarAccount:$provider:$accountType:$email"
            ) { reAuthCalendarAccount(provider, accountType, email) }
        }
    }
}

internal fun SettingsActivity.addAppleCalendarAccount() {
    val wv = SettingsActivity.webViewRef?.get()
    if (wv == null) {
        android.widget.Toast.makeText(this, "WebView not available", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    // TVs and tablets get steered to dashieapp.com/apple — the 16-char
    // app-specific password is painful on a d-pad and tedious on a tablet
    // on-screen keyboard. Tablets keep an escape hatch in case the user
    // really wants to type it here.
    val isTv = com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(this)
    val isTablet = com.dashieapp.Dashie.util.DeviceInfoHelper.isTablet(this)
    if (isTv || isTablet) {
        showAppleCalendarQrPrompt(
            activity = this,
            allowProceed = isTablet,
            onProceed = { showAppleCredentialDialog(wv) }
        )
        return
    }

    showAppleCredentialDialog(wv)
}

/**
 * Build and show the native AlertDialog that collects Apple ID +
 * app-specific password and forwards them to the JS caldavClient.
 * Extracted so it can be re-invoked from the QR-prompt's "Continue here
 * anyway" path without duplicating code.
 */
private fun SettingsActivity.showAppleCredentialDialog(wv: android.webkit.WebView) {
    val dialogView = layoutInflater.inflate(R.layout.dialog_apple_credentials, null)
    val emailInput = dialogView.findViewById<android.widget.EditText>(R.id.appleEmailInput)
    val passwordInput = dialogView.findViewById<android.widget.EditText>(R.id.applePasswordInput)
    val cancelBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonNegative)
    val connectBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonPositive)

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    cancelBtn.setOnClickListener { dialog.dismiss() }
    connectBtn.setOnClickListener {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString().trim()
        if (email.isEmpty() || password.isEmpty()) {
            android.widget.Toast.makeText(this, "Email and password are required", android.widget.Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        dialog.dismiss()
        connectAppleCalendar(wv, email, password)
    }

    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    emailInput.requestFocus()
    dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
}

/**
 * Call JS caldavClient.saveAccount via evaluateJavascript, then auto-enable calendars.
 * Uses JSON.stringify to safely pass the password.
 */
private fun SettingsActivity.connectAppleCalendar(
    wv: android.webkit.WebView,
    email: String,
    password: String
) {
    android.widget.Toast.makeText(this, "Connecting to iCloud…", android.widget.Toast.LENGTH_SHORT).show()

    // JSON-encode the user-supplied values to survive being inlined into JS source
    val emailJs = org.json.JSONObject.quote(email)
    val passwordJs = org.json.JSONObject.quote(password)
    // evaluateJavascript runs at base URL about:blank, so relative module
    // specifiers fail with CORS-cross-origin errors. Use the absolute URL.
    val dashieUrl = com.dashieapp.Dashie.BuildConfig.DASHIE_URL

    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const { default: caldavClient } = await import('$dashieUrl/js/data/services/caldav/caldav-client.js');
                    const existing = await caldavClient.listAccounts();
                    const existingTypes = new Set(existing.map(a => a.accountType));
                    let accountType = 'primary';
                    if (existingTypes.has('primary')) {
                        let n = 2;
                        while (existingTypes.has('account' + n)) n++;
                        accountType = 'account' + n;
                    }
                    // save_account dedupes on (provider,email) server-side and returns
                    // the effective accountType — use that to avoid creating a duplicate.
                    const saveResult = await caldavClient.saveAccount({
                        accountType,
                        email: $emailJs,
                        password: $passwordJs,
                        provider: 'icloud'
                    });
                    if (saveResult && saveResult.accountType) {
                        accountType = saveResult.accountType;
                    }
                    // Fetch calendars via caldavClient directly (not cs.getCalendars) so we
                    // can read caldavClient._lastDebug when 0 calendars come back.
                    const calendars = await caldavClient.listCalendars(accountType, true);
                    const cs = window.calendarService;
                    let enabled = 0;
                    if (cs) {
                        // Invalidate any stale cache in calendarService
                        try { await cs.getCalendars('caldav-' + accountType, true); } catch (_) {}
                        for (const cal of calendars) {
                            const rawId = cal.id || cal.rawId || caldavClient.encodeCalendarId(cal.url);
                            if (!cs.isCalendarActive('caldav-' + accountType, rawId)) {
                                try { await cs.enableCalendar('caldav-' + accountType, rawId); enabled++; } catch (_) {}
                            }
                        }
                    }
                    if (calendars.length === 0 && caldavClient._lastDebug) {
                        // Surface diagnostic info so we can figure out why iCloud returned
                        // no calendars. Stringify trims big XML bodies to the preview.
                        const d = caldavClient._lastDebug;
                        const summary = 'status=' + d.status + ' len=' + d.bodyLength +
                            ' responses=' + d.responseCount +
                            ' blocks=' + JSON.stringify(d.blocks);
                        window.DashieNative.onCalendarError('iCloud 0 calendars. ' + summary);
                        // Also dump the raw preview to console so it shows in logcat
                        console.log('[CALDAV-DEBUG-PREVIEW]', d.preview);
                    } else {
                        window.DashieNative.onCalendarImportSuccess(
                            'Connected to iCloud. Found ' + calendars.length + ' calendar(s); ' + enabled + ' enabled.' +
                            (saveResult && saveResult.updatedExisting ? ' (updated existing account)' : '')
                        );
                    }
                } catch (e) {
                    const msg = (e && e.message) ? e.message : String(e);
                    window.DashieNative.onCalendarError('iCloud connection failed: ' + msg);
                }
            })()
        """.trimIndent(), null)
    }
}
/**
 * Re-authenticate an existing calendar account whose refresh token was
 * terminally revoked. Mirrors [addCalendarAccount] but stages the
 * `pendingReauth*` sessionStorage flags consumed by the JS session-manager
 * OAuth callback (which calls the `reauth_account` edge op to overwrite
 * tokens for the existing slot rather than creating a new one). Closes
 * settings so the OAuth redirect is visible in the WebView underneath.
 */
internal fun SettingsActivity.reAuthCalendarAccount(provider: String, accountType: String, email: String) {
    val wv = SettingsActivity.webViewRef?.get()
    if (wv == null) {
        android.widget.Toast.makeText(this, "WebView not available", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    // Escape user-controlled values for safe interpolation into the JS
    // template string. The fields come from server data but we still
    // double-quote them to avoid unbalanced quotes breaking the script.
    val safeAccountType = accountType.replace("'", "\\'")
    val safeEmail = email.replace("'", "\\'")
    val dashieUrl = com.dashieapp.Dashie.BuildConfig.DASHIE_URL

    // Delegate to the JS handlers — they branch on platform.getRecommendedAuthFlow()
    // and use HybridDeviceAuth + reauth_account inline on TV/tablet (device flow),
    // and the redirect-based OAuth callback on browser/computer. The previous
    // inline implementation always took the redirect path, which broke on
    // tablet/TV because the device-flow branch in google-account-auth.js looks
    // for `pendingAccountType` (not `pendingReauthAccountType`) and threw
    // "Missing target account type for secondary add".
    val handlerMethod = if (provider == "microsoft") "handleReauthMicrosoftAccount" else "handleReauthGoogleAccount"

    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const { CalendarAccountHandler } = await import('$dashieUrl/js/modules/Settings/handlers/calendar-account-handler.js');
                    const handler = new CalendarAccountHandler();
                    await handler.$handlerMethod('$safeAccountType', '$safeEmail');
                } catch (e) {
                    if (!e.message?.includes('cancelled') && !e.message?.includes('canceled')) {
                        window.DashieNative.onCalendarError('Failed to start sign-in: ' + e.message);
                    }
                }
            })()
        """.trimIndent(), null)
    }

    // Close settings so the OAuth flow is visible in the WebView underneath.
    // On return, session-manager's OAuth callback handler picks up the
    // pendingReauth* flags and calls the reauth_account edge op.
    setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
    finish()
}
internal fun SettingsActivity.addCalendarAccount(provider: String) {
    val wv = SettingsActivity.webViewRef?.get()
    if (wv == null) {
        android.widget.Toast.makeText(this, "WebView not available", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    // Trigger the OAuth flow in the WebView (runs behind the settings activity).
    // The OAuth redirect happens in the WebView, user authenticates, and the
    // new account will appear when calendar data is reloaded.
    val dashieUrl = com.dashieapp.Dashie.BuildConfig.DASHIE_URL
    wv.post {
        if (provider == "microsoft") {
            // Delegate to the JS handler so TV/tablet branches to the hybrid
            // QR flow and browser/mobile branches to web OAuth — the handler
            // itself checks platform.getRecommendedAuthFlow(). The previous
            // inline code always called MicrosoftWebOAuthProvider directly,
            // which meant Fire TV got the redirect flow and couldn't sign in.
            wv.evaluateJavascript("""
                (async () => {
                    try {
                        const { CalendarAccountHandler } = await import('$dashieUrl/js/modules/Settings/handlers/calendar-account-handler.js');
                        const handler = new CalendarAccountHandler();
                        await handler.handleAddMicrosoftAccount();
                    } catch (e) {
                        if (!e.message?.includes('cancelled') && !e.message?.includes('canceled')) {
                            window.DashieNative.onCalendarError('Failed to start sign-in: ' + e.message);
                        }
                    }
                })()
            """.trimIndent(), null)
        } else {
            // Google uses the standard sessionManager.signIn() with pendingAccountType.
            // Pass scopeSets.calendarOnly so the add-calendar flow doesn't request
            // Drive — Workspace admins (e.g. Veeva) block the whole consent when
            // Drive scopes are asked for at add time.
            // BUNDLE-EXEMPT: sessionManager — calendar settings are webapp-backed; absent service fails loud via DashieNative.onCalendarError
            wv.evaluateJavascript("""
                (async () => {
                    try {
                        const sm = window.sessionManager;
                        if (!sm) throw new Error('Session manager not available');
                        const ts = sm.getTokenStore();
                        const existing = await ts.getProviderAccounts('google');
                        const types = Object.keys(existing || {});
                        let nextNum = 2;
                        let nextType = 'account' + nextNum;
                        while (types.includes(nextType)) { nextNum++; nextType = 'account' + nextNum; }
                        sessionStorage.setItem('pendingAccountType', nextType);
                        const { API_CONFIG } = await import('$dashieUrl/js/data/auth/auth-config.js');
                        await sm.signIn({ scopes: API_CONFIG.google.scopeSets.calendarOnly });
                    } catch (e) {
                        if (!e.message?.includes('cancelled') && !e.message?.includes('canceled')) {
                            window.DashieNative.onCalendarError('Failed to start sign-in: ' + e.message);
                        }
                    }
                })()
            """.trimIndent(), null)
        }
    }

    // Close settings so the user can see the OAuth flow in the WebView
    setResult(SettingsActivity.RESULT_CLOSE_NO_CC)
    finish()
}
