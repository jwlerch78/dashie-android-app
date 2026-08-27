package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pre-flight that the 2026-07-29 Fire-tablet failure needed: HA's *preferred* pipeline
 * was the stock "Home Assistant" one with no stt_engine and no tts_engine, and every turn died on
 * `validation-error: the pipeline does not support speech-to-text`.
 *
 * The real pipeline list from that box is used as the fixture so the test fails if the parse
 * contract (null engines ⇒ hasStt/hasTts false) ever drifts.
 */
class HaPipelineCapabilityCacheTest {

    private fun pipeline(
        id: String,
        name: String,
        stt: String? = null,
        tts: String? = null,
        preferred: Boolean = false,
    ) = HaAssistClient.Pipeline(
        id = id, name = name, sttEngine = stt, ttsEngine = tts,
        conversationEngine = "conversation.$id", language = "en", isPreferred = preferred,
    )

    /** The Fire tablet's actual HA config: preferred pipeline is the one that CAN'T hear. */
    private val fireTabletPipelines = listOf(
        pipeline("01k9zkrpg2nwx67zy6f3xskb2t", "Home Assistant", preferred = true),
        pipeline("01kdr5hb4ccxzf0r8n2vrkwpzw", "Dashie", stt = "stt.faster_whisper", tts = "tts.piper"),
        pipeline("01kyn6rqspfxmewzw6jkfb0c6k", "Chickadee", stt = "stt.chickadee_stt", tts = "tts.chickadee_tts"),
    )

    private var clock = 1_000L
    private fun cache(ttlMs: Long = 10 * 60 * 1000L) =
        HaPipelineCapabilityCache(nowMs = { clock }, ttlMs = ttlMs)

    @Test
    fun `a cold cache knows nothing and blocks nothing`() {
        val c = cache()
        assertFalse(c.isFresh())
        assertNull("an unknown pipeline must not look like a broken one", c.capsFor(null))
        assertNull(c.capsFor("01kdr5hb4ccxzf0r8n2vrkwpzw"))
    }

    @Test
    fun `null engines mean the stage is unsupported`() {
        val c = cache().apply { update(fireTabletPipelines) }
        val preferred = c.capsFor(null)
        assertNotNull(preferred)
        assertEquals("Home Assistant", preferred!!.name)
        assertFalse("stt_engine was null — this is the whole bug", preferred.hasStt)
        assertFalse(preferred.hasTts)
    }

    @Test
    fun `an explicit pipeline id resolves independently of the preferred one`() {
        val c = cache().apply { update(fireTabletPipelines) }
        val dashie = c.capsFor("01kdr5hb4ccxzf0r8n2vrkwpzw")!!
        assertTrue(dashie.hasStt)
        assertTrue(dashie.hasTts)
        assertNull("an id HA no longer has must read as unknown", c.capsFor("deleted-pipeline"))
    }

    @Test
    fun `freshness expires and invalidate resets to cold`() {
        val c = cache(ttlMs = 100L)
        c.update(fireTabletPipelines)
        assertTrue(c.isFresh())
        clock += 101
        assertFalse("a stale list must be refetched", c.isFresh())

        clock = 1_000L
        c.update(fireTabletPipelines)
        c.invalidate()
        assertFalse(c.isFresh())
        assertNull(c.capsFor(null))
    }

    @Test
    fun `a list with no preferred flag yields no preferred caps`() {
        val c = cache().apply {
            update(listOf(pipeline("only", "Only", stt = "stt.whisper", tts = "tts.piper")))
        }
        assertNull(c.capsFor(null))
        assertNotNull(c.capsFor("only"))
    }
}
