/**
 * Dashie Camera Card v2.0 - Native RTSP via DashieNative bridge
 *
 * Uses DashieNative JS interface directly (no postMessage needed).
 * On Dashie Lite tablets: ExoPlayer overlay for hardware-accelerated RTSP.
 * On browsers: placeholder (WebRTC not yet implemented).
 *
 * Configuration:
 * ```yaml
 * type: custom:dashie-camera-card
 * entity: camera.front_door          # HA camera entity (required)
 * go2rtc_url: http://192.168.1.X:1984  # go2rtc base URL (required for now)
 * stream_name: front_door            # optional, derived from entity
 * title: Front Door                  # optional display name
 * aspect_ratio: 16:9                 # optional, default 16:9
 * rtsp_port: 8554                    # optional, default 8554
 * ```
 */

const CARD_VERSION = '2.0.0';
const TAG = '[DashieCameraCard]';

class DashieCameraCard extends HTMLElement {
    constructor() {
        super();
        this._config = null;
        this._hass = null;
        this._streamId = null;
        this._rtspUrl = null;
        this._isStreaming = false;
        this._container = null;
        this._statusEl = null;
        this._resizeObserver = null;
        this._scrollHandler = null;
        this._visibilityHandler = null;
        this._lastPosition = { x: 0, y: 0, width: 0, height: 0 };
        this._throttledUpdatePosition = null;
    }

    // --- HA Card Interface ---

    setConfig(config) {
        if (!config.entity) {
            throw new Error('You need to define an entity (e.g. camera.front_door)');
        }
        if (!config.go2rtc_url && !config.rtsp_url) {
            throw new Error('You need to define go2rtc_url (e.g. http://192.168.1.X:1984) or rtsp_url');
        }

        this._config = {
            entity: config.entity,
            go2rtc_url: config.go2rtc_url || null,
            rtsp_url: config.rtsp_url || null,
            stream_name: config.stream_name || null,
            title: config.title || null,
            aspect_ratio: config.aspect_ratio || '16:9',
            rtsp_port: config.rtsp_port || 8554,
            show_controls: config.show_controls !== false,
        };

        // Derive stream name from entity if not provided
        if (!this._config.stream_name) {
            this._config.stream_name = this._config.entity.replace('camera.', '');
        }

        // Build RTSP URL
        this._rtspUrl = this._buildRtspUrl();

        // Generate unique stream ID
        this._streamId = `dashie-cam-${this._config.stream_name}`;

        console.log(TAG, 'Config set:', {
            entity: this._config.entity,
            streamName: this._config.stream_name,
            rtspUrl: this._rtspUrl,
            streamId: this._streamId,
        });

        this._render();
    }

    set hass(hass) {
        this._hass = hass;
        if (this._config?.entity && this._statusEl) {
            const state = hass.states[this._config.entity];
            if (state) {
                this._statusEl.textContent = state.attributes.friendly_name || state.entity_id;
            }
        }
    }

    getCardSize() {
        return 4;
    }

    static getStubConfig() {
        return {
            entity: 'camera.front_door',
            go2rtc_url: 'http://192.168.1.100:1984',
        };
    }

    // --- Lifecycle ---

    connectedCallback() {
        // Delay start slightly to let HA finish layout
        setTimeout(() => this._tryStartStream(), 300);

        this._visibilityHandler = () => {
            if (document.hidden) {
                this._stopStream();
            } else {
                this._tryStartStream();
            }
        };
        document.addEventListener('visibilitychange', this._visibilityHandler);
    }

    disconnectedCallback() {
        this._stopStream();

        if (this._visibilityHandler) {
            document.removeEventListener('visibilitychange', this._visibilityHandler);
            this._visibilityHandler = null;
        }
        if (this._resizeObserver) {
            this._resizeObserver.disconnect();
            this._resizeObserver = null;
        }
        if (this._scrollHandler) {
            window.removeEventListener('scroll', this._scrollHandler, true);
            this._scrollHandler = null;
        }
    }

    // --- Stream Control ---

    _tryStartStream() {
        if (this._isStreaming || !this._rtspUrl) return;

        const bridge = this._getBridge();
        if (!bridge) {
            // Neutral wording for the same reason as the card-picker metadata below: this
            // status renders ON SCREEN, in whichever edition is running.
            console.warn(TAG, 'DROP: [expected] DashieNative bridge unavailable — ' +
                'not running inside a kiosk tablet WebView; cannot start native RTSP.');
            this._showStatus('Not on a supported tablet', '#ff9800');
            return;
        }

        if (!bridge.isNativeRtspSupported()) {
            console.warn(TAG, 'Native RTSP not supported (player manager not initialized)');
            this._showStatus('RTSP not available', '#f44336');
            return;
        }

        const pos = this._getPosition();
        if (!pos || pos.width === 0 || pos.height === 0) {
            console.warn(TAG, 'Element not visible yet, retrying in 500ms');
            setTimeout(() => this._tryStartStream(), 500);
            return;
        }

        console.log(TAG, `Starting stream: ${this._streamId}`, {
            url: this._rtspUrl,
            position: pos,
        });

        try {
            // Pass CSS pixels - the Kotlin bridge handles density conversion
            bridge.startRtspStream(
                this._streamId,
                this._rtspUrl,
                Math.round(pos.x),
                Math.round(pos.y),
                Math.round(pos.width),
                Math.round(pos.height)
            );
            this._isStreaming = true;
            this._lastPosition = pos;
            this._showStatus('RTSP', '#4CAF50');
            this._setupPositionTracking();
        } catch (e) {
            console.error(TAG, 'Failed to start stream:', e);
            this._showStatus('Error: ' + e.message, '#f44336');
        }
    }

    _stopStream() {
        if (!this._isStreaming) return;

        const bridge = this._getBridge();
        if (bridge) {
            try {
                bridge.stopRtspStream(this._streamId);
                console.log(TAG, `Stopped stream: ${this._streamId}`);
            } catch (e) {
                console.error(TAG, 'Failed to stop stream:', e);
            }
        }
        this._isStreaming = false;
    }

    // --- Position Tracking ---

    _setupPositionTracking() {
        if (this._resizeObserver) return; // Already set up

        this._throttledUpdatePosition = this._throttle(() => this._updatePosition(), 100);

        this._resizeObserver = new ResizeObserver(() => this._throttledUpdatePosition());
        this._resizeObserver.observe(this);

        this._scrollHandler = () => this._throttledUpdatePosition();
        window.addEventListener('scroll', this._scrollHandler, true);
    }

    _updatePosition() {
        if (!this._isStreaming) return;

        const pos = this._getPosition();
        if (!pos) return;

        // Skip if position hasn't changed meaningfully
        const t = 2;
        if (Math.abs(pos.x - this._lastPosition.x) < t &&
            Math.abs(pos.y - this._lastPosition.y) < t &&
            Math.abs(pos.width - this._lastPosition.width) < t &&
            Math.abs(pos.height - this._lastPosition.height) < t) {
            return;
        }

        const bridge = this._getBridge();
        if (bridge) {
            try {
                bridge.updateRtspStreamPosition(
                    this._streamId,
                    Math.round(pos.x),
                    Math.round(pos.y),
                    Math.round(pos.width),
                    Math.round(pos.height)
                );
                this._lastPosition = pos;
            } catch (e) {
                console.error(TAG, 'Failed to update position:', e);
            }
        }
    }

    _getPosition() {
        if (!this._container) return null;
        const rect = this._container.getBoundingClientRect();
        return { x: rect.left, y: rect.top, width: rect.width, height: rect.height };
    }

    // --- Helpers ---

    _getBridge() {
        return window.DashieNative || null;
    }

    _buildRtspUrl() {
        // If explicit rtsp_url provided, use it directly
        if (this._config.rtsp_url) {
            return this._config.rtsp_url;
        }

        // Derive from go2rtc_url
        if (this._config.go2rtc_url) {
            try {
                const url = new URL(this._config.go2rtc_url);
                const host = url.hostname;
                const port = this._config.rtsp_port;
                return `rtsp://${host}:${port}/${this._config.stream_name}`;
            } catch (e) {
                console.error(TAG, 'Invalid go2rtc_url:', this._config.go2rtc_url);
                return null;
            }
        }

        return null;
    }

    _showStatus(text, color) {
        const badge = this.querySelector('.stream-badge');
        if (badge) {
            badge.textContent = text;
            badge.style.color = color;
        }
    }

    _throttle(fn, wait) {
        let lastTime = 0;
        let timeoutId = null;
        return function (...args) {
            const now = Date.now();
            const remaining = wait - (now - lastTime);
            if (remaining <= 0) {
                if (timeoutId) { clearTimeout(timeoutId); timeoutId = null; }
                lastTime = now;
                fn.apply(this, args);
            } else if (!timeoutId) {
                timeoutId = setTimeout(() => {
                    lastTime = Date.now();
                    timeoutId = null;
                    fn.apply(this, args);
                }, remaining);
            }
        };
    }

    // --- Rendering ---

    _render() {
        const [w, h] = this._config.aspect_ratio.split(':').map(Number);
        const aspectPercent = (h / w) * 100;
        const title = this._config.title || this._config.stream_name;

        this.innerHTML = `
            <ha-card>
                <div style="
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    padding: 12px 16px 8px;
                ">
                    <span style="font-weight: 500; font-size: 14px;">
                        ${title}
                    </span>
                    <span class="stream-badge" style="
                        font-size: 11px;
                        padding: 2px 8px;
                        border-radius: 4px;
                        background: rgba(0,0,0,0.15);
                        color: #888;
                    ">Connecting...</span>
                </div>
                <div class="camera-container" style="
                    position: relative;
                    width: 100%;
                    padding-top: ${aspectPercent}%;
                    background: #000;
                    overflow: hidden;
                ">
                    <div style="
                        position: absolute;
                        top: 0; left: 0; width: 100%; height: 100%;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: #444;
                        font-size: 13px;
                    ">
                        <div style="text-align: center;">
                            <div style="
                                width: 24px; height: 24px;
                                border: 2px solid #333;
                                border-top-color: #888;
                                border-radius: 50%;
                                animation: dashie-cam-spin 1s linear infinite;
                                margin: 0 auto 8px;
                            "></div>
                            <div>${this._config.stream_name}</div>
                        </div>
                    </div>
                </div>
            </ha-card>
            <style>
                @keyframes dashie-cam-spin {
                    to { transform: rotate(360deg); }
                }
            </style>
        `;

        this._container = this.querySelector('.camera-container');
        this._statusEl = this.querySelector('.camera-name');
    }
}

// Register
customElements.define('dashie-camera-card', DashieCameraCard);

/**
 * Card-picker metadata — deliberately BRAND-NEUTRAL in both editions.
 *
 * ⚠️ `type` is a WIRE VALUE and must never change: it is what users write in their Lovelace
 * YAML (`type: custom:dashie-camera-card`), so renaming it breaks every existing dashboard.
 * Only the human-readable `name`/`description` are branded, and those are what get fixed here.
 *
 * ## Why neutral rather than resolved per edition
 *
 * The obvious fix is `DashieNative.getEdition()` → "Dashie Camera" / "Chickadee Camera". Three
 * things rule that out, and the third is decisive:
 *
 *  - It needs an edition→display-name table in THIS file, which cannot import `brand.js`: the
 *    card is deployed by COPYING one file into HA's `/config/www/`, so a sibling `./brand.js`
 *    resolves to `/local/brand.js` and 404s. That makes it a bare hand-mirror of the brand
 *    table — the thing standing rule 1 forbids — for one card label.
 *  - Deriving the name from the edition id instead (capitalise `chickadee`) fails quiet the
 *    first time a brand's display name differs from its id.
 *  - 🔑 **The bridge is absent for a large share of real loads anyway.** The card picker is
 *    normally browsed from a LAPTOP, where there is no `DashieNative` — so a brand-aware build
 *    would read "Dashie Camera" on the tablet and the neutral fallback on the laptop, i.e. one
 *    card under two names depending on where you opened HA. Consistently neutral beats that.
 *
 * Cost is a little findability in the Dashie picker. If John wants the brand word back, it is a
 * small change — but it buys the mirror, the fails-quiet derivation, or the two-names problem
 * above, so it should be a deliberate call rather than a default.
 */
window.customCards = window.customCards || [];
window.customCards.push({
    type: 'dashie-camera-card',
    name: 'RTSP Camera (Native)',
    description: 'Native RTSP camera card for kiosk tablets (go2rtc)',
    preview: false,
});

console.info(
    `%c DASHIE-CAMERA %c v${CARD_VERSION} `,
    'background: #ff6b00; color: white; font-weight: bold; padding: 2px 6px; border-radius: 4px 0 0 4px;',
    'background: #333; color: white; padding: 2px 6px; border-radius: 0 4px 4px 0;'
);
