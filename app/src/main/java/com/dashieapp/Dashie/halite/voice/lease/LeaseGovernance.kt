package com.dashieapp.Dashie.halite.voice.lease

/**
 * Does the household capability lease govern THIS device at all?
 *
 * ## The bug this exists to prevent
 *
 * John, 2026-08-04, on a fully signed-in Mio 15: voice/AI was refused with
 * `LEASE: renew-refused — reason=sharing_disabled`, and the UI told him to enable **household
 * sharing**. That device holds its own account session and its own entitlement. Household sharing
 * exists to lend the account to **un-provisioned LAN kiosks** — so a household-level "no" was both
 * denying capability the device already pays for AND showing a remedy that was never his to apply.
 *
 * M's class diagnosis: *a property of the local HA setup standing in for a fact about this device's
 * own session* — the same shape as the Account-page bug. The principle it yields:
 *
 * > **A device with its own account session uses its own entitlement. The lease governs only
 * > devices that BORROW.**
 *
 * ## Why `kioskProvisionedSession` and not `isLinked` or `haOnlyDisplay`
 *
 * Reused, not minted — a second predicate for the same question is exactly how two answers drift
 * apart. `AccountPreferences.kioskProvisionedSession` already asks precisely this: was **this
 * session** provisioned by the kiosk flow (it borrows), or did a human sign in on this device (it
 * owns)? `KioskSessionProvisioner` sets it true where it mints the `ha_kiosk` session;
 * `onDashieAuthComplete` sets it false, because a login performed here is by definition not
 * kiosk-provisioned.
 *
 * `haOnlyDisplay` is the wrong axis and M already documented why: it is a sticky DEVICE-DISPLAY
 * property, so an HA-displaying tablet later signed into normally still carries it. That is the
 * exact substitution that produced the Account-page bug.
 *
 * ## Truth table
 *
 * | device | `isLinked` | `kioskProvisionedSession` | lease |
 * |---|---|---|---|
 * | no account at all (Chickadee / plain kiosk) | false | false | **runs** — it borrows |
 * | household-provisioned kiosk | true | true | **runs** — it borrows |
 * | human signed in here (John's Mio) | true | false | **skipped** — it owns |
 *
 * ⚠️ **The one known soft edge, stated rather than discovered later.** `kioskProvisionedSession`
 * defaults false, so a shared kiosk provisioned *before* that flag existed reads "owns its session"
 * for exactly one boot and skips the lease. The failure direction is the safe one — it degrades to
 * free engines rather than wrongly claiming metered capability, so it cannot overspend — and the
 * provisioner sets the flag true again on its next boot.
 *
 * Kept pure and Android-free (booleans in, boolean out) for the same reason the rest of this
 * package is: it makes the rule unit-testable without a device, and a branch buried in
 * `HaliteVoiceController` would not have been.
 */
object LeaseGovernance {

    /**
     * @param isLinked whether this device has any Dashie account session at all.
     * @param kioskProvisionedSession whether THIS session was minted by the kiosk provisioner.
     * @return true when the household lease should govern this device (i.e. it borrows).
     */
    fun governs(isLinked: Boolean, kioskProvisionedSession: Boolean): Boolean =
        !isLinked || kioskProvisionedSession

    /** Why the lease is not running, for [LeaseMarkers.markNotStarted]. */
    const val OWN_SESSION_REASON: String =
        "device holds its OWN account session (signed in here, not kiosk-provisioned) — it " +
            "borrows no household capability, so household sharing does not gate it"
}
