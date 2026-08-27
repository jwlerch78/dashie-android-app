/**
 * WebSocket Monitor for Dashie Lite
 *
 * Intercepts Home Assistant WebSocket to measure message rates and
 * identify entity update patterns that may cause performance issues.
 *
 * Privacy: We only collect aggregate metrics (counts, rates, domains).
 * We DO NOT collect entity names, states, or any personal data.
 *
 * This script works in two phases:
 * 1. A minimal proxy is injected very early (onPageStarted) to intercept WS creation
 * 2. This full script is injected later and attaches to already-intercepted WebSockets
 */
(function() {
    'use strict';

    // Don't install twice
    if (window._dashieWsMonitorInstalled) {
        console.log('[DashieLite] WebSocket monitor already installed');
        return;
    }
    window._dashieWsMonitorInstalled = true;

    console.log('[DashieLite] WebSocket Monitor installing...');

    // Get original WebSocket - either from our proxy's backup or the current one
    // (_dwo is the minified variable name from the minimal proxy)
    const OriginalWebSocket = window._dwo || window._dashieOriginalWebSocket || window.WebSocket;

    // Metrics tracking
    window._dashieWsMetrics = {
        // Counts
        messagesReceived: 0,
        messagesSent: 0,
        stateChanges: 0,

        // Timing
        startTime: Date.now(),
        lastReportTime: Date.now(),

        // Entity tracking (by domain only, not full entity IDs)
        entityDomainUpdates: {},  // e.g., {"sensor": 450, "light": 20}
        uniqueEntitiesUpdated: new Set(),

        // Message type breakdown
        messageTypes: {},  // e.g., {"event": 500, "result": 20}

        // Rate tracking (rolling window)
        recentMessages: [],  // timestamps of recent messages
        peakMessagesPerSecond: 0,

        // Collection window for reporting
        collectionWindowMs: 60000,  // 1 minute collection window
        reportIntervalMs: 60000,    // Report every minute

        // Interaction priority tracking
        droppedDuringInteraction: 0,
    };

    // ========================================================================
    // CONNECTION HEALTH TRACKING
    // ========================================================================
    // Track WebSocket connection health to diagnose disconnect issues
    // (e.g., Android doze mode killing connections)

    window._dashieWsHealth = {
        // Connection events
        disconnectCount: 0,
        reconnectCount: 0,
        lastDisconnectTime: null,
        lastReconnectTime: null,
        disconnectEvents: [],  // [{time, code, reason, connectionDurationMs}] - last 10

        // Message gap detection (connection "alive" but no data flowing)
        lastMessageTime: Date.now(),
        maxMessageGapMs: 0,
        messageGaps: [],  // [{startTime, endTime, durationMs}] - gaps > 30s, last 10
        currentGapStart: null,

        // Staleness detection
        staleAlertCount: 0,
        lastStaleAlertTime: null,

        // Current state
        currentState: 'initializing',  // 'connected', 'disconnected', 'reconnecting'
        initializingStartTime: Date.now(),  // Track when we entered initializing state
        connectionStartTime: null,
        totalConnectionTimeMs: 0,
        totalDisconnectedTimeMs: 0,

        // Network/visibility events correlation
        networkEvents: [],  // [{time, type, online}] - last 20
        visibilityEvents: [],  // [{time, hidden}] - last 20
        lastNetworkOnline: navigator.onLine,
        lastVisibilityHidden: document.hidden,
    };

    /**
     * Report a health alert to Android bridge immediately
     */
    function reportHealthAlert(alertType, data) {
        if (window.DashieBridge && typeof window.DashieBridge.onWsHealthAlert === 'function') {
            try {
                var payload = {
                    alertType: alertType,
                    timestamp: new Date().toISOString(),
                    data: data,
                    health: getHealthSnapshot()
                };
                window.DashieBridge.onWsHealthAlert(JSON.stringify(payload));
                console.log('[DashieLite] Health alert reported:', alertType);
            } catch (e) {
                console.error('[DashieLite] Failed to report health alert:', e);
            }
        } else {
            console.warn('[DashieLite] Health alert (no bridge):', alertType, data);
        }
    }

    /**
     * Get health metrics snapshot
     */
    function getHealthSnapshot() {
        var health = window._dashieWsHealth;
        var now = Date.now();

        // Calculate time since last message
        var timeSinceLastMessage = now - health.lastMessageTime;

        // Calculate current connection duration
        var currentConnectionDuration = 0;
        if (health.currentState === 'connected' && health.connectionStartTime) {
            currentConnectionDuration = now - health.connectionStartTime;
        }

        return {
            // Connection state
            currentState: health.currentState,
            disconnectCount: health.disconnectCount,
            reconnectCount: health.reconnectCount,

            // Timing
            lastDisconnectAgo: health.lastDisconnectTime ? Math.round((now - health.lastDisconnectTime) / 1000) + 's' : null,
            lastReconnectAgo: health.lastReconnectTime ? Math.round((now - health.lastReconnectTime) / 1000) + 's' : null,
            currentConnectionDurationMs: currentConnectionDuration,

            // Message flow
            lastMessageAgo: Math.round(timeSinceLastMessage / 1000) + 's',
            lastMessageAgoMs: timeSinceLastMessage,
            maxMessageGapMs: health.maxMessageGapMs,
            staleAlertCount: health.staleAlertCount,

            // Recent events (for correlation)
            recentDisconnects: health.disconnectEvents.slice(-5),
            recentGaps: health.messageGaps.slice(-5),
            recentNetworkEvents: health.networkEvents.slice(-5),
            recentVisibilityEvents: health.visibilityEvents.slice(-5),

            // Totals
            totalConnectionTimeMs: health.totalConnectionTimeMs + currentConnectionDuration,
            totalDisconnectedTimeMs: health.totalDisconnectedTimeMs,

            // Current environment
            networkOnline: navigator.onLine,
            documentHidden: document.hidden,
        };
    }

    // Expose health getter
    window.getDashieWsHealth = getHealthSnapshot;

    /**
     * Track message received - update lastMessageTime and detect gaps
     */
    function trackMessageReceived() {
        var health = window._dashieWsHealth;

        // Skip tracking if simulation has frozen updates (for testing auto-reload)
        if (health._simulationFrozen) {
            return;
        }

        var now = Date.now();
        var gap = now - health.lastMessageTime;

        // Check for significant gap (> 30 seconds)
        if (gap > 30000 && health.lastMessageTime > 0) {
            var gapEvent = {
                startTime: new Date(health.lastMessageTime).toISOString(),
                endTime: new Date(now).toISOString(),
                durationMs: gap,
                durationSec: Math.round(gap / 1000)
            };

            health.messageGaps.push(gapEvent);
            if (health.messageGaps.length > 10) {
                health.messageGaps.shift();  // Keep last 10
            }

            console.warn('[DashieLite] Message gap detected:', gapEvent.durationSec + 's');

            // Report if gap was very long (> 60s)
            if (gap > 60000) {
                reportHealthAlert('message_gap', gapEvent);
            }
        }

        // Update max gap
        if (gap > health.maxMessageGapMs) {
            health.maxMessageGapMs = gap;
        }

        health.lastMessageTime = now;
        health.currentGapStart = null;

        // Reset stale tracking flags if we were in a stale state
        // (resetStaleTracking is defined later but hoisted)
        if (typeof resetStaleTracking === 'function') {
            resetStaleTracking();
        }
    }

    /**
     * Track WebSocket disconnect
     */
    function trackDisconnect(code, reason, connectionDurationMs) {
        var health = window._dashieWsHealth;
        var now = Date.now();

        // Update connection time tracking
        if (health.currentState === 'connected' && health.connectionStartTime) {
            health.totalConnectionTimeMs += (now - health.connectionStartTime);
        }

        health.disconnectCount++;
        health.lastDisconnectTime = now;
        health.currentState = 'disconnected';
        health.connectionStartTime = null;

        var event = {
            time: new Date(now).toISOString(),
            code: code,
            reason: reason || '',
            connectionDurationMs: connectionDurationMs,
            connectionDurationSec: Math.round(connectionDurationMs / 1000)
        };

        health.disconnectEvents.push(event);
        if (health.disconnectEvents.length > 10) {
            health.disconnectEvents.shift();  // Keep last 10
        }

        console.warn('[DashieLite] WebSocket DISCONNECT:', event);

        // Always report disconnects
        reportHealthAlert('disconnect', event);
    }

    /**
     * Track WebSocket reconnect
     */
    function trackReconnect() {
        var health = window._dashieWsHealth;
        var now = Date.now();

        // Update disconnected time tracking
        if (health.currentState === 'disconnected' && health.lastDisconnectTime) {
            health.totalDisconnectedTimeMs += (now - health.lastDisconnectTime);
        }

        health.reconnectCount++;
        health.lastReconnectTime = now;
        health.currentState = 'connected';
        health.connectionStartTime = now;
        health.lastMessageTime = now;  // Reset message tracking
        health.initializingStartTime = null;  // Clear initializing tracking

        // Reset stuck-initializing flags and clear backoff state on successful connection
        var autoReload = window._dashieAutoReload;
        if (autoReload) {
            autoReload._initializingReported = false;
            autoReload._initializingReloadAttempted = false;
            autoReload._expiredTokenCleared = false;
            autoReload._maxRetriesLogged = false;
            autoReload._clearInitBackoffState();  // Clear exponential backoff on success
        }

        console.log('[DashieLite] WebSocket RECONNECTED (count:', health.reconnectCount + ')');

        // Report reconnect if we had a previous disconnect
        if (health.disconnectCount > 0) {
            reportHealthAlert('reconnect', {
                reconnectCount: health.reconnectCount,
                timeSinceDisconnectMs: health.lastDisconnectTime ? (now - health.lastDisconnectTime) : null
            });
        }

        // Signal to Kotlin that HA is back
        signalHaConnected();
    }

    /**
     * Track initial connection
     */
    function trackInitialConnect() {
        var health = window._dashieWsHealth;
        health.currentState = 'connected';
        health.connectionStartTime = Date.now();
        health.lastMessageTime = Date.now();
        health.initializingStartTime = null;  // Clear initializing tracking

        // Reset stuck-initializing flags and clear backoff state on successful connection
        var autoReload = window._dashieAutoReload;
        if (autoReload) {
            autoReload._initializingReported = false;
            autoReload._initializingReloadAttempted = false;
            autoReload._expiredTokenCleared = false;
            autoReload._maxRetriesLogged = false;
            autoReload._clearInitBackoffState();  // Clear exponential backoff on success
        }

        console.log('[DashieLite] WebSocket initial connection established');

        // Signal to Kotlin that HA is connected (in case we were in reconnect mode)
        signalHaConnected();
    }

    /**
     * Signal to Kotlin that HA has disconnected (likely reboot/shutdown).
     * Kotlin can use this to pause the WebView and start a ping loop.
     *
     * Close codes:
     * - 1000: Normal closure (HA shut down gracefully)
     * - 1001: Going away (server shutting down)
     * - 1006: Abnormal closure (network drop, no close frame)
     * - 4001: Our soft reconnect (intentional, don't trigger pause)
     */
    function signalHaDisconnect(code, reason) {
        // Don't signal for our own soft reconnect attempts
        if (code === 4001) {
            console.log('[DashieLite] Ignoring disconnect signal for soft reconnect');
            return;
        }

        console.log('[DashieLite] Signaling HA disconnect to Kotlin: code=' + code);

        if (window.DashieBridge && typeof window.DashieBridge.onHaDisconnect === 'function') {
            try {
                var data = {
                    code: code,
                    reason: reason || '',
                    timestamp: new Date().toISOString(),
                    wasConnectedFor: window._dashieWsHealth.connectionStartTime
                        ? Math.round((Date.now() - window._dashieWsHealth.connectionStartTime) / 1000)
                        : 0
                };
                window.DashieBridge.onHaDisconnect(JSON.stringify(data));
            } catch (e) {
                console.error('[DashieLite] Failed to signal HA disconnect:', e);
            }
        }
    }

    /**
     * Signal to Kotlin that HA is connected.
     * Kotlin can use this to resume normal operation after a reconnect.
     */
    function signalHaConnected() {
        console.log('[DashieLite] Signaling HA connected to Kotlin');

        if (window.DashieBridge && typeof window.DashieBridge.onHaConnected === 'function') {
            try {
                var data = {
                    timestamp: new Date().toISOString(),
                    reconnectCount: window._dashieWsHealth.reconnectCount
                };
                window.DashieBridge.onHaConnected(JSON.stringify(data));
            } catch (e) {
                console.error('[DashieLite] Failed to signal HA connected:', e);
            }
        }
    }

    /**
     * Signal to Kotlin that HA authentication failed.
     * This happens when the WebSocket connects but the token is rejected.
     * Kotlin should pause WebView, attempt token refresh, and reload if needed.
     *
     * Only fires ONCE per page lifecycle to prevent duplicate signals
     * (HA frontend may retry multiple times before Kotlin pauses JS).
     */
    var _authInvalidSignaled = false;

    function signalAuthInvalid(message) {
        if (_authInvalidSignaled) {
            console.warn('[DashieLite] auth_invalid already signaled this page load, ignoring duplicate');
            return;
        }
        _authInvalidSignaled = true;

        console.error('[DashieLite] Signaling auth invalid to Kotlin (will pause JS)');

        if (window.DashieBridge && typeof window.DashieBridge.onAuthInvalid === 'function') {
            try {
                var data = {
                    message: message,
                    timestamp: new Date().toISOString()
                };
                window.DashieBridge.onAuthInvalid(JSON.stringify(data));
            } catch (e) {
                console.error('[DashieLite] Failed to signal auth invalid:', e);
            }
        } else {
            console.warn('[DashieLite] No onAuthInvalid bridge - cannot signal auth failure');
        }
    }

    /**
     * Log auth flow events to Kotlin diagnostic buffer.
     * This helps debug authentication issues when "Send Diagnostics" is used.
     *
     * Events logged:
     * - auth_required: HA connected and asking for token
     * - auth_ok: Token accepted, fully authenticated
     * - auth_invalid: Token rejected (expired/revoked/wrong)
     */
    function logAuthEvent(eventType, data) {
        if (window.DashieBridge && typeof window.DashieBridge.onAuthEvent === 'function') {
            try {
                var payload = {
                    event: eventType,
                    timestamp: new Date().toISOString(),
                    data: data
                };
                window.DashieBridge.onAuthEvent(JSON.stringify(payload));
            } catch (e) {
                console.error('[DashieLite] Failed to log auth event:', e);
            }
        }
        // Also log to console for debugging
        console.log('[DashieLite] Auth event: ' + eventType, data);
    }

    /**
     * Setup network and visibility event listeners
     */
    function setupEnvironmentListeners() {
        var health = window._dashieWsHealth;

        // Network state changes
        window.addEventListener('online', function() {
            var event = { time: new Date().toISOString(), type: 'online', online: true };
            health.networkEvents.push(event);
            if (health.networkEvents.length > 20) health.networkEvents.shift();
            health.lastNetworkOnline = true;
            console.log('[DashieLite] Network: ONLINE');
        });

        window.addEventListener('offline', function() {
            var event = { time: new Date().toISOString(), type: 'offline', online: false };
            health.networkEvents.push(event);
            if (health.networkEvents.length > 20) health.networkEvents.shift();
            health.lastNetworkOnline = false;
            console.warn('[DashieLite] Network: OFFLINE');
            reportHealthAlert('network_offline', event);
        });

        // Visibility changes (app backgrounded/foregrounded)
        document.addEventListener('visibilitychange', function() {
            var hidden = document.hidden;
            var event = { time: new Date().toISOString(), hidden: hidden };
            health.visibilityEvents.push(event);
            if (health.visibilityEvents.length > 20) health.visibilityEvents.shift();
            health.lastVisibilityHidden = hidden;
            console.log('[DashieLite] Visibility:', hidden ? 'HIDDEN' : 'VISIBLE');

            if (hidden) {
                reportHealthAlert('visibility_hidden', event);
            }
        });

        console.log('[DashieLite] Environment listeners installed (network + visibility)');
    }

    // Setup environment listeners immediately
    setupEnvironmentListeners();

    /**
     * Periodic health check - detect stale connections
     */
    function startHealthMonitor() {
        setInterval(function() {
            var health = window._dashieWsHealth;
            var now = Date.now();
            var timeSinceLastMessage = now - health.lastMessageTime;

            // Check for stale connection (no messages for 60+ seconds)
            // HA sends pongs roughly every 30s, so 60s means we missed at least one
            if (timeSinceLastMessage > 60000 && health.currentState === 'connected') {
                health.staleAlertCount++;
                health.lastStaleAlertTime = now;

                var ws = window._dashieHaWebSocket;
                var wsState = ws ? ws.readyState : -1;
                var wsStateStr = ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'][wsState] || 'UNKNOWN';

                console.warn('[DashieLite] STALE CONNECTION: No messages for ' +
                    Math.round(timeSinceLastMessage / 1000) + 's, WS state: ' + wsStateStr);

                reportHealthAlert('stale_connection', {
                    timeSinceLastMessageMs: timeSinceLastMessage,
                    timeSinceLastMessageSec: Math.round(timeSinceLastMessage / 1000),
                    wsReadyState: wsState,
                    wsReadyStateStr: wsStateStr,
                    staleAlertCount: health.staleAlertCount
                });
            }

            // Log periodic health status (every 5 minutes if connected)
            var monitorDuration = now - window._dashieWsMetrics.startTime;
            if (monitorDuration > 0 && monitorDuration % 300000 < 30000) {  // Every ~5 min
                console.log('[DashieLite] Health check: state=' + health.currentState +
                    ', lastMsg=' + Math.round(timeSinceLastMessage / 1000) + 's ago' +
                    ', disconnects=' + health.disconnectCount +
                    ', reconnects=' + health.reconnectCount);
            }
        }, 30000);  // Check every 30 seconds

        console.log('[DashieLite] Health monitor started (30s interval)');
    }

    // Start health monitor
    startHealthMonitor();

    // ========================================================================
    // AUTO-RELOAD ON STALE CONNECTION
    // ========================================================================
    // When connection is stale for too long (no messages), automatically reload
    // the page to restore the WebSocket connection. This helps recover from
    // Android Doze mode killing the connection.

    window._dashieAutoReload = {
        enabled: true,                  // Feature enabled by default
        staleMinutes: 3,                // Trigger reload after N minutes of staleness (default 3)
        softReconnectSeconds: 90,       // Try soft reconnect (close WS) at 90 seconds
        softReconnectAttempted: false,  // Track if soft reconnect was already tried this cycle
        lastReloadAttempt: 0,           // In-memory fallback only — see _getLastReloadAttempt
        minReloadIntervalMs: 30000,     // Don't reload more than once per 30 seconds
        reloadScheduled: false,         // Flag to prevent multiple scheduled reloads
        warningShown: false,            // Track if warning was shown
        softReconnectEnabled: true,     // Soft reconnect is always enabled (independent of auto-reload)
        _initializingReported: false,   // Track if stuck-initializing warning was logged
        _initializingReloadAttempted: false, // Track if reload was attempted for stuck initializing

        // ========================================================================
        // STUCK-INITIALIZING BACKOFF SYSTEM
        // ========================================================================
        // When WS never connects, we reload to retry. But if HA is down, we don't
        // want to reload every 30s forever (wastes resources, eventually OOMs).
        // Use exponential backoff stored in sessionStorage to survive reloads.

        // Backoff settings
        // After just a few reload attempts, we hand off to Kotlin's lightweight
        // TCP ping loop. Reloading the full page is expensive (causes OOM if repeated).
        // Kotlin pings HA without loading the page, then triggers reload when HA responds.
        _initBackoffBaseMs: 30000,      // Start at 30s
        _initBackoffMaxMs: 120000,      // Max 2 minutes between retries
        _initBackoffMultiplier: 2,      // Double each time
        _initMaxRetries: 3,             // Only 3 reload attempts, then hand off to Kotlin ping loop

        // ── Min-interval throttle state (must survive reloads) ──────────────
        // The "don't reload more than once per 30s" throttle is the guard against
        // a reload storm, and a storm is expensive enough to OOM the app (see the
        // stuck-init note above). Keeping the timestamp only in memory made the
        // guard vacuous: `location.reload()` destroys `window._dashieAutoReload`,
        // so every reloaded page came back believing it had never reloaded. Same
        // reason the stuck-init backoff below uses sessionStorage — which does
        // survive a same-tab reload.

        _getLastReloadAttempt: function() {
            try {
                var stored = sessionStorage.getItem('dashie_last_reload_attempt');
                if (stored) return parseInt(stored, 10) || 0;
            } catch (e) {
                // sessionStorage unavailable — fall back to the in-memory value
            }
            return this.lastReloadAttempt;
        },

        _setLastReloadAttempt: function(ts) {
            this.lastReloadAttempt = ts;
            try {
                sessionStorage.setItem('dashie_last_reload_attempt', String(ts));
            } catch (e) {
                console.warn('[DashieLite] Failed to persist reload timestamp:', e);
            }
        },

        // Get backoff state from sessionStorage (survives reloads)
        _getInitBackoffState: function() {
            try {
                var stored = sessionStorage.getItem('dashie_init_backoff');
                if (stored) {
                    return JSON.parse(stored);
                }
            } catch (e) {
                console.warn('[DashieLite] Failed to read backoff state:', e);
            }
            return { attempts: 0, nextDelayMs: this._initBackoffBaseMs, lastAttemptTime: 0 };
        },

        // Save backoff state to sessionStorage
        _setInitBackoffState: function(state) {
            try {
                sessionStorage.setItem('dashie_init_backoff', JSON.stringify(state));
            } catch (e) {
                console.warn('[DashieLite] Failed to save backoff state:', e);
            }
        },

        // Clear backoff state (on successful connection)
        _clearInitBackoffState: function() {
            try {
                sessionStorage.removeItem('dashie_init_backoff');
                console.log('[DashieLite] Cleared stuck-init backoff state (connected successfully)');
            } catch (e) {
                // Ignore
            }
        },

        // Calculate if we should reload for stuck initializing, with backoff
        shouldReloadForStuckInit: function(initializingDurationMs) {
            var state = this._getInitBackoffState();

            // Check if we've exceeded max retries - hand off to Kotlin's lightweight ping loop
            if (state.attempts >= this._initMaxRetries) {
                // Only log/signal once per session
                if (!this._maxRetriesLogged) {
                    this._maxRetriesLogged = true;
                    console.warn('[DashieLite] 🔄 STUCK INIT: Stopping page reloads after ' + this._initMaxRetries +
                        ' attempts - handing off to Kotlin ping loop');

                    // Signal to Kotlin to take over with lightweight TCP pings
                    // Kotlin will reload the page when HA responds to ping
                    if (window.DashieBridge && typeof window.DashieBridge.onConnectionFailed === 'function') {
                        try {
                            window.DashieBridge.onConnectionFailed(JSON.stringify({
                                reason: 'stuck_initializing_handoff_to_ping',
                                attempts: state.attempts,
                                timestamp: new Date().toISOString()
                            }));
                        } catch (e) {
                            console.error('[DashieLite] Failed to signal handoff to ping loop:', e);
                        }
                    }
                }
                return false;  // Don't reload - let Kotlin ping loop handle recovery
            }

            // Check if enough time has passed based on backoff delay
            var requiredDelayMs = state.nextDelayMs || this._initBackoffBaseMs;
            if (initializingDurationMs < requiredDelayMs) {
                return false;
            }

            return true;
        },

        // Record that we're about to reload for stuck init, update backoff
        recordStuckInitReload: function() {
            var state = this._getInitBackoffState();
            state.attempts++;
            state.lastAttemptTime = Date.now();

            // Calculate next delay with exponential backoff
            var nextDelay = (state.nextDelayMs || this._initBackoffBaseMs) * this._initBackoffMultiplier;
            state.nextDelayMs = Math.min(nextDelay, this._initBackoffMaxMs);

            this._setInitBackoffState(state);

            console.log('[DashieLite] 📈 Stuck-init reload #' + state.attempts +
                ' - next retry in ' + Math.round(state.nextDelayMs / 1000) + 's');

            return state;
        },

        // Update settings from Android
        setStaleMinutes: function(minutes) {
            if (minutes === 0) {
                this.enabled = false;
                console.log('[DashieLite] Auto-reload: DISABLED (soft reconnect still active at 90s)');
            } else {
                this.enabled = true;
                this.staleMinutes = minutes;
                console.log('[DashieLite] Auto-reload: enabled at ' + minutes + ' minutes');
            }
        },

        // Check if we should auto-reload
        shouldReload: function() {
            if (!this.enabled) return false;

            var now = Date.now();

            // Don't reload if we already attempted recently
            var lastAttempt = this._getLastReloadAttempt();
            if (now - lastAttempt < this.minReloadIntervalMs) {
                console.log('[DashieLite] Auto-reload: too soon since last attempt (' +
                    Math.round((now - lastAttempt) / 1000) + 's ago)');
                return false;
            }

            // Don't reload if page is hidden (user not looking at it)
            if (document.hidden) {
                console.log('[DashieLite] Auto-reload: skipping, page is hidden');
                return false;
            }

            // Don't reload if network is offline
            if (!navigator.onLine) {
                console.log('[DashieLite] Auto-reload: skipping, network offline');
                return false;
            }

            return true;
        },

        // Perform the reload
        performReload: function() {
            if (!this.shouldReload()) return;

            this._setLastReloadAttempt(Date.now());
            console.log('[DashieLite] Auto-reload: RELOADING page due to stale connection');

            // Report this to telemetry before reloading
            if (window.DashieBridge && typeof window.DashieBridge.onWsHealthAlert === 'function') {
                try {
                    var payload = {
                        alertType: 'auto_reload',
                        timestamp: new Date().toISOString(),
                        data: {
                            staleMinutes: this.staleMinutes,
                            health: getHealthSnapshot()
                        }
                    };
                    window.DashieBridge.onWsHealthAlert(JSON.stringify(payload));
                } catch (e) {
                    console.error('[DashieLite] Failed to report auto-reload:', e);
                }
            }

            // Small delay to let the alert be sent
            setTimeout(function() {
                window.location.reload();
            }, 100);
        }
    };

    // Expose setter for Android to configure auto-reload timeout
    window.setAutoReloadStaleMinutes = function(minutes) {
        window._dashieAutoReload.setStaleMinutes(minutes);

        // Auto-reload requires ping keep-alive to work correctly.
        // Without pings, a quiet dashboard (no sensor updates) would appear stale
        // and trigger false positive reloads. Pings ensure we get regular pong
        // responses that reset the staleness timer.
        if (minutes > 0 && window._dashiePingKeepAlive) {
            if (!window._dashiePingKeepAlive.enabled) {
                console.log('[DashieLite] Auto-reload enabled - also enabling ping keep-alive');
                window._dashiePingKeepAlive.setEnabled(true);
                // Start pinging if we already have a WebSocket
                if (window._dashieHaWebSocket) {
                    window._dashiePingKeepAlive.start(window._dashieHaWebSocket);
                }
            }
        }
    };

    /**
     * Get HA token diagnostics for debugging auth issues.
     * Checks localStorage for hassTokens and reports state without exposing actual tokens.
     */
    function getTokenDiagnostics() {
        try {
            var hassTokensStr = localStorage.getItem('hassTokens');
            if (!hassTokensStr) {
                return { hasToken: false, reason: 'no_hassTokens_in_localStorage' };
            }

            var tokens = JSON.parse(hassTokensStr);
            if (!tokens || typeof tokens !== 'object') {
                return { hasToken: false, reason: 'hassTokens_invalid_json' };
            }

            // Check if we have an access token
            var hasAccessToken = !!(tokens.access_token || tokens.hassUrl);
            var hasRefreshToken = !!tokens.refresh_token;

            // Check expiry if available
            var expiresAt = tokens.expires_at || tokens.expires;
            var isExpired = false;
            var expiresInSec = null;

            if (expiresAt) {
                // HA stores 'expires' as milliseconds since epoch (Date.now() + expires_in * 1000)
                // But handle both ms and seconds: if < 1e12, it's seconds; if >= 1e12, it's ms
                var expiryTime;
                if (typeof expiresAt === 'number') {
                    expiryTime = expiresAt < 1e12 ? expiresAt * 1000 : expiresAt;
                } else {
                    expiryTime = new Date(expiresAt).getTime();
                }
                isExpired = Date.now() > expiryTime;
                expiresInSec = Math.round((expiryTime - Date.now()) / 1000);
            }

            return {
                hasToken: hasAccessToken,
                hasRefreshToken: hasRefreshToken,
                isExpired: isExpired,
                expiresInSec: expiresInSec,
                hassUrl: tokens.hassUrl ? 'present' : 'missing'
            };
        } catch (e) {
            return { hasToken: false, reason: 'error_reading_tokens', error: e.message };
        }
    }

    /**
     * Check if HA frontend element exists and has connection state.
     */
    function getHaFrontendState() {
        try {
            var haEl = document.querySelector('home-assistant');
            if (!haEl) {
                return { haElementExists: false };
            }

            var hass = haEl.hass;
            if (!hass) {
                return { haElementExists: true, hassReady: false };
            }

            return {
                haElementExists: true,
                hassReady: true,
                hasConnection: !!hass.connection,
                connectionState: hass.connection ? (hass.connection.connected ? 'connected' : 'disconnected') : 'none',
                haVersion: hass.config ? hass.config.version : 'unknown'
            };
        } catch (e) {
            return { haElementExists: 'error', error: e.message };
        }
    }

    /**
     * Report a stale connection incident to the diagnostics system.
     * This sends detailed data for the "Send Diagnostics" report.
     */
    function reportStaleIncident(incidentType, staleSec, additionalData) {
        var health = window._dashieWsHealth;
        var ping = window._dashiePingKeepAlive;
        var ws = window._dashieHaWebSocket;
        var wsState = ws ? ws.readyState : -1;
        var wsStateStr = ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'][wsState] || 'UNKNOWN';

        var incidentData = {
            incidentType: incidentType,
            staleSec: staleSec,
            wsReadyState: wsState,
            wsReadyStateStr: wsStateStr,
            pingEnabled: ping ? ping.enabled : false,
            pingsSent: ping ? ping.pingsSent : 0,
            lastPingAgo: ping && ping.lastPingTime ? Math.round((Date.now() - ping.lastPingTime) / 1000) : null,
            lastPongAgo: ping && ping.lastPongTime ? Math.round((Date.now() - ping.lastPongTime) / 1000) : null,
            disconnectCount: health.disconnectCount,
            reconnectCount: health.reconnectCount,
            networkOnline: navigator.onLine,
            documentHidden: document.hidden
        };

        // Merge any additional data
        if (additionalData) {
            for (var key in additionalData) {
                incidentData[key] = additionalData[key];
            }
        }

        // Report to Android bridge for DiagnosticBuffer
        if (window.DashieBridge && typeof window.DashieBridge.onWsHealthAlert === 'function') {
            try {
                var payload = {
                    alertType: 'stale_incident',
                    timestamp: new Date().toISOString(),
                    data: incidentData,
                    health: getHealthSnapshot()
                };
                window.DashieBridge.onWsHealthAlert(JSON.stringify(payload));
                console.log('[DashieLite] Stale incident reported:', incidentType, staleSec + 's');
            } catch (e) {
                console.error('[DashieLite] Failed to report stale incident:', e);
            }
        }
    }

    /**
     * Attempt soft reconnect by closing the WebSocket to trigger HA's reconnect.
     * This is less disruptive than a full page reload.
     */
    function attemptSoftReconnect() {
        var ws = window._dashieHaWebSocket;
        if (ws && ws.readyState === WebSocket.OPEN) {
            console.log('[DashieLite] Attempting soft reconnect (closing WS to trigger HA reconnect)');

            // Report this attempt
            reportHealthAlert('soft_reconnect_attempt', {
                staleSec: Math.round((Date.now() - window._dashieWsHealth.lastMessageTime) / 1000)
            });

            // Close with custom code to indicate intentional close
            ws.close(4001, 'Soft reconnect: stale connection');
            return true;
        }
        return false;
    }

    /**
     * Modified health monitor - implements 3-tier recovery:
     * 1. 60s stale: Log warning incident
     * 2. 90s stale: Attempt soft reconnect (close WS) - ALWAYS runs when ping keep-alive is enabled
     * 3. 3min stale: Full page reload (if soft reconnect didn't work) - only if auto-reload enabled
     */
    function checkStaleForAutoReload() {
        var health = window._dashieWsHealth;
        var autoReload = window._dashieAutoReload;
        var pingKeepAlive = window._dashiePingKeepAlive;

        // Soft reconnect requires ping keep-alive to detect stale connections accurately
        // Without pings, a quiet dashboard would appear stale (false positive)
        var canDetectStale = pingKeepAlive && pingKeepAlive.enabled;

        if (!canDetectStale) {
            // Log once as a warning if auto-reload is enabled but can't detect stale
            if (autoReload.enabled && !autoReload._pingWarningLogged) {
                console.warn('[DashieLite] Auto-reload enabled but ping keep-alive is disabled. ' +
                    'Soft reconnect and auto-reload require ping keep-alive to work.');
                autoReload._pingWarningLogged = true;
            }
            return;
        }

        var now = Date.now();
        var timeSinceLastMessage = now - health.lastMessageTime;
        var staleSec = Math.round(timeSinceLastMessage / 1000);
        var staleThresholdMs = autoReload.staleMinutes * 60 * 1000;
        var softReconnectThresholdMs = autoReload.softReconnectSeconds * 1000;

        // ========================================================================
        // STUCK INITIALIZING DETECTION (with exponential backoff)
        // ========================================================================
        // If WebSocket never connected (state stuck in 'initializing'), trigger recovery.
        // This handles the case where HA is unavailable on page load (reboot, reinstall, etc.)
        //
        // IMPORTANT: Uses exponential backoff to prevent rapid reload loops when HA is down.
        // Backoff state is stored in sessionStorage to persist across reloads.
        // After max retries, gives up and signals failure to Kotlin.
        //
        // IMPORTANT: If our WS proxy didn't intercept the WebSocket (e.g., proxy/Cloudflare
        // setup where pre-fetch fails), check HA's own frontend state. If hass.connection
        // reports connected, the dashboard is working fine — skip the reload.
        if (health.currentState === 'initializing' && health.initializingStartTime) {
            var haState = getHaFrontendState();
            if (haState.connectionState === 'connected') {
                // HA frontend is connected even though our proxy didn't catch the WebSocket.
                // Promote to connected state and skip reload.
                health.currentState = 'connected';
                health.lastMessageTime = now;
                console.log('[DashieLite] HA frontend reports connected — promoting from initializing (WS proxy missed interception)');
                // Clear any backoff state from previous attempts
                autoReload._clearInitBackoffState();
                // Signal to Kotlin that HA is connected
                if (window.DashieBridge && typeof window.DashieBridge.onHaConnected === 'function') {
                    try { window.DashieBridge.onHaConnected(JSON.stringify({reconnectCount: 0})); } catch (e) {}
                }
                // Fall through to normal stale-connection monitoring below
            }
        }
        if (health.currentState === 'initializing' && health.initializingStartTime) {
            var initializingDuration = now - health.initializingStartTime;
            var initializingSec = Math.round(initializingDuration / 1000);
            var backoffState = autoReload._getInitBackoffState();

            // Log warning after 30s (only once per page load)
            if (initializingDuration >= 30000 && !autoReload._initializingReported) {
                autoReload._initializingReported = true;

                // Gather detailed diagnostics to understand why WS isn't connecting
                var tokenDiag = getTokenDiagnostics();
                var haDiag = getHaFrontendState();

                console.warn('[DashieLite] ⚠️ STUCK INIT: WebSocket never connected after ' + initializingSec + 's' +
                    ' (attempt ' + (backoffState.attempts + 1) + '/' + autoReload._initMaxRetries + ')');
                console.warn('[DashieLite] Token state:', JSON.stringify(tokenDiag));
                console.warn('[DashieLite] HA frontend state:', JSON.stringify(haDiag));

                // If token is expired and we're stuck, clear it to force re-login
                // This handles the case where HA's frontend silently fails to refresh
                if (tokenDiag.isExpired && !autoReload._expiredTokenCleared) {
                    autoReload._expiredTokenCleared = true;
                    console.warn('[DashieLite] 🔑 Detected EXPIRED token while stuck - clearing to force re-auth');

                    // Signal to Kotlin to clear tokens
                    if (window.DashieBridge && typeof window.DashieBridge.onAuthInvalid === 'function') {
                        try {
                            window.DashieBridge.onAuthInvalid(JSON.stringify({
                                message: 'Token expired while stuck initializing',
                                expiresInSec: tokenDiag.expiresInSec
                            }));
                        } catch (e) {
                            console.error('[DashieLite] Failed to signal expired token:', e);
                        }
                    }
                    // onAuthInvalid will clear tokens and reload, so we can return
                    return;
                }

                reportStaleIncident('stuck_initializing', initializingSec, {
                    wsExists: !!window._dashieHaWebSocket,
                    wsReadyState: window._dashieHaWebSocket ? window._dashieHaWebSocket.readyState : -1,
                    backoffAttempts: backoffState.attempts,
                    backoffNextDelayMs: backoffState.nextDelayMs,
                    tokenDiagnostics: tokenDiag,
                    haFrontendState: haDiag
                });
            }

            // Check if we should reload (respects exponential backoff and max retries)
            if (autoReload.shouldReloadForStuckInit(initializingDuration) && !autoReload._initializingReloadAttempted) {
                autoReload._initializingReloadAttempted = true;

                // Record this attempt and calculate next backoff delay
                var newState = autoReload.recordStuckInitReload();

                console.warn('[DashieLite] ♻️ STUCK INIT: Triggering reload after ' + initializingSec + 's' +
                    ' (attempt ' + newState.attempts + '/' + autoReload._initMaxRetries +
                    ', next delay: ' + Math.round(newState.nextDelayMs / 1000) + 's)');

                reportStaleIncident('stuck_initializing_reload', initializingSec, {
                    backoffAttempt: newState.attempts,
                    backoffNextDelayMs: newState.nextDelayMs
                });

                autoReload.performReload();
                return;
            }
        }

        // Only act on stale connection if we think we're connected
        if (health.currentState !== 'connected') return;

        var ws = window._dashieHaWebSocket;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
            // No proxied socket to inspect. This is the Cloudflare / proxy setup
            // where WS interception fails (window._dashieHaWebSocket stays null,
            // state=UNKNOWN) and we were promoted to 'connected' off HA's own
            // frontend state. We can't read a readyState or close a socket here,
            // so HA's frontend connection is our only source of truth.
            //
            // Without this branch the function returned early forever: stale
            // warnings spammed every 30s but no tier ever fired, so an overnight
            // HA restart (e.g. a nightly VM reboot) left the dashboard stale until
            // a manual reload. Reported by a kiosk behind Cloudflare. (See
            // test-rig/js/ws-monitor-recovery.test.js.)
            var haState = getHaFrontendState();
            if (haState.connectionState === 'connected') {
                // Dashboard is genuinely live — we just never captured the socket.
                // Keep the staleness timer fresh so the health monitor stops
                // emitting false-positive stale warnings for a healthy connection.
                health.lastMessageTime = now;
                resetStaleTracking();
                return;
            }
            // HA frontend is NOT connected and we have no socket to soft-reconnect.
            // A full reload is the only recovery. Honor the same threshold/enable
            // gate as the normal Tier-3 path below.
            if (autoReload.enabled && timeSinceLastMessage > staleThresholdMs) {
                console.warn('[DashieLite] ♻️ STALE ' + autoReload.staleMinutes +
                    'min (no proxied socket, HA frontend ' + haState.connectionState +
                    '): triggering auto-reload after ' + staleSec + 's');
                reportStaleIncident('stale_no_socket_reload', staleSec, {
                    haFrontendState: haState
                });
                autoReload.performReload();
            }
            return;
        }

        // Tier 1: 60 second warning (report incident but take no action)
        if (staleSec >= 60 && !autoReload._60sReported) {
            autoReload._60sReported = true;
            console.warn('[DashieLite] ⚠️ STALE 60s: No messages for ' + staleSec + 's - monitoring...');
            reportStaleIncident('stale_60s_warning', staleSec);
        }

        // Tier 2: 90 second soft reconnect (close WS to trigger HA reconnect)
        // This ALWAYS runs when ping keep-alive is enabled, regardless of auto-reload setting
        if (timeSinceLastMessage >= softReconnectThresholdMs && !autoReload.softReconnectAttempted) {
            autoReload.softReconnectAttempted = true;
            console.warn('[DashieLite] 🔄 STALE 90s: Attempting soft reconnect after ' + staleSec + 's');
            reportStaleIncident('stale_90s_soft_reconnect', staleSec);
            attemptSoftReconnect();
            return;  // Give soft reconnect a chance before considering reload
        }

        // Tier 3: Full reload after staleMinutes (default 3 min)
        // Only runs if auto-reload is explicitly enabled
        if (autoReload.enabled && timeSinceLastMessage > staleThresholdMs) {
            console.warn('[DashieLite] ♻️ STALE ' + autoReload.staleMinutes + 'min: Triggering auto-reload after ' + staleSec + 's');
            reportStaleIncident('stale_3min_reload', staleSec, {
                softReconnectWasAttempted: autoReload.softReconnectAttempted
            });
            autoReload.performReload();
        }
    }

    /**
     * Reset stale tracking flags when connection is restored.
     * Called when we receive a message after being stale.
     */
    function resetStaleTracking() {
        var autoReload = window._dashieAutoReload;
        if (autoReload._60sReported || autoReload.softReconnectAttempted) {
            console.log('[DashieLite] ✓ Connection restored - resetting stale tracking');
            autoReload._60sReported = false;
            autoReload.softReconnectAttempted = false;
        }
    }

    // Start stale-reload check (runs every 30 seconds, same as health monitor)
    setInterval(checkStaleForAutoReload, 30000);

    // ========================================================================
    // WEBSOCKET PING KEEP-ALIVE
    // ========================================================================
    // Sends periodic ping messages to Home Assistant WebSocket to keep the
    // connection alive. This helps prevent Android from killing the connection
    // during Doze mode or when the screen is off.

    window._dashiePingKeepAlive = {
        enabled: false,
        intervalMs: 10000,  // Ping every 10 seconds for faster detection
        pingTimeoutMs: 5000, // Consider ping failed if no pong in 5 seconds
        intervalId: null,
        pingsSent: 0,
        lastPingTime: null,
        lastPongTime: null,
        pendingPing: false,  // Track if we're waiting for a pong
        consecutiveFailures: 0,  // Track consecutive ping failures
        degradedSignaled: false, // Have we already signaled degraded state?

        // Signal connection degraded to Kotlin (early warning before full disconnect)
        signalDegraded: function() {
            if (this.degradedSignaled) return;  // Only signal once per degradation
            this.degradedSignaled = true;

            console.warn('[DashieLite] 🔶 Connection degraded - pings failing');

            if (window.DashieBridge && typeof window.DashieBridge.onConnectionDegraded === 'function') {
                try {
                    var data = {
                        consecutiveFailures: this.consecutiveFailures,
                        lastPongAgo: this.lastPongTime ? Math.round((Date.now() - this.lastPongTime) / 1000) : null,
                        timestamp: new Date().toISOString()
                    };
                    window.DashieBridge.onConnectionDegraded(JSON.stringify(data));
                } catch (e) {
                    console.error('[DashieLite] Failed to signal connection degraded:', e);
                }
            }
        },

        // Reset degraded state when connection recovers
        resetDegradedState: function() {
            if (this.consecutiveFailures > 0 || this.degradedSignaled) {
                console.log('[DashieLite] ✓ Connection recovered after ' + this.consecutiveFailures + ' failed pings');
            }
            this.consecutiveFailures = 0;
            this.degradedSignaled = false;
        },

        // Wrap ping with timeout
        pingWithTimeout: function(connection) {
            var self = this;
            var pingStartTime = Date.now();

            return new Promise(function(resolve, reject) {
                var timeoutId = setTimeout(function() {
                    reject(new Error('Ping timeout after ' + self.pingTimeoutMs + 'ms'));
                }, self.pingTimeoutMs);

                connection.ping().then(function() {
                    clearTimeout(timeoutId);
                    resolve(Date.now() - pingStartTime);
                }).catch(function(err) {
                    clearTimeout(timeoutId);
                    reject(err);
                });
            });
        },

        // Start ping keep-alive for a WebSocket
        start: function(ws) {
            if (!this.enabled || this.intervalId) return;

            var self = this;
            console.log('[DashieLite] Starting ping keep-alive (interval: ' + this.intervalMs + 'ms, timeout: ' + this.pingTimeoutMs + 'ms)');

            this.intervalId = setInterval(function() {
                // Use HA's connection object to send pings - this uses HA's
                // commandId counter so we don't conflict with frontend messages
                var haEl = document.querySelector('home-assistant');
                if (haEl && haEl.hass && haEl.hass.connection) {
                    try {
                        self.pingsSent++;
                        self.lastPingTime = Date.now();
                        self.pendingPing = true;

                        // Use ping with timeout for faster failure detection
                        self.pingWithTimeout(haEl.hass.connection).then(function(latency) {
                            self.lastPongTime = Date.now();
                            self.pendingPing = false;
                            self.resetDegradedState();
                            console.log('[DashieLite] ✓ Pong #' + self.pingsSent + ' (latency: ' + latency + 'ms)');
                        }).catch(function(err) {
                            self.pendingPing = false;
                            self.consecutiveFailures++;
                            console.warn('[DashieLite] ⚠️ Ping #' + self.pingsSent + ' failed (' + self.consecutiveFailures + ' consecutive): ' + err.message);

                            // Signal degraded after 2 consecutive failures (~15-20 sec)
                            if (self.consecutiveFailures >= 2) {
                                self.signalDegraded();
                            }
                        });
                    } catch (e) {
                        self.pendingPing = false;
                        self.consecutiveFailures++;
                        console.warn('[DashieLite] Failed to send ping:', e);
                    }
                } else if (ws && ws.readyState === WebSocket.OPEN) {
                    // Fallback: try direct WebSocket ping (may not get response without ID)
                    console.log('[DashieLite] Skipping ping - HA connection object not available');
                } else {
                    console.log('[DashieLite] Skipping ping - no HA connection or WS not open');
                }
            }, this.intervalMs);
        },

        // Stop ping keep-alive
        stop: function() {
            if (this.intervalId) {
                clearInterval(this.intervalId);
                this.intervalId = null;
                console.log('[DashieLite] Stopped ping keep-alive');
            }
        },

        // Update settings
        setEnabled: function(enabled) {
            this.enabled = enabled;
            console.log('[DashieLite] Ping keep-alive: ' + (enabled ? 'ENABLED' : 'DISABLED'));
        },

        setInterval: function(ms) {
            this.intervalMs = ms;
            console.log('[DashieLite] Ping interval: ' + ms + 'ms');
        }
    };

    // Expose setters for Android to configure
    window.setPingKeepAliveEnabled = function(enabled) {
        window._dashiePingKeepAlive.setEnabled(enabled);
        // If enabled and we already have a WebSocket, start pinging
        if (enabled && window._dashieHaWebSocket) {
            window._dashiePingKeepAlive.start(window._dashieHaWebSocket);
        } else if (!enabled) {
            window._dashiePingKeepAlive.stop();
        }
    };

    window.setPingKeepAliveInterval = function(ms) {
        window._dashiePingKeepAlive.setInterval(ms);
    };

    // Check if ping keep-alive is enabled via Android bridge
    function checkAndEnablePingKeepAlive() {
        if (window.DashieBridge && typeof window.DashieBridge.isWebsocketPingEnabled === 'function') {
            try {
                var enabled = window.DashieBridge.isWebsocketPingEnabled();
                window._dashiePingKeepAlive.setEnabled(enabled);

                if (typeof window.DashieBridge.getWebsocketPingIntervalMs === 'function') {
                    var intervalMs = window.DashieBridge.getWebsocketPingIntervalMs();
                    window._dashiePingKeepAlive.setInterval(intervalMs);
                }
            } catch (e) {
                console.warn('[DashieLite] Failed to check ping keep-alive setting:', e);
            }
        }
    }

    // Check settings when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', checkAndEnablePingKeepAlive);
    } else {
        checkAndEnablePingKeepAlive();
    }

    // ========================================================================
    // DEBUG SIMULATION FUNCTIONS
    // ========================================================================
    // Functions to simulate connection issues for testing auto-reload and
    // keep-alive features.

    /**
     * Simulate a stale connection by backdating lastMessageTime.
     * Use this to test auto-reload without waiting for actual staleness.
     * Also freezes message tracking so incoming messages don't reset the timer.
     * @param {number} minutes - How many minutes of staleness to simulate
     */
    window.simulateStaleConnection = function(minutes) {
        var health = window._dashieWsHealth;
        var oldTime = health.lastMessageTime;
        health.lastMessageTime = Date.now() - (minutes * 60 * 1000);

        // Freeze message tracking so incoming messages don't reset the timer
        health._simulationFrozen = true;

        console.log('[DashieLite] DEBUG: Simulated ' + minutes + ' minute stale connection');
        console.log('[DashieLite] DEBUG: lastMessageTime changed from ' +
            new Date(oldTime).toISOString() + ' to ' +
            new Date(health.lastMessageTime).toISOString());
        console.log('[DashieLite] DEBUG: Message tracking FROZEN - waiting for auto-reload check (runs every 30s)');

        // Report the simulated staleness
        reportHealthAlert('debug_stale_simulation', {
            simulatedMinutes: minutes,
            autoReloadEnabled: window._dashieAutoReload.enabled,
            autoReloadThreshold: window._dashieAutoReload.staleMinutes
        });

        // Also trigger an immediate check instead of waiting for the 30s interval
        console.log('[DashieLite] DEBUG: Triggering immediate stale check...');
        setTimeout(function() {
            checkStaleForAutoReload();
        }, 100);

        return 'Simulated ' + minutes + ' minutes of staleness. Message tracking frozen. ' +
            (window._dashieAutoReload.enabled ?
                'Auto-reload should trigger shortly (threshold: ' + window._dashieAutoReload.staleMinutes + ' min).' :
                'Auto-reload is disabled.');
    };

    /**
     * Force a WebSocket close to test reconnection behavior.
     * WARNING: This will actually close the connection!
     */
    window.forceWebSocketClose = function() {
        var ws = window._dashieHaWebSocket;
        if (ws && ws.readyState === WebSocket.OPEN) {
            console.log('[DashieLite] DEBUG: Force-closing WebSocket');
            ws.close(4000, 'Debug: forced close for testing');
            return 'WebSocket closed. HA should reconnect automatically.';
        } else {
            return 'No open WebSocket to close.';
        }
    };

    /**
     * Get current connection health status as a string.
     */
    window.getConnectionStatus = function() {
        var health = window._dashieWsHealth;
        var ping = window._dashiePingKeepAlive;
        var now = Date.now();
        var timeSinceLastMessage = now - health.lastMessageTime;
        var timeSinceLastPong = ping.lastPongTime ? (now - ping.lastPongTime) : null;

        return JSON.stringify({
            state: health.currentState,
            lastMessageAgo: Math.round(timeSinceLastMessage / 1000) + 's',
            disconnects: health.disconnectCount,
            reconnects: health.reconnectCount,
            staleAlerts: health.staleAlertCount,
            pingEnabled: ping.enabled,
            pingsSent: ping.pingsSent,
            lastPongAgo: timeSinceLastPong ? Math.round(timeSinceLastPong / 1000) + 's' : 'never',
            autoReloadEnabled: window._dashieAutoReload.enabled,
            autoReloadMinutes: window._dashieAutoReload.staleMinutes
        }, null, 2);
    };

    /**
     * Force an auto-reload immediately for testing purposes.
     * Bypasses all staleness checks - just triggers the reload.
     */
    window.forceAutoReload = function() {
        console.log('[DashieLite] DEBUG: Force auto-reload triggered');

        // Report this to telemetry before reloading
        if (window.DashieBridge && typeof window.DashieBridge.onWsHealthAlert === 'function') {
            try {
                var payload = {
                    alertType: 'debug_force_reload',
                    timestamp: new Date().toISOString(),
                    data: {
                        reason: 'Manual debug trigger',
                        health: getHealthSnapshot()
                    }
                };
                window.DashieBridge.onWsHealthAlert(JSON.stringify(payload));
            } catch (e) {
                console.error('[DashieLite] Failed to report force reload:', e);
            }
        }

        // Small delay to let the alert be sent
        setTimeout(function() {
            window.location.reload();
        }, 100);

        return 'Reloading page in 100ms...';
    };

    /**
     * Simulate a stuck-initializing state for testing the backoff logic.
     * This forces the state to 'initializing' without actually needing
     * network-level WebSocket failure.
     *
     * @param {Object} options - Configuration options
     * @param {number} options.durationMs - How long (in ms) to simulate being stuck (default: 35000)
     * @param {number} options.attempts - Override backoff attempt count (default: use current)
     * @param {boolean} options.clearBackoff - Reset backoff state before simulating
     */
    window.simulateStuckInitializing = function(options) {
        options = options || {};
        var durationMs = options.durationMs || 35000; // Default 35s (enough to trigger 30s threshold)
        var health = window._dashieWsHealth;
        var autoReload = window._dashieAutoReload;

        // Optionally clear backoff state first
        if (options.clearBackoff) {
            try {
                sessionStorage.removeItem('dashie_init_backoff');
                console.log('[DashieLite] DEBUG: Cleared backoff state from sessionStorage');
            } catch (e) {
                console.warn('[DashieLite] DEBUG: Failed to clear backoff:', e);
            }
        }

        // Optionally set a specific attempt count
        if (typeof options.attempts === 'number') {
            try {
                var state = {
                    attempts: options.attempts,
                    nextDelayMs: autoReload._initBackoffBaseMs * Math.pow(autoReload._initBackoffMultiplier, options.attempts),
                    lastAttemptTime: Date.now()
                };
                sessionStorage.setItem('dashie_init_backoff', JSON.stringify(state));
                console.log('[DashieLite] DEBUG: Set backoff attempts to ' + options.attempts);
            } catch (e) {
                console.warn('[DashieLite] DEBUG: Failed to set backoff attempts:', e);
            }
        }

        // Read current backoff state for display
        var backoffState = autoReload._getInitBackoffState();

        // Store previous state for display
        var prevState = health.currentState;

        // Force to initializing state
        health.currentState = 'initializing';
        health.initializingStartTime = Date.now() - durationMs;

        // Freeze to prevent incoming messages from changing state
        health._simulationFrozen = true;

        console.log('[DashieLite] DEBUG: ━━━ SIMULATING STUCK INITIALIZING ━━━');
        console.log('[DashieLite] DEBUG: Previous state: ' + prevState);
        console.log('[DashieLite] DEBUG: Simulated stuck duration: ' + Math.round(durationMs / 1000) + 's');
        console.log('[DashieLite] DEBUG: Current backoff state:');
        console.log('  - Attempts: ' + backoffState.attempts + ' / ' + autoReload._initMaxRetries);
        console.log('  - Next delay: ' + Math.round(backoffState.nextDelayMs / 1000) + 's');
        console.log('[DashieLite] DEBUG: Checking if reload should happen...');

        // Check the backoff logic
        var shouldReload = autoReload.shouldReloadForStuckInit(durationMs);

        if (shouldReload) {
            console.log('[DashieLite] DEBUG: ✓ Reload WILL be triggered');
            console.log('[DashieLite] DEBUG: Triggering checkStaleForAutoReload in 500ms...');
            setTimeout(function() {
                checkStaleForAutoReload();
            }, 500);
        } else if (backoffState.attempts >= autoReload._initMaxRetries) {
            console.log('[DashieLite] DEBUG: ✗ Max retries reached (' + autoReload._initMaxRetries + ')');
            console.log('[DashieLite] DEBUG: Would hand off to Kotlin ping loop');
            // Unfreeze after 5 seconds so messages resume tracking (no reload happening)
            setTimeout(function() {
                health._simulationFrozen = false;
                health.currentState = prevState;
                health.lastMessageTime = Date.now();
                console.log('[DashieLite] DEBUG: Simulation ended - unfrozen, restored state to ' + prevState);
            }, 5000);
        } else {
            console.log('[DashieLite] DEBUG: ✗ Not enough time elapsed yet');
            console.log('[DashieLite] DEBUG: Need ' + Math.round(backoffState.nextDelayMs / 1000) + 's, have ' + Math.round(durationMs / 1000) + 's');
            // Unfreeze immediately since nothing is happening
            setTimeout(function() {
                health._simulationFrozen = false;
                health.currentState = prevState;
                health.lastMessageTime = Date.now();
                console.log('[DashieLite] DEBUG: Simulation ended - unfrozen, restored state to ' + prevState);
            }, 1000);
        }

        return {
            previousState: prevState,
            simulatedDuration: durationMs,
            backoffAttempts: backoffState.attempts,
            maxRetries: autoReload._initMaxRetries,
            nextDelayMs: backoffState.nextDelayMs,
            shouldReload: shouldReload,
            message: shouldReload
                ? 'Reload will trigger shortly'
                : (backoffState.attempts >= autoReload._initMaxRetries
                    ? 'Max retries reached - would hand off to Kotlin'
                    : 'Not enough time elapsed for reload')
        };
    };

    /**
     * Reset simulation state - call this if the simulation left things frozen.
     * This restores normal message tracking and connection state.
     */
    window.resetSimulation = function() {
        var health = window._dashieWsHealth;
        health._simulationFrozen = false;
        health.currentState = 'connected';
        health.lastMessageTime = Date.now();
        health.initializingStartTime = null;
        console.log('[DashieLite] DEBUG: Simulation state reset - unfrozen, state=connected');
        return 'Simulation reset. Connection tracking restored.';
    };

    /**
     * Clear the stuck-initializing backoff state (for testing reset behavior).
     */
    window.clearStuckInitBackoff = function() {
        try {
            sessionStorage.removeItem('dashie_init_backoff');
            // Also reset simulation state to be safe
            window._dashieWsHealth._simulationFrozen = false;
            console.log('[DashieLite] DEBUG: Cleared stuck-init backoff state');
            return 'Backoff state cleared. Next stuck-init will start fresh.';
        } catch (e) {
            console.warn('[DashieLite] DEBUG: Failed to clear backoff:', e);
            return 'Failed to clear: ' + e.message;
        }
    };

    /**
     * Get current backoff state for debugging.
     */
    window.getStuckInitBackoff = function() {
        var autoReload = window._dashieAutoReload;
        var state = autoReload._getInitBackoffState();
        return {
            attempts: state.attempts,
            maxRetries: autoReload._initMaxRetries,
            nextDelayMs: state.nextDelayMs,
            nextDelaySeconds: Math.round(state.nextDelayMs / 1000),
            lastAttemptTime: state.lastAttemptTime ? new Date(state.lastAttemptTime).toISOString() : null,
            willReloadNext: state.attempts < autoReload._initMaxRetries,
            message: state.attempts >= autoReload._initMaxRetries
                ? 'Max retries reached - would hand off to Kotlin ping loop'
                : 'Will retry after ' + Math.round(state.nextDelayMs / 1000) + 's of being stuck'
        };
    };

    console.log('[DashieLite] Debug functions available: simulateStaleConnection(minutes), forceWebSocketClose(), forceAutoReload(), getConnectionStatus(), simulateStuckInitializing(options), clearStuckInitBackoff(), getStuckInitBackoff(), resetSimulation()');

    // ========================================================================
    // INTERACTION PRIORITY MODE
    // ========================================================================
    // When user touches the screen, temporarily PAUSE/QUEUE all updates to give
    // the UI thread priority for handling the user's action.
    //
    // This works in TWO ways:
    // 1. WebSocket state_changed messages are queued and replayed after interaction
    // 2. A global flag (window._dashieInteractionActive) is exposed for timer-based
    //    updates to check and skip their updates during interaction
    //
    // For timer-based updates (setInterval, etc.), check window.shouldSkipDashieUpdate()
    // before performing UI updates. This function returns true during interactions.

    // Expose global flag for timer-based updates to check
    window._dashieInteractionActive = false;

    /**
     * Check if updates should be skipped due to user interaction.
     * Timer-based update functions should call this and skip their update if true.
     *
     * Usage example:
     *   setInterval(function() {
     *       if (window.shouldSkipDashieUpdate && window.shouldSkipDashieUpdate()) {
     *           return; // Skip this update cycle
     *       }
     *       // ... do your UI update
     *   }, 1000);
     *
     * @param {boolean} track - If true, tracks the skipped update for logging
     * @returns {boolean} true if updates should be skipped
     */
    window.shouldSkipDashieUpdate = function(track) {
        var shouldSkip = window._dashieInteractionActive;
        if (shouldSkip && track && window._dashieInteractionPriority) {
            window._dashieInteractionPriority.trackSkippedTimerUpdate();
        }
        return shouldSkip;
    };

    window._dashieInteractionPriority = {
        enabled: false,         // Feature toggle - disabled by default, enabled via settings
        active: false,
        endTime: 0,
        maxDurationMs: 1500,    // Maximum time to queue (safety cap)
        minDurationMs: 150,     // Minimum time before checking if idle (let action start)
        queuedMessages: [],     // Store {event, handler, ws} for replay
        queuedCount: 0,         // Total queued during this interaction
        replayScheduled: false,
        idleCheckScheduled: false,
        skippedTimerUpdates: 0, // Track how many timer updates were skipped

        // Start priority mode on user interaction
        start: function() {
            if (!this.enabled) return;  // Feature disabled
            this.active = true;
            this.endTime = Date.now() + this.maxDurationMs;
            this.queuedCount = 0;
            this.idleCheckScheduled = false;

            // Set global flag for timer-based updates
            window._dashieInteractionActive = true;

            console.log('[DashieLite] Interaction priority: ACTIVE (pausing all updates)');

            // Schedule idle check - end priority as soon as browser is idle
            // This means we stop queueing once the user's action has been processed
            this.scheduleIdleCheck();
        },

        // Check if browser is idle (action handled) and end priority early
        scheduleIdleCheck: function() {
            if (this.idleCheckScheduled || !this.active) return;
            this.idleCheckScheduled = true;

            var self = this;
            var minEndTime = Date.now() + this.minDurationMs;

            var checkIdle = function() {
                if (!self.active) return;

                // Wait at least minDuration to let the action start processing
                if (Date.now() < minEndTime) {
                    setTimeout(checkIdle, 50);
                    return;
                }

                // Use requestIdleCallback to detect when browser is idle
                if (window.requestIdleCallback) {
                    requestIdleCallback(function(deadline) {
                        // If we have significant idle time, the action is done
                        if (deadline.timeRemaining() > 10 || deadline.didTimeout) {
                            console.log('[DashieLite] Browser idle detected - ending priority early');
                            self.endPriorityAndReplay();
                        } else if (self.active) {
                            // Still busy, check again
                            setTimeout(checkIdle, 50);
                        }
                    }, { timeout: 200 });
                } else {
                    // Fallback: just use minDuration + small buffer
                    setTimeout(function() {
                        if (self.active) {
                            self.endPriorityAndReplay();
                        }
                    }, 100);
                }
            };

            // Start checking after a brief delay
            setTimeout(checkIdle, 50);
        },

        // Queue a state_changed message for later replay
        // Returns true if message was queued (caller should NOT process it now)
        queueStateChange: function(event, handler, ws) {
            if (!this.active) return false;

            if (Date.now() > this.endTime) {
                // Priority window expired - process immediately and replay queue
                this.endPriorityAndReplay();
                return false;
            }

            // Queue this message for later
            this.queuedMessages.push({ event: event, handler: handler, ws: ws });
            this.queuedCount++;
            return true;  // Tell caller to skip processing
        },

        // End priority mode and replay queued messages
        endPriorityAndReplay: function() {
            if (!this.active) return;

            this.active = false;

            // Clear global flag to resume timer-based updates
            window._dashieInteractionActive = false;

            var queued = this.queuedMessages;
            this.queuedMessages = [];

            if (queued.length > 0 || this.skippedTimerUpdates > 0) {
                console.log('[DashieLite] Interaction priority: ENDED - replaying ' +
                    queued.length + ' queued WS updates, skipped ' + this.skippedTimerUpdates + ' timer updates');
                this.skippedTimerUpdates = 0;
                window._dashieWsMetrics.droppedDuringInteraction += this.queuedCount;

                // Replay queued messages with small delays to avoid blocking
                // Use requestIdleCallback if available, otherwise setTimeout
                var replayNext = function(index) {
                    if (index >= queued.length) return;

                    var msg = queued[index];
                    if (msg.handler) {
                        try {
                            msg.handler.call(msg.ws, msg.event);
                        } catch (e) {
                            console.warn('[DashieLite] Error replaying queued message:', e);
                        }
                    }

                    // Schedule next message with a small gap to keep UI responsive
                    if (window.requestIdleCallback) {
                        requestIdleCallback(function() { replayNext(index + 1); }, { timeout: 100 });
                    } else {
                        setTimeout(function() { replayNext(index + 1); }, 16);  // ~60fps
                    }
                };

                // Start replay after a short delay to let UI settle
                setTimeout(function() { replayNext(0); }, 50);
            } else {
                console.log('[DashieLite] Interaction priority: ENDED (no updates affected)');
            }
        },

        // Track when a timer-based update is skipped (for logging)
        trackSkippedTimerUpdate: function() {
            if (this.active) {
                this.skippedTimerUpdates++;
            }
        },

        // Schedule replay when max priority window ends (safety fallback)
        scheduleReplayCheck: function() {
            if (this.replayScheduled || !this.active) return;

            var self = this;
            this.replayScheduled = true;

            var checkAndReplay = function() {
                self.replayScheduled = false;
                if (self.active && Date.now() > self.endTime) {
                    console.log('[DashieLite] Max duration reached - ending priority');
                    self.endPriorityAndReplay();
                } else if (self.active) {
                    // Still in priority window, check again later
                    self.replayScheduled = true;
                    setTimeout(checkAndReplay, 100);
                }
            };

            var timeRemaining = Math.max(0, this.endTime - Date.now());
            setTimeout(checkAndReplay, timeRemaining + 50);
        }
    };

    // Listen for user interactions to trigger priority mode
    function setupInteractionListeners() {
        var interactionHandler = function(e) {
            // Only trigger on actual user touch/click, not synthetic events
            if (e.isTrusted) {
                window._dashieInteractionPriority.start();
                window._dashieInteractionPriority.scheduleReplayCheck();
            }
        };

        // D-pad/keyboard handler - trigger on navigation keys
        var keyHandler = function(e) {
            if (!e.isTrusted) return;

            // D-pad keys: arrows, Enter/Select, OK button
            var dpadKeys = [
                'ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight',
                'Enter', ' ',  // Space is also used for selection
                'Escape', 'Backspace'  // Back navigation
            ];

            // Also check for Android keycodes (numeric)
            var dpadCodes = [
                19, 20, 21, 22,  // DPAD_UP, DOWN, LEFT, RIGHT
                23, 66,          // DPAD_CENTER, ENTER
                4                // BACK
            ];

            if (dpadKeys.includes(e.key) || dpadCodes.includes(e.keyCode)) {
                window._dashieInteractionPriority.start();
                window._dashieInteractionPriority.scheduleReplayCheck();
            }
        };

        // Use capture phase to catch events before HA handles them
        document.addEventListener('touchstart', interactionHandler, { capture: true, passive: true });
        document.addEventListener('mousedown', interactionHandler, { capture: true, passive: true });
        document.addEventListener('pointerdown', interactionHandler, { capture: true, passive: true });
        document.addEventListener('keydown', keyHandler, { capture: true, passive: true });

        console.log('[DashieLite] Interaction priority listeners installed (touch + d-pad)');
    }

    // Check if interaction priority is enabled via Android bridge
    function checkAndEnableInteractionPriority() {
        if (window.DashieBridge && typeof window.DashieBridge.isInteractionPriorityEnabled === 'function') {
            try {
                var enabled = window.DashieBridge.isInteractionPriorityEnabled();
                window._dashieInteractionPriority.enabled = enabled;
                console.log('[DashieLite] Interaction priority mode: ' + (enabled ? 'ENABLED' : 'DISABLED'));
            } catch (e) {
                console.warn('[DashieLite] Failed to check interaction priority setting:', e);
            }
        }
    }

    // Install listeners when DOM is ready
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            setupInteractionListeners();
            checkAndEnableInteractionPriority();
        });
    } else {
        setupInteractionListeners();
        checkAndEnableInteractionPriority();
    }

    /**
     * Extract domain from entity_id (e.g., "sensor.temperature" -> "sensor")
     */
    function getDomain(entityId) {
        if (!entityId || typeof entityId !== 'string') return null;
        const dotIndex = entityId.indexOf('.');
        return dotIndex > 0 ? entityId.substring(0, dotIndex) : null;
    }

    /**
     * Calculate messages per second from recent messages
     */
    function calculateMessagesPerSecond() {
        const now = Date.now();
        const windowMs = 5000; // 5 second window

        // Clean old messages
        window._dashieWsMetrics.recentMessages =
            window._dashieWsMetrics.recentMessages.filter(t => now - t < windowMs);

        const count = window._dashieWsMetrics.recentMessages.length;
        return count / (windowMs / 1000);
    }

    /**
     * Process incoming WebSocket message
     */
    function processMessage(data) {
        const metrics = window._dashieWsMetrics;
        const now = Date.now();

        metrics.messagesReceived++;
        metrics.recentMessages.push(now);

        // Track message type
        if (data.type) {
            metrics.messageTypes[data.type] = (metrics.messageTypes[data.type] || 0) + 1;
        }

        // Track state changes specifically
        if (data.type === 'event' && data.event?.event_type === 'state_changed') {
            metrics.stateChanges++;

            const entityId = data.event.data?.entity_id;
            if (entityId) {
                // Track unique entities (just count, not the IDs themselves)
                metrics.uniqueEntitiesUpdated.add(entityId);

                // Track by domain
                const domain = getDomain(entityId);
                if (domain) {
                    metrics.entityDomainUpdates[domain] =
                        (metrics.entityDomainUpdates[domain] || 0) + 1;
                }
            }
        }

        // Update peak rate
        const currentRate = calculateMessagesPerSecond();
        if (currentRate > metrics.peakMessagesPerSecond) {
            metrics.peakMessagesPerSecond = currentRate;
        }
    }

    /**
     * Get current metrics snapshot for reporting
     */
    function getMetricsSnapshot() {
        const metrics = window._dashieWsMetrics;
        const now = Date.now();
        const elapsedSeconds = (now - metrics.startTime) / 1000;

        return {
            // Duration
            monitorDurationSeconds: Math.round(elapsedSeconds),

            // Message counts
            totalMessages: metrics.messagesReceived,
            totalStateChanges: metrics.stateChanges,

            // Rates
            avgMessagesPerSecond: elapsedSeconds > 0
                ? (metrics.messagesReceived / elapsedSeconds).toFixed(2)
                : 0,
            currentMessagesPerSecond: calculateMessagesPerSecond().toFixed(2),
            peakMessagesPerSecond: metrics.peakMessagesPerSecond.toFixed(2),

            // Entity metrics
            uniqueEntitiesUpdated: metrics.uniqueEntitiesUpdated.size,
            entityDomainUpdates: { ...metrics.entityDomainUpdates },

            // Message types
            messageTypes: { ...metrics.messageTypes },

            // Timestamp
            collectedAt: new Date().toISOString()
        };
    }

    /**
     * Report metrics to Android bridge
     */
    function reportMetrics() {
        const snapshot = getMetricsSnapshot();

        // Send to Android if bridge is available
        if (window.DashieBridge && typeof window.DashieBridge.onWsMetrics === 'function') {
            try {
                window.DashieBridge.onWsMetrics(JSON.stringify(snapshot));
                console.log('[DashieLite] WebSocket metrics reported');
            } catch (e) {
                console.error('[DashieLite] Failed to report WS metrics:', e);
            }
        } else {
            console.log('[DashieLite] WebSocket metrics (no bridge):', snapshot);
        }

        // Reset for next collection window (keep cumulative counts but reset window-specific)
        window._dashieWsMetrics.lastReportTime = Date.now();
    }

    // Proxy WebSocket to intercept HA connection
    window.WebSocket = new Proxy(OriginalWebSocket, {
        construct(target, args) {
            const ws = new target(...args);
            const url = args[0];

            // Only intercept HA websocket
            if (url && url.includes('/api/websocket')) {
                console.log('[DashieLite] Intercepting HA WebSocket:', url);

                // Track connection attempt for failed connection detection
                var connectionOpened = false;
                var failedConnectionSignaled = false;

                // Use the same attachment logic as for existing WebSockets
                // This ensures consistent filtering behavior
                ws.addEventListener('open', function() {
                    connectionOpened = true;
                    // Attach our filtering/monitoring after WebSocket is open
                    // but before HA sets up its handlers
                    attachToExistingWebSocket(ws, url);
                });

                // CRITICAL: Also listen for error/close BEFORE open
                // This catches failed connection attempts (server unreachable)
                ws.addEventListener('error', function(event) {
                    if (!connectionOpened && !failedConnectionSignaled) {
                        console.warn('[DashieLite] HA WebSocket connection error (server unreachable?)');
                        // Track this as a failed connection attempt
                        var health = window._dashieWsHealth;
                        if (health) {
                            health.disconnectCount++;
                            health.currentState = 'connection_failed';
                            health.lastDisconnectTime = Date.now();
                        }
                    }
                });

                ws.addEventListener('close', function(event) {
                    if (!connectionOpened && !failedConnectionSignaled) {
                        failedConnectionSignaled = true;
                        console.warn('[DashieLite] HA WebSocket connection failed (code: ' + event.code + ')');

                        // Signal disconnect to Kotlin for failed connections
                        // This enables smart reconnect even when WS never connected
                        signalHaDisconnect(event.code, 'Connection failed before open');

                        // Track health
                        var health = window._dashieWsHealth;
                        if (health) {
                            health.disconnectCount++;
                            health.currentState = 'disconnected';
                        }
                    }
                });
            }

            return ws;
        }
    });

    // Expose metrics getter for manual inspection
    window.getDashieWsMetrics = getMetricsSnapshot;

    /**
     * Attach monitoring AND message filtering to a WebSocket.
     * Used for WebSockets intercepted by the minimal proxy before this script loaded.
     *
     * KEY INSIGHT: To actually improve responsiveness, we need to prevent HA from
     * processing state_changed messages during user interactions. Just monitoring
     * doesn't help - we need to intercept BEFORE HA's handlers see the message.
     *
     * We do this by hijacking the onmessage property setter and wrapping the handler.
     */
    function attachToExistingWebSocket(ws, url) {
        if (!ws || !url || !url.includes('/api/websocket')) return;
        if (ws._dashieAttached) return;  // Don't attach twice
        ws._dashieAttached = true;

        console.log('[DashieLite] Attaching to existing HA WebSocket:', url);

        // Store reference for debugging
        window._dashieHaWebSocket = ws;

        // Store HA's original handler so we can wrap it
        var haOriginalHandler = null;

        // Intercept onmessage setter to wrap HA's handler
        Object.defineProperty(ws, 'onmessage', {
            configurable: true,
            enumerable: true,
            get: function() {
                return haOriginalHandler;
            },
            set: function(handler) {
                console.log('[DashieLite] Intercepting HA onmessage handler');
                haOriginalHandler = handler;

                // Don't set the real onmessage - we'll call handler manually from addEventListener
            }
        });

        // Use addEventListener for our interceptor (runs before onmessage would)
        ws.addEventListener('message', function(event) {
            try {
                var data = JSON.parse(event.data);

                // ================================================================
                // AUTH FLOW LOGGING - Log all auth messages to diagnostics
                // This helps debug "stuck on loading data" issues
                // ================================================================

                // auth_required: HA is asking for authentication (WebSocket just connected)
                if (data.type === 'auth_required') {
                    console.log('[DashieLite] 🔐 AUTH_REQUIRED: HA asking for authentication (version: ' +
                        (data.ha_version || 'unknown') + ')');
                    logAuthEvent('auth_required', {
                        ha_version: data.ha_version,
                        message: 'WebSocket connected, HA requesting authentication'
                    });
                }

                // auth_ok: Authentication successful!
                if (data.type === 'auth_ok') {
                    console.log('[DashieLite] ✅ AUTH_OK: Authentication successful (version: ' +
                        (data.ha_version || 'unknown') + ')');
                    logAuthEvent('auth_ok', {
                        ha_version: data.ha_version,
                        message: 'Token accepted, authenticated successfully'
                    });
                }

                // CRITICAL: Detect auth_invalid - token is bad, need to re-authenticate
                // This happens when:
                // 1. Token expired and refresh failed
                // 2. Token was revoked (password changed, session cleared)
                // 3. HA was restored from backup with different auth
                // When this happens, HA frontend shows "loading data" indefinitely
                if (data.type === 'auth_invalid') {
                    console.error('[DashieLite] ❌ AUTH_INVALID: ' + (data.message || 'Token rejected'));
                    logAuthEvent('auth_invalid', {
                        message: data.message || 'Token rejected',
                        reason: 'Token was rejected by Home Assistant - will clear and reload'
                    });
                    signalAuthInvalid(data.message || 'Token rejected');
                    // Still pass to HA handler so it can try its own recovery
                }

                // Track health - message received (updates lastMessageTime, detects gaps)
                trackMessageReceived();

                // Track pong messages for fallback ping mode (when HA connection not available)
                // Note: When using HA's connection.ping(), the promise handles pong tracking
                if (data.type === 'pong') {
                    var ping = window._dashiePingKeepAlive;
                    // Only log if we're in fallback mode (pendingPing would be false for HA pings)
                    if (!ping.pendingPing) {
                        ping.lastPongTime = Date.now();
                        console.log('[DashieLite] Pong received (fallback mode)');
                    }
                }

                // Always track metrics
                processMessage(data);

                // Check if we should queue state_changed during interaction priority
                if (data.type === 'event' && data.event?.event_type === 'state_changed') {
                    if (window._dashieInteractionPriority.queueStateChange(event, haOriginalHandler, ws)) {
                        // Message was queued for later replay - don't process now
                        // This keeps UI responsive during user interaction
                        return;
                    }
                }

                // Pass to HA's handler (if set)
                if (haOriginalHandler) {
                    haOriginalHandler.call(ws, event);
                }

            } catch (e) {
                // Not JSON or parse error - pass through anyway
                window._dashieWsMetrics.messagesReceived++;
                if (haOriginalHandler) {
                    haOriginalHandler.call(ws, event);
                }
            }
        });

        // Track outgoing messages - wrap the send method
        // Also gates auth messages: blocks expired tokens from reaching HA (prevents IP ban)
        var originalSend = ws.send.bind(ws);
        ws.send = function(data) {
            window._dashieWsMetrics.messagesSent++;

            // TOKEN EXPIRY GATE: Check if HA frontend is about to send an expired token
            // If so, block the send and signal Kotlin to refresh + reload instead.
            // This prevents the auth_invalid from ever reaching HA.
            try {
                if (typeof data === 'string') {
                    var parsed = JSON.parse(data);
                    if (parsed.type === 'auth' && parsed.access_token) {
                        var tokenDiag = getTokenDiagnostics();
                        if (tokenDiag.isExpired) {
                            console.warn('[DashieLite] 🚫 BLOCKED expired token from being sent to HA!' +
                                ' (expired ' + Math.abs(tokenDiag.expiresInSec) + 's ago)');
                            signalAuthInvalid('Token expired before send (blocked by gate)');
                            return;  // Don't send the expired token
                        }
                    }
                }
            } catch (e) {
                // Parse error - let it through, don't break normal sends
            }

            return originalSend(data);
        };

        // Report metrics periodically
        var reportInterval = setInterval(function() {
            if (ws.readyState === WebSocket.OPEN) {
                reportMetrics();
            }
        }, window._dashieWsMetrics.reportIntervalMs);

        // Track connection start time for duration calculation on disconnect
        var connectionStartTime = null;

        // Clean up on close
        ws.addEventListener('close', function(event) {
            var connectionDuration = connectionStartTime ? (Date.now() - connectionStartTime) : 0;

            // Track disconnect with code, reason, and duration
            trackDisconnect(event.code, event.reason, connectionDuration);

            // Stop ping keep-alive
            if (window._dashiePingKeepAlive) {
                window._dashiePingKeepAlive.stop();
            }

            console.log('[DashieLite] HA WebSocket closed (code: ' + event.code + ')');
            clearInterval(reportInterval);
            // Final report
            reportMetrics();

            // Check if this looks like HA shutdown/reboot (code 1000 = normal closure)
            // Signal to Kotlin to potentially pause WebView and start ping loop
            if (event.code === 1000 || event.code === 1001 || event.code === 1006) {
                signalHaDisconnect(event.code, event.reason);
            }
        });

        // If already open, set up timing now
        if (ws.readyState === WebSocket.OPEN) {
            console.log('[DashieLite] HA WebSocket already connected');
            connectionStartTime = Date.now();
            window._dashieWsMetrics.startTime = Date.now();

            // Determine if this is initial connect or reconnect
            var health = window._dashieWsHealth;
            if (health.disconnectCount > 0) {
                // This is a reconnection after a previous disconnect
                trackReconnect();
            } else {
                // Initial connection
                trackInitialConnect();
            }

            // Start ping keep-alive if enabled
            if (window._dashiePingKeepAlive && window._dashiePingKeepAlive.enabled) {
                window._dashiePingKeepAlive.start(ws);
            }

            // Report initial metrics after 15 seconds
            setTimeout(function() {
                if (ws.readyState === WebSocket.OPEN) {
                    console.log('[DashieLite] Reporting initial WS metrics (15s)');
                    reportMetrics();
                }
            }, 15000);
        } else {
            // Wait for open event
            ws.addEventListener('open', function() {
                connectionStartTime = Date.now();

                // Determine if this is initial connect or reconnect
                var health = window._dashieWsHealth;
                if (health.disconnectCount > 0) {
                    // This is a reconnection after a previous disconnect
                    trackReconnect();
                } else {
                    // Initial connection
                    trackInitialConnect();
                }

                console.log('[DashieLite] HA WebSocket connected');
                window._dashieWsMetrics.startTime = Date.now();
                window._dashieWsMetrics.messagesReceived = 0;
                window._dashieWsMetrics.messagesSent = 0;
                window._dashieWsMetrics.stateChanges = 0;
                window._dashieWsMetrics.entityDomainUpdates = {};
                window._dashieWsMetrics.uniqueEntitiesUpdated = new Set();
                window._dashieWsMetrics.messageTypes = {};
                window._dashieWsMetrics.recentMessages = [];
                window._dashieWsMetrics.peakMessagesPerSecond = 0;

                // Start ping keep-alive if enabled
                if (window._dashiePingKeepAlive && window._dashiePingKeepAlive.enabled) {
                    window._dashiePingKeepAlive.start(ws);
                }

                setTimeout(function() {
                    if (ws.readyState === WebSocket.OPEN) {
                        console.log('[DashieLite] Reporting initial WS metrics (15s)');
                        reportMetrics();
                    }
                }, 15000);
            });
        }
    }

    // Check if there are already-intercepted WebSockets from the minimal proxy
    // (_dws is the minified variable name from the minimal proxy)
    var interceptedWs = window._dws || window._dashieInterceptedWs || [];
    if (interceptedWs.length > 0) {
        console.log('[DashieLite] Found', interceptedWs.length, 'pre-intercepted WebSocket(s)');
        for (var i = 0; i < interceptedWs.length; i++) {
            var entry = interceptedWs[i];
            attachToExistingWebSocket(entry.ws, entry.url);
        }
    }

    console.log('[DashieLite] WebSocket Monitor installed');
})();
