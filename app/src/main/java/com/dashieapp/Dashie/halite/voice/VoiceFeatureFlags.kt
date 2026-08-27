package com.dashieapp.Dashie.halite.voice

/**
 * Build-time switches for voice features that are BUILT but deliberately not exposed to users yet.
 *
 * These are not settings. A setting is a choice we're asking the user to make; a flag here is us
 * deciding not to ask. Each one hides UI *and* neutralizes the behavior, so a flag being off can
 * never leave a user in a state they can't see or change.
 */
object VoiceFeatureFlags {

    /**
     * DLG-6 "Keep dialog open" / "Open dialog after commands" — after a device COMMAND (not just a
     * question), re-arm the mic so the user can chain "dim the lights" → "lock the doors" without
     * saying "Hey Dashie" again.
     *
     * OFF since 2026-07-18 (John): the feature works and is device-verified, but it isn't clearly
     * something users want once they've lived with it. Hidden rather than deleted so it can come
     * back if anyone asks for it.
     *
     * ⚠️ Hiding the toggle alone would NOT have been safe. The console seeded
     * `voice.alwaysOpenDialog = true` for the cloud and hybrid presets, so accounts already have it
     * stored ON — hiding only the UI would have left those users chaining after every command with
     * no way to turn it off. That's why this flag gates the READ (see the ReArmPolicy call sites in
     * VoicePipelineCoordinator), not just the schema item.
     *
     * Stored/account values are deliberately left intact: the flag makes them inert, so re-enabling
     * restores each user's prior choice instead of having silently overwritten it with false.
     *
     * To re-enable, all of:
     *  1. this flag → true (restores the Kotlin toggle + the behavior),
     *  2. console `js/pages/voice-ai.js` → `KEEP_DIALOG_OPEN_UI = true`, in BOTH the `dashie-console`
     *     repo and the byte-identical `dashieapp_staging/console/` mirror, or the mirror strands,
     *  3. decide whether the cloud/hybrid preset should resume seeding it ON — the two
     *     `saveDefault('voice.alwaysOpenDialog', true)` calls were removed, not just gated, so that
     *     "on by default" is a fresh decision rather than a silently restored one.
     *
     * The policy itself ([ReArmPolicy], 13 unit tests) is untouched and still correct — with this
     * flag off it simply never sees `alwaysOpenDialog = true`.
     */
    const val KEEP_DIALOG_OPEN_ENABLED = false
}
