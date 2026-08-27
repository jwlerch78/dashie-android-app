package com.dashieapp.Dashie.halite.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [HaEntityMatcher] — the HA fast-path gate (20260717_HA_ENTITY_EXPOSURE_CONTRACT §4).
 * Robolectric only for org.json; the matcher itself is pure. The gate decides HA-vs-brain routing:
 * a false positive costs one Assist round-trip (no_intent_match → brain), a false negative sends a
 * device command to the brain — so the load-bearing cases are the friendly_name/alias hit and the
 * whole-word guard that keeps an unrelated "northern lights" from matching "String Lights".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaEntityMatcherTest {

    private fun entities(vararg specs: Pair<String, List<String>>): JSONArray {
        val arr = JSONArray()
        for ((name, aliases) in specs) {
            val e = JSONObject().put("entity_id", "switch." + name.lowercase().replace(' ', '_'))
                .put("friendly_name", name)
            if (aliases.isNotEmpty()) e.put("aliases", JSONArray(aliases))
            arr.put(e)
        }
        return arr
    }

    @Test fun `matches on friendly_name`() {
        val ents = entities("String Lights" to emptyList())
        assertTrue(HaEntityMatcher.references("turn on the string lights", ents))
        assertTrue(HaEntityMatcher.references("String Lights on please", ents))
    }

    @Test fun `matches on an alias`() {
        val ents = entities("String Lights" to listOf("fairy lights"))
        assertTrue(HaEntityMatcher.references("turn off the fairy lights", ents))
    }

    @Test fun `case and punctuation insensitive`() {
        val ents = entities("Office Lamp" to emptyList())
        assertTrue(HaEntityMatcher.references("Turn on the OFFICE LAMP!", ents))
    }

    @Test fun `no match when no entity is named`() {
        val ents = entities("String Lights" to listOf("fairy lights"))
        assertFalse(HaEntityMatcher.references("what's the weather tomorrow", ents))
    }

    @Test fun `whole-word guard - unrelated phrase sharing a word does not match`() {
        val ents = entities("String Lights" to emptyList())
        // "lights" appears, but the full entity name "string lights" does not.
        assertFalse(HaEntityMatcher.references("tell me about the northern lights", ents))
    }

    @Test fun `whole-word guard - substring inside a larger word does not match`() {
        val ents = entities("Office" to emptyList())
        assertFalse(HaEntityMatcher.references("call the officer", ents))
    }

    @Test fun `empty or null entities never match`() {
        assertFalse(HaEntityMatcher.references("turn on the string lights", JSONArray()))
        assertFalse(HaEntityMatcher.references("turn on the string lights", null))
    }

    // ── shouldRouteToAssist: the broadened gate (verb + domain-noun/area) ──

    private fun withAreas(vararg specs: Triple<String, String, List<String>>): JSONArray {
        val arr = JSONArray()
        for ((name, area, aliases) in specs) {
            val e = JSONObject().put("entity_id", "light." + name.lowercase().replace(' ', '_'))
                .put("friendly_name", name).put("area", area)
            if (aliases.isNotEmpty()) e.put("aliases", JSONArray(aliases))
            arr.put(e)
        }
        return arr
    }

    @Test fun `routes a specific-entity command`() {
        val ents = entities("String Lights" to emptyList())
        assertTrue(HaEntityMatcher.shouldRouteToAssist("turn on the string lights", ents))
    }

    @Test fun `routes a room+domain command that names no single entity`() {
        // Entities are "Kitchen 1"/"Kitchen 2" in area "Kitchen" — "the kitchen lights" matches
        // neither friendly_name, but verb "dim" + area "kitchen" + noun "lights" must still route.
        val ents = withAreas(
            Triple("Kitchen 1", "Kitchen", emptyList()),
            Triple("Kitchen 2", "Kitchen", emptyList()),
        )
        assertTrue(HaEntityMatcher.shouldRouteToAssist("dim the kitchen lights to 50 percent", ents))
    }

    @Test fun `routes a verb+domain command (turn off the lights)`() {
        val ents = withAreas(Triple("Kitchen 1", "Kitchen", emptyList()))
        assertTrue(HaEntityMatcher.shouldRouteToAssist("turn off the lights", ents))
    }

    @Test fun `routes a verb+area command with no domain noun`() {
        val ents = withAreas(Triple("Office Lamp", "Office", emptyList()))
        assertTrue(HaEntityMatcher.shouldRouteToAssist("turn off the office", ents))
    }

    @Test fun `does NOT route a non-command (no control verb)`() {
        val ents = withAreas(Triple("Kitchen 1", "Kitchen", emptyList()))
        assertFalse(HaEntityMatcher.shouldRouteToAssist("what's the weather in the kitchen", ents))
        assertFalse(HaEntityMatcher.shouldRouteToAssist("tell me a joke about lights", ents))
    }

    @Test fun `hasControlVerb pre-filter`() {
        assertTrue(HaEntityMatcher.hasControlVerb("turn on the string lights"))
        assertTrue(HaEntityMatcher.hasControlVerb("dim the kitchen lights to 50 percent"))
        assertTrue(HaEntityMatcher.hasControlVerb("lock the front door"))
        assertFalse(HaEntityMatcher.hasControlVerb("what's the weather tomorrow"))
        assertFalse(HaEntityMatcher.hasControlVerb("who won the game last night"))
    }

    @Test fun `hasControlVerb handles SPLIT turn-object-particle (real speech)`() {
        // The bug the kiosk hit: STT gives "Turn the string lights off", not "turn off …".
        assertTrue(HaEntityMatcher.hasControlVerb("turn the string lights off"))
        assertTrue(HaEntityMatcher.hasControlVerb("switch the fan on"))
        assertTrue(HaEntityMatcher.hasControlVerb("turn the kitchen lights on"))
        // "on"/"off" alone (no turn/switch) is NOT a control verb — avoids "the meeting is on friday".
        assertFalse(HaEntityMatcher.hasControlVerb("what is on tv tonight"))
    }

    @Test fun `routes a split-particle command by entity name`() {
        val ents = entities("String Lights" to emptyList())
        assertTrue(HaEntityMatcher.shouldRouteToAssist("turn the string lights off", ents))
    }

    @Test fun `routes a split-particle room command`() {
        val ents = withAreas(Triple("Kitchen 1", "Kitchen", emptyList()))
        assertTrue(HaEntityMatcher.shouldRouteToAssist("turn the kitchen lights off", ents))
    }

    // ── hasSchedulingQualifier: timed commands must defer to the brain (schedule_action) ──
    // Mirrors the JS HA classifier guard (homeassistant-intents.js ABSOLUTE_TIME_QUALIFIER +
    // parseDuration). A hit here means tryHaFastPath must NOT claim the command.

    @Test fun `scheduling qualifier - relative delay`() {
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("turn off the lights in 10 minutes"))
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("turn the string lights on in 5 minutes"))
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("lock the door in an hour"))
        // Word numbers normalize before the duration parse ("five" → 5).
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("turn off the lights in five minutes"))
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("dim the lamp in half an hour"))
    }

    @Test fun `scheduling qualifier - absolute time`() {
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("at 9:30 turn off the porch light"))
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("turn on the lights at 7 pm"))
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("turn off the string lights tonight"))
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("close the blinds tomorrow"))
        assertTrue(HaEntityMatcher.hasSchedulingQualifier("turn the heater on this evening"))
    }

    @Test fun `no scheduling qualifier - immediate commands stay on the fast-path`() {
        assertFalse(HaEntityMatcher.hasSchedulingQualifier("turn off the lights"))
        assertFalse(HaEntityMatcher.hasSchedulingQualifier("turn the string lights off"))
        assertFalse(HaEntityMatcher.hasSchedulingQualifier("dim the kitchen lights to 50 percent"))
        assertFalse(HaEntityMatcher.hasSchedulingQualifier("set the thermostat to 72"))
        assertFalse(HaEntityMatcher.hasSchedulingQualifier("lock the front door"))
    }
}
