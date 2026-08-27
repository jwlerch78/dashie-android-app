package com.dashieapp.Dashie.halite.widgets

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Owns the per-widget [CalendarReauthPill] overlays — one pill per
 * calendar/agenda widget visible on the dashboard. Pills appear only
 * when [setCount] is > 0 (some Google/Microsoft account has had its
 * refresh token terminally revoked) AND the user hasn't dismissed the
 * pill for the current session.
 *
 * Wiring:
 *  - JS (layout-canvas) reports each calendar/agenda slot's bounds via
 *    [setBounds]. JS calls [removeBounds] when a slot's active widget
 *    changes away from calendar/agenda.
 *  - JS (widget-data-manager) reports [setCount] + sole email each
 *    time calendar data refreshes.
 *
 * On pill click we show an AlertDialog (reusing R.layout.dialog_confirm
 * to match the rest of the app's dialog style). The Fix button opens
 * Kotlin SettingsActivity directly to the calendar page; Dismiss hides
 * the pills until the next process start.
 */
class CalendarReauthOverlayManager(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val visibilityGate: NativeWidgetVisibilityGate? = null,
    private val webViewProvider: (() -> android.webkit.WebView?)? = null,
    private val jsBridgeProvider: (() -> com.dashieapp.Dashie.webview.DashieJSBridge?)? = null
) {
    companion object {
        private const val TAG = "CalReauthOverlay"
        private const val INSET_DP = 8f
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // slotId -> bounds + pill view
    private data class SlotEntry(
        var x: Int, var y: Int, var w: Int, var h: Int,
        var pill: CalendarReauthPill?
    )

    private val slots = mutableMapOf<String, SlotEntry>()
    private var count: Int = 0
    private var soleEmail: String = ""
    private var dismissedThisSession: Boolean = false

    fun setBounds(slotId: String, x: Int, y: Int, w: Int, h: Int) {
        mainHandler.post {
            val entry = slots.getOrPut(slotId) { SlotEntry(x, y, w, h, null) }
            entry.x = x; entry.y = y; entry.w = w; entry.h = h
            ensureAndPositionPill(slotId, entry)
        }
    }

    fun removeBounds(slotId: String) {
        mainHandler.post {
            val entry = slots.remove(slotId) ?: return@post
            entry.pill?.let {
                visibilityGate?.unregister(it)
                rootContainer.removeView(it)
            }
        }
    }

    fun setCount(count: Int, soleEmail: String) {
        mainHandler.post {
            val countChanged = this.count != count
            this.count = count
            this.soleEmail = soleEmail
            // When the count drops to 0 we forget any session-dismiss so
            // the pill is free to reappear on the next failure cycle.
            if (count == 0 && countChanged) {
                dismissedThisSession = false
            }
            for ((slotId, entry) in slots) {
                ensureAndPositionPill(slotId, entry)
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            for (entry in slots.values) {
                entry.pill?.let {
                    visibilityGate?.unregister(it)
                    rootContainer.removeView(it)
                }
            }
            slots.clear()
        }
    }

    // -------------------------------------------------------------------------

    private fun ensureAndPositionPill(slotId: String, entry: SlotEntry) {
        val shouldShow = count > 0 && !dismissedThisSession
        if (!shouldShow) {
            entry.pill?.let {
                visibilityGate?.unregister(it)
                rootContainer.removeView(it)
                entry.pill = null
            }
            return
        }
        val density = activity.resources.displayMetrics.density
        val pill = entry.pill ?: CalendarReauthPill(activity).apply {
            // Start INVISIBLE so the visibility gate can evaluate state
            // before the first layout pass — avoids a 1-frame flash on
            // page reload between addView() and gate.register().
            visibility = View.INVISIBLE
            // Elevation 0 — addView order handles z-ordering inside
            // widgetContainer; positive elevation would punch above the
            // screensaver. See activity_main.xml elevation ladder.
            onClickAction = { showFixOrDismissDialog() }
            rootContainer.addView(this, FrameLayout.LayoutParams(0, 0))
            // Register with the gate as FULL_ONLY — the reauth pill is a
            // dashboard-only surface (no calendar widgets exist in kiosk
            // mode or on the login screen). Gate auto-flips visibility
            // based on UI mode.
            visibilityGate?.register(
                this, NativeWidgetVisibilityGate.WidgetKind.FULL_ONLY
            )
            entry.pill = this
        }

        // Bottom-left corner of the slot, with the same inset the rotator
        // pills use so the two visually agree if they share a slot.
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        pill.measure(unspec, unspec)
        val pw = pill.measuredWidth
        val ph = pill.measuredHeight
        val insetPx = dp(INSET_DP)

        pill.layoutParams = FrameLayout.LayoutParams(pw, ph).apply {
            leftMargin = entry.x + insetPx
            topMargin = entry.y + entry.h - ph - insetPx
        }
        pill.bringToFront()
    }

    private fun showFixOrDismissDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Sign-in Required"
        // 1 broken account → name it. 2+ → just give the count and let the
        // user navigate to settings to see the per-account list.
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = when {
            count == 1 && soleEmail.isNotEmpty() ->
                "$soleEmail needs to be re-signed in.\n\nCalendars from this account aren't loading until you do."
            count >= 2 ->
                "$count calendar accounts need to be re-signed in.\n\nCalendars from these accounts aren't loading until you do."
            else ->
                "A calendar account needs to be re-signed in."
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).apply {
            text = "Dismiss"
            setOnClickListener {
                dialog.dismiss()
                dismissedThisSession = true
                // Re-evaluate visibility — drops all pills.
                for ((slotId, entry) in slots) {
                    ensureAndPositionPill(slotId, entry)
                }
            }
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).apply {
            text = "Fix"
            setOnClickListener {
                dialog.dismiss()
                openCalendarSettings()
            }
        }

        dialog.show()
    }

    private fun openCalendarSettings() {
        try {
            // SettingsActivity reads calendar data via static refs to MainActivity's
            // WebView + JS bridge. Control Center's onNavigateToSettings populates
            // these before launching; the reauth pill is a parallel entry point and
            // must do the same, otherwise loadCalendarDataFromJs() bails out and the
            // page renders empty ("0 of 0 active", no re-sign-in row).
            webViewProvider?.invoke()?.let { wv ->
                com.dashieapp.Dashie.halite.settings.SettingsActivity.webViewRef =
                    java.lang.ref.WeakReference(wv)
            }
            jsBridgeProvider?.invoke()?.let { bridge ->
                com.dashieapp.Dashie.halite.settings.SettingsActivity.jsBridgeRef = bridge
            }
            val intent = Intent(activity, com.dashieapp.Dashie.halite.settings.SettingsActivity::class.java)
            intent.putExtra("navigate_to", "calendar")
            activity.startActivity(intent)
            activity.overridePendingTransition(0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch SettingsActivity", e)
        }
    }

    private fun dp(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, activity.resources.displayMetrics
    ).toInt()
}
