package com.dashieapp.Dashie.halite.schedule

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/**
 * Local persistence for scheduled actions — a JSON array in SharedPreferences
 * (matches the repo idiom; there is no Room dependency). Survives app restart and
 * reboot, which is what the in-memory kitchen timer lacks.
 *
 * Methods are [Synchronized] because alarm fires (BroadcastReceiver thread) and
 * voice creates (main thread) can touch the list concurrently.
 */
class ScheduledActionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun nextId(): String {
        val n = prefs.getInt(KEY_NEXT_ID, 1)
        prefs.edit().putInt(KEY_NEXT_ID, n + 1).apply()
        return "sa_$n"
    }

    @Synchronized
    fun all(): List<ScheduledAction> {
        val raw = prefs.getString(KEY_ACTIONS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { ScheduledAction.fromJson(arr.getJSONObject(it)) }
        }.getOrElse {
            Log.e(TAG, "Failed to parse scheduled actions — returning empty", it)
            emptyList()
        }
    }

    fun active(): List<ScheduledAction> =
        all().filter { it.status == ScheduledAction.Status.SCHEDULED }

    fun get(id: String): ScheduledAction? = all().firstOrNull { it.id == id }

    @Synchronized
    fun put(action: ScheduledAction) {
        save(all().filter { it.id != action.id } + action)
    }

    @Synchronized
    fun updateStatus(id: String, status: ScheduledAction.Status) {
        save(all().map { if (it.id == id) it.copy(status = status) else it })
    }

    @Synchronized
    fun remove(id: String) {
        save(all().filter { it.id != id })
    }

    private fun save(list: List<ScheduledAction>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ACTIONS, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "ScheduledActionStore"
        private const val PREFS_NAME = "dashie_lite_prefs"
        private const val KEY_ACTIONS = "scheduled_actions"
        private const val KEY_NEXT_ID = "scheduled_actions_next_id"
    }
}
