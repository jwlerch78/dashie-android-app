package com.dashieapp.Dashie.halite.schedule

import java.util.Calendar

/**
 * Wall-clock recurrence math for scheduled actions (WS5-a AI callbacks).
 * Calendar-based so "9:30pm every night" stays 9:30pm across DST changes.
 */
object Recurrence {

    /**
     * Next epoch for local wall-clock [hour]:[minute] — today if still ahead
     * of [nowMs], else tomorrow. Used when creating from an "HH:MM" tool arg.
     */
    fun nextOccurrence(hour: Int, minute: Int, nowMs: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /**
     * The fire time after [previousFireMs] for [recurrence], preserving local
     * wall-clock time; null for one-shots. Skips forward past [nowMs] so an
     * action that slept through several days (device off) fires once, not N
     * times in a burst.
     */
    fun nextAfter(previousFireMs: Long, recurrence: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (recurrence != ScheduledAction.RECUR_DAILY) return null
        val cal = Calendar.getInstance().apply { timeInMillis = previousFireMs }
        do {
            cal.add(Calendar.DAY_OF_MONTH, 1)
        } while (cal.timeInMillis <= nowMs)
        return cal.timeInMillis
    }

    /** Parse "HH:MM" (24h) → hour/minute pair, or null if malformed. */
    fun parseHhMm(s: String): Pair<Int, Int>? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(s.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }
}
