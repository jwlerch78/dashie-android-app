package com.dashieapp.Dashie.halite.music.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterization tests for [SendspinTimeFilter] — the Kalman filter that
 * tracks the client/server clock offset for synchronized multi-room audio.
 *
 * This is the most algorithm-dense seam under SyncAudioPlayer, so these lock in
 * the observable contract (offset initialization, readiness, the
 * server<->client conversions, static-delay handling, constant-offset tracking,
 * reset) ahead of the planned audio refactor. They assert deterministic,
 * black-box behavior through the public API — not internal Kalman matrix
 * values, which are tuning-dependent.
 *
 * Robolectric is required only because the class touches android.util.Log; the
 * filter math itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendspinTimeFilterTest {

    private lateinit var filter: SendspinTimeFilter

    @Before
    fun setUp() {
        filter = SendspinTimeFilter()
    }

    @Test
    fun `fresh filter is not ready and has zero offset`() {
        assertFalse(filter.isReady)
        assertEquals(0L, filter.offsetMicros)
        assertEquals(0, filter.measurementCountValue)
    }

    @Test
    fun `with zero offset and no delay conversions are the identity`() {
        assertEquals(1_000_000L, filter.serverToClient(1_000_000L))
        assertEquals(1_000_000L, filter.clientToServer(1_000_000L))
    }

    @Test
    fun `first measurement initializes the offset directly`() {
        filter.addMeasurement(
            measurementOffset = 5000L,
            maxError = 1000L,
            clientTimeMicros = 1_000_000L
        )
        assertEquals(5000L, filter.offsetMicros)
        assertEquals(1, filter.measurementCountValue)
        // serverToClient subtracts the offset
        assertEquals(1_000_000L - 5000L, filter.serverToClient(1_000_000L))
        // clientToServer adds it back
        assertEquals(1_000_000L + 5000L, filter.clientToServer(1_000_000L))
    }

    @Test
    fun `becomes ready only after the minimum number of measurements`() {
        filter.addMeasurement(5000L, 1000L, 1_000_000L)
        assertFalse("not ready after 1 measurement", filter.isReady)

        filter.addMeasurement(5000L, 1000L, 2_000_000L)
        assertTrue("ready after 2 measurements", filter.isReady)
        assertEquals(2, filter.measurementCountValue)
    }

    @Test
    fun `serverToClient and clientToServer are exact inverses`() {
        filter.addMeasurement(12345L, 1000L, 1_000_000L)
        val t = 9_999_999L
        assertEquals(t, filter.clientToServer(filter.serverToClient(t)))
        assertEquals(t, filter.serverToClient(filter.clientToServer(t)))
    }

    @Test
    fun `static delay shifts the conversions symmetrically`() {
        filter.staticDelayMs = 50.0 // 50 ms = 50_000 us
        // offset is still 0; serverToClient adds the delay, clientToServer removes it
        assertEquals(1_000_000L + 50_000L, filter.serverToClient(1_000_000L))
        assertEquals(1_000_000L - 50_000L, filter.clientToServer(1_000_000L))
        // round-trip remains an inverse even with a delay applied
        assertEquals(7_000_000L, filter.clientToServer(filter.serverToClient(7_000_000L)))
    }

    @Test
    fun `tracks a constant offset across a measurement burst`() {
        val trueOffset = 8000L
        var clientTime = 1_000_000L
        repeat(20) {
            filter.addMeasurement(trueOffset, 500L, clientTime)
            clientTime += 500_000L // 500 ms burst interval
        }
        assertTrue(filter.isReady)
        // The filter stays locked near the constant offset it has been fed.
        assertEquals(trueOffset.toDouble(), filter.offsetMicros.toDouble(), 1000.0)
        // And its error estimate shrinks well below the per-measurement maxError.
        assertTrue(
            "error should converge below 500us, was ${filter.errorMicros}",
            filter.errorMicros < 500L
        )
    }

    @Test
    fun `reset clears all state back to a fresh filter`() {
        filter.addMeasurement(5000L, 1000L, 1_000_000L)
        filter.addMeasurement(5000L, 1000L, 2_000_000L)
        assertTrue(filter.isReady)

        filter.reset()

        assertFalse(filter.isReady)
        assertEquals(0L, filter.offsetMicros)
        assertEquals(0, filter.measurementCountValue)
        // conversions revert to the identity
        assertEquals(3_000_000L, filter.serverToClient(3_000_000L))
    }
}
