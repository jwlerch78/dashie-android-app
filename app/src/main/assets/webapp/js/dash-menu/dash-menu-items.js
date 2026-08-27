/**
 * Dash Menu item definitions.
 * Uses artwork SVG paths matching the standard Dashie sidebar pattern.
 * Volume icon logic is handled by the shared volume-slider.js (via AndroidBridge.getVolumeIcon).
 *
 * type: 'view'    = navigation tab in top section (gets active indicator)
 * type: 'view-action' = action button placed in view section (below views)
 * type: 'control' = action button in bottom control strip
 */
export const MENU_ITEMS = [
  { id: 'music',        label: 'Music',          iconPath: '/artwork/icon-music.svg',         type: 'control' },
  { id: 'camera',       label: 'Cameras',        iconPath: '/artwork/icon-video-camera.svg',  type: 'control' },
  { id: 'volume',       label: 'Volume',         iconPath: '/artwork/icon-volume-on.svg',     type: 'control', iconPathMuted: '/artwork/icon-volume-mute.svg' },
  { id: 'brightness',   label: 'Brightness',     iconPath: '/artwork/icon-sun.svg',           type: 'control' },
  { id: 'hamburger',    label: 'Menu',           iconPath: '/artwork/icon-hamburger.svg',     type: 'control' },
];

/**
 * Pin icon paths for the sidebar pin/unpin button.
 */
export const PIN_ICONS = {
  pinned:   '/artwork/icon-pin.svg',
  unpinned: '/artwork/icon-pin-off.svg',
};

/**
 * Icon paths for hamburger popout items.
 */
export const POPOUT_ICONS = {
  unlock:   'artwork/icon-lock.svg',
  lock:     'artwork/icon-lock.svg',
  settings: 'artwork/icon-control-center.svg',
  sleep:    'artwork/icon-sleep.svg',
  reload:   'artwork/icon-reload.svg',
  pin:      'artwork/icon-pin.svg',
  unpin:    'artwork/icon-pin-off.svg',
  exit:     'artwork/icon-exit.svg',
};
