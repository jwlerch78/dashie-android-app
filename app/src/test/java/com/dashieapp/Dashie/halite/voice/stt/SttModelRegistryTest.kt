package com.dashieapp.Dashie.halite.voice.stt

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins the three things [SttModelRegistry] is trusted about, all of which fail EXPENSIVELY on a
 * device: the frozen id mapping, what "installed" means, and where models come from.
 *
 * Each test here corresponds to a failure that costs a 42–135 MB download to discover by hand,
 * which is why they are unit tests rather than a device pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SttModelRegistryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ── The frozen id mapping ────────────────────────────────────────────────

    @Test fun `provider values are the frozen wire ids`() {
        // ⚠️ CONTRACT #16 — these exact strings are cross-checked against
        // js/data/settings/voice-ai-value-ids.js by lint:voice-options. Asserted literally so a
        // change to the derivation (hyphens, prefix) fails HERE rather than as a picker row that
        // silently matches nothing on one of the three surfaces.
        assertEquals("sherpa_moonshine_tiny",
            SttModelRegistry.providerValue(SttModelRegistry.byId("moonshine-tiny")!!))
        assertEquals("sherpa_moonshine_base",
            SttModelRegistry.providerValue(SttModelRegistry.byId("moonshine-base")!!))
    }

    @Test fun `every family round-trips through its provider value`() {
        // The settings page maps family → value and the download callback maps value → family.
        // If those two ever disagree, tapping "download" either does nothing or fetches the wrong
        // model. One direction being right is not the property that matters; the round trip is.
        for (family in SttModelRegistry.FAMILIES) {
            val value = SttModelRegistry.providerValue(family)
            assertEquals("round trip failed for ${family.id}",
                family, SttModelRegistry.byProviderValue(value))
        }
    }

    @Test fun `an unknown provider value resolves to null rather than a guess`() {
        // The stale-picker case. Returning "the first sherpa family" here would download 135 MB of
        // something the user did not ask for.
        assertNull(SttModelRegistry.byProviderValue("sherpa_whisper_large"))
        assertNull(SttModelRegistry.byProviderValue("dashie_cloud"))
        assertNull(SttModelRegistry.byProviderValue(""))
    }

    // ── What "installed" means ───────────────────────────────────────────────

    @Test fun `a family with every member present is installed`() {
        val family = SttModelRegistry.FAMILIES.first()
        writeMembers(family, family.members)
        assertTrue(SttModelRegistry.isInstalled(context, family))
    }

    @Test fun `a family MISSING one member is not installed`() {
        // The load-bearing case: the STT lane gates on this predicate, so a directory that exists
        // but lost a file must read as absent — otherwise the picker offers an engine that will
        // fail at load, which is precisely the present-but-broken state.
        val family = SttModelRegistry.FAMILIES.first()
        writeMembers(family, family.members.drop(1))
        assertFalse(SttModelRegistry.isInstalled(context, family))
    }

    @Test fun `a ZERO-LENGTH member is not installed`() {
        // A truncated or half-written file. `File.exists()` would say yes — which is why the
        // predicate checks length, and why this test exists to stop anyone simplifying it back.
        val family = SttModelRegistry.FAMILIES.first()
        writeMembers(family, family.members)
        File(SttModelRegistry.dir(context, family), family.members.first()).writeBytes(ByteArray(0))
        assertFalse(SttModelRegistry.isInstalled(context, family))
    }

    @Test fun `an absent family directory is not installed`() {
        val family = SttModelRegistry.FAMILIES.last()
        SttModelRegistry.dir(context, family).deleteRecursively()
        assertFalse(SttModelRegistry.isInstalled(context, family))
    }

    // ── Where models come from ───────────────────────────────────────────────

    /**
     * 🔴 **This test used to assert edition-independence for EVERY family, and on 2026-08-23 it
     * caught the change that spent it — which is the whole reason it existed.** John ruled the
     * `.onnx` re-export INTO 1.1.0 and chose Dashie hosting (option B) over HuggingFace (option A)
     * because HF serves individual files, not a `.tar.bz2`, and that would need a second
     * multi-file download mode in `ModelInstaller` for one family.
     *
     * ⚠️ **NARROWED, NOT DELETED, and the difference matters.** Deleting it would silently un-gate
     * every OTHER family — including any future one — for a decision that was made about exactly
     * one. So the rule still holds for everything it still protects, and the single exemption is
     * named, with the reason, so a second Dashie-hosted URL fails here and has to be argued for
     * rather than pattern-matched off this one.
     */
    @Test fun `only the named exemption may be Dashie-hosted, every other model stays upstream`() {
        // The one family John consciously exempted. Not a substring match on "base" — the exact id,
        // so a NEW family cannot inherit the exemption by being named similarly.
        val exempt = setOf(SherpaEngineLoader.MODEL_MOONSHINE_BASE)

        for (family in SttModelRegistry.FAMILIES) {
            val dashieHosted =
                family.url.contains("dashieapp.com") || family.url.contains("supabase")

            if (family.id in exempt) {
                // Assert the exemption is USED, not merely permitted. If base ever moves back
                // upstream this fails, and whoever moves it deletes the exemption in the same
                // change instead of leaving a stale licence lying around for the next family.
                assertTrue(
                    "${family.id} is the named Dashie-hosting exemption but is no longer " +
                        "Dashie-hosted (${family.url}) — remove it from `exempt` here and from " +
                        "the PARTIALLY SPENT section of SttModelRegistry's KDoc",
                    dashieHosted,
                )
                continue
            }

            assertTrue("${family.id} must download from k2-fsa upstream, got ${family.url}",
                family.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/"))
            assertFalse(
                "${family.id} must not point at a Dashie host. Re-hosting reintroduces the " +
                    "dependency an account-free device must not have; if that is genuinely " +
                    "intended it is John's ruling to make, and it goes in `exempt` above WITH " +
                    "the reason — not by widening this assertion",
                dashieHosted,
            )
        }
    }

    // ── The per-family member split (2026-08-23) ─────────────────────────────

    @Test fun `members are DERIVED from the encoder and decoder the recognizer opens`() {
        // 🔴 The property that used to hold BY LUCK. `members` (what the installer extracts and
        // what isInstalled verifies) and the filenames SherpaEngineLoader passes to sherpa were
        // two separate literals that happened to agree while both families shipped `.ort`. Now
        // that base ships `.onnx` and tiny ships `.ort`, agreement has to be structural: members
        // is computed from the same two fields the loader reads.
        for (family in SttModelRegistry.FAMILIES) {
            assertEquals(
                "${family.id}: members must be exactly [encoder, decoder, tokens]",
                listOf(family.encoder, family.decoder, family.tokens),
                family.members,
            )
        }
    }

    @Test fun `the two families do NOT share one member list`() {
        // The seam rule, asserted. A single shared MOONSHINE_MEMBERS constant was correct exactly
        // while the families matched; re-introducing one would silently make the installer look
        // for `.ort` in base's `.onnx` archive (or vice versa) and fail only after a 48 MB
        // download. This is the test that says "these are allowed to differ, and do".
        val tiny = SttModelRegistry.byId(SherpaEngineLoader.MODEL_MOONSHINE_TINY)!!
        val base = SttModelRegistry.byId(SherpaEngineLoader.MODEL_MOONSHINE_BASE)!!
        assertEquals("moonshine-tiny still ships the .ort export", "encoder_model.ort", tiny.encoder)
        assertEquals("moonshine-base ships the .onnx export", "encoder_model.onnx", base.encoder)
        assertFalse("the families must not share one member list", tiny.members == base.members)
    }

    @Test fun `a base install carrying the OLD ort members reads as not installed`() {
        // S s20 cont.5 option (b): same id, new members. This is what makes an existing 111 MB
        // `.ort` install turn back into a download prompt instead of loading a model whose files
        // the recognizer will not open — and what makes SttModelInstaller's sweep reclaim it.
        val base = SttModelRegistry.byId(SherpaEngineLoader.MODEL_MOONSHINE_BASE)!!
        writeMembers(base, listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt"))
        assertFalse(
            "an .ort-era base install must not satisfy the .onnx family contract",
            SttModelRegistry.isInstalled(context, base),
        )
        // Control: the same directory WITH the current members is installed, so the assertion
        // above is discriminating on the filenames and not on the write helper failing.
        writeMembers(base, base.members)
        assertTrue(SttModelRegistry.isInstalled(context, base))
    }

    @Test fun `the download shrinks on both wire and disk`() {
        // The whole point of the re-export. Stated as a relation rather than as the two numbers,
        // so it keeps meaning something if the artifact is ever re-cut.
        val tiny = SttModelRegistry.byId(SherpaEngineLoader.MODEL_MOONSHINE_TINY)!!
        val base = SttModelRegistry.byId(SherpaEngineLoader.MODEL_MOONSHINE_BASE)!!
        assertEquals("base wire size is the MEASURED 48 MB, not the ~59 estimate", 48, base.approxMb)
        assertTrue("base must extract to less than the 141 MB .ort build did",
            base.extractedBytes < 141_300_566L)
        // ...and still be the bigger model, or the ids are crossed.
        assertTrue(base.extractedBytes > tiny.extractedBytes)
    }

    @Test fun `every family pins a well-formed sha256`() {
        // A pasted-wrong digest (uppercase, truncated, a placeholder) is only discovered AFTER the
        // user has spent the whole download — ModelInstaller compares case-insensitively but a
        // wrong LENGTH can never match anything at all.
        val hex = Regex("^[0-9a-f]{64}$")
        for (family in SttModelRegistry.FAMILIES) {
            assertTrue("${family.id} digest is not 64 lowercase hex chars: ${family.archiveSha256}",
                hex.matches(family.archiveSha256))
        }
    }

    @Test fun `families declare a size and their member files`() {
        for (family in SttModelRegistry.FAMILIES) {
            // The size is what the settings row states before the user commits; a zero would
            // render "download required (~0 MB)" and read as free.
            assertTrue("${family.id} must state a plausible size", family.approxMb > 0)
            assertTrue("${family.id} must name its member files", family.members.isNotEmpty())
            assertNotNull(SttModelRegistry.byId(family.id))
        }
    }

    /** Write [members] as non-empty files into the family's real install dir. */
    private fun writeMembers(family: SttModelRegistry.Family, members: List<String>) {
        val dir = SttModelRegistry.dir(context, family)
        dir.deleteRecursively()
        dir.mkdirs()
        members.forEach { File(dir, it).writeBytes(ByteArray(64) { b -> b.toByte() }) }
    }
}
