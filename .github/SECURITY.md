# Security policy

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Email **support@dashieapp.com** with the details. Include the Dashie version, the device and
Android version, and enough detail to reproduce it.

Dashie is maintained by one person, so there is no formal SLA. Reports are read and acted on;
you will get an acknowledgement, and credit in the release notes if you want it.

## What is in scope

The Android application in this repository — the kiosk WebView and its hardening, the local
HTTP API on port 2323, the voice pipeline, credential and token handling on the device, and the
bundled web overlay under `app/src/main/assets/webapp/`.

The Home Assistant add-on and the voice integration live in their own repositories
([dashie-ha](https://github.com/jwlerch78/dashie-ha),
[dashie-ha-integration](https://github.com/jwlerch78/dashie-ha-integration)); report those there,
or here if you are unsure which it is.

## Two things that are not vulnerabilities

Both come up, and both are deliberate:

**The Supabase keys in `app/build.gradle.kts` are `anon` keys.** They are the client identifier,
they ship in every published APK by design, and they grant nothing on their own — row-level
security and per-function authorisation decide access server-side against the caller's session.
Decode either one and check the claim: `role` is `anon`. A `service_role` key would be a real
finding; there isn't one, and `.gitleaks.toml` is configured so that one would fail the scan
rather than be exempted.

**Release builds fall back to the debug keystore** when the upload key is absent, so anyone can
produce a local release build. Those APKs are installable but not publishable — they are not
signed with the key the stores trust.

## Supported versions

Fixes go into the current release. There are no long-term support branches.
