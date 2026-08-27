/**
 * No-op shim for theme-applier.js.
 * Kiosk is always dark theme — no theme toggling needed.
 */
export default {
  isDarkTheme: () => true,
  getCurrentTheme: () => 'default-dark',
  applyTheme: () => {},
};
