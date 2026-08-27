/**
 * Hamburger menu popout for the Dash Menu.
 * Matches the webapp's hamburger menu structure from dom-builder.js:
 * - Unlocked: Dashie logo + divider + system actions
 * - Locked: Reload/Sleep + divider + bold Unlock App
 *
 * Uses data-locked attribute to toggle sections via CSS (same pattern as webapp).
 */

import { POPOUT_ICONS } from './dash-menu-items.js';
import { getBrand, getSidebarLogo } from '../brand.js';

/**
 * Creates the hamburger popout DOM.
 * @param {boolean} isLocked - current lock state
 * @param {boolean} isPinned - current pin state (for pin/unpin label)
 * @param {{ onDismiss: Function, onPin: Function, onSettings: Function }} callbacks
 * @returns {{ element: HTMLElement, popoutWidth: number, destroy: Function }}
 */
export function createHamburgerPopout(isLocked, isPinned, callbacks) {
  const { onDismiss, onPin, onSettings } = callbacks;
  const container = document.createElement('div');
  container.className = 'hamburger-popout-menu';
  container.dataset.locked = String(isLocked);

  // Build unlocked section
  buildUnlockedSection(container, isPinned);

  // Build locked section
  buildLockedSection(container, isPinned);

  // Click handler
  container.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-menu-id]');
    if (!btn) return;

    const action = btn.dataset.menuId;
    handleAction(action, { onDismiss, onPin, onSettings });
  });

  return {
    element: container,
    popoutWidth: 220,
    destroy() { /* no cleanup needed */ }
  };
}

/**
 * Build the unlocked menu section: Dashie logo + system actions
 */
function buildUnlockedSection(container, isPinned) {
  const section = document.createElement('div');
  section.className = 'hamburger-menu-section hamburger-menu-section--unlocked';

  // Dashie logo (long-press triggers PIN recovery)
  const logoWrapper = document.createElement('div');
  logoWrapper.className = 'hamburger-menu-logo';
  const logo = document.createElement('img');
  // Per-render, not cached: the theme can flip under a live page (the Android 14+ toggle), and
  // the popout is rebuilt on open — so reading it here is what keeps the mark on the surface it
  // is actually being drawn on.
  logo.src = getSidebarLogo();
  logo.alt = getBrand().name;
  logo.className = 'hamburger-menu-logo-img';
  logo.draggable = false;
  attachLongPress(logoWrapper, () => {
    try { DashieNative.showPinRecoveryDialog(); } catch (e) { /* */ }
  });
  logoWrapper.appendChild(logo);
  section.appendChild(logoWrapper);

  // Divider
  const divider = document.createElement('div');
  divider.className = 'hamburger-menu-divider';
  section.appendChild(divider);

  // System action items
  let index = 0;
  const systemItems = [
    { id: 'pin-sidebar', label: isPinned ? 'Unpin Sidebar' : 'Pin Sidebar', iconPath: isPinned ? POPOUT_ICONS.unpin : POPOUT_ICONS.pin },
    { id: 'lock',        label: 'Lock App',    iconPath: POPOUT_ICONS.lock },
    { id: 'sleep',       label: 'Sleep',       iconPath: POPOUT_ICONS.sleep },
    { id: 'reload',      label: 'Reload',      iconPath: POPOUT_ICONS.reload },
    { id: 'exit',        label: 'Exit',        iconPath: POPOUT_ICONS.exit },
  ];

  systemItems.forEach(item => {
    section.appendChild(createMenuItem(item, index++));
  });

  // Divider before Control Center
  const settingsDivider = document.createElement('div');
  settingsDivider.className = 'hamburger-menu-divider';
  section.appendChild(settingsDivider);

  // Control Center (primary/bold, same style as Unlock App when locked)
  const settingsBtn = createMenuItem(
    { id: 'settings', label: 'Control Center', iconPath: POPOUT_ICONS.settings },
    index
  );
  settingsBtn.classList.add('hamburger-menu-item--primary');
  section.appendChild(settingsBtn);

  container.appendChild(section);
}

/**
 * Build the locked menu section: minimal system actions + Unlock
 */
function buildLockedSection(container, isPinned) {
  const section = document.createElement('div');
  section.className = 'hamburger-menu-section hamburger-menu-section--locked';

  // Dashie logo (long-press triggers PIN recovery)
  const logoWrapper = document.createElement('div');
  logoWrapper.className = 'hamburger-menu-logo';
  const logo = document.createElement('img');
  // Per-render, not cached: the theme can flip under a live page (the Android 14+ toggle), and
  // the popout is rebuilt on open — so reading it here is what keeps the mark on the surface it
  // is actually being drawn on.
  logo.src = getSidebarLogo();
  logo.alt = getBrand().name;
  logo.className = 'hamburger-menu-logo-img';
  logo.draggable = false;
  attachLongPress(logoWrapper, () => {
    try { DashieNative.showPinRecoveryDialog(); } catch (e) { /* */ }
  });
  logoWrapper.appendChild(logo);
  section.appendChild(logoWrapper);

  // Divider
  const logoDivider = document.createElement('div');
  logoDivider.className = 'hamburger-menu-divider';
  section.appendChild(logoDivider);

  const items = [
    { id: 'reload',      label: 'Reload', iconPath: POPOUT_ICONS.reload },
    { id: 'sleep',       label: 'Sleep',  iconPath: POPOUT_ICONS.sleep },
  ];

  let index = 0;
  items.forEach(item => {
    section.appendChild(createMenuItem(item, index++));
  });

  // Divider before unlock
  const divider = document.createElement('div');
  divider.className = 'hamburger-menu-divider';
  section.appendChild(divider);

  // Unlock App (primary/bold action)
  const unlockBtn = createMenuItem(
    { id: 'unlock', label: 'Unlock App', iconPath: POPOUT_ICONS.unlock },
    index
  );
  unlockBtn.classList.add('hamburger-menu-item--primary');
  section.appendChild(unlockBtn);

  container.appendChild(section);
}

/**
 * Create a single menu item button.
 * Items with iconPath get an icon; Quick Settings items (no iconPath) get text only.
 */
function createMenuItem(item, index) {
  const button = document.createElement('button');
  button.className = 'hamburger-menu-item';
  button.dataset.menuId = item.id;
  button.dataset.menuIndex = index;

  if (item.iconPath) {
    const icon = document.createElement('img');
    icon.className = 'hamburger-menu-icon';
    icon.src = item.iconPath;
    icon.alt = item.label;
    button.appendChild(icon);
  }

  const label = document.createElement('span');
  label.className = 'hamburger-menu-label';
  label.textContent = item.label;
  button.appendChild(label);

  return button;
}

/**
 * Route menu item clicks to appropriate actions.
 */
function handleAction(action, { onDismiss, onPin, onSettings }) {
  try {
    switch (action) {
      case 'unlock':
      case 'lock':
        DashieNative.showLockDialog();
        break;

      case 'settings':
        onSettings?.();
        break;

      case 'pin-sidebar':
        onPin?.();
        break;

      case 'sleep':
        DashieNative.sleepNow();
        break;

      case 'reload':
        DashieNative.reloadDashboard();
        break;

      case 'exit':
        DashieNative.showExitConfirmation();
        break;
    }
  } catch (e) {
    console.warn('[DashMenu] Bridge call failed:', action, e);
  }
  onDismiss?.();
}

/**
 * Attach a long-press (touch-hold) handler to an element.
 * @param {HTMLElement} el - Element to listen on
 * @param {Function} callback - Fires after hold threshold
 * @param {number} [ms=1500] - Hold duration in ms
 */
function attachLongPress(el, callback, ms = 5000) {
  let timer = null;
  const clear = () => { clearTimeout(timer); timer = null; };
  el.addEventListener('touchstart', (e) => {
    timer = setTimeout(() => { callback(); timer = null; }, ms);
  }, { passive: true });
  el.addEventListener('touchend', clear);
  el.addEventListener('touchcancel', clear);
  el.addEventListener('touchmove', clear);
}
