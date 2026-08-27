package com.dashieapp.Dashie.halite.music.sendspin

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import java.security.MessageDigest

/**
 * Settings for the embedded Sendspin client.
 * Replaces SendSpinDroid's UserSettings with sensible defaults for Dashie.
 * Player ID is derived from a hardware-tied stable device ID so the same
 * physical device always registers as the same MA player, even after app
 * reinstall or a switch between Dashie build flavors (each flavor has a
 * different package ID, which is what makes ANDROID_ID alone unreliable
 * for "one physical device = one player" on API 26+).
 */
object SendspinSettings {
    private const val PREFS_NAME = "dashie_sendspin"
    private const val KEY_PLAYER_ID = "sendspin_player_id"

    private var prefs: SharedPreferences? = null
    private var _playerId: String? = null
    private var appContext: Context? = null

    // v2: drop packageName from the ID hash so every build flavor (local/staging/
    // prod/firetv/sideload) on one physical device shares a single MA player.
    // Bumping the key forces existing installs to re-derive the ID once.
    private const val KEY_MIGRATED_TO_STABLE = "migrated_to_stable_id_v2"

    /** Must be called once at app startup with application context. */
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // One-time migration: replace random UUID with device-stable ID
        val migrated = prefs?.getBoolean(KEY_MIGRATED_TO_STABLE, false) ?: false
        if (!migrated) {
            val stableId = deriveStableId()
            val oldId = prefs?.getString(KEY_PLAYER_ID, null)
            _playerId = stableId
            prefs?.edit()
                ?.putString(KEY_PLAYER_ID, stableId)
                ?.putBoolean(KEY_MIGRATED_TO_STABLE, true)
                ?.apply()
            if (oldId != null && oldId != stableId) {
                android.util.Log.i("SendspinSettings", "Migrated player ID: $oldId → $stableId")
            } else {
                android.util.Log.i("SendspinSettings", "Stable player ID: $stableId")
            }
        } else {
            _playerId = prefs?.getString(KEY_PLAYER_ID, null)
        }
    }

    fun getPlayerId(): String {
        if (_playerId == null) {
            _playerId = deriveStableId()
            prefs?.edit()?.putString(KEY_PLAYER_ID, _playerId)?.apply()
            android.util.Log.i("SendspinSettings", "Generated stable player ID: $_playerId")
        } else {
            android.util.Log.d("SendspinSettings", "Using persisted player ID: $_playerId")
        }
        return _playerId!!
    }

    /**
     * Derive a deterministic 16-char hex ID from a hardware-tied stable
     * device ID. We read the same cached value DeviceInfoHandler and
     * VoiceLicenseManager write (`dashie_device_identity/stable_device_id`),
     * which is Widevine MediaDrm-derived on supported devices and falls
     * back to ANDROID_ID otherwise. ANDROID_ID alone is scoped per signing
     * key + package on API 26+, so it varies across Dashie build flavors
     * on the same physical device — that's what makes the hardware-tied
     * stable ID necessary for the "one physical device = one player"
     * property.
     *
     * NOTE: Existing installs that already migrated to v2 (ANDROID_ID-based)
     * keep their cached player_id and never see this function — this new
     * derivation only runs on fresh installs.
     */
    private fun deriveStableId(): String {
        val ctx = appContext
        val stableDeviceId = ctx?.let { com.dashieapp.Dashie.util.StableDeviceId.read(it) } ?: ""
        val input = "dashie-sendspin-$stableDeviceId"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun setPlayerId(id: String) {
        _playerId = id
        prefs?.edit()?.putString(KEY_PLAYER_ID, id)?.apply()
    }

    /** Preferred audio codec. FLAC for quality, Opus for bandwidth. */
    fun getPreferredCodec(): String = "flac"

    /** Low memory mode uses smaller audio buffers. Disabled for tablets. */
    val lowMemoryMode: Boolean = false

    /** High power mode enables infinite reconnect. Enabled for dashboard use. */
    val highPowerMode: Boolean = true
}
