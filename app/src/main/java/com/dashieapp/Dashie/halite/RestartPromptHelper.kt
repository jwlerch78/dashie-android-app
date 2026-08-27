package com.dashieapp.Dashie.halite

import android.app.Activity
import android.app.AlertDialog
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.annotation.LayoutRes
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper

/**
 * Shared "Restart Required" confirmation dialog. Six call sites used to
 * reimplement nearly-identical 30+ line dialog rendering blocks; this
 * consolidates the rendering while leaving the restart strategy itself
 * to the caller (each site uses different mechanics depending on whether
 * it's MainActivity, a separate Activity, or a Fragment).
 */
object RestartPromptHelper {

    fun show(
        activity: Activity,
        message: String,
        title: String = "Restart Required",
        positiveText: String = "Restart Now",
        negativeText: String = "Later",
        @LayoutRes layoutRes: Int = R.layout.dialog_confirm,
        applyImmersive: Boolean = true,
        applyDim: Boolean = false,
        onRestart: () -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(layoutRes, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = title
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = message

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            if (applyDim) {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes?.apply { dimAmount = 0.5f }
            }
        }

        dialogView.findViewById<Button>(R.id.buttonNegative).apply {
            text = negativeText
            setOnClickListener { dialog.dismiss() }
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).apply {
            text = positiveText
            setOnClickListener {
                dialog.dismiss()
                onRestart()
            }
        }

        dialog.show()
        if (applyImmersive) {
            DialogHelper.applyImmersiveModeToDialog(dialog)
        }
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }
}
