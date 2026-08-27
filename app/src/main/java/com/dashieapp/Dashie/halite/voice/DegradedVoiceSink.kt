package com.dashieapp.Dashie.halite.voice

/**
 * Static sink for entering/leaving degraded (free-engine) voice from outside
 * [VoicePipelineCoordinator].
 *
 * ## Why a sink rather than a reference
 *
 * `DegradedVoiceMode` is constructed **inside** the coordinator and never exposed. The capability
 * lease has to drive it — a lapsed lease means "fall back to HA/local engines" — but the
 * coordinator is at its size budget (2,273 lines and growing) and threading a callback through it
 * would mean editing the one file this repo keeps trying not to edit.
 *
 * This is the same shape, for the same reason, as
 * [com.dashieapp.Dashie.halite.voice.BrainRouteApplier], whose own note says routing through the
 * caller *"would force a prefs/callback edit into that maxed, concurrently-edited file."* Two
 * precedents make it the house pattern rather than a one-off dodge.
 *
 * The coordinator installs the lambdas next to where it builds `DegradedVoiceMode` — **one line**,
 * no behaviour change — and the lease calls [enter] / [clear] without knowing the coordinator
 * exists.
 *
 * ## Both calls are safe before installation
 *
 * The lease can lapse before voice has initialised (an APK update, a slow boot). A no-op is the
 * right answer then: with no pipeline running there is nothing to degrade, and the next
 * `initializeVoicePipeline` resolves the free-engine plan from scratch anyway. Callers must not
 * treat `false` as "the fallback failed".
 */
object DegradedVoiceSink {

    /**
     * Installed by [VoicePipelineCoordinator]. Returns false when no free engine exists on this
     * device — which is a real answer, not an error: the honest outcome is that voice cannot
     * continue, and the caller should say so rather than pretend a fallback happened.
     */
    @Volatile
    var enterFn: ((reason: String, expiresInMs: Long) -> Boolean)? = null

    /** Installed alongside [enterFn]. `full = true` clears the plan outright. */
    @Volatile
    var clearFn: ((full: Boolean) -> Unit)? = null

    /**
     * Enter degraded mode.
     *
     * @return true if a free-engine plan is now active; false if none exists **or** if no
     *   pipeline is running yet (the two are deliberately indistinguishable here — neither means
     *   the caller should retry, and the lease logs its own marker regardless).
     */
    fun enter(reason: String, expiresInMs: Long = 0L): Boolean =
        enterFn?.invoke(reason, expiresInMs) ?: false

    /** Leave degraded mode. No-op before installation. */
    fun clear(full: Boolean = true) {
        clearFn?.invoke(full)
    }

    /** Test hook. */
    fun resetForTest() { enterFn = null; clearFn = null }
}
