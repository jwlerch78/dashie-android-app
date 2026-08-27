package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two dashboard-pill dismissal state machines (FB27 low-credit + WS-L.3 auto-refill
 * failure). Both are "latch a dismissal, re-arm when the CONDITION clears" — the shape
 * CalendarReauthOverlayManager uses — and both got that wrong or absent before 2026-07-20.
 *
 * The low-credit re-arm is the regression this file mainly exists for: it used to hang off
 * a `spendable` false→true edge, so a dismissal at $0.30 survived a top-up to $5 and
 * silenced the pill forever (the edge only fires on a refill from ~$0). [lowPillReArmsWhenBalanceRecovers]
 * is that exact scenario.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])   // CreditStateHolder logs via android.util.Log — unmocked on plain JVM
class CreditPillDismissalTest {

    @Before
    fun reset() {
        // Process-global singleton: neutralize it between tests (fail-open defaults).
        CreditStateHolder.update(spendable = true, low = false, balance = 10.0)
        CreditStateHolder.updateAutorefill(failed = false, error = null, errorAt = null)
    }

    // ── low-credit pill ──────────────────────────────────────────────────

    @Test
    fun lowPillReArmsWhenBalanceRecovers() {
        CreditStateHolder.update(spendable = true, low = true, balance = 0.30)
        CreditStateHolder.dismissPill()
        assertTrue("dismissal should latch", CreditStateHolder.pillDismissed)

        // Top-up to $5 — note spendable was TRUE the whole time, so the old
        // false→true edge never fired and the dismissal used to stick forever.
        CreditStateHolder.update(spendable = true, low = false, balance = 5.0)
        assertFalse("condition cleared → dismissal forgotten", CreditStateHolder.pillDismissed)

        // Drains again → the pill is free to reappear.
        CreditStateHolder.update(spendable = true, low = true, balance = 0.40)
        assertFalse(CreditStateHolder.pillDismissed)
    }

    @Test
    fun lowPillStaysDismissedWhileStillBelowThreshold() {
        CreditStateHolder.update(spendable = true, low = true, balance = 0.30)
        CreditStateHolder.dismissPill()
        // A refresh that still reads below the pill threshold must NOT resurrect it.
        CreditStateHolder.update(spendable = true, low = true, balance = 0.20)
        assertTrue(CreditStateHolder.pillDismissed)
    }

    @Test
    fun lowPillReArmsExactlyAtThreshold() {
        CreditStateHolder.update(spendable = true, low = true, balance = 0.10)
        CreditStateHolder.dismissPill()
        CreditStateHolder.update(spendable = true, low = true, balance = CreditStateHolder.PILL_USD)
        assertFalse("at the threshold counts as recovered", CreditStateHolder.pillDismissed)
    }

    // ── auto-refill failure pill (dismissal keyed on the streak timestamp) ──

    @Test
    fun failurePillShowsWhileStreakLiveAndHidesOnDismiss() {
        CreditStateHolder.updateAutorefill(true, "Your card was declined.", "2026-07-20T10:00:00Z")
        assertTrue(CreditStateHolder.showAutorefillFailure)

        CreditStateHolder.dismissFailurePill()
        assertFalse("dismissal silences THIS streak", CreditStateHolder.showAutorefillFailure)

        // Same streak re-reported by the next poll → stays hidden (no nag).
        CreditStateHolder.updateAutorefill(true, "Your card was declined.", "2026-07-20T10:00:00Z")
        assertFalse(CreditStateHolder.showAutorefillFailure)
    }

    @Test
    fun aNewFailureReShowsAfterDismissal() {
        CreditStateHolder.updateAutorefill(true, "declined", "2026-07-20T10:00:00Z")
        CreditStateHolder.dismissFailurePill()
        assertFalse(CreditStateHolder.showAutorefillFailure)

        // A LATER decline is a new streak (new timestamp) — the user must see it.
        CreditStateHolder.updateAutorefill(true, "declined again", "2026-07-21T09:00:00Z")
        assertTrue(CreditStateHolder.showAutorefillFailure)
    }

    @Test
    fun successfulChargeClearsPillAndForgetsDismissal() {
        CreditStateHolder.updateAutorefill(true, "declined", "2026-07-20T10:00:00Z")
        CreditStateHolder.dismissFailurePill()

        // Server cleared last_error (a charge succeeded).
        CreditStateHolder.updateAutorefill(false, null, null)
        assertFalse(CreditStateHolder.showAutorefillFailure)

        // A future failure shows without needing a process restart.
        CreditStateHolder.updateAutorefill(true, "declined", "2026-07-25T10:00:00Z")
        assertTrue(CreditStateHolder.showAutorefillFailure)
    }

    // ── auto-refill enable/disable (WS-L.3 P2 native kill switch) ──────────

    @Test
    fun turningAutorefillOffClearsTheFailureState() {
        CreditStateHolder.updateAutorefill(true, "declined", "2026-07-20T10:00:00Z", enabled = true)
        assertTrue(CreditStateHolder.showAutorefillFailure)

        // Kill switch: there is no auto-refill left to have failed, so the pill must not linger.
        CreditStateHolder.setAutorefillEnabledLocal(false)
        assertFalse(CreditStateHolder.autorefillEnabled)
        assertFalse("a disabled auto-refill can't be in a failed state", CreditStateHolder.showAutorefillFailure)
    }

    @Test
    fun reEnablingAfterAFailureDoesNotResurrectTheOldFailure() {
        CreditStateHolder.updateAutorefill(true, "declined", "2026-07-20T10:00:00Z", enabled = true)
        CreditStateHolder.setAutorefillEnabledLocal(false)
        CreditStateHolder.setAutorefillEnabledLocal(true)
        assertFalse("the old failure was cleared with the disable", CreditStateHolder.showAutorefillFailure)
    }

    @Test
    fun enabledStateSurvivesABalanceOnlyRefresh() {
        CreditStateHolder.updateAutorefill(false, null, null, enabled = true, threshold = 1.0, topup = 10.0)
        // A refresh that omits the autorefill args must not silently flip it off (the defaults
        // carry the current values forward).
        CreditStateHolder.updateAutorefill(failed = false, error = null, errorAt = null)
        assertTrue(CreditStateHolder.autorefillEnabled)
        assertEquals(10.0, CreditStateHolder.autorefillTopup!!, 0.001)
    }

    // ── listener fan-out (was a single slot; a 2nd observer silently replaced the 1st) ──

    @Test
    fun everyRegisteredListenerIsNotified() {
        var a = 0
        var b = 0
        CreditStateHolder.addOnChanged("test-a") { a++ }
        CreditStateHolder.addOnChanged("test-b") { b++ }
        try {
            CreditStateHolder.update(spendable = true, low = true, balance = 0.25)
            assertTrue("first listener still fires after a second registered", a > 0)
            assertTrue("second listener fires", b > 0)

            // Re-registering the same key replaces, never stacks (WebView recreation).
            val aBefore = a
            CreditStateHolder.addOnChanged("test-a") { a++ }
            CreditStateHolder.update(spendable = true, low = true, balance = 0.24)
            assertEquals("re-register de-dupes by key", aBefore + 1, a)
        } finally {
            CreditStateHolder.removeOnChanged("test-a")
            CreditStateHolder.removeOnChanged("test-b")
        }
    }

    @Test
    fun aThrowingListenerDoesNotBlockTheOthers() {
        var reached = false
        CreditStateHolder.addOnChanged("test-throws") { throw IllegalStateException("boom") }
        CreditStateHolder.addOnChanged("test-after") { reached = true }
        try {
            CreditStateHolder.update(spendable = true, low = true, balance = 0.25)
            assertTrue("a throwing observer must not starve the pill refresh", reached)
        } finally {
            CreditStateHolder.removeOnChanged("test-throws")
            CreditStateHolder.removeOnChanged("test-after")
        }
    }
}
