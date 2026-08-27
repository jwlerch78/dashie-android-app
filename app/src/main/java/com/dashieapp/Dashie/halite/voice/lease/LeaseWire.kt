package com.dashieapp.Dashie.halite.voice.lease

import org.json.JSONObject

/**
 * Parsing and classification for the capability-lease wire protocol
 * (`JS_KOTLIN_CONTRACTS #65`, full contract in `CAPABILITY_LEASE_WIRE.md`).
 *
 * Pure: no Android, no network, no clock. That is what makes the one distinction this protocol
 * turns on — 403 vs 503 — a plain JVM unit test with a negative control rather than a device run.
 *
 * ## 🔴 The load-bearing distinction, in the contract's own words
 *
 * > a **403** is a definite refusal from a present add-on → self-destruct IMMEDIATELY
 * > a **503/timeout** is the grant state being UNKNOWN, not withdrawn → keep the lease, retry
 *
 * "Conflate them and either every add-on restart silently revokes the household, or revocation
 * never takes." Both halves of that sentence are a shipped outage, which is why classification is
 * its own type rather than an `if` inside the renewal loop.
 */
sealed interface LeaseResponse {

    /** HTTP 200 with a usable body. */
    data class Granted(
        /** 🔴 A LIST, never a boolean (contract D6). An absent capability is DENIED, not "false
         *  means all off" — see [Lease.allows]. */
        val capabilities: List<String>,
        /** RFC 3339 UTC, **the authority**, as epoch millis. 0 when unparseable. */
        val expiresAtMs: Long,
        /** Informational duration; the fallback when the device's clock is untrustworthy. */
        val ttlSeconds: Long,
        /** When to start renewing. **The add-on sets the cadence**, not the device, so a TTL
         *  change ships as config rather than as an APK. */
        val renewAfterSeconds: Long,
        /**
         * Why each absent capability is absent — `capability` → `sharing_disabled` |
         * `capability_unavailable` (#70). **Diagnostic for the LEASE, load-bearing for the UI.**
         *
         * 🔴 The device still decides what it can DO from [capabilities] alone — absent means
         * denied, and that must not start depending on a reason string the add-on may not send.
         * This exists so the device can say WHY, which is a different job: *"add a key"* and
         * *"turn sharing on"* are opposite instructions and the user needs the right one.
         *
         * Empty when the add-on is older than #70 or sent nothing parseable. Empty ⇒ unknown,
         * never "not configured".
         */
        val withheld: Map<String, String> = emptyMap(),
    ) : LeaseResponse

    /** HTTP 403 — a definite no from an add-on that is present and answering. */
    data class Denied(
        /** For logs and the console only. **The device branches on the STATUS, never on this
         *  string**, so B can add a reason without an APK. */
        val reason: String,
    ) : LeaseResponse

    /** HTTP 503, a timeout, or any transport failure — grant state UNKNOWN, not withdrawn. */
    data class Unknown(val detail: String) : LeaseResponse
}

object LeaseWire {

    /** Path SUFFIX. Composed onto the single `ApiPaths.HA` prefix — never a second prefix. */
    const val PATH_SUFFIX = "/voice/lease"

    /** Default when the caller does not name itself, per the contract. */
    const val DEFAULT_ENDPOINT_ID = "ha-voice"

    /** Request body. `capabilities` omitted means "everything you'd grant me". */
    fun requestBody(endpointId: String?, capabilities: List<String>?): String =
        JSONObject().apply {
            put("endpoint_id", endpointId?.takeIf { it.isNotBlank() } ?: DEFAULT_ENDPOINT_ID)
            capabilities?.let { caps ->
                put("capabilities", org.json.JSONArray().also { arr -> caps.forEach(arr::put) })
            }
        }.toString()

    /**
     * Classify a response. [body] may be null or unparseable — that must never throw, because a
     * malformed body from a present add-on is still information about reachability.
     *
     * ⚠️ **An unparseable 200 is [LeaseResponse.Unknown], not [LeaseResponse.Granted].** Granting
     * on a body we could not read would invent a capability the add-on never conferred; treating
     * it as unknown keeps whatever lease we already hold and lets it expire honestly.
     */
    fun classify(httpStatus: Int, body: String?): LeaseResponse = when {
        httpStatus == 403 -> LeaseResponse.Denied(
            reason = runCatching { JSONObject(body ?: "").optString("reason") }
                .getOrNull().orEmpty().ifEmpty { "unspecified" }
        )
        httpStatus == 200 -> parseGranted(body)
            ?: LeaseResponse.Unknown("200 with unparseable body")
        else -> LeaseResponse.Unknown("http_$httpStatus")
    }

    private fun parseGranted(body: String?): LeaseResponse.Granted? {
        val json = runCatching { JSONObject(body ?: "") }.getOrNull() ?: return null
        // `granted:false` on a 200 is contradictory; treat it as not-a-grant rather than guessing.
        if (json.has("granted") && !json.optBoolean("granted", true)) return null

        val caps = json.optJSONArray("capabilities") ?: return null
        val list = (0 until caps.length()).mapNotNull { i ->
            caps.optString(i).takeIf { it.isNotBlank() }
        }
        // `withheld` — shipped by the add-on since #70 (`capability.js:183`) and, until now,
        // consumed by nobody: contracted, emitted, and read by nothing. Authored-but-unreached
        // inside the wire itself. It became load-bearing the moment a user-facing string had to
        // tell "no AI keys configured" apart from "sharing is off" — two states that look
        // identical in `capabilities` and imply OPPOSITE user actions (add a key vs flip a
        // toggle).
        //
        // ⚠️ Absent or unparseable ⇒ EMPTY, never a guessed reason. Old add-ons do not send this
        // field, and a device that inferred "no keys set up" from its absence would tell a
        // perfectly healthy household to go configure something. Absent means "we do not know
        // why", and the caller must fall back to the reason-free wording.
        val withheld = json.optJSONObject("withheld")?.let { obj ->
            obj.keys().asSequence().mapNotNull { k ->
                obj.optString(k).takeIf { it.isNotBlank() }?.let { k to it }
            }.toMap()
        }.orEmpty()

        return LeaseResponse.Granted(
            capabilities = list,
            expiresAtMs = parseRfc3339(json.optString("expires_at")),
            ttlSeconds = json.optLong("ttl_seconds", 0L),
            renewAfterSeconds = json.optLong("renew_after_seconds", 0L),
            withheld = withheld,
        )
    }

    /**
     * RFC 3339 UTC → epoch millis, 0 when absent or unparseable.
     *
     * 0 is meaningful, not an error swallow: [LeaseClock] treats a missing `expires_at` as
     * "trust `ttl_seconds` instead", which is exactly the bad-clock fallback the contract asks
     * for. Returning 0 keeps that decision in one place rather than throwing here.
     */
    fun parseRfc3339(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching {
            java.time.Instant.parse(value).toEpochMilli()
        }.getOrElse {
            runCatching {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            }.getOrDefault(0L)
        }
    }
}
