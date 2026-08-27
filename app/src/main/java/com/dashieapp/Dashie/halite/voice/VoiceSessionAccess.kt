package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.webkit.WebView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.SupabaseTokenExtractor
import com.dashieapp.Dashie.halite.preferences.AiPreferences

/**
 * Session access for the AI voice lanes — the ONE place that answers:
 *   (a) which conversation agent mode is in effect on THIS device, and
 *   (b) what Bearer credential the conversation-relay socket should use.
 *
 * 2026-07-09 Live-on-kiosk: an anonymous kiosk now runs Live too. Its halves ride
 * the capability probe ([DashieCloudCapabilityClient] caches the household agentMode
 * from the integration `/status`) and the minted `scope:'voice'` session token
 * ([com.dashieapp.Dashie.halite.voice.stt.SttCredentialProvider] caches + refreshes
 * it; the relay verifies it with the same secret and bills the household `sub`).
 * Supersedes the P3.14 live→dialog downgrade — Live is CHEAPER per turn than
 * cascade+cloud-TTS, so kiosks lead with it.
 */
object VoiceSessionAccess {

    /**
     * The effective conversation agent mode ("live" | "dialog" | "single"):
     *   - Logged-in → the account-pushed mode ([com.dashieapp.Dashie.halite.preferences
     *     .VoicePreferences.conversationEngineMode], which honors agentMode + the
     *     legacy derivation). Unchanged behavior.
     *   - Anonymous kiosk → the account can't push settings here, so: a locally set
     *     agentMode → else the probed household mode ([VoicePreferences.kioskAgentMode],
     *     console → add-on → integration `/status` → capability probe) → else the kiosk
     *     default 'live'.
     *
     * NOTE (2026-07-13): on a share-account kiosk the local `agentMode` is no longer a
     * user/dev override — [KioskAccountVoiceApplier] hard-applies the household mode into
     * it on every probe (a stale local 'single' was beating the account's 'dialog' here).
     * The `kioskAgentMode` fallback still covers the window before the first apply and the
     * case where sharing is off.
     */
    fun effectiveAgentMode(halitePrefs: HalitePreferences): String {
        val conn = halitePrefs.connection
        if (conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired) {
            return halitePrefs.voice.conversationEngineMode
        }
        return halitePrefs.voice.agentMode.ifBlank {
            halitePrefs.voice.kioskAgentMode.ifBlank { "live" }
        }
    }

    /**
     * The effective "retrieve pictures in AI responses" flag for the relay config:
     *   - Logged-in → the account-pushed device pref (AiPreferences, unchanged).
     *   - Anonymous kiosk → the household value cached by the capability probe
     *     ([com.dashieapp.Dashie.halite.preferences.VoicePreferences.kioskRetrievePictures]);
     *     defaults false (the pre-probe behavior — the relay then omits image_search).
     * Same account-setting-can't-reach-anon-device class as agentMode (found on the
     * 2026-07-09 kiosk Live validation: Samsung showed images, the kiosk never did).
     */
    fun effectiveRetrievePictures(ctx: Context): Boolean {
        val halitePrefs = HalitePreferences(ctx)
        val conn = halitePrefs.connection
        val loggedIn = conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired
        if (loggedIn) return AiPreferences(ctx).retrievePicturesEnabled
        return halitePrefs.voice.kioskRetrievePictures
    }

    /**
     * Refresh the household capability + agentMode + brain-route cache (best-effort,
     * async). Called at pipeline init — not just settings-open — so a console mode change
     * or sharing toggle takes effect on the next boot/nightly-recreation without the user
     * opening Settings. Anonymous kiosk → full probe ([effectiveAgentMode] reads the cached
     * result per turn). Logged-in → route-only probe: account push covers the kiosk fields,
     * but `brain_route` (Open Brain §5) depends on the box's key store, which never syncs —
     * the probe is the only way a logged-in device learns its cascade turns should go
     * through the gateway to the add-on brain. No-op when HA isn't configured.
     */
    fun refreshKioskCapability(context: Context, halitePrefs: HalitePreferences) {
        val conn = halitePrefs.connection
        val anon = !(conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired)
        if (conn.getHaOrigin().isNullOrBlank() || conn.haAccessToken.isBlank()) return
        DashieCloudCapabilityClient().refresh(
            haUrl = conn.getHaOrigin() ?: "",
            haToken = conn.haAccessToken,
            voicePrefs = halitePrefs.voice,
            routeOnly = !anon,
            // Anon kiosk only: the probe hard-applies the account's voice config
            // (kiosk voice-config mirror, Phase 1). Logged-in devices get their
            // config via the account push, so no Context → apply is skipped.
            context = if (anon) context.applicationContext else null,
        )
    }

    /**
     * Re-probe the add-on brain route at WAKE (fired during STT, so the fresh value is
     * cached before the converse decides direct-cloud-vs-gateway) — TTL-gated so it hits
     * /status at most once per [ttlMs]. This is the fix for "add an API key → it doesn't
     * take effect until I restart the app" (Open Brain §5): the route depends on the box's
     * key store, which never syncs, and was previously only probed at pipeline init. The
     * probe (~200ms) reliably completes inside the STT window, so the SAME turn picks up a
     * just-added key; a slow probe is corrected by the next turn. Best-effort, never blocks.
     */
    fun refreshBrainRouteIfStale(halitePrefs: HalitePreferences, ttlMs: Long = 30_000L) {
        val conn = halitePrefs.connection
        if (conn.getHaOrigin().isNullOrBlank() || conn.haAccessToken.isBlank()) return
        if (System.currentTimeMillis() - halitePrefs.voice.brainRouteCheckedAtMs < ttlMs) return
        DashieCloudCapabilityClient().refresh(
            haUrl = conn.getHaOrigin() ?: "",
            haToken = conn.haAccessToken,
            voicePrefs = halitePrefs.voice,
            routeOnly = true,
        )
    }

    /**
     * Force a brain-route re-probe NOW, ignoring the TTL (unlike [refreshBrainRouteIfStale]).
     *
     * This is the self-heal primitive (see [BrainRouteSelfHeal]): when a locally-routed turn fails
     * to reach the local brain, the cached route may be stale, so we re-probe unconditionally to
     * learn the add-on's CURRENT answer before deciding whether to retry on cloud. The wake-time
     * re-probe is TTL-gated and won't fire on a dialog follow-up — this is how a stranded dialog
     * recovers without waiting for the next fresh wake.
     *
     * The probe updates [VoicePreferences.brainRoute] as a side effect (an explicit valid route
     * overwrites the cache; a failed probe leaves it unchanged). No-op when HA isn't configured.
     */
    fun forceRefreshBrainRoute(halitePrefs: HalitePreferences) {
        val conn = halitePrefs.connection
        if (conn.getHaOrigin().isNullOrBlank() || conn.haAccessToken.isBlank()) return
        DashieCloudCapabilityClient().refresh(
            haUrl = conn.getHaOrigin() ?: "",
            haToken = conn.haAccessToken,
            voicePrefs = halitePrefs.voice,
            routeOnly = true,
        )
    }

    /**
     * Resolve the Bearer for the conversation-relay socket, async:
     *   - Logged-in → the cached account JWT, refreshing via the WebView when expired
     *     (the original path, unchanged).
     *   - Anonymous kiosk → the minted `scope:'voice'` session token the STT lane
     *     already holds ([sessionBearer], typically SttCredentialProvider::currentBearer).
     * [onResult] gets null when no credential is available (caller speaks a friendly
     * abort). May fire on an arbitrary thread — post to a handler before touching state.
     */
    fun resolveLiveBearer(
        halitePrefs: HalitePreferences,
        webView: WebView?,
        sessionBearer: () -> String?,
        onResult: (String?) -> Unit,
    ) {
        val conn = halitePrefs.connection
        if (conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired) { onResult(conn.supabaseJwt); return }
        if (conn.hasSupabaseJwt) {
            // An account exists but the token is stale — refresh via the WebView (logged-in
            // flow); fall back to the session token if the refresh fails.
            SupabaseTokenExtractor.ensureToken(webView, conn) { ok ->
                onResult(if (ok) conn.supabaseJwt else sessionBearer())
            }
            return
        }
        // Anonymous: the session token (prewarmed at init; just used by STT this turn).
        onResult(sessionBearer())
    }
}
