package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the no-device-tool path of [BrainToolResolver] — in particular the HARD RULE that a raw
 * JSON payload is never spoken aloud. That guard is belt-and-suspenders for a brain-side
 * normalizer miss, so it has no natural integration coverage; if it silently broke, the device
 * would read a tool-request object to the user.
 *
 * The tool arms themselves need the WebView bridges and stay covered by the on-device smoke.
 *
 * Robolectric only for android.util.Log (the guard logs when it fires); the logic is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainToolResolverTest {

    @Test
    fun `a normal answer is spoken as-is and is not flagged failed`() {
        val s = plain("Miles Davis was a jazz trumpeter.")
        assertEquals("Miles Davis was a jazz trumpeter.", s.voice)
        assertFalse(s.failed)
    }

    @Test
    fun `a raw json object is never spoken`() {
        val s = plain("""{"tool":"weather","query":{}}""")
        assertTrue("must not read JSON aloud", s.voice.startsWith("Sorry, I didn't quite catch that"))
    }

    @Test
    fun `a raw json array is never spoken`() {
        val s = plain("""[{"a":1}]""")
        assertTrue(s.voice.startsWith("Sorry, I didn't quite catch that"))
    }

    @Test
    fun `leading whitespace does not smuggle json past the guard`() {
        val s = plain("\n   {\"tool\":\"music\"}")
        assertTrue(s.voice.startsWith("Sorry, I didn't quite catch that"))
    }

    @Test
    fun `a sentence merely containing a brace is still spoken`() {
        val s = plain("The set list was {mostly} improvised.")
        assertEquals("The set list was {mostly} improvised.", s.voice)
    }

    @Test
    fun `no voice degrades to the apology AND is flagged failed`() {
        // The flag matters: a fired scheduled action must render an ERROR card, not show this
        // shrug in the normal "answer" card as though it were a real reply.
        listOf(null, "", "   ").forEach { v ->
            val s = plain(v)
            assertTrue("'$v' should apologize", s.voice.startsWith("Sorry, I couldn't get an answer"))
            assertTrue("'$v' must be flagged failed", s.failed)
        }
    }

    @Test
    fun `the json clarification is not flagged failed`() {
        // It's a re-ask, not an outage — callers shouldn't render an error card for it.
        assertFalse(plain("""{"x":1}""").failed)
    }

    private fun plain(v: String?) = BrainToolResolver.plainVoice(v)
}
