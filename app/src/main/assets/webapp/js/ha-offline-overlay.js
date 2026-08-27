/**
 * HA Offline Overlay — shown when Home Assistant is unreachable.
 * Displays a styled "unavailable" message over the HA iframe area
 * and polls the HA URL until connectivity is restored.
 */

const POLL_INTERVAL_MS = 10000;
const POLL_TIMEOUT_MS = 5000;

let overlayEl = null;
let statusEl = null;
let pollTimer = null;
let onReconnect = null;

function createOverlay(haUrl) {
  const el = document.createElement('div');
  el.id = 'ha-offline-overlay';
  el.style.cssText = `
    position: fixed; inset: 0; z-index: 5;
    display: flex; flex-direction: column;
    align-items: center; justify-content: center;
    background: #111; color: #ccc;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  `;

  // Extract just the host:port for display
  let displayUrl = haUrl;
  try { displayUrl = new URL(haUrl).origin; } catch {}

  el.innerHTML = `
    <div style="text-align:center; max-width:400px; padding:24px;">
      <!-- HA logo with wifi-off badge (matches Kotlin-side indicator) -->
      <div style="position:relative; display:inline-block; margin-bottom:16px;">
        <svg width="64" height="64" viewBox="0 0 240 240" style="display:block;">
          <path d="M240,224.762C240,233.012 233.25,239.762 225,239.762H15C6.75,239.762 0,233.012 0,224.762V134.762C0,126.512 4.77,114.993 10.61,109.153L109.39,10.3725C115.22,4.5425 124.77,4.5425 130.6,10.3725L229.39,109.162C235.22,114.992 240,126.522 240,134.772V224.772V224.762Z" fill="#F2F4F9"/>
          <path d="M229.39,109.153L130.61,10.3725C124.78,4.5425 115.23,4.5425 109.4,10.3725L10.61,109.153C4.78,114.983 0,126.512 0,134.762V224.762C0,233.012 6.75,239.762 15,239.762H107.27L66.64,199.132C64.55,199.852 62.32,200.262 60,200.262C48.7,200.262 39.5,191.062 39.5,179.762C39.5,168.462 48.7,159.262 60,159.262C71.3,159.262 80.5,168.462 80.5,179.762C80.5,182.092 80.09,184.322 79.37,186.412L111,218.042V102.162C104.2,98.8225 99.5,91.8425 99.5,83.7725C99.5,72.4725 108.7,63.2725 120,63.2725C131.3,63.2725 140.5,72.4725 140.5,83.7725C140.5,91.8425 135.8,98.8225 129,102.162V183.432L160.46,151.972C159.84,150.012 159.5,147.932 159.5,145.772C159.5,134.472 168.7,125.272 180,125.272C191.3,125.272 200.5,134.472 200.5,145.772C200.5,157.072 191.3,166.272 180,166.272C177.5,166.272 175.12,165.802 172.91,164.982L129,208.892V239.772H225C233.25,239.772 240,233.022 240,224.772V134.772C240,126.522 235.23,115.002 229.39,109.162V109.153Z" fill="#18BCF2"/>
        </svg>
        <!-- Red wifi-off badge (top-right overlap) -->
        <div style="position:absolute; top:-4px; right:-4px; width:24px; height:24px; background:#DC3545; border-radius:50%; display:flex; align-items:center; justify-content:center;">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="white">
            <path d="M1,9l2,2c4.97-4.97 13.03-4.97 18,0l2-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3-3c-1.65-1.66-4.34-1.66-6,0zM5,13l2,2c2.76-2.76 7.24-2.76 10,0l2-2C15.14,9.14 8.87,9.14 5,13z"/>
            <line x1="3" y1="3" x2="21" y2="21" stroke="white" stroke-width="2.5" stroke-linecap="round"/>
          </svg>
        </div>
      </div>
      <h2 style="margin:0 0 8px; font-size:18px; font-weight:600; color:#eee;">
        Home Assistant Unavailable
      </h2>
      <p style="margin:0 0 16px; font-size:13px; color:#888; line-height:1.4;">
        Unable to connect to<br>
        <span style="color:#aaa; font-family:monospace; font-size:12px;">${displayUrl}</span>
      </p>
      <p id="ha-offline-status" style="margin:0; font-size:13px; color:#999;">
        Attempting to reconnect…
      </p>
    </div>
  `;

  return el;
}

function startPolling(haUrl) {
  stopPolling();
  let attempt = 0;

  pollTimer = setInterval(async () => {
    attempt++;
    if (statusEl) {
      statusEl.textContent = `Reconnecting… (attempt ${attempt})`;
    }

    try {
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(), POLL_TIMEOUT_MS);

      await fetch(haUrl, {
        mode: 'no-cors',
        cache: 'no-store',
        signal: controller.signal
      });

      clearTimeout(timeout);

      // fetch with no-cors resolves with opaque response on success
      console.log('[HaOffline] HA reachable — reconnecting');
      const callback = onReconnect;
      hide();
      callback?.();
    } catch {
      // Still offline
    }
  }, POLL_INTERVAL_MS);
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

/**
 * Show the offline overlay and start reconnect polling.
 * @param {string} haUrl - The HA URL to poll
 * @param {Function} reconnectCallback - Called when HA becomes reachable
 */
export function show(haUrl, reconnectCallback) {
  if (overlayEl) return; // Already showing

  console.log('[HaOffline] Showing offline overlay for:', haUrl);
  onReconnect = reconnectCallback;
  overlayEl = createOverlay(haUrl);
  document.body.appendChild(overlayEl);
  statusEl = document.getElementById('ha-offline-status');
  startPolling(haUrl);
}

/**
 * Hide the offline overlay and stop polling.
 */
export function hide() {
  stopPolling();
  if (overlayEl) {
    overlayEl.remove();
    overlayEl = null;
    statusEl = null;
  }
  onReconnect = null;
}

/**
 * Whether the overlay is currently visible.
 */
export function isVisible() {
  return overlayEl !== null;
}
