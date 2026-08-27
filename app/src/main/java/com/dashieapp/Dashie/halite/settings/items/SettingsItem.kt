package com.dashieapp.Dashie.halite.settings.items

/**
 * Sealed class representing different types of settings menu items.
 * Each item type corresponds to a different ViewHolder and layout.
 */
sealed class SettingsItem {
    /** Unique identifier for the item (used for click handling) */
    abstract val id: String

    /**
     * Navigation item - displays label, optional value, and chevron.
     * Used for items that navigate to a sub-screen.
     */
    data class Navigation(
        override val id: String,
        val label: String,
        val value: String? = null,
        val sublabel: String? = null,
        /** Optional color dot shown to the left of the label (hex color, e.g. "#FF6B6B") */
        val colorDot: String? = null,
        /** Optional avatar image URL — when set, shows circular avatar instead of color dot */
        val avatarUrl: String? = null,
        /** When non-null, show checkmark instead of chevron. True = ✓, false = empty. */
        val checked: Boolean? = null
    ) : SettingsItem()

    /**
     * Toggle item - displays label and switch.
     * Used for boolean settings.
     */
    data class Toggle(
        override val id: String,
        val label: String,
        val isChecked: Boolean,
        val isEnabled: Boolean = true,
        val sublabel: String? = null
    ) : SettingsItem()

    /**
     * Action item - displays label only, tappable.
     * Used for buttons that trigger an action.
     */
    data class Action(
        override val id: String,
        val label: String,
        val isDanger: Boolean = false,
        val isEnabled: Boolean = true,
        // Renders as a filled orange block with centered white text (a stand-alone
        // CTA), instead of the default accent-text row. Used for the "Dashie
        // Details & Setup" entry inside the Voice & AI Setup picker.
        val isPrimary: Boolean = false
    ) : SettingsItem()

    /**
     * Section header - displays uppercase header text.
     * Used to separate groups of items.
     */
    data class SectionHeader(
        override val id: String,
        val title: String
    ) : SettingsItem()

    /**
     * Info text - displays description/help text.
     * Used below sections to provide context.
     */
    data class Info(
        override val id: String,
        val text: String,
        val textColor: Int? = null
    ) : SettingsItem()

    /**
     * Value display item - displays label and value, no chevron.
     * Used for read-only information display.
     */
    data class ValueDisplay(
        override val id: String,
        val label: String,
        val value: String,
        val sublabel: String? = null,
        val valueColor: Int? = null
    ) : SettingsItem()

    /**
     * Checkmark item - displays label and optional checkmark.
     * Used for picker lists where one option is selected.
     */
    data class Checkmark(
        override val id: String,
        val label: CharSequence,
        val isChecked: Boolean,
        val isIndented: Boolean = false,
        val sublabel: String? = null,
        val hasPreview: Boolean = false,
        val enabled: Boolean = true,
        // Optional hex color — renders a circular swatch left of the row (D.38).
        val colorDot: String? = null,
        // When set on a DISABLED row, tapping it invokes this callback (via the
        // SettingsCallbackRegistry) instead of being an inert no-op — used to
        // explain WHY the option is unavailable and offer a path to enable it
        // (e.g. the grayed Cloud/Hybrid voice presets → "create account / add
        // credits / set up console"). A row with this set renders fully lit and
        // stays tappable even though enabled == false — UNLESS actionLabel is
        // also set, which grays the content and puts the "path to enable" on an
        // explicit button instead (John's 2026-08-18 model-download UX: the row
        // reads as not-yet-available, the button says what tapping will do).
        val onDisabledTap: String? = null,
        // Label for a right-aligned action button on a disabled row (e.g.
        // "Download"). Tapping the button (or the row) routes to onDisabledTap.
        val actionLabel: String? = null
    ) : SettingsItem()

    /**
     * Slider item - displays label, value text, and a SeekBar.
     * Used for numeric range settings (brightness, zoom, volume, etc.).
     */
    data class Slider(
        override val id: String,
        val label: String,
        val value: Int,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        val unit: String? = null,
        val isEnabled: Boolean = true
    ) : SettingsItem()

    /**
     * Text input item - displays label and editable text field.
     * Used for string settings (URLs, names, etc.).
     */
    data class TextInput(
        override val id: String,
        val label: String,
        val value: String,
        val placeholder: String? = null,
        val inputType: Int = android.text.InputType.TYPE_CLASS_TEXT,
        val isEnabled: Boolean = true,
        val labelColor: Int? = null,
        /** Optional hint text rendered beneath the input field. */
        val sublabel: String? = null
    ) : SettingsItem()

    /**
     * Picker item - displays label and current selection, taps to show options.
     * Used for single-selection from a list of options (shown inline or as dialog).
     */
    data class Picker(
        override val id: String,
        val label: String,
        val selectedValue: String,
        val displayValue: CharSequence,
        val sublabel: String? = null,
        val options: List<PickerOption> = emptyList(),
        val isEnabled: Boolean = true
    ) : SettingsItem()

    /**
     * Confidence bar - horizontal bar with fill level and threshold marker.
     * Used for live wake word detection visualization.
     */
    data class ConfidenceBar(
        override val id: String,
        val value: Int,           // Current value 0-100
        val threshold: Int,       // Threshold marker position 0-100
        val label: String? = null // Optional label text
    ) : SettingsItem()
}

/**
 * Option for a Picker item.
 */
data class PickerOption(
    val value: String,
    val label: String,
    val sublabel: String? = null,
    val enabled: Boolean = true,
    val isCustomInput: Boolean = false,
    val customInputSettingKey: String? = null,
    val customInputTitle: String? = null,
    val customInputHint: String? = null,
    val customInputUnit: String? = null,
    // Optional hex color — renders a circular swatch left of the row (D.38).
    val colorSwatch: String? = null,
    // Callback name invoked when a DISABLED option is tapped (threaded into
    // SettingsItem.Checkmark.onDisabledTap). Lets a grayed picker row explain
    // itself rather than silently no-op.
    val onDisabledTap: String? = null,
    // Right-aligned action button on a disabled row (threaded into
    // SettingsItem.Checkmark.actionLabel), e.g. "Download".
    val actionLabel: String? = null
)
