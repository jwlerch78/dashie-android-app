package com.dashieapp.Dashie.edition

import android.util.Log
import com.dashieapp.Dashie.BuildConfig

/**
 * The HA integration's API prefix **on the household's own Home Assistant box**.
 *
 * One prefix per brand (`JS_KOTLIN_CONTRACTS #63`, Thread B, 2026-08-01):
 *
 * | Edition | Prefix | Served by |
 * |---|---|---|
 * | Dashie | `/api/dashie` | `dashie` device integration + `dashie_voice` |
 * | Chickadee | `/api/chickadee` | `chickadee` device integration + `chickadee_voice` |
 *
 * **One, not two.** An earlier assumption here was that voice would need its own
 * `/api/<brand>_voice/` prefix. It does not: `/api/<brand>/voice/` is the *canonical*
 * cross-integration path that the cede handshake (#60) arbitrates between the device and
 * voice integrations — not a legacy alias. Chickadee's tree carries no legacy aliases at all,
 * so this constant covers every call the APK makes.
 *
 * (Paths above are written without a trailing wildcard on purpose: Kotlin nests block
 * comments, so a literal slash-star inside this KDoc opens one and swallows the rest of it.)
 *
 * ## 🚧 What this is NOT — do not widen it (Thread D, 2026-08-01)
 *
 * There are **two different base-URL-shaped things** in this system and collapsing them would
 * be very hard to unpick later:
 *
 *  1. **This** — a *path prefix* on a host the user already owns and has already authenticated
 *     to (the HA box). It never carries a scheme or a host.
 *  2. **A hosted metering-service base URL** — an *external endpoint* with its own accounts and
 *     billing, if that ever lands (`20260801_METERING_SERVICE_SHAPE.md`). That would arrive as
 *     an engine *choice* in Phase 2f, and belongs in its own constant with its own lifecycle.
 *
 * If you find yourself adding a scheme, a host, or a token to this file, you are building (2)
 * inside (1). Stop and give it its own home.
 *
 * ## Why it derives from `EDITION` rather than its own `buildConfigField`
 *
 * `BuildConfig.EDITION` already exists on every flavor (Phase 2b) and is exactly the brand
 * axis this prefix follows — #63 is literally "one prefix per brand". A second per-flavor field
 * would be a hand-mirror of the first across ten flavor blocks, with the same
 * forget-one-flavor failure mode as `allFlavors`. The `when` is exhaustive and loud rather than
 * a string concatenation, so an unrecognised edition can't silently mint a prefix nothing serves.
 */
object ApiPaths {

    private const val TAG = "ApiPaths"

    /**
     * Prefix only — no scheme, no host, no trailing slash. Compose as
     * `"$haOrigin${ApiPaths.HA}/voice/status"`.
     */
    val HA: String = when (BuildConfig.EDITION) {
        "dashie" -> "/api/dashie"
        "chickadee" -> "/api/chickadee"
        else -> {
            Log.w(TAG, "DROP: unrecognised BuildConfig.EDITION '${BuildConfig.EDITION}' — " +
                "no API prefix is defined for it. Falling back to '/api/dashie', which is " +
                "WRONG for any non-Dashie edition and will 404 against its integration. " +
                "Add the edition here and to JS_KOTLIN_CONTRACTS #63.")
            "/api/dashie"
        }
    }
}
