package com.dashieapp.Dashie.halite.schedule

import org.json.JSONObject

/**
 * A single scheduled action (Phase 1: a relative-time NOTIFY reminder).
 *
 * The model is intentionally forward-compatible with later phases (conditional HA
 * actions, watches, AI callbacks) — Phase 1 only populates the AT-trigger / NOTIFY
 * subset. See `.reference/build-plans/20260627_SCHEDULED_ACTIONS_PHASE1.md`.
 *
 * Canonical name = "scheduled action"; [vernacular] is the user-facing word
 * ("reminder" | "alarm") used for spoken phrase-out.
 */
data class ScheduledAction(
    val id: String,
    val createdAt: Long,
    val fireAtEpochMs: Long,
    val notifyText: String,
    val vernacular: String = VERNACULAR_REMINDER,
    val status: Status = Status.SCHEDULED,
    // AI-callback support (WS5-a): TYPE_AI_TURN actions inject [prompt] into
    // the voice pipeline at fire time instead of speaking notifyText.
    val actionType: String = TYPE_NOTIFY,
    val prompt: String = "",
    // null = one-shot; RECUR_DAILY = re-armed +24h (wall-clock) after firing.
    val recurrence: String? = null,
    // LLM-set short description of the action ("turn off the string lights") — the fire-time
    // card's TITLE, so "Turned off the switch" reads with its original request for context.
    val label: String = ""
) {
    enum class Status { SCHEDULED, FIRED, CANCELLED }

    fun toJson(): JSONObject = JSONObject().apply {
        put(K_ID, id)
        put(K_CREATED_AT, createdAt)
        put(K_FIRE_AT, fireAtEpochMs)
        put(K_NOTIFY_TEXT, notifyText)
        put(K_VERNACULAR, vernacular)
        put(K_STATUS, status.name)
        put(K_ACTION_TYPE, actionType)
        put(K_PROMPT, prompt)
        if (recurrence != null) put(K_RECURRENCE, recurrence)
        if (label.isNotEmpty()) put(K_LABEL, label)
    }

    companion object {
        const val VERNACULAR_REMINDER = "reminder"
        const val VERNACULAR_ALARM = "alarm"

        const val TYPE_NOTIFY = "notify"
        const val TYPE_AI_TURN = "ai_turn"
        const val TYPE_HA_COMMAND = "ha_command"  // brain-tagged kind='command' — issue directly at fire
        const val RECUR_DAILY = "daily"

        private const val K_ID = "id"
        private const val K_CREATED_AT = "createdAt"
        private const val K_FIRE_AT = "fireAtEpochMs"
        private const val K_NOTIFY_TEXT = "notifyText"
        private const val K_VERNACULAR = "vernacular"
        private const val K_STATUS = "status"
        private const val K_ACTION_TYPE = "actionType"
        private const val K_PROMPT = "prompt"
        private const val K_RECURRENCE = "recurrence"
        private const val K_LABEL = "label"

        fun fromJson(o: JSONObject): ScheduledAction = ScheduledAction(
            id = o.getString(K_ID),
            createdAt = o.optLong(K_CREATED_AT),
            fireAtEpochMs = o.getLong(K_FIRE_AT),
            notifyText = o.optString(K_NOTIFY_TEXT),
            vernacular = o.optString(K_VERNACULAR, VERNACULAR_REMINDER),
            status = runCatching { Status.valueOf(o.optString(K_STATUS, Status.SCHEDULED.name)) }
                .getOrDefault(Status.SCHEDULED),
            actionType = o.optString(K_ACTION_TYPE, TYPE_NOTIFY),
            prompt = o.optString(K_PROMPT, ""),
            recurrence = o.optString(K_RECURRENCE, "").ifEmpty { null },
            label = o.optString(K_LABEL, "")
        )
    }
}
