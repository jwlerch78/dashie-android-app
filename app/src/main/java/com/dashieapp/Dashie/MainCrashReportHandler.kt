package com.dashieapp.Dashie

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.halite.diagnostics.CrashHandler
import com.dashieapp.Dashie.halite.diagnostics.CrashReportUploader
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles crash report detection, display, and submission.
 *
 * On startup, shows a compact auto-dismissing banner instead of a full dialog.
 * The full crash report dialog can be opened from the banner or from Performance settings.
 */
class MainCrashReportHandler(
    private val activity: Activity,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CrashReportHandler"
        private const val BANNER_AUTO_DISMISS_MS = 60_000L
        private const val PREFS_NAME = "crash_banner_prefs"
        private const val KEY_BANNER_SHOWN_FOR = "banner_shown_for"

        /**
         * Show the full crash report dialog on any Activity.
         * Used by both the instance (banner "View" button) and settings page.
         */
        fun showCrashReportDialog(activity: Activity, scope: CoroutineScope) {
            val crashReport = CrashHandler.getPendingCrashReport(activity) ?: return

            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_crash_report, null)

            val textCrashPreview = dialogView.findViewById<TextView>(R.id.textCrashPreview)
            val buttonDismiss = dialogView.findViewById<Button>(R.id.buttonDismiss)
            val buttonCopy = dialogView.findViewById<Button>(R.id.buttonCopy)
            val buttonSend = dialogView.findViewById<Button>(R.id.buttonSend)

            // Preview text (truncated for display, full report sent)
            val previewLines = crashReport.lines().take(50).joinToString("\n")
            val preview = if (crashReport.lines().size > 50) {
                "$previewLines\n\n... (${crashReport.lines().size - 50} more lines)"
            } else {
                previewLines
            }
            textCrashPreview.text = preview

            val dialog = AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            buttonDismiss.setOnClickListener {
                CrashHandler.clearPendingCrashReports(activity)
                DiagnosticBuffer.info("CRASH", "User dismissed crash report")
                dialog.dismiss()
            }

            buttonCopy.setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Crash Report", crashReport))
                Toast.makeText(activity, "Crash report copied to clipboard", Toast.LENGTH_SHORT).show()
                CrashHandler.clearPendingCrashReports(activity)
                dialog.dismiss()
            }

            buttonSend.setOnClickListener {
                buttonSend.isEnabled = false
                buttonSend.text = "Sending..."
                sendCrashReportToServer(activity, scope, crashReport, dialog, buttonSend)
            }

            dialog.show()
            DialogHelper.applyImmersiveModeToDialog(dialog)
            buttonSend.requestFocus()
        }

        /**
         * Send crash report to the server. The network upload is shared with
         * the silent abnormal-exit reporter via [CrashReportUploader]; this
         * method only owns the dialog/Toast UI feedback.
         */
        private fun sendCrashReportToServer(
            activity: Activity,
            scope: CoroutineScope,
            crashReport: String,
            dialog: AlertDialog,
            sendButton: Button
        ) {
            scope.launch(Dispatchers.IO) {
                val success = CrashReportUploader.upload(activity, crashReport)
                activity.runOnUiThread {
                    if (success) {
                        Toast.makeText(activity, "Crash report sent. Thank you!", Toast.LENGTH_SHORT).show()
                        CrashHandler.clearPendingCrashReports(activity)
                        DiagnosticBuffer.info("CRASH", "Crash report sent successfully")
                        dialog.dismiss()
                    } else {
                        Toast.makeText(activity, "Failed to send crash report", Toast.LENGTH_SHORT).show()
                        DiagnosticBuffer.error("CRASH", "Failed to send crash report")
                        sendButton.isEnabled = true
                        sendButton.text = "Send"
                    }
                }
            }
        }
    }

    // Banner state
    private var bannerView: View? = null
    private var currentCrashFilename: String? = null
    private val bannerHandler = Handler(Looper.getMainLooper())

    /**
     * Check for pending crash reports from previous sessions.
     * Shows a compact banner instead of a full dialog.
     */
    fun checkForPendingCrashReports() {
        val reports = CrashHandler.getAllPendingCrashReports(activity)
        if (reports.isEmpty()) return

        val latestFilename = reports.first().filename
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alreadyShownFor = prefs.getString(KEY_BANNER_SHOWN_FOR, null)

        if (latestFilename == alreadyShownFor) {
            DiagnosticBuffer.info("CRASH", "Banner already shown for $latestFilename, skipping")
            return
        }

        PersistentLog.warn("CRASH", "New crash report found: $latestFilename")
        DiagnosticBuffer.warn("CRASH", "New crash report: $latestFilename")

        // Show banner after a short delay to let UI initialize.
        Handler(Looper.getMainLooper()).postDelayed({
            showCrashBanner(latestFilename)
        }, 2000)
    }

    /**
     * Show a compact crash notification banner at the bottom of the screen.
     * Auto-dismisses after 60 seconds. "View" opens the full dialog.
     * "Dismiss" hides the banner but keeps crash data for later submission.
     */
    private fun showCrashBanner(crashFilename: String) {
        val overlayContainer = activity.findViewById<FrameLayout>(R.id.overlayContainer)
        if (overlayContainer == null) {
            Log.w(TAG, "overlayContainer not found, falling back to full dialog")
            markBannerShown(crashFilename)
            showCrashReportDialog(activity, scope)
            return
        }

        val banner = LayoutInflater.from(activity).inflate(R.layout.banner_crash_notification, null)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = (24 * activity.resources.displayMetrics.density).toInt()
        }

        val dismissButton = banner.findViewById<Button>(R.id.bannerDismiss)
        val viewButton = banner.findViewById<Button>(R.id.bannerView)

        dismissButton.setOnClickListener {
            markBannerShown(crashFilename)
            dismissBanner()
            DiagnosticBuffer.info("CRASH", "User dismissed crash banner (data preserved)")
        }

        viewButton.setOnClickListener {
            markBannerShown(crashFilename)
            dismissBanner()
            showCrashReportDialog(activity, scope)
        }

        // The banner lives in the Activity window on top of the WebView, which holds focus
        // and keeps the app in touch mode. A plain Button can't take focus in touch mode, so
        // requestFocus() silently fails and the remote can't reach the buttons (TV). Mark them
        // focusable-in-touch-mode so focus can move here; the d-pad then exits touch mode and
        // navigates normally. MainInputHandler.isNativeOverlayShowing() yields d-pad to this
        // overlay while the banner is up so the keys actually reach these buttons.
        viewButton.isFocusableInTouchMode = true
        dismissButton.isFocusableInTouchMode = true

        // Drive LEFT/RIGHT navigation explicitly. A Button consumes CENTER/ENTER (so Select
        // already works) but NOT LEFT/RIGHT, which would fall through to the framework focus
        // search — unreliable here because the WebView keeps the app in touch mode, so the
        // arrow either does nothing or escapes to the sidebar. Toggle focus between the two
        // buttons ourselves; let CENTER/ENTER fall through to the button's click.
        val bannerKeyListener = android.view.View.OnKeyListener { _, keyCode, ev ->
            if (ev.action != android.view.KeyEvent.ACTION_DOWN) return@OnKeyListener false
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (viewButton.hasFocus()) dismissButton.requestFocus() else viewButton.requestFocus()
                    true
                }
                else -> false
            }
        }
        viewButton.setOnKeyListener(bannerKeyListener)
        dismissButton.setOnKeyListener(bannerKeyListener)

        overlayContainer.addView(banner, params)
        bannerView = banner
        currentCrashFilename = crashFilename

        // Auto-dismiss after 60 seconds — marks as shown so it won't re-appear
        bannerHandler.postDelayed({
            markBannerShown(crashFilename)
            dismissBanner()
        }, BANNER_AUTO_DISMISS_MS)

        // Grab focus after layout so requestFocus() actually lands on the button.
        viewButton.post {
            val ok = viewButton.requestFocus()
            DiagnosticBuffer.info("CRASH", "Crash banner View button requestFocus → $ok")
        }
        DiagnosticBuffer.info("CRASH", "Showing crash notification banner (auto-dismiss in 60s)")
    }

    /**
     * True while the crash notification banner is showing in the Activity's overlay
     * container. MainInputHandler reads this (via its Callbacks) to yield d-pad to native
     * focus so the banner's View/Dismiss buttons are reachable by remote on TV devices.
     */
    fun isBannerShowing(): Boolean = bannerView != null

    private fun markBannerShown(crashFilename: String) {
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BANNER_SHOWN_FOR, crashFilename).commit()
    }

    /**
     * Remove the crash banner from the overlay.
     */
    private fun dismissBanner() {
        bannerView?.let { view ->
            (view.parent as? FrameLayout)?.removeView(view)
            bannerView = null
            bannerHandler.removeCallbacksAndMessages(null)
        }
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        dismissBanner()
    }
}
