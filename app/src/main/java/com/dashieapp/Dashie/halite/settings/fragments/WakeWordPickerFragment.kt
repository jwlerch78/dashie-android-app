package com.dashieapp.Dashie.halite.settings.fragments

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.microfrontend.MicroFrontend
import com.dashieapp.Dashie.wakeword.microwakeword.MicroWakeWordDetector
import com.dashieapp.Dashie.wakeword.models.WakeWordEngine
import com.dashieapp.Dashie.wakeword.models.WakeWordModel
import com.dashieapp.Dashie.wakeword.models.WakeWordModelManager
import com.dashieapp.Dashie.wakeword.models.WakeWordSensitivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Static holder for live confidence from any wake word engine.
 * Written by the heartbeat callback in HaliteVoiceController,
 * read by WakeWordPickerFragment for the confidence meter.
 */
object LiveConfidenceHolder {
    @Volatile var lastConfidence: Float = 0f
        private set

    @Volatile var suppressDetection: Boolean = false

    /** Whether the last reading exceeded the threshold (for color logic). */
    @Volatile var reachedThreshold: Boolean = false
        private set

    // Hold peak for 1 second after threshold is reached before decaying
    private var holdUntil: Long = 0L

    fun update(confidence: Float) {
        lastConfidence = maxOf(lastConfidence, confidence)
    }

    /**
     * Mark that the threshold was reached — hold peak for 1 second.
     */
    fun markThresholdReached() {
        reachedThreshold = true
        holdUntil = System.currentTimeMillis() + 1000
    }

    fun getAndDecay(): Float {
        val current = lastConfidence
        val now = System.currentTimeMillis()
        if (now < holdUntil) {
            // Still in hold period — don't decay
            return current
        }
        // Decay
        reachedThreshold = false
        lastConfidence = (lastConfidence * 0.6f).let { if (it < 0.01f) 0f else it }
        return current
    }
}

/**
 * Fragment for selecting wake word model.
 * Shows sensitivity at top, then Fluidity (EI) and microWakeWord model sections.
 */
class WakeWordPickerFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "WakeWordPicker"
        // GAP-2: the "Default (X)" row — follow the account default wake word.
        private const val DEFAULT_ITEM_ID = "__default_wake_word__"
    }

    override val title: String = "Wake Word"

    private val halitePrefs by lazy {
        HalitePreferences(requireContext())
    }

    private val wakeWordModelManager by lazy {
        WakeWordModelManager(requireContext())
    }

    private val aiPrefs by lazy {
        com.dashieapp.Dashie.halite.preferences.AiPreferences(requireContext())
    }

    // GAP-2: whether this device is INHERITING the account default wake word (no per-device
    // override). Seeded from native prefs (kept in lockstep with the Voice & AI summary by
    // applyEffectiveAiVoice's push), so the Default row is correct instantly.
    private var isInheriting = false

    private var wakeWordOptions = listOf<WakeWordOption>()
    private var lastConfidence = 0f
    private val handler = Handler(Looper.getMainLooper())
    private var meterUpdatePending = false

    override fun onResume() {
        super.onResume()
        // Suppress wake word detection while testing on this page
        LiveConfidenceHolder.suppressDetection = true
        // GAP-2: seed the inherit state from native prefs (instant, in sync with the Voice & AI summary).
        isInheriting = aiPrefs.wakeWordInheriting
        if (wakeWordOptions.isNotEmpty()) {
            refreshItems()
        }
        // Register for live confidence updates (MWW engine — higher resolution than heartbeat)
        MicroWakeWordDetector.onLiveConfidence = { _, avgProbability ->
            LiveConfidenceHolder.update(avgProbability)
        }
        // Poll heartbeat for EI engine (no static callback available)
        startHeartbeatPolling()
    }

    override fun onPause() {
        super.onPause()
        LiveConfidenceHolder.suppressDetection = false
        MicroWakeWordDetector.onLiveConfidence = null
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * For Edge Impulse: poll the heartbeat confidence via the static holder.
     * For MWW: the onLiveConfidence callback handles it directly.
     */
    private fun startHeartbeatPolling() {
        val pollRunnable = object : Runnable {
            override fun run() {
                if (!isAdded) return
                val current = LiveConfidenceHolder.getAndDecay()
                if (current != lastConfidence) {
                    lastConfidence = current
                    refreshItems()
                }
                handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(pollRunnable, 500)
    }


    override fun getItems(): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()

        if (wakeWordOptions.isEmpty()) {
            loadWakeWordOptions()
            items.add(SettingsItem.SectionHeader(id = "header_loading", title = "Select Wake Word Model"))
            items.add(SettingsItem.Info(id = "loading", text = "Loading available models..."))
        } else {
            val activeModel = wakeWordModelManager.getActiveModel()
            val defaultCutoffPct = "${(activeModel.probabilityCutoff * 100).toInt()}%"

            // ── Sensitivity section ──
            val sensitivity = WakeWordSensitivity.fromString(halitePrefs.voice.mwwSensitivity)
            items.add(SettingsItem.SectionHeader(id = "header_sensitivity", title = "Sensitivity"))
            items.add(SettingsItem.Navigation(
                id = "sensitivity",
                label = "Detection Sensitivity",
                value = sensitivity.displayName,
                sublabel = "\"${activeModel.wakeWordName}\""
            ))

            // Live confidence bar
            // For dual engine medium: scale 0-50% → 0-85% and 50-100% → 85-100%
            // so the threshold line sits at 85% (matching high sensitivity visually)
            val rawConfidence = lastConfidence
            val displayValue: Int
            val cutoffPct: Int
            if (activeModel.isDualEngine && sensitivity == WakeWordSensitivity.MEDIUM) {
                displayValue = if (rawConfidence <= 0.50f) {
                    (rawConfidence / 0.50f * 85f).toInt()
                } else {
                    (85f + (rawConfidence - 0.50f) / 0.50f * 15f).toInt()
                }
                cutoffPct = 85
            } else if (activeModel.isDualEngine) {
                displayValue = (rawConfidence * 100).toInt()
                cutoffPct = 80
            } else {
                displayValue = (rawConfidence * 100).toInt()
                cutoffPct = (activeModel.getCutoffForSensitivity(sensitivity) * 100).toInt()
            }
            items.add(SettingsItem.ConfidenceBar(
                id = "confidence_meter",
                value = displayValue,
                threshold = cutoffPct,
                label = "${displayValue}% (threshold: ${cutoffPct}%)"
            ))

            // ── Wake word models (flat list, no engine sections) ──
            items.add(SettingsItem.SectionHeader(id = "header_models", title = "Wake Word"))
            // GAP-2 "Default" row — follow the account default wake word. Name resolves from the
            // account-default model id (pushed by JS); unknown/unavailable → bare "Default". Checked
            // when the device is inheriting (no override); concrete rows un-checked in that case.
            val defaultName = wakeWordOptions.find { it.modelId == aiPrefs.wakeWordDefaultId }?.label
            items.add(SettingsItem.Checkmark(
                id = DEFAULT_ITEM_ID,
                label = if (defaultName != null) "$defaultName (Default)" else "Default",
                isChecked = isInheriting,
                sublabel = "Follow the account default"
            ))
            wakeWordOptions.forEach { option ->
                items.add(SettingsItem.Checkmark(
                    id = "model_${option.modelId}",
                    label = option.label,
                    isChecked = !isInheriting && option.isActive,
                    enabled = option.supported,
                    sublabel = if (!option.supported) "microWakeWord not supported on this device" else null
                ))
            }
        }

        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        when {
            item is SettingsItem.Navigation && item.id == "sensitivity" -> {
                navigateTo(MwwSensitivityPickerFragment(), "sensitivity")
            }
            item is SettingsItem.Checkmark && item.id == DEFAULT_ITEM_ID -> {
                selectDefault()
            }
            item is SettingsItem.Checkmark && item.id.startsWith("model_") -> {
                val modelId = item.id.removePrefix("model_")
                val option = wakeWordOptions.find { it.modelId == modelId }
                if (option != null) {
                    handleWakeWordSelection(option)
                }
            }
        }
    }

    /** GAP-2: "Default" picked → drop the device override so this device follows the account default
     *  wake word. Sets the native inherit flag now (instant checkmark + summary), calls
     *  clearWakeWordOverride() via the window global (applyEffectiveAiVoice re-resolves + pushes the
     *  effective word to WakeWordModelManager), and prompts a restart if the resolved default differs
     *  from the currently active model (wake word applies on next launch). */
    private fun selectDefault() {
        if (isInheriting) {
            Toast.makeText(requireContext(), "Already following the account default", Toast.LENGTH_SHORT).show()
            return
        }
        isInheriting = true
        aiPrefs.wakeWordInheriting = true
        SettingsActivity.webViewRef?.get()?.let { wv ->
            wv.post {
                wv.evaluateJavascript(
                    """
                    (async () => {
                        try {
                            // WINDOW GLOBAL, not import('/js/…') — evaluateJavascript's base URL is
                            // about:blank so a dynamic import can't resolve the specifier.
                            if (typeof window.clearWakeWordOverride === 'function') {
                                await window.clearWakeWordOverride();
                            } else { console.error('DROP: window.clearWakeWordOverride missing'); }
                        } catch (e) { console.error('clearWakeWordOverride failed', e); }
                    })()
                    """.trimIndent(), null
                )
            }
        }
        refreshItems()
        // Wake word applies on next launch — prompt a restart if the account default differs from
        // the currently active model.
        val defaultOption = wakeWordOptions.find { it.modelId == aiPrefs.wakeWordDefaultId }
        if (defaultOption != null && defaultOption.modelId != wakeWordModelManager.getActiveModel().modelId) {
            showRestartPrompt(defaultOption.label)
        }
    }

    /** Record a concrete wake-word pick as a per-device OVERRIDE on the JS/cloud side (LS mirror +
     *  user_devices.aiVoice.wakeWord). Without this, LS_WAKE_WORD stays empty and the next boot's
     *  applyEffectiveAiVoice would resolve "inheriting" and clobber the flag back to Default. */
    private fun syncWakeWordOverrideToJs(modelId: String) {
        val escaped = modelId.replace("\\", "\\\\").replace("'", "\\'")
        SettingsActivity.webViewRef?.get()?.let { wv ->
            wv.post {
                wv.evaluateJavascript(
                    """
                    (async () => {
                        try {
                            localStorage.setItem('dashie-device-wake-word', '$escaped');
                            if (window.settingsStore) window.settingsStore.set('ai.wakeWord', '$escaped');
                            const { getDeviceSettingsService } = await import('/js/data/services/device-settings-service.js');
                            const { getDeviceId } = await import('/js/utils/device-id.js');
                            const id = getDeviceId();
                            if (id) await getDeviceSettingsService().updateDeviceSettings(id, 'aiVoice', { wakeWord: '$escaped' }, false);
                            else console.warn('DROP: wake word override upload skipped — no device id');
                        } catch (e) { console.error('sync wake word override failed', e); }
                    })()
                    """.trimIndent(), null
                )
            }
        }
    }

    private fun loadWakeWordOptions() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val activeModel = wakeWordModelManager.getActiveModel()
                val activeModelId = activeModel.modelId

                // On 32-bit devices the microWakeWord native frontend isn't built
                // (arm64/x86_64 only), so pure-MWW words (okay_nabu/jarvis/mycroft/alexa)
                // can't run — selecting one silently falls back to EI "hey dashie" and
                // stops sample collection. Hey Dashie is fine (its dual gate degrades to
                // EI-only). Gray the unsupported ones out instead of misleading the user.
                val mwwAvailable = MicroFrontend.isAvailable()

                val options = WakeWordModel.ALL_MODELS.map { model ->
                    val supported = mwwAvailable ||
                        model.isDualEngine ||
                        model.engine != WakeWordEngine.MICRO_WAKE_WORD
                    WakeWordOption(
                        label = model.wakeWordName,
                        modelId = model.modelId,
                        isActive = activeModelId == model.modelId,
                        isBundled = true,
                        needsDownload = false,
                        supported = supported
                    )
                }

                wakeWordOptions = options

                withContext(Dispatchers.Main) {
                    refreshItems()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading wake word options", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error loading wake word options", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleWakeWordSelection(option: WakeWordOption) {
        if (!option.supported) {
            Toast.makeText(
                requireContext(),
                "${option.label} is unavailable (microWakeWord not supported on this device)",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        when {
            option.isActive -> {
                Toast.makeText(requireContext(), "Already using ${option.label}", Toast.LENGTH_SHORT).show()
            }
            option.needsDownload -> {
                downloadWakeWordModel(option.modelId, option.label)
            }
            option.isBundled -> {
                switchToBundledModel(option.modelId, option.label)
            }
            else -> {
                showRestartPrompt(option.label)
            }
        }
    }

    private fun switchToBundledModel(modelId: String, label: String) {
        Log.i(TAG, "Switching to bundled model: $modelId")
        Toast.makeText(requireContext(), "Switching to $label...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                wakeWordModelManager.selectModelById(modelId)
                wakeWordOptions = wakeWordOptions.map { it.copy(isActive = it.modelId == modelId) }
                // GAP-2: a concrete pick is a per-device override → not inheriting. Record it on the
                // JS/cloud side so it persists and the next boot doesn't resolve back to Default.
                aiPrefs.wakeWordInheriting = false

                withContext(Dispatchers.Main) {
                    isInheriting = false
                    syncWakeWordOverrideToJs(modelId)
                    refreshItems()
                    showRestartPrompt(label)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error switching model", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error switching model: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showRestartPrompt(modelLabel: String) {
        com.dashieapp.Dashie.halite.RestartPromptHelper.show(
            activity = requireActivity(),
            message = "Switched to $modelLabel.\n\nThe new wake word model will take effect after restarting the app.\n\nRestart now?",
            applyDim = true
        ) {
            val intent = android.content.Intent(
                requireContext(), com.dashieapp.Dashie.MainActivity::class.java
            )
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            requireActivity().finish()
        }
    }

    private fun downloadWakeWordModel(modelId: String, label: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Downloading $label...", Toast.LENGTH_SHORT).show()
                }

                val downloadedVersion = wakeWordModelManager.downloadTestModel()

                withContext(Dispatchers.Main) {
                    if (downloadedVersion != null) {
                        showRestartPrompt(label)
                    } else {
                        Toast.makeText(requireContext(), "Download failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading wake word model", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private data class WakeWordOption(
        val label: String,
        val modelId: String,
        val isActive: Boolean,
        val isBundled: Boolean,
        val needsDownload: Boolean,
        val supported: Boolean = true
    )
}
