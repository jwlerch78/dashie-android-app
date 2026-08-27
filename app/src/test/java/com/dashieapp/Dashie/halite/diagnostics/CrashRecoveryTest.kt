package com.dashieapp.Dashie.halite.diagnostics

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dashieapp.Dashie.halite.preferences.PerformancePreferences
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests the crash-relaunch gating and loop guard added to recover from fatal
 * uncaught exceptions (the 2026-05-28 photo-signing crash left the device on
 * the home screen with no auto-restart).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashRecoveryTest {

    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        PerformancePreferences(context).autoReloadOnCrash = true
    }

    /** Consume and return the next scheduled alarm (null if none was scheduled). */
    private fun nextAlarm() = shadowOf(alarmManager).getNextScheduledAlarm()

    @Test
    fun `schedules relaunch on first crash when enabled`() {
        CrashRecovery.maybeScheduleRelaunch(context)
        assertNotNull("a relaunch alarm should be scheduled", nextAlarm())
    }

    @Test
    fun `does not schedule relaunch when autoReloadOnCrash disabled`() {
        PerformancePreferences(context).autoReloadOnCrash = false
        CrashRecovery.maybeScheduleRelaunch(context)
        assertNull("no relaunch when gating disabled", nextAlarm())
    }

    @Test
    fun `suppresses relaunch once crash loop threshold is reached`() {
        // 1st and 2nd crashes within the window relaunch...
        CrashRecovery.maybeScheduleRelaunch(context)
        assertNotNull("1st crash should relaunch", nextAlarm())
        CrashRecovery.maybeScheduleRelaunch(context)
        assertNotNull("2nd crash should relaunch", nextAlarm())
        // ...the 3rd crash in the window is a loop → relaunch suppressed.
        CrashRecovery.maybeScheduleRelaunch(context)
        assertNull("3rd crash within window should be suppressed", nextAlarm())
    }
}
