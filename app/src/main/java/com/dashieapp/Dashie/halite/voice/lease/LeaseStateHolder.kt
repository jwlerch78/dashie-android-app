package com.dashieapp.Dashie.halite.voice.lease

/**
 * The last observed capability-lease state, readable by UI.
 *
 * ## Why a holder exists at all
 *
 * [CapabilityLease] is an *instance* owned by `HaliteVoiceController`, and until now the lease was
 * **purely in-memory with no persistence and no readable snapshot anywhere** — so nothing outside
 * the voice stack could answer *"is this tablet using the household's keys right now?"*. That is
 * the question the Control Center's Account card needs (2026-08-03: *"shared by your HA
 * account"*).
 *
 * Deliberately mirrors [com.dashieapp.Dashie.halite.voice.CreditStateHolder] — the pattern the
 * Control Center **already reads** — so there is no new consumer wiring and the next reader
 * recognises the shape.
 *
 * ## 🔴 One volatile reference to an immutable snapshot — NOT two volatile fields
 *
 * `granted` and `withheld` as separate `@Volatile`s would not update atomically, so a reader could
 * observe the flag from **before** a transition and the list from **after** it: a torn read that
 * renders a state the device was never in. One immutable [Snapshot] behind one `@Volatile`
 * reference makes the update atomic by construction. The bug it prevents would be rare AND
 * invisible, which is the worst combination to ship.
 *
 * ## 🔴 FOUR states, not three — [NOT_APPLICABLE] is the one that is easy to miss
 *
 * The lease loop starts **only** in AI-routing mode; on an HA-Assist device it never runs and the
 * code emits [LeaseMarkers.NOT_STARTED] for exactly that reason. If that device reported an empty
 * snapshot it would render as **"Not shared"** — and that is *false*: nothing was refused, nothing
 * was even asked. It is the same wrong-message class as telling a user a speaker "may be offline"
 * when the server had in fact answered, and worse here because it reads plausibly.
 *
 * So "no lease" and "lease refused" are different states, and **[NOT_APPLICABLE] renders no row at
 * all** rather than a sentence.
 */
object LeaseStateHolder {

    /**
     * What the device can honestly say about household voice/AI right now — John's five states
     * (2026-08-03), see `JS_KOTLIN_CONTRACTS #72` for the sentences.
     *
     * 🔴 [USING_KEYS] vs [FREE_ENGINES_ONLY], and [NO_KEYS_CONFIGURED] vs [SHARING_OFF], are the
     * two distinctions that carry the whole design. Each pair looks identical in `capabilities`
     * and means the opposite thing to a user: the second pair especially implies **opposite
     * actions** — *add a key* versus *turn sharing on*. Collapsing them sends someone with no API
     * key hunting for a toggle that was never the problem.
     */
    enum class Share {
        /** Granted **with** the metered brain — the household is lending its AI keys. */
        USING_KEYS,

        /**
         * Granted, but `ai` is absent: the device runs on the box's free engines (HA
         * Whisper/Piper, keyless tools). **A working state, not a degradation** — and the reason
         * it must not read as an error is that it is the normal shape of a box with no LLM key.
         */
        FREE_ENGINES_ONLY,

        /**
         * `ai` withheld because **nothing is configured** (`capability_unavailable`).
         *
         * Per D's ruling this is the **normal steady state on a Chickadee box**, not an edge —
         * and it is the state the Fire was observed sitting in (2026-08-03 device pass).
         */
        NO_KEYS_CONFIGURED,

        /** `ai` withheld because the household **turned sharing off** (`sharing_disabled`). */
        SHARING_OFF,

        /**
         * Asked and told no outright, or expired and self-destructed.
         *
         * Renders as [SHARING_OFF]'s sentence by the ruling — from the user's end it *is*
         * that state, seen from the other side.
         */
        REFUSED,

        /**
         * The lease does not govern this device's configuration — HA-Assist mode, where the box
         * spends on its own behalf and lends the satellite nothing.
         *
         * 🔴 **Not a synonym for [REFUSED], and the UI must not render a sentence for it.**
         * Nothing was withheld; nothing was requested.
         */
        NOT_APPLICABLE,
    }

    /**
     * Immutable so the whole state changes in one reference write.
     *
     * @param withheld capability names absent from an otherwise-held lease. Meaningful only for
     *   [Share.PARTIAL]; empty everywhere else.
     */
    data class Snapshot(val share: Share, val withheld: List<String> = emptyList())

    /**
     * `null` until the lease reports anything — genuinely *unknown*, distinct from every state
     * above, and it also renders nothing.
     *
     * 🔴 No `DROP:` marker on a null read, on purpose. Before the first renewal there legitimately
     * is no state; that is ordinary startup, not a dispatch fallthrough, and a marker there would
     * fire on every boot and train readers to skim the channel that carries the real ones.
     */
    @Volatile
    var snapshot: Snapshot? = null
        private set

    /**
     * Fold a grant into a reportable state.
     *
     * @param missing capability names absent from the grant (from [LeaseCapabilities.degradedBy]).
     * @param reasons the wire's `withheld` map — capability → why. **May be empty**, and that is
     *   the compat case that matters.
     *
     * 🔴 **An absent reason falls back to [Share.FREE_ENGINES_ONLY], never to
     * [Share.NO_KEYS_CONFIGURED].** Add-ons older than #70 do not send `withheld` at all, so
     * inferring "no keys set up" from silence would tell a perfectly healthy household to go
     * configure something that is already configured. Absent means *we do not know why*, and the
     * honest fallback is the reason-free sentence — which is also true, because the device really
     * is on free engines.
     */
    fun onGranted(missing: List<String>, reasons: Map<String, String> = emptyMap()) {
        val share = when {
            !missing.contains(LeaseCapabilities.AI) -> Share.USING_KEYS
            reasons[LeaseCapabilities.AI] == "capability_unavailable" -> Share.NO_KEYS_CONFIGURED
            reasons[LeaseCapabilities.AI] == "sharing_disabled" -> Share.SHARING_OFF
            else -> Share.FREE_ENGINES_ONLY
        }
        publish(
            Snapshot(share = share, withheld = missing),
            detail = if (missing.isEmpty()) "" else
                "missing=${missing.joinToString(",")} reason=${reasons[LeaseCapabilities.AI] ?: "<none sent>"}",
        )
    }

    /** Refused by the add-on, or expired and self-destructed. */
    fun onRefused() {
        publish(Snapshot(Share.REFUSED))
    }

    /**
     * The lease loop is deliberately not running for this device's configuration.
     *
     * Pairs with [LeaseMarkers.NOT_STARTED] — the marker says it in the log, this says it to the
     * UI, and both exist because a deliberate absence that announces itself is the only kind that
     * can be told apart from a broken one.
     */
    fun onNotApplicable() {
        publish(Snapshot(Share.NOT_APPLICABLE))
    }

    /**
     * Store the snapshot and announce it — **on change only**.
     *
     * The write and the marker are the same statement, so the log and the screen cannot disagree
     * about what the device believes. That property is why this lives here rather than at the
     * call sites: a second call site that forgot to mark would reintroduce exactly the silent
     * fold this was added to end.
     *
     * ⚠️ On CHANGE, not on every renewal. Renewal runs every ~5 minutes and the state is the same
     * almost every time; marking unconditionally would emit ~288 identical lines a day and train
     * readers to filter out the channel carrying the real transitions — the same reason
     * [LeaseStateHolder]'s null read deliberately has no `DROP:`.
     */
    private fun publish(next: Snapshot, detail: String = "") {
        val changed = snapshot?.share != next.share
        snapshot = next
        if (changed) LeaseMarkers.markState(next.share.name, detail)
    }

    /** Test hook. */
    fun resetForTest() { snapshot = null }
}
