package com.dashieapp.Dashie.halite.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device name→entity GATE for the HA fast-path (20260717_HA_ENTITY_EXPOSURE_CONTRACT §4).
 *
 * Decides ONLY "does this utterance reference a known exposed HA entity?" — the yes/no that routes a
 * cascade turn to HA Assist (conversation/process) instead of the LLM brain. It does NOT resolve a
 * service call or pick a single entity; HA Assist owns the final matching + execution (it is far
 * better at verbs/areas/aliases/plurals than we would be). A loose match here costs at most one
 * Assist round-trip that returns no_intent_match and falls back to the brain — so we bias toward
 * precision (phrase match on the full name/alias) but tolerate the occasional miss.
 *
 * The entity list is the SAME enriched set the brain sees (`window.dashieHaVoiceContext.build()` →
 * `ha_entities[]`, contract §3): each item carries `friendly_name`, optional `aliases[]`, optional
 * `area`. Per §4 an utterance term matches an entity if it matches `friendly_name` OR any `alias`
 * (case-insensitive), optionally scoped by `area` / `device_area`. Pure + stateless → unit-testable.
 */
object HaEntityMatcher {

    /** The gate: should this turn go to HA Assist (vs the brain)? True when the utterance either
     *  NAMES a specific exposed entity, OR looks like a generic device command (control verb + a
     *  device-domain noun or a known area). The second arm is what catches collective/room commands
     *  ("dim the kitchen lights", "turn off the office lights") that don't name one entity — Assist
     *  resolves those from the area/domain. A false positive costs one Assist round-trip that returns
     *  no_intent_match/no_valid_targets and falls back to the brain, so we can afford to be generous. */
    fun shouldRouteToAssist(transcript: String, entities: JSONArray?, deviceArea: String? = null): Boolean {
        if (entities == null || entities.length() == 0) return false
        val hay = normalize(transcript)
        if (hay.isBlank()) return false
        return references(hay, entities) || looksLikeDeviceCommand(hay, entities)
    }

    /** True when [transcript] names one of the exposed [entities] (friendly_name ∪ aliases). Kept
     *  public for callers/tests that want the strict entity-name check on its own. */
    fun references(transcript: String, entities: JSONArray?, deviceArea: String? = null): Boolean {
        if (entities == null || entities.length() == 0) return false
        val hay = normalize(transcript)   // idempotent — safe when the caller already normalized
        if (hay.isBlank()) return false
        for (i in 0 until entities.length()) {
            val e = entities.optJSONObject(i) ?: continue
            if (nameMatches(hay, e)) return true
        }
        return false
    }

    // Single-word action verbs. "turn"/"switch" + "on"/"off" is handled separately in
    // hasControlVerbNorm because the particle is usually split from the verb in real speech
    // ("turn the lights off"). Music/volume/timer are intercepted upstream (kiosk-services.js).
    private val SIMPLE_CONTROL_VERBS = listOf(
        "dim", "brighten", "set", "lock", "unlock", "open", "close", "lower", "raise", "toggle", "activate",
    )
    // Generic device-domain nouns — a command over one of these (with a verb) is HA's job even when
    // no specific entity is named. Assist resolves the actual target from the room/domain.
    private val DOMAIN_NOUNS = listOf(
        "light", "lights", "lamp", "lamps", "sconce", "chandelier", "switch",
        "fan", "lock", "door", "garage", "blind", "blinds", "shade", "shades",
        "curtain", "curtains", "thermostat", "heat", "heater", "ac", "plug", "outlet", "scene",
    )

    /** Cheap pre-filter: does the utterance contain a device-control verb at all? Lets a caller skip
     *  the (WebView) entity build on turns that plainly aren't device commands (weather, chitchat). */
    fun hasControlVerb(transcript: String): Boolean = hasControlVerbNorm(normalize(transcript))

    // A future-time / delay qualifier means the user wants the command run LATER ("turn the string
    // lights on in 5 minutes", "at 9:30 turn off the porch light"). The fast-path must NOT claim
    // those — Assist would execute them NOW — so the caller defers to the brain, whose
    // schedule_action tool arms the alarm. Timers/music/volume are intercepted upstream in both
    // modes, so this can't hijack "set a timer for 5 minutes".
    // GENERATED SOURCE (contract #30, Phase B 2026-07-18): the pattern is emitted verbatim from the
    // JS canonical (patterns.js SCHEDULING_TIME_QUALIFIER_SOURCE) into GeneratedIntentData — the
    // former hand-mirror of homeassistant-intents.js is dead. Behavioral parity is pinned by the
    // hasSchedulingQualifier golden vectors.
    private val ABSOLUTE_TIME_QUALIFIER =
        Regex(GeneratedIntentData.SCHEDULING_TIME_QUALIFIER, RegexOption.IGNORE_CASE)

    /** True when [transcript] carries a scheduling qualifier — a relative delay ("in 5 minutes",
     *  word-numbers included) or an absolute time ("at 9:30", "tonight"). Runs on the word-number
     *  normalized text (NOT [normalize], which strips the ':' out of "9:30"). */
    fun hasSchedulingQualifier(transcript: String): Boolean {
        val norm = KotlinIntentPatterns.normalizeWordNumbers(transcript)
        return KotlinIntentPatterns.parseDuration(norm) != null || ABSOLUTE_TIME_QUALIFIER.containsMatchIn(norm)
    }

    // A SECOND-tool verb — music (play/shuffle/…) or cameras (show me the … camera) — means the
    // utterance spans MORE than Home Assistant ("turn off the lights AND play jazz"). The HA
    // fast-path must NOT claim it: Assist would run the HA half and silently DROP the other tool
    // (the string-lights+beatles gap found on-device 2026-07-18). Defer to the brain, which emits a
    // {type:'multi'} turn the device fans out. `play` excludes "play room"/"playroom" so a real HA
    // command ("turn on the play room lights") still fast-paths. Precision-lenient: a false match
    // just routes a single-HA command through the brain (correct, slightly slower).
    // GENERATED SOURCE (contract #34, Phase B 2026-07-18): emitted verbatim from the JS canonical
    // (patterns.js SECOND_TOOL_VERB_SOURCE) — the former hand-mirror is dead; parity pinned by the
    // hasSecondToolVerb golden vectors.
    private val SECOND_TOOL_VERB =
        Regex(GeneratedIntentData.SECOND_TOOL_VERB, RegexOption.IGNORE_CASE)

    /** True when [transcript] pairs an HA control command with a DIFFERENT tool's verb (music /
     *  cameras) → a compound the brain should handle as `multi`, not a fast-path HA-only turn. */
    fun hasSecondToolVerb(transcript: String): Boolean = SECOND_TOOL_VERB.containsMatchIn(normalize(transcript))

    /** Control verb present? Handles a SPLIT "turn/switch … on/off" — real speech puts the object
     *  between the verb and the particle ("turn the string lights off", "switch the fan on"), which a
     *  contiguous "turn off" match misses — plus the single-word verbs. */
    private fun hasControlVerbNorm(hay: String): Boolean {
        if ((containsPhrase(hay, "turn") || containsPhrase(hay, "switch")) &&
            (containsPhrase(hay, "on") || containsPhrase(hay, "off"))) return true
        return SIMPLE_CONTROL_VERBS.any { containsPhrase(hay, it) }
    }

    /** Generic device command = a control verb AND (a device-domain noun OR a known area name). The
     *  area set is derived from the live entity list, so it tracks the user's actual rooms. */
    private fun looksLikeDeviceCommand(normalizedHay: String, entities: JSONArray): Boolean {
        if (!hasControlVerbNorm(normalizedHay)) return false
        if (DOMAIN_NOUNS.any { containsPhrase(normalizedHay, it) }) return true
        for (i in 0 until entities.length()) {
            val area = entities.optJSONObject(i)?.optString("area")?.takeIf { it.isNotBlank() } ?: continue
            if (containsPhrase(normalizedHay, normalize(area))) return true
        }
        return false
    }

    /** An entity matches when its friendly_name (or any alias) appears as a whole-word phrase in the
     *  utterance. Whole-word (not bare substring) so "lights" inside "String Lights" can't be matched
     *  by an unrelated "tell me about the northern lights" — the full name/alias must be present. */
    private fun nameMatches(normalizedHay: String, entity: JSONObject): Boolean {
        val names = ArrayList<String>(4)
        entity.optString("friendly_name").takeIf { it.isNotBlank() }?.let { names.add(it) }
        entity.optJSONArray("aliases")?.let { a ->
            for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        for (raw in names) {
            val needle = normalize(raw)
            if (needle.isBlank()) continue
            if (containsPhrase(normalizedHay, needle)) return true
        }
        return false
    }

    /** Whole-word phrase containment: `needle` must appear in `hay` bounded by string start/end or a
     *  space, so "office" matches "the office" but not "officer". Both are already space-normalized. */
    private fun containsPhrase(hay: String, needle: String): Boolean {
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) return false
            val beforeOk = idx == 0 || hay[idx - 1] == ' '
            val end = idx + needle.length
            val afterOk = end == hay.length || hay[end] == ' '
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
    }

    /** Lowercase, strip punctuation to spaces, collapse whitespace — so "String Lights!" and
     *  "the string lights" compare cleanly. */
    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
}
