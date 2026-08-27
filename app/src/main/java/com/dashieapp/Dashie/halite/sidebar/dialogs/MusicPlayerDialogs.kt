package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.SidebarSettingsHelper.Companion.applyImmersiveModeToDialog
import com.dashieapp.Dashie.halite.music.MusicPlayerStyles
import com.dashieapp.Dashie.webview.injectors.MusicPlayerJsInjector
import org.json.JSONArray

/**
 * Handles music player settings dialog for Dashie Kiosk.
 * Allows enabling/disabling music player and selecting media player entity.
 */
class MusicPlayerDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "MusicPlayerDialogs"

        /**
         * Resolve the best display name for a Music Assistant player given
         * the raw fields from MA's JSON response. Centralized so the four
         * different picker code paths (MA-via-HA-API, MA-streams, first-time
         * setup × 2) all use the same fallback chain:
         *   display_name → name → prettified player_id slug
         * Skips the `name` value when it equals the device model (raw model
         * IDs like "rk3576_u" leak through MA in some configs).
         */
        fun resolveMaFriendlyName(
            playerId: String,
            displayName: String,
            name: String,
            model: String = ""
        ): String {
            return when {
                displayName.isNotBlank() && displayName != model -> displayName
                name.isNotBlank() && name != model -> name
                else -> prettifyEntityIdSlug(playerId)
            }
        }

        /**
         * Convert an entity / player id slug (e.g. "media_player.up34xs_master_kitchen"
         * or "up34xs_master_kitchen") into a human-readable label
         * ("Up34xs Master Kitchen").
         */
        fun prettifyEntityIdSlug(rawId: String): String {
            return rawId.removePrefix("media_player.")
                .replace("_", " ")
                .replace(Regex("\\b\\w")) { it.value.uppercase() }
        }
    }

    // Callbacks
    var onMusicPlayerEnabledChanged: ((Boolean) -> Unit)? = null
    var onMusicPlayerEntityChanged: ((String) -> Unit)? = null
    var onDialogDismissed: (() -> Unit)? = null

    // Available media players (populated by querying HA)
    private var availableMediaPlayers: List<MediaPlayerInfo> = emptyList()

    // WebView reference for querying HA (set by caller)
    var webView: WebView? = null

    /** Set to true when HA is in an iframe (shell page mode). Uses postMessage relay for queries. */
    var useShellRelay: Boolean = false

    /**
     * Media player info from Home Assistant
     */
    data class MediaPlayerInfo(
        val entityId: String,
        val friendlyName: String,
        val state: String = "unknown",
        val isGroup: Boolean = false
    )

    /**
     * Show the music player settings dialog
     */
    fun showMusicPlayerSettingsDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_music_player_settings, null)

        // Get current values
        val currentEnabled = halitePrefs.connection.musicPlayerEnabled
        val currentEntityId = halitePrefs.connection.musicPlayerEntityId
        val currentFullScreen = halitePrefs.connection.musicPlayerFullScreenOnPlay
        val effectiveEntityId = halitePrefs.connection.getEffectiveMusicPlayerEntityId()

        // Track pending changes
        var pendingEnabled = currentEnabled
        var pendingEntityId = currentEntityId
        var pendingFullScreen = currentFullScreen

        // Get UI references
        val rowEnabled = dialogView.findViewById<LinearLayout>(R.id.rowEnabled)
        val switchEnabled = dialogView.findViewById<Switch>(R.id.switchEnabled)
        val rowFullScreenOnPlay = dialogView.findViewById<LinearLayout>(R.id.rowFullScreenOnPlay)
        val switchFullScreenOnPlay = dialogView.findViewById<Switch>(R.id.switchFullScreenOnPlay)
        val rowMediaPlayer = dialogView.findViewById<LinearLayout>(R.id.rowMediaPlayer)
        val textMediaPlayerValue = dialogView.findViewById<TextView>(R.id.textMediaPlayerValue)
        val statusContainer = dialogView.findViewById<LinearLayout>(R.id.statusContainer)
        val statusDot = dialogView.findViewById<View>(R.id.statusDot)
        val textStatus = dialogView.findViewById<TextView>(R.id.textStatus)

        // Set initial values
        switchEnabled.isChecked = currentEnabled
        switchFullScreenOnPlay.isChecked = currentFullScreen
        updateMediaPlayerDisplay(textMediaPlayerValue, pendingEntityId, effectiveEntityId)
        updateStatusDisplay(statusDot, textStatus, currentEnabled, effectiveEntityId)

        // Toggle click handler (for D-pad support)
        rowEnabled.setOnClickListener {
            switchEnabled.isChecked = !switchEnabled.isChecked
            pendingEnabled = switchEnabled.isChecked
            updateStatusDisplay(statusDot, textStatus, pendingEnabled,
                if (pendingEntityId.isEmpty()) halitePrefs.connection.getEffectiveMusicPlayerEntityId() else pendingEntityId)
        }

        // Switch change listener
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            pendingEnabled = isChecked
            updateStatusDisplay(statusDot, textStatus, pendingEnabled,
                if (pendingEntityId.isEmpty()) halitePrefs.connection.getEffectiveMusicPlayerEntityId() else pendingEntityId)
        }

        // Full screen on play toggle
        rowFullScreenOnPlay.setOnClickListener {
            switchFullScreenOnPlay.isChecked = !switchFullScreenOnPlay.isChecked
            pendingFullScreen = switchFullScreenOnPlay.isChecked
        }
        switchFullScreenOnPlay.setOnCheckedChangeListener { _, isChecked ->
            pendingFullScreen = isChecked
        }

        // Media player row click handler
        rowMediaPlayer.setOnClickListener {
            showMediaPlayerPicker(pendingEntityId) { selectedEntityId ->
                pendingEntityId = selectedEntityId
                val effective = if (selectedEntityId.isEmpty()) {
                    halitePrefs.connection.getEffectiveMusicPlayerEntityId()
                } else {
                    selectedEntityId
                }
                updateMediaPlayerDisplay(textMediaPlayerValue, pendingEntityId, effective)
                updateStatusDisplay(statusDot, textStatus, pendingEnabled, effective)
            }
        }

        // Create dialog
        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .setOnDismissListener {
                onDialogDismissed?.invoke()
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Cancel button
        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        // Save button
        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            // Save changes
            val enabledChanged = pendingEnabled != currentEnabled
            val entityChanged = pendingEntityId != currentEntityId
            val fullScreenChanged = pendingFullScreen != currentFullScreen

            if (enabledChanged) {
                halitePrefs.connection.musicPlayerEnabled = pendingEnabled
                Log.i(TAG, "🎵 Music player enabled changed to: $pendingEnabled")
                onMusicPlayerEnabledChanged?.invoke(pendingEnabled)
            }

            if (entityChanged) {
                val pickedName = availableMediaPlayers
                    .firstOrNull { it.entityId == pendingEntityId }
                    ?.let { displayName(it) }
                    ?: ""
                halitePrefs.connection.setMusicPlayerEntityAndName(pendingEntityId, pickedName)
                Log.i(TAG, "🎵 Music player entity changed to: $pendingEntityId ($pickedName)")
                onMusicPlayerEntityChanged?.invoke(pendingEntityId)
            }

            if (fullScreenChanged) {
                halitePrefs.connection.musicPlayerFullScreenOnPlay = pendingFullScreen
                Log.i(TAG, "🎵 Music player full screen on play changed to: $pendingFullScreen")
            }

            dialog.dismiss()
        }

        dialog.show()

        // Constrain dialog height to 80% of screen so content scrolls on small screens
        dialogView.post {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.8).toInt()
            if (dialogView.height > maxHeight) {
                dialog.window?.setLayout(
                    dialog.window?.attributes?.width ?: android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    maxHeight
                )
            }
        }

        applyImmersiveModeToDialog(dialog)
    }

    /**
     * Update the media player display text
     */
    private fun updateMediaPlayerDisplay(textView: TextView, explicitId: String, effectiveId: String) {
        if (explicitId.isEmpty()) {
            textView.text = "Not configured"
            textView.setTextColor(activity.getColor(R.color.text_secondary))
        } else {
            val shortName = explicitId.removePrefix("media_player.")
            textView.text = shortName
            textView.setTextColor(activity.getColor(R.color.text_primary))
        }
    }

    /**
     * Update the status display
     */
    private fun updateStatusDisplay(dot: View, text: TextView, enabled: Boolean, entityId: String) {
        val dotBackground = dot.background as? GradientDrawable
        val speakerOnly = halitePrefs.connection.musicSpeakerOnly

        when {
            !enabled -> {
                dotBackground?.setColor(0xFF808080.toInt())  // Gray
                text.text = "Disabled"
                text.setTextColor(activity.getColor(R.color.text_secondary))
            }
            speakerOnly -> {
                dotBackground?.setColor(0xFF34C759.toInt())  // Green
                text.text = "Speaker only"
                text.setTextColor(activity.getColor(R.color.text_primary))
            }
            entityId.isEmpty() -> {
                dotBackground?.setColor(0xFFFF9500.toInt())  // Orange (warning)
                text.text = "No media player configured"
                text.setTextColor(activity.getColor(R.color.text_secondary))
            }
            else -> {
                dotBackground?.setColor(0xFF34C759.toInt())  // Green
                text.text = "Ready"
                text.setTextColor(activity.getColor(R.color.text_primary))
            }
        }
    }

    /**
     * Show media player picker dialog.
     * First queries HA for available media players, then shows picker.
     * Supports both direct query (legacy) and shell relay (iframe architecture).
     */
    private fun showMediaPlayerPicker(currentEntityId: String, onSelected: (String) -> Unit) {
        Log.i(TAG, "🎵 Querying HA for media players... (shellRelay=$useShellRelay)")

        val handleResult = { jsonString: String ->
            Log.i(TAG, "🎵 ========== RAW QUERY RESULT ==========")
            Log.i(TAG, "🎵 JSON length: ${jsonString.length}")
            Log.i(TAG, "🎵 JSON: $jsonString")
            Log.i(TAG, "🎵 ========================================")
            activity.runOnUiThread {
                val players = parseMediaPlayersJson(jsonString)
                Log.i(TAG, "🎵 Parsed ${players.size} players from JSON")
                val filteredPlayers = filterMusicAssistantPlayers(players)
                availableMediaPlayers = filteredPlayers
                showMediaPlayerPickerDialog(currentEntityId, filteredPlayers, onSelected = onSelected)
            }
        }

        if (useShellRelay) {
            MusicPlayerJsInjector.queryMediaPlayersViaShell(webView, handleResult)
        } else {
            MusicPlayerJsInjector.queryMediaPlayers(webView, handleResult)
        }
    }

    /**
     * Parse JSON array of media players from HA query
     */
    private fun parseMediaPlayersJson(jsonString: String): List<MediaPlayerInfo> {
        val players = mutableListOf<MediaPlayerInfo>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                players.add(MediaPlayerInfo(
                    entityId = obj.getString("entityId"),
                    friendlyName = obj.getString("friendlyName"),
                    state = obj.optString("state", "unknown")
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "🎵 Failed to parse media players JSON: $jsonString", e)
        }
        return players
    }

    /**
     * Filter Music Assistant players to prefer _2 suffix (actual speakers).
     * Music Assistant creates two entities per device:
     * - media_player.xxx_speaker - Group player that can target multiple speakers
     * - media_player.xxx_speaker_2 - Actual physical speaker
     *
     * We prefer the _2 version when both exist, but show non-_2 if only that exists.
     * Also filters out generic "speaker" entities that are just group placeholders.
     *
     * Note: We only filter by entity ID, NOT by friendly_name, because Music Assistant
     * sometimes uses generic names like "Speaker" for real device speakers.
     */
    private fun filterMusicAssistantPlayers(players: List<MediaPlayerInfo>): List<MediaPlayerInfo> {
        val entityIds = players.map { it.entityId }.toSet()

        Log.d(TAG, "🎵 Filtering ${players.size} media players...")
        players.forEach { p ->
            Log.d(TAG, "🎵   Input: ${p.entityId} (${p.friendlyName})")
        }

        val filtered = players.filter { player ->
            val entityId = player.entityId

            // Filter out ONLY the generic "media_player.speaker" or "media_player.speakers" entries
            // These are typically Music Assistant group placeholders with no device association
            // Do NOT filter by friendly_name - Music Assistant uses generic names like "Speaker"
            // even for real device speakers
            if (entityId == "media_player.speaker" || entityId == "media_player.speakers") {
                Log.d(TAG, "🎵 Filtering out generic speaker entry: $entityId")
                return@filter false
            }

            // If this ends with _2, always include it
            if (entityId.endsWith("_2")) {
                return@filter true
            }

            // If this doesn't end with _2, only include if the _2 version doesn't exist
            val withSuffix = "${entityId}_2"
            !entityIds.contains(withSuffix)
        }.sortedBy { it.friendlyName.lowercase() }

        Log.i(TAG, "🎵 Filtered to ${filtered.size} players")
        filtered.forEach { p ->
            Log.d(TAG, "🎵   Output: ${p.entityId} (${p.friendlyName})")
        }

        return filtered
    }

    /**
     * Show the picker dialog with filtered players — iOS-style grouped cells with checkmarks.
     * Orders entities: default at top (labeled "DEFAULT"), recently used next, then rest.
     * Tap a speaker to select it and dismiss. Long-press to set as default.
     *
     * @param currentEntityId The entity to pre-select
     * @param players Filtered list of available media players
     * @param recentEntityIds Recently used entity IDs (most recent first)
     * @param onSelected Called with the selected entity ID
     * @param onSetDefault If provided, long-press sets as default
     */
    private fun showMediaPlayerPickerDialog(
        currentEntityId: String,
        players: List<MediaPlayerInfo>,
        recentEntityIds: List<String> = emptyList(),
        onSelected: (String) -> Unit,
        onSetDefault: ((String) -> Unit)? = null
    ) {
        if (players.isEmpty()) {
            Log.w(TAG, "No media players available to pick from")
            return
        }

        val defaultEntityId = halitePrefs.connection.getEffectiveMusicPlayerEntityId()
        val playerMap = players.associateBy { it.entityId }
        val addedIds = mutableSetOf<String>()

        // Build ordered sections: Default, Recent, All Speakers
        data class Section(val title: String?, val items: List<MediaPlayerInfo>)
        val sections = mutableListOf<Section>()

        // 1. Default entity at top
        val defaultPlayer = playerMap[defaultEntityId]
        if (defaultPlayer != null) {
            sections.add(Section("DEFAULT", listOf(defaultPlayer)))
            addedIds.add(defaultEntityId)
        }

        // 2. Recently used (not already added)
        val recentPlayers = recentEntityIds
            .filter { it !in addedIds }
            .mapNotNull { playerMap[it] }
        if (recentPlayers.isNotEmpty()) {
            sections.add(Section("RECENT", recentPlayers))
            recentPlayers.forEach { addedIds.add(it.entityId) }
        }

        // 3. All remaining speakers
        val remaining = players.filter { it.entityId !in addedIds }
        if (remaining.isNotEmpty()) {
            val headerLabel = if (addedIds.isEmpty()) null else "ALL SPEAKERS"
            sections.add(Section(headerLabel, remaining))
        }

        // ── Build programmatic dialog layout ──
        val isDark = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dialogBgColor = if (isDark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
        val cellBgColor = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        val cellPressedColor = if (isDark) 0xFF3A3A3C.toInt() else 0xFFE5E5EA.toInt()
        val labelColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val secondaryColor = if (isDark) 0xFF8E8E93.toInt() else 0xFF8E8E93.toInt()
        val separatorColor = if (isDark) 0xFF38383A.toInt() else 0xFFC6C6C8.toInt()
        val cornerRadius = dp(10).toFloat()
        val accentColor = MusicPlayerStyles.ACCENT_COLOR

        val outerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(dialogBgColor)
                this.cornerRadius = dp(16).toFloat()
            }
            setPadding(0, dp(24), 0, dp(16))
        }

        // Title
        outerLayout.addView(TextView(activity).apply {
            text = "Select Speaker"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(labelColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24), 0, dp(24), dp(8))
        })

        // Scrollable content
        val scrollView = android.widget.ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
        }
        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        scrollView.addView(contentLayout)
        outerLayout.addView(scrollView)

        // Create dialog early so cells can dismiss it
        val dialog = AlertDialog.Builder(activity)
            .setView(outerLayout)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // ── Build sections ──
        for (section in sections) {
            // Section header
            if (section.title != null) {
                contentLayout.addView(TextView(activity).apply {
                    text = section.title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(secondaryColor)
                    typeface = Typeface.DEFAULT_BOLD
                    letterSpacing = 0.02f
                    setPadding(dp(16), dp(14), 0, dp(6))
                })
            }

            // Grouped cell container (rounded corners)
            val groupContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                    }
                }
            }

            val items = section.items
            for ((index, player) in items.withIndex()) {
                val isFirst = index == 0
                val isLast = index == items.lastIndex
                val isSelected = player.entityId == currentEntityId

                // Cell row
                val cellRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(44)
                    setPadding(dp(16), dp(10), dp(16), dp(10))

                    // Background with position-appropriate rounding
                    val bgDrawable = android.graphics.drawable.StateListDrawable()
                    val pressedBg = GradientDrawable().apply {
                        setColor(cellPressedColor)
                        applyCornerRadii(isFirst, isLast, items.size == 1, cornerRadius)
                    }
                    val focusedBg = GradientDrawable().apply {
                        setColor(cellPressedColor)
                        setStroke(dp(2), accentColor)
                        applyCornerRadii(isFirst, isLast, items.size == 1, cornerRadius)
                    }
                    val defaultBg = GradientDrawable().apply {
                        setColor(cellBgColor)
                        applyCornerRadii(isFirst, isLast, items.size == 1, cornerRadius)
                    }
                    bgDrawable.addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                    bgDrawable.addState(intArrayOf(android.R.attr.state_focused), focusedBg)
                    bgDrawable.addState(intArrayOf(), defaultBg)
                    background = bgDrawable

                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = false
                    setOnClickListener {
                        onSelected(player.entityId)
                        dialog.dismiss()
                    }
                }

                // Speaker/group icon (left of name) — matches speaker drawer approach
                val iconView = ImageView(activity).apply {
                    val iconSize = dp(18)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginEnd = dp(12)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(if (player.isGroup) R.drawable.ic_speaker_group else R.drawable.ic_box_speaker)
                    setColorFilter(if (isSelected) accentColor else secondaryColor)
                }
                cellRow.addView(iconView)

                // Speaker name (fills remaining space)
                cellRow.addView(TextView(activity).apply {
                    text = displayName(player)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setTextColor(labelColor)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                // "Set as default" link — shown on the selected speaker if it's not already the default
                if (isSelected && player.entityId != defaultEntityId && onSetDefault != null) {
                    cellRow.addView(TextView(activity).apply {
                        text = "Set as default"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(accentColor)
                        setPadding(dp(8), 0, dp(4), 0)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            Log.i(TAG, "Setting default entity: ${player.entityId}")
                            onSetDefault.invoke(player.entityId)
                            android.widget.Toast.makeText(
                                activity, "Default: ${displayName(player)}",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            // Update the section header from current to "DEFAULT"
                            // Just dismiss — next open will reflect new default
                            dialog.dismiss()
                        }
                    })
                }

                // Checkmark (right side, orange) — only for selected speaker
                if (isSelected) {
                    cellRow.addView(TextView(activity).apply {
                        text = "\u2713"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                        setTextColor(accentColor)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dp(4), 0, 0, 0)
                    })
                }

                groupContainer.addView(cellRow)

                // Separator between items (not after last)
                if (!isLast) {
                    groupContainer.addView(View(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                        ).apply {
                            // Indent separator to align with text (past dot + margin)
                            marginStart = dp(36)
                        }
                        setBackgroundColor(separatorColor)
                    })
                }
            }

            contentLayout.addView(groupContainer)
        }

        // Cap scroll height
        scrollView.post {
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.6).toInt()
            if (scrollView.height > maxH) {
                scrollView.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, maxH
                )
            }
        }

        Log.i(TAG, "Showing ${players.size} media players in iOS-style picker")
        dialog.show()
        applyImmersiveModeToDialog(dialog)
        // Request focus on first focusable cell so d-pad navigation works on TV
        contentLayout.post {
            contentLayout.findFocus() ?: run {
                for (i in 0 until contentLayout.childCount) {
                    val group = contentLayout.getChildAt(i) as? LinearLayout ?: continue
                    for (j in 0 until group.childCount) {
                        val child = group.getChildAt(j)
                        if (child.isFocusable && child.isClickable) {
                            child.requestFocus()
                            return@post
                        }
                    }
                }
            }
        }
    }

    /** Apply corner radii based on cell position within a grouped section. */
    private fun GradientDrawable.applyCornerRadii(
        isFirst: Boolean, isLast: Boolean, isSingle: Boolean, radius: Float
    ) {
        cornerRadii = when {
            isSingle -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            isFirst -> floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            isLast -> floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
            else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    /** Look up the friendly display name for a known player entity ID. Returns "" if not found. */
    fun displayNameFor(entityId: String): String {
        val match = availableMediaPlayers.firstOrNull { it.entityId == entityId } ?: return ""
        return displayName(match)
    }

    /** Get a display-friendly name for a media player entity. */
    private fun displayName(player: MediaPlayerInfo): String {
        val name = player.friendlyName
        val isGeneric = name.isBlank() ||
            name.lowercase() == "speaker" ||
            name.lowercase() == "speakers" ||
            name.startsWith("media_player.")
        return if (isGeneric) prettifyEntityIdSlug(player.entityId) else name
    }

    private fun dp(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
        activity.resources.displayMetrics
    ).toInt()

    /**
     * Update available media players list (called from JS bridge)
     */
    fun updateAvailableMediaPlayers(players: List<MediaPlayerInfo>) {
        availableMediaPlayers = players
        Log.i(TAG, "🎵 Updated available media players: ${players.size} found")
        players.forEach { player ->
            Log.d(TAG, "  - ${player.entityId}: ${player.friendlyName} (${player.state})")
        }
    }

    /**
     * Show the entity picker for live speaker switching.
     * Called when user taps the speaker indicator on the music player card.
     * Queries HA for available players, then shows the enhanced picker.
     */
    fun showEntityPicker(
        currentEntityId: String,
        recentEntityIds: List<String>,
        onSelected: (String) -> Unit,
        onSetDefault: ((String) -> Unit)? = null
    ) {
        Log.i(TAG, "🎵 showEntityPicker: querying HA for media players...")
        MusicPlayerJsInjector.queryMediaPlayers(webView) { jsonString ->
            activity.runOnUiThread {
                val players = parseMediaPlayersJson(jsonString)
                val filtered = filterMusicAssistantPlayers(players)
                if (filtered.isEmpty()) {
                    // Direct query found nothing — HA may be in an iframe (kiosk mode).
                    // Retry via shell relay which evaluates inside the HA iframe.
                    Log.i(TAG, "🎵 Direct query returned 0 players, retrying via shell relay...")
                    MusicPlayerJsInjector.queryMediaPlayersViaShell(webView) { shellJson ->
                        activity.runOnUiThread {
                            val shellPlayers = parseMediaPlayersJson(shellJson)
                            val shellFiltered = filterMusicAssistantPlayers(shellPlayers)
                            availableMediaPlayers = shellFiltered
                            showMediaPlayerPickerDialog(
                                currentEntityId, shellFiltered, recentEntityIds, onSelected, onSetDefault
                            )
                        }
                    }
                } else {
                    availableMediaPlayers = filtered
                    showMediaPlayerPickerDialog(
                        currentEntityId, filtered, recentEntityIds, onSelected, onSetDefault
                    )
                }
            }
        }
    }

    /**
     * Show the entity picker with pre-fetched player data.
     * Used when the query was performed via shell relay (async callback path)
     * since the direct evaluateJavascript approach doesn't work in shell page mode.
     */
    fun showEntityPickerWithPlayers(
        jsonString: String,
        currentEntityId: String,
        recentEntityIds: List<String>,
        onSelected: (String) -> Unit,
        onSetDefault: ((String) -> Unit)? = null
    ) {
        Log.i(TAG, "🎵 showEntityPickerWithPlayers: ${jsonString.take(100)}")
        val players = parseMediaPlayersJson(jsonString)
        val filtered = filterMusicAssistantPlayers(players)
        availableMediaPlayers = filtered
        showMediaPlayerPickerDialog(
            currentEntityId, filtered, recentEntityIds, onSelected, onSetDefault
        )
    }

    /**
     * Show the entity picker with a pre-built list of MediaPlayerInfo.
     * Used in direct-MA mode where players are fetched from MA's /players endpoint.
     */
    fun showMediaPlayerPickerDialogDirect(
        currentEntityId: String,
        players: List<MediaPlayerInfo>,
        recentEntityIds: List<String>,
        onSelected: (String) -> Unit,
        onSetDefault: ((String) -> Unit)? = null
    ) {
        Log.i(TAG, "🎵 showMediaPlayerPickerDialogDirect: ${players.size} players")
        availableMediaPlayers = players
        showMediaPlayerPickerDialog(
            currentEntityId, players, recentEntityIds, onSelected, onSetDefault
        )
    }

    /**
     * Get status text for the sidebar
     */
    fun getStatusText(): String {
        val enabled = halitePrefs.connection.musicPlayerEnabled
        val entityId = halitePrefs.connection.getEffectiveMusicPlayerEntityId()

        return when {
            !enabled -> "Disabled"
            entityId.isEmpty() -> "Not configured"
            else -> "Enabled"
        }
    }

    /**
     * Show transfer confirmation dialog after a new speaker is selected while media is loaded.
     * Uses the styled dialog_confirm layout with shaped buttons at the bottom.
     * Options: Transfer Music / Switch Only / Cancel
     *
     * @param activity The activity context for the dialog
     * @param onResult Called with "transfer", "switch", or "cancel"
     */
    fun showTransferConfirmation(activity: Activity, onResult: (String) -> Unit) {
        val dialogView = activity.layoutInflater.inflate(
            com.dashieapp.Dashie.R.layout.dialog_confirm, null
        )

        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle)?.text =
            "Transfer Music?"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage)?.text =
            "Would you like to transfer the current music to the new speaker?"

        val buttonCancel = dialogView.findViewById<Button>(com.dashieapp.Dashie.R.id.buttonNegative)
        val buttonTransfer = dialogView.findViewById<Button>(com.dashieapp.Dashie.R.id.buttonPositive)
        buttonCancel?.text = "Cancel"
        buttonTransfer?.text = "Transfer Music"

        // Add a "Switch Only" button between Cancel and Transfer
        val buttonRow = buttonCancel?.parent as? LinearLayout
        val buttonSwitch = android.widget.Button(activity).apply {
            text = "Switch Only"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            isAllCaps = false
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            defaultFocusHighlightEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        }
        // Insert between Cancel (index 0) and Transfer (index 1)
        buttonRow?.addView(buttonSwitch, 1)

        // Reduce margins on the outer buttons to fit 3 buttons
        (buttonCancel?.layoutParams as? LinearLayout.LayoutParams)?.marginEnd = dp(4)
        (buttonTransfer?.layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(4)

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        buttonCancel?.setOnClickListener { dialog.dismiss(); onResult("cancel") }
        buttonSwitch.setOnClickListener { dialog.dismiss(); onResult("switch") }
        buttonTransfer?.setOnClickListener { dialog.dismiss(); onResult("transfer") }
        dialog.setOnCancelListener { onResult("cancel") }

        dialog.show()
        applyImmersiveModeToDialog(dialog)
        DialogHelper.applyBorderButtonFocusHighlight(buttonSwitch, dialogStyle = true)
        DialogHelper.applyBorderButtonFocusHighlight(buttonCancel!!, dialogStyle = true)
    }

    /**
     * Show speaker switch confirmation when music is currently playing.
     * Offers: Transfer / Switch Control / Cancel.
     *
     * Transfer = clear + stop current, transfer queue to target, play on target.
     * Switch = stop + clear current, switch control to target (no auto-play).
     *
     * @param activity The activity context for the dialog
     * @param currentGroupName Friendly name of the currently controlled group
     * @param targetGroupName Friendly name of the group being switched to
     * @param onResult Called with "transfer", "switch", or "cancel"
     */
    fun showSpeakerSwitchConfirmation(
        activity: Activity,
        currentGroupName: String,
        targetGroupName: String,
        onResult: (String) -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(
            com.dashieapp.Dashie.R.layout.dialog_confirm, null
        )

        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle)?.text =
            "Switch Speaker Group"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage)?.text =
            "You are currently controlling music on $currentGroupName."

        val buttonCancel = dialogView.findViewById<Button>(com.dashieapp.Dashie.R.id.buttonNegative)
        val buttonTransfer = dialogView.findViewById<Button>(com.dashieapp.Dashie.R.id.buttonPositive)

        // Stack buttons vertically: Transfer on top, Switch control in middle, Cancel on bottom
        val buttonRow = buttonCancel?.parent as? LinearLayout
        buttonRow?.orientation = LinearLayout.VERTICAL

        // Top: Transfer (primary/orange style)
        buttonTransfer?.text = "Transfer to $targetGroupName"
        (buttonTransfer?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = LinearLayout.LayoutParams.MATCH_PARENT
            weight = 0f; marginStart = 0; bottomMargin = dp(8)
        }
        buttonRow?.removeView(buttonTransfer)
        buttonRow?.addView(buttonTransfer, 0)

        // Middle: Switch control (border style)
        val buttonSwitch = android.widget.Button(activity).apply {
            text = "Switch control to $targetGroupName"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            isAllCaps = false
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            defaultFocusHighlightEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        buttonRow?.addView(buttonSwitch, 1)

        // Bottom: Cancel (border style)
        buttonCancel?.text = "Cancel"
        (buttonCancel?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            width = LinearLayout.LayoutParams.MATCH_PARENT
            weight = 0f; marginEnd = 0
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        buttonCancel?.setOnClickListener { dialog.dismiss(); onResult("cancel") }
        buttonSwitch.setOnClickListener { dialog.dismiss(); onResult("switch") }
        buttonTransfer?.setOnClickListener { dialog.dismiss(); onResult("transfer") }
        dialog.setOnCancelListener { onResult("cancel") }

        dialog.show()
        applyImmersiveModeToDialog(dialog)
        DialogHelper.applyBorderButtonFocusHighlight(buttonSwitch, dialogStyle = true)
        DialogHelper.applyBorderButtonFocusHighlight(buttonCancel!!, dialogStyle = true)
    }

    /**
     * Transfer the Music Assistant queue from source to target speaker.
     * Pauses the source player, transfers the queue, and starts playback on target.
     * Uses evalInHaIframe for kiosk mode compatibility.
     */
    fun transferQueue(sourceEntityId: String, targetEntityId: String) {
        val wv = webView ?: run {
            Log.w(TAG, "🎵 Cannot transfer queue — no WebView reference")
            return
        }
        Log.i(TAG, "🎵 Transferring queue: $sourceEntityId → $targetEntityId")

        val script = """
            (function() {
                try {
                    var hass = document.querySelector('home-assistant');
                    if (hass && hass.hass) {
                        // Pause source player first
                        hass.hass.callService('media_player', 'media_pause', {}, { entity_id: '$sourceEntityId' });
                        // Transfer queue with auto_play to start on target
                        hass.hass.callService('music_assistant', 'transfer_queue', {
                            source_player: '$sourceEntityId',
                            auto_play: true
                        }, { entity_id: '$targetEntityId' });
                    }
                } catch (e) {
                    console.error('Queue transfer failed:', e);
                }
            })();
        """.trimIndent()

        // Try direct evaluation first, fall back to iframe relay for kiosk mode
        wv.post {
            val escaped = script.replace("\\", "\\\\").replace("`", "\\`").replace("\$", "\\\$")
            wv.evaluateJavascript("""
                (function() {
                    var ha = document.querySelector('home-assistant');
                    if (ha && ha.hass) {
                        // Direct mode — HA is in the main WebView
                        $script
                    } else if (typeof evalInHaIframe === 'function') {
                        // Kiosk mode — relay into HA iframe
                        evalInHaIframe(`$escaped`);
                    }
                })();
            """.trimIndent(), null)
        }
    }

}
