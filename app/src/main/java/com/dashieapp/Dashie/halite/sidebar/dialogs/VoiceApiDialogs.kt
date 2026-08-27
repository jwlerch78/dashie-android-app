package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.sidebar.SettingsCallbacks
import com.dashieapp.Dashie.halite.sidebar.SidebarFormatters
import com.dashieapp.Dashie.halite.voice.HaAssistClient

/**
 * Dialogs for voice and API settings including:
 * - API settings (enable, port, password)
 * - Response handling picker
 * - Voice pipeline picker
 */
class VoiceApiDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val webViewProvider: (() -> WebView?)? = null
) {
    private val webView: WebView? get() = webViewProvider?.invoke()

    companion object {
        private const val TAG = "VoiceApiDialogs"
    }

    // Callbacks for actions that need external handling
    var onRestartRequired: ((String) -> Unit)? = null
    var settingsCallbacks: SettingsCallbacks? = null

    /**
     * Show API settings dialog with enable toggle, port, and password
     */
    fun showApiSettingsDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_api_settings, null)

        // Get current settings
        val currentEnabled = halitePrefs.connection.apiEnabled
        val currentPort = halitePrefs.connection.apiPort
        val currentPassword = halitePrefs.connection.apiPassword

        // Initialize views
        val switchApiEnabled = dialogView.findViewById<Switch>(R.id.switchApiEnabled)
        val apiSettingsContainer = dialogView.findViewById<LinearLayout>(R.id.apiSettingsContainer)
        val editApiPort = dialogView.findViewById<EditText>(R.id.editApiPort)
        val editApiPassword = dialogView.findViewById<EditText>(R.id.editApiPassword)
        val buttonToggleVisibility = dialogView.findViewById<TextView>(R.id.buttonTogglePasswordVisibility)

        // Set initial values
        switchApiEnabled.isChecked = currentEnabled
        apiSettingsContainer.visibility = if (currentEnabled) View.VISIBLE else View.GONE
        editApiPort.setText(currentPort.toString())
        editApiPort.setSelection(editApiPort.text.length)
        editApiPassword.setText(currentPassword)

        // Toggle API settings visibility when switch changes
        switchApiEnabled.setOnCheckedChangeListener { _, isChecked ->
            apiSettingsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Password visibility toggle
        var isPasswordVisible = false
        buttonToggleVisibility.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                editApiPassword.inputType = InputType.TYPE_CLASS_TEXT
                buttonToggleVisibility.text = "Hide"
            } else {
                editApiPassword.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                buttonToggleVisibility.text = "Show"
            }
            editApiPassword.setSelection(editApiPassword.text.length)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Cancel button
        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        // Save button
        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            val newEnabled = switchApiEnabled.isChecked
            val newPortText = editApiPort.text.toString()
            val newPassword = editApiPassword.text.toString()

            // Validate port number
            val newPort = newPortText.toIntOrNull()
            if (newEnabled && (newPort == null || newPort < 1024 || newPort > 65535)) {
                Toast.makeText(activity, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Save settings
            val oldEnabled = halitePrefs.connection.apiEnabled
            val oldPort = halitePrefs.connection.apiPort
            Log.i(TAG, "🔧 API settings before save: oldEnabled=$oldEnabled, oldPort=$oldPort")
            Log.i(TAG, "🔧 API settings to save: newEnabled=$newEnabled, newPort=$newPort, newPassword=${if (newPassword.isEmpty()) "none" else "****"}")

            // Save settings first (permission check needs apiEnabled to be true)
            halitePrefs.connection.apiEnabled = newEnabled
            if (newPort != null) {
                halitePrefs.connection.apiPort = newPort
                Log.i(TAG, "🔧 Saved port to preferences: $newPort")
            }
            halitePrefs.connection.apiPassword = newPassword

            // If enabling API, check permissions (after saving so check can read enabled state)
            if (!oldEnabled && newEnabled) {
                Log.i(TAG, "🔧 Enabling API - checking permissions")
                settingsCallbacks?.onApiPermissionsCheck()
            }

            // Verify what was saved
            val verifyPort = halitePrefs.connection.apiPort
            Log.i(TAG, "🔧 Verified port from preferences: $verifyPort")

            // (Legacy sidebar-panel status row deleted 2026-07-19 — no display refresh here.)

            // Notify callback if API enabled state or port changed
            if (oldEnabled != newEnabled || oldPort != (newPort ?: currentPort)) {
                settingsCallbacks?.onApiEnabledChanged(newEnabled)
                Log.i(TAG, "🔧 Invoking onApiEnabledChanged callback with enabled=$newEnabled")
            } else {
                Log.i(TAG, "🔧 No change detected, not invoking callback")
            }

            Toast.makeText(activity, "API settings saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Show voice pipeline picker dialog.
     * Fetches available pipelines from Home Assistant via WebSocket.
     */
    fun showVoicePipelinePicker(textView: TextView?, onPipelineSet: (id: String, name: String) -> Unit) {
        // iOS-style grouped-list picker matching the music player speaker picker.
        // Top cell: "Preferred" (HA default). Below that: one cell per available
        // pipeline. Tapping a cell auto-applies + dismisses (no Save button needed
        // for single-select). Layout is built programmatically so it can re-render
        // when pipelines finish loading asynchronously.

        // Theme colors (dark-mode aware)
        val isDark = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dialogBgColor = if (isDark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
        val cellBgColor = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        val cellPressedColor = if (isDark) 0xFF3A3A3C.toInt() else 0xFFE5E5EA.toInt()
        val labelColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val secondaryColor = 0xFF8E8E93.toInt()
        val separatorColor = if (isDark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
        val accentColor = com.dashieapp.Dashie.halite.music.MusicPlayerStyles.ACCENT_COLOR
        val cornerRadius = dp(10).toFloat()

        // Outer layout (rounded background, vertical)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(dialogBgColor)
                this.cornerRadius = dp(16).toFloat()
            }
            setPadding(0, dp(24), 0, dp(16))
        }

        // Title
        outer.addView(TextView(activity).apply {
            text = "Assist Pipeline"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(labelColor)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        // Scrollable body
        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        scroll.addView(body)
        outer.addView(scroll)

        // Create dialog early so cells can dismiss it
        val dialog = AlertDialog.Builder(activity)
            .setView(outer)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Explicitly add dim scrim — when opened from the Control Center overlay the
        // default theme scrim doesn't always show through, making the settings
        // underneath blend visually with the dialog.
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.6f)

        // Helpers (inner functions capture theme + dialog)
        fun makeCell(
            label: String,
            sublabel: String?,
            isSelected: Boolean,
            isFirst: Boolean, isLast: Boolean, isSingle: Boolean,
            onClick: () -> Unit
        ): LinearLayout {
            val cell = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = cellBackground(
                    cellBgColor, cellPressedColor, accentColor,
                    isFirst, isLast, isSingle, cornerRadius
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            // Label column (vertical stack: title + optional sublabel)
            val textColumn = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            textColumn.addView(TextView(activity).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(labelColor)
            })
            if (!sublabel.isNullOrEmpty()) {
                textColumn.addView(TextView(activity).apply {
                    text = sublabel
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(secondaryColor)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            cell.addView(textColumn)
            if (isSelected) {
                cell.addView(TextView(activity).apply {
                    text = "✓"  // ✓
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                    setTextColor(accentColor)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(dp(4), 0, 0, 0)
                })
            }
            return cell
        }

        fun makeSeparator(): View = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { marginStart = dp(16) }
            setBackgroundColor(separatorColor)
        }

        fun makeGroupContainer(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
        }

        // Re-renders the body — called after pipelines finish loading.
        // Preserved from the loading placeholder to final list.
        fun renderPipelineList(pipelines: List<HaAssistClient.Pipeline>, errorText: String?) {
            body.removeAllViews()

            // Section header
            body.addView(TextView(activity).apply {
                text = "DEFAULT"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(secondaryColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                letterSpacing = 0.02f
                setPadding(dp(16), dp(14), 0, dp(6))
            })

            val preferred = pipelines.find { it.isPreferred }
            if (preferred != null) {
                halitePrefs.voice.preferredPipelineCachedName = preferred.name
            }
            val preferredSublabel = preferred?.let { "Currently: ${it.name}" }
                ?: halitePrefs.voice.preferredPipelineCachedName.takeIf { it.isNotEmpty() }
                    ?.let { "Currently: $it" }

            val preferredGroup = makeGroupContainer()
            preferredGroup.addView(makeCell(
                label = "Preferred",
                sublabel = preferredSublabel,
                isSelected = !halitePrefs.voice.hasCustomPipeline,
                isFirst = true, isLast = true, isSingle = true
            ) {
                halitePrefs.voice.voicePipelineId = ""
                halitePrefs.voice.voicePipelineName = ""
                textView?.text = "Preferred"
                onPipelineSet("", "Preferred")
                Log.i(TAG, "🎙️ Voice pipeline = Preferred (HA default)")
                dialog.dismiss()
            })
            body.addView(preferredGroup)

            // Remaining pipelines (or error message)
            if (errorText != null) {
                body.addView(TextView(activity).apply {
                    text = errorText
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(secondaryColor)
                    setPadding(dp(16), dp(16), dp(16), 0)
                })
                return
            }
            if (pipelines.isEmpty()) {
                body.addView(TextView(activity).apply {
                    text = "Loading pipelines…"
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(secondaryColor)
                    setPadding(dp(16), dp(16), dp(16), 0)
                })
                return
            }

            body.addView(TextView(activity).apply {
                text = "ALL PIPELINES"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(secondaryColor)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                letterSpacing = 0.02f
                setPadding(dp(16), dp(14), 0, dp(6))
            })

            val group = makeGroupContainer()
            pipelines.forEachIndexed { index, pipeline ->
                val isFirst = index == 0
                val isLast = index == pipelines.lastIndex
                val isSingle = pipelines.size == 1
                val description = buildPipelineDescription(pipeline).ifEmpty { null }
                val isSelected = halitePrefs.voice.voicePipelineId == pipeline.id
                group.addView(makeCell(
                    label = pipeline.name,
                    sublabel = description,
                    isSelected = isSelected,
                    isFirst = isFirst, isLast = isLast, isSingle = isSingle
                ) {
                    halitePrefs.voice.voicePipelineId = pipeline.id
                    halitePrefs.voice.voicePipelineName = pipeline.name
                    textView?.text = pipeline.name
                    onPipelineSet(pipeline.id, pipeline.name)
                    Log.i(TAG, "🎙️ Voice pipeline = ${pipeline.name} (${pipeline.id})")
                    dialog.dismiss()
                })
                if (!isLast) group.addView(makeSeparator())
            }
            body.addView(group)
        }

        // Initial render: Preferred + "Loading pipelines..." placeholder
        renderPipelineList(emptyList(), null)

        // Cap scroll area so footer (none here, but safe for short screens) stays visible
        scroll.post {
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.70).toInt()
            if (scroll.height > maxH) {
                scroll.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, maxH
                )
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)

        // Fetch pipelines asynchronously and re-render on completion
        fetchPipelinesFromHa(
            onSuccess = { pipelines ->
                activity.runOnUiThread { renderPipelineList(pipelines, null) }
            },
            onError = { error ->
                activity.runOnUiThread { renderPipelineList(emptyList(), error) }
            }
        )
    }

    /** Builds a StateListDrawable for iOS-style cells with default / pressed / focused states. */
    private fun cellBackground(
        cellColor: Int, pressedColor: Int, focusedStroke: Int,
        isFirst: Boolean, isLast: Boolean, isSingle: Boolean, radius: Float
    ): android.graphics.drawable.StateListDrawable {
        val bg = android.graphics.drawable.StateListDrawable()
        bg.addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
            setColor(pressedColor); applyCornerRadii(isFirst, isLast, isSingle, radius)
        })
        bg.addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
            setColor(pressedColor); setStroke(dp(2), focusedStroke)
            applyCornerRadii(isFirst, isLast, isSingle, radius)
        })
        bg.addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply {
            setColor(cellColor); applyCornerRadii(isFirst, isLast, isSingle, radius)
        })
        return bg
    }

    private fun android.graphics.drawable.GradientDrawable.applyCornerRadii(
        isFirst: Boolean, isLast: Boolean, isSingle: Boolean, radius: Float
    ) {
        cornerRadii = when {
            isSingle -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            isFirst -> floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            isLast -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    private fun dp(value: Int): Int = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
        activity.resources.displayMetrics
    ).toInt()

    /**
     * Build a description string for a pipeline showing its engines.
     */
    fun buildPipelineDescription(pipeline: HaAssistClient.Pipeline): String {
        val parts = mutableListOf<String>()

        // Extract just the engine name from full paths like "stt.faster_whisper"
        pipeline.sttEngine?.let {
            val name = it.substringAfterLast(".")
            parts.add("STT: $name")
        }
        pipeline.conversationEngine?.let {
            val name = it.substringAfterLast(".")
            parts.add("AI: $name")
        }
        pipeline.ttsEngine?.let {
            val name = it.substringAfterLast(".")
            parts.add("TTS: $name")
        }

        return if (parts.isNotEmpty()) parts.joinToString(" • ") else ""
    }

    /**
     * Fetch pipelines from Home Assistant via WebSocket.
     */
    fun fetchPipelinesFromHa(
        onSuccess: (List<HaAssistClient.Pipeline>) -> Unit,
        onError: (String) -> Unit
    ) {
        // First ensure we have a valid token
        HaTokenExtractor.ensureToken(webView, halitePrefs) { hasToken ->
            if (!hasToken) {
                onError("Not logged in to Home Assistant.\nOpen your dashboard first to log in.")
                return@ensureToken
            }

            val haUrl = halitePrefs.connection.buildFullUrl().ifEmpty { halitePrefs.connection.haUrl }
            val accessToken = halitePrefs.connection.haAccessToken

            if (accessToken.isEmpty()) {
                onError("No access token available.\nOpen your dashboard to log in.")
                return@ensureToken
            }

            // Create a temporary HaAssistClient to fetch pipelines
            val client = HaAssistClient(haUrl, accessToken)

            client.onPipelinesReceived = { pipelines ->
                client.disconnect()
                onSuccess(pipelines)
            }

            client.onError = { error ->
                client.disconnect()
                onError("Failed to fetch pipelines:\n$error")
            }

            client.onAuthFailed = { error ->
                client.disconnect()
                onError("Authentication failed:\n$error\n\nTry reopening the dashboard.")
            }

            client.onAuthSuccess = {
                // Now that we're authenticated, request the pipeline list
                client.requestPipelineList()
            }

            // Set a timeout for the whole operation
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (client.isReady()) {
                    // If still connected after timeout, something went wrong
                    client.disconnect()
                    onError("Request timed out.\nCheck your Home Assistant connection.")
                }
            }, 10000) // 10 second timeout

            client.connect()
        }
    }
}
