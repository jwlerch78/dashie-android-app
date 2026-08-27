package com.dashieapp.Dashie.halite.voice

/**
 * Pure, stateless helpers for the cascade Dialog (DLG-6) loop: the leading wake-word strip and the
 * end-intent check.
 *
 * End-intent ownership is SPLIT by path (contract #5):
 *  • The cascade-dialog loop routes each turn to the brain, which owns end-of-conversation via
 *    `dialog-policy.ts` → `metadata.end_conversation` (honored as `result.endConversation`). No
 *    native check there.
 *  • The DLG-6 keep-dialog-open loop re-arms after a LOCAL command; an end phrase ("never mind")
 *    is a local "silent command" that NEVER reaches the brain, so it MUST be recognized here.
 *    `isEndIntent` uses [GeneratedEndIntents] — GENERATED from the same `dialog-policy.ts` (exact
 *    `END_INTENT_PHRASES` + substring `HARD_STOP_PHRASES`) so the two can't drift.
 *
 * No pipeline state and no dependencies, so it's safe to call from anywhere. The stateful cascade
 * loop (STT lifecycle, brain turns, re-arm) stays in the coordinator for now — see the pending
 * CascadeDialogController extraction.
 */
object CascadeDialogSupport {
    // Leading wake word on a follow-up ("hey dashie, ...") — strip before the brain call / intent
    // check. STT spellings: Dashie/Dashi/Dashy/Dashee/Deshy/Deshi… (FB23).
    private val WAKE_PREFIX = Regex(
        """^(?:hey|ok|okay)?[\s,]*d[ae]sh(?:ie|i|y|ee)\b[\s,.!?]*""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The GREETING-ONLY form, for when the capture clipped the name half.
     * The name is mandatory in [WAKE_PREFIX], so a transcript that caught only "Hey" matched
     * nothing and went to the brain verbatim — John saw `"- Hey, tell me a fun fact about Jupiter."`
     *. Worse than cosmetic: [isEndIntent] matches EXACTLY, so `"hey thanks"` ≠
     * `"thanks"` and a real end-intent with a clipped name is MISSED — the dialog stays open, the
     * same won't-close family by a different door.
     * ⚠️ **`hey` and `ok`/`okay` are treated differently on purpose.** "hey" at the start of an
     * utterance is essentially never content, so any separator strips it — which is what rescues
     * the end-intent case `"hey thanks"` (space, no punctuation). But "ok"/"okay" routinely BEGIN
     * real content ("okay google what time is it", "ok fine"), so those strip only before
     * PUNCTUATION: "okay, turn the lights on" loses the greeting, "okay google …" keeps it.
     * A bare "ok"/"okay"/"hey" as the whole utterance is untouched either way — stripping it to
     * empty would make `cascadeDialogTurn` close the dialog on it.
     */
    private val GREETING_ONLY_PREFIX = Regex(
        """^(?:hey[\s,.!?]+|(?:ok|okay)[,.!?]+[\s]*)""",
        RegexOption.IGNORE_CASE,
    )

    /** Leading junk the decoder prepends to a capture — a dangling dash, the `♪` it emits for
     *  non-speech. Stripped before the wake forms so `"- Hey, …"` reaches them at all. */
    private val LEADING_JUNK = Regex("""^[\s\-–—•·♪♫"']+""")

    /** Strip a leading wake word from a follow-up utterance ("hey dashie, turn…" → "turn…").
     *  Order matters: junk first (it hides the greeting), then the full wake word, then the
     *  greeting-only fallback for a capture that clipped the name. */
    fun stripWakePrefix(transcript: String): String {
        var t = transcript.trim().replace(LEADING_JUNK, "")
        val afterWake = t.replace(WAKE_PREFIX, "")
        // Only fall back to the greeting form when the full wake word did NOT match — otherwise
        // "hey dashie, ok fine" would lose its "ok" too.
        t = if (afterWake != t) afterWake else t.replace(GREETING_ONLY_PREFIX, "")
        return t.replace(LEADING_JUNK, "").trim()
    }

    /**
     * Is [transcript] an end-intent? Mirrors `dialog-policy.ts isEndIntent`: strip the wake prefix,
     * normalize (lowercase + strip ALL .!?, — STT punctuates mid-utterance, "okay. thanks."),
     * then exact-match [GeneratedEndIntents.END_INTENT_PHRASES]
     * or substring-match [GeneratedEndIntents.HARD_STOP_PHRASES]. Only the DLG-6 re-arm path uses this
     * (the cascade-dialog loop defers to the brain).
     */
    fun isEndIntent(transcript: String): Boolean {
        val t = stripWakePrefix(transcript).lowercase()
            .replace(Regex("[.!?,]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return false
        if (GeneratedEndIntents.END_INTENT_PHRASES.contains(t)) return true
        if (GeneratedEndIntents.HARD_STOP_PHRASES.any { t.contains(it) }) return true
        // Trailing polite closer at the END ("got it, thanks") — end-anchored, not substring,
        // so "thanks, what's next" (closer up front) stays open.
        return GeneratedEndIntents.TRAILING_CLOSE_PHRASES.any { t == it || t.endsWith(" $it") }
    }
}
