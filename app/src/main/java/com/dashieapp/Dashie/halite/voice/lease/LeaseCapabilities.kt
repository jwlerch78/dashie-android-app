package com.dashieapp.Dashie.halite.voice.lease

/**
 * The capability vocabulary — `CAPABILITY_LEASE_WIRE.md` §4d, `JS_KOTLIN_CONTRACTS #70`.
 *
 * ## Why these are constants and not string literals at the call sites
 *
 * Thread B's warning is the whole reason this file exists: **a misspelled capability name is
 * fail-safe-INVISIBLE.** [CapabilityLease.allows] answers by list membership, so `allows("vioce")`
 * returns false forever — no exception, no warning, just a device that quietly runs on free
 * engines it did not need to. That is the authored-but-unreached shape with the failure hidden
 * behind a correct-looking downgrade. One definition each, referenced by constant.
 *
 * ## What each name obliges the DEVICE to do when it is ABSENT
 *
 * | Capability | The box lends | Absent ⇒ the device must |
 * |---|---|---|
 * | [VOICE] | metered STT/TTS on the box's keys (Deepgram · ElevenLabs · Inworld) | switch to the free STT chain and free TTS. **Voice keeps working** |
 * | [AI] | the box's brain — a metered LLM key, or account credits | stop calling the brain; fall back to the device-control lane / HA Assist |
 * | [TOOLS] | metered tool providers (Tavily · Brave · Pexels · APISports) the BRAIN calls | 🔴 **nothing at all** — see [TOOLS] |
 *
 * ## 🔑 This is an ENGINE SELECTOR, not a security control
 *
 * B verified the spend path gates at **household** scope (`converse.js:174`) with no
 * per-capability check anywhere on it. That is correct by design, and it bounds how defensively
 * this should be built: a device that reads the list wrong **wastes one request — it cannot
 * overspend.** So there is no enforcement layer here, and there should not be one. The list
 * exists so the device picks the right engine without a failed round-trip, and so an operator can
 * tell two opposite causes apart.
 */
object LeaseCapabilities {

    /** Metered STT/TTS on the household's keys. Absent ⇒ free STT chain + free TTS. */
    const val VOICE = "voice"

    /** The household's brain (metered LLM key or account credits). Absent ⇒ do not call it. */
    const val AI = "ai"

    /**
     * Metered tool providers the BRAIN calls on the satellite's behalf.
     *
     * 🔴 **Absent obliges the device to do NOTHING, and this has to be stated because the naive
     * reading is actively harmful.** "Absent ⇒ use the free engine" is right for [VOICE] and [AI]
     * and wrong here: the device has **zero call sites** for these providers (B measured the
     * Kotlin tree). A satellite that suppressed its brain call because `tools` was withheld would
     * convert a *"web search is unavailable"* downgrade into a **voice outage**. The brain simply
     * answers without search.
     *
     * It is in the vocabulary for the operator and for forward compatibility. Correct device
     * handling is to ignore it — which is why [degradesOnAbsence] exists rather than code
     * looping over [KNOWN].
     */
    const val TOOLS = "tools"

    /**
     * The closed set this device understands.
     *
     * 🔴 An **unknown** name is ignorable; a **missing known** one is a downgrade. The device must
     * not fail on a fourth name it has never heard of — that is what lets the add-on add a
     * capability without an APK.
     */
    val KNOWN: Set<String> = setOf(VOICE, AI, TOOLS)

    /**
     * The names whose ABSENCE actually changes device behaviour — i.e. [KNOWN] minus [TOOLS].
     *
     * Separate from [KNOWN] on purpose. The tempting shape is one set and a loop, and it is the
     * shape that produces the voice outage above: every mechanical "for each known capability,
     * degrade if absent" is correct for two of three names and catastrophic for the third. Naming
     * the distinction in the data means a future reader cannot write that loop by accident.
     */
    val DEGRADES_ON_ABSENCE: Set<String> = setOf(VOICE, AI)

    /** Does the absence of [capability] oblige the device to change what it does? */
    fun degradesOnAbsence(capability: String): Boolean = capability in DEGRADES_ON_ABSENCE

    /**
     * Known capabilities missing from [granted], in a stable order, excluding [TOOLS].
     *
     * Used for the partial-grant marker and for the consumer wiring. Returns empty for a full
     * grant and for any grant that only omits `tools`.
     */
    fun degradedBy(granted: Collection<String>): List<String> =
        listOf(VOICE, AI).filter { it !in granted }
}
