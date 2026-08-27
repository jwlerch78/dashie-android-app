package com.dashieapp.Dashie.halite.schedule.condition

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Device-local persistence for condition alerts (SharedPreferences JSON,
 * mirroring ScheduledActionStore). ARMED alerts are re-armed by
 * ConditionAlertManager.init after process restarts.
 */
class ConditionAlertStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dashie_condition_alerts", Context.MODE_PRIVATE)

    fun nextId(): String {
        val n = prefs.getLong("next_id", 1L)
        prefs.edit().putLong("next_id", n + 1).apply()
        return "cond_$n"
    }

    fun all(): List<ConditionAlert> {
        val raw = prefs.getString("alerts", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { ConditionAlert.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun armed(): List<ConditionAlert> =
        all().filter { it.status == ConditionAlert.Status.ARMED }

    fun put(alert: ConditionAlert) {
        save(all().filter { it.id != alert.id } + alert)
    }

    fun updateStatus(id: String, status: ConditionAlert.Status) {
        save(all().map { if (it.id == id) it.copy(status = status) else it })
    }

    private fun save(list: List<ConditionAlert>) {
        // Cap history so the prefs blob can't grow unbounded (terminal-status
        // alerts are only kept for debugging).
        val trimmed = list.sortedByDescending { it.createdAt }.take(50)
        val arr = JSONArray()
        trimmed.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("alerts", arr.toString()).apply()
    }
}
