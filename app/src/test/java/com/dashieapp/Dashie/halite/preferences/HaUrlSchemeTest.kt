package com.dashieapp.Dashie.halite.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [ConnectionPreferences.ensureScheme].
 *
 * ## Why this tiny function has a test file
 *
 * A scheme-less `ha_url` is not a cosmetic defect: OkHttp's `Request.Builder.url()` throws on it,
 * and `HaAssistClient.connect()` makes that call **on the main thread** during
 * `initializeVoicePipeline` — so the stored string "192.168.1.5:8123" produced a **boot crash
 * loop**, roughly one restart every 10 seconds (Thread T, 2026-08-02). Two silent variants of the
 * same shape preceded it in `HaEventSubscriber`, which is three instances of one shape in one day.
 *
 * The reason it kept recurring is that it reads as obviously fine. So the invariant is pinned
 * here rather than trusted to inspection, and the pure helper is `internal` precisely so it can
 * be — no Robolectric, no SharedPreferences, no Android runtime.
 */
class HaUrlSchemeTest {

    @Test
    fun `scheme-less origin gets http, which is the crash case`() {
        assertEquals("http://192.168.1.5:8123", ConnectionPreferences.ensureScheme("192.168.1.5:8123"))
        assertEquals("http://homeassistant.local:8123", ConnectionPreferences.ensureScheme("homeassistant.local:8123"))
    }

    @Test
    fun `existing schemes are left exactly alone`() {
        // The failure this guards is a double prefix ("http://http://…"), which would be a NEW
        // malformed origin invented by the repair itself.
        assertEquals("http://192.168.1.5:8123", ConnectionPreferences.ensureScheme("http://192.168.1.5:8123"))
        assertEquals("https://ha.example.com", ConnectionPreferences.ensureScheme("https://ha.example.com"))
    }

    @Test
    fun `scheme matching is case-insensitive`() {
        // "HTTP://…" is a legal URL. Matching case-sensitively would prefix it and produce
        // "http://HTTP://…" — turning a working value into a crashing one, i.e. the repair
        // becoming the bug.
        assertEquals("HTTP://192.168.1.5:8123", ConnectionPreferences.ensureScheme("HTTP://192.168.1.5:8123"))
        assertEquals("HtTpS://ha.example.com", ConnectionPreferences.ensureScheme("HtTpS://ha.example.com"))
    }

    @Test
    fun `empty stays empty — blank is a real not-configured state`() {
        // Callers check for blank to mean "HA not set up yet". Inventing "http://" here would
        // turn "unset" into a request to nowhere and defeat those checks.
        assertEquals("", ConnectionPreferences.ensureScheme(""))
    }

    @Test
    fun `a path and query survive — the repair must not rewrite the URL`() {
        // A stored haUrl legitimately carries a dashboard path. This helper deliberately does NOT
        // reparse through java.net.URL, so values that work today are untouched.
        assertEquals(
            "http://192.168.1.5:8123/lovelace-home?kiosk",
            ConnectionPreferences.ensureScheme("192.168.1.5:8123/lovelace-home?kiosk")
        )
    }
}
