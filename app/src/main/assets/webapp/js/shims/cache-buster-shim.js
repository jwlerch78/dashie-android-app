/**
 * No-op shim for cache-buster.js.
 * Kiosk assets are bundled in the APK — no cache busting needed.
 */
const cacheBuster = {
  bustUrl: (url) => url,
  getVersion: () => '0',
  getLoadTimestamp: () => 0,
  hasCacheBusting: () => false,
  cleanUrl: (url) => url,
};

export default cacheBuster;
export const bustUrl = cacheBuster.bustUrl;
export const getVersion = cacheBuster.getVersion;
