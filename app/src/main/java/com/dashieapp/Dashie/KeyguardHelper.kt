package com.dashieapp.Dashie

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Helper class for Keyguard operations that require API 26+
 *
 * This is in a separate file to avoid ClassNotFoundException on older devices.
 * The class is only loaded when explicitly called from version-gated code.
 */
object KeyguardHelper {
    private const val TAG = "KeyguardHelper"

    /**
     * Request keyguard dismissal on API 26+
     * Must only be called after checking Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
     *
     * If the keyguard is secure (PIN/password/pattern), this will NOT attempt dismissal
     * to avoid prompting the user for authentication. The app will show over the lockscreen instead.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun requestDismissKeyguard(activity: Activity) {
        val keyguardManager = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

        // Check if keyguard is secure (has PIN/password/pattern)
        if (keyguardManager.isKeyguardSecure) {
            Log.i(TAG, "🔧 Halite: Keyguard is secure (PIN/password set) - skipping dismiss to avoid authentication prompt")
            Log.i(TAG, "🔧 Halite: App will show over lockscreen using FLAG_SHOW_WHEN_LOCKED")
            return
        }

        // Only attempt dismissal if keyguard is NOT secure (no PIN)
        if (keyguardManager.isKeyguardLocked) {
            Log.i(TAG, "🔧 Halite: Keyguard is not secure - attempting dismissal")
            keyguardManager.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    Log.i(TAG, "🔧 Halite: Keyguard dismissed successfully")
                }
                override fun onDismissCancelled() {
                    Log.w(TAG, "🔧 Halite: Keyguard dismiss cancelled")
                }
                override fun onDismissError() {
                    Log.e(TAG, "🔧 Halite: Keyguard dismiss error")
                }
            })
        }
    }
}
