/**
 * Theme utility functions for the kiosk overlay.
 * Handles theme class application for immediate CSS variable updates.
 */

/**
 * Apply theme class to document root for immediate CSS variable updates.
 * This ensures CSS variables are correct even if prefers-color-scheme media query is delayed.
 *
 * Also re-applies inline CSS custom properties on :root after the class change,
 * because Android WebView may drop them during style recalculation triggered by
 * classList changes on documentElement.
 *
 * @param {boolean} isDark - Whether to apply dark theme
 */
export function applyThemeClass(isDark) {
  const root = document.documentElement;

  // Snapshot inline CSS custom properties before class change
  // (WebView may lose them during recalc)
  const inlineVars = {};
  for (let i = 0; i < root.style.length; i++) {
    const prop = root.style[i];
    if (prop.startsWith('--')) {
      inlineVars[prop] = root.style.getPropertyValue(prop);
    }
  }

  if (isDark) {
    root.classList.remove('theme-light');
    root.classList.add('theme-dark');
  } else {
    root.classList.remove('theme-dark');
    root.classList.add('theme-light');
  }

  // Re-apply snapshotted inline custom properties
  for (const [prop, value] of Object.entries(inlineVars)) {
    root.style.setProperty(prop, value);
  }

  console.log('[ThemeUtils] Applied theme class:', isDark ? 'theme-dark' : 'theme-light');
}

/**
 * Sync the HA iframe theme to match the kiosk overlay theme.
 * Dispatches HA's internal `settheme` CustomEvent on the <home-assistant> element.
 *
 * @param {boolean} isDark - Whether to apply dark theme in HA
 */
export function syncHaIframeTheme(isDark) {
  const darkBool = isDark ? 'true' : 'false';
  if (typeof window.evalInHaIframe === 'function') {
    window.evalInHaIframe(`
      try {
        var ha = document.querySelector('home-assistant');
        if (ha) {
          ha.dispatchEvent(new CustomEvent('settheme', { detail: { dark: ${darkBool} } }));
          console.log('[DashieLite] settheme synced: dark=${darkBool}');
        }
      } catch(e) {}
    `);
  }
}
