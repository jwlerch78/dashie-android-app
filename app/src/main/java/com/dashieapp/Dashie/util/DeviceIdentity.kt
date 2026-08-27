package com.dashieapp.Dashie.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * **The** hardware-tied device identity. One definition, for both editions.
 *
 * This ID keys the 7-day device voice trial and the `user_devices` rows. If it ever changes
 * for an existing install, that install silently looks like a **new device** — no error, no
 * log, just orphaned identity and a re-granted trial. That is the failure this file exists to
 * make impossible.
 *
 * ## Why this file exists (Phase 2d-i, 2026-08-01)
 *
 * Before this, the same prefs file and key were derived **three** times independently:
 *
 * | Site | Role | Widevine path | Terminal fallback |
 * |---|---|---|---|
 * | `VoiceLicenseManager.getStableDeviceId()` | writer | SHA-256 → hex → take(32) | ANDROID_ID, then `dl_<uuid>` |
 * | `DeviceInfoHandler.getStableDeviceId()` | writer | **byte-identical** | ANDROID_ID only |
 * | `util.StableDeviceId.read()` | reader | — | ANDROID_ID |
 *
 * On a Widevine device the two writers agreed exactly. On a device **without** Widevine their
 * terminal fallbacks differed, and whichever subsystem ran first won the cache — a race
 * deciding device identity. Pre-existing; not introduced by the edition split, and found only
 * because the split forced someone to read all three.
 *
 * Standing rule 1 says SHARE before writing a second copy. This is that rule applied
 * retroactively: eliminate the mirror rather than gate it.
 *
 * ## The invariant — do not break it
 *
 * 🔴 **[PREFS_NAME] and [KEY] must never change.** Both historic writers read the cached value
 * before deriving anything, so every existing install already has its ID stored under these
 * exact strings. Keeping them identical is what makes this refactor a no-op on upgrade. The
 * derivation below only ever runs for a *fresh* install or a device that somehow never cached.
 *
 * Which is also why a before/after comparison on an upgraded device proves nothing on its own:
 * it passes vacuously off the cache. The derivation needs a fresh-derive test
 * (`pm clear` on a scratch install) to be exercised at all.
 */
object DeviceIdentity {

    private const val TAG = "DeviceIdentity"

    /** 🔴 Load-bearing. See the invariant note above. */
    private const val PREFS_NAME = "dashie_device_identity"

    /** 🔴 Load-bearing. See the invariant note above. */
    private const val KEY = "stable_device_id"

    private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    /**
     * The cached ID, deriving and storing it on first call.
     *
     * Never rewritten once cached — even if Widevine becomes available later — because HA
     * integrations and the licence server key on the first-seen value.
     */
    fun stableId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotEmpty() }?.let { return it }

        val id = derive(context)
        prefs.edit().putString(KEY, id).apply()
        Log.i(TAG, "Cached stable device ID (first derive)")
        return id
    }

    /**
     * Read the cached ID without deriving one. Returns ANDROID_ID when nothing is cached yet,
     * matching the historic [StableDeviceId] reader — for display/diagnostic callers that must
     * not have the side effect of minting an identity.
     */
    fun peek(context: Context): String {
        val cached = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null)
        if (!cached.isNullOrEmpty()) return cached
        return androidId(context) ?: ""
    }

    /**
     * Widevine `MediaDrm` device-unique ID, SHA-256'd to hex and truncated to 32 chars.
     *
     * ⚠️ **The fallback chain is a reconciliation, not a refactor.** The two historic writers
     * disagreed here, so one had to win: this is `VoiceLicenseManager`'s chain
     * (ANDROID_ID → synthetic), chosen because the licence server has been keyed against it.
     * `DeviceInfoHandler`'s chain stopped at ANDROID_ID and could return empty; that behaviour
     * is deliberately dropped.
     */
    private fun derive(context: Context): String = try {
        val drm = android.media.MediaDrm(WIDEVINE_UUID)
        val raw = drm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
        drm.close()
        MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it) }
            .take(32)
    } catch (e: Throwable) {
        Log.w(TAG, "DROP: Widevine unavailable for stable device ID — falling back to " +
            "ANDROID_ID. This device's identity is weaker than normal; if BOTH fallbacks " +
            "fire the ID is synthetic and will not survive a factory reset.")
        androidId(context) ?: synthetic()
    }

    private fun androidId(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    /**
     * Last-resort synthetic ID from `Build.*`. Stable for a given model but NOT unique per
     * device — only reached when Widevine and ANDROID_ID both fail.
     */
    private fun synthetic(): String {
        val deviceInfo = "${Build.MANUFACTURER}_${Build.MODEL}_${Build.DEVICE}"
        return "dl_${UUID.nameUUIDFromBytes(deviceInfo.toByteArray()).toString().replace("-", "")}"
    }
}
