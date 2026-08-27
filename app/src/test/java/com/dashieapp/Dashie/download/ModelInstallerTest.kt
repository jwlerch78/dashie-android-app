package com.dashieapp.Dashie.download

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Pins the one invariant [ModelInstaller] exists for: **a file that is not verified never occupies
 * the destination path.** The STT lane gates on that path's presence, so any hole here becomes a
 * broken engine offered in the picker.
 *
 * The mismatch case is the important one — it is the difference between "download failed" (obvious)
 * and "downloaded the wrong bytes and installed them anyway" (silent, and exactly what the previous
 * wake-word downloader's 1000-byte size floor would have allowed).
 */
class ModelInstallerTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Comfortably above MIN_PLAUSIBLE_BYTES so size is never what the assertion turns on. */
    private val payload = ByteArray(4096) { (it % 251).toByte() }

    private val logged = mutableListOf<String>()

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = createTempDir(prefix = "model-installer-test")
        // android.util.Log throws "not mocked" on the plain-JVM path; capture instead, which also
        // lets the null-digest case assert that the warning actually fires rather than trusting it.
        logged.clear()
        ModelInstaller.logger = { level, msg -> logged += "$level $msg" }
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test fun `a correct digest installs the file`() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val dest = File(dir, "model.ort")

        val r = ModelInstaller.install(
            url = server.url("/model.ort").toString(),
            destination = dest,
            expectedSha256 = sha256(payload),
            tag = "test")

        assertTrue("expected Installed, got $r", r is ModelInstaller.Result.Installed)
        assertTrue(dest.exists())
        assertEquals(payload.size.toLong(), dest.length())
    }

    @Test fun `a MISMATCHED digest leaves the destination absent`() {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val dest = File(dir, "model.ort")

        val r = ModelInstaller.install(
            url = server.url("/model.ort").toString(),
            destination = dest,
            expectedSha256 = "0".repeat(64),   // deliberately wrong
            tag = "test")

        assertTrue(r is ModelInstaller.Result.Failed)
        // The whole point: not merely "failed", but nothing left behind that a presence check
        // could mistake for an installed model.
        assertFalse("destination must NOT exist after a digest mismatch", dest.exists())
        assertFalse("the .part must be cleaned up", File(dir, "model.ort.part").exists())
    }

    @Test fun `an HTTP error leaves the destination absent`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val dest = File(dir, "model.ort")

        val r = ModelInstaller.install(
            url = server.url("/model.ort").toString(),
            destination = dest, expectedSha256 = sha256(payload), tag = "test")

        assertTrue(r is ModelInstaller.Result.Failed)
        assertFalse(dest.exists())
    }

    @Test fun `a truncated response is refused as an error page, not installed`() {
        server.enqueue(MockResponse().setBody("nope"))   // 4 bytes, below MIN_PLAUSIBLE_BYTES
        val dest = File(dir, "model.ort")

        val r = ModelInstaller.install(
            url = server.url("/model.ort").toString(),
            destination = dest, expectedSha256 = null, tag = "test")

        assertTrue(r is ModelInstaller.Result.Failed)
        assertFalse(dest.exists())
    }

    @Test fun `a null digest still installs — tolerated, and the caller is warned`() {
        // The wake-word path until its manifest carries a hash. Asserted so nobody "tightens" this
        // into a hard failure without also fixing that manifest — which would silently disable
        // wake-word model updates rather than secure them.
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val dest = File(dir, "model.tflite")

        val r = ModelInstaller.install(
            url = server.url("/model.tflite").toString(),
            destination = dest, expectedSha256 = null, tag = "wakeword")

        assertTrue(r is ModelInstaller.Result.Installed)
        assertTrue(dest.exists())
        // The tolerance is only defensible because it is LOUD. If someone removes the warning,
        // the unverified path goes silent and this test is what says so.
        assertTrue("a null digest must emit a DROP naming the caller",
            logged.any { it.startsWith("W") && it.contains("DROP") && it.contains("wakeword") })
    }

    @Test fun `a pre-existing stale part file does not survive into the install`() {
        File(dir, "model.ort.part").writeBytes(ByteArray(10) { 9 })
        server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
        val dest = File(dir, "model.ort")

        val r = ModelInstaller.install(
            url = server.url("/model.ort").toString(),
            destination = dest, expectedSha256 = sha256(payload), tag = "test")

        assertTrue(r is ModelInstaller.Result.Installed)
        assertEquals(payload.size.toLong(), dest.length())
        assertFalse(File(dir, "model.ort.part").exists())
    }
}
