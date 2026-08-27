package com.dashieapp.Dashie.halite.settings.fragments

import com.dashieapp.Dashie.halite.settings.pages.syncVideoFeedConfigToWebView
import android.media.MediaPlayer
import android.text.InputType
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import org.json.JSONObject
import java.util.UUID

/**
 * Fragment for adding or editing a video feed rule.
 *
 * Fields:
 * - Source Type (entity, rtsp, go2rtc)
 * - Camera Entity (entity mode) or Stream URL (rtsp/go2rtc mode)
 * - Stream Name
 * - Trigger Entity
 * - Play Sound on Trigger
 * - Trigger Sound (when sound enabled)
 * - Delete (edit mode only)
 */
class VideoFeedEditorFragment : BaseSettingsFragment() {

    companion object {
        fun create(
            feedId: String?,
            onSync: () -> Unit,
            onSave: () -> Unit
        ): VideoFeedEditorFragment {
            return VideoFeedEditorFragment().apply {
                this.editingFeedId = feedId
                this.onSync = onSync
                this.onSave = onSave
            }
        }

        private val SOURCE_TYPE_OPTIONS = listOf(
            "entity" to "HA Camera Entity",
            "rtsp" to "RTSP URL",
            "go2rtc" to "go2rtc Stream"
        )

        private val FPS_OPTIONS = listOf(
            "5" to "5 fps",
            "10" to "10 fps",
            "15" to "15 fps",
            "20" to "20 fps",
            "0" to "Native"
        )

        private val RESOLUTION_OPTIONS = listOf(
            "320" to "320p",
            "480" to "480p",
            "640" to "640p",
            "720" to "720p",
            "0" to "Native"
        )

        private val TRIGGER_SOUNDS = listOf(
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

    private var editingFeedId: String? = null
    private var onSave: () -> Unit = {}
    private var onSync: () -> Unit = {}

    // Draft state — only loaded from prefs on the first onViewCreated.
    // Subsequent calls (after returning from a picker sub-screen) keep the
    // in-memory values so picker selections are not clobbered.
    private var draftLoaded = false
    private var navigatingToSubScreen = false
    private var draftSourceType = "entity"
    private var draftCameraEntityId = ""
    private var draftCameraFriendlyName = ""
    private var draftStreamUrl = ""
    private var draftName = ""
    private var draftTriggerEntityId = ""
    private var draftTriggerFriendlyName = ""
    private var draftTriggerState = "on"
    private var draftPlaySound = false
    private var draftTriggerSound = "extra_wood_knock"
    private var draftFps = 10
    private var draftResolution = 480
    private var draftSubscriptionMode = "subscribed"
    private var draftQuality = 8
    // Frigate camera override:
    //   ""          → auto-detect (use draftFrigateAutoDetectedName for display)
    //   "<name>"    → user-selected camera
    //   "__none__"  → user opted out
    private var draftFrigateCameraOverride = ""
    // Last-known auto-detected Frigate camera name from HA sync. Used to
    // show "auto-detected" hint and to label the auto option in the picker.
    private var draftFrigateAutoDetectedName = ""

    private val prefs: VideoFeedPreferences by lazy {
        VideoFeedPreferences(requireContext())
    }

    private val isEditing: Boolean
        get() = editingFeedId != null

    override val title: String
        get() = if (isEditing) "Edit Feed" else "Add Feed"

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        // Clear sub-screen flag — we're back from the picker
        navigatingToSubScreen = false

        // Only load from prefs on the first view creation. When returning from a
        // picker sub-screen the fragment view is recreated (replace+backstack) but
        // the instance is the same — skip the prefs-load so in-memory draft changes
        // (camera, fps, resolution, etc.) are not overwritten.
        if (!draftLoaded) {
            draftLoaded = true
            feedSaved = false  // Reset for new editor session
            if (isEditing) {
                val rule = prefs.getRules().find {
                    it.optString("id", it.optString("ruleId", "")) == editingFeedId
                }
                if (rule != null) {
                    draftSourceType = rule.optString("streamSourceType", "entity")
                    draftCameraEntityId = rule.optString("cameraEntityId", "")
                    draftCameraFriendlyName = rule.optString("cameraName", "")
                    draftStreamUrl = rule.optString("streamSourceUrl", "")
                    draftName = rule.optString("name", rule.optString("cameraName", ""))
                    draftTriggerEntityId = rule.optString("triggerEntityId", "")
                    draftTriggerState = rule.optString("triggerState", "on")
                    draftPlaySound = rule.optBoolean("playSoundOnTrigger", false)
                    draftTriggerSound = rule.optString("triggerSound", "extra_wood_knock")
                    draftFps = rule.optInt("fps", 10)
                    draftResolution = rule.optInt("resolution", 480)
                    draftSubscriptionMode = rule.optString("subscriptionMode", "subscribed")
                    draftQuality = rule.optInt("quality", 8)
                    draftFrigateCameraOverride = rule.optString("frigateCameraOverride", "")
                    draftFrigateAutoDetectedName = rule.optString("frigateCameraName", "")
                }
            }
        }
        super.onViewCreated(view, savedInstanceState)

        // Override "Done" button to navigate back (save + return to feed list)
        // instead of closing the entire settings activity. Must be after super
        // since BaseSettingsFragment.setupNavBar() sets the default click listener.
        view.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.btnClose)
            ?.setOnClickListener { navigateBack() }
    }

    override fun getItems(): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()

        // Stream Details section
        items.add(SettingsItem.SectionHeader(
            id = "header_stream",
            title = "Stream Details"
        ))

        // Source Type
        val sourceLabel = SOURCE_TYPE_OPTIONS.find { it.first == draftSourceType }?.second ?: "HA Camera Entity"
        items.add(SettingsItem.Picker(
            id = "source_type",
            label = "Source Type",
            selectedValue = draftSourceType,
            displayValue = sourceLabel,
            options = SOURCE_TYPE_OPTIONS.map {
                com.dashieapp.Dashie.halite.settings.items.PickerOption(it.first, it.second)
            }
        ))

        // Camera Entity or Stream URL based on source type
        if (draftSourceType == "entity") {
            val cameraDisplay = when {
                draftCameraFriendlyName.isNotEmpty() -> draftCameraFriendlyName
                draftCameraEntityId.isNotEmpty() -> draftCameraEntityId.removePrefix("camera.")
                else -> "Select camera..."
            }
            val cameraSublabel = if (draftCameraEntityId.isNotEmpty()) draftCameraEntityId else null
            items.add(SettingsItem.Navigation(
                id = "camera_entity",
                label = "Camera Entity",
                value = cameraDisplay,
                sublabel = cameraSublabel
            ))

            // Frigate camera (override or auto-detected). Only meaningful for
            // entity-sourced feeds with a camera selected; hidden otherwise.
            if (draftCameraEntityId.isNotEmpty()) {
                val (frigateValue, frigateSub) = when (draftFrigateCameraOverride) {
                    "__none__" -> "None" to null
                    "" -> {
                        if (draftFrigateAutoDetectedName.isNotEmpty())
                            draftFrigateAutoDetectedName to "auto-detected"
                        else
                            "None" to null
                    }
                    else -> draftFrigateCameraOverride to null
                }
                items.add(SettingsItem.Navigation(
                    id = "frigate_camera",
                    label = "Frigate Camera",
                    value = frigateValue,
                    sublabel = frigateSub
                ))
            }
        } else {
            items.add(SettingsItem.TextInput(
                id = "stream_url",
                label = if (draftSourceType == "go2rtc") "go2rtc Stream" else "RTSP URL",
                value = draftStreamUrl,
                placeholder = if (draftSourceType == "go2rtc") "stream_name" else "rtsp://...",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            ))
        }

        // FPS
        val fpsLabel = FPS_OPTIONS.find { it.first == draftFps.toString() }?.second ?: "10 fps"
        items.add(SettingsItem.Picker(
            id = "fps",
            label = "Frame Rate",
            selectedValue = draftFps.toString(),
            displayValue = fpsLabel,
            options = FPS_OPTIONS.map {
                com.dashieapp.Dashie.halite.settings.items.PickerOption(it.first, it.second)
            }
        ))

        // Resolution
        val resLabel = RESOLUTION_OPTIONS.find { it.first == draftResolution.toString() }?.second ?: "480p"
        items.add(SettingsItem.Picker(
            id = "resolution",
            label = "Resolution",
            selectedValue = draftResolution.toString(),
            displayValue = resLabel,
            options = RESOLUTION_OPTIONS.map {
                com.dashieapp.Dashie.halite.settings.items.PickerOption(it.first, it.second)
            }
        ))

        // Stream Name
        items.add(SettingsItem.TextInput(
            id = "stream_name",
            label = "Stream Name",
            value = draftName,
            placeholder = "e.g. Front Door"
        ))

        // Trigger section
        items.add(SettingsItem.SectionHeader(
            id = "header_trigger",
            title = "Trigger"
        ))

        val triggerDisplay = when {
            draftTriggerFriendlyName.isNotEmpty() -> draftTriggerFriendlyName
            draftTriggerEntityId.isNotEmpty() ->
                draftTriggerEntityId.removePrefix("binary_sensor.").removePrefix("input_boolean.")
            else -> "Select trigger..."
        }
        val triggerSublabel = if (draftTriggerEntityId.isNotEmpty()) draftTriggerEntityId else null
        items.add(SettingsItem.Navigation(
            id = "trigger_entity",
            label = "Trigger Entity",
            value = triggerDisplay,
            sublabel = triggerSublabel
        ))

        items.add(SettingsItem.Toggle(
            id = "play_sound",
            label = "Play Sound on Trigger",
            isChecked = draftPlaySound
        ))

        if (draftPlaySound) {
            val soundLabel = TRIGGER_SOUNDS.find { it.first == draftTriggerSound }?.second ?: draftTriggerSound
            items.add(SettingsItem.Picker(
                id = "trigger_sound",
                label = "Trigger Sound",
                selectedValue = draftTriggerSound,
                displayValue = soundLabel,
                options = TRIGGER_SOUNDS.map {
                    com.dashieapp.Dashie.halite.settings.items.PickerOption(it.first, it.second)
                }
            ))
        }

        // Delete button (edit mode only)
        if (isEditing) {
            items.add(SettingsItem.SectionHeader(
                id = "header_actions",
                title = ""
            ))

            items.add(SettingsItem.Action(
                id = "delete_feed",
                label = "Delete Feed"
            ))
        }

        return items
    }

    override fun handleItemClick(item: SettingsItem) {
        when (item) {
            is SettingsItem.Navigation -> {
                when (item.id) {
                    "camera_entity" -> showEntityPicker("camera")
                    "trigger_entity" -> showEntityPicker("trigger")
                    "frigate_camera" -> showFrigateCameraPicker()
                }
            }
            is SettingsItem.Picker -> {
                when (item.id) {
                    "source_type" -> {
                        // Handled by the picker sub-screen system
                        showPickerDialog(
                            "Source Type",
                            SOURCE_TYPE_OPTIONS.map { it.first to it.second },
                            draftSourceType
                        ) { value ->
                            draftSourceType = value
                            if (value == "entity") draftStreamUrl = ""
                            else draftCameraEntityId = ""
                            refreshItems()
                        }
                    }
                    "fps" -> {
                        showPickerDialog(
                            "Frame Rate",
                            FPS_OPTIONS.map { it.first to it.second },
                            draftFps.toString()
                        ) { value ->
                            draftFps = value.toIntOrNull() ?: 10
                            refreshItems()
                        }
                    }
                    "resolution" -> {
                        showPickerDialog(
                            "Resolution",
                            RESOLUTION_OPTIONS.map { it.first to it.second },
                            draftResolution.toString()
                        ) { value ->
                            draftResolution = value.toIntOrNull() ?: 480
                            refreshItems()
                        }
                    }
                    "trigger_sound" -> {
                        showSoundPickerDialog()
                    }
                }
            }
            is SettingsItem.Action -> {
                when (item.id) {
                    "delete_feed" -> showDeleteConfirmation()
                }
            }
            else -> {}
        }
    }

    override fun handleToggleChange(item: SettingsItem.Toggle, newValue: Boolean) {
        when (item.id) {
            "play_sound" -> {
                draftPlaySound = newValue
                refreshItems()
            }
        }
    }

    private var feedSaved = false

    override fun navigateBack() {
        saveFeed()
    }

    override fun handleTextChange(item: SettingsItem.TextInput, newValue: String) {
        when (item.id) {
            "stream_url" -> draftStreamUrl = newValue
            "stream_name" -> draftName = newValue
        }
    }

    /**
     * @param skipNavigation If true, save the data but don't call onSave() (which
     *   pops the back stack). Used when the activity is finishing/destroying.
     */
    private fun saveFeed(skipNavigation: Boolean = false) {
        if (feedSaved) return
        feedSaved = true

        // Capture any focused text inputs before reading draft values
        captureActiveTextInputs()

        // Don't save if no camera source is configured
        val hasSource = when (draftSourceType) {
            "entity" -> draftCameraEntityId.isNotEmpty()
            else -> draftStreamUrl.isNotEmpty()
        }
        if (!hasSource && !isEditing) {
            // Cancel — just pop back without saving
            if (!skipNavigation) onSave()
            return
        }

        // Auto-generate name if empty
        if (draftName.isEmpty()) {
            draftName = when {
                draftCameraEntityId.isNotEmpty() -> {
                    val derived = draftCameraEntityId.removePrefix("camera.").replace('_', ' ')
                    derived.replaceFirstChar { it.uppercase() }
                }
                draftStreamUrl.isNotEmpty() -> {
                    if (draftSourceType == "go2rtc") draftStreamUrl else "RTSP Stream"
                }
                else -> "Feed"
            }
        }

        val ruleId = editingFeedId ?: UUID.randomUUID().toString()
        val rule = JSONObject().apply {
            put("id", ruleId)
            put("name", draftName)
            put("cameraEntityId", draftCameraEntityId)
            put("cameraName", draftName)
            put("triggerEntityId", draftTriggerEntityId)
            put("triggerState", draftTriggerState)
            put("streamSourceType", draftSourceType)
            put("streamSourceUrl", draftStreamUrl)
            put("playSoundOnTrigger", draftPlaySound)
            put("triggerSound", draftTriggerSound)
            put("autoDismissSeconds", 30)
            put("continueWhileActive", true)
            put("fps", draftFps)
            put("resolution", draftResolution)
            put("quality", draftQuality)
            put("subscriptionMode", draftSubscriptionMode)
            put("enabled", draftSubscriptionMode != "ignored")
            put("frigateCameraOverride", draftFrigateCameraOverride)
        }

        prefs.saveRule(rule)
        onSync()
        if (!skipNavigation) onSave()
    }

    /**
     * Force-capture text from any focused EditText before save.
     * Clearing focus triggers the onFocusChange listener which updates draft values.
     */
    private fun captureActiveTextInputs() {
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val editText = child.findViewById<android.widget.EditText>(R.id.editText) ?: continue
            if (editText.hasFocus()) {
                editText.clearFocus()
            }
        }
    }

    private fun showEntityPicker(domain: String) {
        val fragment = EntityPickerFragment.create(
            domain = domain,
            currentValue = if (domain == "camera") draftCameraEntityId else draftTriggerEntityId,
            cameraEntityId = if (domain == "trigger") draftCameraEntityId else null,
            onSelect = { entityId, friendlyName ->
                if (domain == "camera") {
                    draftCameraEntityId = entityId
                    draftCameraFriendlyName = friendlyName
                    if (draftName.isEmpty()) draftName = friendlyName
                } else {
                    draftTriggerEntityId = entityId
                    draftTriggerFriendlyName = friendlyName
                }
                refreshItems()
            }
        )
        navigatingToSubScreen = true
        navigateTo(fragment, "entity_picker_$domain")
    }

    /**
     * Show a picker dialog with checkmark items.
     */
    private fun showPickerDialog(
        title: String,
        options: List<Pair<String, String>>,
        currentValue: String,
        onSelect: (String) -> Unit
    ) {
        val fragment = PickerDialogFragment.create(
            title = title,
            options = options,
            currentValue = currentValue,
            onSelect = onSelect
        )
        navigatingToSubScreen = true
        (requireActivity() as? com.dashieapp.Dashie.halite.settings.SettingsActivity)
            ?.showFragment(fragment, "picker_${title.lowercase().replace(' ', '_')}")
    }

    /**
     * Fetch the live Frigate camera list from HA, then open a picker so the
     * user can override which Frigate camera this feed maps to (or opt out).
     *
     * Stored values:
     *   ""          → auto-detect (HA-side matcher decides)
     *   "<name>"    → explicit camera
     *   "__none__"  → opt out, never treat as Frigate
     *
     * The auto-detected camera (if any) is shown as a regular option with
     * an "auto-detected" sublabel; selecting it stores "" so future Frigate
     * config changes still flow through the auto-matcher.
     */
    private fun showFrigateCameraPicker() {
        FrigateCameraFetcher.fetch(requireContext()) { cameras ->
            if (cameras == null || cameras.isEmpty()) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "Frigate not reachable — no cameras to choose from",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@fetch
            }

            val options = mutableListOf<Pair<String, String>>()
            for (cam in cameras) options.add(cam to cam)
            options.add("__none__" to "None")

            val autoName = draftFrigateAutoDetectedName
            val subLabelFor: (String) -> String? = { value ->
                if (value == autoName && autoName.isNotEmpty() && draftFrigateCameraOverride.isEmpty())
                    "auto-detected"
                else null
            }

            // Currently-selected option in the dropdown:
            //   override == "__none__" → "__none__"
            //   override == ""         → autoName (so the auto row appears selected)
            //   override == "<name>"   → that name
            val currentOptionValue = when {
                draftFrigateCameraOverride == "__none__" -> "__none__"
                draftFrigateCameraOverride.isNotEmpty() -> draftFrigateCameraOverride
                else -> autoName  // may be "" if no auto-match — nothing selected
            }

            val fragment = PickerDialogFragment.create(
                title = "Frigate Camera",
                options = options,
                currentValue = currentOptionValue,
                subLabelFor = subLabelFor,
                onSelect = { selected ->
                    draftFrigateCameraOverride = when (selected) {
                        autoName -> ""              // picked the auto row → store empty
                        "__none__" -> "__none__"
                        else -> selected
                    }
                    refreshItems()
                }
            )
            navigatingToSubScreen = true
            (requireActivity() as? com.dashieapp.Dashie.halite.settings.SettingsActivity)
                ?.showFragment(fragment, "picker_frigate_camera")
        }
    }

    private fun showSoundPickerDialog() {
        val fragment = PickerDialogFragment.create(
            title = "Trigger Sound",
            options = TRIGGER_SOUNDS,
            currentValue = draftTriggerSound,
            onSelect = { value ->
                draftTriggerSound = value
                refreshItems()
            },
            onPreview = { soundName -> previewSound(soundName) }
        )
        navigatingToSubScreen = true
        (requireActivity() as? com.dashieapp.Dashie.halite.settings.SettingsActivity)
            ?.showFragment(fragment, "picker_trigger_sound")
    }

    private var mediaPlayer: MediaPlayer? = null

    private fun previewSound(soundName: String) {
        mediaPlayer?.release()
        val resId = resources.getIdentifier(soundName, "raw", requireContext().packageName)
        if (resId == 0) return
        mediaPlayer = MediaPlayer.create(requireContext(), resId)?.apply {
            setOnCompletionListener { it.release() }
            start()
        }
    }

    override fun onDestroyView() {
        // Save feed when view is destroyed (covers "Done" button closing settings
        // overlay without triggering navigateBack).
        // - Skip entirely when navigating to a sub-screen (entity picker, sound picker)
        //   — saveFeed() with no camera would pop the back stack, destroying the picker.
        // - When activity is finishing/destroyed, save data but skip navigation
        //   — FragmentManager is already executing transactions, popBackStackImmediate() would crash.
        if (!navigatingToSubScreen) {
            val activityFinishing = activity?.isFinishing == true || activity?.isDestroyed == true
            saveFeed(skipNavigation = activityFinishing)
        }
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroyView()
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
                editingFeedId?.let { id ->
                    prefs.deleteRule(id)
                    val sa = requireActivity() as? com.dashieapp.Dashie.halite.settings.SettingsActivity
                    sa?.syncVideoFeedConfigToWebView()
                    requireActivity().sendBroadcast(
                        android.content.Intent("com.dashieapp.Dashie.ACTION_VIDEO_FEED_CONFIG_CHANGED").apply {
                            setPackage(requireContext().packageName)
                        }
                    )
                    sa?.popBackStackDirect()
                    sa?.popBackStackDirect()
                    sa?.supportFragmentManager?.fragments
                        ?.filterIsInstance<com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>()
                        ?.firstOrNull()?.refresh()
                }
            }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    }
}

/**
 * Simple picker fragment that shows a checkmark list.
 * Used by the feed editor for source type, trigger sound, and entity selection.
 */
class PickerDialogFragment : BaseSettingsFragment() {

    companion object {
        fun create(
            title: String,
            options: List<Pair<String, String>>,
            currentValue: String,
            onSelect: (String) -> Unit,
            onPreview: ((String) -> Unit)? = null,
            subLabelFor: ((String) -> String?)? = null
        ): PickerDialogFragment {
            return PickerDialogFragment().apply {
                this.pickerTitle = title
                this.options = options
                this.currentValue = currentValue
                this.onSelect = onSelect
                this.onPreview = onPreview
                this.subLabelFor = subLabelFor
            }
        }
    }

    private lateinit var pickerTitle: String
    private lateinit var options: List<Pair<String, String>>
    private lateinit var currentValue: String
    private var onSelect: (String) -> Unit = {}
    private var onPreview: ((String) -> Unit)? = null
    private var subLabelFor: ((String) -> String?)? = null

    override val title: String
        get() = pickerTitle

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Recreate adapter with preview support if needed
        if (onPreview != null) {
            val newAdapter = com.dashieapp.Dashie.halite.settings.adapters.SettingsAdapter(
                onItemClick = { item -> handleItemClick(item) },
                onToggleChange = { _, _ -> },
                onPreviewClick = { item ->
                    val value = item.id.removePrefix("opt_")
                    onPreview?.invoke(value)
                }
            )
            recyclerView.adapter = newAdapter
            adapter = newAdapter
            loadItems()
        }
    }

    override fun getItems(): List<SettingsItem> {
        val hasPreview = onPreview != null
        val subFn = subLabelFor
        return options.map { (value, label) ->
            SettingsItem.Checkmark(
                id = "opt_$value",
                label = label,
                isChecked = value == currentValue,
                hasPreview = hasPreview,
                sublabel = subFn?.invoke(value)
            )
        }
    }

    override fun handleItemClick(item: SettingsItem) {
        if (item is SettingsItem.Checkmark) {
            val value = item.id.removePrefix("opt_")
            currentValue = value
            onSelect(value)
            refreshItems()
            // Auto-navigate back after selection
            navigateBack()
        }
    }
}
