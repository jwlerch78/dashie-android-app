package com.dashieapp.Dashie.halite.voice.lease

/**
 * Reconciles the two expiry authorities the contract deliberately ships together, and decides
 * when to renew.
 *
 * Pure and injectable-clock, so every branch below is a JVM unit test.
 *
 * ## Why there are two, and why SOONER wins
 *
 * The contract gives both `expires_at` (an absolute instant, the stated authority) and
 * `ttl_seconds` (the same interval as a duration), and warns:
 *
 * > `expires_at` and `ttl_seconds` will disagree if the device's clock is wrong. The device should
 * > renew at whichever comes SOONER. **A clock ahead of the add-on's must not extend a lease.**
 *
 * That asymmetry is the whole point. Taking the later of the two would let a device with a fast
 * clock hold a revoked capability past the household's intent — the failure the lease exists to
 * prevent — while taking the sooner only ever costs an early, harmless renewal.
 */
object LeaseClock {

    /**
     * The instant this lease dies, as epoch millis.
     *
     * @param expiresAtMs the server's absolute expiry, or 0 if absent/unparseable
     * @param ttlSeconds the server's duration, or 0 if absent
     * @param receivedAtMs when we received the grant (device clock)
     * @return 0 when the server gave us NEITHER — the caller must treat that as "no usable lease"
     *   rather than as "never expires". Defaulting to infinity here is how a device keeps a
     *   capability forever on a malformed grant.
     */
    fun effectiveExpiryMs(expiresAtMs: Long, ttlSeconds: Long, receivedAtMs: Long): Long {
        val fromTtl = if (ttlSeconds > 0) receivedAtMs + ttlSeconds * 1000L else 0L
        return when {
            expiresAtMs > 0 && fromTtl > 0 -> minOf(expiresAtMs, fromTtl)  // SOONER wins
            expiresAtMs > 0 -> expiresAtMs
            fromTtl > 0 -> fromTtl
            else -> 0L
        }
    }

    /**
     * When to begin renewing, as epoch millis.
     *
     * `renew_after_seconds` is the add-on's cadence — the device does not choose it, so a TTL
     * change ships as add-on config rather than as an APK. When it is absent we fall back to
     * **one third** of the lease's own life, matching the contract's default and giving roughly
     * three attempts before expiry: enough to ride out an add-on restart without widening the
     * revocation window.
     */
    fun renewAtMs(
        renewAfterSeconds: Long,
        receivedAtMs: Long,
        effectiveExpiryMs: Long,
    ): Long {
        if (renewAfterSeconds > 0) return receivedAtMs + renewAfterSeconds * 1000L
        if (effectiveExpiryMs <= receivedAtMs) return receivedAtMs
        return receivedAtMs + (effectiveExpiryMs - receivedAtMs) / 3
    }

    /** Has [effectiveExpiryMs] passed? A 0 expiry is treated as expired — see the note above. */
    fun isExpired(effectiveExpiryMs: Long, nowMs: Long): Boolean =
        effectiveExpiryMs <= 0L || nowMs >= effectiveExpiryMs
}
