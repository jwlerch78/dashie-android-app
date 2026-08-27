package com.dashieapp.Dashie.webview.delegates

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.DashieDeviceAdminReceiver
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.edition.brandName

/**
 * JS bridge delegate for device administration, boot settings, Locations/Chores/
 * Calendar JSON settings passthrough, and Dashie account linking.
 *
 * Extracted from JsBridgeSettingsDelegate in Phase 7 (structural split).
 */
class JsBridgeSystemDelegate(
    private val context: Context,
    private val webView: WebView,
    private val halitePrefs: () -> HalitePreferences?
) {
    companion object {
        private const val TAG = "JsBridgeSystem"
    }

    // Callbacks
    var onRequestDeviceAdmin: (() -> Unit)? = null
    var onReturnHomeTimeoutChanged: ((Int) -> Unit)? = null

    // ============================================
    // Advanced Settings
    // ============================================

    fun isDeviceAdminActive(): Boolean =
        com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(context)

    fun requestDeviceAdmin() {
        Log.i(TAG, "🔧 requestDeviceAdmin()")
        webView.post { onRequestDeviceAdmin?.invoke() }
    }

    fun setReturnHomeTimeout(seconds: Int) {
        halitePrefs()?.performance?.returnHomeTimeout = seconds
        Log.i(TAG, "Return to home timeout set to ${seconds}s")
        onReturnHomeTimeoutChanged?.invoke(seconds)
    }

    fun setMemoryRecoveryEnabled(enabled: Boolean) {
        halitePrefs()?.performance?.memoryRecoveryEnabled = enabled
        Log.i(TAG, "Memory recovery ${if (enabled) "enabled" else "disabled"}")
    }

    // ============================================
    // Boot Settings
    // ============================================

    fun getBootSettings(): String {
        val prefs = halitePrefs() ?: HalitePreferences(context)
        return org.json.JSONObject(mapOf(
            "startOnBoot" to prefs.sleep.startOnBoot,
            "launchOnWake" to prefs.sleep.launchOnWake,
            "useExactAlarms" to prefs.performance.useExactAlarms,
            "autoReloadOnCrash" to prefs.performance.autoReloadOnCrash,
            "pinAppWhenLocked" to prefs.lock.pinAppWhenLocked,
            "isDeviceOwner" to isDeviceOwnerApp()
        )).toString()
    }

    fun setStartOnBoot(enabled: Boolean) {
        Log.i(TAG, "📱 setStartOnBoot($enabled)")
        val prefs = halitePrefs() ?: HalitePreferences(context)
        prefs.sleep.startOnBoot = enabled
    }

    fun setAutoReloadOnCrash(enabled: Boolean) {
        Log.i(TAG, "📱 setAutoReloadOnCrash($enabled)")
        halitePrefs()?.performance?.autoReloadOnCrash = enabled
    }

    fun setPinAppWhenLocked(enabled: Boolean) {
        Log.i(TAG, "📱 setPinAppWhenLocked($enabled)")
        val prefs = halitePrefs() ?: HalitePreferences(context)
        prefs.lock.pinAppWhenLocked = enabled
    }

    /**
     * Check if this app is set as the device owner.
     * Device owner enables silent lock task mode (no toast, blocks notification shade).
     * Set via ADB: adb shell dpm set-device-owner <package>/<receiver>
     */
    fun isDeviceOwnerApp(): Boolean =
        com.dashieapp.Dashie.halite.DeviceAdminHelper.isDeviceOwnerApp(context)

    /**
     * Relinquish device owner privileges.
     * After this, screen pinning reverts to standard behavior (shows toast).
     * Re-enabling requires ADB access.
     * @return true if successfully relinquished, false on error or not device owner
     */
    fun relinquishDeviceOwner(): Boolean {
        val dpm = com.dashieapp.Dashie.halite.DeviceAdminHelper.dpm(context) ?: return false
        if (!com.dashieapp.Dashie.halite.DeviceAdminHelper.isDeviceOwnerApp(context)) return false
        try {
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "📱 Device owner relinquished successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "📱 Failed to relinquish device owner: ${e.message}")
            return false
        }
    }

    /**
     * Show native confirmation dialog for relinquishing device owner.
     * On confirm, calls relinquishDeviceOwner() and notifies JS of result.
     */
    fun showRelinquishDeviceOwnerConfirmation() {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

            dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Relinquish Device Owner"
            dialogView.findViewById<TextView>(R.id.dialogMessage).text =
                "This will remove Device Owner privileges from ${activity.brandName()}. Screen pinning will revert to showing the \"app is pinned\" notification.\n\nTo re-enable, you will need ADB access."

            val dialog = AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(true)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
                dialog.dismiss()
            }

            dialogView.findViewById<Button>(R.id.buttonPositive).text = "Relinquish"
            dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
                dialog.dismiss()
                val success = relinquishDeviceOwner()
                webView.post {
                    webView.evaluateJavascript(
                        // BUNDLE-EXEMPT: _onDeviceOwnerRelinquished — response callback — only evaluated in reply to a webapp bridge call
                        "window._onDeviceOwnerRelinquished && window._onDeviceOwnerRelinquished($success)",
                        null
                    )
                }
            }

            dialog.show()
        }
    }

    // ============================================
    // Locations Settings
    // ============================================

    fun getLocationsSettings(): String {
        val prefs = halitePrefs()?.locations ?: return "{}"
        return org.json.JSONObject().apply {
            put("trackingEnabled", prefs.trackingEnabled)
            put("travelTimeEnabled", prefs.travelTimeEnabled)
            put("earlyArrivalGames", prefs.earlyArrivalGames)
            put("earlyArrivalPractices", prefs.earlyArrivalPractices)
            put("earlyArrivalOther", prefs.earlyArrivalOther)
            put("trafficModel", prefs.trafficModel)
            put("showRadiusCircles", prefs.showRadiusCircles)
            put("notificationSounds", prefs.notificationSounds)
        }.toString()
    }

    fun setLocationsSettings(json: String) {
        Log.i(TAG, "📍 setLocationsSettings")
        val prefs = halitePrefs()?.locations ?: return
        try {
            val obj = org.json.JSONObject(json)
            if (obj.has("trackingEnabled")) prefs.trackingEnabled = obj.getBoolean("trackingEnabled")
            if (obj.has("travelTimeEnabled")) prefs.travelTimeEnabled = obj.getBoolean("travelTimeEnabled")
            if (obj.has("earlyArrivalGames")) prefs.earlyArrivalGames = obj.getInt("earlyArrivalGames")
            if (obj.has("earlyArrivalPractices")) prefs.earlyArrivalPractices = obj.getInt("earlyArrivalPractices")
            if (obj.has("earlyArrivalOther")) prefs.earlyArrivalOther = obj.getInt("earlyArrivalOther")
            if (obj.has("trafficModel")) prefs.trafficModel = obj.getString("trafficModel")
            if (obj.has("showRadiusCircles")) prefs.showRadiusCircles = obj.getBoolean("showRadiusCircles")
            // Device-scoped: getLocationsSettings already emits it; without this
            // branch a cloud/restore value never reached the pref (get/set asymmetry).
            if (obj.has("notificationSounds")) prefs.notificationSounds = obj.getBoolean("notificationSounds")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse locations settings JSON", e)
        }
    }

    // ============================================
    // Chores & Rewards Settings
    // ============================================

    fun getChoresRewardsSettings(): String {
        val prefs = halitePrefs()?.choresRewards ?: return "{}"
        return org.json.JSONObject().apply {
            put("choresEnabled", prefs.choresEnabled)
            put("anyoneEnabled", prefs.anyoneEnabled)
            val participants = prefs.getParticipants()
            put("participants", if (participants != null) org.json.JSONArray(participants) else org.json.JSONObject.NULL)
            put("upcomingDays", prefs.upcomingDays)
            put("rewardsEnabled", prefs.rewardsEnabled)
        }.toString()
    }

    fun setChoresRewardsSettings(json: String) {
        Log.i(TAG, "✅ setChoresRewardsSettings")
        val prefs = halitePrefs()?.choresRewards ?: return
        try {
            val obj = org.json.JSONObject(json)
            if (obj.has("choresEnabled")) prefs.choresEnabled = obj.getBoolean("choresEnabled")
            if (obj.has("anyoneEnabled")) prefs.anyoneEnabled = obj.getBoolean("anyoneEnabled")
            if (obj.has("participants")) {
                if (obj.isNull("participants")) {
                    prefs.setParticipants(null)
                } else {
                    val arr = obj.getJSONArray("participants")
                    prefs.setParticipants((0 until arr.length()).map { arr.getString(it) })
                }
            }
            if (obj.has("upcomingDays")) prefs.upcomingDays = obj.getInt("upcomingDays")
            if (obj.has("rewardsEnabled")) prefs.rewardsEnabled = obj.getBoolean("rewardsEnabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse chores/rewards settings JSON", e)
        }
    }

    // ============================================
    // Calendar Settings
    // ============================================

    // Week-start vocabulary bridge. start_week_on is persisted full-word
    // ("sunday"/"monday"/"saturday") so the native picker (CalendarPageSchema)
    // and value provider stay consistent, but the JS widget + settingsStore +
    // web all speak the abbreviated form ("sun"/"mon"/"sat"). Translate at this
    // JS-bridge boundary so the SYNCED value matches the widget; a full word
    // reaching settingsStore.calendar.startWeekOn fails the widget's `=== 'mon'`
    // check and silently falls back to Sunday.
    private fun abbrevWeekStart(full: String?): String = when (full) {
        "monday", "mon" -> "mon"
        "saturday", "sat" -> "sat"
        else -> "sun"
    }
    private fun fullWeekStart(abbrev: String?): String = when (abbrev) {
        "mon", "monday" -> "monday"
        "sat", "saturday" -> "saturday"
        else -> "sunday"
    }

    fun getCalendarSettings(): String {
        val prefs = context.getSharedPreferences("dashie_calendar_prefs", Context.MODE_PRIVATE)
        // scroll_time is persisted as a string for legacy reasons — emit it as
        // an int to JS so Supabase / localStorage / settingsStore all agree on
        // type. Without this coerce, user_devices.calendar.scrollTime lands as
        // "6" (string) while the JS Settings UI writes 6 (int), and the four
        // storage layers drift on every diff.
        val scrollTimeStr = prefs.getString("scroll_time", "8") ?: "8"
        val scrollTime = scrollTimeStr.toIntOrNull() ?: 8
        return org.json.JSONObject().apply {
            put("enabled", prefs.getBoolean("calendar_enabled", true))
            put("scrollTime", scrollTime)
            put("startWeekOn", abbrevWeekStart(prefs.getString("start_week_on", "sunday")))
            // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
            // writeAccess is ACCOUNT-level like startWeekOn; no vocabulary bridge
            // needed (values none|touch|voice|both match web/cloud verbatim).
            put("writeAccess", prefs.getString("write_access", "touch"))
        }.toString()
    }

    fun setCalendarSettings(json: String) {
        Log.i(TAG, "📅 setCalendarSettings")
        val prefs = context.getSharedPreferences("dashie_calendar_prefs", Context.MODE_PRIVATE)
        try {
            val obj = org.json.JSONObject(json)
            val edit = prefs.edit()
            if (obj.has("enabled")) edit.putBoolean("calendar_enabled", obj.getBoolean("enabled"))
            if (obj.has("scrollTime")) {
                // Accept either int (new path) or string (legacy JS caller) —
                // JSONObject.optInt coerces numeric strings so "6" and 6 both work.
                edit.putString("scroll_time", obj.optInt("scrollTime", 8).toString())
            }
            // Accept abbreviated (web/widget/cloud) or full-word (legacy) and
            // persist full-word so the native picker's option match still works.
            if (obj.has("startWeekOn")) edit.putString("start_week_on", fullWeekStart(obj.getString("startWeekOn")))
            // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
            // writeAccess (account-level) round-trips verbatim — persist as-is.
            if (obj.has("writeAccess")) edit.putString("write_access", obj.getString("writeAccess"))
            edit.commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse calendar settings JSON", e)
        }
    }

    // ============================================
    // Dashie Account
    // ============================================

    fun onDashieAuthComplete(email: String) {
        Log.i(TAG, "🔐 Dashie auth complete: $email")
        val prefs = halitePrefs() ?: com.dashieapp.Dashie.halite.HalitePreferences(context)
        // IDENTITY-SET marker. This links the device off a JS-side assertion alone — the
        // webapp calls it whenever IT considers itself authenticated (auth-initializer.js)
        // — with no check that a credential actually landed natively. That is how a revoked
        // tablet ends up announcing an account it cannot authenticate as; KioskJwtRefresher's
        // reconcileStaleIdentity now cleans it up, and this line says who caused it.
        if (prefs.connection.supabaseJwt.isEmpty()) {
            Log.w(TAG, "IDENTITY-SET: onDashieAuthComplete('$email') linking with NO native credential")
        }
        prefs.account.isLinked = true
        prefs.account.email = email
        // 🔴 THE FIX (Thread M, 2026-08-01): a login the user performed on this device is by
        // definition NOT kiosk-provisioned. Nothing used to clear this, so an HA-displaying
        // tablet that signed in normally kept the kiosk Account section forever — asserting a
        // false origin and withholding Sign Out on a rationale that did not apply to the
        // session, which left no working on-device control at all.
        prefs.account.kioskProvisionedSession = false
        // Force-pin the sidebar for users without an HA setup + no advanced
        // controls. Triggered on every sign-in (sign-up flow OR existing
        // sign-in via the "Sign in with Google" button), since neither path
        // is guaranteed to call setHaEnabled. We key off isSetupComplete
        // (false for non-HA users) rather than haEnabled (which only flips
        // false post-setHaEnabled). Idempotent.
        if (!prefs.connection.isSetupComplete && !prefs.connection.advancedTabletControlsEnabled
            && !prefs.display.dashMenuPinned) {
            prefs.display.dashMenuPinned = true
            Log.i(TAG, "🏠 Force-pinning sidebar from onDashieAuthComplete (no HA setup, no advanced)")
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_REFRESH_SIDEBAR_PIN").apply {
                    setPackage(context.packageName)
                }
            )
        } else if (prefs.connection.isSetupComplete
            && !prefs.account.firstKioskToWidgetsAutoPinDone) {
            // D.52 follow-up — fresh signup from HA-kiosk state. The
            // welcome wizard's layout-mode picker often doesn't fire a
            // real layout change (default is already "widgets"), so
            // the SettingsCallbackWiring kiosk → widgets check never
            // triggers. Pin here instead, gated on the one-shot
            // firstKioskToWidgetsAutoPinDone flag so we only do this
            // for the user's very first account on this device.
            prefs.display.dashMenuPinned = true
            prefs.account.firstKioskToWidgetsAutoPinDone = true
            Log.i(TAG, "🏠 Force-pinning sidebar from onDashieAuthComplete (first signin after HA-kiosk)")
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_REFRESH_SIDEBAR_PIN").apply {
                    setPackage(context.packageName)
                }
            )
        }
    }

    /**
     * Voice-only kiosk sign-in complete (kiosk cloud/voice onboarding). The user
     * added a Dashie account from the Voice & AI cloud-activation dialog (flow=voice)
     * to enable cloud voice — they are NOT trialing the full dashboard. Link the
     * account but keep the device in KIOSK mode: set haOnlyDisplay=true so
     * showsDashboard stays false (AccountPreferences), then switch the WebView back
     * to the HA kiosk shell (ACTION_SWITCH_TO_KIOSK → determineInitialUrl(), which
     * returns the shell once showsDashboard is false). No dashboard, no setup wizard,
     * no 30-day trial (the account is created ha_only via device_type='ha_app').
     *
     * Distinct from onDashieAuthComplete (the full-dashboard link, which also
     * auto-pins the widgets sidebar) — the web /login calls exactly one of the two.
     */
    fun returnToKioskAfterSignIn(email: String) {
        Log.i(TAG, "🔐 Voice-only kiosk sign-in complete: $email — caching session, staying in kiosk")
        val prefs = halitePrefs() ?: com.dashieapp.Dashie.halite.HalitePreferences(context)
        // The device-flow JWT lives in THIS WebView's localStorage (still on the
        // Dashie /login origin). Scrape it into native prefs BEFORE navigating to
        // the HA kiosk shell — once the WebView loads HA, the Dashie-origin JWT is
        // unreachable (SupabaseTokenExtractor's canonical constraint; the same
        // reason KioskSessionProvisioner exists). Without this the device is
        // "linked" with NO credential and Settings shows it signed out. Mirrors the
        // provisioner's post-JWT block: cache credential → set identity → push
        // session to the WebView stack → hand back to the shell.
        // @JavascriptInterface runs off the UI thread → post to the WebView.
        webView.post {
            com.dashieapp.Dashie.halite.SupabaseTokenExtractor.extractAndCache(webView, prefs.connection) { cached ->
                val account = prefs.account
                account.email = email
                account.authUserId = prefs.connection.supabaseUserId
                account.haOnlyDisplay = true   // D2: logged in, but keep displaying HA
                // Direct voice-only account (NOT a household-shared kiosk): Sign Out
                // sticks here, so Account should offer it. Marks this apart from a
                // KioskSessionProvisioner session (which re-provisions on boot).
                account.haOnlyVoiceSignup = true
                account.isLinked = true
                if (cached) {
                    com.dashieapp.Dashie.halite.auth.KioskSessionInjector.push(prefs)
                    Log.i(TAG, "🔐 Voice-only kiosk session cached (hasJwt) — logged in, staying on HA")
                } else {
                    Log.w(TAG, "IDENTITY-SET: voice-only sign-in linked '$email' with NO credential " +
                        "(no JWT found in WebView) — KioskJwtRefresher will reconcile this away")
                }
                context.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_SWITCH_TO_KIOSK").apply {
                        setPackage(context.packageName)
                    }
                )
            }
        }
    }

    /**
     * Called from the Dashie login page when the user clicks
     * "Connect to Home Assistant". Sets a one-shot pref flag, then triggers
     * the existing sign-out flow (which clears Dashie auth and reloads the
     * kiosk shell). The kiosk-overlay onboarding controller reads + clears
     * the flag on init and jumps straight to the HA config screen.
     */
    fun requestHaSetup() {
        Log.i(TAG, "🏠 HA setup requested from login page")
        com.dashieapp.Dashie.halite.HalitePreferences(context).connection.pendingHaSetup = true
        onDashieSignOut()
    }

    /** Read the pendingHaSetup flag — called by JS on onboarding init. */
    fun getPendingHaSetup(): Boolean {
        return com.dashieapp.Dashie.halite.HalitePreferences(context).connection.pendingHaSetup
    }

    /** Clear the pendingHaSetup flag once consumed. */
    fun clearPendingHaSetup() {
        com.dashieapp.Dashie.halite.HalitePreferences(context).connection.pendingHaSetup = false
    }

    /**
     * Set the per-device home_assistant.enabled flag. Used by the JS auth
     * flow when the user creates a Dashie account (mode=create) — the
     * fresh-install default is true, but a user explicitly creating a
     * Dashie account is on the non-HA path.
     */
    fun setHaEnabled(enabled: Boolean) {
        Log.i(TAG, "🏠 setHaEnabled($enabled) from JS")
        val prefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
        prefs.connection.haEnabled = enabled
        // Force-pin the sidebar for non-HA / no-advanced users at the moment
        // their non-HA path is committed. The initial force-pin in
        // NativeSidebarController.initialize() runs before this JS bridge
        // call (haEnabled still at the fresh-install default of true), so
        // it skips. Updating the pref here matches the controller's "fresh
        // non-HA user" intent (no recovery path in the hamburger popout
        // for them, so we keep the sidebar pinned). MainBroadcastManager
        // listens for ACTION_REFRESH_SIDEBAR_PIN and re-applies live.
        if (!enabled && !prefs.connection.advancedTabletControlsEnabled
            && !prefs.connection.isCustomUrlKiosk) {
            prefs.display.dashMenuPinned = true
            Log.i(TAG, "🏠 Force-pinning sidebar (non-HA, no advanced controls)")
            context.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_REFRESH_SIDEBAR_PIN").apply {
                    setPackage(context.packageName)
                }
            )
        }
    }

    /**
     * Set display.layoutMode from JS — used by the welcome wizard's
     * Display Mode picker so users can choose "widgets" vs "single_panel"
     * during onboarding without going through native Settings.
     *
     * Mirrors the SettingsCallbackWiring "notifyLayoutModeChanged"
     * callback the schema-driven settings UI uses, but we don't run
     * the kiosk-switch path here because the wizard only offers
     * non-kiosk modes (kiosk is reserved for HA-only).
     */
    /**
     * D.70 — set `display.permissionPromptDeclined` from JS. Called from
     * the welcome wizard's "Skip for Now" button on the device-
     * permissions step. Without this, MainServiceManager
     * .validateApiPermissionsOnResume → MainPermissionDelegate
     * .checkAndRequestApiPermissions re-prompts on the very next resume
     * (HA-enabled-tablet branch), ignoring the user's "later" choice.
     * The flag is honored by checkAndRequestApiPermissions's existing
     * `permissionPromptDeclined` guard. User can re-enable from API
     * settings (clears the flag), per the pref's documented contract.
     */
    fun setPermissionPromptDeclined(declined: Boolean) {
        Log.i(TAG, "🔐 setPermissionPromptDeclined($declined) from JS")
        val prefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
        prefs.display.permissionPromptDeclined = declined
    }

    fun setLayoutMode(mode: String) {
        Log.i(TAG, "📐 setLayoutMode($mode) from JS")
        if (mode != "widgets" && mode != "single_panel") {
            Log.w(TAG, "📐 setLayoutMode: ignoring unsupported mode '$mode'")
            return
        }
        val prefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
        if (prefs.display.layoutMode == mode) {
            Log.d(TAG, "📐 setLayoutMode: already at '$mode', skipping")
            return
        }
        prefs.display.layoutMode = mode
        // ACTION_LAYOUT_MODE_CHANGED is what the layout-service listens
        // for to swap the canvas in place — same broadcast the schema
        // UI fires.
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_LAYOUT_MODE_CHANGED").apply {
                setPackage(context.packageName)
            }
        )
    }

    /**
     * Wipe all per-user Kotlin SharedPreferences after the JS-side delete-
     * account flow has confirmed the server-side delete. Without this, the
     * Kotlin layer would still hold the prior account's HA URL / tokens /
     * MA tokens / account-linked flag — and a fresh sign-up afterwards
     * would inherit them.
     *
     * Cleared:
     *   ConnectionPreferences:  haUrl, haBaseUrl, haUsername, haPassword,
     *                           haAccessToken, haRefreshToken, haTokenExpiry,
     *                           supabaseJwt + expiry + userId, haEnabled,
     *                           pendingHaSetup, advancedTabletControlsEnabled,
     *                           customUrl, useCustomUrl, MA tokens,
     *                           music settings, isSetupComplete.
     *   AccountPreferences:     full clear() (linked flag + email).
     *   SubscriptionPreferences: full clear().
     *
     * Cookies are cleared by the existing JS clearLocalData(); this is
     * the Kotlin counterpart for SharedPrefs.
     */
    /**
     * Two-mode clear:
     *   Mode A — HA NOT configured: nuke ALL SharedPreferences. Device returns
     *     to fresh-install state (no HA, no account, no settings).
     *   Mode B — HA IS configured (`setup_complete=true && ha_url != ""`):
     *     keep HA-related state so the device stays usable as an HA kiosk
     *     after the Dashie account is gone. Wipe everything else (account,
     *     subscription, AI/photo/screensaver/sleep/display prefs,
     *     family zip, dark mode, chores/rewards/locations toggles).
     *
     * ⚠️ The VOICE LICENSE and the LOCAL VOICE PIPELINE are explicitly preserved
     * (see the `voice_` prefix + local-engine keys below). The license is a
     * one-time purchase bound to the DEVICE, not the account — destroying it on
     * sign-out was an unrecoverable brick of something the user paid for.
     *
     * Allow-list of keys preserved in `dashie_lite_prefs` when HA is
     * configured. Anything not on this list (and not matched by the prefix
     * rules below) is deleted. Note: `supabase_jwt`/`supabase_jwt_expiry`/
     * `supabase_user_id` are intentionally NOT preserved — they're tied to
     * the account that was just deleted.
     */
    fun clearAccountDataLocal() {
        Log.i(TAG, "🗑️ clearAccountDataLocal() from JS — wiping Kotlin per-user state")
        try {
            val mainPrefs = context.getSharedPreferences("dashie_lite_prefs", Context.MODE_PRIVATE)
            val haUrl = mainPrefs.getString("ha_url", "") ?: ""
            val isSetupComplete = mainPrefs.getBoolean("setup_complete", false)
            val haConfigured = isSetupComplete && haUrl.isNotEmpty()
            Log.i(TAG, "🗑️ haConfigured=$haConfigured (setup_complete=$isSetupComplete, haUrl=${if (haUrl.isEmpty()) "empty" else "set"})")

            // Always preserve device-level state: setup_complete,
            // beta_welcome_shown, swipe_tip_shown, dashboard_telemetry_consent,
            // device identity, and the rest of the per-device UX flags. These
            // belong to the DEVICE, not the account — once shown / set on this
            // physical hardware, they should survive an account sign-out. The
            // earlier "no HA → full wipe" branch reset all of them, which made
            // the kiosk onboarding flow (Setting up Dashie loading + data-
            // collection screen + post-onboarding swipe tip) re-fire every
            // time a Dashie-cloud user signed out and bounced back into the
            // kiosk shell. clearMainPrefsKeepingHa preserves the right keys
            // — when HA isn't configured, the HA-specific keys are empty/
            // unset so preserving them is a no-op.
            clearMainPrefsKeepingHa(mainPrefs)

            // Cookie wipe is still conditional on HA mode: kiosk-mode users
            // need their HA dashboard iframe session preserved across a
            // Dashie account sign-out; non-HA Dashie users want fresh
            // Google/Supabase cookies so a re-sign-up doesn't inherit the
            // previous session.
            if (!haConfigured) {
                try {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                } catch (e: Exception) {
                    Log.w(TAG, "🗑️ Could not clear WebView cookies: ${e.message}")
                }
            }

            // Per-feature pref files. Chores/Locations/Subscription are all
            // account-tied (chores boards live in Supabase, locations are
            // family-member features, subscription is per-account).
            listOf(
                "dashie_chores_prefs",
                "dashie_locations_prefs",
                "dashie_subscription_prefs"
            ).forEach { name ->
                try {
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                } catch (e: Exception) {
                    Log.w(TAG, "🗑️ Could not clear $name: ${e.message}")
                }
            }
            // Preserved across account delete:
            //   dashie_dark_mode_prefs  — per-device UX preference
            //   dashie_power_config     — device-level power tuning

            Log.i(TAG, "🗑️ ✅ Kotlin per-user state cleared (haConfigured=$haConfigured)")
        } catch (e: Exception) {
            Log.e(TAG, "🗑️ ❌ clearAccountDataLocal failed: ${e.message}", e)
        }
    }

    private fun clearMainPrefsKeepingHa(prefs: android.content.SharedPreferences) {
        val haPreserveKeys = setOf(
            // HA URL + connection
            "ha_url", "ha_base_url", "dashboard_name", "ha_username", "ha_password",
            "use_native_login", "keep_logged_in",
            "use_custom_url", "custom_url",
            "auto_build_url", "hide_sidebar", "hide_tabs", "hide_search", "hide_assistant",
            "ssdp_enabled",
            // HA tokens (the HA login session — survives Dashie delete)
            "ha_access_token", "ha_refresh_token", "ha_token_expiry",
            "pending_ha_setup", "pending_ha_login_success", "pending_ha_login_url",
            // Setup state
            "setup_complete", "ha_enabled",
            // Add-on / dev server
            "addon_url", "addon_mode", "dev_server_url",
            "api_enabled", "api_password", "api_port",
            // Device identity (stable per device, not per account)
            "device_uuid", "device_name", "device_friendly_name",
            // HA weather + time + cached coords
            "use_ha_for_weather", "use_ha_for_time",
            "weather_cached_latitude", "weather_cached_longitude",
            "weather_entity_id",

            // ── Per-device UX preferences (preserved across account delete) ──
            // These are device-level preferences, not account-tied — keeping
            // them avoids the user losing their setup when their account
            // changes. Also matches kiosk-mode-only users who never had
            // a Dashie account in the first place.
            "use_24_hour_clock", "date_format", "temperature_unit",
            "auto_brightness_enabled", "auto_brightness_min",
            "auto_brightness_max", "auto_brightness_curve",
            "show_ui_tips", "beta_welcome_shown", "swipe_tip_shown",
            "setup_pending_step", "timer_positions", "dashboard_zoom",
            "low_bandwidth_mode", "dash_menu_enabled", "dash_menu_pinned",
            "sidebar_icon_size", "layout_mode",
            "permission_prompt_declined", "firetv_idle_warning_dismissed",
            // Dashboard telemetry consent — once the user has answered the
            // onboarding data-collection prompt (or toggled in Settings),
            // their choice belongs to the device and shouldn't reset on a
            // Dashie account sign-out. Without preserving these the
            // kiosk-onboarding data-collection screen would re-fire on the
            // next kiosk launch (alongside setup_complete reset).
            "dashboard_telemetry_enabled", "dashboard_telemetry_consent",
            // Screensaver display flags (single-tokens that don't fit a prefix)
            "show_metadata", "show_clock", "show_date", "show_preview_on_wake",
            // Screensaver brightness
            "dim_brightness", "reduce_brightness_on_black",
            // Forecast card
            "forecast_card_size",
            // Camera motion (HA-adjacent)
            "camera_motion_enabled",

            // ── Local voice pipeline (device-owned; see the "voice_" prefix below) ──
            // These point at the user's OWN hardware — Piper/Whisper/Kokoro/Ollama on
            // their LAN, the HA box's engines — so they're device infrastructure, not
            // account data. Wiping them left a local-voice kiosk with no engine URLs,
            // silently falling back to (metered, now account-less) cloud defaults.
            // NOT prefix-matchable: these keys predate the voice_* naming.
            "stt_provider", "tts_provider",
            "local_stt_url", "local_tts_url", "local_tts_voice", "local_engines",
            "local_llm_url", "local_llm_model",
            "ha_tts_engine", "ha_tts_voice", "ha_stt_engine",
            "pipeline_preset", "customize_voice_pipeline",
            // The add-on's brain-route decision. Preserved so a local-brain household
            // doesn't silently revert to the cloud route on the next boot after sign-out.
            "brain_route_cached", "brain_route_checked_at"
        )
        // Prefix-matched preservation for HA-driven feature keys with
        // dynamic suffixes (per-rule positions, per-sensor toggles, etc.)
        // PLUS per-device UX preferences (screensaver, photo, music, etc.)
        // that survive an account delete.
        val haPreservePrefixes = listOf(
            // ⚠️ THE VOICE LICENSE IS A ONE-TIME PURCHASE BOUND TO THIS DEVICE, NOT TO THE
            // DASHIE ACCOUNT. It validates fully OFFLINE against sha256(voice_device_id),
            // compared to `device_id_hash` in the `dashie_voice_license_secure` prefs file
            // (which this wipe does NOT touch). But `voice_device_id` lives HERE, in
            // dashie_lite_prefs — so wiping it made VoiceLicenseManager.getDeviceId()
            // re-seed from the Widevine stable ID, the hash no longer matched, and a PAID
            // license read as INVALID. The only recovery was recoverLicenseFromServer()
            // — i.e. Supabase. If Dashie ever goes away, that is an UNRECOVERABLE BRICK of
            // something the user bought outright. (Audit 2026-07-14.)
            //
            // Made worse by a trap: once the JWT expires the tablet boots to a login screen
            // it can never satisfy, and the only obvious escape a user finds is "Sign out"
            // — the very thing that used to destroy their license.
            //
            // A PREFIX (not an explicit list) so a future voice_* key can't silently
            // reintroduce this. Covers: voice_device_id (the binding), voice_license_*,
            // voice_enabled, voice_pipeline_*, voice_control_method, voice_display_format.
            // None of these are account secrets.
            "voice_",
            "mqtt_",                  // HA-driven
            "video_feed_",            // HA camera/motion rules
            "rtsp_",                  // HA RTSP cameras
            // Music Assistant + music player config (HA-adjacent)
            "music_", "ma_", "speaker_only_",
            // Screensaver settings (per-device UX)
            "screensaver_", "slideshow_",
            "clock_position", "clock_size", "clock_font_size",
            "motion_wake_", "camera_wake_", "face_wake_",
            // Photo source configurations
            "photo_", "immich_", "google_photos_",
            "unsplash_", "ha_media_",
            "local_photo_folder",
            "launch_app_"
        )

        val all = prefs.all
        val preserved = all.filter { (k, _) ->
            k in haPreserveKeys || haPreservePrefixes.any { k.startsWith(it) }
        }
        Log.i(TAG, "🗑️ HA mode — preserving ${preserved.size} of ${all.size} keys in dashie_lite_prefs")

        prefs.edit().clear().commit()
        val editor = prefs.edit()
        for ((k, v) in preserved) {
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is Int -> editor.putInt(k, v)
                is Long -> editor.putLong(k, v)
                is Float -> editor.putFloat(k, v)
                is String -> editor.putString(k, v)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(k, v as Set<String>)
                }
                else -> Log.w(TAG, "🗑️ Skipping preserved key $k — unsupported type ${v?.javaClass}")
            }
        }
        editor.commit()
    }

    // ============================================
    // Account-delete progress + result dialogs (native)
    // ============================================
    // Why native: web modals (DashieModal) render in the WebView DOM,
    // which on Android sits BELOW the native Control Center overlay
    // (added directly to WindowManager). Users running delete from the
    // CC's Account row couldn't see the progress spinner or error toast
    // until they dismissed CC. AlertDialogs share window-level z-order
    // with CC and render above it.

    private var accountDeleteProgressDialog: AlertDialog? = null

    /**
     * Show a non-cancellable "Deleting account…" progress dialog.
     * Caller must invoke dismissAccountDeleteProgress() OR
     * showAccountDeleteResult() to clear it.
     */
    fun showAccountDeleteProgress() {
        val activity = context as? Activity ?: run {
            Log.w(TAG, "🗑️ showAccountDeleteProgress: no activity context, skipping")
            return
        }
        activity.runOnUiThread {
            try {
                accountDeleteProgressDialog?.dismiss()
                val view = activity.layoutInflater.inflate(R.layout.dialog_account_delete_progress, null)
                val dialog = AlertDialog.Builder(activity)
                    .setView(view)
                    .setCancelable(false)
                    .create()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                accountDeleteProgressDialog = dialog
                dialog.show()
                Log.i(TAG, "🗑️ Account delete progress shown")
            } catch (e: Exception) {
                Log.e(TAG, "🗑️ ❌ showAccountDeleteProgress failed: ${e.message}", e)
            }
        }
    }

    fun dismissAccountDeleteProgress() {
        val activity = context as? Activity ?: return
        activity.runOnUiThread {
            try {
                accountDeleteProgressDialog?.dismiss()
                accountDeleteProgressDialog = null
            } catch (e: Exception) {
                Log.w(TAG, "🗑️ dismissAccountDeleteProgress threw: ${e.message}")
            }
        }
    }

    /**
     * Show the result dialog after account-delete completes. Must dismiss
     * the progress dialog first so it doesn't visually layer behind. On
     * success, `onAccountDeleted()` should be called separately by JS to
     * trigger the broadcast → MainActivity reload chain.
     */
    fun showAccountDeleteResult(success: Boolean, message: String) {
        val activity = context as? Activity ?: run {
            Log.w(TAG, "🗑️ showAccountDeleteResult: no activity context")
            return
        }
        activity.runOnUiThread {
            try {
                accountDeleteProgressDialog?.dismiss()
                accountDeleteProgressDialog = null
                val title = if (success) "Account Deleted" else "Couldn't delete account"
                AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message.ifBlank {
                        if (success) "Your account has been deleted."
                        else "Something went wrong. Please try again or contact support."
                    })
                    .setPositiveButton(if (success) "OK" else "Close") { d, _ -> d.dismiss() }
                    .setCancelable(true)
                    .show()
                Log.i(TAG, "🗑️ Account delete result shown (success=$success)")
            } catch (e: Exception) {
                Log.e(TAG, "🗑️ ❌ showAccountDeleteResult failed: ${e.message}", e)
            }
        }
    }

    /**
     * Called from JS once handleDeleteAccount has finished its server-side
     * delete + clearAccountDataLocal step. Fires a broadcast so MainActivity
     * can dismiss native overlays / sidebar, show a Kotlin AlertDialog
     * confirmation, and reload to the kiosk shell. JS doesn't show its own
     * success modal or reload in this path — Kotlin owns both.
     */
    fun onAccountDeleted() {
        Log.i(TAG, "🗑️ onAccountDeleted() from JS — broadcasting completion")
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_ACCOUNT_DELETED_COMPLETE").apply {
                setPackage(context.packageName)
            }
        )
    }

    fun onDashieSignIn(mode: String = "") {
        Log.i(TAG, "🔐 Dashie sign in from webapp settings (mode=$mode)")
        // The kiosk-overlay onboarding calls this with mode="signin" or
        // "create" when the user takes the non-HA Dashie account path
        // (either no HA was detected on the network, or they explicitly
        // chose "Use Dashie without HA" / "Sign up for Dashie"). Flip
        // home_assistant.enabled to false on this device — the user can
        // re-enable HA later via the Advanced page or by tapping Connect
        // to Home Assistant. The legacy parameterless signIn() (from
        // Settings → Sign In to Dashie) is an existing-user flow and
        // shouldn't change haEnabled.
        if (mode.isNotEmpty()) {
            com.dashieapp.Dashie.halite.HalitePreferences(context).connection.haEnabled = false
            Log.i(TAG, "🏠 home_assistant.enabled flipped false (non-HA sign-in path)")
        }
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_IN").apply {
                setPackage(context.packageName)
                if (mode.isNotEmpty()) putExtra("mode", mode)
            }
        )
    }

    fun onDashieSignOut() {
        Log.i(TAG, "🔐 Dashie sign out from webapp")
        // Send broadcast to trigger full sign-out flow (clears account, switches viewport, reloads)
        context.sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_DASHIE_SIGN_OUT").apply {
                setPackage(context.packageName)
            }
        )
    }

    fun isDashieAccountLinked(): Boolean {
        return halitePrefs()?.account?.isLinked ?: false
    }

    fun getDashieAccountEmail(): String {
        return halitePrefs()?.account?.email ?: ""
    }

    // ============================================
    // Diagnostic: Kotlin-side view of device settings
    //
    // Returns a JSON blob in the shape of user_devices.settings.{category}.{key}
    // so the JS settings-snapshot tool can diff it against localStorage /
    // settingsStore / Supabase. Only includes fields for which Kotlin has a
    // SharedPrefs mirror — unknown/non-tracked keys are omitted entirely so
    // the snapshot treats them as "not tracked by Kotlin" rather than as a
    // disagreement.
    // ============================================

    /**
     * Called from JS when a settings dump is ready. evaluateJavascript
     * cannot await a Promise, so the ACTION_DUMP_SETTINGS broadcast kicks
     * off window.dumpSettings() and lets the Promise resolve back here
     * for logging.
     */
    fun onSettingsDump(json: String) {
        Log.i("DashieSettingsDump", "=== SETTINGS DUMP ===")
        json.chunked(3500).forEachIndexed { idx, chunk ->
            Log.i("DashieSettingsDump", "[$idx] $chunk")
        }
        Log.i("DashieSettingsDump", "=== END SETTINGS DUMP ===")
    }

    fun getAllDeviceSettings(): String {
        val prefs = halitePrefs() ?: return "{}"
        val themePrefs = context.getSharedPreferences("dashie_theme_prefs", Context.MODE_PRIVATE)
        val calendarPrefs = context.getSharedPreferences("dashie_calendar_prefs", Context.MODE_PRIVATE)
        val darkMode = com.dashieapp.Dashie.devicecontrols.DarkModeManager.getStoredPreference(context)
        val voicePrefs = com.dashieapp.Dashie.halite.preferences.VoicePreferences(context)
        val aiPrefs = com.dashieapp.Dashie.halite.preferences.AiPreferences(context)
        val locationsPrefs = com.dashieapp.Dashie.halite.preferences.LocationsPreferences(context)

        val display = org.json.JSONObject().apply {
            if (darkMode != null) put("darkMode", darkMode)
            put("themeFamily", themePrefs.getString("theme_family", "default") ?: "default")
            put("animationsEnabled", themePrefs.getBoolean("animations_enabled", false))
            put("animationLevel", themePrefs.getString("animation_level", "high") ?: "high")
            put("weatherOverlayEnabled", prefs.screensaver.weatherOverlayEnabled)
            put("weatherEntityId", prefs.screensaver.weatherEntityId)
            // Full-coverage extension (Phase 3 check G): every Kotlin-mirrored
            // manifest key must appear here so the reboot canary / household-sim
            // can diff the Kotlin layer without reading pref files directly.
            put("widgetFontSize", prefs.display.widgetFontSize)
            put("displaySize", prefs.display.displaySize)
            put("dashboardZoom", prefs.display.dashboardZoom)
            put("widgetZoom", prefs.display.widgetZoom)
            put("sidebarIconSize", prefs.display.sidebarIconSize)
            put("layoutMode", prefs.display.layoutMode)
            put("orientationLock", prefs.display.orientationLock)
            put("autoBrightnessEnabled", prefs.display.autoBrightnessEnabled)
            // same derivation as JsBridgeSleepDelegate.getScreenOffBehavior
            put("screenOffBehavior", if (prefs.sleep.hardwareScreenOff) "power_off" else "black_overlay")
            put("motionWakeMode", prefs.screensaver.motionWakeMode)
        }

        val screensaver = org.json.JSONObject().apply {
            put("timeout", prefs.screensaver.screensaverTimeout)
            put("mode", prefs.screensaver.screensaverMode)
            put("showClock", prefs.screensaver.showClock)
            put("showDate", prefs.screensaver.showDate)
            put("dimBrightness", prefs.screensaver.dimBrightness)
        }

        val photos = org.json.JSONObject().apply {
            put("sourceType", prefs.screensaver.photoSourceType)
            put("haMediaFolder", prefs.screensaver.haMediaFolder)
            put("unsplashQuery", prefs.screensaver.unsplashQuery)
            put("slideshowInterval", prefs.screensaver.slideshowInterval)
            put("slideshowTransition", prefs.screensaver.slideshowTransition)
            put("slideshowShuffle", prefs.screensaver.slideshowShuffle)
            put("photoFit", prefs.screensaver.photoFit)
            put("showMetadata", prefs.screensaver.showMetadata)
            put("unsplashArtistHyperlinks", prefs.screensaver.unsplashArtistHyperlinks)
        }

        val sleep = org.json.JSONObject().apply {
            put("enabled", prefs.sleep.sleepEnabled)
            put("sleepTime", prefs.sleep.sleepTime)
            put("wakeTime", prefs.sleep.wakeTime)
            put("sleepMethod", prefs.sleep.sleepMethod)
            put("resleepTimeout", prefs.sleep.resleepTimeout)
            put("inactivityTimeout", prefs.sleep.inactivityTimeout)
            put("motionWakeForSleep", prefs.sleep.motionWakeForSleep)
            put("sleepShowClock", prefs.sleep.sleepShowClock)
            put("reduceBrightnessOnSleep", prefs.sleep.reduceBrightnessOnSleep)
        }

        val aiVoice = org.json.JSONObject().apply {
            put("personalityId", aiPrefs.personalityId)
            put("voiceKey", voicePrefs.voiceKey)
            put("voiceId", voicePrefs.voiceId)
        }

        val voice = org.json.JSONObject().apply {
            put("enabled", voicePrefs.voiceEnabled)
            put("controlMethod", voicePrefs.voiceControlMethod)
            put("customizePipeline", voicePrefs.customizePipeline)
            put("sttProvider", voicePrefs.sttProvider)
            put("ttsProvider", voicePrefs.ttsProvider)
            put("responseHandling", voicePrefs.responseHandling)
            put("displayFormat", voicePrefs.displayFormat)
            put("confirmationToneType", voicePrefs.confirmationToneType)
            put("confirmationToneVolume", voicePrefs.confirmationToneVolume.toDouble())
        }

        val calendar = org.json.JSONObject().apply {
            put("enabled", calendarPrefs.getBoolean("calendar_enabled", true))
            // Coerce to Int — matches what getCalendarSettings() emits after the
            // scrollTime type-drift fix (previously Supabase landed "6" string).
            put("scrollTime", (calendarPrefs.getString("scroll_time", "8") ?: "8").toIntOrNull() ?: 8)
            put("startWeekOn", abbrevWeekStart(calendarPrefs.getString("start_week_on", "sunday")))
            // Vertical density preset (per-device): 'auto' or a number-of-hours
            // string. Emit in the dump so the drift canary sees the Kotlin layer.
            put("hoursToShow", calendarPrefs.getString("hours_to_show", "auto") ?: "auto")
            // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
            // Emit account-level writeAccess in the settings dump so the drift
            // canary can see the Kotlin layer (mirrors startWeekOn above).
            put("writeAccess", calendarPrefs.getString("write_access", "touch"))
        }

        // Locations SharedPrefs are currently dead writes from the Kotlin runtime
        // (Kotlin settings UI writes + reads them, but no Kotlin runtime consumer
        // acts on the values — see LocationsPreferences.kt). Still emit them so
        // the dump can detect drift against Supabase/localStorage.
        val locations = org.json.JSONObject().apply {
            put("trackingEnabled", locationsPrefs.trackingEnabled)
            put("showRadiusCircles", locationsPrefs.showRadiusCircles)
            put("travelTimeEnabled", locationsPrefs.travelTimeEnabled)
            put("earlyArrivalGames", locationsPrefs.earlyArrivalGames)
            put("earlyArrivalPractices", locationsPrefs.earlyArrivalPractices)
            put("earlyArrivalOther", locationsPrefs.earlyArrivalOther)
            put("trafficModel", locationsPrefs.trafficModel)
            put("notificationSounds", locationsPrefs.notificationSounds)
        }

        val developer = org.json.JSONObject().apply {
            put("liveSampleCollection", voicePrefs.sampleCollectionEnabled)
        }

        val choresRewards = org.json.JSONObject().apply {
            val cr = prefs.choresRewards
            put("choresEnabled", cr.choresEnabled)
            put("anyoneEnabled", cr.anyoneEnabled)
            val participants = cr.getParticipants()
            put("participants", if (participants != null) org.json.JSONArray(participants) else org.json.JSONObject.NULL)
            put("upcomingDays", cr.upcomingDays)
            put("rewardsEnabled", cr.rewardsEnabled)
        }

        val root = org.json.JSONObject().apply {
            put("display", display)
            put("screensaver", screensaver)
            put("photos", photos)
            put("sleep", sleep)
            put("aiVoice", aiVoice)
            put("voice", voice)
            put("developer", developer)
            put("calendar", calendar)
            put("locations", locations)
            put("choresRewards", choresRewards)
        }

        return root.toString()
    }
}
