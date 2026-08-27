/**
 * This build's HA integration API prefix — `"/api/dashie"` or `"/api/chickadee"`
 * (`JS_KOTLIN_CONTRACTS #63`). Compose as `getHaApiPrefix() + '/exposed_entities'`.
 *
 * ## 🔴 Why this is NOT a field in `brand.js`'s BRANDS table
 *
 * It looks like a brand fact, and that is the trap. The prefix is a **wire value** shared with
 * the Python integrations, and #63's content is *one prefix per brand, ONE derivation* —
 * Kotlin's `ApiPaths`. A row in the brand table mapping the same edition axis to the same two
 * literals would be a hand-mirror: `lint:wire-values` reads the Kotlin and would never see it
 * drift, and the failure mode is a 404 against a healthy integration — exactly the near-miss
 * that made #63 a registered contract. Everything in `BRANDS` is a JS-side asset or string with
 * no Kotlin counterpart to drift from; this one has one. So it is asked, not tabled.
 *
 * ## Why it is its OWN module rather than living in `brand.js`
 *
 * It was in `brand.js` first, and the built artifact showed the cost: `kiosk-services.js`
 * imports only this function, but importing it from `brand.js` pulled the **whole BRANDS table**
 * — Dashie's legal URLs included — into `kiosk-services.bundle.js`, adding a Dashie-host string
 * to a second bundle in the account-free edition. Caught by `lint:chickadee-surface` on the
 * rebuilt APK, not by reading the diff. (Phrased without the literal host on purpose: the
 * comment explaining the leak should not itself register as one.)
 *
 * The fix and the coherence argument are the same one: the KDoc above already says this is not a
 * brand-table fact, so it should never have shared a module with the brand table.
 *
 * @see kiosk-overlay/js/brand.js — brand facts (name, marks, accent, whether accounts exist)
 */

const TAG = '[HaApiPrefix]';

/**
 * Pre-feature answer. **Direction is deliberate: Dashie.** The bridge method is absent in
 * exactly two places — a browser dev session, and an APK built before it existed. Both are
 * Dashie by construction; no Chickadee APK has ever shipped without it. Same reasoning as
 * `brand.js`'s edition fallback.
 */
const FALLBACK_API_PREFIX = '/api/dashie';

let _cached = null;

/**
 * Resolve the prefix once per page load.
 *
 * Both miss paths log a grep-able `DROP:` per standing rule 2, graded the same way the Kotlin
 * seams grade theirs: `[expected]` for the browser/old-APK case that happens in normal
 * operation, `[unexpected]` for a bridge that is present but answers with something unusable —
 * which would mean `ApiPaths` itself fell through to its own `DROP:`.
 *
 * @returns {string} prefix with a leading slash and no trailing slash
 */
export function getHaApiPrefix() {
  if (_cached) return _cached;
  let prefix = null;
  try {
    const fn = window.DashieNative?.getHaApiPrefix;
    if (typeof fn !== 'function') {
      console.warn(TAG, 'DROP: [expected] DashieNative.getHaApiPrefix unavailable — browser ' +
        'dev session or an APK predating it. Assuming "' + FALLBACK_API_PREFIX + '".');
    } else {
      prefix = fn.call(window.DashieNative);
    }
  } catch (e) {
    console.warn(TAG, 'DROP: [expected] DashieNative.getHaApiPrefix threw — assuming "' +
      FALLBACK_API_PREFIX + '".', e);
  }

  if (typeof prefix === 'string' && prefix.startsWith('/api/')) {
    _cached = prefix;
  } else {
    if (prefix != null) {
      console.warn(TAG, 'DROP: [unexpected] DashieNative.getHaApiPrefix returned "' + prefix +
        '", which is not an /api/ prefix. Falling back to "' + FALLBACK_API_PREFIX + '" — ' +
        'WRONG for any non-Dashie edition and it will 404 against its integration. Check ' +
        'ApiPaths.HA and JS_KOTLIN_CONTRACTS #63.');
    }
    _cached = FALLBACK_API_PREFIX;
  }
  return _cached;
}
