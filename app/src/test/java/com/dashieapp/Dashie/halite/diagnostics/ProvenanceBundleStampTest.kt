package com.dashieapp.Dashie.halite.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the scalar `kioskBundleBuild` selection.
 *
 * The defect this guards (T s43 cont.26, Fire): provenance reported a 20-day-old stamp because it
 * read ONE hard-coded bundle. Since build.js went content-stable each bundle carries its own
 * last-changed stamp, so the scalar must be the NEWEST across them — anything else can report a
 * stale bundle as if it were the device's build, which sends a field diagnosis down a false path.
 *
 * No Android in these — [ProvenanceReporter.newestBundleStamp] is pure by design so the choice is
 * testable without a device (none was claimed for this fix).
 */
class ProvenanceBundleStampTest {

    /** The exact Fire case: services untouched since Aug-2, shell rebuilt Aug-22. */
    @Test
    fun `picks the newest stamp, not the first bundle`() {
        val out = ProvenanceReporter.newestBundleStamp(
            mapOf(
                "kiosk-services.bundle.js" to "893eead46 2026-08-02T17:32:56",
                "kiosk-shell.bundle.js" to "4ffaf9cda 2026-08-22T16:13:07",
            )
        )
        assertTrue("must report the Aug-22 shell stamp, got: $out", out.contains("2026-08-22T16:13:07"))
        assertTrue("must name which bundle it came from, got: $out", out.contains("kiosk-shell.bundle.js"))
        assertTrue("must not report the stale services stamp, got: $out", !out.contains("893eead46"))
    }

    /** Map order must not decide the answer — the old bug was positional, not chronological. */
    @Test
    fun `insertion order does not change the answer`() {
        val newestFirst = ProvenanceReporter.newestBundleStamp(
            mapOf("b.bundle.js" to "bbb 2026-08-22T16:13:07", "a.bundle.js" to "aaa 2026-08-02T17:32:56")
        )
        val newestLast = ProvenanceReporter.newestBundleStamp(
            mapOf("a.bundle.js" to "aaa 2026-08-02T17:32:56", "b.bundle.js" to "bbb 2026-08-22T16:13:07")
        )
        assertTrue(newestFirst.contains("2026-08-22T16:13:07"))
        assertTrue(newestLast.contains("2026-08-22T16:13:07"))
    }

    /** A bundle predating the banner (or unreadable) must not be able to win by sorting high. */
    @Test
    fun `non-stamps never win over a real stamp`() {
        val out = ProvenanceReporter.newestBundleStamp(
            mapOf(
                "old.bundle.js" to "unstamped (bundle predates build.js banner)",
                "broken.bundle.js" to "unreadable (java.io.FileNotFoundException)",
                "kiosk-shell.bundle.js" to "4ffaf9cda 2026-08-22T16:13:07",
            )
        )
        assertTrue("a real stamp must win, got: $out", out.contains("4ffaf9cda"))
    }

    /** …but when there is nothing real, say so rather than inventing a build. */
    @Test
    fun `all-unreadable degrades honestly instead of claiming a build`() {
        val out = ProvenanceReporter.newestBundleStamp(
            mapOf("broken.bundle.js" to "unreadable (boom)")
        )
        assertTrue("must surface the failure, got: $out", out.contains("unreadable"))
    }
}
