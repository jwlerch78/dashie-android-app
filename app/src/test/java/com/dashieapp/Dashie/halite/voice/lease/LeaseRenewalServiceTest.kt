package com.dashieapp.Dashie.halite.voice.lease

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The renewal loop's real HTTP behaviour, against a MockWebServer.
 *
 * [CapabilityLeaseTest] already pins the policy; this covers only what a pure test cannot see —
 * that a real 403 body reaches the state machine as a destruct, that a real 503 does not, and
 * that the request is built from the single `ApiPaths` prefix rather than a second hard-coded one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LeaseRenewalServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    // 🔴 Synchronized, not plain ArrayLists. The renewal loop runs on Dispatchers.IO and appends
    // through LeaseMarkers.emit while the test thread iterates these in waitUntil/assertions —
    // an unsynchronized read-while-append throws ConcurrentModificationException. Observed as an
    // intermittent failure of the 503 test that passed on re-run, i.e. a flake, which in a suite
    // Thread T's rows depend on is worse than a red: it trains everyone to re-run and move on.
    // CopyOnWriteArrayList, not synchronizedList: every read here ITERATES (count/any/$markers),
    // and synchronizedList only guards single operations — iteration still needs the caller to
    // hold the lock, which is exactly the mistake that looks fixed and isn't. COW gives snapshot
    // iteration with no external synchronization, and these lists are tiny and rarely written.
    private val destructs = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val markers = java.util.concurrent.CopyOnWriteArrayList<String>()
    private lateinit var lease: CapabilityLease

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        destructs.clear(); markers.clear()
        LeaseMarkers.emit = { markers += it }
        lease = CapabilityLease(onDestruct = { destructs += it }, onRestore = { })
    }

    @After fun tearDown() {
        scope.cancel(); server.shutdown(); LeaseMarkers.resetForTest()
    }

    private fun service() = LeaseRenewalService(
        lease = lease,
        haUrlProvider = { server.url("/").toString().trimEnd('/') },
        haTokenProvider = { "ha-token" },
        endpointIdProvider = { "kiosk-living-room" },
    )

    @Test fun `a real 403 drives self-destruct through the state machine`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"granted":true,"capabilities":["voice"],"ttl_seconds":1800,
               "renew_after_seconds":600}"""
        ))
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"granted":false,"reason":"sharing_disabled"}"""
        ))

        val svc = service()
        svc.start(scope)

        // First request: the renew-at-boot attempt.
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertTrue("must build from the single ApiPaths prefix",
            first.path!!.endsWith("/voice/lease"))
        assertEquals("Bearer ha-token", first.getHeader("Authorization"))
        assertTrue(first.body.readUtf8().contains("kiosk-living-room"))

        svc.renewNow("test")
        server.takeRequest(5, TimeUnit.SECONDS)

        waitUntil { destructs.isNotEmpty() }
        assertEquals(listOf("denied:sharing_disabled"), destructs)
        assertTrue(markers.any { it.startsWith(LeaseMarkers.FALLBACK_ENGAGED) })
        svc.stop()
    }

    @Test fun `a real 503 does NOT revoke — the lease rides it`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"granted":true,"capabilities":["voice"],"ttl_seconds":1800,
               "renew_after_seconds":600}"""
        ))
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val svc = service()
        svc.start(scope)
        server.takeRequest(5, TimeUnit.SECONDS)
        repeat(3) { svc.renewNow("test"); server.takeRequest(5, TimeUnit.SECONDS) }

        waitUntil { markers.count { it.startsWith(LeaseMarkers.UNKNOWN) } >= 1 }
        assertTrue("an unreachable add-on is not a revocation", lease.isGranted)
        assertTrue(destructs.isEmpty())
        svc.stop()
    }

    @Test fun `no HA credentials is UNKNOWN, never a denial`() {
        val svc = LeaseRenewalService(
            lease = lease,
            haUrlProvider = { "" },
            haTokenProvider = { "" },
            endpointIdProvider = { null },
        )
        svc.start(scope)
        waitUntil { markers.any { it.contains("no_ha_credentials") } }
        assertFalse(lease.isGranted)
        assertTrue("must not report a destruct it never had", destructs.isEmpty())
        svc.stop()
    }

    /**
     * 🔴 Regression for the defect Thread T measured on hardware: a HELD lease whose renewals are
     * failing retried at exactly 1.00/sec until expiry (201 attempts in 200s). A failed renewal
     * leaves `nextRenewAtMs` in the past, so `next - now` went negative and MIN_SLEEP_MS caught it.
     *
     * This asserts the CADENCE, which is what the other 23 tests could not see — they all pin
     * decisions (403 destroys, 503 does not), and a request storm is a correct decision taken far
     * too often.
     */
    @Test fun `a held lease whose renewal is failing BACKS OFF instead of retrying every second`() {
        val t0 = 1_000_000L
        // Granted: renew after 10s, expires in an hour — so the schedule is real and expiry is far.
        lease.onResponse(
            LeaseResponse.Granted(
                capabilities = listOf("voice"),
                expiresAtMs = t0 + 3_600_000L,
                ttlSeconds = 3600,
                renewAfterSeconds = 10,
            ),
            t0,
        )
        assertTrue(lease.isGranted)

        val svc = service()
        // While the schedule is still ahead, the wait tracks it — no backoff involved.
        assertEquals(10_000L, svc.sleepMs(t0))

        // Now walk time PAST the renewal instant with no successful renewal: the failing-held case.
        val past = t0 + 20_000L
        val waits = (1..5).map { svc.sleepMs(past) }

        assertEquals(
            "must back off, not floor at MIN_SLEEP_MS — this is T's 1/sec storm",
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L),
            waits,
        )
        assertTrue("must cap, not grow without bound", svc.sleepMs(past) <= 60_000L)

        // 🔴 The guarantee the backoff must not break: it never sleeps past expiry, so the
        // self-destruct still fires on time. Near expiry the wait collapses to the remaining time.
        val nearExpiry = t0 + 3_600_000L - 2_000L
        assertEquals(2_000L, svc.sleepMs(nearExpiry))

        // And a renewal that SUCCEEDS resets the backoff — otherwise one bad patch would leave the
        // device renewing a minute late for the rest of its uptime.
        lease.onResponse(
            LeaseResponse.Granted(
                capabilities = listOf("voice"),
                expiresAtMs = nearExpiry + 3_600_000L,
                ttlSeconds = 3600,
                renewAfterSeconds = 10,
            ),
            nearExpiry,
        )
        assertEquals(10_000L, svc.sleepMs(nearExpiry))
        val pastAgain = nearExpiry + 20_000L
        assertEquals("backoff must restart from the first step", 5_000L, svc.sleepMs(pastAgain))
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms; markers=$markers")
    }
}
