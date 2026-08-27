package com.dashieapp.Dashie.webview.injectors

import android.util.Log
import android.webkit.WebView

/**
 * Music Player JavaScript Injection for Home Assistant WebView.
 *
 * Injects scripts that:
 * - Subscribe to media_player entity state changes via HA's hass object
 * - Send state updates to Kotlin via DashieNative.updateMusicPlayer(json)
 * - Handle playback controls (play/pause/next/prev/stop)
 * - Manage voice ducking (lower volume during wake word interactions)
 * - Handle Music Assistant play_media fallback for voice commands
 * - Detect and handle idle-bounce behavior from Music Assistant
 * - Query available media_player entities for settings dialog
 */
object MusicPlayerJsInjector {

    private const val TAG = "MusicPlayerJsInjector"

    // Pending callback for shell-relay media players query
    @Volatile
    private var pendingQueryCallback: ((String) -> Unit)? = null

    // Asset cache. The injector is hit many times during boot (once per
    // entity switch + once per media-player query) and `assets.open(...)`
    // is synchronous I/O. Boot is already CPU-saturated by H.264 video
    // decode + JS bootstrap; reading a 50KB asset from disk on the UI
    // thread every call has caused boot ANRs on Samsung. Cache after first
    // read — assets are immutable for the lifetime of the APK install.
    @Volatile private var cachedSubscriptionJs: String? = null
    @Volatile private var cachedRecentlyPlayedJs: String? = null

    private fun loadAssetCached(webView: WebView, name: String, current: String?, store: (String) -> Unit): String {
        current?.let { return it }
        val text = webView.context.assets.open(name).bufferedReader().use { it.readText() }
        store(text)
        return text
    }

    /**
     * Build the music player subscription IIFE script. Loads the JS body from
     * assets/js/music-player-subscription.js and substitutes the per-call
     * placeholders for the active entity and force-reinject flag.
     */
    private fun buildMusicPlayerSubscriptionJs(webView: WebView, entityId: String, force: Boolean): String {
        val template = loadAssetCached(
            webView,
            "js/music-player-subscription.js",
            cachedSubscriptionJs
        ) { cachedSubscriptionJs = it }
        return template
            .replace("__DASHIE_ENTITY_ID__", entityId.replace("\\", "\\\\").replace("'", "\\'"))
            .replace("__DASHIE_FORCE__", force.toString())
    }

    private fun buildRecentlyPlayedQueryJs(webView: WebView): String {
        return loadAssetCached(
            webView,
            "js/music-player-recently-played.js",
            cachedRecentlyPlayedJs
        ) { cachedRecentlyPlayedJs = it }
    }


    fun injectMusicPlayerSubscription(webView: WebView?, entityId: String, force: Boolean = false) {
        webView ?: return
        val js = buildMusicPlayerSubscriptionJs(webView, entityId, force)
        webView.evaluateJavascript(js, null)
        Log.i(TAG, "🎵 Injected music player subscription for entity: $entityId")
    }

    /**
     * Inject music player subscription via the kiosk shell page.
     * Sends the script through evalInHaIframe() which relays it
     * to the HA iframe via postMessage.
     */
    fun injectMusicPlayerSubscriptionViaShell(webView: WebView?, entityId: String, force: Boolean = false) {
        webView ?: return
        val js = buildMusicPlayerSubscriptionJs(webView, entityId, force)
        val escaped = js.replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\\$")
        webView.evaluateJavascript("evalInHaIframe(`${escaped}`);", null)
        Log.i(TAG, "🎵 Injected music player subscription via shell for entity: $entityId (force=$force)")
    }

    /**
     * Inject a one-shot query to fetch recently played albums and artists
     * from Music Assistant via the HA websocket. Results are sent back
     * to Kotlin via DashieNative.updateRecentlyPlayed(json).
     */
    fun injectRecentlyPlayedQuery(webView: WebView?) {
        webView ?: return
        val js = buildRecentlyPlayedQueryJs(webView)
        webView.evaluateJavascript(js, null)
        Log.i(TAG, "🎵 Injected recently played query")
    }

    /**
     * Inject recently played query via the kiosk shell page (iframe relay).
     */
    fun injectRecentlyPlayedQueryViaShell(webView: WebView?) {
        webView ?: return
        val js = buildRecentlyPlayedQueryJs(webView)
        val escaped = js.replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\\$")
        webView.evaluateJavascript("evalInHaIframe(`${escaped}`);", null)
        Log.i(TAG, "🎵 Injected recently played query via shell")
    }


    /**
     * Query all media_player entities from Home Assistant.
     * Returns JSON array via callback with entity info.
     *
     * @param webView The WebView containing the HA frontend
     * @param callback Receives JSON string with array of {entityId, friendlyName, state}
     */
    fun queryMediaPlayers(webView: WebView?, callback: (String) -> Unit) {
        webView ?: run {
            callback("[]")
            return
        }

        val js = """
            (function() {
                const TAG = '[DashieMediaQuery]';
                const ha = document.querySelector('home-assistant');
                if (!ha || !ha.hass || !ha.hass.states) {
                    console.log(TAG, 'HA not ready');
                    return JSON.stringify([]);
                }

                const states = ha.hass.states;
                const players = [];

                for (const entityId in states) {
                    if (entityId.startsWith('media_player.')) {
                        const state = states[entityId];
                        const friendlyName = state.attributes?.friendly_name || entityId;
                        players.push({
                            entityId: entityId,
                            friendlyName: friendlyName,
                            state: state.state || 'unknown'
                        });
                    }
                }

                console.log(TAG, 'Found', players.length, 'media players');
                return JSON.stringify(players);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            // Result comes back as a JSON string wrapped in quotes, need to parse it
            val jsonString = if (result.startsWith("\"") && result.endsWith("\"")) {
                result.substring(1, result.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            } else {
                result
            }
            Log.i(TAG, "🎵 Media players query result: $jsonString")
            callback(jsonString)
        }
    }

    /**
     * Query all media_player entities via the kiosk shell page's evalInHaIframe relay.
     * Used when HA is in an iframe (shell page architecture) — the direct
     * evaluateJavascript approach can't reach the HA document in that case.
     *
     * The query script runs in the HA iframe and calls DashieNative.onMediaPlayersQueried(json)
     * to return results asynchronously.
     *
     * @param webView The WebView containing the kiosk shell page
     * @param callback Receives JSON string with array of {entityId, friendlyName, state}
     */
    fun queryMediaPlayersViaShell(webView: WebView?, callback: (String) -> Unit) {
        webView ?: run {
            callback("[]")
            return
        }

        pendingQueryCallback = callback

        // Timeout: if HA iframe doesn't respond within 5s, return empty
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            pendingQueryCallback?.let {
                Log.w(TAG, "🎵 Shell media players query timed out")
                it.invoke("[]")
                pendingQueryCallback = null
            }
        }, 5000L)

        val queryScript = """
            (function() {
                var ha = document.querySelector('home-assistant');
                if (!ha || !ha.hass || !ha.hass.states) {
                    console.log('[DashieMediaQuery] HA not ready in iframe');
                    if (window.DashieNative && window.DashieNative.onMediaPlayersQueried) {
                        window.DashieNative.onMediaPlayersQueried('[]');
                    }
                    return;
                }
                var states = ha.hass.states;
                var players = [];
                for (var entityId in states) {
                    if (entityId.indexOf('media_player.') === 0) {
                        var state = states[entityId];
                        var friendlyName = (state.attributes && state.attributes.friendly_name) || entityId;
                        players.push({
                            entityId: entityId,
                            friendlyName: friendlyName,
                            state: state.state || 'unknown'
                        });
                    }
                }
                console.log('[DashieMediaQuery] Found ' + players.length + ' media players (via shell relay)');
                if (window.DashieNative && window.DashieNative.onMediaPlayersQueried) {
                    window.DashieNative.onMediaPlayersQueried(JSON.stringify(players));
                }
            })();
        """.trimIndent()

        val escaped = queryScript.replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\\$")
        webView.evaluateJavascript("evalInHaIframe(`${escaped}`);", null)
        Log.i(TAG, "🎵 Sent media players query via shell relay")
    }

    /**
     * Handle the async result from queryMediaPlayersViaShell.
     * Called from DashieJSBridge.onMediaPlayersQueried().
     */
    fun handleMediaPlayersQueryResult(json: String) {
        Log.i(TAG, "🎵 Received media players query result via shell relay: ${json.take(200)}")
        pendingQueryCallback?.invoke(json)
        pendingQueryCallback = null
    }
}
