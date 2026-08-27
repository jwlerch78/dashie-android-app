/**
 * Dashboard Telemetry Collector for Dashie Lite
 *
 * Analyzes Home Assistant dashboard structure and performance metrics
 * for beta testing. Only runs when user has opted in.
 *
 * Privacy: We only collect aggregate metrics (counts, types, timing).
 * We DO NOT collect entity names, states, URLs, or any personal data.
 *
 * Collection triggers:
 * - On page/view change (different dashboard path)
 * - Every 3 hours on the same page (to track memory/performance over time)
 */
(function() {
    'use strict';

    // Configuration
    const DEBUG = false;
    const MAX_WAIT_MS = 15000; // Max time to wait for HA to load
    const CHECK_INTERVAL_MS = 500;
    const COLLECTION_INTERVAL_MS = 3 * 60 * 60 * 1000; // 3 hours

    console.log('[DashieLite] Dashboard Telemetry script loaded');

    // Track pending collection to avoid duplicates
    // Using window property so it persists across script re-injections
    // Format: { path: string, startTime: number } or null
    // window._dashieTelemetryPending

    // ========================================================================
    // FPS MONITORING
    // ========================================================================
    // Start FPS monitoring immediately so we capture render performance
    // during the initial load and card rendering
    if (!window._dashieFpsMonitor) {
        window._dashieFpsMonitor = {
            frames: [],
            droppedFrames: 0,
            lastFrameTime: 0,
            running: false,
            startTimestamp: 0,
            // Target is 60fps = 16.67ms per frame
            // Consider a frame "dropped" if it takes > 33ms (less than 30fps)
            dropThresholdMs: 33,

            // Initial load tracking (first 5 seconds)
            initialLoadFrames: [],
            initialLoadDropped: 0,
            initialLoadComplete: false,
            initialLoadDurationMs: 5000,

            // Long task tracking
            longTasks: [],
            longTaskObserver: null,

            start: function() {
                if (this.running) return;
                this.running = true;
                this.frames = [];
                this.droppedFrames = 0;
                this.initialLoadFrames = [];
                this.initialLoadDropped = 0;
                this.initialLoadComplete = false;
                this.longTasks = [];
                this.startTimestamp = performance.now();
                this.lastFrameTime = this.startTimestamp;

                // Start Long Task Observer if available
                this.startLongTaskObserver();

                this.tick();
            },

            startLongTaskObserver: function() {
                var self = this;
                if (typeof PerformanceObserver !== 'undefined') {
                    try {
                        this.longTaskObserver = new PerformanceObserver(function(list) {
                            var entries = list.getEntries();
                            for (var i = 0; i < entries.length; i++) {
                                self.longTasks.push({
                                    duration: entries[i].duration,
                                    startTime: entries[i].startTime
                                });
                            }
                        });
                        this.longTaskObserver.observe({ entryTypes: ['longtask'] });
                    } catch (e) {
                        // Long task observation not supported
                    }
                }
            },

            tick: function() {
                if (!this.running) return;

                var now = performance.now();
                var delta = now - this.lastFrameTime;
                var elapsed = now - this.startTimestamp;

                // Record frame time
                this.frames.push(delta);

                // Track initial load separately (first 5 seconds)
                if (!this.initialLoadComplete) {
                    this.initialLoadFrames.push(delta);
                    if (delta > this.dropThresholdMs) {
                        this.initialLoadDropped += Math.floor(delta / 16.67) - 1;
                    }
                    if (elapsed >= this.initialLoadDurationMs) {
                        this.initialLoadComplete = true;
                    }
                }

                // Count dropped frames (frame took longer than threshold)
                if (delta > this.dropThresholdMs) {
                    // Estimate how many frames were dropped
                    // e.g., 50ms = ~3 frames at 60fps, so 2 dropped
                    this.droppedFrames += Math.floor(delta / 16.67) - 1;
                }

                this.lastFrameTime = now;

                // Keep only last 10 seconds of frames (at 60fps = 600 frames)
                if (this.frames.length > 600) {
                    this.frames = this.frames.slice(-600);
                }

                requestAnimationFrame(this.tick.bind(this));
            },

            stop: function() {
                this.running = false;
                if (this.longTaskObserver) {
                    this.longTaskObserver.disconnect();
                }
            },

            getMetrics: function() {
                if (this.frames.length < 10) {
                    return null; // Not enough data
                }

                // Calculate FPS from frame times
                var totalTime = this.frames.reduce(function(a, b) { return a + b; }, 0);
                var avgFrameTime = totalTime / this.frames.length;
                var avgFps = 1000 / avgFrameTime;

                // Calculate min/max FPS from individual frame times
                var minFrameTime = Math.min.apply(null, this.frames);
                var maxFrameTime = Math.max.apply(null, this.frames);
                var maxFps = 1000 / minFrameTime;
                var minFps = 1000 / maxFrameTime;

                // Calculate percentiles for better understanding of jank
                var sorted = this.frames.slice().sort(function(a, b) { return a - b; });
                var p50 = sorted[Math.floor(sorted.length * 0.5)];
                var p95 = sorted[Math.floor(sorted.length * 0.95)];
                var p99 = sorted[Math.floor(sorted.length * 0.99)];

                // Count frames below key FPS thresholds
                var below30fps = this.frames.filter(function(t) { return t > 33.33; }).length;
                var below15fps = this.frames.filter(function(t) { return t > 66.67; }).length;

                var metrics = {
                    totalFrames: this.frames.length,
                    droppedFrames: this.droppedFrames,
                    avgFps: Math.round(avgFps * 10) / 10,
                    minFps: Math.round(minFps * 10) / 10,
                    maxFps: Math.round(maxFps * 10) / 10,
                    avgFrameTimeMs: Math.round(avgFrameTime * 100) / 100,
                    p50FrameTimeMs: Math.round(p50 * 100) / 100,
                    p95FrameTimeMs: Math.round(p95 * 100) / 100,
                    p99FrameTimeMs: Math.round(p99 * 100) / 100,
                    framesBelow30fps: below30fps,
                    framesBelow15fps: below15fps,
                    jankPercentage: Math.round((below30fps / this.frames.length) * 1000) / 10
                };

                // Add initial load metrics (first 5 seconds)
                if (this.initialLoadFrames.length > 0) {
                    var initTotal = this.initialLoadFrames.reduce(function(a, b) { return a + b; }, 0);
                    var initAvgFrame = initTotal / this.initialLoadFrames.length;
                    metrics.initialLoadAvgFps = Math.round((1000 / initAvgFrame) * 10) / 10;
                    metrics.initialLoadDroppedFrames = this.initialLoadDropped;
                    metrics.initialLoadFrameCount = this.initialLoadFrames.length;
                }

                // Add long task metrics
                if (this.longTasks.length > 0) {
                    var longestTask = 0;
                    var totalBlocked = 0;
                    for (var i = 0; i < this.longTasks.length; i++) {
                        var dur = this.longTasks[i].duration;
                        if (dur > longestTask) longestTask = dur;
                        // Blocked time = time over 50ms threshold
                        totalBlocked += Math.max(0, dur - 50);
                    }
                    metrics.longTaskCount = this.longTasks.length;
                    metrics.longestTaskMs = Math.round(longestTask);
                    metrics.totalBlockedTimeMs = Math.round(totalBlocked);
                }

                // Add JS heap memory if available (Chrome/WebView)
                if (performance.memory) {
                    metrics.jsHeapUsedMB = Math.round(performance.memory.usedJSHeapSize / (1024 * 1024));
                    metrics.jsHeapTotalMB = Math.round(performance.memory.totalJSHeapSize / (1024 * 1024));
                    metrics.jsHeapLimitMB = Math.round(performance.memory.jsHeapSizeLimit / (1024 * 1024));
                }

                return metrics;
            }
        };

        // Start monitoring immediately
        window._dashieFpsMonitor.start();
        console.log('[DashieLite] FPS monitor started');
    }

    // ========================================================================
    // NAVIGATION WATCHER - Must be installed FIRST, before any early returns
    // ========================================================================
    // This handles SPA navigation (HA tab/view switches) that doesn't trigger
    // Android's onPageFinished
    if (!window._dashieTelemetryNavWatcher) {
        window._dashieTelemetryNavWatcher = true;

        let lastWatchedPath = window.location.pathname + window.location.hash;

        // Handler function for navigation events
        function handleNavigation(source) {
            const newPath = window.location.pathname + window.location.hash;
            if (newPath !== lastWatchedPath) {
                console.log('[DashieLite] Navigation detected (' + source + '):', lastWatchedPath, '->', newPath);
                lastWatchedPath = newPath;

                // Quick snapshot after 3s (cards rendered) so crash reports
                // reflect the new page even if full telemetry hasn't finished
                setTimeout(function() {
                    if ((window.location.pathname + window.location.hash) === newPath) {
                        persistQuickSnapshot();
                    }
                }, 3000);

                // Full telemetry collection (includes 16s WS metrics wait)
                setTimeout(function() {
                    window._dashieTelemetryLastPath = '';
                    runCollection();
                }, 500);
            }
        }

        // Use popstate for back/forward navigation
        window.addEventListener('popstate', function() {
            handleNavigation('popstate');
        });

        // Watch for programmatic navigation (pushState/replaceState)
        // HA uses history.pushState for view navigation
        const originalPushState = history.pushState;
        const originalReplaceState = history.replaceState;

        history.pushState = function() {
            originalPushState.apply(this, arguments);
            handleNavigation('pushState');
        };

        history.replaceState = function() {
            originalReplaceState.apply(this, arguments);
            handleNavigation('replaceState');
        };

        console.log('[DashieLite] SPA navigation watcher installed');
    }

    // ========================================================================
    // COLLECTION LOGIC
    // ========================================================================

    // Get current view identifier
    // HA uses pathname for dashboard navigation (e.g., /lovelace/0, /lovelace/1)
    function getCurrentPath() {
        return window.location.pathname + window.location.hash;
    }

    // Get HA root element
    function getHaRoot() {
        return document.querySelector('home-assistant');
    }

    // Get hass object
    function getHass() {
        const root = getHaRoot();
        return root ? root.hass : null;
    }

    // Count DOM nodes (indicator of dashboard complexity)
    function countDOMNodes() {
        return document.getElementsByTagName('*').length;
    }

    // Traverse shadow DOMs to find cards
    function findAllCards(root, results = { cards: [], cameras: [], customCards: [], allTags: [] }, depth = 0) {
        if (!root) return results;

        // Check current element
        const tagName = root.tagName?.toLowerCase() || '';

        // Debug: collect all custom element tags for analysis
        if (DEBUG && tagName.includes('-') && depth < 50) {
            if (!results.allTags.includes(tagName)) {
                results.allTags.push(tagName);
            }
        }

        // Standard HA cards
        if (tagName.startsWith('hui-') && tagName.endsWith('-card')) {
            // Skip 'hui-card' wrapper - it's a generic container, look inside for actual card
            if (tagName !== 'hui-card') {
                const cardType = tagName.replace('hui-', '').replace('-card', '');
                results.cards.push(cardType);

                // Track cameras specifically
                if (cardType === 'picture-entity' || cardType === 'picture-glance' ||
                    cardType === 'picture' || cardType === 'camera') {
                    // Check if it's actually a camera
                    const config = root._config;
                    if (config && (config.camera_image || config.entity?.startsWith('camera.'))) {
                        results.cameras.push(cardType);
                    }
                }
            }
        }

        // Also detect card features (hui-*-card-feature) - these indicate complex cards
        if (tagName.startsWith('hui-') && tagName.endsWith('-card-feature')) {
            const featureType = tagName.replace('hui-', '').replace('-card-feature', '');
            // Track as a feature, not a card
            if (!results.features) results.features = [];
            results.features.push(featureType);
        }

        // Custom cards (from HACS or manual install)
        // Match any custom element that looks like a card component
        if (tagName.includes('-') && !tagName.startsWith('hui-') &&
            !tagName.startsWith('ha-') && !tagName.startsWith('home-') &&
            !tagName.startsWith('paper-') && !tagName.startsWith('mwc-') &&
            !tagName.startsWith('iron-') && !tagName.startsWith('md-') &&
            !tagName.startsWith('state-') && !tagName.startsWith('partial-') &&
            !tagName.startsWith('wa-')) {
            // Check if it's a card or camera-related element
            const isCard = tagName.endsWith('-card') || tagName.includes('card');
            const isCamera = tagName.includes('webrtc') || tagName.includes('camera') ||
                            tagName.includes('frigate') || tagName.includes('stream') ||
                            tagName.includes('rtsp');

            if (isCard || isCamera) {
                const sanitized = sanitizeCustomCardName(tagName);
                if (sanitized) {
                    results.customCards.push(sanitized);

                    // Track cameras specifically
                    if (isCamera) {
                        results.cameras.push(sanitized);
                    }
                }
            }
        }

        // Traverse children
        if (root.children) {
            for (const child of root.children) {
                findAllCards(child, results, depth + 1);
            }
        }

        // Traverse shadow DOM
        if (root.shadowRoot) {
            findAllCards(root.shadowRoot, results, depth + 1);
        }

        return results;
    }

    // Sanitize custom card names - keep the actual tag name, just clean it up
    function sanitizeCustomCardName(tagName) {
        // Return the actual tag name (lowercase), which is already anonymized
        // Tag names like "webrtc-camera-card" tell us what card type is used
        // without revealing any personal info (entity names, URLs, etc.)
        return tagName.toLowerCase();
    }

    // Count entity subscriptions
    function countEntities(hass) {
        if (!hass || !hass.states) return 0;
        return Object.keys(hass.states).length;
    }

    // Detect number of views/tabs
    function countViews() {
        // Try to find tabs
        let tabs = document.querySelectorAll('ha-tabs paper-tab, ha-tab, .mdc-tab, paper-tab');
        if (tabs.length > 0) return tabs.length;

        // Try shadow DOM
        const haRoot = getHaRoot();
        if (haRoot && haRoot.shadowRoot) {
            const main = haRoot.shadowRoot.querySelector('home-assistant-main');
            if (main && main.shadowRoot) {
                const panel = main.shadowRoot.querySelector('ha-panel-lovelace');
                if (panel && panel.shadowRoot) {
                    const huiRoot = panel.shadowRoot.querySelector('hui-root');
                    if (huiRoot && huiRoot.shadowRoot) {
                        tabs = huiRoot.shadowRoot.querySelectorAll('paper-tab, ha-tab');
                        if (tabs.length > 0) return tabs.length;
                    }
                }
            }
        }

        return 1; // At least one view
    }

    // Get app feature state from Android (wake word, cameras, etc.)
    function getAppFeatureState() {
        if (window.DashieBridge && typeof window.DashieBridge.getFeatureState === 'function') {
            try {
                const stateJson = window.DashieBridge.getFeatureState();
                return JSON.parse(stateJson);
            } catch (e) {
                console.warn('[DashieLite] Failed to get feature state:', e);
            }
        }
        return null;
    }

    // Collect all telemetry
    function collectTelemetry(hass, startTime, hassAvailableTime) {
        const telemetry = {
            // Timing metrics
            loadTimeMs: hassAvailableTime ? (hassAvailableTime - startTime) : null,
            totalTimeMs: Date.now() - startTime,

            // Dashboard structure
            viewCount: countViews(),
            entityCount: countEntities(hass),
            domNodeCount: countDOMNodes(),

            // Card analysis
            cards: {},
            cameraCount: 0,
            customCardCount: 0,
            customCards: {},

            // Collected at (ISO timestamp)
            collectedAt: new Date().toISOString(),
        };

        // Include app feature state (wake word detection, camera streaming, etc.)
        const featureState = getAppFeatureState();
        if (featureState) {
            telemetry.appFeatures = featureState;
        }

        // Include FPS metrics if available
        if (window._dashieFpsMonitor) {
            const fpsMetrics = window._dashieFpsMonitor.getMetrics();
            if (fpsMetrics) {
                telemetry.fpsMetrics = fpsMetrics;
            }
        }

        // Find all cards
        const haRoot = getHaRoot();
        const cardResults = findAllCards(haRoot);

        // Count card types
        const cardCounts = {};
        for (const card of cardResults.cards) {
            cardCounts[card] = (cardCounts[card] || 0) + 1;
        }
        telemetry.cards = cardCounts;
        telemetry.totalCardCount = cardResults.cards.length;

        // Camera count
        telemetry.cameraCount = cardResults.cameras.length;

        // Custom cards
        const customCounts = {};
        for (const card of cardResults.customCards) {
            customCounts[card] = (customCounts[card] || 0) + 1;
        }
        telemetry.customCards = customCounts;
        telemetry.customCardCount = cardResults.customCards.length;

        // Card features (like media player controls, light brightness sliders, etc.)
        if (cardResults.features && cardResults.features.length > 0) {
            const featureCounts = {};
            for (const feature of cardResults.features) {
                featureCounts[feature] = (featureCounts[feature] || 0) + 1;
            }
            telemetry.cardFeatures = featureCounts;
        }

        // Media elements - these are the primary memory consumers in WebView
        telemetry.mediaElements = {
            iframes: document.querySelectorAll('iframe').length,
            videos: document.querySelectorAll('video').length,
            canvases: document.querySelectorAll('canvas').length,
            images: document.querySelectorAll('img').length
        };

        if (DEBUG) {
            console.log('[DashieLite] Telemetry collected:', telemetry);
            console.log('[DashieLite] All custom element tags found:', cardResults.allTags.sort().join(', '));
        }

        return telemetry;
    }

    // Send telemetry to Android
    function sendToAndroid(telemetry) {
        // Check if Android bridge is available
        if (window.DashieBridge && typeof window.DashieBridge.onTelemetry === 'function') {
            try {
                const json = JSON.stringify(telemetry);
                window.DashieBridge.onTelemetry(json);
                console.log('[DashieLite] Telemetry sent to Android');
                return true;
            } catch (e) {
                console.error('[DashieLite] Failed to send telemetry:', e);
            }
        } else {
            // Fallback: post message (for debugging in browser)
            if (DEBUG) {
                console.log('[DashieLite] Telemetry (no Android bridge):', JSON.stringify(telemetry, null, 2));
            }
        }
        return false;
    }

    // Collect and persist a quick structural snapshot (no WS metrics wait).
    // Used on navigation so crash reports reflect the current page even if
    // the full 16s telemetry collection hasn't completed yet.
    function persistQuickSnapshot() {
        var hass = getHass();
        if (!hass || !hass.states || Object.keys(hass.states).length === 0) return;

        var telemetry = collectTelemetry(hass, Date.now(), null);
        sendToAndroid(telemetry);
        console.log('[DashieLite] Quick navigation snapshot persisted');
    }

    // Wait for HA to be ready, then execute callback
    function waitForHass(startTime, callback) {
        let hassAvailableTime = null;

        const checkReady = () => {
            const elapsed = Date.now() - startTime;

            if (elapsed > MAX_WAIT_MS) {
                console.log('[DashieLite] Telemetry: Timeout waiting for HA');
                callback(null, startTime, null);
                return;
            }

            const hass = getHass();
            if (hass && hass.states && Object.keys(hass.states).length > 0) {
                if (!hassAvailableTime) {
                    hassAvailableTime = Date.now();
                    // Fetch Lovelace config once when hass first becomes available
                    fetchLovelaceConfig();
                }
                callback(hass, startTime, hassAvailableTime);
                return;
            }

            setTimeout(checkReady, CHECK_INTERVAL_MS);
        };

        checkReady();
    }

    // Main collection flow - can be called multiple times
    function runCollection() {
        const currentPath = getCurrentPath();
        const lastCollectionTime = window._dashieTelemetryLastCollection || 0;
        const lastCollectionPath = window._dashieTelemetryLastPath || '';
        const timeSinceLastCollection = Date.now() - lastCollectionTime;
        const isNewPath = currentPath !== lastCollectionPath;

        // Check if there's already a pending collection for this path
        const pending = window._dashieTelemetryPending;
        if (pending && pending.path === currentPath) {
            const pendingAge = Date.now() - pending.startTime;
            // Allow new collection if pending is stale (>30 seconds old, meaning it failed)
            if (pendingAge < 30000) {
                console.log('[DashieLite] Telemetry: Collection already pending for ' + currentPath + ' (' + Math.round(pendingAge/1000) + 's ago)');
                return;
            }
        }

        // Only skip if same path AND within the hour
        if (!isNewPath && lastCollectionTime > 0 && timeSinceLastCollection < COLLECTION_INTERVAL_MS) {
            const minutesRemaining = Math.ceil((COLLECTION_INTERVAL_MS - timeSinceLastCollection) / 60000);
            console.log('[DashieLite] Telemetry: Same view, next collection in ' + minutesRemaining + ' minutes');
            return;
        }

        // Log why we're collecting
        if (isNewPath && lastCollectionPath) {
            console.log('[DashieLite] Dashboard Telemetry: View switch detected (' + lastCollectionPath + ' -> ' + currentPath + '), collecting...');
        } else if (isNewPath) {
            console.log('[DashieLite] Dashboard Telemetry: First collection for ' + currentPath + '...');
        } else {
            console.log('[DashieLite] Dashboard Telemetry: Hourly collection...');
        }

        // Mark collection as pending
        const startTime = Date.now();
        window._dashieTelemetryPending = { path: currentPath, startTime: startTime };

        waitForHass(startTime, (hass, st, hat) => {
            if (!hass) {
                console.log('[DashieLite] Telemetry: Could not get hass object');
                window._dashieTelemetryPending = null;
                return;
            }

            // Wait for cards to render AND for WebSocket metrics to be collected
            // WS monitor reports initial metrics at 15s, so we wait 16s to ensure they're available
            // This gives us ~15s of steady-state WebSocket data for accurate message rates
            setTimeout(() => {
                // Check if we're still on the same path (user might have navigated away)
                const nowPath = getCurrentPath();
                if (nowPath !== currentPath) {
                    console.log('[DashieLite] Telemetry: Path changed during collection (' + currentPath + ' -> ' + nowPath + '), skipping send');
                    window._dashieTelemetryPending = null;
                    return;
                }

                const telemetry = collectTelemetry(hass, st, hat);
                sendToAndroid(telemetry);
                window._dashieTelemetryLastCollection = Date.now();
                window._dashieTelemetryLastPath = nowPath;
                window._dashieTelemetryPending = null;
                console.log('[DashieLite] Telemetry collection complete for ' + nowPath);
            }, 16000); // Wait for cards + WS metrics (reported at 15s)
        });
    }

    // ========================================================================
    // LOVELACE CONFIG CAPTURE (one-time)
    // ========================================================================
    // Fetches the raw Lovelace dashboard config via HA's WebSocket API.
    // Captured once at load and persisted for crash reports and diagnostics.
    // The config reveals card types, entity bindings, and card_mod CSS —
    // critical for diagnosing memory issues on low-RAM devices.

    function fetchLovelaceConfig() {
        if (window._dashieLovelaceConfigFetched) return;
        window._dashieLovelaceConfigFetched = true;

        var hass = getHass();
        if (!hass || !hass.connection) {
            console.warn('[DashieLite] Cannot fetch Lovelace config: no hass connection');
            window._dashieLovelaceConfigFetched = false;
            return;
        }

        // Extract dashboard url_path from current URL
        // e.g. /dashboard-wallmovilvertical/HomeWallmovilvertical → "dashboard-wallmovilvertical"
        // /lovelace/0 → null (default dashboard)
        var pathParts = window.location.pathname.split('/').filter(function(p) { return p; });
        var urlPath = null;
        if (pathParts.length > 0 && pathParts[0] !== 'lovelace') {
            urlPath = pathParts[0];
        }

        var msg = { type: 'lovelace/config' };
        if (urlPath) {
            msg.url_path = urlPath;
        }

        console.log('[DashieLite] Fetching Lovelace config for dashboard:', urlPath || '(default)');

        hass.connection.sendMessagePromise(msg).then(function(config) {
            if (!config) {
                console.warn('[DashieLite] Lovelace config response was empty');
                return;
            }

            var configJson = JSON.stringify(config);
            console.log('[DashieLite] Lovelace config fetched: ' +
                Math.round(configJson.length / 1024) + 'KB, ' +
                (config.views ? config.views.length : 0) + ' views');

            // Send to Android for persistence
            if (window.DashieBridge && typeof window.DashieBridge.onLovelaceConfig === 'function') {
                try {
                    window.DashieBridge.onLovelaceConfig(configJson);
                    console.log('[DashieLite] Lovelace config sent to Android');
                } catch (e) {
                    console.error('[DashieLite] Failed to send Lovelace config:', e);
                }
            } else if (DEBUG) {
                console.log('[DashieLite] Lovelace config (no bridge):', configJson.substring(0, 500));
            }
        }).catch(function(err) {
            console.error('[DashieLite] Failed to fetch Lovelace config:', err);
            // Allow retry on next collection if it was a transient error
            window._dashieLovelaceConfigFetched = false;
        });
    }

    // Export for navigation watcher to call
    window.runCollection = runCollection;

    // ========================================================================
    // HOURLY COLLECTION SCHEDULER
    // ========================================================================
    // Schedule hourly collections even if user stays on the same page
    // This is important for tracking performance degradation over time
    if (!window._dashieTelemetryHourlyScheduler) {
        window._dashieTelemetryHourlyScheduler = true;

        function scheduleNextHourlyCollection() {
            // Check every minute if we need to collect
            // This is more efficient than setting a 1-hour timeout that might drift
            const CHECK_INTERVAL = 60 * 1000; // 1 minute

            setTimeout(function hourlyCheck() {
                const lastCollectionTime = window._dashieTelemetryLastCollection || 0;
                const timeSinceLastCollection = Date.now() - lastCollectionTime;

                // If an hour has passed and we're still on the same page, collect
                if (lastCollectionTime > 0 && timeSinceLastCollection >= COLLECTION_INTERVAL_MS) {
                    console.log('[DashieLite] Hourly telemetry collection triggered');
                    runCollection();
                }

                // Schedule next check
                setTimeout(hourlyCheck, CHECK_INTERVAL);
            }, CHECK_INTERVAL);
        }

        // Start the scheduler after initial collection completes
        // Wait 2 minutes to ensure first collection is done
        setTimeout(scheduleNextHourlyCollection, 2 * 60 * 1000);
        console.log('[DashieLite] Hourly telemetry scheduler initialized');
    }

    // Start collection when DOM is ready
    if (document.readyState === 'complete') {
        runCollection();
    } else {
        window.addEventListener('load', runCollection);
    }
})();
