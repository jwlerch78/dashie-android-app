package com.dashieapp.Dashie.halite.settings

import android.app.Activity
import android.app.AlertDialog
import android.util.Log
// Note: explicitly using android.app.AlertDialog (not androidx.appcompat) so
// DialogHelper.applyImmersiveModeToDialog accepts it.
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.QrCodeGenerator
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper

/**
 * Show the "Add Apple Calendar — do this on your phone" dialog with a QR
 * code linking to dashieapp.com/apple. Apple's 16-char app-specific
 * password is painful to enter on a d-pad and tedious on a tablet on-screen
 * keyboard, so TVs always go through this prompt and tablets get an escape
 * hatch.
 *
 * @param allowProceed When true, surfaces a "Continue here anyway" button.
 * @param onProceed Invoked if (and only if) the user clicks the proceed
 *                  button. Not invoked when the dialog is dismissed.
 */
fun showAppleCalendarQrPrompt(
    activity: Activity,
    allowProceed: Boolean,
    onProceed: () -> Unit
) {
    val dialogView = activity.layoutInflater.inflate(
        R.layout.dialog_apple_calendar_qr, null
    )

    val qrImage = dialogView.findViewById<ImageView>(R.id.appleQrImage)
    val urlText = dialogView.findViewById<android.widget.TextView>(R.id.appleQrUrl)
    val doneButton = dialogView.findViewById<Button>(R.id.appleQrDoneButton)
    val proceedButton = dialogView.findViewById<Button>(R.id.appleQrProceedButton)

    // Match the QR URL to the build flavor so dev/local devices don't direct
    // the user to a host that doesn't have /apple yet (or has a stale copy).
    val baseUrl = com.dashieapp.Dashie.BuildConfig.DASHIE_URL
    val appleUrl = "$baseUrl/apple"
    val displayUrl = appleUrl
        .removePrefix("https://")
        .removePrefix("http://")
    urlText.text = displayUrl

    val orange = 0xFFFF9500.toInt()
    val qrBitmap = QrCodeGenerator.generateQrCode(
        url = appleUrl,
        size = 512,
        foregroundColor = orange
    )
    if (qrBitmap != null) {
        qrImage.setImageBitmap(qrBitmap)
    } else {
        Log.w("AppleCalendarQrPrompt", "QR generation failed")
    }

    val dialog = AlertDialog.Builder(activity)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    doneButton.setOnClickListener { dialog.dismiss() }

    if (allowProceed) {
        proceedButton.visibility = android.view.View.VISIBLE
        proceedButton.setOnClickListener {
            dialog.dismiss()
            onProceed()
        }
    }

    dialog.show()
    DialogHelper.applyImmersiveModeToDialog(dialog)
    doneButton.requestFocus()
}
