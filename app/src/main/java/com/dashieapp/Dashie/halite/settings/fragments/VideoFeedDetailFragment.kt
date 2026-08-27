package com.dashieapp.Dashie.halite.settings.fragments

import com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import org.json.JSONObject

/**
 * Feed detail/subscription fragment.
 * Shows feed info, subscription mode picker (on-demand, trigger, trigger+alert, ignored),
 * and edit/delete actions.
 */
class VideoFeedDetailFragment : BaseSettingsFragment() {

    companion object {
        fun create(
            feedId: String,
            onModeChanged: () -> Unit,
            onEdit: (String) -> Unit,
            onDelete: (String) -> Unit
        ): VideoFeedDetailFragment {
            return VideoFeedDetailFragment().apply {
                this.feedId = feedId
                this.onModeChanged = onModeChanged
                this.onEdit = onEdit
                this.onDelete = onDelete
            }
        }

        private val MODE_OPTIONS = listOf(
            Triple("subscribed", "On-demand", "Available on-demand only"),
            Triple("trigger", "Trigger", "Auto-shows on trigger, silent"),
            Triple("trigger_alert", "Trigger + Alert", "Auto-shows + plays alert sound"),
            Triple("ignored", "Ignored", "Hidden from this device")
        )

        private val FPS_LABELS = mapOf(
            5 to "5 fps", 10 to "10 fps", 15 to "15 fps", 20 to "20 fps", 0 to "Native"
        )

        private val RESOLUTION_LABELS = mapOf(
            320 to "320p", 480 to "480p", 640 to "640p", 720 to "720p", 0 to "Native"
        )

        private val SOUND_LABELS = mapOf(
            "notify_bell_tap" to "Bell Tap",
            "notify_chord_wash" to "Chord Wash",
            "notify_pulse_alert" to "Pulse Alert",
            "notify_soft_double" to "Soft Double",
            "notify_tri_fall" to "Tri Fall",
            "notify_tri_rise" to "Tri Rise",
            "extra_bubble" to "Bubble",
            "extra_celesta" to "Celesta",
            "extra_deep_bell" to "Deep Bell",
            "extra_duo_chirp" to "Duo Chirp",
            "extra_wood_knock" to "Wood Knock",
            "extra_xylophone_pair" to "Xylophone Pair"
        )
    }

    private lateinit var feedId: String
    private var onModeChanged: () -> Unit = {}
    private var onEdit: (String) -> Unit = {}
    private var onDelete: (String) -> Unit = {}

    private val prefs: VideoFeedPreferences by lazy {
        VideoFeedPreferences(requireContext())
    }

    private val feed: JSONObject?
        get() = prefs.getRules().find {
            it.optString("id", it.optString("ruleId", "")) == feedId
        }

    override val title: String
        get() = feed?.let {
            it.optString("name",
                it.optString("cameraName",
                    it.optString("label", "Feed Detail")))
        } ?: "Feed Detail"

    override fun getItems(): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()
        val feedData = feed ?: return listOf(
            SettingsItem.Info(id = "no_feed", text = "Feed not found")
        )

        val currentMode = prefs.getFeedMode(feedId)

        // Subscription mode section (at top)
        items.add(SettingsItem.SectionHeader(
            id = "header_mode",
            title = "Subscription"
        ))

        for ((mode, label, sublabel) in MODE_OPTIONS) {
            items.add(SettingsItem.Checkmark(
                id = "mode_$mode",
                label = label,
                isChecked = mode == currentMode,
                sublabel = sublabel
            ))
        }

        // Feed info section
        items.add(SettingsItem.SectionHeader(
            id = "header_info",
            title = "Feed Info"
        ))

        items.add(SettingsItem.Action(
            id = "edit_feed",
            label = "Edit Feed"
        ))

        val cameraEntity = feedData.optString("cameraEntityId", "")
        if (cameraEntity.isNotEmpty()) {
            val cameraName = feedData.optString("cameraName",
                feedData.optString("name", cameraEntity.removePrefix("camera.")))
            val entityShort = cameraEntity.removePrefix("camera.")
            items.add(SettingsItem.ValueDisplay(
                id = "feed_camera",
                label = cameraName,
                value = entityShort
            ))

            // Frigate camera (override or auto-detected). Mirrors the editor row.
            val frigateOverride = feedData.optString("frigateCameraOverride", "")
            val frigateAuto = feedData.optString("frigateCameraName", "")
            val (frigateValue, frigateSub) = when (frigateOverride) {
                "__none__" -> "None" to null
                "" -> {
                    if (frigateAuto.isNotEmpty()) frigateAuto to "auto-detected"
                    else "None" to null
                }
                else -> frigateOverride to null
            }
            items.add(SettingsItem.ValueDisplay(
                id = "feed_frigate_camera",
                label = "Frigate Camera",
                value = frigateValue,
                sublabel = frigateSub
            ))
        }

        val triggerEntity = feedData.optString("triggerEntityId", "")
        if (triggerEntity.isNotEmpty()) {
            items.add(SettingsItem.ValueDisplay(
                id = "feed_trigger",
                label = "Trigger",
                value = triggerEntity
            ))
        }

        val sourceType = feedData.optString("streamSourceType", "entity")
        if (sourceType != "entity") {
            items.add(SettingsItem.ValueDisplay(
                id = "feed_source_type",
                label = "Source",
                value = sourceType.uppercase()
            ))
            val url = feedData.optString("streamSourceUrl", "")
            if (url.isNotEmpty()) {
                items.add(SettingsItem.ValueDisplay(
                    id = "feed_source_url",
                    label = "URL",
                    value = url
                ))
            }
        }

        // Alert sound info (always show)
        val triggerSound = feedData.optString("triggerSound", "")
        val playSound = feedData.optBoolean("playSoundOnTrigger", false)
        val soundDisplay = if (playSound && triggerSound.isNotEmpty()) {
            SOUND_LABELS[triggerSound] ?: triggerSound
        } else {
            "None"
        }
        items.add(SettingsItem.ValueDisplay(
            id = "feed_alert_sound",
            label = "Alert Sound",
            value = soundDisplay
        ))

        val fps = feedData.optInt("fps", 10)
        val resolution = feedData.optInt("resolution", 480)
        val fpsDisplay = FPS_LABELS[fps] ?: "${fps} fps"
        val resDisplay = RESOLUTION_LABELS[resolution] ?: "${resolution}p"
        items.add(SettingsItem.ValueDisplay(
            id = "feed_stream_quality",
            label = "Stream Quality",
            value = "$resDisplay, $fpsDisplay"
        ))

        // Actions section
        items.add(SettingsItem.SectionHeader(
            id = "header_actions",
            title = ""
        ))

        items.add(SettingsItem.Action(
            id = "delete_feed",
            label = "Delete Feed"
        ))

        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        when (item) {
            is SettingsItem.Checkmark -> {
                val mode = item.id.removePrefix("mode_")
                prefs.setFeedMode(feedId, mode)
                onModeChanged()
                refreshItems()
            }
            is SettingsItem.Action -> {
                when (item.id) {
                    "edit_feed" -> onEdit(feedId)
                    "delete_feed" -> showDeleteConfirmation()
                }
            }
            else -> {}
        }
    }

    private fun showDeleteConfirmation() {
        val activity = requireActivity()
        val dialogView = layoutInflater.inflate(
            com.dashieapp.Dashie.R.layout.dialog_confirm, null
        )

        dialogView.findViewById<android.widget.TextView>(
            com.dashieapp.Dashie.R.id.dialogTitle
        ).text = "Delete Feed"
        dialogView.findViewById<android.widget.TextView>(
            com.dashieapp.Dashie.R.id.dialogMessage
        ).text = "This will remove the feed. This cannot be undone."

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<android.widget.Button>(
            com.dashieapp.Dashie.R.id.buttonNegative
        ).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }

        dialogView.findViewById<android.widget.Button>(
            com.dashieapp.Dashie.R.id.buttonPositive
        ).apply {
            text = "Delete"
            setOnClickListener {
                dialog.dismiss()
                onDelete(feedId)
            }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    }
}
