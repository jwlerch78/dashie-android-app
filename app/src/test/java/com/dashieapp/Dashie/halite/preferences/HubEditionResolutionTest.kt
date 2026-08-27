package com.dashieapp.Dashie.halite.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the gateway-`hub` wire contract (JS_KOTLIN_CONTRACTS #59) — the one that already
 * shipped broken.
 *
 * The published integration renamed its own hub id along with its domain
 * (`dashie-voice-integration@5ad8e6c`, v0.7.0): `chickadee` → `dashie_voice`. Kotlin still
 * tested `== "chickadee"`, so every APK pointed at the DEV add-on mis-detected the hub and
 * resolved to the FULL edition **silently** — wrong management-surface name, wrong branch in
 * CreditUrls, and nothing in the log to say so.
 *
 * There is no lint that can catch this (Python ↔ Kotlin, two repos, no shared build), so these
 * assertions are the gate. Each one is a live production case, not a hypothetical:
 *
 *  - `dashie_voice`    — what the DEV add-on sends today (0.9.6). THE BUG.
 *  - `chickadee_voice` — what CHICKADEE's integration sends. A distinct PRODUCT, not a rename.
 *  - `chickadee`       — what the PROD add-on still sends (0.8.6); prod is a promotion, not a
 *                        build, so it lags by design. Deleting this case breaks every
 *                        un-promoted household. ⚠️ Despite the string, this is DASHIE's
 *                        pre-0.7 id — hence [VoicePreferences.HUB_DASHIE_VOICE_PRE_0_7].
 *  - `""`              — the full edition. Its integration emits no `hub` key at all, so
 *                        absence *is* the signal.
 *  - anything else     — fails toward PUBLISHED with a loud DROP, because only the published
 *                        integrations emit `hub`, so an unknown id is far likelier to be a
 *                        newer published one than a full-edition device.
 *
 * ⚠️ The two `chickadee`-shaped values are NOT the same thing and must not be collapsed:
 * `chickadee_voice` is Chickadee's current id; bare `chickadee` is Dashie's historic one.
 */
// Robolectric only because the unknown-hub branch emits a real android.util.Log DROP, which a
// plain JVM test cannot call. That the DROP is load-bearing enough to force this is the point.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HubEditionResolutionTest {

    @Test
    fun `the current published hub id resolves to published`() {
        assertTrue(
            "dashie_voice is what the dev add-on sends TODAY — this is the shipped bug",
            VoicePreferences.isPublishedHubValue(VoicePreferences.HUB_DASHIE_VOICE)
        )
        assertTrue(VoicePreferences.isPublishedHubValue("dashie_voice"))
    }

    @Test
    fun `Chickadee's hub id resolves to published`() {
        assertTrue(
            "chickadee_voice is what Chickadee's generated integration sends — a distinct " +
                "product, not a rename of dashie_voice",
            VoicePreferences.isPublishedHubValue(VoicePreferences.HUB_CHICKADEE_VOICE)
        )
        assertTrue(VoicePreferences.isPublishedHubValue("chickadee_voice"))
    }

    @Test
    fun `Dashie's pre-0_7 hub id still resolves to published`() {
        assertTrue(
            "prod still sends bare 'chickadee' until it is promoted past the rename",
            VoicePreferences.isPublishedHubValue(VoicePreferences.HUB_DASHIE_VOICE_PRE_0_7)
        )
        assertTrue(VoicePreferences.isPublishedHubValue("chickadee"))
    }

    /**
     * The rename's whole point, pinned: the two `chickadee`-shaped constants carry DIFFERENT
     * values. If a future edit ever makes them equal, one product's households start reading
     * as the other's — silently, since both resolve to `true`.
     */
    @Test
    fun `Chickadee's id and Dashie's pre-0_7 id are different values`() {
        assertNotEquals(
            "HUB_CHICKADEE_VOICE is Chickadee; HUB_DASHIE_VOICE_PRE_0_7 is Dashie's old id",
            VoicePreferences.HUB_CHICKADEE_VOICE,
            VoicePreferences.HUB_DASHIE_VOICE_PRE_0_7
        )
    }

    @Test
    fun `an absent hub means the full edition`() {
        assertFalse(
            "the full edition's integration has no `hub` field — absence is the signal",
            VoicePreferences.isPublishedHubValue("")
        )
    }

    @Test
    fun `an unrecognised hub fails toward published rather than silently to full`() {
        assertTrue(
            "the next rename must not read as a full-edition device the way this one did",
            VoicePreferences.isPublishedHubValue("dashie_voice_v2")
        )
    }
}
