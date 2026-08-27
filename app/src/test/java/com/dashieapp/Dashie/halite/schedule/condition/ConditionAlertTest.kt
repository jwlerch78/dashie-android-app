package com.dashieapp.Dashie.halite.schedule.condition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConditionAlertTest {

    private fun alert(op: String = ConditionAlert.OP_GTE, value: Double = 102.0) =
        ConditionAlert(
            id = "cond_1", createdAt = 0, entityId = "sensor.hot_tub_temperature",
            friendlyName = "Hot Tub", op = op, value = value, unit = "°F",
            expiresAtMs = 1000,
        )

    @Test
    fun gteFiresAtAndAboveThreshold() {
        val a = alert()
        assertFalse(a.isSatisfiedBy(101.9))
        assertTrue(a.isSatisfiedBy(102.0))
        assertTrue(a.isSatisfiedBy(104.5))
    }

    @Test
    fun lteFiresAtAndBelowThreshold() {
        val a = alert(op = ConditionAlert.OP_LTE, value = 32.0)
        assertTrue(a.isSatisfiedBy(31.0))
        assertTrue(a.isSatisfiedBy(32.0))
        assertFalse(a.isSatisfiedBy(33.0))
    }

    @Test
    fun notifyTextSpeaksDegreesForTemperatureUnits() {
        assertEquals("Hot Tub is at 102 degrees!", alert().notifyText(102.0))
    }

    @Test
    fun expiryBoundary() {
        assertFalse(alert().isExpired(999))
        assertTrue(alert().isExpired(1000))
    }

    @Test
    fun jsonRoundTrip() {
        val a = alert()
        val back = ConditionAlert.fromJson(a.toJson())
        assertEquals(a, back)
    }

    @Test
    fun adaptivePollBands() {
        val a = alert() // target 102
        assertEquals(ConditionPoller.MAX_INTERVAL_MS, ConditionPoller.intervalFor(a, 80.0))
        assertEquals(ConditionPoller.MID_INTERVAL_MS, ConditionPoller.intervalFor(a, 95.0))
        assertEquals(ConditionPoller.MIN_INTERVAL_MS, ConditionPoller.intervalFor(a, 100.5))
    }

    @Test
    fun fuzzyMatcherPrefersFullPhraseMatch() {
        val phrase = HaEntityResolver.tokenize("the hot tub")
        val hotTub = HaEntityResolver.tokenize("Hot Tub Temperature")
        val hotWater = HaEntityResolver.tokenize("Hot Water Heater")
        val full = HaEntityResolver.scoreTokens(phrase, hotTub)
        val partial = HaEntityResolver.scoreTokens(phrase, hotWater)
        assertTrue("full ($full) should beat partial ($partial)", full > partial)
        assertEquals(0, HaEntityResolver.scoreTokens(phrase, HaEntityResolver.tokenize("Kitchen Lights")))
    }
}
