package com.dashieapp.Dashie.halite.videofeed

import android.util.Log
import android.webkit.WebView

/**
 * Injects JavaScript into the HA iframe to monitor trigger entity state changes.
 * When a monitored binary_sensor changes state, posts a message to the parent
 * window which the Kotlin side receives via HaliteComponentWiring.
 *
 * Consistent with existing evalInHaIframe() patterns used throughout the app.
 */
object VideoFeedTriggerInjector {

    private const val TAG = "VideoFeedTriggerInject"

    /**
     * Inject the trigger monitoring script into the HA iframe.
     * Should be called after the HA iframe has loaded and whenever rules change.
     *
     * @param webView The main WebView that contains the HA iframe
     * @param triggerEntityIds List of binary_sensor entity IDs to monitor
     */
    fun inject(webView: WebView, triggerEntityIds: List<String>) {
        if (triggerEntityIds.isEmpty()) {
            Log.d(TAG, "No trigger entities to monitor, clearing any existing injector")
            clear(webView)
            return
        }

        val entityIdsJs = triggerEntityIds.joinToString(",") { "'$it'" }
        Log.i(TAG, "Injecting trigger monitor for ${triggerEntityIds.size} entities: $entityIdsJs")

        val script = """
            (function() {
                try {
                    console.log('[VideoFeedTrigger] Injecting trigger monitor for entities:', [$entityIdsJs]);
                    // Install parent-side listener to route trigger messages to DashieNative
                    if (!window._dashieVideoFeedListenerInstalled) {
                        window._dashieVideoFeedListenerInstalled = true;
                        window.addEventListener('message', function(event) {
                            var d = event.data;
                            if (d && d.source === 'dashie-child' && d.type === 'videoFeedTrigger') {
                                console.log('[VideoFeedTrigger] Trigger message received:', JSON.stringify(d));
                                if (typeof DashieNative !== 'undefined' && DashieNative.onVideoFeedTrigger) {
                                    DashieNative.onVideoFeedTrigger(JSON.stringify(d));
                                } else {
                                    console.warn('[VideoFeedTrigger] DashieNative.onVideoFeedTrigger not available');
                                }
                            }
                        });
                        console.log('[VideoFeedTrigger] Parent listener installed');
                    }

                    // Use evalInHaIframe to inject into the HA iframe
                    if (typeof window.evalInHaIframe !== 'function') {
                        console.warn('[VideoFeedTrigger] evalInHaIframe not available');
                        return;
                    }

                    console.log('[VideoFeedTrigger] Calling evalInHaIframe');
                    window.evalInHaIframe(`
                        (function() {
                            // Guard against double-injection
                            if (window._dashieVideoFeedInjected) {
                                // Update monitored entities if already injected
                                window._dashieVideoFeedEntities = [$entityIdsJs];
                                console.log('[VideoFeedTrigger-iframe] Updated entities (already injected)');
                                return;
                            }
                            window._dashieVideoFeedInjected = true;
                            window._dashieVideoFeedEntities = [$entityIdsJs];
                            console.log('[VideoFeedTrigger-iframe] Injected, monitoring:', JSON.stringify(window._dashieVideoFeedEntities));

                            var previousStates = {};
                            var logCount = 0;

                            function checkStates() {
                                try {
                                    var hass = document.querySelector('home-assistant');
                                    if (!hass || !hass.hass || !hass.hass.states) {
                                        if (logCount++ < 5) console.log('[VideoFeedTrigger-iframe] hass not ready');
                                        return;
                                    }

                                    var entities = window._dashieVideoFeedEntities || [];
                                    for (var i = 0; i < entities.length; i++) {
                                        var entityId = entities[i];
                                        var state = hass.hass.states[entityId];
                                        if (!state) {
                                            if (logCount < 5) console.log('[VideoFeedTrigger-iframe] Entity not found:', entityId);
                                            continue;
                                        }

                                        var prev = previousStates[entityId];
                                        if (prev !== state.state) {
                                            previousStates[entityId] = state.state;
                                            // Don't fire on initial state capture
                                            if (prev !== undefined) {
                                                console.log('[VideoFeedTrigger-iframe] State change:', entityId, prev, '->', state.state);
                                                window.parent.postMessage({
                                                    source: 'dashie-child',
                                                    type: 'videoFeedTrigger',
                                                    entityId: entityId,
                                                    newState: state.state,
                                                    oldState: prev
                                                }, '*');
                                            } else {
                                                if (logCount++ < 10) console.log('[VideoFeedTrigger-iframe] Initial state:', entityId, '=', state.state);
                                            }
                                        }
                                    }
                                } catch (e) {
                                    console.error('[VideoFeedTrigger-iframe] Error:', e);
                                }
                            }

                            // Poll every 500ms
                            setInterval(checkStates, 500);
                            // Initial state capture
                            checkStates();
                        })();
                    `);
                } catch (e) {
                    console.error('[VideoFeedTrigger] Injection failed:', e);
                }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script, null)
    }

    /**
     * Clear the injected trigger monitor (e.g., when feature is disabled).
     */
    fun clear(webView: WebView) {
        Log.d(TAG, "Clearing trigger monitor")
        val script = """
            (function() {
                try {
                    if (typeof window.evalInHaIframe === 'function') {
                        window.evalInHaIframe(`
                            window._dashieVideoFeedInjected = false;
                            window._dashieVideoFeedEntities = [];
                        `);
                    }
                } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * Extract trigger entity IDs from the configured rules.
     */
    fun extractTriggerEntityIds(rules: List<org.json.JSONObject>): List<String> {
        return rules
            .filter { it.optBoolean("enabled", true) }
            .filter {
                // Only monitor triggers for trigger/trigger_alert modes (not subscribed/ignored)
                val mode = it.optString("subscriptionMode", "subscribed")
                mode == "trigger" || mode == "trigger_alert"
            }
            .mapNotNull { it.optString("triggerEntityId").takeIf { id -> id.isNotBlank() } }
            .distinct()
    }
}
