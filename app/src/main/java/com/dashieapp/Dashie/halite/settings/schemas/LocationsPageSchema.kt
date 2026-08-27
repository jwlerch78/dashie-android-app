package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Locations settings page.
 *
 * Sections:
 * 1. Location Tracking — enable tracking, show radius circles
 * 2. Travel Time Estimates — enable, traffic model picker
 * 3. Default Early Arrival — games, practices, other events
 * 4. Notifications — location notification sounds
 */
object LocationsPageSchema {

    private val trackingEnabled = Condition.IsTrue("locations.trackingEnabled")
    private val travelTimeEnabled = Condition.And(listOf(
        trackingEnabled,
        Condition.IsTrue("locations.travelTimeEnabled")
    ))

    fun create() = SettingsPageSchema(
        id = "locations",
        title = "Locations",
        sections = listOf(
            locationTrackingSection(),
            travelTimeSection(),
            earlyArrivalSection(),
            notificationsSection()
        ),
        subScreens = mapOf(
            "locations-traffic-model" to trafficModelSubScreen(),
            "locations-early-games" to earlyArrivalSubScreen("Games", "locations.earlyArrivalGames"),
            "locations-early-practices" to earlyArrivalSubScreen("Practices", "locations.earlyArrivalPractices"),
            "locations-early-other" to earlyArrivalSubScreen("Other Events", "locations.earlyArrivalOther")
        )
    )

    // ── Section 1: Location Tracking ────────────────────────────────

    private fun locationTrackingSection() = SettingsSection(
        header = "Location Tracking",
        items = listOf(
            SchemaItem.Toggle(
                id = "tracking_enabled",
                label = "Enable Location Tracking",
                settingKey = "locations.trackingEnabled",
                sublabel = "Share family member locations on the dashboard",
                onChanged = "notifyLocationsConfigChanged"
            ),
            SchemaItem.Toggle(
                id = "show_radius_circles",
                label = "Show Location Radius",
                settingKey = "locations.showRadiusCircles",
                sublabel = "Display accuracy radius on the map",
                visibleWhen = trackingEnabled,
                onChanged = "notifyLocationsConfigChanged"
            )
        )
    )

    // ── Section 2: Travel Time Estimates ────────────────────────────

    private fun travelTimeSection() = SettingsSection(
        header = "Travel Time Estimates",
        visibleWhen = trackingEnabled,
        items = listOf(
            SchemaItem.Toggle(
                id = "travel_time_enabled",
                label = "Calculate Travel Times",
                settingKey = "locations.travelTimeEnabled",
                sublabel = "Show estimated travel time to upcoming events",
                onChanged = "notifyLocationsConfigChanged"
            ),
            SchemaItem.Navigation(
                id = "traffic_model",
                label = "Traffic Model",
                navigateTo = "locations-traffic-model",
                displayValueKey = "locations.trafficModelDisplay",
                visibleWhen = travelTimeEnabled
            )
        )
    )

    // ── Section 3: Default Early Arrival ────────────────────────────

    private fun earlyArrivalSection() = SettingsSection(
        header = "Default Early Arrival",
        footer = "How early to leave before each event type",
        visibleWhen = travelTimeEnabled,
        items = listOf(
            SchemaItem.Navigation(
                id = "early_games",
                label = "Games",
                navigateTo = "locations-early-games",
                displayValueKey = "locations.earlyArrivalGamesDisplay"
            ),
            SchemaItem.Navigation(
                id = "early_practices",
                label = "Practices",
                navigateTo = "locations-early-practices",
                displayValueKey = "locations.earlyArrivalPracticesDisplay"
            ),
            SchemaItem.Navigation(
                id = "early_other",
                label = "Other Events",
                navigateTo = "locations-early-other",
                displayValueKey = "locations.earlyArrivalOtherDisplay"
            )
        )
    )

    // ── Section 4: Notifications ────────────────────────────────────

    private fun notificationsSection() = SettingsSection(
        header = "Notifications",
        visibleWhen = trackingEnabled,
        items = listOf(
            SchemaItem.Toggle(
                id = "notification_sounds",
                label = "Location Notification Sounds",
                settingKey = "locations.notificationSounds",
                onChanged = "notifyLocationsConfigChanged"
            )
        )
    )

    // ── Sub-screens ─────────────────────────────────────────────────

    private fun trafficModelSubScreen() = SubScreenSchema(
        title = "Traffic Model",
        parent = "locations",
        sections = listOf(
            SettingsSection(
                footer = "How to estimate traffic conditions for travel times",
                items = listOf(
                    SchemaItem.Picker(
                        id = "traffic_model_picker",
                        label = "Traffic Model",
                        settingKey = "locations.trafficModel",
                        options = listOf(
                            SchemaPickerOption("best_guess", "Best Guess"),
                            SchemaPickerOption("optimistic", "Optimistic"),
                            SchemaPickerOption("pessimistic", "Pessimistic")
                        ),
                        onChanged = "notifyLocationsConfigChanged"
                    )
                )
            )
        )
    )

    private fun earlyArrivalSubScreen(title: String, settingKey: String) = SubScreenSchema(
        title = "Early Arrival — $title",
        parent = "locations",
        sections = listOf(
            SettingsSection(
                footer = "How many minutes before the event to leave",
                items = listOf(
                    SchemaItem.Picker(
                        id = "early_arrival_picker_${settingKey.substringAfterLast('.')}",
                        label = title,
                        settingKey = settingKey,
                        options = listOf(
                            SchemaPickerOption("0", "None"),
                            SchemaPickerOption("5", "5 minutes"),
                            SchemaPickerOption("10", "10 minutes"),
                            SchemaPickerOption("15", "15 minutes"),
                            SchemaPickerOption("30", "30 minutes")
                        ),
                        onChanged = "notifyLocationsConfigChanged"
                    )
                )
            )
        )
    )
}
