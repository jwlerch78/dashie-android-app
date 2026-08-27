package com.dashieapp.Dashie.halite.settings.pages.calendar

import com.dashieapp.Dashie.halite.settings.SettingsActivity

/**
 * The CLOUD calendar-account flows — Google / Microsoft / Apple add-account and re-auth.
 *
 * ## Why this interface exists in `main/` while its implementation does not
 *
 * Chickadee has no cloud calendar accounts; its calendar source is Home Assistant's own
 * entities (the ruling, 2026-08-02). Those rows were already gated OFF at runtime by
 * [com.dashieapp.Dashie.edition.EditionSeams.hasCloudCalendarAccounts] — but **a runtime `if`
 * still COMPILES its body.** So the flows themselves stayed in `main/` and shipped inside the
 * Chickadee artifact: unreachable, but present, along with `dashieapp.com` and the Apple
 * credential dialog. The product claim is about the ARTIFACT, so unreachable-but-present is
 * not good enough.
 *
 * Moving the implementations to `src/dashie/java/` removes them from the Chickadee build
 * entirely — which is exactly why the CALL SITES had to move behind this interface too, or
 * `main/` would reference symbols that no longer exist in that edition.
 *
 * ## What deliberately did NOT move
 *
 * - **`importHomeAssistantCalendars`** — Chickadee's only calendar source.
 * - 🔴 **`removeCalendarAccount`** — it serves cloud accounts *and* the Home Assistant one.
 *   `CalendarBridgeWiring.kt:141` builds that account with `accountType: 'ha'`, and
 *   `showRemoveCalendarAccountScreen` registers a remove callback **per account**, HA included.
 *   Moving it would have left a Chickadee household able to import HA calendars and never able
 *   to remove them — and because the registration is by string through the callback registry,
 *   **that regression would have COMPILED.** It is the one failure here that the build could
 *   not have caught, so it is the one thing deliberately left in `main/`.
 *
 * `reAuthCalendarAccount` *is* here, and that was verified rather than assumed: the HA account
 * object never sets `authInvalid`, and the re-auth registrar filters on exactly that flag, so
 * an HA account can never receive a re-auth callback.
 *
 * ## Not a second split point
 *
 * There is **no `chickadeeStub` twin of this file**, deliberately. `EditionSeams`' own KDoc
 * records that one source-set-split symbol was chosen over seven, because the split is the part
 * most likely to be got wrong. This interface is visible to both editions from `main/`; only the
 * IMPLEMENTATION is edition-only, which is the same shape as `VoiceLicenseManager`'s move and
 * carries no same-name twin to keep in sync.
 */
interface CloudCalendarFlows {

    /**
     * Register the "add a Google / Microsoft / Apple account" action callbacks.
     *
     * Called unconditionally by `main/`; the seam returns `null` in an edition without cloud
     * accounts, so nothing is registered there. An unregistered action is a **loud registry
     * miss** rather than a working path into a flow the edition does not have — belt and
     * braces alongside the schema already omitting the rows.
     */
    fun registerAddAccountCallbacks(activity: SettingsActivity)

    /**
     * Register the per-account re-auth callback for an account whose token was revoked.
     *
     * Idempotent — the callback registry replaces an existing key — so it is safe to call from
     * both the phase-1 and phase-2 account-load handlers, which is how the caller uses it.
     */
    fun registerReauthCallback(
        activity: SettingsActivity,
        provider: String,
        accountType: String,
        email: String,
    )
}
