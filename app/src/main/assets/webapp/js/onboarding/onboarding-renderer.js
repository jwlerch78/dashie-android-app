/**
 * Onboarding Renderer
 * Builds DOM for each onboarding screen. Pure rendering — no state logic.
 *
 * Screens:
 * 1. Welcome + Network Scan
 * 2. HA Config (URL, toggles, API)
 * 3. Permission Prompt (Now / Later)
 * Post-onboarding tips (floating overlays):
 * - Swipe Tip (sidebar reveal + arrow)
 * - Control Center Tip (hamburger menu + arrow)
 */

import { getBrand } from '../brand.js';

// Inline SVGs for icons (avoids asset path issues in kiosk bundle)
const HA_LOGO_SVG = `<svg width="40" height="40" viewBox="0 0 240 240" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M229.39 109.153L130.61 10.3725C124.78 4.5425 115.23 4.5425 109.4 10.3725L10.61 109.153C4.78 114.983 0 126.512 0 134.762V224.762C0 233.012 6.75 239.762 15 239.762H107.27L66.64 199.132C64.55 199.852 62.32 200.262 60 200.262C48.7 200.262 39.5 191.062 39.5 179.762C39.5 168.462 48.7 159.262 60 159.262C71.3 159.262 80.5 168.462 80.5 179.762C80.5 182.092 80.09 184.322 79.37 186.412L111 218.042V102.162C104.2 98.8225 99.5 91.8425 99.5 83.7725C99.5 72.4725 108.7 63.2725 120 63.2725C131.3 63.2725 140.5 72.4725 140.5 83.7725C140.5 91.8425 135.8 98.8225 129 102.162V183.432L160.46 151.972C159.84 150.012 159.5 147.932 159.5 145.772C159.5 134.472 168.7 125.272 180 125.272C191.3 125.272 200.5 134.472 200.5 145.772C200.5 157.072 191.3 166.272 180 166.272C177.5 166.272 175.12 165.802 172.91 164.982L129 208.892V239.772H225C233.25 239.772 240 233.022 240 224.772V134.772C240 126.522 235.23 115.002 229.39 109.162V109.153Z" fill="#18bcf2"/>
</svg>`;

const GRID_ICON_SVG = `<svg width="24" height="24" viewBox="0 0 24 24" fill="currentColor" xmlns="http://www.w3.org/2000/svg">
  <rect x="2" y="2" width="9" height="9" rx="2"/>
  <rect x="13" y="2" width="9" height="4" rx="1.5"/>
  <rect x="13" y="8" width="9" height="3" rx="1.5"/>
  <rect x="2" y="13" width="4" height="4" rx="1"/>
  <rect x="7" y="13" width="4" height="4" rx="1"/>
  <rect x="2" y="18.5" width="4" height="3.5" rx="1"/>
  <rect x="7" y="18.5" width="4" height="3.5" rx="1"/>
  <rect x="13" y="13" width="9" height="9" rx="2"/>
</svg>`;

// Multicolor Google "G" — matches the Sign in with Google button on the
// web login page (login/index.html) so the onboarding card reads the same.
const GOOGLE_ICON_SVG = `<svg width="40" height="40" viewBox="0 0 48 48" xmlns="http://www.w3.org/2000/svg">
  <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
  <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
  <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
  <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
</svg>`;

const HA_LOGO_LARGE_SVG = `<svg width="64" height="64" viewBox="0 0 240 240" fill="none" xmlns="http://www.w3.org/2000/svg">
  <path d="M229.39 109.153L130.61 10.3725C124.78 4.5425 115.23 4.5425 109.4 10.3725L10.61 109.153C4.78 114.983 0 126.512 0 134.762V224.762C0 233.012 6.75 239.762 15 239.762H107.27L66.64 199.132C64.55 199.852 62.32 200.262 60 200.262C48.7 200.262 39.5 191.062 39.5 179.762C39.5 168.462 48.7 159.262 60 159.262C71.3 159.262 80.5 168.462 80.5 179.762C80.5 182.092 80.09 184.322 79.37 186.412L111 218.042V102.162C104.2 98.8225 99.5 91.8425 99.5 83.7725C99.5 72.4725 108.7 63.2725 120 63.2725C131.3 63.2725 140.5 72.4725 140.5 83.7725C140.5 91.8425 135.8 98.8225 129 102.162V183.432L160.46 151.972C159.84 150.012 159.5 147.932 159.5 145.772C159.5 134.472 168.7 125.272 180 125.272C191.3 125.272 200.5 134.472 200.5 145.772C200.5 157.072 191.3 166.272 180 166.272C177.5 166.272 175.12 165.802 172.91 164.982L129 208.892V239.772H225C233.25 239.772 240 233.022 240 224.772V134.772C240 126.522 235.23 115.002 229.39 109.162V109.153Z" fill="#18bcf2"/>
</svg>`;

/**
 * The brand mark, as an `<img>` tag string.
 *
 * Every brand's mark is now drawn for the card's dark #1C1C1E, so there is no plate branch —
 * see the mark-slot note in `brand.js`.
 */
function brandLogoImg(brand, extraClass = '') {
  const cls = extraClass ? `onboarding-logo ${extraClass}` : 'onboarding-logo';
  return `<img src="${brand.logo}" alt="${escapeHtml(brand.name)}" class="${cls}" />`;
}

/**
 * Render a generic loading card — brand logo + spinner + status line.
 * Shown briefly while the network scan runs. Modeled after the loading
 * card on the login page for visual consistency.
 *
 * @param {object} [opts]
 * @param {string} [opts.message] - status text under the spinner
 * @returns {HTMLElement}
 */
export function renderLoading({ message = 'Setting things up...' } = {}) {
  const brand = getBrand();
  const card = document.createElement('div');
  card.className = 'onboarding-card onboarding-card--welcome';
  card.innerHTML = `
    <div class="onboarding-welcome-main" style="align-items: center; justify-content: center; gap: 20px;">
      ${brandLogoImg(brand)}
      <div class="onboarding-spinner" style="width: 48px; height: 48px;"></div>
      <div class="onboarding-subtitle">${escapeHtml(message)}</div>
    </div>
  `;
  return card;
}

/**
 * "192.168.1.116:8123" from a base URL — what a user recognises as "their box".
 * Returns '' for anything unparseable so callers can treat it as "not configured".
 */
function hostLabel(url) {
  if (!url) return '';
  try {
    const u = new URL(url);
    return u.port ? `${u.hostname}:${u.port}` : u.hostname;
  } catch (e) {
    return String(url).replace(/^https?:\/\//, '').replace(/\/+$/, '');
  }
}

/**
 * Do two HA references point at the same box?
 *
 * ⚠️ Compares HOST ONLY, deliberately. The configured value is a full base URL
 * while a scan hit is an ip:port, and they legitimately differ in scheme, in
 * trailing slash, and in whether the default port is spelled out. Comparing
 * strings would report "different box" for two spellings of one box — a false
 * alarm in the exact banner whose job is to be trustworthy about which box it
 * means.
 */
function sameHaTarget(a, b) {
  const ha = hostLabel(a).split(':')[0];
  const hb = hostLabel(b).split(':')[0];
  return !!ha && ha === hb;
}

/**
 * Render the Welcome screen (Screen 1).
 *
 * Two layouts depending on scan results, and — in an account-free edition
 * (`brand.hasAccounts === false`) — only the HA half of either:
 *   HA detected: HA primary, "Use <brand> without HA" secondary.
 *   HA not detected: "Sign in with Google" + "Sign up for <brand>" primary,
 *     "Use <brand> with HA" secondary.
 * While scanning, render the HA-detected layout with an info status.
 *
 * @param {object} opts
 * @param {object|null} opts.scanResults - {ha: [{ip, port, name, url}]} or null while scanning
 * @param {boolean} opts.scanning - true while scan in progress
 * @param {object|null} [opts.configuredHa] - the HA this device is ALREADY configured against
 *   ({url}), from `DashieNative.getHaConnectionSettings()`. Outranks anything the scan found —
 *   see the discovery-vs-configuration note in the body.
 * @param {function} opts.onHaPath - callback when user chooses HA path (detected or via "Use with HA")
 * @param {function} opts.onStandalonePath - callback when user picks "Use Dashie without HA"
 * @param {function} opts.onCreateAccount - callback when user picks "Create a Dashie Account"
 * @param {function} opts.onSignIn - callback when user picks "I already have a Dashie Account"
 * @returns {HTMLElement}
 */
export function renderWelcome({ scanResults, scanning, configuredHa, onHaPath, onStandalonePath, onCustomUrlPath, onCreateAccount, onSignIn, onClose }) {
  const brand = getBrand();
  const card = document.createElement('div');
  card.className = 'onboarding-card onboarding-card--welcome';

  const haInst = scanResults?.ha?.[0] || null;
  const haFound = !scanning && haInst;

  // Status row — only shown while scanning or when HA found.
  // When HA isn't detected we drop the gray box entirely and lead with
  // Dashie account buttons.
  //
  // 🔴 DISCOVERY IS A GUESS; CONFIGURATION IS A FACT. Do not let them render
  // with the same weight (T cont.55, proved across three repoints): a Samsung
  // CONFIGURED at .116 — a host-forwarded qemu VM, which advertises no
  // mDNS/SSDP and therefore cannot be discovered — was offered `.72`, an
  // unrelated Termux box, in a line that reads like a setting. Repointed at a
  // box that DOES advertise, the banner agreed again. So the failure is not
  // random: it is systematic for every non-advertising HA, which includes any
  // VM, any reverse-proxied or remote box, and anything on another subnet.
  //
  // The rule this encodes: when we already KNOW the configured box, say that
  // first and label the scan result as an alternative. Only when there is
  // nothing configured does a scan hit lead — and even then it is worded as a
  // find on the network, not as this device's Home Assistant.
  const configuredUrl = configuredHa?.url || '';
  const configuredHost = hostLabel(configuredUrl);
  const detectedHost = haInst ? `${haInst.ip}:${haInst.port}` : '';
  const detectedDiffers = !!(configuredHost && detectedHost && !sameHaTarget(configuredUrl, haInst?.url || detectedHost));

  let scanText = '';
  if (scanning) {
    scanText = `<div class="onboarding-scan-status"><div class="onboarding-spinner"></div> Scanning your network...</div>`;
  } else if (configuredHost) {
    const alt = detectedDiffers
      ? `<div class="onboarding-scan-alt">Also found ${escapeHtml(detectedHost)} on your network — a different box.</div>`
      : '';
    scanText = `<div class="onboarding-scan-status onboarding-scan-found-box">Using your configured Home Assistant at ${escapeHtml(configuredHost)}.</div>${alt}`;
  } else if (haFound) {
    scanText = `<div class="onboarding-scan-status onboarding-scan-found-box">Found a Home Assistant on your network at ${escapeHtml(detectedHost)}.</div>`;
  }

  // What the HA button carries forward. A CONFIGURED box always wins over a
  // scan hit — renderHaConfig already prefers savedConfig over prefilledUrl,
  // so this is belt-and-braces, but it keeps the two screens telling the user
  // the same story instead of one saying .116 and the other .72.
  const haUrl = configuredUrl || haInst?.url || null;

  // A device with HA already configured is NOT a device with no HA, even when
  // the scan comes back empty — which is the normal case for a non-advertising
  // box. Without `!configuredUrl` here, T's VM-configured Samsung would be
  // offered the "no Home Assistant found → create an account" layout while it
  // was, at that moment, pointed at a working HA.
  const haNotDetected = !scanning && !haInst && !configuredUrl;

  // Whether this screen may offer a Dashie account at all. TWO independent
  // questions, ANDed — do not collapse them:
  //
  //  1. brand.hasAccounts — does the EDITION have accounts? Chickadee is
  //     account-free by construction, so "Create an account" describes a
  //     thing that does not exist. Structural, and the reason this gate is
  //     no longer bridge-only.
  //  2. isDashieAccountEnabled — the pre-existing intent to hide account
  //     paths on the prod APK while accounts were staging-only.
  //
  // ⚠️ (2) has NO Kotlin implementation — verified 2026-08-01 by grepping the
  // whole android repo for `isDashieAccountEnabled`: zero hits outside the
  // built bundles. So it has always fallen open to `true` and the prod
  // gating it describes has never once happened. Kept, feature-detected and
  // documented rather than deleted, because it is someone's unfinished
  // intent and quietly dropping it would be the silent-drop failure; but it
  // must not be mistaken for a working gate. Reported to Thread A status
  // 2026-08-01 as a Dashie-side authored-but-unreached site.
  const bridgeAllowsAccounts = (() => {
    try {
      const fn = window.DashieNative?.isDashieAccountEnabled;
      return typeof fn === 'function' ? !!fn.call(window.DashieNative) : true;
    } catch (e) {
      return true;
    }
  })();
  const dashieAccountEnabled = brand.hasAccounts && bridgeAllowsAccounts;

  // Path buttons differ between the two layouts. While scanning we show the
  // HA-first layout (most users have HA); the buttons re-render when scan
  // completes.
  let pathButtons;
  if (haNotDetected) {
    if (dashieAccountEnabled) {
      // Account options mirror the web login "Sign in to Dashie" screen:
      // primary "Sign in with Google", divider, secondary "Sign up for
      // Dashie". Button ids are kept (ob-signin-btn / ob-create-btn) so the
      // existing click wiring and actions are unchanged.
      pathButtons = `
        <button class="onboarding-path-btn primary" id="ob-signin-btn">
          <span class="onboarding-path-icon">${GOOGLE_ICON_SVG}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Sign in with Google</span>
          </span>
        </button>

        <div class="onboarding-divider"><span>or</span></div>

        <button class="onboarding-path-btn secondary" id="ob-create-btn">
          <span class="onboarding-path-icon onboarding-path-icon--grid">${GRID_ICON_SVG}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Sign up for ${escapeHtml(brand.name)}</span>
            <span class="onboarding-path-desc">Calendar, photos, family sharing, and more</span>
          </span>
        </button>

        <div class="onboarding-divider"><span>or</span></div>

        <button class="onboarding-path-btn secondary" id="ob-ha-btn">
          <span class="onboarding-path-icon">${HA_LOGO_SVG}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Use ${escapeHtml(brand.name)} with Home Assistant</span>
            <span class="onboarding-path-desc">Connect manually if Home Assistant isn't on this network</span>
          </span>
        </button>
      `;
    } else {
      // Account-disabled prod build — HA-only path. Promote HA to primary
      // since it's the only option the user can take from here.
      pathButtons = `
        <button class="onboarding-path-btn primary" id="ob-ha-btn">
          <span class="onboarding-path-icon">${HA_LOGO_SVG}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Connect to Home Assistant</span>
            <span class="onboarding-path-desc">Enter your Home Assistant address manually</span>
          </span>
        </button>
      `;
    }
  } else {
    pathButtons = `
      <button class="onboarding-path-btn primary" id="ob-ha-btn">
        <span class="onboarding-path-icon">${HA_LOGO_SVG}</span>
        <span class="onboarding-path-text">
          <span class="onboarding-path-label">${haUrl ? 'Configure Home Assistant' : 'Connect to Home Assistant'} or Host Your Own Dashboard</span>
          <span class="onboarding-path-desc">Display dashboards & enable voice control for a smarter smart home</span>
        </span>
      </button>
    `;
    if (dashieAccountEnabled) {
      pathButtons += `
        <div class="onboarding-divider"><span>or</span></div>

        <button class="onboarding-path-btn secondary" id="ob-standalone-btn">
          <span class="onboarding-path-icon onboarding-path-icon--grid">${GRID_ICON_SVG}</span>
          <span class="onboarding-path-text">
            <span class="onboarding-path-label">Use ${escapeHtml(brand.name)} without Home Assistant</span>
            <span class="onboarding-path-desc">Calendar, photos, family sharing, and more</span>
          </span>
        </button>
      `;
    }
  }

  // Custom-URL path: NO chooser button any more (John, 2026-08-17). It sat
  // beside "Use <brand> without Home Assistant" with the same grid icon and the
  // two were visually indistinguishable — and it is a minority case. It now
  // lives as an alt-path link on the HA config screen (renderHaConfig's
  // onCustomUrl), which is why the HA button above says "…or Host Your Own
  // Dashboard". The welcome card's #ob-custom-url-btn wiring below stays
  // null-guarded so this removal is inert there.

  // Legal footer only where the brand HAS published pages. Chickadee's
  // (`brand.legal === null`) are Thread C's to publish; linking invented URLs
  // would put a 404 on the very first screen a beta tester sees, which is
  // worse than no link. Rendering nothing is deliberate, not forgotten.
  const legalFooter = brand.legal ? `
    <div class="onboarding-footer">
      <div class="onboarding-legal-links">
        <a href="${brand.legal.privacyUrl}" target="_blank" rel="noopener noreferrer">Privacy Policy</a>
        <span class="onboarding-legal-sep">&bull;</span>
        <a href="${brand.legal.termsUrl}" target="_blank" rel="noopener noreferrer">Terms of Service</a>
      </div>
    </div>
  ` : '';

  card.innerHTML = `
    <button class="onboarding-close-btn" id="ob-close-btn" aria-label="Close">&times;</button>
    <div class="onboarding-welcome-main">
      <!-- No "Welcome to <brand>!" line here (John, 2026-08-05): the mark IS the greeting, at
           double size. The alt text still carries the brand name, so a screen reader announces
           the card exactly as before — dropping the heading must not drop the only accessible
           statement of what this app is. -->
      ${brandLogoImg(brand, 'onboarding-logo--hero')}
      ${scanText}

      <div class="onboarding-path-buttons">
        ${pathButtons}
      </div>
    </div>

    ${legalFooter}
  `;

  // Wire up handlers — only the buttons that exist in the chosen layout.
  const haBtn = card.querySelector('#ob-ha-btn');
  if (haBtn) haBtn.addEventListener('click', () => onHaPath(haUrl));

  const standaloneBtn = card.querySelector('#ob-standalone-btn');
  if (standaloneBtn) standaloneBtn.addEventListener('click', () => onStandalonePath && onStandalonePath());

  const customUrlBtn = card.querySelector('#ob-custom-url-btn');
  if (customUrlBtn) customUrlBtn.addEventListener('click', () => onCustomUrlPath && onCustomUrlPath());

  const createBtn = card.querySelector('#ob-create-btn');
  if (createBtn) createBtn.addEventListener('click', () => onCreateAccount && onCreateAccount());

  const signinBtn = card.querySelector('#ob-signin-btn');
  if (signinBtn) signinBtn.addEventListener('click', () => onSignIn && onSignIn());

  card.querySelector('#ob-close-btn').addEventListener('click', () => {
    if (onClose) onClose();
  });

  return card;
}

/**
 * Render the HA Config screen (Screen 2).
 * @param {object} opts
 * @param {string} opts.prefilledUrl - pre-filled HA URL from scan (base URL, e.g. "http://192.168.1.100:8123")
 * @param {object} [opts.savedConfig] - previously entered config to restore (url, dashboardPath, hideSidebar, hideTabs, apiEnabled, apiPort, apiPassword)
 * @param {function} opts.onConnect - callback({url, dashboardPath, hideSidebar, hideTabs, apiEnabled, apiPort, apiPassword})
 * @param {function} opts.onBack - go back to welcome
 * @returns {HTMLElement}
 */
export function renderHaConfig({ prefilledUrl, savedConfig, onConnect, onBack, onCustomUrl }) {
  const card = document.createElement('div');
  card.className = 'onboarding-card';

  // Restore from saved config if returning from login, otherwise use scan-detected URL
  const baseUrl = savedConfig?.url || prefilledUrl || '';
  const dashPath = savedConfig?.dashboardPath || '';
  const hideSidebar = savedConfig ? savedConfig.hideSidebar : true;
  const hideTabs = savedConfig ? savedConfig.hideTabs : false;
  const apiEnabled = savedConfig ? savedConfig.apiEnabled : false;
  const apiPort = savedConfig?.apiPort || 2323;
  const apiPassword = savedConfig?.apiPassword || '';
  // Auto-build defaults ON — both for detected URLs (where we have a value
  // to prefill) and for the empty manual-config path (most users want to
  // type just the IP/host and let Dashie fill in the protocol/port).
  // Falls back to the saved value if the user toggled it off and came
  // back to this screen.
  const autoBuildOn = savedConfig ? savedConfig.autoBuild !== false : true;

  // 🔴 The badge and the URL field can name DIFFERENT boxes, and the badge is
  // the one that sounds authoritative. `baseUrl` above prefers savedConfig, so
  // on a device configured against a non-advertising HA the field correctly
  // holds .116 while the scan handed us .72 — and the old badge said, flatly,
  // "Detected at .72" beside it (T cont.55). Say which is which when they
  // disagree; keep the short form when they agree.
  const hasDetected = !!prefilledUrl;
  const detectedBadge = !hasDetected
    ? ''
    : (savedConfig?.url && !sameHaTarget(savedConfig.url, prefilledUrl))
      ? `<span class="onboarding-detected-badge onboarding-detected-badge--mismatch">Scan found a different box at ${escapeHtml(hostLabel(prefilledUrl))} — the address above is the one you configured</span>`
      : `<span class="onboarding-detected-badge">Detected at ${escapeHtml(prefilledUrl)}</span>`;

  card.innerHTML = `
    <div class="onboarding-ha-config-header">
      <div class="onboarding-ha-logo-inline">${HA_LOGO_LARGE_SVG}</div>
      <div class="onboarding-title" style="margin-bottom: 0;">Configure Home Assistant</div>
    </div>

    <!-- Auto-Build URL toggle row -->
    <div class="onboarding-auto-build-row">
      <input type="checkbox" class="onboarding-toggle" id="ob-auto-build" ${autoBuildOn ? 'checked' : ''} />
      <span class="onboarding-toggle-label">Auto-Build URL</span>
      ${detectedBadge}
    </div>

    <!-- Auto-Build fields (base URL + dashboard path side by side) -->
    <div id="ob-auto-build-section" style="${autoBuildOn ? '' : 'display: none;'}">
      <div class="onboarding-url-row">
        <div class="onboarding-form-group onboarding-url-base">
          <label class="onboarding-form-label">Base URL</label>
          <input class="onboarding-input" id="ob-ha-base-url" type="url"
                 placeholder="192.168.1.100:8123"
                 value="${baseUrl}" autocomplete="off" />
        </div>
        <div class="onboarding-form-group onboarding-url-path">
          <label class="onboarding-form-label">Dashboard Path</label>
          <input class="onboarding-input" id="ob-ha-dashboard" type="text"
                 placeholder="e.g. kitchen" value="${dashPath}"
                 autocomplete="off" autocapitalize="off" />
        </div>
      </div>
    </div>

    <!-- Manual URL field (single full URL) -->
    <div id="ob-manual-url-section" style="${autoBuildOn ? 'display: none;' : ''}">
      <div class="onboarding-form-group">
        <label class="onboarding-form-label">Full Dashboard URL</label>
        <div class="onboarding-form-hint">You can change this later in Settings.</div>
        <input class="onboarding-input" id="ob-ha-full-url" type="url"
               placeholder="http://192.168.1.100:8123/lovelace/0"
               value="" autocomplete="off" />
      </div>
    </div>

    <div class="onboarding-toggle-row">
      <span class="onboarding-toggle-label">Hide sidebar</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-hide-sidebar" ${hideSidebar ? 'checked' : ''} />
    </div>
    <div class="onboarding-toggle-row">
      <span class="onboarding-toggle-label">Hide header / tabs</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-hide-tabs" ${hideTabs ? 'checked' : ''} />
    </div>

    <div class="onboarding-toggle-row" style="border-bottom: none;">
      <span class="onboarding-toggle-label">Enable API</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-api-enabled" ${apiEnabled ? 'checked' : ''} />
    </div>
    <div id="ob-api-details" style="${apiEnabled ? '' : 'display: none;'}">
      <div class="onboarding-url-row">
        <div class="onboarding-form-group onboarding-url-base">
          <label class="onboarding-form-label">API Password</label>
          <input class="onboarding-input" id="ob-api-password" type="password"
                 placeholder="Optional" value="${apiPassword}" autocomplete="new-password" />
        </div>
        <div class="onboarding-form-group onboarding-url-path">
          <label class="onboarding-form-label">API Port</label>
          <input class="onboarding-input" id="ob-api-port" type="number"
                 value="${apiPort}" min="1024" max="65535" />
        </div>
      </div>
    </div>
  `;

  // Wire auto-build toggle to swap sections
  const autoBuildToggle = card.querySelector('#ob-auto-build');
  const autoBuildSection = card.querySelector('#ob-auto-build-section');
  const manualSection = card.querySelector('#ob-manual-url-section');
  autoBuildToggle.addEventListener('change', () => {
    const on = autoBuildToggle.checked;
    autoBuildSection.style.display = on ? '' : 'none';
    manualSection.style.display = on ? 'none' : '';
  });

  // Wire API toggle to show/hide details
  const apiToggle = card.querySelector('#ob-api-enabled');
  const apiDetails = card.querySelector('#ob-api-details');
  apiToggle.addEventListener('change', () => {
    apiDetails.style.display = apiToggle.checked ? '' : 'none';
    // Scroll the newly revealed section into view on small screens
    if (apiToggle.checked) {
      requestAnimationFrame(() => {
        apiDetails.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        // Some Android WebViews auto-focus the first input of a
        // newly-visible section (api-port is type=number → numeric IME
        // pops). Re-assert readonly on the just-revealed inputs and
        // blur whatever is currently focused so the soft keyboard
        // doesn't appear. The d-pad CENTER handler will lift readonly
        // when the user explicitly enters one of these fields.
        apiDetails.querySelectorAll('input').forEach(i => { i.readOnly = true; });
        if (document.activeElement && document.activeElement !== apiToggle && document.activeElement.blur) {
          document.activeElement.blur();
        }
        // Restore focus to the toggle so d-pad stays anchored.
        apiToggle.focus();
      });
    }
  });

  // Enter key on Dashboard Path: blur keyboard and move focus to next toggle
  const dashboardInput = card.querySelector('#ob-ha-dashboard');
  dashboardInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      dashboardInput.blur();
    }
  });

  // Button row (Back left, Connect right)
  const btnRow = document.createElement('div');
  btnRow.className = 'onboarding-btn-row';

  const backBtn = document.createElement('button');
  backBtn.className = 'onboarding-btn-back';
  backBtn.textContent = 'Back';
  backBtn.addEventListener('click', onBack);

  const connectBtn = document.createElement('button');
  connectBtn.className = 'onboarding-btn-primary';
  connectBtn.textContent = 'Connect';
  connectBtn.addEventListener('click', () => {
    const isAutoBuild = autoBuildToggle.checked;
    let url, dashboardPath;

    if (isAutoBuild) {
      url = card.querySelector('#ob-ha-base-url').value.trim();
      dashboardPath = card.querySelector('#ob-ha-dashboard').value.trim();
      if (!url) {
        card.querySelector('#ob-ha-base-url').focus();
        return;
      }
    } else {
      url = card.querySelector('#ob-ha-full-url').value.trim();
      dashboardPath = '';
      if (!url) {
        card.querySelector('#ob-ha-full-url').focus();
        return;
      }
    }

    // Normalize URL: fix common input errors
    url = normalizeHaUrl(url);

    // Validate URL before connecting
    try {
      const parsed = new URL(url);
      if (!parsed.hostname || !parsed.protocol.startsWith('http')) {
        throw new Error('Invalid URL');
      }
    } catch (e) {
      showUrlError(card, isAutoBuild ? '#ob-ha-base-url' : '#ob-ha-full-url',
        'Invalid URL — check the format (e.g. http://192.168.1.100:8123)');
      return;
    }

    onConnect({
      url,
      dashboardPath,
      autoBuild: isAutoBuild,
      hideSidebar: card.querySelector('#ob-hide-sidebar').checked,
      hideTabs: card.querySelector('#ob-hide-tabs').checked,
      apiEnabled: apiToggle.checked,
      apiPort: parseInt(card.querySelector('#ob-api-port').value) || 2323,
      apiPassword: card.querySelector('#ob-api-password').value
    });
  });

  btnRow.appendChild(backBtn);
  btnRow.appendChild(connectBtn);
  card.appendChild(btnRow);

  // Alt path BELOW the Back/Connect row (John, 2026-08-17): the custom-URL
  // option moved off the welcome chooser (it read identically to the
  // no-HA path there) and lives here as a quiet link — the chooser's HA
  // button says "…or Host Your Own Dashboard", this is where that lands.
  if (onCustomUrl) {
    const altPath = document.createElement('button');
    altPath.type = 'button';
    altPath.className = 'onboarding-alt-path';
    altPath.id = 'ob-host-other-btn';
    altPath.textContent = 'Host a different dashboard instead →';
    altPath.addEventListener('click', () => onCustomUrl());
    card.appendChild(altPath);
  }

  // Make text inputs readonly by default for d-pad navigation.
  // This prevents the virtual keyboard from appearing when d-pad moves to an input.
  // The d-pad CENTER/ENTER handler in OnboardingController removes readonly to trigger keyboard.
  // Touch/click also removes readonly so tapping a field opens the keyboard.
  card.querySelectorAll('input.onboarding-input').forEach(input => {
    input.readOnly = true;
    input.addEventListener('click', () => {
      input.readOnly = false;
      input.focus();
    });
  });

  return card;
}

/**
 * Render the Custom URL config screen — the non-HA sibling of [renderHaConfig].
 *
 * A deliberately SMALLER form, not a copy: it keeps the URL field and the API
 * block, and drops auto-build, dashboard path, hide-sidebar and hide-tabs. Those
 * four are Home-Assistant-chrome concepts — there is no Lovelace path to build
 * and no HA sidebar to hide on an arbitrary web page, so offering them would be
 * four controls that quietly do nothing.
 *
 * The caller is responsible for the part that actually differs: this path must
 * NOT trigger an HA login afterwards (see OnboardingController._applyCustomUrlConfig).
 *
 * @param {object} opts
 * @param {object} [opts.savedConfig] - restore values ({url, apiEnabled, apiPort, apiPassword})
 * @param {function} opts.onConnect - callback({url, apiEnabled, apiPort, apiPassword})
 * @param {function} opts.onBack
 */
export function renderCustomUrlConfig({ savedConfig, onConnect, onBack }) {
  const card = document.createElement('div');
  card.className = 'onboarding-card';

  const url = savedConfig?.url || '';
  const apiEnabled = savedConfig ? savedConfig.apiEnabled : false;
  const apiPort = savedConfig?.apiPort || 2323;
  const apiPassword = savedConfig?.apiPassword || '';

  card.innerHTML = `
    <div class="onboarding-title">Use another dashboard</div>
    <div class="onboarding-subtitle">Enter the address of the page you want to display.</div>

    <div class="onboarding-form-group">
      <label class="onboarding-form-label">Dashboard URL</label>
      <div class="onboarding-form-hint">You can change this later in Settings.</div>
      <input class="onboarding-input" id="ob-custom-full-url" type="url"
             placeholder="https://example.com/dashboard"
             value="${escapeHtml(url)}" autocomplete="off" />
    </div>

    <div class="onboarding-toggle-row" style="border-bottom: none;">
      <span class="onboarding-toggle-label">Enable API</span>
      <input type="checkbox" class="onboarding-toggle" id="ob-api-enabled" ${apiEnabled ? 'checked' : ''} />
    </div>
    <div id="ob-api-details" style="${apiEnabled ? '' : 'display: none;'}">
      <div class="onboarding-url-row">
        <div class="onboarding-form-group onboarding-url-base">
          <label class="onboarding-form-label">API Password</label>
          <input class="onboarding-input" id="ob-api-password" type="password"
                 placeholder="Optional" value="${escapeHtml(apiPassword)}" autocomplete="new-password" />
        </div>
        <div class="onboarding-form-group onboarding-url-path">
          <label class="onboarding-form-label">API Port</label>
          <input class="onboarding-input" id="ob-api-port" type="number"
                 value="${apiPort}" min="1024" max="65535" />
        </div>
      </div>
    </div>
  `;

  // Same reveal behaviour as the HA screen, including the readonly re-assert:
  // some Android WebViews auto-focus the first input of a newly-visible section
  // and pop the numeric IME. See renderHaConfig for the full rationale.
  const apiToggle = card.querySelector('#ob-api-enabled');
  const apiDetails = card.querySelector('#ob-api-details');
  apiToggle.addEventListener('change', () => {
    apiDetails.style.display = apiToggle.checked ? '' : 'none';
    if (apiToggle.checked) {
      requestAnimationFrame(() => {
        apiDetails.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
        apiDetails.querySelectorAll('input').forEach(i => { i.readOnly = true; });
        if (document.activeElement && document.activeElement !== apiToggle && document.activeElement.blur) {
          document.activeElement.blur();
        }
      });
    }
  });

  const btnRow = document.createElement('div');
  btnRow.className = 'onboarding-btn-row';

  // Same classes as renderHaConfig's row — the page previously used
  // 'onboarding-btn secondary/primary', classes that exist in NO stylesheet,
  // so Back/Continue rendered as unstyled browser defaults (John's 08-17
  // screenshot). Consistency here IS the fix.
  const backBtn = document.createElement('button');
  backBtn.className = 'onboarding-btn-back';
  backBtn.id = 'ob-custom-back-btn';
  backBtn.textContent = 'Back';
  backBtn.addEventListener('click', () => onBack && onBack());

  const connectBtn = document.createElement('button');
  connectBtn.className = 'onboarding-btn-primary';
  connectBtn.id = 'ob-custom-connect-btn';
  connectBtn.textContent = 'Continue';
  connectBtn.addEventListener('click', () => {
    const input = card.querySelector('#ob-custom-full-url');
    let value = normalizeHaUrl(input.value.trim());
    if (!value) { input.focus(); return; }
    try {
      const parsed = new URL(value);
      if (!parsed.hostname || !parsed.protocol.startsWith('http')) throw new Error('Invalid URL');
    } catch (e) {
      showUrlError(card, '#ob-custom-full-url',
        'Invalid URL — check the format (e.g. https://example.com/dashboard)');
      return;
    }
    onConnect({
      url: value,
      apiEnabled: apiToggle.checked,
      apiPort: parseInt(card.querySelector('#ob-api-port').value) || 2323,
      apiPassword: card.querySelector('#ob-api-password').value
    });
  });

  btnRow.appendChild(backBtn);
  btnRow.appendChild(connectBtn);
  card.appendChild(btnRow);

  // Readonly-by-default for d-pad nav — identical to the HA screen.
  card.querySelectorAll('input.onboarding-input').forEach(input => {
    input.readOnly = true;
    input.addEventListener('click', () => {
      input.readOnly = false;
      input.focus();
    });
  });

  return card;
}

/**
 * Render the Permission Prompt screen (shown after successful HA login).
 * Asks if the user wants to grant device permissions now or later.
 * @param {object} opts
 * @param {function} opts.onGrantNow - callback to request all permissions
 * @param {function} opts.onLater - callback to skip to Get Started
 * @returns {HTMLElement}
 */
export function renderPermissionPrompt({ onGrantNow, onLater }) {
  const brand = getBrand();
  const card = document.createElement('div');
  card.className = 'onboarding-card';

  // Detect TV devices — Screen Off and Brightness aren't relevant on TV/streaming sticks
  let isTv = false;
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.getDeviceInfo) {
      const info = JSON.parse(DashieNative.getDeviceInfo());
      isTv = info.isTv || info.isFireTV || false;
    }
  } catch (e) { /* ignore */ }

  const permissions = [
    'Camera — motion detection & video streaming',
    'Microphone — voice commands & video streaming',
    ...(!isTv ? ['Screen Off — enable screen to power off (device admin)'] : []),
    'Exact Timers — reliable sleep and wake times',
    'Battery Optimization — keep running in the background without being paused',
    ...(!isTv ? ['Brightness — allow control of screen brightness (change system settings)'] : []),
    'Auto-Relaunch — restart app after forced shutdown (appear on top)',
  ];

  card.innerHTML = `
    <div class="onboarding-title">Device Permissions</div>
    <div class="onboarding-subtitle">${escapeHtml(brand.name)} works best with a few permissions for voice control, screen management, and timers.</div>

    <ul class="onboarding-perm-summary">
      ${permissions.map(p => `<li>${p}</li>`).join('\n      ')}
    </ul>
  `;

  // Button row (Later left, Set Up Now right)
  const btnRow = document.createElement('div');
  btnRow.className = 'onboarding-btn-row';

  const laterBtn = document.createElement('button');
  laterBtn.className = 'onboarding-btn-back';
  laterBtn.textContent = 'Later';
  laterBtn.addEventListener('click', onLater);

  const nowBtn = document.createElement('button');
  nowBtn.className = 'onboarding-btn-primary';
  nowBtn.textContent = 'Set Up Now';
  nowBtn.addEventListener('click', onGrantNow);

  btnRow.appendChild(laterBtn);
  btnRow.appendChild(nowBtn);
  card.appendChild(btnRow);

  const tip = document.createElement('div');
  tip.className = 'onboarding-tip';
  tip.textContent = 'You can grant permissions later in Settings';
  card.appendChild(tip);

  return card;
}

/**
 * Render the Data Collection opt-out screen (shown after HA login, before permissions).
 * Two pre-checked checkboxes for performance data & wake word sample sharing.
 * @param {object} opts
 * @param {function} opts.onContinue - callback({ perfEnabled, wakeEnabled })
 * @param {function} opts.onLearnMore - callback to show info view
 * @returns {HTMLElement}
 */
export function renderDataCollection({ onContinue, onLearnMore }) {
  const brand = getBrand();
  const card = document.createElement('div');
  card.className = 'onboarding-card';

  // Same rule as the welcome footer: link the Privacy Policy only where one
  // is published. The "Learn how we use your data" link is in-app and always
  // renders, so the row never collapses to nothing.
  const privacyLink = brand.legal
    ? `<a href="${brand.legal.privacyUrl}" target="_blank" rel="noopener noreferrer">Privacy Policy</a>
      <span class="onboarding-data-links-sep">&bull;</span>`
    : '';

  card.innerHTML = `
    <div class="onboarding-title">Data Collection</div>
    <div class="onboarding-subtitle">Help improve ${escapeHtml(brand.name)} by sharing anonymous usage data. You can change these settings at any time.</div>

    <div class="onboarding-optout-row">
      <input type="checkbox" class="onboarding-checkbox" id="ob-perf-checkbox" checked>
      <div>
        <div class="onboarding-optout-text">Share Performance Data</div>
        <div class="onboarding-optout-desc">Anonymous metrics to help us fix crashes and improve stability</div>
      </div>
    </div>

    <div class="onboarding-optout-row">
      <input type="checkbox" class="onboarding-checkbox" id="ob-wake-checkbox" checked>
      <div>
        <div class="onboarding-optout-text">Share Wake Word Samples</div>
        <div class="onboarding-optout-desc">Short audio clips to improve voice detection accuracy</div>
      </div>
    </div>

    <div class="onboarding-data-links">
      ${privacyLink}
      <a href="#" id="ob-learn-more">Learn how we use your data to improve ${escapeHtml(brand.name)}</a>
    </div>
  `;

  const continueBtn = document.createElement('button');
  continueBtn.className = 'onboarding-btn-primary';
  continueBtn.textContent = 'Continue';
  continueBtn.addEventListener('click', () => {
    const perfEnabled = card.querySelector('#ob-perf-checkbox').checked;
    const wakeEnabled = card.querySelector('#ob-wake-checkbox').checked;
    onContinue({ perfEnabled, wakeEnabled });
  });
  card.appendChild(continueBtn);

  const tip = document.createElement('div');
  tip.className = 'onboarding-tip';
  tip.textContent = 'You can change these anytime in Settings';
  card.appendChild(tip);

  card.querySelector('#ob-learn-more').addEventListener('click', (e) => {
    e.preventDefault();
    onLearnMore();
  });

  return card;
}

/**
 * Render the Data Collection info view explaining how data is used.
 * @param {object} opts
 * @param {function} opts.onBack - callback to return to data collection screen
 * @returns {HTMLElement}
 */
export function renderDataCollectionInfo({ onBack }) {
  const brand = getBrand();
  const card = document.createElement('div');
  card.className = 'onboarding-card';

  card.innerHTML = `
    <div class="onboarding-title">How We Use Your Data</div>

    <div class="onboarding-data-info-section">
      <div class="onboarding-data-info-heading">Performance Data</div>
      <div class="onboarding-data-info-text">
        When enabled, ${escapeHtml(brand.name)} collects anonymous performance metrics such as memory usage,
        refresh timing, and WebSocket stability. This data helps us identify crashes,
        optimize resource usage, and ensure ${escapeHtml(brand.name)} runs smoothly on every device.
        No personal information, photos, or calendar data is ever included.
      </div>
    </div>

    <div class="onboarding-data-info-section">
      <div class="onboarding-data-info-heading">Wake Word Samples</div>
      <div class="onboarding-data-info-text">
        When enabled, short audio clips captured during wake word detection are shared
        with us to improve voice recognition accuracy. These clips are typically 1–2 seconds
        long and contain only the wake word trigger. They are used exclusively to train
        and refine the on-device wake word model so it works better for everyone.
      </div>
    </div>

    <div class="onboarding-data-info-section">
      <div class="onboarding-data-info-heading">Your Control</div>
      <div class="onboarding-data-info-text">
        Both of these can be turned off at any time in Settings. Performance data sharing
        is found under System &gt; Performance &amp; Diagnostics, and wake word sample
        sharing is under AI &amp; Voice &gt; Wake Word Collection.
      </div>
    </div>
  `;

  const backBtn = document.createElement('button');
  backBtn.className = 'onboarding-btn-primary';
  backBtn.textContent = 'Back';
  backBtn.addEventListener('click', onBack);
  card.appendChild(backBtn);

  return card;
}

/**
 * Render the Swipe Tip floating card (shown after onboarding completes).
 * Floats next to the revealed sidebar with an arrow pointing at it.
 * @param {object} opts
 * @param {function} opts.onGotIt - callback when user taps "Got it"
 * @returns {HTMLElement}
 */
export function renderSwipeTip({ onGotIt }) {
  const overlay = document.createElement('div');
  overlay.className = 'onboarding-tip-overlay';

  // Detect TV for appropriate tip text
  let isTv = false;
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.getDeviceInfo) {
      const info = JSON.parse(DashieNative.getDeviceInfo());
      isTv = info.isTv || info.isFireTV || false;
    }
  } catch (e) { /* ignore */ }

  const tipText = isTv
    ? 'Press the Back button on your remote to open the sidebar'
    : 'Swiping away from the left edge of the screen opens the sidebar';

  overlay.innerHTML = `
    <div class="onboarding-tip-card onboarding-tip-card--sidebar">
      <div class="onboarding-tip-arrow onboarding-tip-arrow--left"></div>
      <div class="onboarding-title" style="font-size: 20px; margin-bottom: 8px;">Quick Tip</div>
      <div class="onboarding-subtitle" style="margin-bottom: 20px;">${tipText}</div>
      <button class="onboarding-btn-primary" id="ob-tip-gotit">Got it</button>
    </div>
  `;

  overlay.querySelector('#ob-tip-gotit').addEventListener('click', onGotIt);
  return overlay;
}

/**
 * Render the Control Center Tip floating card (shown after sidebar tip).
 * Floats near the hamburger menu with an arrow pointing at Control Center.
 * @param {object} opts
 * @param {function} opts.onGoToControlCenter - open Control Center settings
 * @param {function} opts.onStart - dismiss and start using Dashie
 * @returns {HTMLElement}
 */
export function renderControlCenterTip({ onGoToControlCenter, onStart }) {
  const brand = getBrand();
  const overlay = document.createElement('div');
  overlay.className = 'onboarding-tip-overlay';

  overlay.innerHTML = `
    <div class="onboarding-tip-card onboarding-tip-card--menu">
      <div class="onboarding-tip-arrow onboarding-tip-arrow--left"></div>
      <div class="onboarding-title" style="font-size: 20px; margin-bottom: 8px;">Setting up ${escapeHtml(brand.name)}</div>
      <div class="onboarding-subtitle" style="margin-bottom: 20px;">You can configure everything in ${escapeHtml(brand.name)} from the Control Center, which you can reach from the sidebar</div>
      <div class="onboarding-btn-row">
        <button class="onboarding-btn-back" id="ob-tip-start">Start using ${escapeHtml(brand.name)}</button>
        <button class="onboarding-btn-primary" id="ob-tip-config">Go to Control Center</button>
      </div>
    </div>
  `;

  overlay.querySelector('#ob-tip-config').addEventListener('click', onGoToControlCenter);
  overlay.querySelector('#ob-tip-start').addEventListener('click', onStart);
  return overlay;
}

/** Escape HTML special characters */
function escapeHtml(str) {
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

/**
 * Normalize a Home Assistant URL to fix common input errors:
 * - Ensure http:// prefix if no protocol
 * - Fix period-instead-of-colon before port (e.g. 192.168.1.100.8123 → 192.168.1.100:8123)
 * - Remove trailing slashes
 */
function normalizeHaUrl(url) {
  // Add http:// if no protocol
  if (!/^https?:\/\//i.test(url)) {
    url = 'http://' + url;
  }
  // Fix IP.port pattern: 4 octets followed by .port (e.g. 192.168.1.50.8123)
  // The port is typically 4-5 digits after the last period
  url = url.replace(
    /^(https?:\/\/\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\.(\d{4,5})(\/|$)/,
    '$1:$2$3'
  );
  // Remove trailing slashes
  url = url.replace(/\/+$/, '');
  return url;
}

/** Show a validation error below a URL input field, auto-clears on input */
function showUrlError(card, inputSelector, message) {
  const input = card.querySelector(inputSelector);
  if (!input) return;
  // Remove existing error
  const existing = input.parentElement.querySelector('.onboarding-url-error');
  if (existing) existing.remove();
  // Add error message
  const err = document.createElement('div');
  err.className = 'onboarding-url-error';
  err.textContent = message;
  err.style.cssText = 'color: #FF6B6B; font-size: 12px; margin-top: 4px;';
  input.parentElement.appendChild(err);
  input.focus();
  // Clear on next input
  input.addEventListener('input', () => err.remove(), { once: true });
}
