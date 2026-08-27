package com.dashieapp.Dashie.halite.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dashieapp.Dashie.halite.HalitePreferences
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The regression test that makes the stale-brain-route strand STAY fixed (keystone §5).
 *
 * The bug: a device cached `brain_route='local'` while the account was actually `cloud`, and the
 * only corrector was a wake-word-gated probe — so a follow-up-heavy conversation stayed stranded,
 * every turn failing with a bogus "no API key". The keystone: the gateway stamps the authoritative
 * route on every converse response header, and the device re-caches from it.
 *
 * This asserts the DEVICE half end-to-end: given a cached `local` and a converse response carrying
 * `X-Dashie-Brain-Route: cloud`, the cache is `cloud` after ONE turn — no wake word, no TTL wait.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrainRouteHeaderApplyTest {

    private lateinit var server: MockWebServer
    private lateinit var prefs: HalitePreferences

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = HalitePreferences(context)
        // Install the production sink exactly as MainActivity does.
        BrainRouteApplier.applyFn = { route -> prefs.voice.applyBrainRoute(route) }
    }

    @After
    fun tearDown() {
        server.shutdown()
        BrainRouteApplier.resetForTest()
    }

    private fun converseOnce(responseBuilder: MockResponse.() -> Unit) {
        server.enqueue(MockResponse().apply(responseBuilder))
        val done = CountDownLatch(1)
        BrainConverseClient().converse(
            transcript = "what time is it",
            haUrl = server.url("/").toString().trimEnd('/'),
            haToken = "ha-token",
            endpointId = "dashboard",
            useLocalBrain = false,   // gateway path (not direct-cloud) so haUrl is used
            cloudJwt = null,
        ) { done.countDown() }
        assertEquals("converse callback did not fire", true, done.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `stale local cache flips to cloud from the response header in one turn`() {
        prefs.voice.brainRoute = "local"
        converseOnce {
            setResponseCode(200)
            addHeader("X-Dashie-Brain-Route", "cloud")
            setBody("""{"type":"response","voice":"It's 3pm","text":null}""")
        }
        assertEquals("cloud", prefs.voice.brainRoute)
    }

    @Test
    fun `header is applied even on an error response (a stranded local turn 503s)`() {
        // The strand's own failing turn carries the header too → it self-corrects, not just a
        // healthy turn. The gateway stamps the header on error paths for exactly this reason.
        prefs.voice.brainRoute = "local"
        converseOnce {
            setResponseCode(503)
            addHeader("X-Dashie-Brain-Route", "cloud")
            setBody("""{"ok":false,"error":"addon_unavailable"}""")
        }
        assertEquals("cloud", prefs.voice.brainRoute)
    }

    @Test
    fun `no header (old gateway) leaves the cache untouched`() {
        prefs.voice.brainRoute = "local"
        converseOnce {
            setResponseCode(200)
            setBody("""{"type":"response","voice":"hi","text":null}""")
        }
        assertEquals("local", prefs.voice.brainRoute)
    }

    @Test
    fun `a garbage header value never downgrades the cache`() {
        prefs.voice.brainRoute = "cloud"
        converseOnce {
            setResponseCode(200)
            addHeader("X-Dashie-Brain-Route", "banana")
            setBody("""{"type":"response","voice":"hi","text":null}""")
        }
        assertEquals("cloud", prefs.voice.brainRoute)
    }
}
