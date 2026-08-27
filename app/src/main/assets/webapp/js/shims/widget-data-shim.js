// kiosk-overlay/js/shims/widget-data-shim.js
//
// Stands in for `js/core/widget-data-manager.js` and
// `js/core/widget-data/loaders/photos-data-loader.js` in the KIOSK bundle.
//
// The settings stack reaches for these in exactly one place each — the "photo source
// changed, reload the photos widget" branch:
//   device-registration.js:610/622   (applyDeviceSettings)
//   native-settings-listener.js:291-292 (reloadPhotosWidget)
//
// A kiosk has no photos widget — its WebView shows Home Assistant. Both call sites
// already null-guard what they get back (`getWidgetDataManager()?.…`), so the CALL is
// harmless; it's the IMPORT that binds, dragging widget-data-manager (1,607 lines) and
// its loader tree into a bundle that must boot on a 512 MB device.
//
// Returning null / no-op is not a degradation here — it is the correct behaviour for a
// device with nothing to reload.

/** device-registration + native-settings-listener both optional-chain the result. */
export function getWidgetDataManager() {
  return null;
}

/** Kiosk has no photos widget to load data for. */
export async function loadPhotosData() {
  return null;
}
