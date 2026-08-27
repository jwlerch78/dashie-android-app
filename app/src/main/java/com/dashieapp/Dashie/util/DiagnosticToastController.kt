package com.dashieapp.Dashie.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast

/**
 * Controls visibility of diagnostic toasts throughout the app.
 *
 * By default, toasts are DISABLED to avoid clutter on the screen.
 * They can be enabled via JavaScript command from the web console:
 *
 *   DashieNative.enableDiagnosticToasts()
 *   DashieNative.disableDiagnosticToasts()
 *   DashieNative.isDiagnosticToastsEnabled()
 *
 * This allows developers to toggle toast visibility for debugging without
 * recompiling the app.
 */
object DiagnosticToastController {
    private const val TAG = "DiagnosticToastController"
    private const val PREFS_NAME = "dashie_diagnostics"
    private const val KEY_TOASTS_ENABLED = "toasts_enabled"

    private var context: Context? = null
    private var preferences: SharedPreferences? = null

    /**
     * Initialize the controller with app context
     * Must be called before using any toast methods
     */
    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        preferences = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.d(TAG, "DiagnosticToastController initialized. Toasts enabled: ${isEnabled()}")
    }

    /**
     * Check if diagnostic toasts are currently enabled
     */
    fun isEnabled(): Boolean {
        return preferences?.getBoolean(KEY_TOASTS_ENABLED, false) ?: false
    }

    /**
     * Enable diagnostic toasts
     */
    fun enable() {
        preferences?.edit()?.putBoolean(KEY_TOASTS_ENABLED, true)?.apply()
        Log.i(TAG, "✅ Diagnostic toasts ENABLED")
        showToast("✅ Diagnostic toasts enabled")
    }

    /**
     * Disable diagnostic toasts
     */
    fun disable() {
        // Show one final toast to confirm disabling
        showToast("❌ Diagnostic toasts disabled")
        preferences?.edit()?.putBoolean(KEY_TOASTS_ENABLED, false)?.apply()
        Log.i(TAG, "❌ Diagnostic toasts DISABLED")
    }

    /**
     * Show a toast message (only if toasts are enabled)
     * This is the main method that should be used throughout the app
     */
    fun showToast(message: String) {
        if (isEnabled()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                context?.let {
                    Toast.makeText(it, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Force show a toast regardless of enabled state
     * Use sparingly - only for critical errors or status changes
     */
    fun forceShowToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            context?.let {
                Toast.makeText(it, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
