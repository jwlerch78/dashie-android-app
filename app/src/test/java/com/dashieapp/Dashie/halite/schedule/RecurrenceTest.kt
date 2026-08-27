package com.dashieapp.Dashie.halite.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RecurrenceTest {

    private fun at(hour: Int, minute: Int, dayOffset: Int = 0): Long =
        Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, dayOffset)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun parseHhMm() {
        assertEquals(21 to 30, Recurrence.parseHhMm("21:30"))
        assertEquals(9 to 5, Recurrence.parseHhMm("9:05"))
        assertNull(Recurrence.parseHhMm("25:00"))
        assertNull(Recurrence.parseHhMm("930"))
        assertNull(Recurrence.parseHhMm("nine thirty"))
    }

    @Test
    fun nextOccurrenceTodayIfStillAhead() {
        val now = at(20, 0)
        assertEquals(at(21, 30), Recurrence.nextOccurrence(21, 30, now))
    }

    @Test
    fun nextOccurrenceTomorrowIfPassed() {
        val now = at(22, 0)
        assertEquals(at(21, 30, dayOffset = 1), Recurrence.nextOccurrence(21, 30, now))
    }

    @Test
    fun nextAfterDailyAdvancesOneDay() {
        val fired = at(21, 30)
        assertEquals(at(21, 30, 1),
            Recurrence.nextAfter(fired, ScheduledAction.RECUR_DAILY, nowMs = fired + 1000))
    }

    @Test
    fun nextAfterSkipsMissedDaysWithoutBurst() {
        // Device off for 3 days: next fire is the first future occurrence,
        // not three back-to-back fires.
        val fired = at(21, 30)
        val now = fired + 3 * 24 * 3600 * 1000L + 60_000
        val next = Recurrence.nextAfter(fired, ScheduledAction.RECUR_DAILY, nowMs = now)!!
        assertTrue(next > now)
        assertTrue(next <= now + 24 * 3600 * 1000L)
    }

    @Test
    fun nextAfterNullForOneShots() {
        assertNull(Recurrence.nextAfter(at(21, 30), null))
        assertNull(Recurrence.nextAfter(at(21, 30), "weekly"))
    }
}
