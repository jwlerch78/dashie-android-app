package com.dashieapp.Dashie.sidebar

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.QrCodeGenerator

/**
 * Native Kotlin dialog for the hamburger menu's "Connect to Mobile" entry.
 *
 * Shows a QR pointing the user's phone at the Dashie web app
 * (`BuildConfig.DASHIE_URL`). The phone signs in normally on landing —
 * Google one-tap is typically two phone-side taps. No one-time tokens,
 * no backend plumbing — keeping this MVP lean per johnlerch (2026-05-05).
 *
 * Per-flavor URL:
 *   local   → https://local.dashieapp.com
 *   staging → https://dev.dashieapp.com
 *   prod    → https://app.dashieapp.com
 *
 * QR is rendered in the Dashie brand orange (#FF9500) to match other
 * QR surfaces in the app (Apple Calendar prompt, etc.). Cloned UI from
 * VoiceLicenseUI.showPurchaseDialog but stripped down — no Open in
 * Browser (TV browsers are bad UX), no Check License, no manual-key
 * path. Just QR + URL caption + Cancel.
 */
class ConnectMobileDialog(private val activity: Activity) {

    companion object {
        private const val TAG = "ConnectMobileDialog"
        private const val QR_SIZE_PX = 512
        private val DASHIE_ORANGE = 0xFFFF9500.toInt()
    }

    /** Build the destination URL — the mobile management console for the
     *  user's flavor. Per-flavor base host + `/console` path. */
    fun getConsoleUrl(): String = "${BuildConfig.DASHIE_URL.trimEnd('/')}/console"

    /** The mobile dashboard root for the user's flavor (no `/console`).
     *  Used by "Manage account", which sends the owner to the mobile site
     *  (Settings → Account) to manage/delete their account from their phone. */
    fun getDashboardUrl(): String = BuildConfig.DASHIE_URL.trimEnd('/')

    /**
     * Show the "Manage your account" variant — a QR to the mobile dashboard
     * (where the owner opens Settings → Account to manage or delete their
     * account, with a 15-day recoverable grace), plus the console URL offered
     * as a text alternative.
     *
     * Account management is intentionally NOT possible on the shared tablet —
     * this routes it to the owner's own phone. Used by the tablet Settings
     * "Manage account" row and the trial-expired modal.
     */
    fun showManageAccount(): Boolean {
        val consoleUrl = getConsoleUrl()
            .removePrefix("https://").removePrefix("http://")
        return show(
            titleOverride = "Manage your account",
            subtitleOverride = "Scan with your phone to open Dashie, then go to " +
                "Settings → Account to manage or delete your account. You can " +
                "also manage it at $consoleUrl.",
            urlOverride = getDashboardUrl()
        )
    }

    /**
     * Show the dialog. Returns true if shown, false if the activity is in a
     * bad state.
     *
     * @param titleOverride    optional replacement for the default
     *   "Manage Dashie on your phone" title — lets callers like "Manage
     *   Subscription" reuse the same QR with their own copy.
     * @param subtitleOverride optional replacement for the default subtitle.
     * @param urlOverride      optional replacement for the default `/console`
     *   destination (and the QR encoding) — e.g. "Manage account" points at
     *   the mobile dashboard root instead.
     */
    fun show(
        titleOverride: String? = null,
        subtitleOverride: String? = null,
        urlOverride: String? = null
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Activity not in a state to show dialog")
            return false
        }

        val url = urlOverride ?: getConsoleUrl()
        val displayUrl = url.removePrefix("https://").removePrefix("http://")

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_connect_mobile, null)
        val qrImage = dialogView.findViewById<ImageView>(R.id.imageQrCode)
        val urlText = dialogView.findViewById<TextView>(R.id.textUrl)
        val cancelButton = dialogView.findViewById<Button>(R.id.buttonCancel)

        urlText?.text = displayUrl
        titleOverride?.let { dialogView.findViewById<TextView>(R.id.dialogTitle)?.text = it }
        subtitleOverride?.let { dialogView.findViewById<TextView>(R.id.dialogSubtitle)?.text = it }

        val qrBitmap = QrCodeGenerator.generateQrCode(
            url = url,
            size = QR_SIZE_PX,
            foregroundColor = DASHIE_ORANGE
        )
        if (qrBitmap != null) {
            qrImage?.setImageBitmap(qrBitmap)
        } else {
            // QR generation failed — hide the image, leave the URL caption so
            // the user can still type it in. Rare (only happens on encoder
            // exceptions, which shouldn't fire for a static https URL).
            qrImage?.visibility = View.GONE
            Log.w(TAG, "QR generation returned null for $url")
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.let { w ->
            w.setBackgroundDrawableResource(android.R.color.transparent)
            // Explicit dim — without it the dialog renders flat on top of
            // the content behind it (Settings page / dashboard) with no
            // visual separation, looking blended in. Mirrors the sign-out
            // and trial-expired dialogs.
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.6f)
        }

        cancelButton?.setOnClickListener { dialog.dismiss() }

        dialog.show()
        // Default focus to Cancel — the only action on a TV remote.
        cancelButton?.post { cancelButton.requestFocus() }

        Log.i(TAG, "Showed Connect to Mobile dialog (url=$url)")
        return true
    }
}
