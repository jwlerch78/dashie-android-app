package com.dashieapp.Dashie.halite.voice.lease

import android.util.Log
import com.dashieapp.Dashie.edition.ApiPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The device half of the capability lease: renew on a cadence, self-destruct on expiry, and
 * fall back loudly (`JS_KOTLIN_CONTRACTS #65`, nudge transport #68).
 *
 * All policy lives in [CapabilityLease] / [LeaseClock], which are pure and unit-tested. This
 * class owns only the things that cannot be: a socket, a clock, and a coroutine.
 *
 * ## Renewal runs on its OWN timer, not on a voice turn
 *
 * Sharing-off has to take effect on an **idle** tablet — a device nobody has spoken to all day is
 * precisely the one the switch was flipped for. Tying renewal to turns would make revocation
 * invisible until someone spoke, which is the failure the whole mechanism exists to remove.
 *
 * ## The loop never sleeps past expiry
 *
 * The wait is bounded by BOTH the next renewal instant and `expires_at`, so a device that has been
 * offline for a whole TTL still self-destructs on time rather than waking late. That is the
 * offline-revocation guarantee: being unreachable *kills* the capability instead of preserving it.
 *
 * ## The HA token is read LIVE, never captured
 *
 * It rotates roughly every 30 minutes; a token captured at construction would work for one cycle
 * and then fail every renewal — which this protocol would correctly read as UNKNOWN and ride until
 * expiry, so the device would self-destruct on a bug that looks exactly like an unreachable
 * add-on. Hence providers, not values.
 */
class LeaseRenewalService(
    private val lease: CapabilityLease,
    /** Live HA base URL, e.g. `http://192.168.1.10:8123`. Blank ⇒ skip this attempt. */
    private val haUrlProvider: () -> String,
    /** Live HA token — see the note above; rotates, so never cache the result. */
    private val haTokenProvider: () -> String,
    /** This device's id, as already sent to `/voice/converse`. */
    private val endpointIdProvider: () -> String?,
    /** Capabilities to request; null ⇒ "everything you'd grant me". */
    private val capabilitiesProvider: () -> List<String>? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val http: OkHttpClient = defaultClient(),
) {

    private companion object {
        const val TAG = "LeaseRenewal"

        /** Ceiling on a single sleep, so a wrong/absent schedule can't park the loop forever. */
        const val MAX_SLEEP_MS = 5 * 60_000L

        /** Floor, so a pathological schedule cannot spin. */
        const val MIN_SLEEP_MS = 1_000L

        /**
         * First retry interval when NO lease is held.
         *
         * With nothing held there is no schedule to derive a wait from, and the floor alone meant
         * one request per second forever against an add-on that is down or has no endpoint yet —
         * observed on device before the endpoints existed. Backs off to [MAX_SLEEP_MS].
         */
        const val NO_LEASE_RETRY_MS = 5_000L

        /**
         * First retry interval when a lease IS held but its renewal is failing.
         *
         * 🔴 The sibling of [NO_LEASE_RETRY_MS], and it was missing — measured by Thread T on
         * hardware: **201 attempts in 200 seconds, exactly 1.00/sec**, from the moment renewals
         * started failing until expiry. At the shipping 1800s TTL that is ~1700 requests per
         * outage per device, aimed at an add-on that is by definition already unwell.
         *
         * The cause is structural rather than a typo, which is why reading the code did not catch
         * it: a failed renewal leaves `nextRenewAtMs` in the PAST, so `next - now` is negative and
         * [MIN_SLEEP_MS] catches it — the floor doing its job and, in this one case, producing the
         * exact symptom the no-lease branch's comment already warned about. One branch got the
         * backoff; its twin did not.
         */
        const val HELD_RETRY_MS = 5_000L

        /**
         * Ceiling on the held-lease backoff. Deliberately well under any real TTL (the shipping
         * one is 1800s, the debug floor 10s): the loop must still come round to [lease.tick] before
         * `expires_at` or self-destruct arrives late, and expiry timing is the guarantee the whole
         * protocol rests on. The sleep is clamped against time-to-expiry as well, so this cap is
         * the second line of defence rather than the only one.
         */
        const val MAX_HELD_RETRY_MS = 60_000L

        /** Short, because a hung request must not hold the lease past its renewal window. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private var job: Job? = null

    /** Backoff for the no-lease-held case; reset whenever a lease is in hand. */
    private var noLeaseBackoffMs = NO_LEASE_RETRY_MS

    /**
     * Backoff for the held-but-failing case; reset whenever the schedule moves into the future
     * (i.e. a renewal actually succeeded). See [HELD_RETRY_MS].
     */
    private var heldBackoffMs = HELD_RETRY_MS

    /**
     * Wake signal. CONFLATED on purpose: ten nudges arriving together should cost one renewal,
     * not ten. Carries the reason for the log only — the loop never branches on it (#68).
     */
    private val wake = Channel<String>(Channel.CONFLATED)

    /** Start the loop. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        // 🔴 Dispatchers.IO, NOT the caller's scope dispatcher. HaliteVoiceController's scope is
        // Dispatchers.MAIN, and attempt() calls OkHttp execute() synchronously — on Main that
        // throws NetworkOnMainThreadException on EVERY attempt, so no renewal ever reaches the
        // network and every device self-destructs at expiry. Found on device; the protocol's own
        // robustness HID it, because a transport failure is correctly UNKNOWN, so the bug
        // degraded gracefully into never working. Nothing here touches UI.
        job = scope.launch(Dispatchers.IO) {
            // Renew-at-boot: T pinned L2b to attempting immediately rather than waiting out
            // `renew_after`. Note this is only about WHEN the first attempt happens — a failure
            // here is still UNKNOWN, so a boot while the add-on is down (both restarting after a
            // power cut) changes nothing and the cached lease stands until expires_at.
            renewNow("boot")
            while (isActive) {
                val now = nowMs()
                lease.tick(now)                       // expiry fires even if every renewal failed
                val reason = withTimeoutOrNull(sleepMs(now)) { wake.receive() }
                if (reason != null) {
                    Log.i(TAG, "renew-now (${reason})")
                }
                attempt()
            }
        }
    }

    /** Stop the loop. The lease itself is untouched — stopping is not revoking. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Renew at the next opportunity.
     *
     * Called for renew-at-boot and for the #68 nudge. 🔴 **This does not and must not revoke.**
     * The nudge carries no authorization; the device renews and obeys the REAL response, so a
     * spoofed packet costs at most one extra renewal instead of killing voice.
     */
    fun renewNow(reason: String) {
        wake.trySend(reason)
    }

    /**
     * How long to wait before the next attempt: bounded by the schedule AND by expiry.
     *
     * `internal` rather than private so the backoff is directly assertable. The 1/sec defect lived
     * here and **23 green tests missed it**, because every one of them exercised the loop's
     * *decisions* and none its *cadence* — the pure state machine has no clock and the HTTP tests
     * assert on responses. A rate is only visible if something measures the rate.
     */
    internal fun sleepMs(now: Long): Long {
        val candidates = listOfNotNull(
            lease.nextRenewAtMs.takeIf { it > 0 },
            lease.expiresAtMs.takeIf { it > 0 },
        )
        val next = candidates.minOrNull()
        if (next == null) {
            // No lease held ⇒ no schedule exists. Back off instead of retrying at the floor,
            // which would be one request per second forever while the add-on is unreachable.
            val wait = noLeaseBackoffMs
            noLeaseBackoffMs = (noLeaseBackoffMs * 2).coerceAtMost(MAX_SLEEP_MS)
            return wait
        }
        noLeaseBackoffMs = NO_LEASE_RETRY_MS   // holding a lease ⇒ a real schedule; reset

        val untilNext = next - now
        if (untilNext > 0) {
            // The schedule is genuinely ahead of us ⇒ the last renewal succeeded. Reset.
            heldBackoffMs = HELD_RETRY_MS
            return untilNext.coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS)
        }

        // 🔴 The schedule is in the PAST: we hold a lease whose renewal is failing. Without a
        // backoff this floors at MIN_SLEEP_MS and hammers a sick add-on at 1/sec until expiry
        // (T measured 201 attempts in 200s). Back off like the no-lease twin — but clamp to the
        // time remaining before expiry, because the loop MUST come round to tick() on time: a
        // late self-destruct would trade a request storm for a broken revocation guarantee, and
        // the guarantee is the point. Expiry still fires to the second; only the retry rate drops.
        val wait = heldBackoffMs
        heldBackoffMs = (heldBackoffMs * 2).coerceAtMost(MAX_HELD_RETRY_MS)
        val untilExpiry = lease.expiresAtMs.takeIf { it > 0 }?.let { it - now } ?: Long.MAX_VALUE
        return wait.coerceAtMost(untilExpiry).coerceIn(MIN_SLEEP_MS, MAX_SLEEP_MS)
    }

    /** One renewal round-trip, folded into the state machine. Never throws. */
    private fun attempt() {
        val base = haUrlProvider().trimEnd('/')
        val token = haTokenProvider()
        if (base.isBlank() || token.isBlank()) {
            // Not reachable ⇒ UNKNOWN, never a denial: no HA config yet, or the token has not
            // been minted. Keeping the lease is correct — nothing has told us the grant changed.
            lease.onResponse(LeaseResponse.Unknown("no_ha_credentials"), nowMs())
            return
        }

        val url = base + ApiPaths.HA + LeaseWire.PATH_SUFFIX
        val body = LeaseWire.requestBody(endpointIdProvider(), capabilitiesProvider())
            .toRequestBody("application/json".toMediaTypeOrNull())

        val response = try {
            http.newCall(
                Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .post(body)
                    .build()
            ).execute().use { r -> LeaseWire.classify(r.code, r.body?.string()) }
        } catch (e: Exception) {
            // Transport failure is UNKNOWN by definition — the add-on is unreachable, so the
            // grant state is unknown rather than withdrawn.
            LeaseResponse.Unknown("transport:${e.javaClass.simpleName}")
        }

        lease.onResponse(response, nowMs())
    }
}
