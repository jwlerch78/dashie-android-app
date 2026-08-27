package com.dashieapp.Dashie.halite.voice

import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.voice.stt.SttProviderManager.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The WS-D.1 degraded-mode state machine: enter once, announce once, clear on refill.
 *
 * The behaviours worth pinning are the ones a future edit could plausibly break: announcing
 * on EVERY turn (a nag), and failing to re-arm the announcement after credits return (so a
 * second depletion degrades silently — the exact thing the design forbids).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DegradedVoiceModeTest {

    private lateinit var prefs: HalitePreferences
    private var changes = mutableListOf<Boolean>()
    private var announcements = mutableListOf<String>()

    /** HA configured ⇒ HA_ASSIST is a real free fallback. */
    private fun modeWithHa(available: Boolean = true): DegradedVoiceMode {
        prefs.connection.haBaseUrl = "http://ha.local:8123"
        prefs.connection.haAccessToken = "token"
        return build(available)
    }

    /** No HA, no local Whisper, and no Android recognizer (Fire OS / Lineage) ⇒ genuinely
     *  nothing free. This is the only true dead end now that the recognizer is a real rung. */
    private fun modeWithNothing(): DegradedVoiceMode {
        prefs.connection.haBaseUrl = ""
        prefs.connection.haAccessToken = ""
        prefs.voice.localSttUrl = ""
        return build(available = false)
    }

    /** A plug-and-play Google-services tablet: no HA, no Whisper box, but the built-in
     *  recognizer works — the case WS-D.1 had no answer for before it became a provider. */
    private fun modeWithOnlyAndroidRecognizer(): DegradedVoiceMode {
        prefs.connection.haBaseUrl = ""
        prefs.connection.haAccessToken = ""
        prefs.voice.localSttUrl = ""
        return build(available = true)
    }

    private fun build(available: Boolean) =
        DegradedVoiceMode(prefs, isProviderAvailable = { available }).also {
            it.onChanged = { on -> changes += on }
            it.onAnnounce = { line -> announcements += line }
        }

    /** HA-configured mode with an injected clock, for the "Not now" 24h expiry. */
    private fun modeWithClock(clock: () -> Long): DegradedVoiceMode {
        prefs.connection.haBaseUrl = "http://ha.local:8123"
        prefs.connection.haAccessToken = "token"
        return DegradedVoiceMode(prefs, clock, { true }).also {
            it.onChanged = { on -> changes += on }
            it.onAnnounce = { line -> announcements += line }
        }
    }

    @Before
    fun setUp() {
        prefs = HalitePreferences(RuntimeEnvironment.getApplication())
        changes = mutableListOf()
        announcements = mutableListOf()
    }

    @Test
    fun enteringWithAFreeEngineActivatesAndAnnouncesOnce() {
        val mode = modeWithHa()
        assertTrue(mode.enter("out of credits"))
        assertTrue(mode.isActive)
        assertEquals(ProviderType.HA_ASSIST, mode.sttChain.first())
        assertEquals(listOf(true), changes)
        assertEquals(1, announcements.size)

        // Every subsequent turn re-enters — it must NOT re-announce (that's a nag).
        mode.enter("out of credits")
        mode.enter("out of credits")
        assertEquals(listOf(true), changes)
        assertEquals(1, announcements.size)
    }

    @Test
    fun degradedChainKeepsTheFullFreeListNotJustTheLead() {
        // The bug this pins (on-device 2026-07-20): forcing only a lead left the manager's
        // FALLBACK on the configured (billed) chain — an offline local Whisper fell back to
        // Deepgram → 402. The whole FREE chain must be installed so the fallback stays free.
        prefs.connection.haBaseUrl = "http://ha.local:8123"
        prefs.connection.haAccessToken = "token"
        prefs.voice.localSttUrl = "http://box:9000"   // own-box Whisper configured (maybe offline)
        val mode = build(available = true)
        assertTrue(mode.enter("out of credits"))
        // Lead is local Whisper, but HA Assist MUST remain in the chain as the free fallback.
        assertTrue("chain must include the free fallback, not collapse to the lead",
            mode.sttChain.size >= 2)
        assertTrue(mode.sttChain.contains(ProviderType.LOCAL_WHISPER))
        assertTrue(mode.sttChain.contains(ProviderType.HA_ASSIST))
        assertFalse("NO billed engine may appear in the degraded chain",
            mode.sttChain.contains(ProviderType.DEEPGRAM))
    }

    @Test
    fun enteringWithNoFreeEngineFailsAndStaysInactive() {
        val mode = modeWithNothing()
        assertFalse("nothing free ⇒ caller must block + prompt", mode.enter("out of credits"))
        assertFalse(mode.isActive)
        assertTrue(mode.sttChain.isEmpty())
        assertTrue("a failed entry announces nothing", announcements.isEmpty())
        assertTrue(changes.isEmpty())
    }

    @Test
    fun androidRecognizerAloneIsEnoughToDegradeInsteadOfBlocking() {
        val mode = modeWithOnlyAndroidRecognizer()
        assertTrue("a Google-services tablet has a free rung now", mode.enter("out of credits"))
        assertEquals(ProviderType.ANDROID_NATIVE, mode.sttChain.first())
        // No HA ⇒ no free brain, so it's the device-control-only announcement.
        assertEquals(FreeVoicePlan.ANNOUNCE_DEVICE_ONLY, announcements.single())
    }

    @Test
    fun unavailableProviderCountsAsNoFreeEngine() {
        // HA is configured but its provider isn't actually usable right now.
        val mode = modeWithHa(available = false)
        assertFalse(mode.enter("out of credits"))
        assertFalse(mode.isActive)
    }

    @Test
    fun clearOnRefillDeactivatesAndReArmsTheAnnouncement() {
        val mode = modeWithHa()
        mode.enter("out of credits")

        mode.clear(full = true)
        assertFalse(mode.isActive)
        assertTrue(mode.sttChain.isEmpty())
        assertNull(mode.ttsProvider)
        assertEquals(listOf(true, false), changes)

        // A SECOND depletion must announce again — otherwise it degrades silently.
        mode.enter("out of credits")
        assertEquals(2, announcements.size)
        assertEquals(listOf(true, false, true), changes)
    }

    @Test
    fun probeClearDoesNotAnnounceOrReArm() {
        val mode = modeWithHa()
        mode.enter("out of credits")

        // The periodic probe drops the plan so the paid path is retried, but it is NOT a
        // credit restoration — no "we're back" signal, and no re-announce when it re-degrades.
        mode.clear(full = false)
        assertFalse(mode.isActive)
        assertEquals("probe must not claim credits returned", listOf(true), changes)

        mode.enter("out of credits")
        assertEquals("probe re-entry must not re-announce", 1, announcements.size)
    }

    @Test
    fun timeBoxedFallbackExpiresAfterItsWindow() {
        var nowMs = 1_000_000L
        val mode = modeWithClock { nowMs }
        val day = 24 * 60 * 60 * 1000L
        assertTrue(mode.enter("not now", expiresInMs = day))
        assertFalse("fresh temp fallback is not expired", mode.isExpired())
        nowMs += day - 1
        assertFalse("still inside the 24h window", mode.isExpired())
        nowMs += 2
        assertTrue("elapsed past the window ⇒ expired", mode.isExpired())
    }

    @Test
    fun noExpiryFallbackNeverExpires() {
        var nowMs = 1_000_000L
        val mode = modeWithClock { nowMs }
        assertTrue(mode.enter("don't show again", expiresInMs = 0L))
        nowMs += 100L * 24 * 60 * 60 * 1000L   // 100 days later
        assertFalse("a no-expiry degrade never expires", mode.isExpired())
    }

    @Test
    fun reEnterPreservesTheOriginalWindowAndClearResetsIt() {
        var nowMs = 1_000_000L
        val mode = modeWithClock { nowMs }
        val day = 24 * 60 * 60 * 1000L
        mode.enter("not now", expiresInMs = day)
        // A per-turn re-enter must NOT slide the window forward.
        nowMs += day / 2
        mode.enter("re-enter", expiresInMs = day)
        nowMs += day / 2 + 1
        assertTrue("window is measured from the FIRST entry, not the re-enter", mode.isExpired())
        // clear() drops the expiry so a later no-expiry entry isn't immediately 'expired'.
        mode.clear(full = true)
        mode.enter("don't show again", expiresInMs = 0L)
        assertFalse(mode.isExpired())
    }

    @Test
    fun clearWhenNotDegradedIsANoOp() {
        val mode = modeWithHa()
        mode.clear(full = true)
        mode.clear(full = true)
        assertTrue("no spurious onChanged for an already-clear mode", changes.isEmpty())
    }
}
