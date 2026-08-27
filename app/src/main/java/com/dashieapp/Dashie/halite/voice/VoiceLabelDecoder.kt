package com.dashieapp.Dashie.halite.voice

/**
 * Friendly display labels for local/HA TTS voice ids (punch #3). The Kotlin port of the
 * console's decoder (`dashie-console/js/lib/voice-ai-options.js` `_piperVoiceLabel` /
 * `localVoiceLabel` / `_PIPER_QUALITY` / `_KOKORO_ACCENT`) so the tablet's Voice row reads
 * "Amy (fast)" instead of the raw `en_US-amy-low`. DISPLAY-ONLY — the stored value stays the
 * real voice id; this only decodes what's shown. Behavioral parallel of the console (see
 * JS_KOTLIN_CONTRACTS.md #44), pinned by VoiceLabelDecoderTest golden vectors.
 *
 * Both engines encode everything the user cares about IN the id, so decode it:
 *   Piper  `en_US-amy-low`  → "Amy (fast)"           (low→fast, medium→balanced, high→high quality)
 *   Kokoro `af_nicole`      → "Nicole (American female)"
 * Anything unrecognized keeps its given label (capitalized).
 */
object VoiceLabelDecoder {

    // Piper names voices <speaker>-<low|medium|high> by synthesis speed/quality; relabel to
    // what the user cares about (mirrors the console _PIPER_QUALITY).
    private val PIPER_QUALITY = mapOf(
        "low" to "fast", "med" to "balanced", "medium" to "balanced", "high" to "high quality",
    )

    // Kokoro encodes accent in the id's first letter (af_heart = American female).
    private val KOKORO_ACCENT = mapOf(
        "a" to "American", "b" to "British", "e" to "Spanish", "f" to "French", "h" to "Hindi",
        "i" to "Italian", "j" to "Japanese", "p" to "Portuguese", "z" to "Mandarin",
    )

    private val PAREN = Regex("""^(.*?)\s*\((low|med|medium|high)\)\s*$""", RegexOption.IGNORE_CASE)
    private val KOKORO = Regex("""^([abefhijpz])([fm])_(.+)$""")
    private val PIPER_ID = Regex("""^[a-z]{2}_[A-Z]{2}-""")

    private fun capFirst(s: String): String =
        if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

    /**
     * Prettify a Piper voice: capitalize the speaker and relabel the quality suffix. Handles
     * both HA's "amy (low)" name form and the raw "en_US-amy-low" id. A non-Piper-shaped name
     * is returned capitalized. Mirrors the console `_piperVoiceLabel`.
     */
    fun piperVoiceLabel(name: String?, voiceId: String?): String {
        val src = (name ?: voiceId) ?: ""
        var speaker: String? = null
        var quality: String? = null
        val paren = PAREN.matchEntire(src.trim())
        if (paren != null) {
            speaker = paren.groupValues[1].trim()
            quality = paren.groupValues[2].lowercase()
        } else {
            val parts = (voiceId ?: "").split("-")
            val q = parts.lastOrNull()?.lowercase()
            if (parts.size >= 2 && q != null && PIPER_QUALITY.containsKey(q)) {
                quality = q
                speaker = parts.subList(1, parts.size - 1).joinToString("-").ifEmpty { parts[0] }
            }
        }
        if (speaker.isNullOrEmpty() || quality == null) return if (src.isNotEmpty()) capFirst(src) else src
        return "${capFirst(speaker)} (${PIPER_QUALITY[quality] ?: quality})"
    }

    /**
     * Human label for a local-engine voice id — Kokoro `af_nicole` → "Nicole (American female)",
     * Piper ids → [piperVoiceLabel]. Unrecognized keeps [fallback] (or the id). Mirrors the
     * console `localVoiceLabel`.
     */
    fun localVoiceLabel(value: String?, fallback: String? = null): String {
        val id = value ?: ""
        val kokoro = KOKORO.matchEntire(id)
        if (kokoro != null) {
            val (lang, gender, name) = kokoro.destructured
            val accent = KOKORO_ACCENT[lang] ?: ""
            val who = listOf(accent, if (gender == "m") "male" else "female")
                .filter { it.isNotBlank() }.joinToString(" ")
            val pretty = capFirst(name).replace("_", " ")
            return if (who.isNotBlank()) "$pretty ($who)" else pretty
        }
        if (PIPER_ID.containsMatchIn(id)) return piperVoiceLabel(fallback, id)
        return fallback ?: id
    }
}
