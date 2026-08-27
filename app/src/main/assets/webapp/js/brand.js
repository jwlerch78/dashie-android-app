/**
 * Brand facts for the kiosk overlay — ONE table, derived from ONE edition signal.
 *
 * The kiosk overlay ships as a single bundle inside `app/src/main/assets/webapp/`, which is
 * the **main** Android source set: every flavor of both editions gets the same file. So the
 * edition cannot be a build-time fact here the way `BuildConfig.EDITION` is on the Kotlin
 * side — it has to be asked at runtime, once, and everything brand-shaped derived from it.
 *
 * ## Why a table and not `if (isChickadee)` at each site
 *
 * Standing rule 1 (the seam test): before writing a second copy of anything, share the first.
 * The onboarding renderer alone carries a product name, a logo, two legal URLs and an
 * account-paths decision. Scattered edition checks would be five independent chances to add a
 * sixth Dashie string that nobody notices until a Chickadee beta tester photographs it — which
 * is exactly how this file came to exist (Fire tablet, 2026-08-01: the first-run screen said
 * "Welcome to Dashie!" and offered to create a Dashie account).
 *
 * ## `hasAccounts` is a CAPABILITY, not a preference
 *
 * Chickadee is account-free by construction: no sign-up, no sign-in, no credits, no metered
 * service. A first-run screen offering "Create a Dashie Account" in an account-free product is
 * an edition-logic defect, not a branding one — so this is the same shape as Kotlin's
 * `VoiceEntitlement.hasLicensing`: the option does not EXIST rather than existing and failing.
 *
 * ## ⚠️ Cross-boundary contract — `JS_KOTLIN_CONTRACTS.md #64`
 *
 * The keys of `BRANDS` are Kotlin's `BuildConfig.EDITION` vocabulary. Adding an edition in
 * `app/build.gradle.kts` without adding it here silently brands the new edition as Dashie.
 * Gated by `npm run lint:edition-vocabulary`, which compares the two sets.
 *
 * @see kiosk-overlay/js/onboarding/onboarding-renderer.js — the first consumer
 */

const TAG = '[Brand]';

/**
 * The accent, shared by BOTH brands since 2026-08-02 (John: one colour set everywhere).
 *
 * One constant rather than the same hex typed into two table rows — the JS half of the same
 * decision that deleted `chickadeeBrand/res/values/colors.xml`'s palette on the Kotlin side.
 * There the fix was "no override"; a JS table has no inheritance to fall back on, so the
 * equivalent is one definition that both entries point at. Two equal literals would be a
 * duplicate that must stay equal, which is the thing that drifts.
 *
 * If the brands ever diverge again, this becomes a per-entry value again — one line each,
 * which is the property the seam was bought for.
 */
const SHARED_ACCENT = '#FF9500';

/**
 * Every brand fact the overlay needs, keyed by edition.
 *
 * ⚠️ `legal` was `null` for Chickadee until 2026-08-02 and that was deliberate, not an oversight
 * — the pages did not exist and the renderer omits the links rather than shipping a 404 onto the
 * first screen a beta tester sees. Both brands now have real pages. The `null` branch is KEPT and
 * still handled: it is the correct state for any future edition whose pages are not written yet.
 *
 * ## ⚠️ Both brands' marks are drawn for DARK surfaces, and the light theme is reachable
 *
 * `dash-menu.css` ships a light theme twice over — `prefers-color-scheme: light` AND a
 * `:root.theme-light` class toggle added for Android 14, where the media query is stuck. On
 * `--dm-bg-secondary: #ffffff` both brands' wordmark ink measures near-invisible:
 *
 * | mark | ink on #252525 (dark, default) | ink on #ffffff (light) |
 * |---|---|---|
 * | `dashie-logo-orange.png` | 12.90:1 | **1.19:1** |
 * | `Chickadee_Full_Logo_White_Transparent.png` | 7.74:1 | **1.98:1** |
 *
 * ⚠️ **Severity refinement (Thread T, rendered at real size on both themes):** the numbers above
 * are the **text ink**, and on white the text does effectively vanish — but the **bird survives,
 * because it is orange**, not neutral. So the loss is partial: the brand stays identifiable and
 * only loses its *name*. Worth knowing before anyone reopens this as urgent. (T independently
 * measured the dark-theme fix as 1.24:1 → 6.56:1, corroborating the 1.11 → 7.74 below.)
 *
 * 🔵 **This is PRE-EXISTING and SHARED, not a Chickadee defect** — which is why there is no
 * Chickadee-specific mechanism here. Giving one brand a theme-reactive mark slot would be a
 * second, better mechanism for one edition, i.e. exactly the divergence the shared-palette
 * decision removed. Chickadee is chosen for PARITY with Dashie and lands marginally ahead of
 * it. Fixing it properly means a theme-reactive slot for BOTH (or two all-accent wordmarks,
 * which is Thread C's call as the art owner) and belongs to whoever does light-theme work.
 *
 * ## ✅ THE ART OWNER'S CALL (Thread C, 2026-08-03) — measured, and it settles the open option
 *
 * The open question above offered two fixes. **Measurement rules one of them out, so it should
 * not be re-proposed:**
 *
 * **1. "Two all-accent wordmarks" does NOT work.** A single ink cannot serve both surfaces —
 * this is a ceiling, not a tuning problem. Sweeping every neutral and the whole orange family
 * against `#252525` and `#ffffff`, the best any single ink achieves is **~3.9:1 on both**
 * (`#818181` → 3.93/3.90; best orange `#e74918` → 3.92/3.91). The brand orange itself is only
 * **2.20:1 on white**, and the current ink is 8.08/1.90. So going all-accent would **halve the
 * DEFAULT (dark) surface** — from 8.08 to ~3.9 — to buy a non-default one. Bad trade; rejected.
 *
 * **2. A theme-reactive slot for BOTH is the only real fix, and for Chickadee it needs NO new
 * art.** Both variants already exist in `.reference/brand-assets/chickadee/`:
 *
 * | asset | ink | on `#252525` | on `#ffffff` |
 * |---|---|---|---|
 * | `wordmark-dark-900x300.png` (shipping now) | (191,188,181) | **8.08** | 1.90 |
 * | `wordmark-900x300.png` (charcoal `#40434A`) | (64,67,74) | 1.55 | **9.91** |
 *
 * Theme-reactive would therefore give Chickadee **8.08 dark / 9.91 light** — more than twice the
 * single-ink ceiling on *both* surfaces, at zero art cost.
 *
 * **The DASHIE side measures worse, and that is now a RULING, not a gap.** All three Dashie marks
 * in this tree — `dashie-logo-orange.png`, `dashie-logo-dark.png`, `dashie-logo-light.png` — carry
 * the same dominant ink (243,154,33): **6.90 dark / 2.22 white for every one of them.**
 * ⚠️ The `-dark`/`-light` filenames promise a theme distinction these files do not contain; do
 * not reach for them expecting variants. (`Dashie_Full_Logo_White_Transparent.png` is pure white:
 * 15.33 dark, **1.00 white** — invisible.)
 *
 * ✅ **JOHN, 2026-08-03 — the brand owner closed this:** *"The orange on white is really fine.
 * We've been using it for Dashie and it looks good."* So **Dashie ships ONE mark on BOTH surfaces,
 * permanently.** No light-surface wordmark is coming.
 *
 * 🔴 **Read the 2.22 as ACCEPTED, not as a defect awaiting art.** This paragraph previously said
 * the mechanism "cannot be built symmetrically until one exists", which invited exactly the wrong
 * follow-up: a future session finding 2.22:1, reading it as an open contrast bug, and either
 * chasing a Dashie asset that will never arrive or "fixing" a mark the brand owner has explicitly
 * signed off. It is a measurement of an accepted state.
 *
 * 📌 Net: theme-reactivity is a **Chickadee-only** need. The slot should carry per-theme marks as
 * DATA — Chickadee declaring two and Dashie declaring one used for both — rather than branching on
 * edition, and Dashie's single entry is its permanent shape, not a waiting state.
 *
 * The white PLATE that used to sit behind the Chickadee mark is gone: it existed only because
 * the mark was near-black ink on a #1C1C1E card, and Thread C's dark-surface wordmark
 * (`80472d9d8`) solves that at the asset. The flag, the CSS class and the renderer branch went
 * with it rather than staying as a mechanism no brand selects — see git history for the shape
 * if a future brand needs it back.
 */
const BRANDS = {
  dashie: {
    edition: 'dashie',
    /** Product name as it appears in prose ("Welcome to Dashie!"). Not `app_name`, which
     *  carries build-channel suffixes like "(Staging)". */
    name: 'Dashie',
    logo: 'artwork/Dashie_Full_Logo_Orange_Transparent.png',
    /**
     * The sidebar pop-out's own mark — a DIFFERENT asset from [logo], which is why it survived
     * the first branding pass. Same lesson as the native `dashie_logo_orange` vs
     * `dashie_full_logo` split: one brand, several mark slots.
     *
     * ✅ **ONE mark on both surfaces, and that is PERMANENT** — John, 2026-08-03: *"The orange on
     * white is really fine."* This is not a placeholder awaiting a light-surface wordmark; see the
     * contrast note above before "fixing" the 2.22:1.
     */
    sidebarLogo: { dark: 'images/dashie-logo-orange.png', light: 'images/dashie-logo-orange.png' },
    /** The overlay's own `--accent-primary`, i.e. the value already in onboarding.css. */
    accent: SHARED_ACCENT,
    hasAccounts: true,
    legal: {
      privacyUrl: 'https://dashieapp.com/privacy-policy.html',
      termsUrl: 'https://dashieapp.com/terms-of-service.html',
    },
  },
  chickadee: {
    edition: 'chickadee',
    name: 'Chickadee',
    /**
     * ONE asset in both mark slots, deliberately.
     *
     * Dashie's two slots hold genuinely different art (a full logo and a sidebar mark), so the
     * table keeps two fields. Chickadee's held two byte-identical copies of one file at two
     * paths — a duplicate that must stay equal, i.e. the thing that drifts. Both fields now
     * point at the single asset; if the brands' slots ever diverge, that is one line here and
     * a second file, which is the property the two fields were bought for.
     *
     * "White" follows Dashie's ink-naming convention for this slot
     * (`Dashie_Full_Logo_White_Transparent.png`), not a literal `#FFFFFF` — the ink is Thread
     * C's inverted neutral (187,184,177) and the bird keeps the brand orange.
     */
    logo: 'artwork/Chickadee_Full_Logo_White_Transparent.png',
    /**
     * 🔵 **The theme-reactive slot** — the one place either brand actually needs two marks.
     *
     * Chickadee owns two wordmarks that already exist, so this costs no art: the light ink reads
     * **8.08:1** on the default `#252525` popout and the charcoal reads **9.91:1** on the light
     * theme's `#ffffff`. Using either alone means ~1.55–1.90:1 on the other surface, i.e. the
     * near-invisible mark measured at s55.
     *
     * ⚠️ **Both files are 854×220 (3.88:1) — the TRIMMED lockup**, which is what
     * `.hamburger-menu-logo-img`'s `max-width: 171px` is computed from (44 × 3.88). Keep the two
     * variants at the same aspect or that slot silently under-fills for one theme only. They are
     * the trimmed pair from `.reference/brand-assets/chickadee/`, NOT the 900×300 originals —
     * those are 3.00:1 and would break the slot's arithmetic.
     *
     * 📌 Updated 2026-08-04 from 867×241 / 3.60 / 158px when John's new art landed. Recording the
     * move because the numbers here and in `dash-menu.css` are ONE fact written twice — a doc and
     * a dependent value — and the failure mode is silent: art changes, the doc keeps asserting the
     * old aspect, and the next person computes from a number no asset has. C now crops BOTH inks
     * to the union of their ink boxes, so they share a canvas by construction and the runtime
     * theme swap cannot make the mark jump size. Do not re-trim them individually.
     */
    sidebarLogo: {
      dark: 'artwork/Chickadee_Full_Logo_White_Transparent.png',
      light: 'artwork/Chickadee_Full_Logo_Charcoal_Transparent.png',
    },
    /** Was Thread C's brand blue (#316ec7), sampled from the mark's wing. **Superseded
     *  2026-08-02:** John moved Chickadee onto the Dashie orange, so there is no second palette
     *  left to keep in sync — the Kotlin sibling this note used to warn about
     *  (`chickadeeBrand`'s `chickadee_blue`) was deleted rather than re-valued, for the same
     *  reason [SHARED_ACCENT] exists here. */
    accent: SHARED_ACCENT,
    hasAccounts: false,
    /**
     * LIVE as of 2026-08-02 — Thread C's pages, verified 200 by O before this flip.
     *
     * These were `null` on purpose until the site existed: the renderer omits the links entirely
     * rather than rendering a dead one, because a 404 on the first screen a beta tester sees is
     * worse than no link. That was the right default and it is why nothing shipped broken while
     * the pages were being written.
     *
     * Canonical paths. C's site also redirects the Dashie-shaped `/privacy-policy`,
     * `/privacy-policy.html`, `/terms-of-service` and `/terms-of-service.html` here, so an older
     * hardcoded variant still lands — but these are the ones to use.
     */
    /*
     * Back to `null`, 2026-08-22: the chickadee domain registration is being dropped, so these
     * URLs are about to 404, and the paragraph above already prescribes the answer for exactly
     * this state — omit the links rather than render dead ones.
     *
     * 🔴 It must be `legal: null`, NOT `legal: { privacyUrl: null, … }`. Both render sites gate on
     * the WHOLE OBJECT being falsy (`brand.legal ? … : ''` at onboarding-renderer.js:303 and :813),
     * never on the individual fields. Nulling only the fields leaves `brand.legal` truthy, so the
     * footer renders `href="null"` — a broken link, which is worse than the 404 this avoids.
     * (Caught while making that exact mistake.)
     *
     * Restore an object here if the brand ever un-freezes with a live domain.
     */
    legal: null,
  },
};

const FALLBACK_EDITION = 'dashie';

let _cached = null;

/**
 * Resolve the running edition from the native bridge.
 *
 * **Fallback direction is deliberate: Dashie.** The bridge method is absent in exactly two
 * places — a browser dev session, and a Dashie APK built before `getEdition()` existed. Both
 * are Dashie by construction; no Chickadee APK has ever shipped without it, because the
 * Chickadee flavors and this bundle land in the same build. Falling back to Chickadee would
 * mis-brand every old Dashie install, which is the strictly worse error.
 *
 * Both miss paths log a grep-able `DROP:` per standing rule 2, split the way the Kotlin seams
 * split theirs: `[expected]` for the browser/old-APK case that happens in normal operation,
 * `[unexpected]` for an edition Gradle knows and this table does not — which is the drift #64
 * exists to catch, and which shows up as Dashie branding on a non-Dashie product.
 */
function resolveEdition() {
  let raw;
  try {
    const fn = window.DashieNative?.getEdition;
    if (typeof fn !== 'function') {
      console.warn(TAG, 'DROP: [expected] DashieNative.getEdition unavailable — browser dev ' +
        'session or an APK predating it. Assuming "' + FALLBACK_EDITION + '".');
      return FALLBACK_EDITION;
    }
    raw = fn.call(window.DashieNative);
  } catch (e) {
    console.warn(TAG, 'DROP: [expected] DashieNative.getEdition threw — assuming "' +
      FALLBACK_EDITION + '".', e);
    return FALLBACK_EDITION;
  }

  if (raw && Object.prototype.hasOwnProperty.call(BRANDS, raw)) return raw;

  console.warn(TAG, 'DROP: [unexpected] edition "' + raw + '" is not in the brand table. ' +
    'Falling back to "' + FALLBACK_EDITION + '", which is WRONG for any non-Dashie edition — ' +
    'the user will see Dashie branding. Add it to kiosk-overlay/js/brand.js and to ' +
    'JS_KOTLIN_CONTRACTS #64.');
  return FALLBACK_EDITION;
}

/**
 * The running edition's brand facts. Resolved once per page load and cached — the answer
 * cannot change without a process restart, and the renderers call this per screen.
 *
 * @returns {{edition: string, name: string, logo: string,
 *            sidebarLogo: {dark: string, light: string},
 *            accent: string, hasAccounts: boolean,
 *            legal: {privacyUrl: string, termsUrl: string}|null}}
 */
export function getBrand() {
  if (!_cached) _cached = BRANDS[resolveEdition()];
  return _cached;
}

/**
 * The sidebar pop-out mark for the surface that is actually on screen right now.
 *
 * ## The precedence MIRRORS `dash-menu.css`, and that is the whole correctness argument
 *
 * The overlay picks its theme two ways and the CSS resolves them in a specific order:
 * `@media (prefers-color-scheme: light)` sets vars on `:root`, then `:root.theme-dark` /
 * `:root.theme-light` override — **later in source AND more specific (0,2,0 vs 0,1,0), so the
 * CLASS WINS.** The class toggle exists because `prefers-color-scheme` is stuck on Android 14+,
 * i.e. precisely where the media query is *wrong*. So: class first, media query only as the
 * fallback when no class is set.
 *
 * 🔴 Getting this backwards is invisible rather than broken — you get a correct-looking mark that
 * is charcoal-on-charcoal or near-white-on-white. That is the s55 failure exactly: Chickadee's
 * sidebar mark shipped at **1.11:1**, unreadable on device, and no brand pass caught it because
 * everyone checked *which* art was wired, never whether it could be seen.
 *
 * Resolved per CALL, not cached, unlike [getBrand] — the edition cannot change without a process
 * restart but the theme can change under a running page (that is what the toggle does).
 *
 * @returns {string} path to the mark for the current surface
 */
export function getSidebarLogo() {
  const slot = getBrand().sidebarLogo;
  // Tolerate the pre-2026-08-04 string form rather than rendering a broken <img>: an older
  // bundle paired with a newer table (or vice versa) should degrade to today's single mark.
  if (typeof slot === 'string') return slot;

  let light;
  const root = typeof document !== 'undefined' ? document.documentElement : null;
  if (root && root.classList.contains('theme-light')) {
    light = true;
  } else if (root && root.classList.contains('theme-dark')) {
    light = false;
  } else {
    light = typeof window !== 'undefined' && typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-color-scheme: light)').matches;
  }
  return light ? slot.light : slot.dark;
}

/**
 * Paint the brand accent onto one element as a CSS custom property.
 *
 * `--accent-primary` is declared on `.onboarding-overlay` and consumed by ~20 rules in
 * `onboarding.css` — spinner, primary button, d-pad focus ring, toggles, links. Setting the
 * ONE variable rebrands all of them, which is the same move the Kotlin side already made for
 * the splash (`chickadeeBrand/res/values/colors.xml` redirects `orange` rather than copying
 * five theme files). Copying the rules per brand would be twenty chances to miss one.
 *
 * Scoped to the element passed in, deliberately — the drawer and dash-menu carry their own
 * Dashie-orange tokens (`--dashie-orange`, `--accent-orange` in `css/variables.css`) and are
 * NOT covered here. That is remaining 2g work, not an oversight.
 *
 * @param {HTMLElement} el
 */
export function applyBrandAccent(el) {
  if (!el) return;
  el.style.setProperty('--accent-primary', getBrand().accent);
}

/**
 * Editions this table knows about. Read by `scripts/check-edition-vocabulary.mjs` (the #64
 * gate) — keep it exported even though nothing in the app calls it.
 */
export const KNOWN_EDITIONS = Object.keys(BRANDS);
