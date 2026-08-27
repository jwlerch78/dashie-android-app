# Provenance

Written to be checked, not skimmed. Everything below is a thing you'd otherwise find by grepping
and have to guess at, and we would rather you learn it here than find it out later.

**This is the full app.** It is what ships on Play, Amazon, and as a sideload APK. Nothing about
the HA experience is feature-gated on paying, and the wake word, HA Assist, on-device STT and
bring-your-own-model paths involve no server of ours.

**Development happens in a private repository, and this one is published from it with a fresh
history.** The whole tree is one commit, so `git blame` here won't tell you much. Where a reason
is load-bearing, it is in a comment instead.

**The same app also powers a paid family-dashboard product.** The code for that is in here, but
doesn't do anything without an account. I decided not to strip it out, to keep everything fully
transparent.

**The package namespace is `com.dashieapp.Dashie`** and comments still reference internal context.

**There is a second brand called "Chickadee" referenced here.** It was an unreleased attempt at a
separately branded, HA-only edition. After further consideration I opted to just open up Dashie
rather than build and maintain two separate repos and brands.

**Two things do not rebuild from this tree alone:**

1. **The speech engine and models** — gitignored and fetched by `scripts/`, as described in the
   README.
2. **The two kiosk overlay bundles** — `dist/kiosk-shell.bundle.js` and
   `dist/kiosk-services.bundle.js`. Their source is here and so is the esbuild config, but both
   also import a set of modules from the private web-dashboard repo (settings sync, the Supabase
   edge client, logging, the intent classifier, some utilities). Those imports are inlined into
   the bundles that ship, so the code is in every APK; the readable source of those specific
   modules is not in this repository.
   [`tools/kiosk-overlay-build/README.md`](tools/kiosk-overlay-build/README.md) provides more
   details.

## Network connections

No account is required, there are no analytics or crash-reporting SDKs, no advertising
identifiers, and the microphone is not streamed anywhere before the wake word fires.

| host | when | off switch |
|---|---|---|
| `api.open-meteo.com`, `geocoding-api.open-meteo.com`, `api.zippopotam.us`, `nominatim.openstreetmap.org` | the weather forecast, and turning the zip or place you entered into coordinates | the forecast runs either way; the geocoding step is skipped entirely if you leave the location field empty |
| `ipapi.co` | when no location can be resolved — no zip, or one that fails to geocode — and Home Assistant gives no home zone and nothing is cached; it receives your IP | set a zip that resolves; a zip that fails still falls through to here |
| `github.com`, Dashie object storage | speech models you choose to download — **and** an automatic wake-word model manifest check whenever the wake word is running | downloads: don't pick a model. The manifest check: none today |
| `unsplash.com` | you pick Unsplash as a screensaver source | pick another source |
| Dashie servers | only if you sign in: the optional cloud voice pipeline, cloud photo features (which resolve photo GPS to a place name), and usage metadata for your account | stay signed out |
| update check | at startup, on the daily refresh, and a 6-hourly poll | Advanced → "Automatic Update Checks" (on Play builds the store can still update the app on its own schedule) |
| wake-word sample upload | **opt-in**, off by default | Voice & AI → "Wake Word Training" — turn it back off any time |
| monthly check-in — install token, hashed WiFi name, sign-in status | **opt-in**, off by default | Advanced → "Share Performance Data" |

Voice *content* follows the pipeline you pick: HA Assist goes to your Home Assistant, on-device
transcription never leaves the tablet, and only the cloud pipeline sends a turn to us. The
wake-word manifest check in the table is a separate thing and carries no audio. Signed in on a
local pipeline, a turn's content still never reaches us — prompt and response are `NULL` in our
usage ledger, and you can verify that from your own account.

If you find traffic on your network that contradicts this, that is a bug and we want the issue.

**Dashie's development is AI-assisted.** However, the code is reviewed and tested by the dev. It
started as a fully hand-developed app, but AI has just gotten too good not to leverage it.

**One link is intentionally hidden right now.** The README's pointer to the Home Assistant
**add-on** is commented out until that edition releases. It is a forward reference to something
not yet shipped, not a broken link — the repository is public. It comes back when the edition
does. (The HACS **integration** is a different thing, ships today, and is linked from the README.)
