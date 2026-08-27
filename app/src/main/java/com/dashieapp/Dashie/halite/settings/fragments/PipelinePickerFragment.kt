package com.dashieapp.Dashie.halite.settings.fragments

import android.util.Log
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import com.dashieapp.Dashie.halite.sidebar.dialogs.VoiceApiDialogs
import com.dashieapp.Dashie.halite.voice.HaAssistClient

/**
 * Fragment for selecting the HA Assist pipeline.
 *
 * Navigated to from the Voice & AI schema page. Renders the native schema-driven
 * settings look (grouped cells with checkmarks) instead of an AlertDialog so the
 * layout matches the rest of the settings UI.
 *
 * Flow:
 * 1. onResume triggers fetchPipelines() if we haven't loaded yet.
 * 2. While loading, shows a "Preferred" cell + "Loading pipelines…" info item.
 * 3. On success, shows "Default" section (Preferred) and "All Pipelines" section
 *    with one Checkmark cell per pipeline, sublabel = STT/LLM/TTS engine summary.
 * 4. Tap on a cell saves the selection to HalitePreferences and navigates back;
 *    the parent schema page's onResume re-runs refreshItems() so its "Pipeline"
 *    row picks up the new value.
 */
class PipelinePickerFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "PipelinePicker"
    }

    override val title: String = "Assist Pipeline"

    private val halitePrefs by lazy { HalitePreferences(requireContext()) }

    // Use the dialog's existing pipeline fetch + description helpers so we don't
    // duplicate that logic here. VoiceApiDialogs requires an Activity constructor
    // arg but we only call its two public helpers, not its dialog-show methods.
    private val voiceApiDialogs by lazy { VoiceApiDialogs(requireActivity(), halitePrefs) }

    private enum class LoadState { LOADING, SUCCESS, ERROR }

    private var pipelines: List<HaAssistClient.Pipeline> = emptyList()
    private var loadState: LoadState = LoadState.LOADING
    private var errorText: String? = null

    override fun onResume() {
        super.onResume()
        // Kick off fetch on first entry; subsequent resumes (e.g. after returning
        // from a nested nav) refresh display without re-fetching.
        if (pipelines.isEmpty() && loadState != LoadState.ERROR) {
            loadState = LoadState.LOADING
            fetchPipelines()
        }
        refreshItems()
    }

    override fun getItems(): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()

        // DEFAULT section — "Preferred" cell (HA's auto-selected pipeline)
        items.add(SettingsItem.SectionHeader(id = "header_default", title = "Default"))
        val preferredName = pipelines.find { it.isPreferred }?.name
            ?: halitePrefs.voice.preferredPipelineCachedName.takeIf { it.isNotEmpty() }
        items.add(SettingsItem.Checkmark(
            id = "preferred",
            label = "Preferred",
            isChecked = !halitePrefs.voice.hasCustomPipeline,
            sublabel = preferredName?.let { "Currently: $it" }
        ))

        when (loadState) {
            LoadState.LOADING -> {
                items.add(SettingsItem.Info(id = "loading", text = "Loading pipelines…"))
            }
            LoadState.ERROR -> {
                items.add(SettingsItem.Info(
                    id = "error",
                    text = errorText ?: "Couldn't load pipelines from Home Assistant."
                ))
            }
            LoadState.SUCCESS -> {
                if (pipelines.isEmpty()) {
                    items.add(SettingsItem.Info(
                        id = "empty",
                        text = "No custom pipelines configured in Home Assistant."
                    ))
                } else {
                    items.add(SettingsItem.SectionHeader(id = "header_all", title = "All Pipelines"))
                    pipelines.forEach { pipeline ->
                        val description = voiceApiDialogs.buildPipelineDescription(pipeline).ifEmpty { null }
                        items.add(SettingsItem.Checkmark(
                            id = "pipeline_${pipeline.id}",
                            label = pipeline.name,
                            isChecked = halitePrefs.voice.voicePipelineId == pipeline.id,
                            sublabel = description
                        ))
                    }
                }
            }
        }
        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        if (item !is SettingsItem.Checkmark) return
        when {
            item.id == "preferred" -> {
                halitePrefs.voice.voicePipelineId = ""
                halitePrefs.voice.voicePipelineName = ""
                Log.i(TAG, "🎙️ Voice pipeline = Preferred (HA default)")
                navigateBack()
            }
            item.id.startsWith("pipeline_") -> {
                val pipelineId = item.id.removePrefix("pipeline_")
                val pipeline = pipelines.find { it.id == pipelineId } ?: return
                halitePrefs.voice.voicePipelineId = pipeline.id
                halitePrefs.voice.voicePipelineName = pipeline.name
                Log.i(TAG, "🎙️ Voice pipeline = ${pipeline.name} (${pipeline.id})")
                navigateBack()
            }
        }
    }

    private fun fetchPipelines() {
        voiceApiDialogs.fetchPipelinesFromHa(
            onSuccess = { fetched ->
                activity?.runOnUiThread {
                    pipelines = fetched
                    fetched.find { it.isPreferred }?.let {
                        halitePrefs.voice.preferredPipelineCachedName = it.name
                    }
                    loadState = LoadState.SUCCESS
                    errorText = null
                    refreshItems()
                }
            },
            onError = { error ->
                activity?.runOnUiThread {
                    errorText = error
                    loadState = LoadState.ERROR
                    refreshItems()
                }
            }
        )
    }
}
