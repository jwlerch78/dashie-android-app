package com.dashieapp.Dashie.halite.settings.fragments

import android.content.Context
import android.util.Log
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import com.dashieapp.Dashie.halite.voice.tts.PiperVoiceProbe
import org.json.JSONArray
import org.json.JSONObject

/**
 * GAP-2 Piece A: picker for the HA Piper TTS voice (voice.haTtsVoiceId). Probes the configured
 * Piper engine on-device (PiperVoiceProbe → HA WS tts/engine/voices), caches the list for an
 * instant re-open, and writes the picked voice_id (account-scoped → propagates via the account
 * upload payload). A "Custom…" row keeps the free-text fallback for a voice the probe didn't return.
 */
class PiperVoicePickerFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "PiperVoicePicker"
        private const val CUSTOM_ITEM_ID = "__custom_voice__"
        private const val CACHE_PREFS = "piper_voice_cache"
        private const val CACHE_KEY = "voices_json"
    }

    override val title = "Piper Voice"

    private val voicePrefs by lazy { VoicePreferences(requireContext()) }
    private var voices: List<PiperVoiceProbe.Voice> = emptyList()
    private var loaded = false
    private var error: String? = null

    override fun onResume() {
        super.onResume()
        // Instant render from cache, then re-probe.
        readCache()?.let { voices = it; loaded = true }
        refreshItems()
        PiperVoiceProbe(requireContext()).probe(
            onVoices = { list ->
                voices = list; loaded = true; error = null
                writeCache(list)
                refreshItems()
            },
            onError = { msg ->
                loaded = true; error = msg
                refreshItems()
            }
        )
    }

    override fun getItems(): List<SettingsItem> {
        val current = voicePrefs.haTtsVoiceId
        val items = mutableListOf<SettingsItem>()
        items.add(SettingsItem.SectionHeader(id = "header", title = "Piper Voice"))

        if (!loaded && voices.isEmpty()) {
            items.add(SettingsItem.Info(id = "loading", text = "Probing Home Assistant for voices…"))
        }
        for (v in voices) {
            items.add(SettingsItem.Checkmark(
                id = "voice_${v.id}",
                // Friendly "Amy (fast/balanced/high quality)" via the shared decoder (console parity).
                label = com.dashieapp.Dashie.halite.voice.VoiceLabelDecoder.piperVoiceLabel(v.name, v.id),
                isChecked = v.id == current,
                sublabel = v.id
            ))
        }
        // Free-text fallback — a custom/uncommon voice id the probe didn't list (or is currently set).
        val currentUnlisted = current.isNotBlank() && voices.none { it.id == current }
        items.add(SettingsItem.Checkmark(
            id = CUSTOM_ITEM_ID,
            label = "Custom…",
            isChecked = currentUnlisted,
            sublabel = if (currentUnlisted) current else "Enter a voice id manually"
        ))
        if (error != null && voices.isEmpty()) {
            items.add(SettingsItem.Info(id = "error", text = "Couldn't probe voices: $error. Use Custom… to enter one."))
        }
        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        when {
            item.id == CUSTOM_ITEM_ID -> promptCustom()
            item.id.startsWith("voice_") -> select(item.id.removePrefix("voice_"))
        }
    }

    private fun select(voiceId: String) {
        voicePrefs.haTtsVoiceId = voiceId
        // Account-scoped upload + pipeline refresh (haTtsVoiceId now rides the account-voice payload).
        requireContext().sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_AI_VOICE_SETTINGS_CHANGED").apply {
                setPackage(requireContext().packageName)
                putExtra("changedKey", "voice.haTtsVoiceId")
            }
        )
        refreshItems()
        refreshParentFragment()
    }

    private fun promptCustom() {
        val input = EditText(requireContext()).apply {
            setText(voicePrefs.haTtsVoiceId)
            hint = "en_US-amy-low"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Piper Voice")
            .setMessage("Enter a Piper voice id.")
            .setView(input)
            .setPositiveButton("Save") { _, _ -> select(input.text.toString().trim()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshParentFragment() {
        val activity = activity as? SettingsActivity ?: return
        activity.supportFragmentManager.fragments
            .filterIsInstance<com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>()
            .forEach { it.refresh() }
    }

    private fun readCache(): List<PiperVoiceProbe.Voice>? = try {
        val raw = context?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)?.getString(CACHE_KEY, null)
            ?: return null
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            PiperVoiceProbe.Voice(o.optString("id"), o.optString("name"))
        }.ifEmpty { null }
    } catch (e: Exception) { null }

    private fun writeCache(list: List<PiperVoiceProbe.Voice>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
            context?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(CACHE_KEY, arr.toString())?.apply()
        } catch (e: Exception) { Log.w(TAG, "cache write failed: ${e.message}") }
    }
}
