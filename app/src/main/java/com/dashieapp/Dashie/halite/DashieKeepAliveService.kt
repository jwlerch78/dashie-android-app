package com.dashieapp.Dashie.halite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dashieapp.Dashie.MainActivity
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.edition.brandName

/**
 * Minimal foreground service that elevates Dashie's process priority.
 *
 * On aggressive OEM ROMs (Xiaomi/MIUI, Huawei/EMUI, Samsung), apps without
 * a foreground service are killed aggressively when the screen is off, even
 * with WifiLock + WakeLock held. A foreground service with a persistent
 * notification tells the OS "this app is actively doing work" and prevents
 * the process from being killed.
 *
 * The notification uses IMPORTANCE_MIN so it's silent and nearly invisible
 * on wall-mounted dashboard tablets.
 */
class DashieKeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val NOTIFICATION_CHANNEL_ID = "dashie_keep_alive"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, DashieKeepAliveService::class.java)
            try {
                // Foreground-aware: plain startService() while the app is foreground
                // avoids the 5s startForeground() deadline (blown during daily-refresh
                // cold starts on slow devices — June 2026 crash reports).
                com.dashieapp.Dashie.halite.util.ServiceStartHelper.startForegroundAware(context, intent)
                Log.i(TAG, "Keep-alive service started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start keep-alive service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DashieKeepAliveService::class.java))
            Log.i(TAG, "Keep-alive service stopped")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Promote to foreground FIRST: the ~5s startForegroundService() deadline spans
        // both onCreate and onStartCommand, and onStartCommand can be delayed past it
        // behind cold-start work (daily-refresh restarts). onStartCommand re-asserts.
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Idempotent re-assert for START_STICKY re-deliveries without a fresh onCreate.
        promoteToForeground()
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(brandName())
            .setContentText("Dashboard active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Keep-alive service destroyed")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Dashboard Keep-Alive",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps ${brandName()} alive on devices with aggressive battery management"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }
}
