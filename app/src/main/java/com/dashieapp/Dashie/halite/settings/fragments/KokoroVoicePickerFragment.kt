package com.dashieapp.Dashie.halite.settings.fragments

import android.content.Context
import android.util.Log
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import com.dashieapp.Dashie.halite.voice.VoiceLabelDecoder
import com.dashieapp.Dashie.halite.voice.tts.KokoroVoiceProbe
import org.json.JSONArray
import org.json.JSONObject

/**
 * GAP-2 Piece A (Kokoro): picker for the local TTS voice (voice.localTtsVoiceId). Probes the
 * configured local box (KokoroVoiceProbe → GET /v1/audio/voices), caches the list for an instant
 * re-open, and writes the picked id (account-scoped → propagates via the account upload payload).
 * A "Custom…" row keeps the manual-entry fallback. Mirrors PiperVoicePickerFragment.
 */
class KokoroVoicePickerFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "KokoroVoicePicker"
        private const val CUSTOM_ITEM_ID = "__custom_voice__"
        private const val CACHE_PREFS = "kokoro_voice_cache"
        private const val CACHE_KEY = "voices_json"
    }

    override val title = "Local TTS Voice"

    private val voicePrefs by lazy { VoicePreferences(requireContext()) }
    private var voices: List<KokoroVoiceProbe.Voice> = emptyList()
    private var loaded = false
    private var error: String? = null

    override fun onResume() {
        super.onResume()
        readCache()?.let { voices = it; loaded = true }
        refreshItems()
        KokoroVoiceProbe(requireContext()).probe(
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
        val current = voicePrefs.localTtsVoiceId
        val items = mutableListOf<SettingsItem>()
        items.add(SettingsItem.SectionHeader(id = "header", title = "Local TTS Voice"))

        if (!loaded && voices.isEmpty()) {
            items.add(SettingsItem.Info(id = "loading", text = "Probing the local TTS box for voices…"))
        }
        for (v in voices) {
            items.add(SettingsItem.Checkmark(
                id = "voice_${v.id}",
                // Friendly Kokoro label via the shared decoder ("Lily (British female)"); Piper ids
                // that leak in decode too. Falls back to the probe's name / the raw id.
                label = VoiceLabelDecoder.localVoiceLabel(v.id, v.name),
                isChecked = v.id == current,
                sublabel = v.id
            ))
        }
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
        voicePrefs.localTtsVoiceId = voiceId
        // Account-scoped upload + pipeline refresh (localTtsVoiceId rides the account-voice payload).
        requireContext().sendBroadcast(
            android.content.Intent("com.dashieapp.Dashie.ACTION_AI_VOICE_SETTINGS_CHANGED").apply {
                setPackage(requireContext().packageName)
                putExtra("changedKey", "voice.localTtsVoiceId")
            }
        )
        refreshItems()
        refreshParentFragment()
    }

    private fun promptCustom() {
        val input = EditText(requireContext()).apply {
            setText(voicePrefs.localTtsVoiceId)
            hint = "af_heart"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Local TTS Voice")
            .setMessage("Enter a voice id.")
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

    private fun readCache(): List<KokoroVoiceProbe.Voice>? = try {
        val raw = context?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)?.getString(CACHE_KEY, null)
            ?: return null
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull {
            val o = arr.optJSONObject(it) ?: return@mapNotNull null
            KokoroVoiceProbe.Voice(o.optString("id"), o.optString("name"))
        }.ifEmpty { null }
    } catch (e: Exception) { null }

    private fun writeCache(list: List<KokoroVoiceProbe.Voice>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
            context?.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                ?.edit()?.putString(CACHE_KEY, arr.toString())?.apply()
        } catch (e: Exception) { Log.w(TAG, "cache write failed: ${e.message}") }
    }
}
