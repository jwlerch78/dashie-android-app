package com.dashieapp.Dashie.halite.settings.schema

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.app.AlertDialog
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.halite.settings.adapters.SettingsAdapter
import com.dashieapp.Dashie.halite.settings.fragments.BaseSettingsFragment
import com.dashieapp.Dashie.halite.settings.items.PickerOption
import com.dashieapp.Dashie.halite.settings.items.SettingsItem

/**
 * Sub-screen that displays a list of checkmark items for single-selection.
 * Used by [SchemaSettingsFragment] when a Picker schema item is tapped.
 *
 * Supports optional section headers between groups of options
 * (e.g. AI model picker grouped by provider).
 *
 * If any option has [PickerOption.isCustomInput] = true, tapping that option reveals
 * an inline text input directly below it in the list instead of saving a placeholder value.
 */
class PickerSubScreenFragment : BaseSettingsFragment() {

    companion object {
        fun create(
            title: String,
            settingKey: String,
            options: List<PickerOption>,
            valueProvider: SettingsValueProvider,
            callbackRegistry: SettingsCallbackRegistry,
            onChanged: String? = null,
            onDismiss: (() -> Unit)? = null,
            sectionHeaders: List<Pair<String, Int>>? = null,
            autoPopOnSelect: Boolean = false,
            customInputPlaceholder: String? = null,
            leadingDescription: String? = null,
            leadingActionId: String? = null,
            leadingActionLabel: String? = null,
            leadingActionCallback: String? = null
        ): PickerSubScreenFragment {
            return PickerSubScreenFragment().apply {
                this._title = title
                this.settingKey = settingKey
                this.options = options
                this.valueProvider = valueProvider
                this.callbackRegistry = callbackRegistry
                this.onChanged = onChanged
                this.onDismiss = onDismiss
                this.sectionHeaders = sectionHeaders
                this.autoPopOnSelect = autoPopOnSelect
                this.customInputPlaceholder = customInputPlaceholder
                this.leadingDescription = leadingDescription
                this.leadingActionId = leadingActionId
                this.leadingActionLabel = leadingActionLabel
                this.leadingActionCallback = leadingActionCallback
            }
        }
    }

    // Lead-in shown after the first section header (a description + a primary
    // orange-block action that opens an explainer/setup dialog via a callback).
    private var leadingDescription: String? = null
    private var leadingActionId: String? = null
    private var leadingActionLabel: String? = null
    private var leadingActionCallback: String? = null
    private var leadingInjected = false

    private lateinit var _title: String
    private lateinit var settingKey: String
    private lateinit var options: List<PickerOption>
    private lateinit var valueProvider: SettingsValueProvider
    private lateinit var callbackRegistry: SettingsCallbackRegistry
    private var onChanged: String? = null
    private var onDismiss: (() -> Unit)? = null
    // List of (header title, index in options list) pairs for section breaks
    private var sectionHeaders: List<Pair<String, Int>>? = null
    // When true, picking a (non-custom) option navigates straight back to the
    // parent screen instead of staying put. Used by directPicker pickers (the
    // family Role / Color screens) so the user doesn't have to hunt for Back
    // — and can't mistake the global "Done" (which closes Settings) for it.
    private var autoPopOnSelect = false
    // Placeholder for the inline custom-input row; states the accepted range
    private var customInputPlaceholder: String? = null
    // Whether the user has tapped the Custom option to expand the inline input
    private var showCustomInput = false

    override val title: String
        get() = _title

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // If any option uses custom text input, replace the adapter to handle text changes
        if (options.any { it.isCustomInput }) {
            val newAdapter = SettingsAdapter(
                onItemClick = { item -> handleItemClick(item) },
                onToggleChange = { _, _ -> },
                onTextChange = { _, newValue ->
                    newValue.trim().toIntOrNull()?.let { intValue ->
                        valueProvider.setString(settingKey, intValue.toString())
                        onChanged?.let { callbackRegistry.invoke(it) }
                        refreshItems()
                    }
                }
            )
            recyclerView.adapter = newAdapter
            adapter = newAdapter
        }

        // Live-refresh model rows while an install is in flight — the sublabel override in
        // getItems() only renders what SttInstallProgress says NOW, so each progress event
        // rebuilds the list (events are throttled to ~1/MB by the installer; DiffUtil keeps
        // the rebuild cheap). Registered only for pickers that actually contain a model row.
        if (options.any { modelFamilyIdFor(it.value) != null }) {
            installListener = com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.Listener {
                if (view.isAttachedToWindow) refreshItems()
            }.also { com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.addListener(it) }
        }
    }

    /** Add the lead-in (description Info + primary Action) once per build. */
    private fun injectLeading(items: MutableList<SettingsItem>) {
        if (leadingInjected) return
        if (leadingDescription == null && leadingActionLabel == null) return
        leadingInjected = true
        leadingDescription?.let { items.add(SettingsItem.Info(id = "leading_desc", text = it)) }
        val lid = leadingActionId
        val ll = leadingActionLabel
        if (lid != null && ll != null) {
            items.add(SettingsItem.Action(id = lid, label = ll, isPrimary = true))
        }
    }

    override fun getItems(): List<SettingsItem> {
        val currentValue = valueProvider.getString(settingKey) ?: ""
        // Custom is "active" when the stored value doesn't match any preset option
        val isCustomValue = options.none { !it.isCustomInput && it.value == currentValue }
        val items = mutableListOf<SettingsItem>()
        val headers = sectionHeaders
        leadingInjected = false

        // No section headers → the lead-in (if any) goes at the very top.
        if (headers.isNullOrEmpty()) injectLeading(items)

        for ((index, option) in options.withIndex()) {
            // Insert section header if one is defined at this index
            headers?.find { it.second == index }?.let { (headerTitle, _) ->
                items.add(SettingsItem.SectionHeader(
                    id = "header_$headerTitle",
                    title = headerTitle
                ))
                // Lead-in sits right under the FIRST header (e.g. Dashie).
                injectLeading(items)
            }

            // Case-insensitive: some valueProvider getters return a display-
            // cased value (e.g. family.editMemberRole capitalizes "parent" →
            // "Parent") while option values are lowercase. An exact compare
            // would never tick the box (D.18). No picker has options that
            // differ only by case, so this is safe across the board.
            val isChecked = if (option.isCustomInput) isCustomValue
                else option.value.equals(currentValue, ignoreCase = true)

            // For dialog-based custom input, append the current value to the label
            // (e.g. "Custom (120pt) ✎") with a trailing edit icon once a value is stored.
            val displayLabel: CharSequence = if (option.isCustomInput && option.customInputSettingKey != null) {
                val custom = valueProvider.getString(option.customInputSettingKey) ?: ""
                val ctx = context
                if (custom.isNotEmpty() && ctx != null) {
                    buildCustomValueLabel(ctx, option.label, custom, option.customInputUnit)
                } else option.label
            } else option.label

            // Live install state for on-device model rows: while THIS option's model is
            // downloading/extracting, the row's sublabel IS the progress and the Download
            // button is dropped — re-showing the button mid-install read as "not started"
            // (2026-08-18). Null for every option of every non-model picker.
            val installProgress = installProgressFor(option.value)
            items.add(SettingsItem.Checkmark(
                id = "picker_${settingKey}_${option.value}",
                label = displayLabel,
                isChecked = isChecked,
                sublabel = installProgress ?: option.sublabel,
                enabled = option.enabled,
                colorDot = option.colorSwatch,
                onDisabledTap = option.onDisabledTap,
                actionLabel = if (installProgress != null) null else option.actionLabel
            ))

            // Show inline text input directly below the custom option when active.
            // Skipped for dialog-based custom input (customInputSettingKey != null) —
            // those options collect the value in a popup instead.
            if (option.isCustomInput && option.customInputSettingKey == null &&
                (showCustomInput || isCustomValue)) {
                items.add(SettingsItem.TextInput(
                    id = "custom_input_$settingKey",
                    label = "Custom (%)",
                    value = if (isCustomValue) currentValue else "",
                    placeholder = customInputPlaceholder ?: "50–300",
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    labelColor = ContextCompat.getColor(requireContext(), R.color.dashie_orange)
                ))
            }
        }

        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        // The injected primary action (e.g. "Dashie Details & Setup") opens a
        // dialog via its callback — it's not a selectable value.
        if (item is SettingsItem.Action && item.id == leadingActionId) {
            leadingActionCallback?.let { callbackRegistry.invoke(it, "") }
            return
        }
        if (item is SettingsItem.Checkmark) {
            if (!item.enabled) {
                // A disabled row with an explainer callback routes the tap there
                // (e.g. grayed Cloud/Hybrid preset → cloud-activation dialog)
                // instead of being an inert no-op. Selection is still blocked —
                // the value never changes for a disabled option. Pass the tapped
                // option's VALUE through so the handler knows which option was hit
                // (Cloud vs Hybrid) and can remember + auto-apply it once the
                // blocker clears (e.g. after the user adds credits).
                item.onDisabledTap?.let { cb ->
                    val tappedValue = options.find {
                        "picker_${settingKey}_${it.value}" == item.id
                    }?.value ?: ""
                    callbackRegistry.invoke(cb, tappedValue)
                }
                return
            }
            val selectedOption = options.find {
                "picker_${settingKey}_${it.value}" == item.id
            } ?: return

            if (selectedOption.isCustomInput) {
                if (selectedOption.customInputSettingKey != null) {
                    showCustomInputDialog(selectedOption)
                } else {
                    showCustomInput = true
                    refreshItems()
                }
            } else {
                showCustomInput = false
                valueProvider.setString(settingKey, selectedOption.value)
                onChanged?.let { callbackRegistry.invoke(it) }
                if (autoPopOnSelect) {
                    // Selection is the confirmation — return to the parent
                    // screen. onDismiss (→ parent refreshItems) fires from
                    // onDestroyView, so the parent's row updates.
                    navigateBack()
                } else {
                    refreshItems()
                }
            }
        }
    }

    private fun showCustomInputDialog(option: PickerOption) {
        val customKey = option.customInputSettingKey ?: return
        val ctx = context ?: return
        val currentCustomValue = valueProvider.getString(customKey) ?: ""

        val dialogView = layoutInflater.inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text =
            option.customInputTitle ?: option.label

        val inputField = dialogView.findViewById<EditText>(R.id.dialogInput)
        inputField.inputType = InputType.TYPE_CLASS_NUMBER
        option.customInputHint?.let { inputField.hint = it }
        if (currentCustomValue.isNotEmpty()) {
            inputField.setText(currentCustomValue)
            inputField.setSelection(currentCustomValue.length)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            val entered = inputField.text.toString().trim().toIntOrNull()
            if (entered != null) {
                valueProvider.setString(customKey, entered.toString())
                valueProvider.setString(settingKey, option.value)
                onChanged?.let { callbackRegistry.invoke(it) }
                refreshItems()
            }
            dialog.dismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        inputField.requestFocus()
    }

    override fun onDestroyView() {
        installListener?.let {
            com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.removeListener(it)
        }
        installListener = null
        onDismiss?.invoke()
        super.onDestroyView()
    }

    // ── Live install state on model rows ─────────────────────────────────
    //
    // ⚠️ The picker is otherwise generic; this is the one option family whose rows change
    // while the picker is OPEN (a 42–135 MB download + extract). If a second live family
    // ever appears, generalize this into an override-provider passed through create()
    // rather than adding a second special case here.

    private var installListener: com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.Listener? = null

    /** The STT family id an option value names, or null for every non-model option. */
    private fun modelFamilyIdFor(optionValue: String): String? =
        com.dashieapp.Dashie.halite.voice.stt.SttModelRegistry.byProviderValue(optionValue)?.id

    /**
     * Live sublabel for [optionValue] while ITS model install is in flight, else null.
     * Phrasing comes from [com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.render]
     * — the same line the download dialog and Control Center strip show.
     */
    private fun installProgressFor(optionValue: String): String? {
        val snap = com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.snapshot() ?: return null
        if (modelFamilyIdFor(optionValue) != snap.familyId) return null
        val line = com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.render(snap)
        return if (snap.phase == com.dashieapp.Dashie.halite.voice.stt.SttInstallProgress.Phase.DOWNLOADING)
            "Downloading…  ·  $line" else line
    }
}
