package com.dashieapp.Dashie.halite.voice

/**
 * Static sink that applies an add-on-reported brain route (from the `X-Dashie-Brain-Route`
 * converse-response header) to the persistent cache.
 *
 * Why a static singleton (same rationale as [BrainRouteHealth]): the writer,
 * [BrainConverseClient], is a stateless client with no Context/prefs handle and is constructed
 * in the 1945-line-budgeted [VoicePipelineCoordinator]. Routing the header through the caller
 * would force a prefs/callback edit into that maxed, concurrently-edited file. Instead the apply
 * lambda is installed ONCE from a Context-holding site ([MainActivity], via
 * `VoicePreferences.applyBrainRoute`), and the client just calls [apply].
 *
 * Installed lazily at startup; [apply] is a safe no-op until the lambda is set (that turn's
 * header is simply ignored — the next probe/turn corrects it). Invoked from an OkHttp background
 * thread, so the lambdas are @Volatile.
 *
 * ## 🔴 Why an ABSENT header is now reported instead of silently ignored
 *
 * `if (route.isNullOrBlank()) return` was a **silent** fallthrough, and it hid a real strand:
 * when the header never arrives, the keystone self-correction cannot fire, so a device cached on
 * `brain_route=local` with no reachable local engine dead-ends **every turn** with no user-facing
 * error and nothing to grep. That is T's V3 red row, and it is standing rule 2's exact shape.
 *
 * Absence is NOT always a fault — a healthy direct-to-edge-function turn legitimately carries no
 * header, because only the gateway stamps it. So the report is delegated to [onAbsent], installed
 * at the Context-holding site, which can see the cached route and stay quiet unless absence
 * actually strands this device. The decision lives where the prefs are; this object stays
 * prefs-free.
 */
object BrainRouteApplier {
    /** Set once at startup: `{ route -> halitePrefs.voice.applyBrainRoute(route) }`. */
    @Volatile
    var applyFn: ((String) -> Unit)? = null

    /**
     * Set once at startup alongside [applyFn]. Called when a converse response carried NO
     * authoritative route. The installed implementation decides whether that is benign (a direct
     * cloud turn) or a strand (cached `local`, nothing to correct it) and logs accordingly —
     * see `MainActivity`.
     */
    @Volatile
    var onAbsent: (() -> Unit)? = null

    /** Apply a route reported by the gateway header, or report its absence.
     *
     *  Still a no-op when no sink is installed (startup race) — that case is genuinely
     *  uninteresting, and reporting it would fire before the app can act on it anyway. */
    fun apply(route: String?) {
        if (applyFn == null) return
        if (route.isNullOrBlank()) {
            onAbsent?.invoke()
            return
        }
        applyFn?.invoke(route)
    }

    /** Test hook. */
    fun resetForTest() { applyFn = null; onAbsent = null }
}
