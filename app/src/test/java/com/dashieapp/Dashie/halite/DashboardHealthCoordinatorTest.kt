package com.dashieapp.Dashie.halite

import android.os.Handler
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tier-1 state-machine tests for [DashboardHealthCoordinator].
 *
 * These cover the regression-risk core called out in the design review: the flag
 * must CLEAR (no permanent pageErrorProvider=true), a persistent error must BACK
 * OFF rather than loop, the wake path must reload exactly once, and stop() must
 * leave no orphaned loop. Time is advanced virtually via the Robolectric main
 * looper, so the whole suite runs in milliseconds with no real waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DashboardHealthCoordinatorTest {

    private val reloads = AtomicInteger(0)
    private var enabled = true

    // Small backoff so virtual-time advances are easy to reason about: 1s → 2s → 4s (cap).
    private fun newCoordinator() = DashboardHealthCoordinator(
        reloadIframe = { reloads.incrementAndGet() },
        isEnabled = { enabled },
        handler = Handler(Looper.getMainLooper()),
        firstBackoffMs = 1_000L,
        maxBackoffMs = 4_000L,
    )

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test
    fun `error then healthy clears state and schedules no further reloads`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/404")
        assertTrue("content should be errored", c.isContentErrored())

        c.onIframeHealthy()
        assertFalse("healthy signal must clear the error flag", c.isContentErrored())

        // The loop must be cancelled — no reloads should fire after recovery.
        advance(10_000)
        assertEquals("no reloads after healthy", 0, reloads.get())
    }

    @Test
    fun `persistent error while asleep backs off, it does not tight-loop`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/404")

        // Reloads land at t = 1s, 3s, 7s, 11s, 15s … (backoff 1s→2s→4s→cap 4s).
        advance(1_000); assertEquals(1, reloads.get())                                   // t=1s  #1
        advance(1_000); assertEquals("must not fire again at fixed 1s", 1, reloads.get()) // t=2s
        advance(1_000); assertEquals(2, reloads.get())                                   // t=3s  #2
        advance(4_000); assertEquals(3, reloads.get())                                   // t=7s  #3

        // Over a long window the 4s cap holds it to a sane rate — nowhere near a storm.
        advance(40_000) // → t=47s; reloads at 11,15,…,47 add 10 more
        assertTrue("capped backoff should keep reloads modest", reloads.get() in 12..16)
    }

    @Test
    fun `wake while parked on error fires exactly one immediate reload then stops`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/404")
        reloads.set(0) // ignore any asleep-loop reloads; isolate the wake behaviour

        c.onScreenSleepChanged(false) // wake
        assertEquals("wake should reload immediately, once", 1, reloads.get())

        // Awake, the native loop must NOT run (JS owns recovery while visible).
        advance(20_000)
        assertEquals("no native loop while awake", 1, reloads.get())
    }

    @Test
    fun `no native reloads while awake even when errored`() {
        val c = newCoordinator()
        // Never asleep — JS poll owns this case.
        c.onIframeError("http://ha/404")
        advance(20_000)
        assertEquals("awake errors are JS-driven, not native", 0, reloads.get())
        assertTrue("state still tracked for pageErrorProvider", c.isContentErrored())
    }

    @Test
    fun `stop cancels the loop with no orphaned reloads`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/404")
        advance(1_000); assertEquals(1, reloads.get())

        c.stop() // simulates WebView recreation
        assertFalse(c.isContentErrored())
        advance(30_000)
        assertEquals("no reloads after stop()", 1, reloads.get())
    }

    @Test
    fun `disabled coordinator takes no action`() {
        enabled = false
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/404")
        assertFalse("disabled: no error state", c.isContentErrored())
        advance(10_000)
        assertEquals("disabled: no reloads", 0, reloads.get())
    }

    @Test
    fun `recovery resets backoff for the next outage`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)

        c.onIframeError("http://ha/404")
        advance(1_000); assertEquals(1, reloads.get()) // first outage: 1s backoff
        c.onIframeHealthy()
        reloads.set(0)

        // New outage must start from the first (short) backoff again, not the grown one.
        c.onIframeError("http://ha/404")
        advance(1_000)
        assertEquals("second outage should reload at the reset 1s backoff", 1, reloads.get())
    }

    @Test
    fun `sustained outage escalates iframe to page reload to recreate`() {
        val ifr = AtomicInteger(0); val page = AtomicInteger(0); val recreate = AtomicInteger(0)
        val c = DashboardHealthCoordinator(
            reloadIframe = { ifr.incrementAndGet() },
            reloadPage = { page.incrementAndGet() },
            recreateWebView = { recreate.incrementAndGet() },
            isEnabled = { true },
            handler = Handler(Looper.getMainLooper()),
            firstBackoffMs = 1_000L, maxBackoffMs = 4_000L,
        )
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/down")
        advance(60_000) // many attempts; ESCALATE_TO_PAGE=3, ESCALATE_TO_RECREATE=6

        // attempts 1–2 = in-place iframe, 3–5 = full page reload, 6+ = recreate.
        assertEquals("first 2 attempts use the cheap in-place reload", 2, ifr.get())
        assertEquals("attempts 3–5 escalate to full page reload", 3, page.get())
        assertTrue("attempts 6+ escalate to recreate", recreate.get() >= 1)
    }

    // ── Phase 1a: post-load liveness verify ─────────────────────────────────
    // The silent overnight failure (WebView-78): after an asleep recreate the HA
    // iframe never navigates — no error page, no onload — so the JS content-detection
    // never fires. The native verify is the ONLY thing that can catch it.

    @Test
    fun `shell ready with no iframe load synthesizes a content error`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onShellReady()
        assertFalse("no error before the verify window elapses", c.isContentErrored())

        // Iframe never fires onIframeLoaded → at LIVENESS_VERIFY_MS the verify trips.
        advance(DashboardHealthCoordinator.LIVENESS_VERIFY_MS)
        assertTrue("silent never-loaded iframe must become CONTENT_ERROR", c.isContentErrored())
    }

    @Test
    fun `iframe loaded within the window cancels the verify`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onShellReady()
        advance(DashboardHealthCoordinator.LIVENESS_VERIFY_MS / 2)
        c.onIframeLoaded() // proves the iframe navigated

        advance(DashboardHealthCoordinator.LIVENESS_VERIFY_MS)
        assertFalse("a loaded iframe must not trip the verify", c.isContentErrored())
    }

    @Test
    fun `iframe loaded does not by itself assert healthy over an existing error`() {
        // onIframeLoaded proves navigation, NOT health — an error page also fires onload.
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/404")
        c.onIframeLoaded() // the error-page's onload
        assertTrue("iframe-loaded must not clear a real content error", c.isContentErrored())
    }

    @Test
    fun `verify failure while asleep drives recovery`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onShellReady()
        advance(DashboardHealthCoordinator.LIVENESS_VERIFY_MS) // verify trips → CONTENT_ERROR
        reloads.set(0)
        advance(2_000) // asleep backoff loop should now be reloading
        assertTrue("verify-detected outage must engage the asleep recovery loop", reloads.get() >= 1)
    }

    @Test
    fun `syncScreenSleeping starts the loop for an already-errored recreated coordinator`() {
        // Simulates the recreation re-sync: a fresh coordinator that is already errored
        // (verify tripped) but defaulted to screenSleeping=false must start recovering
        // once told the true asleep state.
        val c = newCoordinator()
        c.onShellReady()
        advance(DashboardHealthCoordinator.LIVENESS_VERIFY_MS) // errors while "awake" (default)
        assertTrue(c.isContentErrored())
        reloads.set(0)
        advance(10_000); assertEquals("awake: no native loop yet", 0, reloads.get())

        c.syncScreenSleeping(true) // recreation re-sync discovers the screen is off
        advance(2_000)
        assertTrue("re-sync must start the asleep recovery loop", reloads.get() >= 1)
    }

    @Test
    fun `stop cancels a pending verify`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onShellReady()
        c.stop() // recreation before the iframe loaded
        advance(DashboardHealthCoordinator.LIVENESS_VERIFY_MS + 5_000)
        assertFalse("a stopped coordinator must not trip its verify", c.isContentErrored())
    }

    @Test
    fun `redundant error signals do not stack multiple loops`() {
        val c = newCoordinator()
        c.onScreenSleepChanged(true)
        c.onIframeError("http://ha/404")
        c.onIframeError("http://ha/404") // re-detect (e.g. JS re-report) must not double-schedule
        c.onIframeError("http://ha/404")

        advance(1_000)
        assertEquals("only one loop running despite repeated error signals", 1, reloads.get())
    }
}
