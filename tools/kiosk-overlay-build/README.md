# Building the kiosk web overlay

The kiosk WebView loads a small web app bundled inside the APK, at
`app/src/main/assets/webapp/`. Two files in that tree are esbuild bundles rather than source:

- `app/src/main/assets/webapp/dist/kiosk-shell.bundle.js` — onboarding, the HA iframe lifecycle,
  the screensaver bridge, D-pad routing
- `app/src/main/assets/webapp/dist/kiosk-services.bundle.js` — timers, voice, the intent classifier

This directory holds the configuration that produces them. It is here so those two files are not
opaque blobs in an otherwise readable repository.

## Running it

```bash
cd tools/kiosk-overlay-build
npm install
node build.js
```

`build.js` expects to run from a directory that also contains the overlay's own source — `js/`,
`css/`, `esbuild-kiosk-shims.js`. In this repository that source lives at
`app/src/main/assets/webapp/`, so copy these three files there first, or point `build.js` at it.
They are kept separate because the sync that populates `assets/webapp/` deliberately excludes
build tooling from the APK.

## What you can and cannot reproduce from this repository alone

**You cannot currently rebuild the two bundles byte-for-byte here, and this is the honest
statement of why.**

Both entry points resolve import aliases into the private web-dashboard repository:

| alias | resolves to | used by |
|---|---|---|
| `@dashie/ui`, `@dashie/utils`, `@dashie/config` | the dashboard's shared UI, utilities, and app config | `kiosk-shell` |
| `@dashieapp/intent-classifier` | the shared intent-classifier package | `kiosk-services` |

Measured against esbuild's own metafile, the two bundles pull **47 hand-written source files**
from outside this tree — settings sync and the settings store, the Supabase edge client, logging,
device/client identity, timezone and geocoding helpers, and the intent classifier — plus the
`@supabase/*` packages those reach, which `npm install` supplies.

That code is *in* the bundles this repository ships. It is minified, not hidden: it is present in
every APK we publish and readable with any JS beautifier. What is missing here is its readable
source, and the reason is that it belongs to a repository that is not open source.

Running `node build.js` here fails at module resolution, naming the first import it cannot find:

```
✘ [ERROR] Could not resolve "../../js/utils/video-feed-config.js"
    js/kiosk-shell.js:19:59
```

That is the intended failure. It stops at the real boundary and tells you which file is missing,
rather than producing a subtly different bundle. (It also prints
`fatal: not a git repository` when run outside a checkout — harmless; the build stamps the
commit SHA when it can find one and carries on when it cannot.)

`build.js` also copies `../css/modules/weather-overlay.css` from the dashboard repository when
that repository is present. It is not present here, and the file is already in the overlay
source as `css/weather-overlay.css`, so the copy is skipped and the build says so. Nothing is
missing on that path.

## Why publish this at all, then

Because the alternative is two minified files with no explanation, in a repository whose whole
claim is that you can read what runs on your device. The configuration tells you exactly how the
bundles are produced, which flags, which shims, and which imports come from where — and the table
above tells you precisely what you would need to close the gap. That is a smaller gap, honestly
described, rather than an unexplained one.
