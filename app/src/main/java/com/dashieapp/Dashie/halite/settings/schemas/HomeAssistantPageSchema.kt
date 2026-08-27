package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Home Assistant settings page.
 *
 * On Android the app is always HA-centric (no "enable HA" toggle needed).
 *
 * Sections:
 * 1. Connection — dashboard URL configuration
 * 2. Display — hide sidebar, hide tabs
 * 3. Device API — enable, password, port
 * 4. Navigation — return to home timeout
 */
object HomeAssistantPageSchema {

    private val apiEnabled = Condition.IsTrue("connection.apiEnabled")
    private val notUsingCustomUrl = Condition.IsFalse("connection.useCustomUrl")
    private val usingCustomUrl = Condition.IsTrue("connection.useCustomUrl")

    /**
     * @param needsLogin when true, render a "Sign-in Required" action row
     *   at the top of the page. Tap dispatches the `signInToHa` action.
     */
    fun create(needsLogin: Boolean = false) = SettingsPageSchema(
        // ⚠️ The id is a WIRE VALUE — the Control Center's NATIVE_SETTINGS_PAGE_MAP
        // ("cc-ha" → "home_assistant"), deep-link targets and navigateTo strings
        // all key off it. The TITLE is display-only, so renaming the title to
        // "Dashboard config" (2026-08-18) is safe; renaming the id is not.
        id = "home_assistant",
        title = com.dashieapp.Dashie.controlcenter.ControlCenterStateProvider.CARD_LABEL,
        sections = listOfNotNull(
            signInRequiredSection().takeIf { needsLogin },
            connectionSection(),
            displaySection(),
            deviceApiSection(),
            navigationSection()
        ),
        subScreens = mapOf(
            "ha-return-home" to returnHomeSubScreen()
        )
    )

    /**
     * Top-of-page "Sign-in Required" notice + action row, shown only
     * when an access token is missing for the configured HA URL. Tap
     * triggers the `signInToHa` action which closes settings and
     * launches HaLoginActivity (forceActivityFlow=true).
     */
    private fun signInRequiredSection() = SettingsSection(
        header = "Sign-in Required",
        footer = "Calendars and other Home Assistant integrations won't work until you sign in.",
        items = listOf(
            SchemaItem.Action(
                id = "sign_in_to_ha",
                label = "Sign in to Home Assistant",
                action = "signInToHa"
            )
        )
    )

    // ── Section 1: Connection ─────────────────────────────────────────────

    private fun connectionSection() = SettingsSection(
        header = "Connection",
        items = listOf(
            SchemaItem.Navigation(
                id = "ha_url",
                label = "Dashboard URL",
                navigateTo = "ext:url_config",
                displayValueKey = "connection.urlDisplay"
            ),
            SchemaItem.Toggle(
                id = "use_custom_url",
                label = "Use Non-HA URL",
                settingKey = "connection.useCustomUrl",
                onChanged = "notifyHaConfigChanged"
            ),
            SchemaItem.TextInput(
                id = "custom_url",
                label = "URL",
                settingKey = "connection.customUrl",
                placeholder = "https://example.com/dashboard",
                onChanged = "notifyHaConfigChanged",
                visibleWhen = usingCustomUrl
            )
        )
    )

    // ── Section 2: Display ────────────────────────────────────────────────

    private fun displaySection() = SettingsSection(
        header = "Display",
        visibleWhen = notUsingCustomUrl,
        items = listOf(
            SchemaItem.Toggle(
                id = "hide_sidebar",
                label = "Hide Sidebar",
                settingKey = "connection.hideSidebar",
                onChanged = "notifyHaDisplayChanged"
            ),
            SchemaItem.Toggle(
                id = "hide_tabs",
                label = "Hide Header / Tabs",
                settingKey = "connection.hideTabs",
                onChanged = "notifyHaDisplayChanged"
            ),
            SchemaItem.Toggle(
                id = "hide_search",
                label = "Hide Search",
                settingKey = "connection.hideSearch",
                onChanged = "notifyHaDisplayChanged"
            ),
            SchemaItem.Toggle(
                id = "hide_assistant",
                label = "Hide Assistant",
                settingKey = "connection.hideAssistant",
                onChanged = "notifyHaDisplayChanged"
            )
        )
    )

    // ── Section 3: Device API ─────────────────────────────────────────────

    private fun deviceApiSection() = SettingsSection(
        header = "Device API",
        items = listOf(
            SchemaItem.Toggle(
                id = "api_enabled",
                label = "Enable API",
                settingKey = "connection.apiEnabled",
                onChanged = "notifyApiChanged"
            ),
            SchemaItem.TextInput(
                id = "api_password",
                label = "Password",
                settingKey = "connection.apiPassword",
                placeholder = "API password",
                inputType = "password",
                onChanged = "notifyApiChanged",
                visibleWhen = apiEnabled
            ),
            SchemaItem.TextInput(
                id = "api_port",
                label = "Port",
                settingKey = "connection.apiPort",
                placeholder = "2323",
                inputType = "number",
                onChanged = "notifyApiChanged",
                visibleWhen = apiEnabled
            )
        )
    )

    // ── Section 4: Return to Home ─────────────────────────────────────────

    private fun navigationSection() = SettingsSection(
        header = "Return to Home",
        items = listOf(
            SchemaItem.Toggle(
                id = "show_floating_back_button",
                label = "Floating Back Button",
                settingKey = "connection.showFloatingBackButton",
                onChanged = "notifyHaDisplayChanged"
            ),
            SchemaItem.Navigation(
                id = "return_home",
                label = "After Timeout",
                navigateTo = "ha-return-home",
                displayValueKey = "connection.returnHomeDisplay"
            )
        )
    )

    // ── Sub-screen: Return to Home ────────────────────────────────────────

    private fun returnHomeSubScreen() = SubScreenSchema(
        title = "Return to Home",
        parent = "home_assistant",
        sections = listOf(
            SettingsSection(
                footer = "Navigate back to the default dashboard view after a period of inactivity.",
                items = listOf(
                    SchemaItem.Picker(
                        id = "return_home_timeout",
                        label = "Timeout",
                        settingKey = "connection.returnHomeTimeout",
                        options = listOf(
                            SchemaPickerOption("0", "Disabled"),
                            SchemaPickerOption("60", "1 min"),
                            SchemaPickerOption("120", "2 min"),
                            SchemaPickerOption("180", "3 min"),
                            SchemaPickerOption("300", "5 min"),
                            SchemaPickerOption("600", "10 min"),
                            SchemaPickerOption("900", "15 min"),
                            SchemaPickerOption("1800", "30 min")
                        ),
                        onChanged = "notifyReturnHomeChanged"
                    )
                )
            )
        )
    )
}
