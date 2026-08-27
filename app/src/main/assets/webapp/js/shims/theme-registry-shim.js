/**
 * No-op shim for theme-registry.js.
 * Kiosk is always dark theme — returns minimal defaults so Settings pages can render.
 */

export const DEFAULT_THEME_FAMILY = 'default';
export const DEFAULT_THEME_MODE = 'dark';

export function getAllThemeFamilies() {
  return [{ id: 'default', name: 'Default', modes: ['light', 'dark'] }];
}

export function parseThemeId(themeId) {
  const parts = (themeId || 'default-dark').split('-');
  return { familyId: parts[0] || 'default', mode: parts[1] || 'dark' };
}

export function buildThemeId(familyId, mode) {
  return `${familyId}-${mode}`;
}

export function getThemeFamilyIds() { return ['default']; }
export function getThemeFamily(familyId) { return { id: familyId, name: familyId }; }
export function isValidThemeFamily() { return true; }
export function isValidThemeMode() { return true; }
export function getThemeConfig() { return {}; }
export function getThemeById() { return {}; }
export function isValidThemeId() { return true; }
