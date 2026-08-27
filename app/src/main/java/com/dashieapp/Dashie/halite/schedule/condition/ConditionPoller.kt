package com.dashieapp.Dashie.halite.schedule.condition

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Adaptive poll loop for armed condition alerts.
 *
 * Interval is DYNAMIC, banded on distance-to-threshold: 60 s while far,
 * tightening to 10 s near the crossing — near-instant fire without tight
 * polling during the hours a hot tub spends heating. (Bands are in raw
 * entity units — crude but right for v1 temperatures; a rate-aware
 * estimator can replace [intervalFor] without touching anything else.)
 * Runs ONLY while ≥1 alert is armed; the WS4.1 subscribe_trigger upgrade
 * replaces the tick with pushes and keeps this as reconnect safety-net.
 *
 * Threading: tick scheduling on main; HTTP + evaluation on [ioExecutor];
 * fire/expire callbacks posted back to main.
 */
class ConditionPoller(
    private val store: ConditionAlertStore,
    private val resolver: HaEntityResolver,
    private val onFire: (ConditionAlert, Double) -> Unit,
    private val onExpire: (ConditionAlert) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var running = false
    private val lastKnown = mutableMapOf<String, Double>()

    fun ensureRunning() {
        if (running) return
        running = true
        Log.i(TAG, "Condition poller started")
        handler.post(tick)
    }

    fun shutdown() {
        running = false
        handler.removeCallbacks(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val armed = store.armed()
            if (armed.isEmpty()) {
                running = false
                Log.i(TAG, "No armed alerts — poller stopped")
                return
            }
            ioExecutor.execute {
                val nextDelay = pollAll(armed)
                handler.postDelayed(this, nextDelay)
            }
        }
    }

    /** Polls each armed alert once; returns the tightest next-poll delay. */
    private fun pollAll(armed: List<ConditionAlert>): Long {
        val now = System.currentTimeMillis()
        var minDelay = MAX_INTERVAL_MS
        for (alert in armed) {
            if (alert.isExpired(now)) {
                store.updateStatus(alert.id, ConditionAlert.Status.EXPIRED)
                handler.post { onExpire(alert) }
                continue
            }
            val current = resolver.currentValue(alert.entityId)
            if (current == null) {
                Log.w(TAG, "${alert.id}: ${alert.entityId} unreadable — retrying at max interval")
                continue
            }
            lastKnown[alert.id] = current
            if (alert.isSatisfiedBy(current)) {
                store.updateStatus(alert.id, ConditionAlert.Status.FIRED)
                Log.i(TAG, "${alert.id}: ${alert.entityId}=$current satisfies ${alert.op}${alert.value} — firing")
                handler.post { onFire(alert, current) }
            } else {
                minDelay = minOf(minDelay, intervalFor(alert, current))
            }
        }
        return minDelay
    }

    companion object {
        private const val TAG = "ConditionPoller"
        const val MAX_INTERVAL_MS = 60_000L
        const val MID_INTERVAL_MS = 30_000L
        const val MIN_INTERVAL_MS = 10_000L

        /** Distance-banded adaptive interval (raw entity units). */
        fun intervalFor(alert: ConditionAlert, current: Double): Long {
            val distance = kotlin.math.abs(current - alert.value)
            return when {
                distance <= 3.0 -> MIN_INTERVAL_MS
                distance <= 10.0 -> MID_INTERVAL_MS
                else -> MAX_INTERVAL_MS
            }
        }
    }
}
