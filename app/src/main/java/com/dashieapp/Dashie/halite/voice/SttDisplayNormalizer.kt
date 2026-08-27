package com.dashieapp.Dashie.halite.voice

/**
 * Cosmetic normalization of STT transcripts before they hit the screen.
 *
 * Deepgram / Gemini Live routinely transcribe the product name as "dashi",
 * "dashy", "dashee", or "dashey"; showing those in the overlay reads as a bug
 * to the user. DISPLAY-ONLY on purpose: the raw transcript still goes to the
 * brain/intent layer untouched, so this can never change routing or intent —
 * it only fixes what the person reads back.
 */
object SttDisplayNormalizer {

    // Standalone mishearings of "Dashie". Word-bounded so real words that merely
    // contain the stem ("dashing", "haberdashery") are untouched.
    private val DASHIE_VARIANTS = Regex("""\bdash(?:i|y|ee|ey)\b""", RegexOption.IGNORE_CASE)

    /** Replace Dashie mishearings, preserving the leading capitalization of each match. */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        return DASHIE_VARIANTS.replace(text) { m ->
            when {
                m.value.all { it.isUpperCase() } -> "DASHIE"
                m.value.first().isUpperCase() -> "Dashie"
                else -> "dashie"
            }
        }
    }
}
