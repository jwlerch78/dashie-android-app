package com.dashieapp.Dashie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * Handles device boot and wake events to auto-launch Dashie.
 *
 * - BOOT_COMPLETED: Launches after device cold boot (controlled by startOnBoot preference)
 * - USER_PRESENT: Launches when device wakes from sleep (controlled by launchOnWake preference)
 *
 * Note: SCREEN_ON handling is done dynamically in DashieApiService since it cannot
 * be registered in the manifest on newer Android versions.
 *
 * This provides the same functionality as the "Launch on Boot" app from ITVlab.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DashieBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = HalitePreferences(context)

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                if (prefs.sleep.startOnBoot) {
                    Log.i(TAG, "📱 Boot completed (${intent.action}) - launching Dashie")
                    launchService(context)
                } else {
                    Log.i(TAG, "📱 Boot completed - NOT launching (startOnBoot disabled)")
                }
            }

            Intent.ACTION_USER_PRESENT -> {
                if (prefs.sleep.launchOnWake) {
                    Log.i(TAG, "👁️ Device wake detected - launching Dashie")
                    launchService(context)
                } else {
                    Log.d(TAG, "👁️ Device wake detected - NOT launching (launchOnWake disabled)")
                }
            }
        }
    }

    private fun launchService(context: Context) {
        // Direct activity launch from BOOT_COMPLETED/USER_PRESENT.
        // These broadcasts provide a temporary exemption to start activities from background.
        // Android 15+ restricts shortService FGS from BOOT_COMPLETED, so a FGS fallback
        // isn't viable on modern OS versions anyway.
        launchActivityDirect(context)
    }

    /**
     * Direct activity launch. BOOT_COMPLETED and USER_PRESENT broadcasts provide a
     * temporary exemption to start activities from background.
     */
    @Suppress("DEPRECATION")
    private fun launchActivityDirect(context: Context) {
        try {
            // Wake up the screen
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "DashieLite:BootReceiverWakeLock"
            )
            wakeLock.acquire(10000)

            // Create the launch intent
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("launched_from_boot", true)
            }

            context.startActivity(launchIntent)
            Log.i(TAG, "✓ Direct activity launch succeeded")

            // Release wake lock after a moment
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            }, 5000)
        } catch (e: Exception) {
            Log.e(TAG, "Direct activity launch failed: ${e.message}")
        }
    }
}
