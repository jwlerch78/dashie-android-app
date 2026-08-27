package com.dashieapp.Dashie.halite.schedule.condition

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.halite.schedule.ScheduledAction
import java.util.concurrent.Executors

/**
 * Facade for condition-triggered alerts ("tell me when the hot tub is at
 * 102"). Owns resolve → arm → poll → fire; the fire experience is the
 * standard reminder NOTIFY (chime + full-screen alert + TTS) via
 * [fireNotify] (wired to ScheduledActionManager.fireNow).
 *
 * v1: device-local (no cloud mirror/console), no per-member targeting —
 * see 20260710_VOICE_ID_CONDITION_ALERTS_PLAN.md WS4.
 */
class ConditionAlertManager(
    context: Context,
    private val credentialsProvider: () -> Pair<String, String>?,
    private val fireNotify: (ScheduledAction) -> Unit,
) {
    private val store = ConditionAlertStore(context)
    private val resolver = HaEntityResolver(credentialsProvider)
    private val createExecutor = Executors.newSingleThreadExecutor()

    private val poller = ConditionPoller(
        store = store,
        resolver = resolver,
        onFire = { alert, current -> notify(alert.notifyText(current)) },
        onExpire = { alert ->
            Log.i(TAG, "${alert.id} expired unfired (${alert.entityId})")
        },
    )

    /** Re-arm persisted alerts after process restart. */
    fun init() {
        if (store.armed().isNotEmpty()) poller.ensureRunning()
    }

    fun shutdown() = poller.shutdown()

    /**
     * Resolve + arm asynchronously (called from the JS bridge thread).
     * The voice ack ("Okay — I'll keep an eye on it") is spoken by the JS
     * handler; failures surface as a NOTIFY alert so they're never silent.
     */
    fun create(entityPhrase: String, op: String, value: Double) {
        createExecutor.execute {
            val resolved = resolver.resolve(entityPhrase)
            if (resolved == null) {
                Log.w(TAG, "Could not resolve '$entityPhrase' — notifying failure")
                notify("I couldn't find a sensor matching \"$entityPhrase\", so I can't watch it.")
                return@execute
            }
            val alert = ConditionAlert(
                id = store.nextId(),
                createdAt = System.currentTimeMillis(),
                entityId = resolved.entityId,
                friendlyName = resolved.friendlyName,
                op = op,
                value = value,
                unit = resolved.unit,
                expiresAtMs = System.currentTimeMillis() + ConditionAlert.DEFAULT_TTL_MS,
            )
            store.put(alert)
            Log.i(TAG, "Armed ${alert.id}: ${alert.entityId} ${alert.op} ${alert.value} " +
                    "(now ${resolved.currentValue}, expires 24h)")
            poller.ensureRunning()
        }
    }

    /** Cancels every armed alert; returns how many were cancelled. */
    fun cancelAll(): Int {
        val armed = store.armed()
        armed.forEach { store.updateStatus(it.id, ConditionAlert.Status.CANCELLED) }
        return armed.size
    }

    private fun notify(text: String) {
        fireNotify(
            ScheduledAction(
                id = "cond_notify_${System.currentTimeMillis()}",
                createdAt = System.currentTimeMillis(),
                fireAtEpochMs = System.currentTimeMillis(),
                notifyText = text,
                vernacular = ScheduledAction.VERNACULAR_REMINDER,
            )
        )
    }

    companion object {
        private const val TAG = "ConditionAlertMgr"
    }
}
