package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema
import org.json.JSONObject

/**
 * Schema definition for the Video Feeds settings page (HA registry mode).
 *
 * Dynamic schema — the feed list is generated from the current config rules.
 * Each feed appears as a Navigation item linking to the feed detail screen.
 *
 * Sections when enabled:
 * 1. Enable toggle
 * 2. Feeds — dynamic list of configured feeds + "Add Feed" action
 * 3. Display Settings — display method, feed size, alerts, auto-dismiss,
 *    continue while active, cooldown
 */
object VideoFeedsPageSchema {

    private val feedsEnabled = Condition.IsTrue("videoFeed.enabled")

    /**
     * @param feedProvider Returns current feed rules from VideoFeedPreferences.
     *   Called each time the schema is evaluated (supports dynamic lists).
     */
    fun create(feedProvider: () -> List<JSONObject> = { emptyList() }) = SettingsPageSchema(
        id = "video_feeds",
        title = "Video Feeds",
        sections = listOf(
            enableSection(),
            feedsSection(feedProvider()),
            displaySettingsSection()
        ),
        subScreens = mapOf(
            "alerts" to alertsSubScreen()
        )
    )

    // ── Section 1: Enable Toggle ──────────────────────────────────────

    private fun enableSection() = SettingsSection(
        items = listOf(
            SchemaItem.Toggle(
                id = "vf_enabled",
                label = "Enable Video Feeds",
                settingKey = "videoFeed.enabled",
                onChanged = "notifyVideoFeedEnabledChanged"
            )
        )
    )

    // ── Section 2: Feeds ──────────────────────────────────────────────

    private fun feedsSection(feeds: List<JSONObject>): SettingsSection {
        val feedItems = if (feeds.isEmpty()) {
            listOf(
                SchemaItem.Info(
                    id = "vf_no_feeds",
                    label = "No feeds configured",
                    visibleWhen = feedsEnabled
                )
            )
        } else {
            feeds.map { feed ->
                val feedId = feed.optString("id", feed.optString("ruleId", ""))
                val name = feed.optString("name",
                    feed.optString("cameraName",
                        feed.optString("label", "Unnamed Feed")))
                val fps = feed.optInt("fps", 10)
                val resolution = feed.optInt("resolution", 480)
                val fpsLabel = when (fps) { 5 -> "5 fps"; 10 -> "10 fps"; 15 -> "15 fps"; 20 -> "20 fps"; 0 -> "Native fps"; else -> "${fps} fps" }
                val resLabel = when (resolution) { 320 -> "320p"; 480 -> "480p"; 640 -> "640p"; 720 -> "720p"; 0 -> "Native"; else -> "${resolution}p" }
                val frigateSuffix = if (feed.optBoolean("isFrigateCamera", false)) ", Frigate playback enabled" else ""
                SchemaItem.Navigation(
                    id = "vf_feed_$feedId",
                    label = name,
                    sublabel = "$resLabel, $fpsLabel$frigateSuffix",
                    navigateTo = "ext:feed_detail:$feedId",
                    displayValueKey = "videoFeed.feedMode.$feedId",
                    visibleWhen = feedsEnabled
                )
            }
        }

        val addFeedItem = SchemaItem.Action(
            id = "vf_add_feed",
            label = "+ Add Feed",
            action = "videoFeedAddFeed",
            visibleWhen = feedsEnabled
        )

        return SettingsSection(
            header = "Feeds",
            visibleWhen = feedsEnabled,
            items = feedItems + addFeedItem
        )
    }

    // ── Section 3: Display Settings ───────────────────────────────────

    private fun displaySettingsSection() = SettingsSection(
        header = "Display Settings",
        visibleWhen = feedsEnabled,
        items = listOf(
            SchemaItem.Picker(
                id = "vf_display_method",
                label = "Display Method",
                settingKey = "videoFeed.feedLocation",
                options = listOf(
                    SchemaPickerOption("sidebar", "Sidebar"),
                    SchemaPickerOption("notification", "Notification")
                ),
                onChanged = "notifyVideoFeedConfigChanged"
            ),
            SchemaItem.Picker(
                id = "vf_feed_size",
                label = "Feed Size",
                settingKey = "videoFeed.feedSize",
                options = listOf(
                    SchemaPickerOption("small", "Small (25%)"),
                    SchemaPickerOption("medium", "Medium (33%)"),
                    SchemaPickerOption("large", "Large (50%)"),
                    SchemaPickerOption("xl", "Extra Large (75%)")
                ),
                onChanged = "notifyVideoFeedConfigChanged"
            ),
            SchemaItem.Picker(
                id = "vf_auto_dismiss",
                label = "Auto-Dismiss",
                settingKey = "videoFeed.autoDismissSeconds",
                options = listOf(
                    SchemaPickerOption("0", "Off"),
                    SchemaPickerOption("5", "5 sec"),
                    SchemaPickerOption("10", "10 sec"),
                    SchemaPickerOption("15", "15 sec"),
                    SchemaPickerOption("20", "20 sec"),
                    SchemaPickerOption("30", "30 sec"),
                    SchemaPickerOption("45", "45 sec"),
                    SchemaPickerOption("60", "1 min"),
                    SchemaPickerOption("120", "2 min"),
                    SchemaPickerOption("300", "5 min")
                ),
                onChanged = "notifyVideoFeedConfigChanged"
            ),
            SchemaItem.Toggle(
                id = "vf_continue_while_active",
                label = "Display while Active",
                sublabel = "Camera will not dismiss while trigger remains active",
                settingKey = "videoFeed.continueWhileActive",
                onChanged = "notifyVideoFeedConfigChanged"
            ),
            SchemaItem.Picker(
                id = "vf_cooldown",
                label = "Cooldown",
                settingKey = "videoFeed.cooldownSeconds",
                options = listOf(
                    SchemaPickerOption("0", "Off"),
                    SchemaPickerOption("5", "5 sec"),
                    SchemaPickerOption("10", "10 sec"),
                    SchemaPickerOption("15", "15 sec"),
                    SchemaPickerOption("20", "20 sec"),
                    SchemaPickerOption("30", "30 sec"),
                    SchemaPickerOption("45", "45 sec"),
                    SchemaPickerOption("60", "1 min"),
                    SchemaPickerOption("120", "2 min"),
                    SchemaPickerOption("300", "5 min")
                ),
                onChanged = "notifyVideoFeedConfigChanged"
            ),
            SchemaItem.Navigation(
                id = "vf_alerts",
                label = "Alerts",
                navigateTo = "alerts",
                displayValueKey = "videoFeed.alertsDisplay"
            )
        )
    )

    // ── Sub-screen: Alerts ──────────────────────────────────────────

    private fun alertsSubScreen() = SubScreenSchema(
        title = "Alerts",
        parent = "video_feeds",
        sections = listOf(
            SettingsSection(
                footer = "Volume for all video-feed alert sounds on this device. " +
                    "Turn a feed's sound on or off in that feed's own settings.",
                items = listOf(
                    SchemaItem.Slider(
                        id = "vf_alert_volume",
                        label = "Alert Volume",
                        settingKey = "videoFeed.alertVolume",
                        min = 1,
                        max = 10,
                        step = 1,
                        onChanged = "notifyVideoFeedConfigChanged"
                    )
                )
            )
        )
    )
}
