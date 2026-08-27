package com.dashieapp.Dashie.halite.settings.schema

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsProfiler
import com.dashieapp.Dashie.halite.settings.SettingsTrace
import com.dashieapp.Dashie.halite.settings.fragments.BaseSettingsFragment
import com.dashieapp.Dashie.halite.settings.items.PickerOption
import com.dashieapp.Dashie.halite.settings.items.SettingsItem

/**
 * Fragment that renders a [SettingsPageSchema] (or [SubScreenSchema]) into the
 * existing RecyclerView-based settings UI.
 *
 * Converts schema items → [SettingsItem] list, delegates reads/writes to
 * [SettingsValueProvider], evaluates conditions via [ConditionEvaluator],
 * and fires side-effects via [SettingsCallbackRegistry].
 *
 * Usage:
 * ```kotlin
 * SchemaSettingsFragment.create(
 *     schemaProvider = { DisplayPageSchema.create() },
 *     valueProvider = valueProvider,
 *     callbackRegistry = callbackRegistry
 * )
 * ```
 */
class SchemaSettingsFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "SchemaSettings"
        private const val ARG_SUB_SCREEN_ID = "sub_screen_id"

        /**
         * Create a fragment for a top-level schema page.
         *
         * @param navigationCallback Optional handler for navigation targets not found
         *   in the schema's sub-screens (e.g. custom fragments). Return true if handled.
         * @param backNavigationInterceptor Optional handler called before back navigation.
         *   Return true if the interceptor handled it (e.g. showed a dialog); false to proceed normally.
         */
        fun create(
            schemaProvider: () -> SettingsPageSchema,
            valueProvider: SettingsValueProvider,
            callbackRegistry: SettingsCallbackRegistry,
            navigationCallback: ((String) -> Boolean)? = null,
            backNavigationInterceptor: (() -> Boolean)? = null,
            toggleCallback: ((settingKey: String, newValue: Boolean) -> Unit)? = null
        ): SchemaSettingsFragment {
            return SchemaSettingsFragment().apply {
                this.schemaProvider = schemaProvider
                this.valueProvider = valueProvider
                this.callbackRegistry = callbackRegistry
                this.navigationCallback = navigationCallback
                this.backNavigationInterceptor = backNavigationInterceptor
                this.toggleCallback = toggleCallback
            }
        }

        /**
         * Create a fragment for a sub-screen within a schema page.
         */
        fun createSubScreen(
            schemaProvider: () -> SettingsPageSchema,
            subScreenId: String,
            valueProvider: SettingsValueProvider,
            callbackRegistry: SettingsCallbackRegistry
        ): SchemaSettingsFragment {
            return SchemaSettingsFragment().apply {
                this.schemaProvider = schemaProvider
                this.valueProvider = valueProvider
                this.callbackRegistry = callbackRegistry
                arguments = Bundle().apply {
                    putString(ARG_SUB_SCREEN_ID, subScreenId)
                }
            }
        }
    }

    // Injected dependencies (set via create() factory methods)
    private lateinit var schemaProvider: () -> SettingsPageSchema
    private lateinit var valueProvider: SettingsValueProvider
    private lateinit var callbackRegistry: SettingsCallbackRegistry
    private var navigationCallback: ((String) -> Boolean)? = null
    private var backNavigationInterceptor: (() -> Boolean)? = null
    private var toggleCallback: ((settingKey: String, newValue: Boolean) -> Unit)? = null

    private val conditionEvaluator by lazy { ConditionEvaluator(valueProvider) }

    // Listener for runtime-updated pref keys written outside the settings UI
    // (e.g. MQTT connection status updated by DashieMqttService). Only attached
    // while the fragment is visible; refreshes the current screen on change.
    private var runtimePrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val isInitialized: Boolean
        get() = ::schemaProvider.isInitialized

    private val schema: SettingsPageSchema
        get() = schemaProvider()

    private val subScreenId: String?
        get() = arguments?.getString(ARG_SUB_SCREEN_ID)

    override val title: String
        get() {
            if (!isInitialized) return "Settings"
            val screenId = subScreenId
            return if (screenId != null) {
                schema.subScreens[screenId]?.title ?: schema.title
            } else {
                schema.title
            }
        }

    /** Hide the nav-bar "Done" when the schema opts out (data-entry forms — D.30). */
    override val showDoneButton: Boolean
        get() = if (isInitialized) !schema.hideDoneButton else true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!isInitialized) {
            // Fragment was recreated by the system after process death —
            // schemaProvider was never re-injected. Pop back to avoid crash.
            Log.w(TAG, "schemaProvider not initialized (process death recreation), popping fragment")
            parentFragmentManager.popBackStack()
            return
        }
        SettingsTrace.shown("SchemaFragment/schema.id=${schema.id} subScreen=${subScreenId ?: "(root)"}")
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (!isInitialized) return
        // Listen for MQTT status updates written by DashieMqttService so the
        // Connection Status row refreshes live instead of only on re-entry.
        val prefs = requireContext().getSharedPreferences("dashie_lite_prefs", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mqtt_last_status" || key == "mqtt_last_error") {
                view?.post { if (isInitialized) refreshItems() }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        runtimePrefsListener = listener
    }

    override fun onPause() {
        runtimePrefsListener?.let {
            requireContext().getSharedPreferences("dashie_lite_prefs", android.content.Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(it)
        }
        runtimePrefsListener = null
        super.onPause()
    }

    override fun getItems(): List<SettingsItem> {
        if (!isInitialized) return emptyList()
        val screenName = subScreenId ?: schema.title
        val sections = SettingsProfiler.measure("Schema.getSections($screenName)") {
            getSectionsForCurrentScreen()
        }
        return SettingsProfiler.measure("Schema.buildItemList($screenName, ${sections.size} sections)") {
            buildItemList(sections)
        }
    }

    private fun getSectionsForCurrentScreen(): List<SettingsSection> {
        val screenId = subScreenId
        return if (screenId != null) {
            schema.subScreens[screenId]?.sections ?: emptyList()
        } else {
            schema.sections
        }
    }

    /**
     * Converts schema sections into a flat list of [SettingsItem] for the adapter.
     * Evaluates visibility conditions and resolves display values from preferences.
     */
    private fun buildItemList(sections: List<SettingsSection>): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()

        for (section in sections) {
            // Check section-level visibility
            if (!conditionEvaluator.evaluate(section.visibleWhen)) continue

            // Section header
            if (section.header != null) {
                items.add(SettingsItem.SectionHeader(
                    id = "header_${section.header}",
                    title = section.header
                ))
            }

            // Section items
            for (schemaItem in section.items) {
                if (!conditionEvaluator.evaluate(schemaItem.visibleWhen)) continue

                val isEnabled = conditionEvaluator.evaluate(schemaItem.enabledWhen)
                val settingsItem = mapSchemaItem(schemaItem, isEnabled)
                if (settingsItem != null) {
                    items.add(settingsItem)
                }
            }

            // Section footer
            if (section.footer != null) {
                items.add(SettingsItem.Info(
                    id = "footer_${section.header ?: section.hashCode()}",
                    text = section.footer
                ))
            }
        }

        return items
    }

    /**
     * Maps a [SchemaItem] to a [SettingsItem] for the adapter.
     */
    private fun mapSchemaItem(schemaItem: SchemaItem, isEnabled: Boolean): SettingsItem? {
        return when (schemaItem) {
            is SchemaItem.Toggle -> SettingsItem.Toggle(
                id = schemaItem.id,
                label = schemaItem.label,
                isChecked = valueProvider.getBoolean(schemaItem.settingKey),
                isEnabled = isEnabled,
                sublabel = schemaItem.sublabelKey?.let { valueProvider.getString(it) }
                    ?: schemaItem.sublabel
            )

            is SchemaItem.Navigation -> {
                val displayValue = schemaItem.displayValue
                    ?: schemaItem.displayValueKey?.let { valueProvider.getString(it) }
                SettingsItem.Navigation(
                    id = schemaItem.id,
                    label = schemaItem.label,
                    value = displayValue,
                    sublabel = schemaItem.sublabel,
                    colorDot = schemaItem.colorDot,
                    avatarUrl = schemaItem.avatarUrl,
                    checked = schemaItem.checked
                )
            }

            is SchemaItem.Picker -> {
                val currentValue = valueProvider.getString(schemaItem.settingKey) ?: ""
                val selectedOption = schemaItem.options.find { it.value == currentValue }
                // A live override wins over the option's static label (see Picker.displayValueKey).
                // Blank → fall back, so pickers that don't set the key render exactly as before.
                val liveLabel = schemaItem.displayValueKey
                    ?.let { valueProvider.getString(it) }
                    ?.takeIf { it.isNotBlank() }
                val baseLabel = liveLabel ?: selectedOption?.label ?: currentValue
                val customLabel: CharSequence = if (selectedOption?.isCustomInput == true &&
                    selectedOption.customInputSettingKey != null) {
                    val custom = valueProvider.getString(selectedOption.customInputSettingKey) ?: ""
                    val ctx = context
                    if (custom.isNotEmpty() && ctx != null) {
                        buildCustomValueLabel(ctx, baseLabel, custom, selectedOption.customInputUnit)
                    } else baseLabel
                } else baseLabel
                val badge = schemaItem.badgeKey
                    ?.let { ValueBadge.from(valueProvider.getString(it)) }
                val badgeCtx = context
                val displayLabel: CharSequence =
                    if (badge != null && badgeCtx != null)
                        buildBadgedValueLabel(badgeCtx, customLabel, badge)
                    else customLabel
                SettingsItem.Picker(
                    id = schemaItem.id,
                    label = schemaItem.label,
                    selectedValue = currentValue,
                    displayValue = displayLabel,
                    sublabel = schemaItem.sublabel,
                    options = schemaItem.options.map {
                PickerOption(
                    value = it.value,
                    label = it.label,
                    sublabel = it.sublabel,
                    enabled = it.enabled,
                    isCustomInput = it.isCustomInput,
                    customInputSettingKey = it.customInputSettingKey,
                    customInputTitle = it.customInputTitle,
                    customInputHint = it.customInputHint,
                    customInputUnit = it.customInputUnit,
                    colorSwatch = it.colorSwatch,
                    onDisabledTap = it.onDisabledTap,
                    actionLabel = it.actionLabel
                )
            },
                    isEnabled = isEnabled
                )
            }

            is SchemaItem.Slider -> SettingsItem.Slider(
                id = schemaItem.id,
                label = schemaItem.label,
                value = valueProvider.getInt(schemaItem.settingKey),
                min = schemaItem.min,
                max = schemaItem.max,
                step = schemaItem.step,
                unit = schemaItem.unit,
                isEnabled = isEnabled
            )

            is SchemaItem.Info -> {
                val value = schemaItem.staticValue
                    ?: schemaItem.valueKey?.let { valueProvider.getString(it) }
                if (value != null) {
                    val warningColor = if (schemaItem.valueColorKey != null && valueProvider.getBoolean(schemaItem.valueColorKey)) {
                        androidx.core.content.ContextCompat.getColor(requireContext(), com.dashieapp.Dashie.R.color.dashie_orange)
                    } else null
                    SettingsItem.ValueDisplay(
                        id = schemaItem.id,
                        label = schemaItem.label,
                        value = value,
                        valueColor = warningColor
                    )
                } else {
                    SettingsItem.Info(
                        id = schemaItem.id,
                        text = schemaItem.label
                    )
                }
            }

            is SchemaItem.Action -> SettingsItem.Action(
                id = schemaItem.id,
                label = schemaItem.label,
                isDanger = schemaItem.destructive,
                isEnabled = isEnabled
            )

            is SchemaItem.TextInput -> {
                if (schemaItem.dialogMode) {
                    // Clickable row with current value + edit chevron; tap
                    // opens a dialog input. See handleTextInputDialogClick.
                    SettingsItem.Navigation(
                        id = schemaItem.id,
                        label = schemaItem.label,
                        // A displayValueKey (friendly-decoded label) wins on the collapsed row;
                        // blank falls back to the raw stored value. The edit dialog still uses
                        // settingKey. (punch #3)
                        value = schemaItem.displayValueKey?.let { valueProvider.getString(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?: valueProvider.getString(schemaItem.settingKey) ?: "",
                        sublabel = schemaItem.sublabel
                    )
                } else {
                    val inputType = when (schemaItem.inputType) {
                        "number" -> InputType.TYPE_CLASS_NUMBER
                        "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        "multiline" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        else -> InputType.TYPE_CLASS_TEXT
                    }
                    SettingsItem.TextInput(
                        id = schemaItem.id,
                        label = schemaItem.label,
                        value = valueProvider.getString(schemaItem.settingKey) ?: "",
                        placeholder = schemaItem.placeholder,
                        inputType = inputType,
                        isEnabled = isEnabled,
                        sublabel = schemaItem.sublabel
                    )
                }
            }
        }
    }

    // ── Event handlers ──────────────────────────────────────────────────

    override fun handleItemClick(item: SettingsItem) {
        // A dialogMode TextInput is emitted as a Navigation SettingsItem
        // so the adapter renders it like a chevron row; route the click to
        // the input-dialog handler instead of standard navigation.
        if (item is SettingsItem.Navigation) {
            val schemaItem = findSchemaItem(item.id)
            if (schemaItem is SchemaItem.TextInput && schemaItem.dialogMode) {
                showTextInputDialog(schemaItem)
                return
            }
        }
        when (item) {
            is SettingsItem.Navigation -> handleNavigationClick(item.id)
            is SettingsItem.Action -> handleActionClick(item.id)
            is SettingsItem.Picker -> handlePickerClick(item)
            is SettingsItem.Checkmark -> handleCheckmarkClick(item)
            else -> Log.d(TAG, "Unhandled click: ${item.id}")
        }
    }

    /**
     * Show a pop-up text entry dialog for a TextInput with dialogMode=true.
     * Mirrors PickerSubScreenFragment.showCustomInputDialog.
     */
    private fun showTextInputDialog(schemaItem: SchemaItem.TextInput) {
        val ctx = context ?: return
        val current = valueProvider.getString(schemaItem.settingKey) ?: ""

        val dialogView = layoutInflater.inflate(R.layout.dialog_input, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text =
            schemaItem.dialogTitle ?: schemaItem.label

        val inputField = dialogView.findViewById<EditText>(R.id.dialogInput)
        inputField.inputType = when (schemaItem.inputType) {
            "number" -> InputType.TYPE_CLASS_NUMBER
            "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
        schemaItem.placeholder?.let { inputField.hint = it }
        if (current.isNotEmpty()) {
            inputField.setText(current)
            inputField.setSelection(current.length)
        }

        val dialog = android.app.AlertDialog.Builder(ctx)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).setOnClickListener {
            val entered = inputField.text.toString().trim()
            valueProvider.setString(schemaItem.settingKey, entered)
            schemaItem.onChanged?.let { callbackRegistry.invoke(it) }
            refreshItems()
            dialog.dismiss()
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        inputField.requestFocus()
    }

    private fun handleNavigationClick(itemId: String) {
        // Find the corresponding schema item
        // Ignore clicks on fragments that are no longer active (replaced by another fragment)
        if (!isResumed) {
            Log.d(TAG, "handleNavigationClick: ignoring click on non-resumed fragment, itemId=$itemId, schema.id=${schema.id}")
            return
        }
        val schemaItem = findSchemaItem(itemId) as? SchemaItem.Navigation
        if (schemaItem == null) {
            Log.w(TAG, "handleNavigationClick: schemaItem not found for itemId=$itemId, schema.id=${schema.id}")
            return
        }
        val targetId = schemaItem.navigateTo
        Log.d(TAG, "handleNavigationClick: itemId=$itemId, target=$targetId, schema=${schema.id}")

        // directPicker: skip the sub-screen and go straight to the picker inside it
        if (schemaItem.directPicker && targetId in schema.subScreens) {
            val subScreen = schema.subScreens[targetId] ?: return
            val pickerItem = subScreen.sections.flatMap { it.items }
                .filterIsInstance<SchemaItem.Picker>().firstOrNull() ?: return
            val pickerFragment = PickerSubScreenFragment.create(
                title = subScreen.title,
                settingKey = pickerItem.settingKey,
                options = pickerItem.options.map {
                    com.dashieapp.Dashie.halite.settings.items.PickerOption(
                        value = it.value,
                        label = it.label,
                        sublabel = it.sublabel,
                        enabled = it.enabled,
                        isCustomInput = it.isCustomInput,
                        customInputSettingKey = it.customInputSettingKey,
                        customInputTitle = it.customInputTitle,
                        customInputHint = it.customInputHint,
                        colorSwatch = it.colorSwatch,
                        onDisabledTap = it.onDisabledTap,
                    actionLabel = it.actionLabel
                    )
                },
                valueProvider = valueProvider,
                callbackRegistry = callbackRegistry,
                onChanged = pickerItem.onChanged,
                onDismiss = { refreshItems() },
                sectionHeaders = pickerItem.sectionHeaders,
                // directPicker pickers (family Role / Color) are entered with
                // one tap and should return with one tap — selecting an option
                // navigates straight back, so the user never has to reach for
                // the global "Done" (which would close Settings — D.19).
                autoPopOnSelect = true,
                customInputPlaceholder = pickerItem.customInputPlaceholder
            )
            navigateTo(pickerFragment, "picker_${pickerItem.id}")
            return
        }

        // Check if it's a sub-screen in our schema
        if (targetId in schema.subScreens) {
            val subFragment = createSubScreen(
                schemaProvider = schemaProvider,
                subScreenId = targetId,
                valueProvider = valueProvider,
                callbackRegistry = callbackRegistry
            )
            navigateTo(subFragment, targetId)
        } else if (navigationCallback?.invoke(targetId) == true) {
            // Handled by external callback (e.g. custom fragment navigation)
        } else {
            Log.w(TAG, "Navigation target not found in schema: $targetId")
        }
    }

    private fun handleActionClick(itemId: String) {
        val schemaItem = findSchemaItem(itemId) as? SchemaItem.Action ?: return
        callbackRegistry.invoke(schemaItem.action)
    }

    private fun handlePickerClick(item: SettingsItem.Picker) {
        // Navigate to a checkmark sub-screen for this picker
        val schemaItem = findSchemaItem(item.id) as? SchemaItem.Picker ?: return
        val pickerFragment = PickerSubScreenFragment.create(
            title = schemaItem.label,
            settingKey = schemaItem.settingKey,
            options = schemaItem.options.map {
                PickerOption(
                    value = it.value,
                    label = it.label,
                    sublabel = it.sublabel,
                    enabled = it.enabled,
                    isCustomInput = it.isCustomInput,
                    customInputSettingKey = it.customInputSettingKey,
                    customInputTitle = it.customInputTitle,
                    customInputHint = it.customInputHint,
                    customInputUnit = it.customInputUnit,
                    colorSwatch = it.colorSwatch,
                    onDisabledTap = it.onDisabledTap,
                    actionLabel = it.actionLabel
                )
            },
            valueProvider = valueProvider,
            callbackRegistry = callbackRegistry,
            onChanged = schemaItem.onChanged,
            onDismiss = { refreshItems() },
            sectionHeaders = schemaItem.sectionHeaders,
            customInputPlaceholder = schemaItem.customInputPlaceholder,
            leadingDescription = schemaItem.leadingDescription,
            leadingActionId = schemaItem.leadingActionId,
            leadingActionLabel = schemaItem.leadingActionLabel,
            leadingActionCallback = schemaItem.leadingActionCallback
        )
        navigateTo(pickerFragment, "picker_${item.id}")
    }

    private fun handleCheckmarkClick(item: SettingsItem.Checkmark) {
        // Checkmark items are handled by PickerSubScreenFragment
    }

    override fun handleToggleChange(item: SettingsItem.Toggle, newValue: Boolean) {
        val schemaItem = findSchemaItem(item.id) as? SchemaItem.Toggle ?: return

        // Check for interceptor (e.g. license check before enabling voice)
        val interceptor = callbackRegistry.getToggleInterceptor(schemaItem.settingKey)
        if (interceptor != null) {
            // Don't set the value yet — let the interceptor decide.
            // The toggle switch is already visually flipped by the adapter.
            interceptor(newValue, {
                // proceed callback — apply the change and refresh
                SettingsProfiler.measure("Toggle.persist(${schemaItem.settingKey})") {
                    valueProvider.setBoolean(schemaItem.settingKey, newValue)
                }
                schemaItem.onChanged?.let { callbackRegistry.invoke(it) }
                toggleCallback?.invoke(schemaItem.settingKey, newValue)
                refreshItems()
            }, {
                // cancel callback — pref is untouched; force a rebind of all items so
                // the Switch visual snaps back to the stored value (undoes the adapter's
                // optimistic flip). refreshItems() alone wouldn't work: DiffUtil sees no
                // model change and therefore skips rebinding, leaving the Switch stuck.
                forceRebindAllItems()
            })
        } else {
            SettingsProfiler.measure("Toggle.persist(${schemaItem.settingKey})") {
                valueProvider.setBoolean(schemaItem.settingKey, newValue)
            }
            schemaItem.onChanged?.let { callbackRegistry.invoke(it) }
            toggleCallback?.invoke(schemaItem.settingKey, newValue)
            refreshItems()
        }
    }

    override fun handleSliderChange(item: SettingsItem.Slider, newValue: Int) {
        val schemaItem = findSchemaItem(item.id) as? SchemaItem.Slider ?: return
        valueProvider.setInt(schemaItem.settingKey, newValue)
        schemaItem.onChanged?.let { callbackRegistry.invoke(it) }
    }

    override fun handleTextChange(item: SettingsItem.TextInput, newValue: String) {
        val schemaItem = findSchemaItem(item.id) as? SchemaItem.TextInput ?: return
        valueProvider.setString(schemaItem.settingKey, newValue)
        schemaItem.onChanged?.let { callbackRegistry.invoke(it) }
    }

    override fun navigateBack() {
        if (interceptExit()) return
        super.navigateBack()
    }

    /**
     * Check the exit interceptor. Returns true if the interceptor handled it
     * (e.g. showed a reload dialog) and navigation should be cancelled.
     */
    fun interceptExit(): Boolean {
        return backNavigationInterceptor?.invoke() == true
    }

    /**
     * Public refresh for external callers (e.g. after a dialog updates preferences).
     * Safe to call even if the fragment was recreated after process death —
     * silently no-ops when schemaProvider hasn't been re-injected.
     */
    fun refresh() {
        if (!isInitialized) {
            Log.w(TAG, "refresh() called but schemaProvider not initialized, ignoring")
            return
        }
        refreshItems()
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Find a schema item by ID across all sections (including sub-screens).
     */
    private fun findSchemaItem(id: String): SchemaItem? {
        val sections = getSectionsForCurrentScreen()
        for (section in sections) {
            for (item in section.items) {
                if (item.id == id) return item
            }
        }
        // Also check sub-screens
        for ((_, subScreen) in schema.subScreens) {
            for (section in subScreen.sections) {
                for (item in section.items) {
                    if (item.id == id) return item
                }
            }
        }
        return null
    }
}
