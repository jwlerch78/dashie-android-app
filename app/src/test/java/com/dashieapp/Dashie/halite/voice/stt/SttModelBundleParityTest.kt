package com.dashieapp.Dashie.halite.voice.stt

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Gates the build↔runtime model mirror that [SttModelRegistry]'s KDoc has ASSERTED since it was
 * written but that nothing enforced.
 *
 * ## What the mirror is
 *
 * `scripts/fetch-stt-models.sh` bundles models into the `local` flavor's asset set; the registry
 * describes the same archives for the download lane. The KDoc's claim — *"the model a developer
 * bundles and the model a user downloads are then provably the same artifact"* — only holds while
 * the URL and the sha256 in those two files agree.
 *
 * 🔴 **They are in different languages, in different halves of the repo, and until now the only
 * thing keeping them equal was a comment saying "if you bump one, bump the other".** That is the
 * bare hand-mirror the standing seam rule exists to catch: the tier ladder is eliminate → codegen
 * → lint, and this was on none of the rungs. Eliminating it is not available (a shell script
 * cannot read Kotlin constants without a codegen step nobody wants for two URLs), so it gets the
 * lint rung — here, where a mismatch fails in a second instead of after a 48 MB download onto a
 * device whose bundled model is a different build.
 *
 * ⚠️ **It caught something the moment it existed:** the 2026-08-23 `.onnx` re-export changed
 * base's URL and digest in the registry, and the script would have kept fetching the old 111 MB
 * `.ort` archive — so a `local` dev build and a user's download would have been *different
 * models*, which is precisely the divergence the KDoc says cannot happen.
 *
 * Skips (rather than fails) when the script cannot be located, so a checkout that does not carry
 * it — or a runner with a different working directory — reports honestly instead of red.
 */
class SttModelBundleParityTest {

    private fun fetchScript(): File? {
        // Walk up from the working dir: unit tests run with `app/` as cwd under Gradle, but that
        // is a convention rather than a guarantee, and hardcoding "../scripts/..." makes the test
        // fail for a reason that has nothing to do with the mirror.
        var dir: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "scripts/fetch-stt-models.sh")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    @Test fun `the build script fetches exactly the archives the registry describes`() {
        val script = fetchScript()
        assumeTrue("scripts/fetch-stt-models.sh not found from ${File(".").absolutePath}", script != null)
        val text = script!!.readText()

        for (family in SttModelRegistry.FAMILIES) {
            // 🎯 THE DIGEST IS THE ASSERTION, not the URL. Two facts pushed it here: the script
            // composes its URLs from a `$GH` prefix, so matching the registry's full literal URL
            // would be checking string FORMATTING rather than the mirror — and more importantly,
            // "provably the same artifact" is a claim about BYTES. A mirror serving identical
            // bytes from a different host does not break it; a different digest does, whatever
            // the URL says.
            assertTrue(
                "${family.id}: scripts/fetch-stt-models.sh does not pin the sha256 the registry " +
                    "declares (${family.archiveSha256}). The model a developer bundles and the " +
                    "model a user downloads would be DIFFERENT ARTIFACTS — update both in the " +
                    "same change, which is what SttModelRegistry's KDoc promises.",
                text.contains(family.archiveSha256),
            )
            // The archive basename, so the script is visibly fetching the same file and not just
            // happening to carry the right hex string in a comment.
            val archiveName = family.url.substringAfterLast('/')
            assertTrue(
                "${family.id}: the build script never names the archive '$archiveName'",
                text.contains(archiveName),
            )
        }
    }

    @Test fun `the build script copies the same member filenames the registry requires`() {
        // The `.ort` vs `.onnx` half of the same mirror, and the one the URL check cannot catch:
        // a correct archive whose members are extracted under the wrong names bundles an asset dir
        // the loader's probe will not match.
        val script = fetchScript()
        assumeTrue("scripts/fetch-stt-models.sh not found", script != null)
        val text = script!!.readText()

        for (family in SttModelRegistry.FAMILIES) {
            for (member in family.members) {
                assertTrue(
                    "${family.id}: the build script never mentions '$member', which the registry " +
                        "lists as a required member",
                    text.contains(member),
                )
            }
        }
    }
}
