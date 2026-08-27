/**
 * Brightness / Theme popout for the Dash Menu.
 *
 * On tablets: shows the standard brightness slider (via shared createBrightnessSliderContent).
 * On TV: shows a simple dark/light mode toggle (TVs handle their own brightness).
 *
 * Dark mode toggle works by calling HA's frontend.set_theme service directly via
 * evalInHaIframe(), since Android 14 WebView ignores prefers-color-scheme changes
 * from Resources.updateConfiguration(). Settings.Secure.ui_night_mode is still written
 * for persistence across app restarts.
 */

import { createBrightnessSliderContent } from '@dashie/ui/brightness-slider.js';
import { applyThemeClass, syncHaIframeTheme } from '../utils/theme-utils.js';

/**
 * Detect whether the current device is a TV.
 */
function isTvDevice() {
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.getDeviceInfo) {
      const info = JSON.parse(DashieNative.getDeviceInfo());
      return info.isTv || info.isFireTV || false;
    }
  } catch (e) { /* bridge unavailable */ }
  return false;
}

/**
 * Check if the app has WRITE_SECURE_SETTINGS permission for dark mode control.
 */
function hasSecureSettingsPermission() {
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.canWriteSecureSettings) {
      return DashieNative.canWriteSecureSettings();
    }
  } catch (e) { /* bridge unavailable */ }
  return false;
}

/**
 * Get the app's package name for the ADB command.
 */
function getPackageName() {
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.getAppVersionInfo) {
      const info = JSON.parse(DashieNative.getAppVersionInfo());
      return info.packageName || 'com.dashieapp.Dashie';
    }
  } catch (e) { /* bridge unavailable */ }
  return 'com.dashieapp.Dashie';
}

/**
 * Show a dialog explaining that WRITE_SECURE_SETTINGS permission is needed
 * and providing the ADB command to grant it.
 */
function showPermissionDialog() {
  const pkg = getPackageName();
  const adbCmd = `adb shell pm grant ${pkg} android.permission.WRITE_SECURE_SETTINGS`;

  const overlay = document.createElement('div');
  overlay.className = 'dm-permission-overlay';

  const dialog = document.createElement('div');
  dialog.className = 'dm-permission-dialog';
  dialog.innerHTML = `
    <div class="dm-permission-title">Permission Required</div>
    <div class="dm-permission-body">
      Dark mode control requires a special Android permission that can only be granted via ADB.
      Connect the device to a computer and run:
    </div>
    <div class="dm-permission-cmd">${adbCmd}</div>
    <div class="dm-permission-hint">
      This only needs to be done once per device. After granting, the dark mode toggle will work.
    </div>
    <button class="dm-permission-close-btn">OK</button>
  `;

  overlay.appendChild(dialog);
  document.body.appendChild(overlay);

  const close = () => overlay.remove();
  dialog.querySelector('.dm-permission-close-btn').addEventListener('click', close);
  overlay.addEventListener('click', (e) => { if (e.target === overlay) close(); });
}

/**
 * Set the HA theme directly via evalInHaIframe + persist via Settings.Secure.
 * This bypasses prefers-color-scheme (which Android 14 WebView ignores) and
 * sets the user's theme preference via HA's WebSocket API (frontend/set_user_data).
 */
function setDarkMode(isDark) {
  console.log(`[DashMenu] setDarkMode(${isDark})`);

  // 1. Apply theme class to kiosk overlay UI (sidebar, menus, modals)
  applyThemeClass(isDark);

  // 2. Persist to Settings.Secure for app restart recovery
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.setSystemDarkMode) {
      DashieNative.setSystemDarkMode(isDark);
    }
  } catch (e) {
    console.warn('[DashMenu] setSystemDarkMode failed:', e);
  }

  // 3. Tell HA to switch themes via its internal settheme event
  syncHaIframeTheme(isDark);
}

/**
 * Get current dark mode state. Checks DOM theme class first (set on init),
 * falls back to native bridge, defaults to dark.
 */
function getCurrentDarkMode() {
  // Check DOM class (set by applyThemeClass on startup)
  if (document.documentElement.classList.contains('theme-light')) return false;
  if (document.documentElement.classList.contains('theme-dark')) return true;
  // Fall back to native bridge
  try {
    if (typeof DashieNative !== 'undefined' && DashieNative.isSystemDarkMode) {
      return DashieNative.isSystemDarkMode();
    }
  } catch (e) { /* bridge unavailable */ }
  return true; // default dark
}

/**
 * Create a simple dark/light mode toggle popout for TV devices.
 * @param {Function} onActivity - called on user interaction (resets auto-hide)
 * @returns {{ element: HTMLElement, popoutWidth: number, destroy: Function }}
 */
function createThemeTogglePopout(onActivity) {
  let isDark = getCurrentDarkMode();

  const popup = document.createElement('div');
  popup.className = 'brightness-slider-popup dm-theme-toggle-popout';

  const btn = document.createElement('button');
  btn.className = 'dm-theme-toggle-btn';
  btn.innerHTML = `
    <span class="dm-theme-toggle-icon">${isDark ? '&#9788;' : '&#9789;'}</span>
    <span class="dm-theme-toggle-label">Switch to ${isDark ? 'Light' : 'Dark'} Mode</span>
  `;

  const handleClick = () => {
    onActivity?.();
    if (!hasSecureSettingsPermission()) {
      showPermissionDialog();
      return;
    }
    isDark = !isDark;
    setDarkMode(isDark);
    btn.querySelector('.dm-theme-toggle-icon').innerHTML = isDark ? '&#9788;' : '&#9789;';
    btn.querySelector('.dm-theme-toggle-label').textContent =
      `Switch to ${isDark ? 'Light' : 'Dark'} Mode`;
  };

  btn.addEventListener('click', handleClick);
  popup.appendChild(btn);

  return {
    element: popup,
    popoutWidth: 220,
    destroy: () => btn.removeEventListener('click', handleClick),
  };
}

/**
 * Creates the brightness popout for the dash menu.
 * On TV: returns a theme toggle. On tablet: returns the brightness slider.
 * @param {Function} onActivity - called on user interaction (resets auto-hide)
 * @returns {{ element: HTMLElement, popoutWidth: number, destroy: Function }}
 */
export function createBrightnessPopout(onActivity) {
  if (isTvDevice()) {
    return createThemeTogglePopout(onActivity);
  }

  // Use the shared brightness slider with the theme toggle button visible
  const result = createBrightnessSliderContent({
    onActivity,
    showThemeToggle: true,
    resolveIconUrl: (path) => path.replace(/^\//, ''),
  });

  const popup = document.createElement('div');
  popup.className = 'brightness-slider-popup';
  popup.appendChild(result.element);

  // Override the theme button's click handler to use kiosk-native dark mode
  // (the shared slider uses themeApplier which is shimmed out in kiosk)
  const themeBtn = result.element.querySelector('.brightness-slider-theme-btn');
  if (themeBtn) {
    let isDark = getCurrentDarkMode();

    // Fix initial label to match actual system state
    const modeText = themeBtn.querySelector('.brightness-slider-theme-mode');
    if (modeText) modeText.textContent = isDark ? 'Light' : 'Dark';

    const handleKioskThemeClick = (e) => {
      e.stopImmediatePropagation(); // prevent the shared handler from firing
      onActivity?.();
      if (!hasSecureSettingsPermission()) {
        showPermissionDialog();
        return;
      }
      isDark = !isDark;
      setDarkMode(isDark);
      if (modeText) modeText.textContent = isDark ? 'Light' : 'Dark';
    };

    // Add our handler BEFORE the shared one so stopImmediatePropagation works
    themeBtn.addEventListener('click', handleKioskThemeClick, true);
  }

  return {
    element: popup,
    popoutWidth: 280,
    destroy: () => result.destroy(),
  };
}
