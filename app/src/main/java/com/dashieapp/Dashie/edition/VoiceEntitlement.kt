package com.dashieapp.Dashie.edition

/**
 * "May voice run on this device?"
 *
 * ## 🔴 As of 2026-08-02 the answer is unconditionally YES, in both editions
 *
 * the ruling: **voice is just free.** The one-time voice licence and its 30-day trial are
 * retired; the Dashie subscription and its own trial are untouched, because the subscription is
 * the broader product and was never a voice gate. Metering is unaffected — consuming AI / TTS /
 * LLM / tool services still spends credits through `CreditService` and the #70 capability
 * predicate. "Free" describes the FEATURE having no licence gate, not the services being unpaid.
 *
 * ## So why does this interface still exist?
 *
 * Kept deliberately (2026-08-02) rather than deleted with the rest of the licence surface.
 * It is one always-true method, and the temptation is to inline it — but this is the reserved
 * landing spot for the next thing that can answer "no". The 2c seam decision said so explicitly:
 * if Chickadee ever gains a hosted option, *"is voice available?" lands on `isVoiceAllowed()` —
 * today unconditional true, later "true unless the service says otherwise." Same interface,
 * different impl, no rework.* Deleting it would forfeit that for the sake of removing one file.
 *
 * ⚠️ It is therefore **not** dead code in the authored-but-unreached sense: the call sites are
 * live and they branch on it. What changed is only that both implementations currently answer
 * the same way.
 *
 * ## What used to be here
 *
 * `hasLicensing`, `statusLabel()`, `voiceSummaryNote()`, `licenseStatusJson()`, `refresh()` and
 * `publishHouseholdSeedIfLicensed()` — all licence vocabulary, all deleted with the licence.
 * The thinness note that used to justify keeping them at arm's length is now moot: there is no
 * licence to keep at arm's length.
 */
interface VoiceEntitlement {

    /**
     * The gate. Called before voice initialises and on every toggle-on.
     *
     * Both editions: always `true` — see the class note for why the question survives its
     * current answer.
     */
    fun isVoiceAllowed(): Boolean
}
