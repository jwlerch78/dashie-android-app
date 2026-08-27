package com.dashieapp.Dashie.halite

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Single native owner of HA *content* (iframe) recovery.
 *
 * Background: recovery for the HA dashboard iframe historically lived entirely in
 * WebView JS timers (`kiosk-shell.js` error detection + `ha-offline-overlay.js`
 * 10s fetch poll). Those timers freeze when the screen is off — the exact
 * overnight-kiosk failure window — so a dashboard that 404'd while asleep (HA
 * restart) stayed parked on the 404 until someone manually reloaded. See
 * `.reference/_TECHNICAL_DEBT.md` → "WebView / HA Recovery — Multiple
 * Uncoordinated Controllers" for the full picture and the staged plan; this class
 * is step 1 (the seed).
 *
 * Division of labour (deliberately temporally disjoint to avoid double-driving the
 * iframe reload):
 *  - **Awake:** the JS offline-overlay poll drives recovery (page is visible, its
 *    timers run normally). The coordinator only tracks state.
 *  - **Asleep:** native owns recovery. JS timers are frozen, so a native Handler
 *    backoff loop reloads the iframe until JS reports it healthy.
 *  - **On wake:** if parked on an error, fire ONE immediate reload (the "woke up
 *    to a 404" fix), then hand back to JS.
 *
 * State also feeds `HaConnectionMonitor.pageErrorProvider`, so the existing
 * resume-with-reload-on-wake path covers iframe errors too (previously main-frame
 * only).
 *
 * THREADING: all signal methods ([onIframeError], [onIframeHealthy],
 * [onScreenSleepChanged], [stop]) must be invoked on the coordinator's Handler
 * thread (the main thread in production). The JS bridge marshals onto main; unit
 * tests run on the Robolectric main looper. This keeps the mutable state
 * single-threaded without locks.
 *
 * Testability hooks (also the production safety features): the reload action,
 * enabled-flag, Handler, and backoff timings are all injected so the state machine
 * can be unit-tested with a fake clock and the rig can run with sub-second backoff.
 */
class DashboardHealthCoordinator(
    private val reloadIframe: () -> Unit,
    // Escalation actuators (default to reloadIframe / no-op so existing callers + unit
    // tests keep working). reloadPage = full WebView page reload (rebuilds the iframe
    // fresh — recovers states an in-place iframe reload can't, e.g. a wedged/error-page
    // iframe). recreateWebView = heavyweight last resort.
    private val reloadPage: () -> Unit = reloadIframe,
    private val recreateWebView: () -> Unit = reloadPage,
    private val isEnabled: () -> Boolean = { true },
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val firstBackoffMs: Long = DEFAULT_FIRST_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
) {
    companion object {
        private const val TAG = "HealthCoordinator"
        const val DEFAULT_FIRST_BACKOFF_MS = 15_000L
        const val DEFAULT_MAX_BACKOFF_MS = 60_000L

        // Escalation ladder: in-place iframe reload is cheap and handles transient
        // blips, but a real outage can leave the iframe wedged / parked on an error
        // page that in-place reload never recovers (the Fire-tablet field bug: 814
        // in-place reloads, 0 effective; only a full reload via process restart
        // recovered). So escalate by attempt count: iframe → full page reload →
        // recreate WebView. Recreate spawns a fresh coordinator (stop() is called on
        // recreation), so it's naturally terminal for this instance.
        const val ESCALATE_TO_PAGE = 3       // after 3 in-place reloads, reload the page
        const val ESCALATE_TO_RECREATE = 6   // after 3 more (page reloads), recreate

        // Post-load liveness verify window. After the shell reports ready, the HA
        // iframe must fire its load event within this window. If it never does — the
        // silent overnight failure (Teclast WebView-78: an asleep recreate leaves the
        // iframe pointed at nothing, no error page, no onload, so JS detection never
        // fires and health stays HEALTHY forever) — we synthesize a CONTENT_ERROR so
        // the (asleep loop / wake) recovery engages. Level-triggered: asserts the
        // positive "iframe loaded" event instead of waiting for a negative error signal.
        const val LIVENESS_VERIFY_MS = 90_000L
    }

    enum class Health { HEALTHY, CONTENT_ERROR }

    @Volatile private var health: Health = Health.HEALTHY
    private var screenSleeping = false
    private var loopRunnable: Runnable? = null
    private var verifyRunnable: Runnable? = null
    private var currentBackoffMs = firstBackoffMs
    private var reloadsThisOutage = 0

    /** Feeds `HaConnectionMonitor.pageErrorProvider`. */
    fun isContentErrored(): Boolean = health == Health.CONTENT_ERROR

    /** Current health — exposed for diagnostics / the test rig oracle. */
    fun currentHealth(): Health = health

    /**
     * Signal: JS detected the HA iframe is on an error page (404/5xx/blank/
     * unreachable). Re-fired by JS after each reload that still lands on an error,
     * which is what sustains the asleep backoff loop.
     */
    fun onIframeError(url: String) {
        if (!isEnabled()) return
        cancelVerify()  // an error is a definitive signal; the liveness verify is moot
        val wasHealthy = health == Health.HEALTHY
        health = Health.CONTENT_ERROR
        if (wasHealthy) {
            // Start of a new outage — reset backoff/escalation bookkeeping.
            reloadsThisOutage = 0
            currentBackoffMs = firstBackoffMs
            PersistentLog.warn(TAG, "HEALTH: state=CONTENT_ERROR url=$url sleeping=$screenSleeping")
        }
        // Only own recovery while asleep; awake, the JS poll drives it.
        if (screenSleeping) ensureLoopScheduled()
    }

    /** Signal: JS confirmed the HA iframe loaded a healthy dashboard. */
    fun onIframeHealthy() {
        cancelVerify()
        if (health == Health.CONTENT_ERROR) {
            PersistentLog.info(TAG, "HEALTH: recovered after $reloadsThisOutage reload(s)")
        }
        health = Health.HEALTHY
        stopLoop()
    }

    /**
     * Signal: the kiosk shell finished loading (post page-load / post-recreate). Arms
     * the liveness verify — the HA iframe must fire [onIframeLoaded] within
     * [LIVENESS_VERIFY_MS] or we treat it as a silent content error. This is the
     * detection half the JS error-sniffing can't cover: an iframe that never navigates
     * produces no error page and no onload, so nothing else would ever notice.
     */
    fun onShellReady() {
        if (!isEnabled()) return
        armVerify()
    }

    /**
     * Signal: the HA iframe fired its load event (native-observed, from the WS-monitor
     * forward point). Proves the iframe *navigated* — rules out the "never loaded"
     * silent failure — so it cancels the liveness verify. It does NOT assert healthy:
     * an error page also fires onload, so healthy-vs-error stays with the JS content
     * signals ([onIframeHealthy] / [onIframeError]).
     */
    fun onIframeLoaded() {
        cancelVerify()
    }

    /**
     * Re-assert the current screen-sleep state without side effects. Called by the
     * recreation wiring: a fresh coordinator defaults to screenSleeping=false, so an
     * asleep recreate would leave it believing it's awake — the asleep recovery loop
     * ([onIframeError]'s `if (screenSleeping)` gate) would then never start, and
     * recovery would wait until the next real wake. Re-syncing from the authoritative
     * source (HaConnectionMonitor) on rewire fixes that. If already errored + asleep,
     * start the loop now.
     */
    fun syncScreenSleeping(sleeping: Boolean) {
        screenSleeping = sleeping
        if (sleeping && health == Health.CONTENT_ERROR) ensureLoopScheduled()
    }

    /**
     * Signal: screen sleep state changed. Fed from `HaConnectionMonitor`'s existing
     * screen-sleep plumbing so we reuse one reliable source of truth.
     */
    fun onScreenSleepChanged(sleeping: Boolean) {
        screenSleeping = sleeping
        if (sleeping) {
            if (health == Health.CONTENT_ERROR) ensureLoopScheduled()
        } else {
            // Waking: native loop stands down (JS takes over). If parked on an error,
            // fire ONE immediate reload — the "woke up to a 404" fix. The reload
            // re-runs JS detection, which re-asserts the true state (error → re-report,
            // healthy → onIframeHealthy clears it), so this can't get stuck.
            stopLoop()
            if (health == Health.CONTENT_ERROR && isEnabled()) {
                issueReload("wake")
            }
        }
    }

    /**
     * Cancel all pending work and reset. MUST be called when the WebView is
     * recreated — otherwise the old coordinator's backoff loop keeps running
     * against a dead WebView (the orphaned-loop class of bug that bit the
     * screen-sleep keepalive).
     */
    fun stop() {
        stopLoop()
        cancelVerify()
        health = Health.HEALTHY
    }

    private fun armVerify() {
        cancelVerify()
        val r = Runnable {
            verifyRunnable = null
            // Fire only if still unproven: the iframe never signalled loaded and we're
            // not already handling an error. Re-check enabled at fire time.
            if (!isEnabled() || health == Health.CONTENT_ERROR) return@Runnable
            PersistentLog.warn(TAG, "HEALTH: liveness verify FAILED — iframe never loaded ${LIVENESS_VERIFY_MS / 1000}s after shell ready")
            onIframeError("liveness:post-load")
        }
        verifyRunnable = r
        handler.postDelayed(r, LIVENESS_VERIFY_MS)
    }

    private fun cancelVerify() {
        verifyRunnable?.let { handler.removeCallbacks(it) }
        verifyRunnable = null
    }

    private fun ensureLoopScheduled() {
        if (loopRunnable != null) return // already looping
        scheduleNext()
    }

    private fun scheduleNext() {
        val delay = currentBackoffMs
        val r = Runnable {
            loopRunnable = null
            // Re-check every gate at fire time — state may have changed during the wait.
            if (health != Health.CONTENT_ERROR || !screenSleeping || !isEnabled()) return@Runnable
            issueReload("loop")
            // Grow backoff toward the cap; JS re-detection keeps the loop alive.
            currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(maxBackoffMs)
            scheduleNext()
        }
        loopRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun stopLoop() {
        loopRunnable?.let { handler.removeCallbacks(it) }
        loopRunnable = null
        currentBackoffMs = firstBackoffMs
    }

    private fun issueReload(source: String) {
        reloadsThisOutage++
        // Escalate by attempt count: in-place iframe reload → full page reload → recreate.
        val action: () -> Unit
        val name: String
        when {
            reloadsThisOutage >= ESCALATE_TO_RECREATE -> { action = recreateWebView; name = "recreate-webview" }
            reloadsThisOutage >= ESCALATE_TO_PAGE -> { action = reloadPage; name = "reload-page" }
            else -> { action = reloadIframe; name = "reload-iframe" }
        }
        PersistentLog.info(TAG, "HEALTH: action=$name source=$source attempt=$reloadsThisOutage")
        Log.i(TAG, "🔄 $name (source=$source attempt=$reloadsThisOutage)")
        try {
            action()
        } catch (e: Exception) {
            Log.e(TAG, "$name action threw: ${e.message}")
        }
    }
}
