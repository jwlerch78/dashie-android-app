package com.dashieapp.Dashie.halite.update

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.edition.brandName
import com.dashieapp.Dashie.halite.preferences.GeneralPreferences

/**
 * Orchestrates Dashie's in-app update UX. Owns an [UpdateBackend], shows the
 * update banner when an update is available, and handles the three banner actions
 * (mirrors the crash-report banner pattern).
 *
 * Wiring: create one instance with the host Activity.
 *  - Self-polls Play every [UPDATE_CHECK_INTERVAL_MS] so a kiosk left running
 *    for days keeps discovering updates without a restart.
 *  - The banner offers Dismiss / Later / Install Now. "Later" arms the 2 AM
 *    install alarm on backends that can install unattended (Play, device-owner
 *    sideload); on backends that can't, it snoozes the banner ~24h instead.
 *  - Control Center reads [isUpdatePendingInControlCenter] and calls
 *    [showBannerAgain] when the user taps its "Software Update" card.
 */
class DashieUpdateController(
    private val activity: Activity,
    /** True once first-run onboarding is finished — the banner is held back
     *  until then so it doesn't interrupt the welcome / setup flow. */
    private val isSetupComplete: () -> Boolean = { true }
) {

    companion object {
        private const val TAG = "DashieUpdate"
        /** How often to re-poll Play while the app stays running (6 hours). */
        private const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** How long "Later" snoozes the banner when an unattended install isn't possible. */
        private const val SNOOZE_MS = 24 * 60 * 60 * 1000L
    }

    // Backend is picked by the build flavor (BuildConfig.UPDATE_BACKEND):
    //  "play"     — Google Play In-App Updates (store builds)
    //  "sideload" — self APK download + install (sideload / staging testbed)
    //  "amazon"   — detect + deep-link to the Amazon Appstore
    private val manager: UpdateBackend = when (BuildConfig.UPDATE_BACKEND) {
        "sideload" -> SideloadUpdateBackend(activity, BuildConfig.UPDATE_MANIFEST_URL)
        "amazon" -> AmazonUpdateBackend(activity, BuildConfig.UPDATE_MANIFEST_URL)
        // Play backend gets the same manifest URL (reused for release notes
        // metadata — Play API only exposes versionCode). Prod flavor points
        // at amazon-latest.json since both ship the same release line; other
        // flavors using "play" backend leave it empty and skip the fetch.
        else -> PlayUpdateBackend(activity, BuildConfig.UPDATE_MANIFEST_URL)
    }
    private var bannerView: View? = null

    /** Bottom-anchored status banner shown during an update download or
     *  install. Persistent (no buttons) so users not in Control Center
     *  still see "something is happening". */
    private var statusBannerView: View? = null

    /** Set when an update was found but the banner was held back — onboarding in progress, or snoozed. */
    private var bannerDeferred = false

    /** D-pad focus position on the banner: 0=Dismiss, 1=Later/Tonight, 2=Install Now. */
    private var bannerFocusIndex = 2

    private val periodicHandler = Handler(Looper.getMainLooper())
    private val periodicCheck = object : Runnable {
        override fun run() {
            Log.i(TAG, "Periodic update check")
            // Routed through the gated entry point, not manager.checkForUpdate()
            // directly — this is an unattended check like the other two.
            checkForUpdateAutomatic()
            tryShowDeferredBanner()
            periodicHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS)
        }
    }

    /** Set when the most recent download attempt failed (SHA mismatch, HTTP
     *  error, network exception). Surfaces a red-dot error state on the CC
     *  "Software Update" card so the user has persistent feedback (a Toast
     *  is easy to miss on a TV). Cleared when the user taps the card or
     *  when a fresh download succeeds. */
    private var lastDownloadFailedReason: String? = null

    init {
        manager.onUpdateAvailable = { activity.runOnUiThread { showBanner() } }
        // Surface download failures to the user instead of silently
        // discarding the broken APK — without this they tap Install Now,
        // wait for an indeterminate time, then see nothing happen. The
        // CC card flips to a red-dot "Update Failed" state which is more
        // persistent than a Toast (especially on TV where the toast is
        // easy to miss).
        manager.onDownloadFailed = { reason ->
            lastDownloadFailedReason = reason
            activity.runOnUiThread {
                // CC card flips to a red-dot "Update Failed — tap to try
                // again" state on its next 3s refresh; status banner is
                // dismissed. No Toast needed — the persistent CC indicator
                // is more discoverable than a transient Toast.
                dismissStatusBanner()
            }
        }
        // Re-poll on a fixed interval — an always-on kiosk rarely restarts and
        // may have no daily-refresh window configured, so neither startup nor
        // the daily refresh is a reliable update trigger on its own.
        periodicHandler.postDelayed(periodicCheck, UPDATE_CHECK_INTERVAL_MS)
        // The 2 AM deferred-install alarm routes here.
        UpdateInstallReceiver.onInstallTriggered = {
            activity.runOnUiThread { installIfPending() }
        }
        // PackageInstaller commit result — fires on success OR any failure
        // (including STATUS_FAILURE_ABORTED when the user cancels the
        // system "Install Dashie?" prompt). On success the OS replaces
        // the process so the UI state doesn't matter; on failure we
        // need to clear the installInProgress flag so the CC card and
        // bottom status banner don't stay stuck on "Installing...". The
        // CC card flipping back to "Update Available" + the bottom
        // banner disappearing are the user-visible signals — a Toast
        // here would be redundant.
        ApkInstallResultReceiver.onInstallFinished = { status, _ ->
            if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                activity.runOnUiThread {
                    manager.clearInstallInProgress()
                    dismissStatusBanner()
                }
            }
        }
        // A "tonight" install that survived an app restart / device reboot —
        // re-arm the alarm (AlarmManager alarms don't survive a reboot).
        if (manager.tonightInstallPending) {
            Log.i(TAG, "Pending tonight-install found on startup — re-arming install alarm")
            manager.scheduleInstallAlarm()
        }
    }

    /** Forward [Activity.onActivityResult] for the Play in-app update flow.
     *  No-op for non-Play backends. Hooks Play's consent-dialog cancel
     *  path so we can clean up the status banner + CC card state when the
     *  user dismisses Play's prompt (otherwise the listener never fires
     *  and our UI stays stuck in "Downloading…"). */
    fun handleActivityResult(requestCode: Int, resultCode: Int) {
        (manager as? PlayUpdateBackend)?.onActivityResult(requestCode, resultCode)
    }

    /** Update check — called at startup and on the daily-refresh window. Safe to call repeatedly.
     *
     *  @param suppressBanner true when the user explicitly tapped
     *  "Check for Updates" in Settings → Advanced — they're already
     *  engaged with the UI, so the auto-banner pop-up is unnecessary.
     *  The CC "Software Update" card still surfaces (driven by
     *  updateAvailable, not the banner), giving the user a clear path
     *  to install. Default false = automatic checks fire the banner. */
    fun checkForUpdate(suppressBanner: Boolean = false) {
        suppressBannerForExplicitCheck = suppressBanner
        manager.checkForUpdate()
    }

    /**
     * The AUTOMATIC check — startup, the daily refresh, and the 6-hourly
     * self-poll. Honours the user's "Automatic update checks" preference;
     * every unattended path must route through here.
     *
     * 🔑 One holder on purpose. The rule was originally specified as "gate both
     * MainActivity call sites", but there is a THIRD unattended caller — this
     * class's own [periodicCheck] — so gating the call sites would have left a
     * device polling every 6 hours with the toggle off, i.e. exactly the "the
     * toggle is a lie" failure the requirement was written to prevent. A rule
     * that lives in one place cannot be half-applied by a caller that arrives
     * later.
     *
     * The explicit Settings → Advanced check calls [checkForUpdate] directly
     * and is deliberately NOT gated: a button that silently does nothing is
     * worse than no button.
     */
    fun checkForUpdateAutomatic() {
        if (!GeneralPreferences(activity).autoUpdateCheckEnabled) {
            // Loud, per standing rule 2 — a suppressed check must be visible in
            // logs, or "no update found" and "never looked" are indistinguishable.
            Log.i(TAG, "DROP: automatic update check skipped — 'Automatic update checks' is OFF")
            return
        }
        checkForUpdate()
    }

    /** Set true by checkForUpdate(suppressBanner=true); consumed by
     *  showBanner() the next time it would have fired. Each new
     *  checkForUpdate call overwrites the flag, so an automatic check
     *  after a manual one doesn't inherit the suppression. */
    private var suppressBannerForExplicitCheck = false

    /** Host activity resumed — resume a permission-blocked install, and show a
     *  banner that was held back during onboarding now that setup may be done. */
    fun onActivityResumed() {
        manager.onActivityResumed()
        tryShowDeferredBanner()
    }

    /** First-run onboarding finished — show a banner that was held back during setup. */
    fun onSetupCompleted() {
        activity.runOnUiThread { tryShowDeferredBanner() }
    }

    /** Re-show a banner that was deferred — once onboarding is done and any snooze has expired. */
    private fun tryShowDeferredBanner() {
        if (bannerDeferred && isSetupComplete() &&
            System.currentTimeMillis() >= manager.snoozeUntilMillis) {
            Log.i(TAG, "Re-showing deferred update banner")
            activity.runOnUiThread { showBanner() }
        }
    }

    /** Install a "tonight"-deferred update — called by the 2 AM alarm (and the daily-refresh backup). */
    fun installIfPending() {
        if (manager.tonightInstallPending) {
            Log.i(TAG, "Install window reached — installing deferred update")
            manager.installAndRestart()
        }
    }

    /**
     * True when an update is available but the banner isn't currently on
     * screen — so Control Center always carries a reliable "Software Update"
     * entry, whether the user dismissed the banner, tapped Update and the
     * install stalled, or a download is currently in progress. Stays true
     * during download so the CC card can flip to a "Downloading…" state
     * (see [isUpdateDownloading]) instead of vanishing.
     */
    fun isUpdatePendingInControlCenter(): Boolean =
        manager.updateAvailable && bannerView == null

    /** True while an update APK is downloading — drives the "Downloading…"
     *  label on the CC "Software Update" card. */
    fun isUpdateDownloading(): Boolean = manager.downloadInProgress

    /** True from the moment the PackageInstaller session is committed
     *  until the OS replaces our app process — drives the "Installing…"
     *  label on the CC card so the user has feedback during the system
     *  install (otherwise CC looks idle while the install happens). */
    fun isUpdateInstalling(): Boolean = manager.installInProgress

    /** True when the last download attempt failed — drives the red-dot
     *  "Update Failed" state on the CC card. Cleared the next time a
     *  download starts or succeeds. */
    fun lastDownloadFailed(): Boolean = lastDownloadFailedReason != null && !manager.downloadInProgress

    /** Fire TV / Fire devices don't reliably auto-relaunch after a
     *  sideload install (the launcher takes over instead). All other
     *  Android forms restart on their own. Centralized here so the
     *  CC card and toasts use consistent copy. */
    private val needsManualReopen: Boolean by lazy {
        com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV()
    }

    /** Trailing copy used after "Dashie will restart…" — switches to
     *  "you'll need to reopen from your home screen" on Fire TV. Drives
     *  the CC card's "Installing…" summary text. */
    fun postInstallReopenHint(): String = if (needsManualReopen)
        "Please reopen ${activity.brandName()} from your Fire TV home screen when the install finishes."
    else
        "${activity.brandName()} will restart when the install finishes."

    /**
     * D-pad handling for the banner on TV. The kiosk routes all d-pad input to
     * JS, so a native banner can't rely on Android focus traversal — this is
     * called from [MainActivity.dispatchKeyEvent] (mirrors the Control Center
     * pattern). Returns true (key consumed) only while the banner is showing.
     */
    fun handleBannerDpadKey(keyCode: Int): Boolean {
        val banner = bannerView ?: return false
        val buttons = listOf(
            banner.findViewById<Button>(R.id.updateDismiss),
            banner.findViewById<Button>(R.id.updateTonight),
            banner.findViewById<Button>(R.id.updateNow)
        )
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT ->
                bannerFocusIndex = (bannerFocusIndex - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                bannerFocusIndex = (bannerFocusIndex + 1).coerceAtMost(buttons.lastIndex)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                buttons[bannerFocusIndex].performClick()
                return true
            }
            // Single-row banner — swallow up/down so they don't leak to the
            // dashboard behind it.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            else -> return false
        }
        buttons[bannerFocusIndex].requestFocus()
        return true
    }

    /** Re-show the banner — e.g. from a Control Center "Software Update" tap. */
    fun showBannerAgain() {
        // Explicit re-show — clear any active snooze so the banner shows now.
        manager.snoozeUntilMillis = 0
        manager.dismissedToControlCenter = false
        activity.runOnUiThread { showBanner() }
    }

    /**
     * Show the full update-details modal — title + description + version +
     * the same 3 actions as the banner. Triggered from Control Center's
     * "Software Update" card. Falls back to generic copy when the manifest
     * doesn't carry release notes (old-schema manifests, Play backend).
     */
    fun showUpdateModal() {
        manager.snoozeUntilMillis = 0
        manager.dismissedToControlCenter = false
        activity.runOnUiThread { presentUpdateModal() }
    }

    /** Expose the manifest's versionName for the CC card label. */
    val availableVersionName: String?
        get() = manager.availableVersionName

    /** Stop the periodic check and release listeners. Call from the host's onDestroy. */
    fun cleanup() {
        periodicHandler.removeCallbacks(periodicCheck)
        UpdateInstallReceiver.onInstallTriggered = null
        ApkInstallResultReceiver.onInstallFinished = null
        dismissStatusBanner()
        manager.cleanup()
    }

    /**
     * Debug-only: preview the full update UI — shows the banner and forces the
     * "update available" state so the dismiss → Control Center "Software
     * Update" card path works without a real Play update.
     */
    fun showBannerForTest() {
        manager.simulateUpdateAvailableForTest()
        showBannerAgain()
    }

    // ── Banner ──────────────────────────────────────────────────────

    private fun showBanner() {
        if (bannerView != null) return  // already showing
        // Explicit "Check for Updates" from Settings → Advanced suppresses
        // the auto-banner — the user is already in the UI and the CC
        // "Software Update" card surfaces independently. Consume the
        // flag so subsequent automatic detections fire the banner normally.
        if (suppressBannerForExplicitCheck) {
            suppressBannerForExplicitCheck = false
            bannerDeferred = false
            Log.i(TAG, "Update detected via explicit check — suppressing auto-banner; CC card still surfaces")
            return
        }
        // Hold the banner back during first-run onboarding (don't interrupt
        // setup) and while snoozed via "Later". tryShowDeferredBanner() shows
        // it once both clear.
        if (!isSetupComplete()) {
            bannerDeferred = true
            Log.i(TAG, "Onboarding in progress — deferring update banner")
            return
        }
        if (System.currentTimeMillis() < manager.snoozeUntilMillis) {
            bannerDeferred = true
            Log.i(TAG, "Update snoozed — deferring banner")
            return
        }
        bannerDeferred = false
        val overlay = activity.findViewById<FrameLayout>(R.id.overlayContainer)
        if (overlay == null) {
            Log.w(TAG, "overlayContainer not found — cannot show update banner")
            return
        }
        val banner = LayoutInflater.from(activity)
            .inflate(R.layout.banner_update_notification, null)
        banner.findViewById<TextView>(R.id.updateBannerText)?.text =
            branded(R.string.update_available_banner)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            // Top-anchored — clears the bottom-anchored crash-report banner and
            // the post-onboarding "Setting up Dashie" tips card.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (24 * activity.resources.displayMetrics.density).toInt()
        }

        // Dismiss / [Tonight|Later] / Install Now. The middle button (id
        // `updateTonight`, the historical name) genuinely installs tonight on
        // backends that can install unattended → labelled "Tonight"; on the
        // rest it just snoozes → labelled "Later". See onLater().
        val installBtn = banner.findViewById<Button>(R.id.updateNow)
        val laterBtn = banner.findViewById<Button>(R.id.updateTonight)
        val dismissBtn = banner.findViewById<Button>(R.id.updateDismiss)
        laterBtn.text = if (manager.supportsDeferredInstall) "Tonight" else "Later"
        // "Install Now" on the banner doesn't start the download directly —
        // it opens the full update-details modal first so the user sees
        // what's in the update before committing. Matches the CC card flow.
        // The modal's own Install Now button is what actually triggers
        // the download.
        installBtn.setOnClickListener {
            dismissBanner()
            presentUpdateModal()
        }
        laterBtn.setOnClickListener { onLater() }
        dismissBtn.setOnClickListener { onDismiss() }

        overlay.addView(banner, params)
        bannerView = banner
        bannerFocusIndex = 2
        installBtn.requestFocus()
        Log.i(TAG, "Update banner shown")
    }

    private fun dismissBanner() {
        bannerView?.let { (it.parent as? FrameLayout)?.removeView(it) }
        bannerView = null
    }

    /** "Install Now" — download, then install + restart as soon as it's ready. */
    private fun onInstallNow() {
        // No-op if an install is already underway — don't kick off a fresh
        // download on top of a pending PackageInstaller session. The
        // bottom status banner already shows "Installing…" so no Toast
        // needed here.
        if (manager.installInProgress) return
        // Clear any prior failure state — the user is retrying.
        lastDownloadFailedReason = null
        manager.onReadyToInstall = {
            activity.runOnUiThread {
                // Update the bottom status banner to "Installing…" before
                // the out-of-process system prompt fires so the user has
                // visible feedback throughout the install window.
                showOrUpdateStatusBanner(installingText())
                manager.installAndRestart()
            }
        }
        // Show the persistent bottom banner immediately so users not in
        // Control Center know the download is in flight — replaces the
        // old transient "Downloading update…" Toast.
        showOrUpdateStatusBanner(downloadingText())
        manager.startDownload(activity)
        dismissBanner()
    }

    /**
     * "Later" — on a backend that can install unattended (Play, device-owner
     * sideload), download now and arm the 2 AM install alarm. Otherwise just
     * snooze the banner ~24h and re-prompt — an unattended install isn't
     * possible (a non-device-owner install needs a tap nobody's there to give).
     */
    private fun onLater() {
        dismissBanner()
        if (manager.supportsDeferredInstall) {
            manager.startDownload(activity)
            manager.scheduleInstallAlarm()
            // The 2 AM install runs unattended — secure any needed permission now.
            manager.prepareForDeferredInstall(activity)
            Log.i(TAG, "Update deferred — install armed for ${UpdateBackend.INSTALL_HOUR}:00")
        } else {
            manager.snoozeUntilMillis = System.currentTimeMillis() + SNOOZE_MS
            Log.i(TAG, "Update snoozed for ${SNOOZE_MS / 3_600_000}h")
        }
    }

    /** "Dismiss" — close the banner; Control Center keeps the "Software Update" card. */
    private fun onDismiss() {
        manager.markDismissed()
        dismissBanner()
    }

    // ── Update-details modal ─────────────────────────────────────────

    private fun presentUpdateModal() {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_update_details, null)

        val titleView = view.findViewById<TextView>(R.id.updateModalTitle)
        val versionView = view.findViewById<TextView>(R.id.updateModalVersion)
        val descView = view.findViewById<TextView>(R.id.updateModalDescription)
        val installBtn = view.findViewById<Button>(R.id.updateModalInstall)
        val laterBtn = view.findViewById<Button>(R.id.updateModalLater)
        val dismissBtn = view.findViewById<Button>(R.id.updateModalDismiss)

        // Release metadata when we have it, branded fallback when we do not. These WERE the
        // layout's hardcoded strings, which made them look like design-time placeholders — they
        // are not: with no releaseTitle/releaseDescription they are exactly what the user reads,
        // and they said "Dashie" on a Chickadee device.
        titleView.text = manager.releaseTitle ?: branded(R.string.update_available_title)
        manager.availableVersionName?.let { versionView.text = "Updating to v$it" }
            ?: run { versionView.visibility = View.GONE }
        descView.text = manager.releaseDescription ?: branded(R.string.update_available_body)

        laterBtn.text = if (manager.supportsDeferredInstall) "Tonight" else "Later"

        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        installBtn.setOnClickListener {
            dialog.dismiss()
            onInstallNow()
        }
        laterBtn.setOnClickListener {
            dialog.dismiss()
            onLater()
        }
        dismissBtn.setOnClickListener {
            dialog.dismiss()
            onDismiss()
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        installBtn.requestFocus()
        Log.i(TAG, "Update modal shown")
    }

    // ── Bottom status banner (download / install in progress) ─────────

    private fun downloadingText(): String {
        val v = manager.availableVersionName
        return if (v != null) "Downloading update — v$v" else "Downloading update"
    }

    private fun installingText(): String {
        val v = manager.availableVersionName
        val base = if (v != null) "Installing update — v$v" else "Installing update"
        return if (needsManualReopen) "$base. Reopen from your Fire TV home screen when done." else base
    }

    /**
     * A user-facing string with the product name filled in.
     *
     * The brand belongs in ONE resource (`@string/brand_name`, overridden per source set) with
     * every sentence taking it as a format argument. The alternative — a per-source-set copy of
     * each sentence — drifts silently the moment the copy is edited on one side, and no
     * compiler or lint can see it.
     */
    private fun branded(resId: Int): String =
        activity.getString(resId, activity.getString(R.string.brand_name))

    private fun showOrUpdateStatusBanner(text: String) {
        activity.runOnUiThread {
            val overlay = activity.findViewById<FrameLayout>(R.id.overlayContainer) ?: return@runOnUiThread
            val existing = statusBannerView
            if (existing != null) {
                existing.findViewById<TextView>(R.id.updateStatusText)?.text = text
                return@runOnUiThread
            }
            val banner = LayoutInflater.from(activity)
                .inflate(R.layout.banner_update_status, null)
            banner.findViewById<TextView>(R.id.updateStatusText)?.text = text
            // Dismiss pill — hides the banner but doesn't cancel the underlying
            // download/install. CC card still shows the same state, and the
            // PackageInstaller callback will still react to cancel/finish.
            val dismissBtn = banner.findViewById<Button>(R.id.updateStatusDismiss)
            dismissBtn?.setOnClickListener { dismissStatusBanner() }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (24 * activity.resources.displayMetrics.density).toInt()
            }
            overlay.addView(banner, params)
            statusBannerView = banner
            // Default focus on the dismiss pill so TV d-pad has something
            // to land on (the status banner claims d-pad while visible).
            dismissBtn?.requestFocus()
            Log.i(TAG, "Status banner shown: $text")
        }
    }

    /**
     * D-pad handling for the bottom status banner. The kiosk routes
     * all d-pad input to JS, so the native banner can't rely on
     * Android focus traversal — this is called from
     * [MainActivity.dispatchKeyEvent] (mirrors [handleBannerDpadKey]).
     * Returns true (key consumed) only while the status banner is
     * showing AND a directional / select key is the input — Back is
     * NOT consumed so the user can still navigate away normally.
     */
    fun handleStatusBannerDpadKey(keyCode: Int): Boolean {
        val banner = statusBannerView ?: return false
        val dismissBtn = banner.findViewById<Button>(R.id.updateStatusDismiss) ?: return false
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dismissBtn.performClick()
                true
            }
            // Single button — swallow d-pad direction keys so they
            // don't leak to the dashboard while the banner is up.
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                dismissBtn.requestFocus()
                true
            }
            else -> false
        }
    }

    private fun dismissStatusBanner() {
        activity.runOnUiThread {
            statusBannerView?.let { (it.parent as? FrameLayout)?.removeView(it) }
            statusBannerView = null
        }
    }
}
