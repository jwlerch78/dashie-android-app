package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterization tests for [KotlinIntentPatterns] — the on-device voice
 * command classifier (mirror of the JS @dashieapp/intent-classifier).
 *
 * These lock in the CURRENT behavior of the pure classification logic so the
 * planned voice/music refactors can proceed without silently changing what
 * "set a 5 minute timer" or "play jazz on the kitchen" resolves to. They
 * assert observed behavior, not aspirational behavior — where the current
 * output is quirky (e.g. "twenty five" → "20 5", "Setting a 5 minutes
 * timer."), the test pins the quirk so a future change to it is a conscious
 * decision, not an accident.
 *
 * Robolectric is required only because the classifier calls android.util.Log;
 * the logic itself is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KotlinIntentPatternsTest {

    private val P = KotlinIntentPatterns

    // ==================== normalizeWordNumbers ====================

    @Test
    fun `normalize lowercases and converts single word numbers to digits`() {
        assertEquals("set a 5 minute timer", P.normalizeWordNumbers("Set a FIVE minute timer"))
    }

    @Test
    fun `normalize strips leading polite prefixes`() {
        assertEquals("pause the music", P.normalizeWordNumbers("please pause the music"))
        assertEquals("pause the music", P.normalizeWordNumbers("hey pause the music"))
        assertEquals("pause the music", P.normalizeWordNumbers("can you pause the music"))
    }

    @Test
    fun `normalize strips trailing please`() {
        assertEquals("play music", P.normalizeWordNumbers("hey play music please"))
    }

    @Test
    fun `normalize fixes set-at and start-at STT mishearings`() {
        assertEquals("set a 5 minute timer", P.normalizeWordNumbers("set at 5 minute timer"))
    }

    @Test
    fun `normalize compounds tens plus ones like JS`() {
        // Was "20 5" (a bug: "twenty five minutes" parsed as 5 min). Now compounds to 25,
        // matching the JS normalizeWordNumbers. Guarded end-to-end by the golden vectors.
        assertEquals("25", P.normalizeWordNumbers("twenty five"))
        assertEquals("set a timer for 25 minutes", P.normalizeWordNumbers("set a timer for twenty five minutes"))
    }

    // ==================== parseDuration ====================

    @Test
    fun `parseDuration handles minutes, hours, seconds`() {
        assertEquals(300, P.parseDuration("5 minutes")?.totalSeconds)
        assertEquals(3600, P.parseDuration("an hour")?.totalSeconds)
        assertEquals(30, P.parseDuration("30 seconds")?.totalSeconds)
    }

    @Test
    fun `parseDuration handles natural-language fractions`() {
        assertEquals(1800, P.parseDuration("half an hour")?.totalSeconds)
        assertEquals(1800, P.parseDuration("half hour")?.totalSeconds)
        assertEquals(90, P.parseDuration("a minute and a half")?.totalSeconds)
    }

    @Test
    fun `parseDuration does not double-count whole hours`() {
        // Regression guard: "an hour" must stay 1h, not get reinterpreted.
        assertEquals(3600, P.parseDuration("an hour")?.totalSeconds)
    }

    @Test
    fun `parseDuration combines components`() {
        val d = P.parseDuration("1 hour 30 minutes")
        assertEquals(1, d?.hours)
        assertEquals(30, d?.minutes)
        assertEquals(5400, d?.totalSeconds)
    }

    @Test
    fun `parseDuration returns null when no duration present`() {
        assertNull(P.parseDuration("turn on the lights"))
    }

    // ==================== formatDuration ====================

    @Test
    fun `formatDuration pluralizes and omits zero components`() {
        assertEquals("5 minutes", P.formatDuration(300))
        assertEquals("1 minute", P.formatDuration(60))
        assertEquals("1 hour", P.formatDuration(3600))
        assertEquals("1 minute 30 seconds", P.formatDuration(90))
        assertEquals("5 seconds", P.formatDuration(5))
    }

    @Test
    fun `formatDuration suppresses seconds when hours are present`() {
        // 1h 1m 1s -> seconds dropped because h > 0
        assertEquals("1 hour 1 minute", P.formatDuration(3661))
    }

    @Test
    fun `formatDuration of zero is empty`() {
        assertEquals("", P.formatDuration(0))
    }

    // ==================== classify: timers ====================

    @Test
    fun `classify start timer with duration`() {
        val r = P.classify("set a 5 minute timer")!!
        assertEquals("timer", r.category)
        assertEquals("start_timer", r.command)
        assertEquals(300, r.params["durationSeconds"])
    }

    @Test
    fun `classify start timer with no parseable duration falls through to null`() {
        // TIMER_START matches "set a ..." but parseDuration fails -> returns null
        assertNull(P.classify("set a kitchen timer"))
    }

    @Test
    fun `classify timer pause resume cancel`() {
        assertEquals("pause_timer", P.classify("pause the timer")!!.command)
        assertEquals("resume_timer", P.classify("resume the timer")!!.command)
        assertEquals("cancel_timer", P.classify("cancel the timer")!!.command)
    }

    @Test
    fun `classify timer query reflects cached remaining`() {
        val none = P.classify("how much time is left")!!
        assertEquals("query_time", none.command)
        assertEquals("You don't have any active timers.", none.ttsResponse)

        val cached = P.classify("how much time is left", cachedTimerRemaining = 120)!!
        assertEquals("2 minutes remaining on your timer.", cached.ttsResponse)
    }

    @Test
    fun `classify add and subtract time`() {
        val add = P.classify("add 5 minutes")!!
        assertEquals("add_time", add.command)
        assertEquals(300, add.params["addSeconds"])

        val addGeneric = P.classify("add time to the timer")!!
        assertEquals("add_time", addGeneric.command)
        assertEquals(60, addGeneric.params["addSeconds"])

        val sub = P.classify("subtract 2 minutes")!!
        assertEquals("subtract_time", sub.command)
        assertEquals(120, sub.params["subtractSeconds"])
    }

    @Test
    fun `classify timer reference by name and slot`() {
        assertEquals("pasta", P.classify("pause the pasta timer")!!.params["timerName"])
        assertEquals(2, P.classify("pause timer 2")!!.params["timerSlot"])
    }

    // ==================== classify: alarm dismiss ====================

    @Test
    fun `classify alarm dismiss only when alarm playing`() {
        assertEquals("stop_alarm", P.classify("stop", alarmPlaying = true)!!.command)
        // "stop" alone with no alarm is not a recognized command
        assertNull(P.classify("stop", alarmPlaying = false))
    }

    // ==================== classify: media ====================

    @Test
    fun `classify explicit media keywords`() {
        assertEquals("pause", P.classify("pause the music")!!.command)
        assertEquals("play", P.classify("play music")!!.command)
        assertEquals("next", P.classify("next song")!!.command)
        assertEquals("previous", P.classify("previous song")!!.command)
    }

    @Test
    fun `classify play-search by query and by artist`() {
        val jazz = P.classify("play jazz")!!
        assertEquals("play_search", jazz.command)
        assertEquals("jazz", jazz.params["mediaId"])

        val artist = P.classify("play songs by taylor swift")!!
        assertEquals("play_search", artist.command)
        assertEquals("taylor swift", artist.params["mediaId"])
        assertEquals("artist", artist.params["mediaType"])
    }

    @Test
    fun `classify play-search with speaker targeting`() {
        val r = P.classify("play jazz on the kitchen")!!
        assertEquals("play_search", r.command)
        assertEquals("jazz", r.params["mediaId"])
        assertEquals("kitchen", r.params["targetSpeaker"])
    }

    // ==================== classify: speaker mute ====================

    @Test
    fun `classify mute and unmute capture target speaker`() {
        val mute = P.classify("mute the kitchen")!!
        assertEquals("speaker", mute.category)
        assertEquals("mute", mute.command)
        assertEquals("kitchen", mute.params["targetSpeaker"])
    }

    @Test
    fun `mute is matched before media so 'mute kitchen' is not a media stop`() {
        // classify() runs classifySpeakerMute before classifyMedia by design.
        assertEquals("speaker", P.classify("mute kitchen")!!.category)
    }

    // ==================== classify: volume ====================

    @Test
    fun `classify volume up down and set`() {
        assertEquals("volume_up", P.classify("turn it up")!!.command)
        assertEquals("volume_down", P.classify("turn it down")!!.command)
        val set = P.classify("set volume to 40")!!
        assertEquals("volume_set", set.command)
        assertEquals(40, set.params["level"])
    }

    // ==================== classify: playback mode (music-gated) ====================

    @Test
    fun `classify shuffle and repeat only when music is playing`() {
        assertEquals("shuffle_on", P.classify("shuffle on", musicPlaying = true)!!.command)
        assertEquals("repeat_one", P.classify("repeat this song", musicPlaying = true)!!.command)
        // Gated off when no music is playing -> let HA handle it
        assertNull(P.classify("shuffle on", musicPlaying = false))
    }

    // ==================== classify: video feed ====================

    @Test
    fun `classify show and hide cameras`() {
        assertEquals("show_all", P.classify("show all cameras")!!.command)
        assertEquals("dismiss_all", P.classify("hide all cameras")!!.command)
    }

    // ==================== classify: fallthrough ====================

    @Test
    fun `classify returns null for non-local commands so HA handles them`() {
        assertNull(P.classify("what's the weather tomorrow"))
        assertNull(P.classify("turn on the living room lights"))
    }
}
