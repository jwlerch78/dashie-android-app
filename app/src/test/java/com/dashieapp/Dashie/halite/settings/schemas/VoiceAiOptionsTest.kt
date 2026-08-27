package com.dashieapp.Dashie.halite.settings.schemas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the Local-preset AI-model options (punch #2b): Home Assistant is offered as a
 * first-class AI in the Local preset, alongside the own-AI row, and no cloud models leak
 * into Local. Non-local presets still offer Home Assistant as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceAiOptionsTest {

    @Test fun `local preset offers Home Assistant first, then own-AI`() {
        val opts = VoiceAiOptions.aiModelOptions(cloudAvailable = true, preset = "local")
        assertEquals("home_assistant", opts.first().value)
        assertTrue("own-AI row must remain", opts.any { it.value == "local" })
    }

    @Test fun `local preset offers no cloud models`() {
        val opts = VoiceAiOptions.aiModelOptions(cloudAvailable = true, preset = "local")
        assertFalse(
            "Local must stay local — no cloud LLM leaks in",
            opts.any { it.value.contains("gemini") || it.value.contains("claude") || it.value.contains("gpt") },
        )
    }

    @Test fun `non-local presets still offer Home Assistant`() {
        val hybrid = VoiceAiOptions.aiModelOptions(cloudAvailable = true, preset = "hybrid")
        assertTrue(hybrid.any { it.value == "home_assistant" })
    }

    // ── On-device STT rows: install state decides selectability (on-demand models, 08-04) ──

    private fun stt(models: Map<String, Int?>) = VoiceAiOptions.sttOptions(
        hasAccount = true, sherpaAvailable = true, sherpaModels = models)

    @Test fun `an on-device row whose model is NOT installed cannot be selected`() {
        // 🔴 The outcome this whole feature turns on: selecting a model that is not on the device
        // would point the STT lane at an engine that cannot run. The row must offer to BECOME
        // real, not pretend it already is.
        // Subject moved tiny -> base when tiny was retired (2026-08-20). The INVARIANT is
        // unchanged; only the model that carries it changed.
        val row = stt(mapOf("sherpa_moonshine_base" to 111))
            .first { it.value == "sherpa_moonshine_base" }
        assertFalse("a not-yet-downloaded model must not be selectable", row.enabled)
        assertEquals("onSttModelDownloadRequested", row.onDisabledTap)
        assertTrue("the row must state the size before the user commits",
            row.sublabel!!.contains("111 MB"))
    }

    // ── Retirement of moonshine-tiny (John, 2026-08-20 "Yes - B") ──────────────────

    @Test fun `the retired tiny model is NOT offered, in any install state`() {
        // Offered-ness must not depend on whether the old model happens to be installed —
        // that was the failure mode on the device that already had it.
        for (models in listOf(
            emptyMap<String, Int?>(),
            mapOf("sherpa_moonshine_tiny" to null),   // installed
            mapOf("sherpa_moonshine_tiny" to 30),     // downloadable
        )) {
            assertTrue("tiny must not appear in the offering (models=$models)",
                stt(models).none { it.value == "sherpa_moonshine_tiny" })
        }
    }

    @Test fun `the on-device rows are named by PROVENANCE, never by quality`() {
        // John, 2026-08-20 ("I like Open Source and System"). Both rows run on the device, so the
        // question the label answers is WHOSE CODE IS LISTENING — ours or the OS vendor's.
        val rows = stt(mapOf("sherpa_moonshine_base" to null))
        assertEquals("On-Device (Open Source)",
            rows.first { it.value == "sherpa_moonshine_base" }.label)
        assertEquals("On-Device (Built-in)",
            rows.first { it.value == "android_voice" }.label)

        // No quality word may return through any row's label — "(Accurate)"/"(Fast)" were a
        // contrast between two models, and with one model they are bare promises.
        for (row in rows) {
            for (word in listOf("Accurate", "Fast", "Best", "Native")) {
                assertFalse("'$word' must not appear in a picker label — got '${row.label}'",
                    row.label.contains(word))
            }
        }
    }

    @Test fun `Google is disclosed in the sublabel, never asserted in the label`() {
        // In the label it reads as an accusation; in the sublabel, as disclosure.
        // androidSttAvailable=true is required: the unavailable branch says "Not available on this
        // device" and discloses nothing, which is correct — there is no vendor recognizer to name.
        val row = VoiceAiOptions.sttOptions(
            hasAccount = true, sherpaAvailable = true, androidSttAvailable = true,
            sherpaModels = mapOf("sherpa_moonshine_base" to null),
        ).first { it.value == "android_voice" }
        assertFalse(row.label.contains("Google"))
        assertTrue("the system recognizer's provenance must be disclosed — got '${row.sublabel}'",
            row.sublabel!!.contains("Google"))
    }

    @Test fun `the download dialog title carries the same qualifier as the row that opens it`() {
        // 🔴 The coupling this pins is a REAL defect twice over: the dialog title once read
        // "Local speech recognition (accurate)" beside a row reading "On-Device", and then — when
        // the row gained "(Open Source)" — "On-Device Speech Recognition", leaving one shared
        // token. A picker row and the dialog it opens are the same "row gate and tap popup can
        // never disagree" discipline, so assert the qualifier travels rather than trusting a KDoc.
        val row = stt(mapOf("sherpa_moonshine_base" to 111))
            .first { it.value == "sherpa_moonshine_base" }
        val dialogTitle = com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry
            .byProviderValue("sherpa_moonshine_base")!!.label

        val qualifier = Regex("\\(([^)]+)\\)").find(row.label)!!.groupValues[1]
        assertTrue("row '${row.label}' and dialog '$dialogTitle' must share the qualifier",
            dialogTitle.contains(qualifier))
        assertTrue("both must keep the 'On-Device' family prefix",
            row.label.startsWith("On-Device") && dialogTitle.startsWith("On-Device"))
    }

    @Test fun `the on-device size stays DATA-DRIVEN, never hardcoded in the trait`() {
        // The dialog once stated tiny's 30 MB for base's 111 MB download because a number was
        // written into copy. The size must come from the registry's measured wire size.
        val needsDownload = stt(mapOf("sherpa_moonshine_base" to 111))
            .first { it.value == "sherpa_moonshine_base" }
        assertTrue("a not-installed row must state its measured size — got '${needsDownload.sublabel}'",
            needsDownload.sublabel!!.contains("111 MB"))

        val installed = stt(mapOf("sherpa_moonshine_base" to null))
            .first { it.value == "sherpa_moonshine_base" }
        assertFalse("an installed row must not quote a download size — got '${installed.sublabel}'",
            installed.sublabel!!.contains("MB"))
    }

    @Test fun `an installed on-device row is selectable and offers no download`() {
        val row = stt(mapOf("sherpa_moonshine_base" to null))
            .first { it.value == "sherpa_moonshine_base" }
        assertTrue(row.enabled)
        // A tap route on an ENABLED row would be dead wiring — the picker only invokes
        // onDisabledTap for disabled rows, so leaving one here reads as a handler that never runs.
        assertNull(row.onDisabledTap)
        assertFalse("an installed model must not still say 'download required'",
            row.sublabel!!.contains("download required"))
    }

    @Test fun `a row unknown to the registry keeps its historic selectable behaviour`() {
        // Empty map = the old caller shape. Nothing about install state is known, so the rows must
        // behave exactly as they did before on-demand models existed rather than defaulting to
        // "blocked" and making on-device STT unreachable on a path that never opted in.
        val rows = stt(emptyMap()).filter { it.value.startsWith("sherpa_") }
        assertTrue("expected the on-device rows", rows.isNotEmpty())
        rows.forEach {
            assertTrue("${it.value} must stay selectable when state is unknown", it.enabled)
            assertNull(it.onDisabledTap)
        }
    }
}
