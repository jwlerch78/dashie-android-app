package com.dashieapp.Dashie.halite.voice.stt

import android.util.Log

/**
 * Resolves the Bearer credential the [DeepgramSttProvider] streams `deepgram-stt-stream` with.
 *
 * Two paths (same logged-in-vs-anonymous fork [com.dashieapp.Dashie.halite.voice.BrainConverseClient]
 * uses for converse):
 *  - **Logged-in** device (account JWT cached natively, even in kiosk display mode) → the account
 *    JWT IS the credential; nothing to fetch. It carries a `sub`, so the hardened proxy accepts it.
 *  - **Anonymous** kiosk (no Dashie login) → a short-lived, STT-scoped token fetched from the
 *    integration's `/api/dashie/voice/session` ([SttSessionTokenClient]) and cached until near expiry.
 *
 * [currentBearer] is synchronous (called from `startSession` on the audio thread) and returns the
 * freshest cached value, kicking a background refresh when the anonymous token is stale/missing. A
 * null return means "no valid credential yet" → the provider falls back to the legacy anon key,
 * which only streams while the proxy's `stt_auth_enforce` flag is OFF (build plan C11 rollout).
 *
 * Build plan §3.2 (WS2) / §2.6 topology A.
 */
class SttCredentialProvider(
    private val endpointId: String,
    private val haOrigin: () -> String?,       // conn.getHaOrigin() — clean scheme://host:port
    private val haToken: () -> String,         // conn.haAccessToken
    private val accountBearer: () -> String?,  // conn.supabaseJwt when present + unexpired, else null
    private val client: SttSessionTokenClient = SttSessionTokenClient(),
) {
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiryMs: Long = 0L
    @Volatile private var fetching = false

    /** The Bearer to authenticate the Deepgram WS with, or null (→ legacy anon-key fallback). */
    fun currentBearer(): String? {
        // Logged-in: the account JWT is the credential; no minted token needed.
        accountBearer()?.let { return it }

        // Anonymous: use the cached minted token while it's comfortably valid.
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && now < cachedExpiryMs - REFRESH_SKEW_MS) return cached

        // Stale or missing → kick an async refresh and return whatever we have (may be null this
        // once → anon-key fallback). By the time the flag flips, prewarm() has run.
        refresh()
        return cached
    }

    /** Warm the anonymous token at pipeline init so the first utterance has a valid credential. */
    fun prewarm() {
        if (accountBearer() == null) refresh()
    }

    private fun refresh() {
        if (fetching) return
        val origin = haOrigin()
        val token = haToken()
        if (origin.isNullOrBlank() || token.isBlank()) return // no HA → can't mint; anon-key fallback
        fetching = true
        client.fetch(origin, token, endpointId) { result ->
            fetching = false
            if (result != null) {
                cachedToken = result.sttToken
                cachedExpiryMs = System.currentTimeMillis() + result.expiresInMs
            } else {
                Log.d(TAG, "STT token refresh returned null (sharing off / unreachable) — anon-key fallback")
            }
        }
    }

    companion object {
        private const val TAG = "SttCredential"
        private const val REFRESH_SKEW_MS = 120_000L // refresh 2 min before expiry
    }
}
