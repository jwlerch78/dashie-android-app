package com.dashieapp.Dashie.halite.schedule.condition

import org.json.JSONObject

/**
 * A condition-triggered alert: "tell me when the hot tub is at 102".
 *
 * Semantics are HA-numeric_state-like but "level-triggered once": the alert
 * fires the first time the condition is OBSERVED true (including on the
 * first poll if already true at creation — asking about an already-ready
 * hot tub should answer immediately), then completes. Device-local v1: no
 * cloud mirror/console yet (comes with the WS4 trigger JSONB migration).
 * See .reference/build-plans/20260710_VOICE_ID_CONDITION_ALERTS_PLAN.md.
 */
data class ConditionAlert(
    val id: String,
    val createdAt: Long,
    val entityId: String,
    val friendlyName: String,
    val op: String,                 // OP_GTE | OP_LTE
    val value: Double,
    val unit: String = "",          // e.g. "°F" — for spoken text only
    val expiresAtMs: Long,
    val status: Status = Status.ARMED
) {
    enum class Status { ARMED, FIRED, CANCELLED, EXPIRED, FAILED }

    fun isSatisfiedBy(current: Double): Boolean = when (op) {
        OP_GTE -> current >= value
        OP_LTE -> current <= value
        else -> false
    }

    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs

    /** Spoken/shown at fire time: "Hot Tub is at 102 degrees!" */
    fun notifyText(current: Double): String {
        val shown = if (current % 1.0 == 0.0) current.toInt().toString() else current.toString()
        val degrees = if (unit.contains("°") || unit.equals("F", true) || unit.equals("C", true))
            " degrees" else if (unit.isNotEmpty()) " $unit" else ""
        return "$friendlyName is at $shown$degrees!"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("entityId", entityId)
        put("friendlyName", friendlyName)
        put("op", op)
        put("value", value)
        put("unit", unit)
        put("expiresAtMs", expiresAtMs)
        put("status", status.name)
    }

    companion object {
        const val OP_GTE = ">="
        const val OP_LTE = "<="
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000

        fun fromJson(o: JSONObject): ConditionAlert = ConditionAlert(
            id = o.getString("id"),
            createdAt = o.optLong("createdAt"),
            entityId = o.getString("entityId"),
            friendlyName = o.optString("friendlyName", o.getString("entityId")),
            op = o.optString("op", OP_GTE),
            value = o.getDouble("value"),
            unit = o.optString("unit", ""),
            expiresAtMs = o.optLong("expiresAtMs", Long.MAX_VALUE),
            status = runCatching { Status.valueOf(o.optString("status", Status.ARMED.name)) }
                .getOrDefault(Status.ARMED)
        )
    }
}
