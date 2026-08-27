package com.dashieapp.Dashie.halite.settings.schema

/**
 * Declarative schema for a settings page.
 * Defines the structure (sections, items, sub-screens) independently of
 * any particular renderer — consumed by both the Kotlin RecyclerView
 * renderer and (in the future) a JS/web renderer.
 */
data class SettingsPageSchema(
    val id: String,
    val title: String,
    val sections: List<SettingsSection>,
    val subScreens: Map<String, SubScreenSchema> = emptyMap(),
    /**
     * Hide the nav-bar "Done" button (which closes all of Settings). Set on
     * data-entry forms — e.g. Add / Edit Family Member — where "Done" reads
     * like a save/confirm but actually discards the form without saving
     * (D.30). Such screens carry their own explicit Save / Cancel actions.
     */
    val hideDoneButton: Boolean = false
)

data class SubScreenSchema(
    val title: String,
    val parent: String,
    val sections: List<SettingsSection>,
    /** If true, items are loaded at runtime (e.g. entity lists from HA) */
    val dynamic: Boolean = false
)

data class SettingsSection(
    val header: String? = null,
    val footer: String? = null,
    val items: List<SchemaItem>,
    val visibleWhen: Condition? = null
)

/**
 * Declarative item definition — describes *what* a setting is, not *how*
 * it renders.  The renderer maps these to [SettingsItem] view types.
 */
sealed class SchemaItem {
    abstract val id: String
    abstract val label: String
    abstract val visibleWhen: Condition?
    abstract val enabledWhen: Condition?

    data class Toggle(
        override val id: String,
        override val label: String,
        val settingKey: String,
        val defaultValue: Boolean = false,
        val sublabel: String? = null,
        /** Dynamic sublabel resolved from value provider (overrides sublabel if set) */
        val sublabelKey: String? = null,
        val onChanged: String? = null,
        override val visibleWhen: Condition? = null,
        override val enabledWhen: Condition? = null
    ) : SchemaItem()

    data class Navigation(
        override val id: String,
        override val label: String,
        val navigateTo: String,
        val sublabel: String? = null,
        /** Static display value */
        val displayValue: String? = null,
        /** Setting key whose value is shown as the display value */
        val displayValueKey: String? = null,
        /** When true, skip the sub-screen and navigate directly to the picker inside it */
        val directPicker: Boolean = false,
        /** Optional color dot shown to the left of the label (hex color, e.g. "#FF6B6B") */
        val colorDot: String? = null,
        /** Optional avatar image URL — when set, shows circular avatar instead of color dot */
        val avatarUrl: String? = null,
        /** When non-null, show checkmark (✓) instead of chevron. True = checked, false = unchecked. */
        val checked: Boolean? = null,
        override val visibleWhen: Condition? = null,
        override val enabledWhen: Condition? = null
    ) : SchemaItem()

    data class Picker(
        override val id: String,
        override val label: String,
        val settingKey: String,
        val options: List<SchemaPickerOption>,
        val onChanged: String? = null,
        val sublabel: String? = null,
        /** Section headers for grouped pickers: list of (title, optionIndex) pairs */
        val sectionHeaders: List<Pair<String, Int>>? = null,
        /** Optional lead-in shown at the top of the picker sub-screen (after the
         *  first section header, before the options): a description Info line and
         *  a primary (orange-block) action row that invokes [leadingActionCallback]
         *  — e.g. the Voice & AI explainer + "Dashie Details & Setup" button. */
        val leadingDescription: String? = null,
        val leadingActionId: String? = null,
        val leadingActionLabel: String? = null,
        val leadingActionCallback: String? = null,
        /** Placeholder for the inline custom-input row (should state the accepted range) */
        val customInputPlaceholder: String? = null,
        /**
         * Overrides the collapsed row's display text with a live value from the valueProvider,
         * the way [Navigation.displayValueKey] does. A picker's label is otherwise pinned to the
         * matched option's STATIC text, which can't express a runtime fact — e.g. WHICH local
         * engine "My own AI" currently resolves to. Blank/absent → falls back to the option
         * label, so pickers that don't set this are unaffected. Does not change what's stored:
         * the picker dialog still lists (and writes) `options`.
         */
        val displayValueKey: String? = null,
        /**
         * Key yielding "local" / "cloud" to tag the row with the console's colored badge.
         * Anything else (or blank) → no badge. See [ValueBadge].
         */
        val badgeKey: String? = null,
        override val visibleWhen: Condition? = null,
        override val enabledWhen: Condition? = null
    ) : SchemaItem()

    data class Slider(
        override val id: String,
        override val label: String,
        val settingKey: String,
        val min: Int,
        val max: Int,
        val step: Int = 1,
        val unit: String? = null,
        val onChanged: String? = null,
        override val visibleWhen: Condition? = null,
        override val enabledWhen: Condition? = null
    ) : SchemaItem()

    data class Info(
        override val id: String,
        override val label: String,
        /** Setting key whose value is displayed (read-only) */
        val valueKey: String? = null,
        val staticValue: String? = null,
        /** Setting key for optional value color (Int color value, null = default) */
        val valueColorKey: String? = null,
        override val visibleWhen: Condition? = null,
        override val enabledWhen: Condition? = null
    ) : SchemaItem()

    data class Action(
        override val id: String,
        override val label: String,
        val action: String,
        val destructive: Boolean = false,
        override val visibleWhen: Condition? = null,
        override val enabledWhen: Condition? = null
    ) : SchemaItem()

    data class TextInput(
        override val id: String,
        override val label: String,
        val settingKey: String,
        val placeholder: String? = null,
        val inputType: String = "text",
        /** Optional hint text rendered beneath the input field. */
        val sublabel: String? = null,
        /**
         * When true, the item is rendered as a clickable row showing the
         * current value + edit icon (like a Navigation item); tapping it
         * opens a pop-up dialog with a text field. Avoids the inline-
         * EditText path where the on-screen keyboard can cover the input.
         */
        val dialogMode: Boolean = false,
        val dialogTitle: String? = null,
        /** Optional computed key whose value is SHOWN on the collapsed row instead of the raw
         *  [settingKey] (the edit dialog still reads/writes [settingKey]). Blank → falls back to
         *  the raw value. Lets a free-text row display a friendly-decoded label, e.g. the TTS
         *  voice row showing "Amy (fast)" for a stored `en_US-amy-low` (punch #3). dialogMode only. */
        val displayValueKey: String? = null,
        val onChanged: String? = null,
        override val visibleWhen: Condition? = null,
        override val enabledWhen: Condition? = null
    ) : SchemaItem()
}

data class SchemaPickerOption(
    val value: String,
    val label: String,
    val sublabel: String? = null,
    val enabled: Boolean = true,
    val isCustomInput: Boolean = false,
    // When isCustomInput is true and these are set, tapping the option opens
    // a number-entry dialog that writes to customInputSettingKey (instead of
    // showing an inline text input below the list).
    val customInputSettingKey: String? = null,
    val customInputTitle: String? = null,
    val customInputHint: String? = null,
    // Unit suffix appended to the custom value when displayed (e.g. "pt" → "120pt").
    val customInputUnit: String? = null,
    // Optional hex color — when set, the picker row shows a circular swatch
    // to the left of the label (e.g. the family member color picker — D.38).
    val colorSwatch: String? = null,
    // Callback name invoked when this option is tapped while DISABLED. Instead of
    // an inert no-op, the grayed row routes the tap to the named callback so it
    // can explain why it's unavailable (e.g. Cloud/Hybrid presets without an
    // account/credits → cloud-activation dialog). See SettingsItem.onDisabledTap.
    val onDisabledTap: String? = null,
    // Right-aligned action button on a disabled row (e.g. "Download" on an
    // on-device model that isn't installed). See SettingsItem.Checkmark.actionLabel.
    val actionLabel: String? = null
)

/**
 * Conditions for controlling visibility and enabled state of sections/items.
 * Evaluated at render time against the current settings values.
 */
sealed class Condition {
    data class Equals(val key: String, val value: Any) : Condition()
    data class NotEquals(val key: String, val value: Any) : Condition()
    data class IsTrue(val key: String) : Condition()
    data class IsFalse(val key: String) : Condition()
    data class And(val conditions: List<Condition>) : Condition()
    data class Or(val conditions: List<Condition>) : Condition()
    /** Always evaluates to false — hides an item unconditionally. */
    data object Never : Condition()
}
