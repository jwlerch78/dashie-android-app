package com.dashieapp.Dashie.halite

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single source of truth for "give me a current HA access token" callers.
 *
 * Background: HA's OAuth access tokens expire (~30 min). The OAuth login flow
 * stores both the access token and a refresh token; existing code at a few
 * call sites refreshes via [HaTokenExtractor.refreshTokenSync] /
 * [HaTokenExtractor.refreshTokenAsync] when it notices the access token has
 * expired. But other consumers (video feed availability probes in
 * [com.dashieapp.Dashie.halite.videofeed.VideoFeedOverlayManager], future
 * background API callers) used to read [HalitePreferences.haAccessToken]
 * directly with no expiry check. That meant a sleeping device whose token
 * expired during downtime would get HA 401s on every poll until something
 * else (a JS bridge call, a page load) happened to trigger refresh.
 *
 * This provider centralises the read so all such consumers are refresh-aware.
 *
 * Read semantics:
 *   - Fresh cached token → returned immediately, no I/O.
 *   - Expired AND a refresh token is available → returns the stale token AND
 *     kicks off an asynchronous refresh in the background. Caller gets one
 *     cycle of 401s; the next [get] call returns the refreshed token.
 *   - No refresh token → returns whatever's stored; caller will fail (same
 *     behaviour as before this provider existed).
 *
 * Why background-refresh and not sync-block: typical callers run inside a
 * periodic loop (5-30s probe cadence). Returning the stale token costs one
 * cycle of 401 noise; blocking every read on a sync HTTP refresh adds
 * 100-300ms latency to every consumer, including ones whose tokens didn't
 * need refreshing. Eventual-consistency wins here.
 *
 * In-flight refreshes are deduped via [refreshing] so concurrent reads from
 * multiple probe threads can't fire N parallel /auth/token requests.
 */
class HaTokenProvider(
    private val prefs: HalitePreferences,
) {
    companion object { private const val TAG = "HaTokenProvider" }

    private val refreshing = AtomicBoolean(false)

    /**
     * Get the current HA access token. Triggers a background refresh if the
     * token has expired and a refresh token is on file. Always returns
     * synchronously; never blocks on I/O.
     */
    fun get(): String {
        val conn = prefs.connection
        val token = conn.haAccessToken
        if (token.isEmpty()) return ""

        if (conn.isHaTokenExpired && conn.haRefreshToken.isNotEmpty()) {
            triggerBackgroundRefresh()
        }
        return token
    }

    private fun triggerBackgroundRefresh() {
        if (!refreshing.compareAndSet(false, true)) return  // single-flight
        Log.d(TAG, "Token expired; kicking off async refresh")
        HaTokenExtractor.refreshTokenAsync(prefs) { ok ->
            refreshing.set(false)
            if (ok) Log.i(TAG, "Background token refresh succeeded")
            else Log.w(TAG, "Background token refresh failed")
        }
    }
}
