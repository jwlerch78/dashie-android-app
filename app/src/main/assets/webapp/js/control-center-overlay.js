/**
 * Control Center Overlay
 *
 * Pre-renders the Control Center as a standalone overlay (outside the Settings modal)
 * for near-instant open times. Shows cached HTML immediately, then refreshes
 * battery/power status first, followed by a full state refresh to catch any
 * API-driven or external setting changes.
 *
 * Navigation flow:
 *   Hamburger → CC overlay (instant) → tap card → hide CC, open Settings sub-page
 *   Back from sub-page → close Settings → re-show CC with targeted refresh
 */

import { SettingsDashieConfigPage } from '../../js/modules/Settings/pages/settings-dashie-config-page.js';

const LOG_TAG = '[CCOverlay]';

/** Android keycode → action mapping */
const KEYCODE_TO_ACTION = {
    19: 'up', 20: 'down', 21: 'left', 22: 'right',
    23: 'enter', 66: 'enter', 4: 'back',
};

/**
 * Maps Settings page IDs to CC card selectors for targeted refresh.
 * When returning from a sub-page, only the relevant card's summary is updated.
 */
const PAGE_TO_CARD_SELECTOR = {
    'voice':                '[data-action="open-voice"] .cc-card-summary',
    'music':                '[data-action="open-music"] .cc-card-summary',
    'camera':               '[data-action="open-camera"] .cc-card-summary',
    'video-feeds':          '[data-action="open-video-feeds"] .cc-card-summary',
    'home-assistant':       '[data-action="open-ha"] .cc-card-summary',
    'display-screensaver':  '[data-action="open-screensaver"] .cc-card-summary',
    'display-sleep':        '[data-action="open-sleep"] .cc-card-summary',
    'system-power-management': '[data-action="open-power"] .cc-card-summary',
    'account':              '[data-action="open-account"] .cc-bottom-btn-subtitle',
};

/** Maps Settings page IDs to state keys for targeted refresh */
const PAGE_TO_STATE_KEY = {
    'voice': 'voice',
    'music': 'music',
    'camera': 'camera',
    'video-feeds': 'videoFeeds',
    'home-assistant': 'ha',
    'display-screensaver': 'screensaver',
    'display-sleep': 'sleep',
    'system-power-management': 'power',
    'account': 'account',
};

export class ControlCenterOverlay {
    constructor() {
        this.page = new SettingsDashieConfigPage();
        this.overlayElement = null;
        this._containerElement = null;
        this.contentElement = null;
        this.isVisible = false;

        /** When true, closing Settings will re-show the CC overlay */
        this._returnToCcAfterBack = false;
        /** The Settings page that was opened from CC (for targeted refresh on return) */
        this._openedPageId = null;

        /** Inactivity auto-close timer handle */
        this._inactivityTimer = null;
    }

    // ── Lifecycle ────────────────────────────────────────────────────

    /**
     * Pre-render during init. Creates the overlay DOM, loads initial state,
     * renders CC HTML, and appends to document.body (hidden).
     */
    prerender() {
        console.log(LOG_TAG, 'Pre-rendering Control Center overlay');

        // Load initial state from bridge
        this.page._loadState();

        // Create overlay DOM
        this.overlayElement = document.createElement('div');
        this.overlayElement.className = 'cc-overlay';

        this.overlayElement.innerHTML = `
            <div class="cc-overlay__container">
                <div class="settings-modal__nav-bar">
                    <button class="settings-modal__nav-close" data-action="close"
                            style="display: block;">Close</button>
                    <h1 class="settings-modal__nav-title">Control Center</h1>
                </div>
                <div class="cc-overlay__content"></div>
            </div>
        `;

        this._containerElement = this.overlayElement.querySelector('.cc-overlay__container');
        this.contentElement = this.overlayElement.querySelector('.cc-overlay__content');
        this.contentElement.innerHTML = this.page.render();

        // Wire click handler
        this.overlayElement.addEventListener('click', (e) => this._handleClick(e));

        // Touch interactions (scroll, swipe) also reset the inactivity timer
        this.overlayElement.addEventListener('touchstart', () => this._resetInactivityTimer(), { passive: true });

        // Wire the page's navigation callback to route through the overlay
        this.page.onNavigate = (pageId) => this.navigateToSettingsPage(pageId);

        // Append to body (hidden)
        document.body.appendChild(this.overlayElement);

        // Defocus all focusable elements
        this._defocusElements();

        console.log(LOG_TAG, 'Pre-render complete');
    }

    /**
     * Show the CC overlay instantly with cached content.
     * Refreshes battery/power first, then full state async.
     */
    show() {
        if (this.isVisible) return;
        this.isVisible = true;
        this._returnToCcAfterBack = false;
        this._openedPageId = null;

        console.log(LOG_TAG, 'Showing CC overlay');

        // Show instantly — no transition, no flash
        this.overlayElement.style.display = 'flex';

        // Activate D-pad
        this._activateDpad();

        // Defocus to prevent WebView native focus ring
        this._defocusElements();

        // Register callback for native wake mode dialog result
        window.onMotionWakeChanged = (description) => {
            this._refreshWakeModeCard(description);
        };

        // Priority: refresh battery/power status first
        this._refreshBattery();

        // Synchronous full state refresh so the user sees correct state immediately
        this._refreshFullState();

        // Start periodic polling while CC is open
        this._startPolling();

        // Start 30s inactivity auto-close timer
        this._resetInactivityTimer();
    }

    /**
     * Hide the CC overlay.
     */
    hide() {
        if (!this.isVisible) return;
        this.isVisible = false;

        console.log(LOG_TAG, 'Hiding CC overlay');

        this._clearInactivityTimer();
        this._stopPolling();
        this._deactivateDpad();
        // Reset container visibility in case it was hidden during Settings navigation
        if (this._containerElement) this._containerElement.style.visibility = '';
        this.overlayElement.style.display = 'none';
    }

    // ── Navigation ───────────────────────────────────────────────────

    /**
     * Navigate from CC to a Settings sub-page.
     * The CC overlay stays visible (backdrop + hidden content) the entire time
     * Settings is open. This prevents any flash — the dark backdrop is always
     * behind Settings. When Settings closes, CC content is restored.
     */
    navigateToSettingsPage(pageId) {
        console.log(LOG_TAG, `Navigating to Settings page: ${pageId}`);

        // Deactivate CC d-pad — Settings will manage its own d-pad
        this._deactivateDpad();

        // Clear CC timer — Settings will manage its own inactivity timeout
        this._clearInactivityTimer();

        if (pageId === 'main') {
            // "All Settings" — open Settings main menu, don't return to CC
            this._returnToCcAfterBack = false;
            this._openedPageId = null;
            this.hide();
            window.Settings?.show();
            return;
        }

        // Set return flag so closing Settings re-shows CC
        this._returnToCcAfterBack = true;
        this._openedPageId = pageId;

        // Pause polling while in a sub-page
        this._stopPolling();

        // Hide CC content card but keep the dark backdrop visible.
        // The overlay stays display:flex — Settings modal opens on top.
        this._containerElement.style.visibility = 'hidden';

        // Disable all Settings modal transitions (opacity fade, scale grow)
        // so the sub-page appears instantly — no flash, no scale animation.
        const modal = document.querySelector('.settings-modal');
        const modalContainer = modal?.querySelector('.settings-modal__container');
        if (modal) modal.style.transition = 'none';
        if (modalContainer) modalContainer.style.transition = 'none';

        // Show + navigate synchronously — both run in the same paint frame,
        // so the browser never renders the main menu. orchestrator.open()
        // has no real awaits, so it completes synchronously.
        window.Settings?.show();
        window.Settings?.navigateToPage(pageId);

        // Restore transitions after the initial paint for normal Settings use
        requestAnimationFrame(() => {
            if (modal) modal.style.transition = '';
            if (modalContainer) modalContainer.style.transition = '';
        });
    }

    /**
     * Called when Settings modal closes.
     * Restores CC content (was hidden with visibility:hidden) and re-activates d-pad.
     */
    onSettingsClosed(closedByInactivity = false) {
        if (!this._returnToCcAfterBack) return;

        if (closedByInactivity) {
            // Settings timed out — also dismiss CC without restoring it
            console.log(LOG_TAG, 'Settings timed out — hiding CC overlay too');
            this._returnToCcAfterBack = false;
            this._openedPageId = null;
            this.hide();
            return;
        }

        const pageId = this._openedPageId;
        this._returnToCcAfterBack = false;
        this._openedPageId = null;

        console.log(LOG_TAG, `Settings closed, returning to CC (from page: ${pageId})`);

        // Refresh the specific card that was just edited
        if (pageId) {
            this._refreshCardForPage(pageId);
        }

        // Restore CC content card (was visibility:hidden during Settings)
        this._containerElement.style.visibility = '';

        // Re-activate CC d-pad, refresh state, and resume polling
        this._activateDpad();
        this._defocusElements();
        this._refreshBattery();
        requestAnimationFrame(() => this._refreshFullState());
        this._startPolling();

        // Restart CC inactivity timer now that the user is back here
        this._resetInactivityTimer();
    }

    // ── Refresh ──────────────────────────────────────────────────────

    /**
     * Refresh only battery/power status (async).
     * Battery is the only truly dynamic value that changes without user action.
     */
    _refreshBattery() {
        if (!this.page._state?.hasBattery) return;
        if (!this.page.powerManagementPage?.config?.enabled) return;

        this.page.powerManagementPage._loadBatteryStatus().then(() => {
            // Rebuild power summary with fresh battery data
            const prevSummary = this.page._state?.power?.summary;
            this.page._loadState();
            const newSummary = this.page._state?.power?.summary;

            if (prevSummary !== newSummary) {
                const powerCard = this.contentElement?.querySelector(
                    '[data-action="open-power"] .cc-card-summary'
                );
                if (powerCard && this.page._state?.power) {
                    powerCard.textContent = this.page._state.power.summary;
                    console.log(LOG_TAG, `Battery updated: ${newSummary}`);
                }
            }
        });
    }

    // ── Polling ───────────────────────────────────────────────────────

    /**
     * Start periodic polling while CC is visible.
     * Camera fast-poll: 1.5s when enabled but not yet streaming.
     * General poll: 3s for all other state.
     */
    _startPolling() {
        this._stopPolling();
        this._pollTick = 0;

        // Camera fast-poll — 1.5s when camera is enabled but bridge says not running
        this._cameraPollTimer = setInterval(() => {
            const cam = this.page._state?.camera;
            if (!cam || cam.status !== 'warn') return;
            this._refreshFullState();
        }, 1500);

        // General poll — 3s for battery, all cards
        this._pollTimer = setInterval(() => {
            this._pollTick++;
            if (this._pollTick % 2 === 0) this._refreshBattery();
            this._refreshFullState();
        }, 3000);
    }

    _stopPolling() {
        if (this._pollTimer) {
            clearInterval(this._pollTimer);
            this._pollTimer = null;
        }
        if (this._cameraPollTimer) {
            clearInterval(this._cameraPollTimer);
            this._cameraPollTimer = null;
        }
    }

    /**
     * Full state refresh — reloads all settings from bridge and updates
     * only the card summaries that changed. Does NOT re-render the entire DOM.
     */
    _refreshFullState() {
        const oldState = this.page._state ? { ...this.page._state } : null;
        this.page._loadState();
        const newState = this.page._state;

        if (!oldState || !newState || !this.contentElement) return;

        // Debug: log camera state on every refresh when status is warn (waiting for stream)
        if (newState.camera?.status === 'warn') {
            let rawIsRunning = '?';
            try {
                const raw = JSON.parse(DashieNative.getRtspSettings());
                rawIsRunning = raw.isRunning;
            } catch (e) { /* */ }
            console.log(LOG_TAG, `Camera poll: status=${newState.camera.status} bridge.isRunning=${rawIsRunning} summary="${newState.camera.summary}"`);
        }

        // Debug: log camera state changes
        if (oldState.camera?.summary !== newState.camera?.summary ||
            oldState.camera?.status !== newState.camera?.status) {
            console.log(LOG_TAG, `Camera changed: "${oldState.camera?.summary}" (${oldState.camera?.status}) → "${newState.camera?.summary}" (${newState.camera?.status})`);
        }

        // Compare and update only changed cards
        let updated = 0;
        const checks = [
            { key: 'voice', selector: '[data-action="open-voice"] .cc-card-summary' },
            { key: 'videoFeeds', selector: '[data-action="open-video-feeds"] .cc-card-summary' },
            { key: 'music', selector: '[data-action="open-music"] .cc-card-summary' },
            { key: 'camera', selector: '[data-action="open-camera"] .cc-card-summary' },
            { key: 'ha', selector: '[data-action="open-ha"] .cc-card-summary' },
            { key: 'screensaver', selector: '[data-action="open-screensaver"] .cc-card-summary' },
            { key: 'sleep', selector: '[data-action="open-sleep"] .cc-card-summary' },
            { key: 'wakeMode', selector: '[data-action="open-wake-mode"] .cc-card-summary' },
            { key: 'power', selector: '[data-action="open-power"] .cc-card-summary' },
        ];

        for (const { key, selector } of checks) {
            // Voice card: trial note overrides the summary text
            const summary = key === 'voice'
                ? this._getVoiceSummary(newState)
                : newState[key]?.summary;
            const oldSummary = key === 'voice'
                ? this._getVoiceSummary(oldState)
                : oldState[key]?.summary;

            if (oldSummary !== summary) {
                const el = this.contentElement.querySelector(selector);
                if (el) {
                    el.textContent = summary;
                    // Update trial note styling for voice card
                    if (key === 'voice') {
                        const trialNote = this.page._getVoiceTrialNote(newState.voiceLicense);
                        el.className = trialNote
                            ? `cc-card-summary ${trialNote.cls}`
                            : 'cc-card-summary';
                    }
                    updated++;
                }
            }
            // Also check status dot changes
            if (oldState[key]?.status !== newState[key]?.status) {
                const card = this.contentElement.querySelector(`[data-action="${selector.match(/data-action="([^"]+)"/)?.[1] || ''}"]`);
                if (card) {
                    const dot = card.querySelector('.cc-dot');
                    if (dot) {
                        dot.className = `cc-dot cc-dot--${newState[key].status}`;
                        updated++;
                    }
                }
            }
        }

        if (updated > 0) {
            console.log(LOG_TAG, `Refreshed ${updated} card(s) from full state update`);
        }
    }

    /**
     * Refresh a specific card after returning from its Settings sub-page.
     */
    _refreshCardForPage(pageId) {
        const stateKey = PAGE_TO_STATE_KEY[pageId];
        const selector = PAGE_TO_CARD_SELECTOR[pageId];
        if (!stateKey || !selector) return;

        // Reload state from bridge
        this.page._loadState();
        const state = this.page._state?.[stateKey];
        if (!state) return;

        // Voice card: trial note overrides the summary text
        const summary = stateKey === 'voice'
            ? this._getVoiceSummary(this.page._state)
            : (state.summary || '');

        const el = this.contentElement?.querySelector(selector);
        if (el) {
            el.textContent = summary;
            // Update trial note styling for voice card
            if (stateKey === 'voice') {
                const trialNote = this.page._getVoiceTrialNote(this.page._state?.voiceLicense);
                el.className = trialNote
                    ? `cc-card-summary ${trialNote.cls}`
                    : 'cc-card-summary';
            }
            console.log(LOG_TAG, `Refreshed ${pageId} card: ${summary}`);
        }

        // Update status dot if applicable
        if (state.status) {
            const action = selector.split('"')[1]; // Extract action from selector
            const card = this.contentElement?.querySelector(`[data-action="${action}"]`);
            const dot = card?.querySelector('.cc-dot');
            if (dot) {
                dot.className = `cc-dot cc-dot--${state.status}`;
            }
        }
    }

    /**
     * Update wake mode card when native dialog reports a change.
     */
    _refreshWakeModeCard(description) {
        const summary = description || 'Touch Only';
        if (this.page._state) {
            this.page._state.wakeMode = { summary };
        }
        const el = this.contentElement?.querySelector('[data-action="open-wake-mode"] .cc-card-summary');
        if (el) {
            el.textContent = summary;
            console.log(LOG_TAG, `Wake mode updated: ${summary}`);
        }
    }

    /**
     * Get the display summary for the voice card, respecting trial note override.
     * When a trial is active/expired, show trial text instead of wake word details.
     */
    _getVoiceSummary(state) {
        if (!state) return '';
        const trialNote = this.page._getVoiceTrialNote(state.voiceLicense);
        return trialNote ? trialNote.text : (state.voice?.summary || '');
    }

    // ── Inactivity Timeout ────────────────────────────────────────────

    /** Start (or restart) the 60-second inactivity auto-close timer. */
    _resetInactivityTimer() {
        if (this._inactivityTimer) clearTimeout(this._inactivityTimer);
        this._inactivityTimer = setTimeout(() => {
            console.log(LOG_TAG, 'Inactivity timeout — closing CC overlay');
            this.hide();
        }, 60_000);
    }

    /** Cancel the inactivity timer without scheduling a new one. */
    _clearInactivityTimer() {
        if (this._inactivityTimer) {
            clearTimeout(this._inactivityTimer);
            this._inactivityTimer = null;
        }
    }

    // ── Click Handling ────────────────────────────────────────────────

    _handleClick(event) {
        // Any click resets the inactivity timer (hide() will cancel it if closing)
        this._resetInactivityTimer();

        // Backdrop click closes CC
        if (event.target === this.overlayElement) {
            this.hide();
            return;
        }

        // Close button
        const closeBtn = event.target.closest('[data-action="close"]');
        if (closeBtn && closeBtn.closest('.settings-modal__nav-bar')) {
            this.hide();
            return;
        }

        // Route to page's handleItemClick for cards with data-action
        const actionTarget = event.target.closest('[data-action]');
        if (actionTarget && !actionTarget.closest('.settings-modal__nav-bar')) {
            this.page.handleItemClick(actionTarget);
        }
    }

    // ── D-pad Navigation ─────────────────────────────────────────────

    _activateDpad() {
        this.page._gridRow = 0;
        this.page._gridCol = 0;
        this.page._gridMap = null;

        window._ccOverlayDpad = (keyCode) => {
            const action = KEYCODE_TO_ACTION[keyCode];
            if (!action) return;

            // Any d-pad input resets the inactivity timer
            this._resetInactivityTimer();

            if (action === 'back') {
                this.hide();
                return;
            }

            if (action === 'enter') {
                const focused = this.contentElement?.querySelector(
                    '.settings-modal__menu-item--selected'
                );
                if (focused) focused.click();
                return;
            }

            // D-pad navigation — use handleDpad with overlay context
            this.page.handleDpad(action, {
                container: this.contentElement,
                setFocusIndex: (index) => this._setFocusIndex(index)
            });
        };

        // Tell Kotlin to forward ALL d-pad keys to JS
        window.overlayHasKeyboardFocus = true;
        try { DashieNative.setOverlayKeyboardFocus(true); } catch (e) { /* bridge unavailable */ }

        // Initial highlight on first item
        this._setFocusIndex(0);

        console.log(LOG_TAG, 'D-pad activated');
    }

    _deactivateDpad() {
        delete window._ccOverlayDpad;

        // Only release keyboard focus if nothing else needs it
        if (!window._shellSettingsDpad && !window.dashieDashBarDpad) {
            window.overlayHasKeyboardFocus = false;
            try { DashieNative.setOverlayKeyboardFocus(false); } catch (e) { /* bridge unavailable */ }
        }

        console.log(LOG_TAG, 'D-pad deactivated');
    }

    /**
     * Update the visual focus highlight to the given index.
     */
    _setFocusIndex(index) {
        if (!this.contentElement) return;

        // Clear existing selection
        this.contentElement.querySelectorAll('.settings-modal__menu-item--selected')
            .forEach(el => el.classList.remove('settings-modal__menu-item--selected'));

        // Highlight new selection
        const focusable = Array.from(
            this.contentElement.querySelectorAll('[data-focusable]')
        );
        if (focusable[index]) {
            focusable[index].classList.add('settings-modal__menu-item--selected');
            focusable[index].scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        }
    }

    /**
     * Prevent Android WebView from independently d-pad-focusing elements.
     */
    _defocusElements() {
        this.overlayElement?.querySelectorAll('[data-focusable]').forEach(el => {
            el.tabIndex = -1;
        });
    }
}
