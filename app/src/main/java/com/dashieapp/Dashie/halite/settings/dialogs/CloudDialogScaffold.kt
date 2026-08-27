package com.dashieapp.Dashie.halite.settings.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.QrCodeGenerator
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper

/**
 * Shared scaffold for the cloud-activation / console-setup dialogs (kiosk
 * cloud/voice onboarding, phase 1). Inflates [R.layout.dialog_cloud_activation],
 * applies the house window styling (transparent rounded card, dim, immersive —
 * same treatment as SettingsDialogHelper / CreditBoundaryUi), and exposes
 * addButton()/showQr() so callers only supply content + actions.
 *
 * Buttons stack vertically (labels are long, e.g. "Create a Dashie account with
 * Google"). The first button added is auto-focused for d-pad.
 */
class CloudDialogScaffold(
    private val activity: Activity,
    title: String,
    message: CharSequence
) {
    private val view = activity.layoutInflater.inflate(R.layout.dialog_cloud_activation, null)
    private val container = view.findViewById<LinearLayout>(R.id.buttonContainer)
    private val qrSection = view.findViewById<LinearLayout>(R.id.qrSection)
    private val dialog: AlertDialog
    private var firstButton: Button? = null

    init {
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message
        dialog = AlertDialog.Builder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }
    }

    /**
     * Add a stacked button. [primary] = orange focal (button_primary); otherwise
     * a bordered secondary. [dismissFirst] closes the dialog before [onClick]
     * runs — pass false for actions that mutate the dialog in place (e.g. the
     * add-credits QR reveal).
     */
    fun addButton(
        label: String,
        primary: Boolean = false,
        dismissFirst: Boolean = true,
        onClick: () -> Unit
    ): Button {
        val density = activity.resources.displayMetrics.density
        val btn = Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setBackgroundResource(if (primary) R.drawable.button_primary else R.drawable.button_border)
            setTextColor(if (primary) Color.WHITE else activity.getColor(R.color.text_secondary))
            if (primary) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (46 * density).toInt()
            ).apply { topMargin = (8 * density).toInt() }
            setOnClickListener {
                if (dismissFirst) dialog.dismiss()
                onClick()
            }
        }
        DialogHelper.applyBorderButtonFocusHighlight(btn, dialogStyle = true)
        container.addView(btn)
        if (firstButton == null) firstButton = btn
        return btn
    }

    /** Show a blue URL line directly under the message (e.g. the add-on repo URL
     *  the user types into Home Assistant). */
    fun setPrimaryUrl(label: String) {
        view.findViewById<TextView>(R.id.textPrimaryUrl).apply {
            text = label
            visibility = View.VISIBLE
        }
    }

    /**
     * Reveal the in-place QR. [intro] renders ABOVE the QR (a lead-in like "For
     * detailed instructions…"); [caption] renders BELOW it (e.g. the add-credits
     * "scan to add credits" line). Either may be null.
     */
    fun showQr(url: String, urlLabel: String, caption: String? = null, intro: String? = null) {
        val qr = QrCodeGenerator.generateQrCode(url, 512, foregroundColor = 0xFFFF9500.toInt())
        view.findViewById<ImageView>(R.id.imageQrCode).setImageBitmap(qr)
        view.findViewById<TextView>(R.id.textQrUrl).text = urlLabel
        view.findViewById<TextView>(R.id.textQrIntro).apply {
            text = intro ?: ""
            visibility = if (intro != null) View.VISIBLE else View.GONE
        }
        view.findViewById<TextView>(R.id.textQrCaption).apply {
            text = caption ?: ""
            visibility = if (caption != null) View.VISIBLE else View.GONE
        }
        qrSection.visibility = View.VISIBLE
    }

    /** Run [action] whenever the dialog is dismissed (button, back, or outside tap). */
    fun setOnDismiss(action: () -> Unit) {
        dialog.setOnDismissListener { action() }
    }

    fun show() {
        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        firstButton?.requestFocus()
    }

    fun dismiss() = dialog.dismiss()
}
