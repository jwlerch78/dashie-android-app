package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsTrace

/**
 * Dismiss a dialog without crashing if its window has already been torn
 * down (e.g. the hosting Activity was recreated after a WebView renderer
 * crash while a postDelayed Runnable still held a reference). Should be
 * used by any delayed / async dismiss path — immediate button handlers
 * don't need it.
 *
 * Root cause seen on Lenovo StarView 2026-04-17: Activity recreated 4 min
 * before a Handler Runnable fired `dialog.dismiss()`, throwing
 * `DecorView not attached to window manager` from WindowManagerGlobal.
 */
fun android.app.Dialog.safeDismiss() {
    runCatching {
        if (isShowing) dismiss()
    }
}

/**
 * Helper utilities for dialog management in the sidebar.
 * Provides immersive mode support and other common dialog functionality.
 */
object DialogHelper {

    /**
     * Set default focus on the Cancel (negative) button for simple confirm dialogs.
     * Only applies focus on devices without a touchscreen (TV/D-pad devices).
     * Call this after dialog.show() for dialogs using dialog_confirm.xml or dialog_simple_message.xml.
     * This improves D-pad navigation UX by starting focus on the safer Cancel option.
     *
     * @param dialogView The inflated dialog view containing buttonNegative
     */
    fun setDefaultFocusOnCancel(dialogView: View) {
        // Only set focus on non-touchscreen devices (TV, devices with D-pad only)
        val hasTouchscreen = dialogView.context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

        if (!hasTouchscreen) {
            dialogView.findViewById<Button>(R.id.buttonNegative)?.let { cancelButton ->
                cancelButton.isFocusable = true
                cancelButton.requestFocus()
            }
        }
    }

    /**
     * Set default focus on the OK (positive) button for single-button info /
     * error / acknowledgment dialogs. Without this, TV users have to press
     * d-pad DOWN before SELECT will dismiss the dialog. Only applies on
     * non-touchscreen devices.
     *
     * @param dialogView The inflated dialog view containing buttonPositive
     */
    fun setDefaultFocusOnConfirm(dialogView: View) {
        val hasTouchscreen = dialogView.context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

        if (!hasTouchscreen) {
            dialogView.findViewById<Button>(R.id.buttonPositive)?.let { okButton ->
                okButton.isFocusable = true
                okButton.requestFocus()
            }
        }
    }

    /**
     * Apply d-pad focus highlighting to a border-style button.
     * Manually swaps the background drawable on focus change to bypass
     * MaterialComponents theme backgroundTint interference that prevents
     * the XML drawable's focused state from rendering.
     *
     * Call this after finding the button and setting its click listener.
     *
     * @param button The Button using @drawable/button_border background
     * @param dialogStyle If true, uses bold white border (matches positive button). If false, uses sidebar-style orange border.
     */
    fun applyBorderButtonFocusHighlight(button: Button, dialogStyle: Boolean = false) {
        val density = button.resources.displayMetrics.density
        button.backgroundTintList = null
        button.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val d = GradientDrawable()
                if (dialogStyle) {
                    d.setColor(0xFFFFAA33.toInt())  // Solid lighter orange (matches positive button)
                    d.setStroke((3 * density).toInt(), Color.WHITE)
                } else {
                    d.setColor(0x80FF9500.toInt())  // 50% opacity orange (matches sidebar highlight)
                    d.setStroke((3 * density).toInt(), 0xFFFF9500.toInt())
                }
                d.cornerRadius = 4 * density
                v.background = d
                v.backgroundTintList = null
            } else {
                v.setBackgroundResource(R.drawable.button_border)
                v.backgroundTintList = null
            }
        }
    }

    /**
     * Apply immersive mode flags to a dialog window to prevent nav bar from showing.
     * Call this after dialog.show() to keep the nav bar hidden.
     * Also sets up a listener to re-hide bars if they appear due to interaction.
     */
    @Suppress("DEPRECATION")
    fun applyImmersiveModeToDialog(dialog: AlertDialog) {
        SettingsTrace.shownFromCaller("Dialog/AlertDialog")
        dialog.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(
                        WindowInsets.Type.statusBars() or
                        WindowInsets.Type.navigationBars()
                    )
                    // Use BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE so bars auto-hide
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }

                // Set up listener to re-hide bars if they appear
                window.decorView.setOnApplyWindowInsetsListener { view, insets ->
                    val navVisible = insets.isVisible(WindowInsets.Type.navigationBars())
                    if (navVisible) {
                        // Re-hide immediately
                        view.postDelayed({
                            window.insetsController?.hide(
                                WindowInsets.Type.statusBars() or
                                WindowInsets.Type.navigationBars()
                            )
                        }, 100)
                    }
                    view.onApplyWindowInsets(insets)
                }
            } else {
                // Android 10 and below
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )

                // Set up listener to re-hide bars if they appear (legacy)
                window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                    if ((visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                        window.decorView.postDelayed({
                            window.decorView.systemUiVisibility = (
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            )
                        }, 100)
                    }
                }
            }
        }
    }
}
