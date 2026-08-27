/**
 * Power Management Engine — background charging control.
 * Polls battery every 60s and toggles a Home Assistant smart switch
 * based on configured min/max thresholds (hysteresis algorithm).
 *
 * Runs as a singleton in the kiosk shell bundle.
 */

const POLL_INTERVAL = 60 * 1000; // 60 seconds
const MIN_TOGGLE_GAP = 5 * 60 * 1000; // 5 minutes between switch toggles
const CONFIG_KEY = 'dashie-power-management-config';
const STATE_KEY = 'dashie-power-engine-state';
const TAG = '[PowerEngine]';

const DEFAULT_CONFIG = {
    enabled: false,
    entityId: '',
    preferNight: false,
    minThreshold: 20,
    maxThreshold: 80,
    emergencyThreshold: 10,
    nightStart: '22:00',
    nightEnd: '06:00'
};

class PowerManagementEngineImpl {
    constructor() {
        this._intervalId = null;
        this._state = 'IDLE'; // IDLE or CHARGING
        this._lastToggleTime = 0;
        this._lastBatteryLevel = null;
        this._lastSwitchState = null;
        this._manualOverrideUntil = 0;
        this._overrideTarget = null; // null = normal, 100 = charge-to-full, 5 = discharge-to-5%
        this._restoreState();

        // Listen for switch state replies from HA iframe
        window.addEventListener('message', (e) => {
            if (e.data?.type === 'power-engine-switch-state') {
                this._lastSwitchState = e.data.state;
            }
        });

        // Listen for native watchdog toggle commands (Kotlin runs hysteresis
        // natively during Doze/screen-off, sends toggle via this event)
        window.addEventListener('power-watchdog-toggle', (e) => {
            const { entityId, turnOn } = e.detail || {};
            if (!entityId) return;
            const action = turnOn ? 'turn_on' : 'turn_off';
            console.log(`${TAG} Watchdog toggle: switch.${action} → ${entityId}`);
            if (typeof window.evalInHaIframe !== 'function') {
                console.warn(`${TAG} [watchdog] evalInHaIframe not available`);
                return;
            }
            window.evalInHaIframe(`
                (function() {
                    try {
                        var hass = document.querySelector('home-assistant')?.hass;
                        if (!hass) { console.warn('PowerEngine: hass not available'); return; }
                        hass.callService('switch', '${action}', { entity_id: '${entityId}' });
                        console.log('PowerEngine: switch.${action} sent to ${entityId} (watchdog)');
                    } catch (e) { console.error('PowerEngine: switch toggle failed', e); }
                })();
            `);
        });
    }

    /** Start the polling loop. Safe to call multiple times. */
    start() {
        if (this._intervalId) return;

        console.log(`${TAG} Started (poll every ${POLL_INTERVAL / 1000}s, state: ${this._state})`);

        // Sync config to native watchdog (handles screen-off evaluation)
        this._syncConfigToNative();

        // Run immediately on start, then every 60s
        this._evaluate();
        this._intervalId = setInterval(() => this._evaluate(), POLL_INTERVAL);
    }

    /** Stop the polling loop. */
    stop() {
        if (this._intervalId) {
            clearInterval(this._intervalId);
            this._intervalId = null;
            console.log(`${TAG} Stopped`);
        }
    }

    get isRunning() {
        return this._intervalId !== null;
    }

    /**
     * Notify the engine that the user manually toggled the switch.
     * @param {boolean} turnedOn
     * @param {number|null} overrideTarget null = normal (10-min override),
     *   100 = charge to full, 5 = discharge to 5% then turn back on.
     */
    onManualToggle(turnedOn, overrideTarget = null) {
        const newState = turnedOn ? 'CHARGING' : 'IDLE';
        this._overrideTarget = overrideTarget;
        if (overrideTarget != null) {
            this._manualOverrideUntil = Number.MAX_SAFE_INTEGER;
            console.log(`${TAG} Manual toggle: switch ${turnedOn ? 'ON' : 'OFF'} → state=${newState} (override until ${overrideTarget}%)`);
        } else {
            this._manualOverrideUntil = Date.now() + 10 * 60 * 1000;
            console.log(`${TAG} Manual toggle: switch ${turnedOn ? 'ON' : 'OFF'} → state=${newState} (override 10min)`);
        }
        this._setState(newState);
    }

    // ==================== CORE LOGIC ====================

    _evaluate() {
        const config = this._loadConfig();

        if (!config.enabled || !config.entityId) {
            if (this._intervalId) {
                console.log(`${TAG} Disabled or no entity — skipping`);
            }
            return;
        }

        const battery = this._getBattery();
        if (battery.level == null) {
            console.log(`${TAG} Battery level unavailable — skipping`);
            return;
        }

        // Query actual HA switch state for next poll's logging
        this._querySwitchState(config.entityId);

        // Determine active thresholds
        const isNight = this._isNightWindow(config.nightStart, config.nightEnd);
        let thresholdLow = config.minThreshold;
        if (config.preferNight && !isNight) {
            thresholdLow = config.emergencyThreshold;
        }
        const thresholdHigh = config.maxThreshold;

        // Full context log
        const switchState = this._lastSwitchState || 'unknown';
        const prevLevel = this._lastBatteryLevel;
        const delta = prevLevel != null ? battery.level - prevLevel : null;
        const deltaStr = delta != null ? ` (${delta >= 0 ? '+' : ''}${delta}%)` : '';
        const pollMsg = `bat:${battery.level}%${deltaStr} chg:${battery.charging} sw:${switchState} st:${this._state} range:${thresholdLow}-${thresholdHigh}%`;
        console.log(`${TAG} ── POLL ── ${pollMsg}`);
        this._lastBatteryLevel = battery.level;

        // Manual override: user toggled the switch, skip hysteresis until override expires
        if (Date.now() < this._manualOverrideUntil) {
            const target = this._overrideTarget;
            if (target != null) {
                if (target >= 100 && battery.level >= 100) {
                    // Charged to full — clear override
                    console.log(`${TAG} Override target reached: bat ${battery.level}% >= ${target}% — clearing override`);
                    this._manualOverrideUntil = 0;
                    this._overrideTarget = null;
                } else if (target <= 5 && battery.level <= target) {
                    // Discharged to 5% — turn switch back ON
                    console.log(`${TAG} Override target reached: bat ${battery.level}% <= ${target}% — turning switch ON`);
                    const sent = this._toggleSwitch(config.entityId, true, 'override-target-reached');
                    if (sent) this._setState('CHARGING');
                    this._manualOverrideUntil = 0;
                    this._overrideTarget = null;
                } else {
                    console.log(`${TAG} Override active (target: ${target}%) — skipping hysteresis`);
                    // Handle disconnect during charge-to-full override
                    if (this._state === 'CHARGING' && !battery.charging) {
                        console.warn(`${TAG} DISCONNECT during override: re-sending turn_on`);
                        this._toggleSwitch(config.entityId, true, 'override-disconnect-detect');
                    }
                }
            } else {
                console.log(`${TAG} Manual override active — skipping hysteresis`);
            }
            return;
        }

        // Charger disconnect detection: if we think we're CHARGING but device says no,
        // re-send the turn_on command immediately (switch may have been toggled externally or HA lost it)
        if (this._state === 'CHARGING' && !battery.charging) {
            console.warn(`${TAG} DISCONNECT: st=CHARGING but not charging (sw:${switchState}) → turn_on`);
            this._toggleSwitch(config.entityId, true, 'disconnect-detect');
        }

        // Hysteresis state machine
        if (this._state === 'IDLE' && battery.level <= thresholdLow) {
            console.log(`${TAG} ON: bat ${battery.level}% <= ${thresholdLow}% → switch ON`);
            const sent = this._toggleSwitch(config.entityId, true, 'hysteresis-on');
            if (sent) this._setState('CHARGING');
        } else if (this._state === 'IDLE' && battery.level >= thresholdHigh && battery.charging) {
            console.log(`${TAG} GUARD OFF: bat ${battery.level}% >= ${thresholdHigh}% while IDLE+charging → switch OFF`);
            this._toggleSwitch(config.entityId, false, 'idle-above-max');
        } else if (this._state === 'CHARGING' && battery.level >= thresholdHigh) {
            console.log(`${TAG} OFF: bat ${battery.level}% >= ${thresholdHigh}% → switch OFF`);
            const sent = this._toggleSwitch(config.entityId, false, 'hysteresis-off');
            if (sent) this._setState('IDLE');
        }
    }

    // ==================== HA SWITCH CONTROL ====================

    _toggleSwitch(entityId, turnOn, reason) {
        // Rapid-cycle protection
        const now = Date.now();
        if (now - this._lastToggleTime < MIN_TOGGLE_GAP) {
            const waitSec = Math.round((MIN_TOGGLE_GAP - (now - this._lastToggleTime)) / 1000);
            console.log(`${TAG} BLOCKED (${reason}): rapid-cycle, retry in ${waitSec}s`);
            return false;
        }

        if (typeof window.evalInHaIframe !== 'function') {
            console.warn(`${TAG} FAILED (${reason}): evalInHaIframe not available`);
            return false;
        }

        const action = turnOn ? 'turn_on' : 'turn_off';
        console.log(`${TAG} SEND: switch.${action} → ${entityId} (${reason})`);

        window.evalInHaIframe(`
            (function() {
                try {
                    var hass = document.querySelector('home-assistant')?.hass;
                    if (!hass) { console.warn('PowerEngine: ❌ hass not available in iframe'); return; }
                    hass.callService('switch', '${action}', { entity_id: '${entityId}' });
                    console.log('PowerEngine: ✅ switch.${action} sent to ${entityId}');
                } catch (e) { console.error('PowerEngine: ❌ switch toggle failed', e); }
            })();
        `);

        this._lastToggleTime = now;
        return true;
    }

    /** Query HA for the actual switch state — result available on next poll via _lastSwitchState. */
    _querySwitchState(entityId) {
        if (typeof window.evalInHaIframe !== 'function') return;

        window.evalInHaIframe(`
            (function() {
                try {
                    var hass = document.querySelector('home-assistant')?.hass;
                    if (!hass) return;
                    var state = hass.states['${entityId}'];
                    var val = state ? state.state : 'entity_not_found';
                    window.parent.postMessage({ type: 'power-engine-switch-state', state: val }, '*');
                } catch (e) { /* ignore */ }
            })();
        `);
    }

    // ==================== BATTERY READING ====================

    _getBattery() {
        try {
            if (window.dashieDevice?.getSystemMetrics) {
                const metrics = JSON.parse(window.dashieDevice.getSystemMetrics());
                return {
                    level: metrics.batteryPercent ?? null,
                    charging: !!metrics.isCharging
                };
            }
        } catch (e) {
            console.warn(`${TAG} Failed to read battery`, e);
        }
        return { level: null, charging: false };
    }

    // ==================== NIGHT WINDOW ====================

    _isNightWindow(startStr, endStr) {
        const now = new Date();
        const minutes = now.getHours() * 60 + now.getMinutes();

        const [startH, startM] = startStr.split(':').map(Number);
        const [endH, endM] = endStr.split(':').map(Number);
        const start = startH * 60 + startM;
        const end = endH * 60 + endM;

        // Handle midnight crossing (e.g., 22:00 → 06:00)
        if (start <= end) {
            return minutes >= start && minutes < end;
        } else {
            return minutes >= start || minutes < end;
        }
    }

    // ==================== STATE PERSISTENCE ====================

    _setState(newState) {
        const oldState = this._state;
        this._state = newState;
        this._consecutiveDrops = 0;
        try {
            localStorage.setItem(STATE_KEY, JSON.stringify({
                state: newState,
                timestamp: Date.now()
            }));
        } catch (e) { /* localStorage unavailable */ }
        console.log(`${TAG} State: ${oldState} → ${newState}`);
    }

    _restoreState() {
        try {
            const raw = localStorage.getItem(STATE_KEY);
            if (raw) {
                const saved = JSON.parse(raw);
                this._state = saved.state || 'IDLE';
                console.log(`${TAG} Restored state: ${this._state} (saved ${Math.round((Date.now() - saved.timestamp) / 60000)}min ago)`);
            }
        } catch (e) {
            this._state = 'IDLE';
        }
    }

    // ==================== CONFIG ====================

    _loadConfig() {
        try {
            const raw = localStorage.getItem(CONFIG_KEY);
            if (raw) return { ...DEFAULT_CONFIG, ...JSON.parse(raw) };
        } catch (e) { /* use defaults */ }
        return { ...DEFAULT_CONFIG };
    }

    /** Push config to native SharedPreferences for the Kotlin watchdog. */
    _syncConfigToNative() {
        try {
            const config = this._loadConfig();
            if (window.dashieDevice?.syncPowerConfig) {
                window.dashieDevice.syncPowerConfig(JSON.stringify(config));
                console.log(`${TAG} Config synced to native watchdog`);
            }
        } catch (e) { /* ignore */ }
    }
}

export const PowerManagementEngine = new PowerManagementEngineImpl();
