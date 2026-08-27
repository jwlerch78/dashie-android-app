package com.dashieapp.Dashie.halite.timer

/**
 * Data model for a timer.
 */
data class TimerData(
    val id: String,
    val slot: Int,
    val label: String,
    val description: String?,
    val durationSeconds: Int,
    val remainingSeconds: Int,
    val state: TimerState,
    val isMinimized: Boolean,
    val createdAt: Long,
    val deviceId: String
) {
    companion object {
        /**
         * Parse timer JSON from JavaScript into TimerData object.
         */
        fun fromJson(timerJson: String): TimerData {
            val json = org.json.JSONObject(timerJson)
            return TimerData(
                id = json.getString("id"),
                slot = json.getInt("slot"),
                label = json.getString("label"),
                description = json.optString("description").takeIf { it.isNotEmpty() },
                durationSeconds = json.getInt("durationSeconds"),
                remainingSeconds = json.getInt("remainingSeconds"),
                state = when (json.getString("state").lowercase()) {
                    "paused" -> TimerState.PAUSED
                    "completed" -> TimerState.COMPLETED
                    else -> TimerState.RUNNING
                },
                isMinimized = json.optBoolean("isMinimized", false),
                createdAt = json.getLong("createdAt"),
                deviceId = json.optString("deviceId", "")
            )
        }
    }

    /**
     * Format remaining time as M:SS or H:MM:SS
     */
    fun formatTime(): String {
        val hours = remainingSeconds / 3600
        val minutes = (remainingSeconds % 3600) / 60
        val seconds = remainingSeconds % 60

        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    /**
     * Get progress percentage (0-100)
     */
    fun getProgress(): Int {
        if (durationSeconds == 0) return 0
        val elapsed = durationSeconds - remainingSeconds
        return ((elapsed.toFloat() / durationSeconds.toFloat()) * 100).toInt().coerceIn(0, 100)
    }
}

enum class TimerState {
    RUNNING,
    PAUSED,
    COMPLETED
}
