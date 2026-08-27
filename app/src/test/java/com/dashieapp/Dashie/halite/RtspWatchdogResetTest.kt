package com.dashieapp.Dashie.halite

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the watchdog counter-reset bug discovered in the
 * StarView OOM crash (2026-05-30).
 *
 * Before the fix, handleSocketDead() incremented watchdogRestartCount and
 * called restartServer(), which called release() — and release() reset the
 * counter back to 0. The MAX_WATCHDOG_RESTARTS = 3 exhaustion check thus
 * never fired, and the watchdog looped forever on memory-pressured devices.
 *
 * The fix introduced an inWatchdogRestart flag that restartServer() sets
 * around its internal release() call, suppressing the reset.
 *
 * This test exercises release() directly with the flag in both states and
 * verifies the counter behavior matches the new contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RtspWatchdogResetTest {

    private lateinit var context: Context
    private lateinit var prefs: HalitePreferences
    private lateinit var server: RtspCameraServer2

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = HalitePreferences(context)
        // Instantiate but do NOT call initialize() — we only need to exercise
        // release()'s counter-reset logic. The constructor is lightweight; the
        // heavy Camera2/MediaCodec wiring happens in initialize().
        server = RtspCameraServer2(context, prefs)
    }

    private fun setPrivateInt(field: String, value: Int) {
        val f = RtspCameraServer2::class.java.getDeclaredField(field)
        f.isAccessible = true
        f.setInt(server, value)
    }

    private fun setPrivateBoolean(field: String, value: Boolean) {
        val f = RtspCameraServer2::class.java.getDeclaredField(field)
        f.isAccessible = true
        f.setBoolean(server, value)
    }

    private fun getPrivateInt(field: String): Int {
        val f = RtspCameraServer2::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.getInt(server)
    }

    private fun getPrivateBoolean(field: String): Boolean {
        val f = RtspCameraServer2::class.java.getDeclaredField(field)
        f.isAccessible = true
        return f.getBoolean(server)
    }

    @Test
    fun `release outside watchdog restart resets the counter (normal teardown path)`() {
        setPrivateInt("watchdogRestartCount", 2)
        setPrivateBoolean("watchdogExhausted", true)
        setPrivateBoolean("inWatchdogRestart", false)  // explicitly NOT in a watchdog restart

        server.release()

        // External release() must reset cleanly so the next user-initiated
        // start begins from a clean slate.
        assertEquals(
            "watchdogRestartCount should reset on external release",
            0, getPrivateInt("watchdogRestartCount")
        )
        assertFalse(
            "watchdogExhausted should reset on external release",
            getPrivateBoolean("watchdogExhausted")
        )
    }

    @Test
    fun `release inside watchdog restart preserves the counter (the bug fix)`() {
        setPrivateInt("watchdogRestartCount", 2)
        setPrivateBoolean("watchdogExhausted", false)
        setPrivateBoolean("inWatchdogRestart", true)  // simulates restartServer's internal release

        server.release()

        // This is the core regression: when release() is called from inside
        // restartServer(), the counter must NOT reset — otherwise
        // MAX_WATCHDOG_RESTARTS can never accumulate to its exhaustion limit.
        assertEquals(
            "watchdogRestartCount must NOT reset when inside watchdog restart",
            2, getPrivateInt("watchdogRestartCount")
        )
    }

    @Test
    fun `repeated release-inside-watchdog cycles let the counter accumulate to exhaustion`() {
        // Simulate three failed-restart cycles to verify the counter monotonically
        // grows across them — i.e., the next exhaustion check (count > 3) would
        // actually trigger on the 4th failure instead of looping forever.
        setPrivateInt("watchdogRestartCount", 0)

        repeat(3) { cycle ->
            // Simulate handleSocketDead's increment that happens before restartServer is called.
            setPrivateInt("watchdogRestartCount", getPrivateInt("watchdogRestartCount") + 1)
            setPrivateBoolean("inWatchdogRestart", true)
            server.release()
            setPrivateBoolean("inWatchdogRestart", false)
        }

        assertEquals(
            "Counter should equal 3 after 3 watchdog-driven release cycles (was 1 before fix)",
            3, getPrivateInt("watchdogRestartCount")
        )
    }
}
