package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Paint
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Dashie-styled confirm/info dialogs backed by dialog_confirm.xml, for one-off
 * prompts that would otherwise fall back to the unthemed system AlertDialog.
 * Handles the shared boilerplate: transparent window background, immersive
 * mode, and d-pad focus wiring.
 */
object StyledConfirmDialog {

    /**
     * @param negativeText null hides the negative button (single-button info dialog)
     * @param linkText non-null shows an underlined link-style action below the
     *        buttons (e.g. "Don't ask again"); tapping it dismisses and calls onLink
     * @param onNegative also invoked when the dialog is cancelled (back/outside tap)
     */
    fun show(
        activity: Activity,
        title: String,
        message: String,
        positiveText: String = "OK",
        negativeText: String? = null,
        linkText: String? = null,
        onNegative: () -> Unit = {},
        onLink: () -> Unit = {},
        onPositive: () -> Unit = {},
    ): AlertDialog {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = title
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = message

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { onNegative() }
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val positiveBtn = dialogView.findViewById<Button>(R.id.buttonPositive)
        val negativeBtn = dialogView.findViewById<Button>(R.id.buttonNegative)
        val link = dialogView.findViewById<TextView>(R.id.dialogLink)

        positiveBtn.text = positiveText
        positiveBtn.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }

        if (negativeText == null) {
            negativeBtn.visibility = View.GONE
        } else {
            negativeBtn.text = negativeText
            negativeBtn.setOnClickListener {
                dialog.dismiss()
                onNegative()
            }
        }

        if (linkText != null) {
            link.text = linkText
            link.paintFlags = link.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            link.visibility = View.VISIBLE
            link.setOnClickListener {
                dialog.dismiss()
                onLink()
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)

        // D-pad focus. Do NOT set isFocusableInTouchMode — causes double-tap
        // on touchscreens.
        DialogHelper.applyBorderButtonFocusHighlight(negativeBtn, dialogStyle = true)
        positiveBtn.isFocusable = true
        if (negativeText != null) {
            negativeBtn.isFocusable = true
            positiveBtn.nextFocusLeftId = R.id.buttonNegative
            negativeBtn.nextFocusRightId = R.id.buttonPositive
        }
        if (linkText != null) {
            // Auto focus-search can't reliably cross out of the nested
            // horizontal button row — wire the link in explicitly
            link.isFocusable = true
            positiveBtn.nextFocusDownId = R.id.dialogLink
            negativeBtn.nextFocusDownId = R.id.dialogLink
            link.nextFocusUpId = R.id.buttonPositive
        }
        positiveBtn.post { positiveBtn.requestFocus() }

        return dialog
    }
}
