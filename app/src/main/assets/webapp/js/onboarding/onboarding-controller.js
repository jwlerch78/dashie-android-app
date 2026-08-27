/**
 * Onboarding Controller
 * Manages the first-launch flow:
 * 1. Welcome + Network Scan
 * 2. HA Config (URL, toggles, API)
 * 3. Permission Prompt (Now / Later)
 * Post-onboarding guided tips (floating overlays on live shell):
 * - Swipe Tip: reveals sidebar, shows floating card with arrow
 * - Control Center Tip: opens hamburger menu, shows floating card
 *
 * Communicates with Kotlin via DashieNative bridge.
 * Registers global callbacks for async results (scan, HA login, permissions).
 */

import { renderLoading, renderWelcome, renderHaConfig, renderCustomUrlConfig, renderDataCollection, renderDataCollectionInfo, renderPermissionPrompt } from './onboarding-renderer.js';
import { applyBrandAccent } from '../brand.js';

const SCAN_TIMEOUT_MS = 5000;

const TAG = '[Onboarding]';

// Initial timestamp captured at module load so we can log relative timings
// (ms since this module first ran) at every major step in the onboarding
// flow. Useful for diagnosing slow first-run experiences.
const T0 = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
function _ts() {
  const now = (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
  return `[t=${Math.round(now - T0)}ms]`;
}

// Android keycodes for d-pad navigation
const KEYCODE = {
  DPAD_UP: 19, DPAD_DOWN: 20, DPAD_LEFT: 21, DPAD_RIGHT: 22,
  DPAD_CENTER: 23, ENTER: 66, BACK: 4
};

export class OnboardingController {
  constructor() {
    this.overlay = null;
    this.scanResults = null;
    this.scanning = true;
    this.haLoginSuccess = false;
    this.haConfigData = null;

    // Scan timeout (show results even if scan hangs)
    this.scanTimeoutId = null;

    // Permission poll interval (re-check after returning from system settings)
    this.permPollId = null;

    // D-pad navigation state
    this._focusIndex = 0;
    this._currentScreen = null;  // 'welcome' | 'ha-config' | 'custom-url-config' | 'data-collection' | 'data-collection-info' | 'permissions'
    this.customUrlData = null;   // set only on the custom-URL path; also the flag back-nav reads
  }

  /**
   * Initialize onboarding overlay and start on Screen 1.
   * Call this when isSetupComplete() returns false.
   */
  initialize() {
    console.log(TAG, _ts(), 'initialize() called — starting onboarding flow');

    // Create overlay container
    this.overlay = document.createElement('div');
    this.overlay.className = 'onboarding-overlay';
    // Light vs dark follows Dashie's OWN theme, not the OS. The kiosk theme
    // system applies html.theme-light / html.theme-dark on the root; mirror
    // that onto the overlay as a .light class so onboarding.css can paint the
    // setup screen to match (rather than via prefers-color-scheme, which reads
    // the diverging system dark mode). Fall back to localStorage 'dashie-theme'
    // if the root class isn't set yet.
    if (this._isLightTheme()) this.overlay.classList.add('light');
    // Brand accent (spinner, primary button, d-pad focus ring, toggles). Must run BEFORE the
    // first _setContent so nothing paints Dashie orange for a frame on a Chickadee device.
    applyBrandAccent(this.overlay);
    document.body.appendChild(this.overlay);

    // Register global callbacks for Kotlin → JS
    window.dashieOnScanComplete = (results) => this._onScanComplete(results);
    window.dashieOnHaLoginComplete = (result) => this._onHaLoginComplete(result);
    window.dashieOnPermissionsComplete = () => this._onPermissionsComplete();

    // Register d-pad handler for Kotlin → JS routing (via kiosk-overlay-bridge)
    window.dashieOnboardingDpad = (keyCode) => this._handleDpad(keyCode);

    // Check if there's a pending login success from a previous attempt
    // (activity was recreated due to memory pressure, or inline auth navigated
    // away from the shell and back). If so, skip directly to post-login step.
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (N && N.hasPendingLoginSuccess && N.hasPendingLoginSuccess()) {
      console.log(TAG, 'Pending login success found — skipping to post-login');
      this.haLoginSuccess = true;
      N.clearPendingLoginSuccess();

      // Animate in, then show data collection (post-login step)
      requestAnimationFrame(() => {
        this.overlay.classList.add('visible');
      });
      this._showDataCollection();
      return;
    }

    // Durable resume: a previous HA login already persisted credentials
    // (HA URL + refresh token). This happens when onboarding was killed
    // mid-flow — notably Android force-restarts the app when the
    // "Install unknown apps" grant changes during the permissions step.
    // Unlike the one-shot pendingHaLoginSuccess flag above (consumed on
    // first read, lost in restart races), stored credentials survive any
    // number of restarts — so resume at post-login instead of making the
    // user redo Welcome → scan → HA config → HA login. Feature-detected:
    // older APKs without the bridge method fall through to the normal flow.
    if (N && N.hasStoredHaCredentials && N.hasStoredHaCredentials()) {
      this.haLoginSuccess = true;

      // If every onboarding permission is also already satisfied, the user
      // made it through the whole wizard before the kill (the install-unknown-
      // apps grant is the LAST sweep step, so the forced restart always lands
      // here with everything granted). Nothing left to ask — complete
      // onboarding outright and let Kotlin navigate to the dashboard.
      if (N.areOnboardingPermissionsGranted && N.areOnboardingPermissionsGranted()) {
        console.log(TAG, 'Stored HA credentials + all permissions granted — completing onboarding');
        this._complete();
        return;
      }

      console.log(TAG, 'Stored HA credentials found — resuming at post-login');
      requestAnimationFrame(() => {
        this.overlay.classList.add('visible');
      });
      this._showDataCollection();
      return;
    }

    // Check if user clicked "Connect to Home Assistant" on the Dashie login
    // page, OR cancelled / failed an in-progress HA login — skip scan +
    // welcome and go straight to HA URL entry. When resuming from a
    // cancelled login the user already typed a config, so restore it from
    // prefs instead of showing an empty form (D.29 follow-up).
    if (N && N.getPendingHaSetup && N.getPendingHaSetup()) {
      console.log(TAG, 'Pending HA setup intent found — jumping to HA config');
      N.clearPendingHaSetup();
      // The network scan never ran on this path. Clear the flag so a later
      // back-navigation to the welcome card doesn't render a perpetual
      // "scanning your network" state with no scan actually in flight.
      this.scanning = false;
      // Restore the previously-entered HA config from prefs so the IP /
      // settings the user already typed aren't lost on resume.
      const savedConfig = this._loadSavedConfigFromPrefs();
      // Seed scanResults from the saved config too. Otherwise, if the user
      // backs out of the HA config screen to the welcome card, renderWelcome
      // sees no HA and shows the no-HA "Create a Dashie Account" layout —
      // wrong, since the user demonstrably has HA (they were configuring it).
      if (savedConfig && savedConfig.url) {
        try {
          const u = new URL(savedConfig.url);
          this.scanResults = { ha: [{
            url: savedConfig.url,
            ip: u.hostname,
            port: u.port || (u.protocol === 'https:' ? '443' : '80')
          }] };
        } catch (e) {
          this.scanResults = { ha: [{ url: savedConfig.url, ip: savedConfig.url, port: '' }] };
        }
      }
      requestAnimationFrame(() => {
        this.overlay.classList.add('visible');
      });
      this._showHaConfig('', savedConfig);
      return;
    }

    // Show the loading card BEFORE kicking off the scan so the user always
    // sees a paint, even when the scan completes near-instantly (e.g. on
    // hotspots where the HA port probe fails immediately).
    this._showLoading();
    this._startScan();

    // Animate in
    requestAnimationFrame(() => {
      this.overlay.classList.add('visible');
      console.log(TAG, _ts(), 'overlay visible class added (after rAF)');
    });
  }

  /**
   * Light vs dark for the onboarding overlay, from Dashie's OWN theme — not
   * the OS. Prefer the root theme class the kiosk theme system sets
   * (html.theme-light / theme-dark); fall back to localStorage 'dashie-theme'
   * (light when empty, 'light', or a '*-light' family variant). Avoids
   * prefers-color-scheme, which reads the diverging Android system dark mode.
   */
  _isLightTheme() {
    try {
      const root = document.documentElement;
      if (root.classList.contains('theme-light')) return true;
      if (root.classList.contains('theme-dark')) return false;
      const theme = localStorage.getItem('dashie-theme') || '';
      return !theme || theme === 'light' || theme.endsWith('-light');
    } catch (_) {
      return false; // unavailable → dark default
    }
  }

  /** Remove the onboarding overlay from DOM. */
  destroy() {
    console.log(TAG, 'Destroying onboarding overlay');
    clearTimeout(this.scanTimeoutId);
    clearInterval(this.permPollId);
    delete window.dashieOnScanComplete;
    delete window.dashieOnHaLoginComplete;
    delete window.dashieOnPermissionsComplete;
    delete window.dashieOnboardingDpad;
    if (this.overlay) {
      const el = this.overlay;
      el.classList.remove('visible');
      setTimeout(() => el.remove(), 300);
      this.overlay = null;
    }
  }

  // --- Screen rendering ---

  _setContent(el, screenName) {
    if (!this.overlay) return;
    this.overlay.innerHTML = '';
    this.overlay.appendChild(el);
    this._currentScreen = screenName || null;
    this._focusIndex = 0;
    // Apply initial d-pad highlight after render
    requestAnimationFrame(() => this._updateFocusHighlight());
  }

  _showLoading() {
    console.log(TAG, _ts(), '_showLoading() rendering loading card');
    this._setContent(renderLoading(), 'loading');
  }

  _showWelcome() {
    this._setContent(renderWelcome({
      scanResults: this.scanResults,
      scanning: this.scanning,
      // What this device is ALREADY pointed at. Read every render rather than
      // cached: the welcome card is re-entered by back-navigation from the HA
      // config screen, where the user may just have changed it.
      configuredHa: this._loadConfiguredHaForWelcome(),
      onHaPath: (detectedUrl) => this._showHaConfig(detectedUrl),
      onStandalonePath: () => this._handleStandalonePath(),
      onCustomUrlPath: () => this._showCustomUrlConfig(),
      onCreateAccount: () => this._handleDashieAccountPath('create'),
      onSignIn: () => this._handleDashieAccountPath('signin'),
      onClose: () => this._handleClose()
    }), 'welcome');
    // Auto-focus the primary CTA. No HA detected → "Sign in with Google"
    // (ob-signin-btn, only present on the no-HA layout); HA detected →
    // "Connect / Configure Home Assistant" (ob-ha-btn).
    const elements = this._getFocusableElements();
    const preferredIds = ['ob-signin-btn', 'ob-ha-btn'];
    let primaryIdx = -1;
    for (const id of preferredIds) {
      primaryIdx = elements.findIndex(el => el.id === id);
      if (primaryIdx >= 0) break;
    }
    if (primaryIdx >= 0) {
      this._focusIndex = primaryIdx;
      this._updateFocusHighlight();
    }
  }

  _showHaConfig(prefilledUrl, savedConfig) {
    // Fall back to prefs whenever no in-memory savedConfig is provided.
    // Covers the activity-recreated-mid-permissions case where the user
    // had completed HA config + login but landed back on the welcome card
    // via path 3 init (hasPendingLoginSuccess already consumed) and then
    // clicked "Connect to HA" — without this, the form rendered blank.
    if (!savedConfig) {
      savedConfig = this._loadSavedConfigFromPrefs();
    }
    this._setContent(renderHaConfig({
      prefilledUrl: prefilledUrl || '',
      savedConfig: savedConfig || null,
      onConnect: (config) => this._handleConnect(config),
      onBack: () => this._showWelcome(),
      onCustomUrl: () => this._showCustomUrlConfig()
    }), 'ha-config');
  }

  /** Show the custom-URL config screen (the non-HA sibling of the HA config screen). */
  _showCustomUrlConfig(savedConfig = null) {
    this._setContent(renderCustomUrlConfig({
      savedConfig: savedConfig || this.customUrlData || null,
      onConnect: (config) => this._applyCustomUrlConfig(config),
      // Back returns to the HA config screen, not the welcome chooser — since
      // 08-17 this screen's only entry is the HA screen's alt-path link, and
      // Back should undo the step the user actually took.
      onBack: () => this._showHaConfig('', this.haConfigData)
    }), 'custom-url-config');
  }

  /**
   * Persist the custom-URL choice and continue onboarding WITHOUT an HA login.
   *
   * This is the whole point of the path, and the one place it must not copy the
   * HA flow: `_handleConnect` ends in `N.openHaLogin(...)`, and there is no HA to
   * log into here. Completion therefore has to be asserted directly.
   *
   * Why one `setHomeAssistantSettings` call rather than a new bridge method:
   * every key below already exists on the SHIPPED 1.0.15 bridge, so this whole
   * feature is JS-only and cherry-picks to dev without an APK/Kotlin release.
   *   - useCustomUrl/customUrl → the pair MainUrlHandler already routes on
   *   - haEnabled:false        → onOnboardingComplete() would force this TRUE and
   *                              surface HA widgets/sections to someone with no HA
   *   - isSetupComplete:true   → the kiosk gate (MainActivity → UiMode.KIOSK)
   */
  _applyCustomUrlConfig(config) {
    this.customUrlData = config;
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (N) {
      if (typeof N.setHomeAssistantSettings === 'function') {
        // ⚠️ The payload is NESTED by section — {core|api|camera|videoFeed|power|alert}.
        // A FLAT {useCustomUrl,...} object parses fine, matches no section, and is
        // silently discarded: JsBridgeHaDelegate only try/catches JSON parse errors, so
        // nothing logs and the caller sees success. Cost me a device run — the setup
        // completed, api_enabled persisted, and not one of these four keys did.
        N.setHomeAssistantSettings(JSON.stringify({
          core: {
            useCustomUrl: true,
            customUrl: config.url,
            haEnabled: false,
            isSetupComplete: true
          }
        }));
      } else {
        // Older bridge than we support — say so loudly rather than silently
        // landing the user on a half-configured device (standing rule 2).
        console.warn(TAG, 'DROP: setHomeAssistantSettings missing — custom URL not persisted');
      }
      if (typeof N.setApiEnabled === 'function') {
        N.setApiEnabled(config.apiEnabled);
        if (config.apiEnabled) {
          if (typeof N.setApiPort === 'function') N.setApiPort(config.apiPort);
          if (config.apiPassword && typeof N.setApiPassword === 'function') {
            N.setApiPassword(config.apiPassword);
          }
        }
      }
    }
    // Same tail as the HA path — data collection, then permissions.
    this._showDataCollection();
  }

  /**
   * Build a savedConfig object from previously-saved HA prefs, or null if
   * nothing useful is stored. Shared by every code path that lands on the
   * HA config screen, so the form survives activity recreation regardless
   * of which init branch we re-entered through.
   */
  /**
   * The HA this device is genuinely configured against, or null — for the
   * welcome card's "configured outranks discovered" line.
   *
   * 🔴 Reads `ha.url`, NOT `ha.base_url`, and the difference decides whether
   * this is a fix or a regression. `getHaConnectionSettings()` returns
   * `base_url` verbatim, INCLUDING the untouched `http://192.168.1.x:8123`
   * placeholder that every unconfigured device carries — the delegate's own
   * comment says it treats that string as "no URL configured" and blanks
   * `url` for exactly this reason. Reading base_url here would have told every
   * fresh device it was "configured at 192.168.1.x", suppressing both the real
   * discovery banner and the no-HA account layout. Caught by reading the
   * delegate rather than trusting the field name.
   *
   * Deliberately ignores `ha.enabled`, which also folds in the per-device
   * "HA features on/off" toggle: a user who configured a box and toggled HA
   * off has still told us which box is theirs, and that is the only question
   * this answers.
   */
  _loadConfiguredHaForWelcome() {
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (!N || !N.getHaConnectionSettings) return null;
    try {
      const ha = JSON.parse(N.getHaConnectionSettings());
      const url = (ha && ha.url) || '';
      return url ? { url } : null;
    } catch (e) {
      console.warn(TAG, 'Failed to read configured HA for welcome card', e);
      return null;
    }
  }

  _loadSavedConfigFromPrefs() {
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (!N || !N.getHaConnectionSettings) return null;
    try {
      const ha = JSON.parse(N.getHaConnectionSettings());
      if (ha && (ha.base_url || ha.url)) {
        return {
          url: ha.base_url || ha.url || '',
          dashboardPath: ha.dashboard || '',
          hideSidebar: ha.hide_sidebar !== false,
          hideTabs: !!ha.hide_tabs,
          apiEnabled: !!ha.api_enabled,
          apiPort: ha.api_port || 2323,
          apiPassword: ha.api_password || '',
          autoBuild: ha.auto_build !== false
        };
      }
    } catch (e) {
      console.warn(TAG, 'Failed to read saved HA config from prefs', e);
    }
    return null;
  }

  _showDataCollection() {
    this._setContent(renderDataCollection({
      onContinue: ({ perfEnabled, wakeEnabled }) => {
        console.log(TAG, 'Data collection preferences:', { perfEnabled, wakeEnabled });
        this._saveDataCollectionPreferences(perfEnabled, wakeEnabled);
        // D.67 — skip the permission prompt entirely on TV devices. The
        // Android-side requestAllOnboardingPermissions() already short-
        // circuits on TV (no requestable permissions), but the prompt
        // SCREEN still rendered and made the user press "Set Up Now" to
        // proceed. renderPermissionPrompt strips TV-irrelevant items from
        // the list but doesn't suppress the screen itself.
        let isTv = false;
        try {
          if (typeof DashieNative !== 'undefined' && DashieNative.getDeviceType) {
            isTv = DashieNative.getDeviceType() === 'tv';
          }
        } catch (e) { /* ignore */ }
        if (isTv) {
          console.log(TAG, 'TV device — skipping permission prompt screen');
          this._startTipFlow();
        } else {
          this._showPermissionPrompt();
        }
      },
      onLearnMore: () => this._showDataCollectionInfo()
    }), 'data-collection');
    // Auto-focus Continue button
    const elements = this._getFocusableElements();
    if (elements.length > 0) {
      this._focusIndex = elements.length - 1;
      this._updateFocusHighlight();
    }
  }

  _showDataCollectionInfo() {
    this._setContent(renderDataCollectionInfo({
      onBack: () => this._showDataCollection()
    }), 'data-collection-info');
    // Auto-focus Back button
    const elements = this._getFocusableElements();
    if (elements.length > 0) {
      this._focusIndex = elements.length - 1;
      this._updateFocusHighlight();
    }
  }

  _showPermissionPrompt() {
    this._setContent(renderPermissionPrompt({
      onGrantNow: () => {
        console.log(TAG, 'User chose to grant permissions now');
        const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
        if (N && N.requestAllOnboardingPermissions) {
          N.requestAllOnboardingPermissions();
        } else {
          this._startTipFlow();
        }
      },
      onLater: () => {
        console.log(TAG, 'User chose to skip permissions');
        // D.70 — persist the deferral so the next onResume's HA-enabled
        // permission sweep doesn't re-prompt right after.
        const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
        if (N && N.setPermissionPromptDeclined) {
          try { N.setPermissionPromptDeclined(true); } catch (_) {}
        }
        this._startTipFlow();
      }
    }), 'permissions');
    // Auto-focus "Set Up Now" button (last focusable element = primary action)
    const elements = this._getFocusableElements();
    if (elements.length > 0) {
      this._focusIndex = elements.length - 1;
      this._updateFocusHighlight();
    }
  }

  // --- Post-onboarding tip flow ---

  /**
   * Complete onboarding. Tips are shown later by Kotlin after the shell
   * reloads (token injection navigates the WebView away and back).
   * Kotlin calls window.dashieShowOnboardingTips() once the shell is ready.
   */
  _startTipFlow() {
    console.log(TAG, 'Starting tip flow — completing onboarding');
    clearInterval(this.permPollId);
    this._complete();
  }

  // --- Bridge calls ---

  _startScan() {
    console.log(TAG, _ts(), '_startScan() invoking DashieNative.startNetworkScan');
    if (typeof DashieNative !== 'undefined' && DashieNative.startNetworkScan) {
      DashieNative.startNetworkScan();
    } else {
      console.warn(TAG, _ts(), 'DashieNative.startNetworkScan not available');
    }
    this.scanTimeoutId = setTimeout(() => {
      if (this.scanning) {
        console.log(TAG, _ts(), `Scan timeout fired (${SCAN_TIMEOUT_MS}ms) — proceeding with empty results`);
        this.scanning = false;
        this.scanResults = this.scanResults || { ha: [] };
        this._routeAfterScan();
      }
    }, SCAN_TIMEOUT_MS);
  }

  _onScanComplete(results) {
    console.log(TAG, _ts(), 'Scan complete callback:', JSON.stringify(results));
    clearTimeout(this.scanTimeoutId);
    this.scanning = false;
    this.scanResults = results;
    this._routeAfterScan();
  }

  /**
   * Decide what to show after the network scan finishes — always the
   * welcome card. renderWelcome adapts its own layout:
   *   - HA found          → HA-first layout
   *   - HA not found      → "Sign in with Google" / "Sign up for Dashie" /
   *                          "Use Dashie with Home Assistant" (the manual
   *                          HA-setup path — needed since HA wasn't detected)
   *   - accounts disabled → HA-only layout (no account buttons)
   *
   * We deliberately never auto-route straight to /login on a no-HA scan:
   * the user still needs the manual HA-setup option, and the old auto-route
   * also tripped a "stuck on Setting things up..." bug when the Kotlin gate
   * suppressed the navigation broadcast.
   *
   * Defers the route decision until after at least one paint to ensure the
   * loading card is visible (otherwise super-fast scans on hotspots cause
   * a blank-white flash directly into the WebView navigation).
   */
  _routeAfterScan() {
    const haInst = this.scanResults?.ha?.[0] || null;
    console.log(TAG, _ts(), `_routeAfterScan: haFound=${!!haInst}`);
    requestAnimationFrame(() => {
      // Always land on the welcome card — renderWelcome picks the layout.
      console.log(TAG, _ts(), `Showing welcome card (haFound=${!!haInst})`);
      this._showWelcome();
    });
  }

  _handleConnect(config) {
    console.log(TAG, 'Connect:', config.url);
    this.haConfigData = config;

    // Save display/API settings and URL via bridge BEFORE launching login.
    // Inline auth navigates the WebView away from the shell (destroying this
    // controller), so everything must be persisted to prefs first.
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (N) {
      // Build the full URL and save it to prefs immediately
      let fullUrl = config.url.replace(/\/+$/, '');
      if (config.dashboardPath) {
        fullUrl += '/' + config.dashboardPath.replace(/^\/+/, '');
      }
      this.pendingFullUrl = fullUrl;
      N.saveHaUrl(fullUrl);

      N.setHaHideSidebar(config.hideSidebar);
      N.setHaHideTabs(config.hideTabs);
      N.setApiEnabled(config.apiEnabled);
      if (config.apiEnabled) {
        N.setApiPort(config.apiPort);
        if (config.apiPassword) {
          N.setApiPassword(config.apiPassword);
        }
      }
      // Trigger HA login (URL passed directly, not via setConfiguredUrl)
      N.openHaLogin(config.url);
    }
  }

  _onHaLoginComplete(result) {
    console.log(TAG, 'HA login result:', result?.success);
    if (result?.success) {
      this.haLoginSuccess = true;
      // Save the URL to prefs (without navigating the main WebView)
      // Navigation + token injection happens in onOnboardingComplete via injectSavedTokensAndLoad()
      const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
      if (N && this.pendingFullUrl) {
        console.log(TAG, 'Saving HA URL:', this.pendingFullUrl);
        N.saveHaUrl(this.pendingFullUrl);
      }
      // Show data collection opt-out, then permissions
      this._showDataCollection();
    } else {
      // On failure/cancel: go back to HA config screen with saved form values
      console.log(TAG, 'Login failed/cancelled — returning to HA config');
      this._showHaConfig('', this.haConfigData);
    }
  }

  _onPermissionsComplete() {
    console.log(TAG, 'All permissions complete — starting tip flow');
    this._startTipFlow();
  }

  _handleClose() {
    console.log(TAG, 'Close button tapped — showing exit confirmation');
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (N && N.showExitConfirmation) {
      N.showExitConfirmation();
    } else if (N && N.exitApp) {
      N.exitApp();
    }
  }

  _handleStandalonePath() {
    // "Use Dashie without Home Assistant" — user is opting out of HA but
    // hasn't picked sign-in vs sign-up yet. Route to /login/?mode=choose
    // which shows the merged sign-in/sign-up card (no auto-route to QR).
    // The non-empty mode value still signals the Kotlin side
    // (JsBridgeSystemDelegate.onDashieSignIn) to flip haEnabled=false.
    this._handleDashieAccountPath('choose');
  }

  /**
   * Route to the Dashie sign-in flow with a mode hint.
   * Kotlin's MainBroadcastManager appends ?mode=<mode> to the login URL.
   * @param {'create'|'signin'} mode
   */
  _handleDashieAccountPath(mode) {
    console.log(TAG, _ts(), '_handleDashieAccountPath: invoking DashieNative.signIn(mode=' + mode + ')');
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (N && N.signIn) {
      N.signIn(mode);
    } else {
      console.warn(TAG, _ts(), 'DashieNative.signIn not available — cannot route to login');
    }
  }

  // --- D-pad navigation ---

  /** Get visible focusable elements in the current onboarding screen. */
  _getFocusableElements() {
    if (!this.overlay) return [];
    return Array.from(this.overlay.querySelectorAll(
      // Anchors (<a>) are included for Privacy Policy / "Learn how we use
      // your data" on the data-collection screen — without them d-pad
      // skips past those rows entirely.
      'button:not([disabled]), input:not([disabled]), a[href], .onboarding-toggle'
    )).filter(el => el.offsetParent !== null); // Exclude hidden elements (display:none parents)
  }

  /** Apply d-pad highlight to the currently focused element and scroll it into view. */
  _updateFocusHighlight() {
    const elements = this._getFocusableElements();
    elements.forEach((el, i) => {
      el.classList.toggle('onboarding-dpad-focus', i === this._focusIndex);
    });
    // Scroll the focused element into view within the onboarding card
    const focused = elements[this._focusIndex];
    if (focused) {
      focused.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  }

  /**
   * Find the nearest focusable element in the given direction using spatial navigation.
   * @param {'up'|'down'|'left'|'right'} direction
   * @returns {number} Index of the best target, or -1 if none found
   */
  _findSpatialTarget(direction) {
    const elements = this._getFocusableElements();
    const current = elements[this._focusIndex];
    if (!current) return -1;

    const rect = current.getBoundingClientRect();
    const cx = rect.left + rect.width / 2;
    const cy = rect.top + rect.height / 2;

    let bestIndex = -1;
    let bestScore = Infinity;

    for (let i = 0; i < elements.length; i++) {
      if (i === this._focusIndex) continue;
      const r = elements[i].getBoundingClientRect();
      const ex = r.left + r.width / 2;
      const ey = r.top + r.height / 2;
      const dx = ex - cx;
      const dy = ey - cy;

      // Check if element is in the correct direction (with 5px tolerance)
      let inDirection = false;
      switch (direction) {
        case 'up':    inDirection = dy < -5; break;
        case 'down':  inDirection = dy > 5; break;
        case 'left':  inDirection = dx < -5; break;
        case 'right': inDirection = dx > 5; break;
      }
      if (!inDirection) continue;

      // Score: prefer elements aligned with the movement direction
      // Penalize cross-axis offset to prefer in-line elements, but not too
      // heavily — 1.5x allows finding side-by-side buttons in the same row
      let score;
      if (direction === 'up' || direction === 'down') {
        score = Math.abs(dy) + Math.abs(dx) * 1.5;
      } else {
        score = Math.abs(dx) + Math.abs(dy) * 1.5;
      }

      if (score < bestScore) {
        bestScore = score;
        bestIndex = i;
      }
    }
    return bestIndex;
  }

  /**
   * Handle d-pad input from Kotlin (via kiosk-overlay-bridge).
   * Uses spatial navigation so LEFT/RIGHT work for side-by-side elements
   * (e.g. Base URL ↔ Dashboard Path, Back ↔ Connect buttons).
   * @param {number} keyCode - Android keycode
   */
  _handleDpad(keyCode) {
    const elements = this._getFocusableElements();
    if (!elements.length) return;

    // Clamp focus index
    if (this._focusIndex >= elements.length) this._focusIndex = elements.length - 1;

    switch (keyCode) {
      case KEYCODE.DPAD_UP:
      case KEYCODE.DPAD_DOWN:
      case KEYCODE.DPAD_LEFT:
      case KEYCODE.DPAD_RIGHT: {
        // For LEFT/RIGHT, check if we're in a button row first — use DOM structure
        // rather than spatial coordinates (more reliable across different form layouts)
        if (keyCode === KEYCODE.DPAD_LEFT || keyCode === KEYCODE.DPAD_RIGHT) {
          const currentEl = elements[this._focusIndex];
          const row = currentEl?.closest('.onboarding-btn-row');
          if (row) {
            const rowButtons = Array.from(row.querySelectorAll('button'));
            const rowIdx = rowButtons.indexOf(currentEl);
            const step = keyCode === KEYCODE.DPAD_RIGHT ? 1 : -1;
            const nextRowIdx = rowIdx + step;
            if (nextRowIdx >= 0 && nextRowIdx < rowButtons.length) {
              const targetIdx = elements.indexOf(rowButtons[nextRowIdx]);
              if (targetIdx >= 0) {
                this._focusIndex = targetIdx;
                this._updateFocusHighlight();
                break;
              }
            }
          }
        }

        const dirMap = {
          [KEYCODE.DPAD_UP]: 'up', [KEYCODE.DPAD_DOWN]: 'down',
          [KEYCODE.DPAD_LEFT]: 'left', [KEYCODE.DPAD_RIGHT]: 'right'
        };
        let target = this._findSpatialTarget(dirMap[keyCode]);
        // Fallback for LEFT/RIGHT: if spatial nav found nothing, try adjacent element
        // in the same row (within 30px vertical tolerance)
        if (target < 0 && (keyCode === KEYCODE.DPAD_LEFT || keyCode === KEYCODE.DPAD_RIGHT)) {
          const step = keyCode === KEYCODE.DPAD_RIGHT ? 1 : -1;
          const nextIdx = this._focusIndex + step;
          if (nextIdx >= 0 && nextIdx < elements.length) {
            const curRect = elements[this._focusIndex].getBoundingClientRect();
            const nextRect = elements[nextIdx].getBoundingClientRect();
            const curCY = curRect.top + curRect.height / 2;
            const nextCY = nextRect.top + nextRect.height / 2;
            if (Math.abs(nextCY - curCY) < 30) {
              target = nextIdx;
            }
          }
        }
        if (target >= 0) {
          this._focusIndex = target;
          this._updateFocusHighlight();
        }
        break;
      }

      case KEYCODE.DPAD_CENTER:
      case KEYCODE.ENTER: {
        const el = elements[this._focusIndex];
        if (!el) break;

        // Toggles (.onboarding-toggle) and standalone checkboxes
        // (.onboarding-checkbox on the data-collection screen) are
        // both <input> elements but should TOGGLE on Enter, not focus
        // for keyboard input. Without the type==='checkbox' branch,
        // d-pad Enter on the consent checkboxes fell through to the
        // generic INPUT path and tried to bring up a keyboard, leaving
        // the pre-checked state un-changeable.
        const isCheckboxInput = el.tagName === 'INPUT' && el.type === 'checkbox';
        if (el.classList.contains('onboarding-toggle') || isCheckboxInput) {
          el.checked = !el.checked;
          el.dispatchEvent(new Event('change', { bubbles: true }));
        } else if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
          // Remove readonly and focus to trigger keyboard
          el.readOnly = false;
          el.focus();
        } else {
          el.click();
        }
        break;
      }

      case KEYCODE.BACK: {
        // If an input is actively focused (keyboard open), blur it
        const active = document.activeElement;
        if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA') &&
            this.overlay?.contains(active)) {
          active.readOnly = true;
          active.blur();
          return;
        }
        // Otherwise navigate back between screens
        this.handleBack();
        break;
      }
    }
  }

  /**
   * Handle back navigation between onboarding screens.
   * Called by kiosk-shell.js dashieHandleBack() and by _handleDpad(BACK).
   */
  handleBack() {
    console.log(TAG, 'Back from screen:', this._currentScreen);
    switch (this._currentScreen) {
      case 'ha-config':
      case 'custom-url-config':
        this._showWelcome();
        break;
      case 'data-collection':
        // Back to whichever config screen got us here — the custom-URL path has
        // no HA config to return to, and sending it there would strand the user
        // on a form for a product they did not choose.
        if (this.customUrlData) this._showCustomUrlConfig(this.customUrlData);
        else this._showHaConfig('', this.haConfigData);
        break;
      case 'data-collection-info':
        this._showDataCollection();
        break;
      case 'permissions':
        this._showDataCollection();
        break;
      case 'welcome':
      default:
        // On welcome screen, back triggers exit
        this._handleClose();
        break;
    }
  }

  _saveDataCollectionPreferences(perfEnabled, wakeEnabled) {
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (N) {
      if (N.setDashboardTelemetryEnabled) N.setDashboardTelemetryEnabled(perfEnabled);
      if (N.setSampleCollectionEnabled) N.setSampleCollectionEnabled(wakeEnabled);
    }
    // Also persist to settingsStore if available
    const store = window.settingsStore;
    if (store) {
      store.set('performance.dashboardTelemetryEnabled', perfEnabled);
      store.set('voice.sampleCollectionEnabled', wakeEnabled);
    }
    console.log(TAG, 'Data collection preferences saved', { perfEnabled, wakeEnabled });
  }

  _complete() {
    console.log(TAG, 'Onboarding complete');
    const N = typeof DashieNative !== 'undefined' ? DashieNative : null;
    if (N && N.onOnboardingComplete) {
      N.onOnboardingComplete();
    }
    this.destroy();
  }
}
