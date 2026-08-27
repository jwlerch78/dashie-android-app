# `src/dashie/java` — the paid-edition source set

Compiled into the **Dashie** flavors only (`local, staging, prod, firetv, amazon,
sideload, release`). The `chickadee*` flavors compile `src/chickadeeStub/java`
instead — same API, no-op bodies.

**Empty as of phase 2b.** Code moves here in 2c–2e, one seam at a time, keeping
both editions compiling at every commit. What lands here: account, licence,
billing/trial, credits/metering, subscription, paywall UI, family settings
schemas. Manifest: `dashieapp_staging/.reference/build-plans/20260731_PHASE2A_KOTLIN_TRIAGE.md`.

⚠️ Anything added here is **absent** from the published Chickadee build. That is
the point — Chickadee's claim is that the commercial code is not in the tree, not
that it is switched off. Do not reach for a runtime `if (EDITION == …)` instead.
