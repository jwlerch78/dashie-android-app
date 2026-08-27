package com.dashieapp.Dashie.util

import android.content.Context

/**
 * Read the cached hardware-tied stable device ID.
 *
 * Use this everywhere a per-device identifier is needed for DISPLAY or DIAGNOSTICS —
 * telemetry, crash reports, control-centre footer — so the value the user sees matches what
 * shows up in server-side logs.
 *
 * Kept as a thin delegate to [DeviceIdentity] rather than deleted: ~15 call sites read through
 * this name, and churning them would have buried the one change in Phase 2d-i that matters
 * (collapsing three independent derivations into one) inside an unrelated rename.
 *
 * **Reads only — never mints.** If nothing is cached yet this falls back to `ANDROID_ID`
 * without writing, so a diagnostic read can't decide the device's permanent identity as a side
 * effect. Callers that legitimately need the ID to exist should use [DeviceIdentity.stableId].
 */
object StableDeviceId {
    fun read(context: Context): String = DeviceIdentity.peek(context)
}
