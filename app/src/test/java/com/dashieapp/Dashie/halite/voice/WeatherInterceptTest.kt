package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden vectors for [WeatherIntercept.classify] — the native, conservative weather
 * detector that lets HA-Assist / $0-degraded turns answer weather on-device (punch #4).
 *
 * Two things are pinned: (1) weather QUESTIONS match and non-weather turns fall through
 * (null), so the intercept never hijacks a media/device command; (2) the timeframe token
 * stays inside the set templateWeather understands (current/today/tonight/weekend/
 * this_week/<weekday>) — contract #40. `todayName` is injected so "tomorrow" is
 * deterministic. Robolectric only for a real org.json.JSONObject.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherInterceptTest {

    private val W = WeatherIntercept

    private fun tf(transcript: String, today: String = "friday"): String? =
        W.classify(transcript, today)?.optString("timeframe")

    // ==================== matches + timeframe ====================

    @Test fun `plain weather question is current`() = assertEquals("current", tf("what's the weather"))
    @Test fun `weather today`() = assertEquals("today", tf("what's the weather today"))
    @Test fun `weather tonight`() = assertEquals("tonight", tf("what's the weather tonight"))
    @Test fun `weather this weekend`() = assertEquals("weekend", tf("what's the weather this weekend"))
    @Test fun `bare weekend`() = assertEquals("weekend", tf("weather for the weekend"))
    @Test fun `weather this week`() = assertEquals("this_week", tf("what's the weather this week"))

    @Test fun `tomorrow resolves to the next weekday`() =
        assertEquals("saturday", tf("will it rain tomorrow", today = "friday"))
    @Test fun `tomorrow wraps sunday to monday`() =
        assertEquals("monday", tf("what's the forecast tomorrow", today = "sunday"))
    @Test fun `explicit weekday`() = assertEquals("monday", tf("what's the forecast for monday"))

    @Test fun `temperature triggers`() = assertNotNull(W.classify("what's the temperature outside right now"))
    @Test fun `how hot is it triggers`() = assertNotNull(W.classify("how hot is it going to be"))
    @Test fun `chance of rain triggers`() = assertNotNull(W.classify("is there a chance of rain later"))
    @Test fun `need an umbrella triggers`() = assertNotNull(W.classify("do I need an umbrella"))
    @Test fun `hows it outside triggers`() = assertNotNull(W.classify("how's it outside"))
    @Test fun `will it snow triggers`() = assertNotNull(W.classify("will it snow this weekend"))

    // ==================== fall-through (must NOT hijack) ====================

    @Test fun `named-sensor temperature falls through - pool`() =
        assertNull(W.classify("what's the pool temperature"))
    @Test fun `named-sensor temperature falls through - thermometer`() =
        assertNull(W.classify("what's the pool thermometer say"))
    @Test fun `room temperature falls through`() = assertNull(W.classify("what's the room temperature"))
    @Test fun `the temperature OF the pool falls through`() =
        assertNull(W.classify("what's the temperature of the pool"))
    @Test fun `the temperature IN the bedroom falls through`() =
        assertNull(W.classify("what is the temperature in the bedroom"))
    @Test fun `the temperature outside is still weather`() =
        assertNotNull(W.classify("what's the temperature outside"))
    @Test fun `plain the-temperature is still weather`() =
        assertNotNull(W.classify("what's the temperature"))

    @Test fun `device command falls through`() = assertNull(W.classify("turn off the kitchen light"))
    @Test fun `outside lights command falls through`() = assertNull(W.classify("turn off the outside lights"))
    @Test fun `play umbrella song falls through`() = assertNull(W.classify("play umbrella by rihanna"))
    @Test fun `timer falls through`() = assertNull(W.classify("set a five minute timer"))
    @Test fun `empty falls through`() = assertNull(W.classify(""))
    @Test fun `unrelated question falls through`() = assertNull(W.classify("what time is it"))
}
