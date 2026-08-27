package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Chores & Rewards settings page.
 *
 * Sections:
 * 1. Chores — master toggle, anyone-can-complete, participants, upcoming days
 * 2. Rewards — toggle (cascading: disabled when chores is off)
 * 3. Info — help text
 *
 * Cascading behavior: disabling chores also disables rewards and grays out
 * dependent options. This is handled via the onChanged callback in SettingsSchemaWiring.
 */
object ChoresRewardsPageSchema {

    private val choresEnabled = Condition.IsTrue("choresRewards.choresEnabled")

    fun create() = SettingsPageSchema(
        id = "chores_rewards",
        title = "Chores & Rewards",
        sections = listOf(
            choresSection(),
            rewardsSection(),
            infoSection()
        ),
        subScreens = mapOf(
            "chores-upcoming-days" to upcomingDaysSubScreen()
        )
    )

    // ── Section 1: Chores ───────────────────────────────────────────

    private fun choresSection() = SettingsSection(
        header = "Chores",
        items = listOf(
            SchemaItem.Toggle(
                id = "chores_enabled",
                label = "Enable Chores",
                settingKey = "choresRewards.choresEnabled",
                onChanged = "notifyChoresRewardsConfigChanged"
            ),
            SchemaItem.Toggle(
                id = "anyone_enabled",
                label = "Allow Anyone to Complete Chores",
                settingKey = "choresRewards.anyoneEnabled",
                sublabel = "Any family member can mark chores as done",
                visibleWhen = choresEnabled,
                onChanged = "notifyChoresRewardsConfigChanged"
            ),
            SchemaItem.Navigation(
                id = "participants",
                label = "Chore Participants",
                navigateTo = "ext:chores_participants_picker",
                displayValueKey = "choresRewards.participantsDisplay",
                visibleWhen = choresEnabled
            ),
            SchemaItem.Navigation(
                id = "upcoming_days",
                label = "Show Upcoming Chores",
                navigateTo = "chores-upcoming-days",
                displayValueKey = "choresRewards.upcomingDaysDisplay",
                visibleWhen = choresEnabled
            ),
            // Orange single-click action — chores & rewards are authored on
            // the mobile app / Console, so this opens the Connect-to-Mobile
            // QR. No sublabel; matches the Advanced page action rows.
            SchemaItem.Action(
                id = "add_chores_mobile",
                label = "Add Chores on Mobile",
                action = "choresAddOnMobile"
            )
        )
    )

    // ── Section 2: Rewards ──────────────────────────────────────────

    private fun rewardsSection() = SettingsSection(
        header = "Rewards",
        visibleWhen = choresEnabled,
        items = listOf(
            SchemaItem.Toggle(
                id = "rewards_enabled",
                label = "Enable Rewards",
                settingKey = "choresRewards.rewardsEnabled",
                sublabel = "Allow family members to earn rewards for completing chores",
                onChanged = "notifyChoresRewardsConfigChanged"
            )
        )
    )

    // ── Section 3: Info ─────────────────────────────────────────────

    private fun infoSection() = SettingsSection(
        items = listOf(
            SchemaItem.Info(
                id = "chores_info",
                label = "When chores are disabled, the chores widget and all chore-related features are hidden from the dashboard."
            )
        )
    )

    // ── Sub-screens ─────────────────────────────────────────────────

    private fun upcomingDaysSubScreen() = SubScreenSchema(
        title = "Show Upcoming Chores",
        parent = "chores_rewards",
        sections = listOf(
            SettingsSection(
                footer = "How many days ahead to show upcoming chores on the dashboard",
                items = listOf(
                    SchemaItem.Picker(
                        id = "upcoming_days_picker",
                        label = "Days",
                        settingKey = "choresRewards.upcomingDays",
                        options = (0..7).map { days ->
                            SchemaPickerOption(
                                days.toString(),
                                if (days == 0) "Today only" else "$days day${if (days != 1) "s" else ""}"
                            )
                        },
                        onChanged = "notifyChoresRewardsConfigChanged"
                    )
                )
            )
        )
    )
}
