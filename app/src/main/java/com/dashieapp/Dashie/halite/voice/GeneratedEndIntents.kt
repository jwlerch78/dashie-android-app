// GENERATED FILE — DO NOT EDIT BY HAND.
// Source of truth: dashieapp_staging/supabase/functions/voice-conversation/dialog-policy.ts
// Regenerate:      node scripts/gen-android-end-intents.mjs  (then rebuild the APK)
package com.dashieapp.Dashie.halite.voice

/**
 * End-intent vocabulary, generated from the brain's unified dialog policy
 * (dialog-policy.ts). Used by CascadeDialogSupport.isEndIntent for the DLG-6
 * keep-dialog-open loop, where an end phrase is a LOCAL command that never reaches
 * the brain (so metadata.end_conversation can't cover it). Contract #5.
 */
object GeneratedEndIntents {
    /** Whole-utterance closers (exact match after normalize). */
    val END_INTENT_PHRASES: Set<String> = setOf(
        "thanks",
        "thank you",
        "that's all",
        "thats all",
        "never mind",
        "nevermind",
        "ok thanks",
        "okay thanks",
        "ok thank you",
        "okay thank you",
        "stop",
        "done",
        "goodbye",
        "nothing",
        "shut up",
        "stop talking",
        "be quiet",
        "quiet",
        "shush",
        "stop it",
        "enough",
        "that's enough"
    )

    /** Unambiguous stop imperatives that also close on a SUBSTRING match. */
    val HARD_STOP_PHRASES: List<String> = listOf(
        "shut up",
        "stop talking"
    )

    /** Polite closers that ALSO close when they END a longer utterance ("got it, thanks").
     *  END-anchored (suffix), NOT substring — see CascadeDialogSupport.isEndIntent. */
    val TRAILING_CLOSE_PHRASES: List<String> = listOf(
        "thanks",
        "thank you",
        "that's all",
        "thats all",
        "goodbye"
    )
}
