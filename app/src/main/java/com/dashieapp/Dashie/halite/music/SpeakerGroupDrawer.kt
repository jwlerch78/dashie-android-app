package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.edition.brandName

/**
 * Speaker group drawer with left/right split layout.
 *
 * Left column (~30%): pinned host player + current at top, then scrollable
 *   recents / groups / speakers sections below.
 * Right column (~70%): members of selected target with check/uncheck + volume controls.
 */
class SpeakerGroupDrawer(
    private val context: Context,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    var thisDevicePlayerId: String = "",
    var defaultEntityId: String = "",
    private val onClose: (() -> Unit)? = null,
    private val onTargetSelected: ((targetId: String) -> Unit)? = null,
    private val onSpeakerToggle: ((playerId: String, join: Boolean) -> Unit)? = null,
    private val onSpeakerVolumeChange: ((playerId: String, percent: Int) -> Unit)? = null,
    private val onSpeakerMuteToggle: ((playerId: String, muted: Boolean) -> Unit)? = null,
    private val onSpeakerBarTextChanged: ((String) -> Unit)? = null,
    /** Called when user taps a non-current speaker/group to transfer playback. */
    private val onTransferQueue: ((targetPlayerId: String, targetName: String) -> Unit)? = null,
    /** Called when user taps × to clear/stop a paused queue, freeing its speakers. */
    private val onClearQueue: ((targetPlayerId: String) -> Unit)? = null,
    /** Called when user changes group volume. */
    private val onGroupVolumeChange: ((percent: Int) -> Unit)? = null,
    /** Called when user toggles group mute. */
    private val onGroupMuteToggle: ((muted: Boolean) -> Unit)? = null,
    /** Coordinator for shared volume state (single source of truth). */
    var coordinator: MusicStateCoordinator? = null
) {
    companion object {
        private const val TAG = "SpeakerGroupDrawer"
        const val DRAWER_HEIGHT_DP = 324  // 270 * 1.2 = 324 (20% taller)
        private const val LEFT_WEIGHT = 0.30f
        private const val RIGHT_WEIGHT = 0.70f
        private const val SECTION_LABEL_SIZE_SP = 10f
        private const val TARGET_TEXT_SIZE_SP = 13f
        private const val DIVIDER_HEIGHT_DP = 1
        private const val ARROW_SIZE_DP = 20
        private const val MAX_RECENTS = 4
        private const val PREFS_NAME = "dashie_speaker_recents"
        private const val KEY_RECENTS = "recent_target_ids"
        private const val KEY_LAST_BAR_TEXT = "last_speaker_bar_text"
        private const val KEY_LAST_IS_GROUP = "last_is_group"
        private const val KEY_LAST_SPEAKER_COUNT = "last_speaker_count"
        private const val KEY_SPEAKER_RECENTS = "recent_speaker_ids"
        private const val MAX_SPEAKER_RECENTS = 3
        private const val MIN_SPEAKER_PERCENT = 10  // Floor: speakers clamp at scale 1 (10%) instead of muting
    }

    private fun textPrimary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
        else MusicPlayerStyles.textPrimary(context)
    private fun textSecondary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_SECONDARY_DARK
        else MusicPlayerStyles.textSecondary(context)
    private fun dp(dp: Int): Int = (MusicPlayerStyles.dpToPx(context, dp) * sizeMultiplier).toInt()

    val view: LinearLayout
    // Left column: pinned header + scrollable body
    private val leftPinned: LinearLayout      // HOST PLAYER + current (fixed)
    private val leftScrollBody: LinearLayout   // Recents + Groups + Speakers (scrollable)
    private val leftScroll: ScrollView
    // Right column
    private val rightList: LinearLayout
    private val rightScroll: ScrollView
    private val speakerItems = mutableMapOf<String, SpeakerGroupItem>()
    private var leftScrollWrapper: FrameLayout? = null
    private var leftBodyCollapsed = false

    // Scroll arrows for left column
    private var leftUpArrow: ImageView? = null
    private var leftDownArrow: ImageView? = null
    // Scroll arrows for right column
    private var rightUpArrow: ImageView? = null
    private var rightDownArrow: ImageView? = null

    // Speaker visibility
    val visibilityStore = SpeakerVisibilityStore(context)
    private var isManageMode = false
    /** Whether the tablet is in locked mode (manage button disabled). */
    var isLocked = false

    // Current state
    private var selectedTargetId: String = ""
    internal var allSpeakers: List<SpeakerInfo> = emptyList()
    private var allGroups: List<GroupInfo> = emptyList()
    private var currentlyPlayingTargetId: String = ""
    private val localMemberOverrides = mutableMapOf<String, Boolean>()

    // Recent selections persistence
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        val isDark = MusicPlayerStyles.isDarkMode(context)
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Cap drawer height: screen height minus strip content (drawer sits below top bar)
            val screenHeight = context.resources.displayMetrics.heightPixels
            val stripHeightPx = dp(MusicPlayerStyles.STRIP_HEIGHT_DP)
            val desiredHeight = dp(DRAWER_HEIGHT_DP)
            val maxHeight = screenHeight - stripHeightPx
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, minOf(desiredHeight, maxHeight)
            )
            // Solid background matching the media player/browser panel
            setBackgroundColor(if (isDark) MusicPlayerStyles.BG_COLOR_DARK else MusicPlayerStyles.BG_COLOR_LIGHT)
            visibility = View.GONE
        }

        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // ── Left column: pinned top + scrollable bottom ──
        val leftColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, LEFT_WEIGHT)
        }
        leftColumn.background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), if (forceDarkColors) 0x1AFFFFFF.toInt() else 0x1A000000.toInt())
        }

        // Pinned section (host player + current)
        leftPinned = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(4), 0)
        }
        leftColumn.addView(leftPinned)

        // Scrollable section wrapped in FrameLayout for arrows
        val lsw = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        leftScrollWrapper = lsw
        leftScrollBody = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(4), dp(4))
        }
        leftScroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            addView(leftScrollBody)
        }
        lsw.addView(leftScroll)

        // Up arrow
        leftUpArrow = createScrollArrow(pointingUp = true).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
            setOnClickListener { leftScroll.smoothScrollBy(0, -dp(80)) }
        }
        lsw.addView(leftUpArrow)

        // Down arrow
        leftDownArrow = createScrollArrow(pointingUp = false).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
            setOnClickListener { leftScroll.smoothScrollBy(0, dp(80)) }
        }
        lsw.addView(leftDownArrow)

        leftScroll.viewTreeObserver.addOnScrollChangedListener { updateLeftArrows() }

        leftColumn.addView(lsw)
        contentRow.addView(leftColumn)

        // ── Right column ──
        val rightScrollWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, RIGHT_WEIGHT)
        }
        rightList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4))
        }
        rightScroll = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            addView(rightList)
        }
        rightScrollWrapper.addView(rightScroll)

        rightUpArrow = createScrollArrow(pointingUp = true).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
            setOnClickListener { rightScroll.smoothScrollBy(0, -dp(80)) }
        }
        rightScrollWrapper.addView(rightUpArrow)

        rightDownArrow = createScrollArrow(pointingUp = false).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            visibility = View.GONE
            setOnClickListener { rightScroll.smoothScrollBy(0, dp(80)) }
        }
        rightScrollWrapper.addView(rightDownArrow)

        rightScroll.viewTreeObserver.addOnScrollChangedListener { updateRightArrows() }

        contentRow.addView(rightScrollWrapper)
        view.addView(contentRow)
    }

    // ========== Scroll arrow helpers ==========

    private fun createScrollArrow(pointingUp: Boolean): ImageView {
        val isDark = MusicPlayerStyles.isDarkMode(context)
        return ImageView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) 0xCC444444.toInt() else 0xCC000000.toInt())
            }
            setImageResource(if (pointingUp) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(4).toFloat()
            isClickable = true; isFocusable = true
        }
    }

    private fun updateLeftArrows() {
        val child = leftScroll.getChildAt(0) ?: return
        val scrollY = leftScroll.scrollY
        val maxScroll = child.height - leftScroll.height
        leftUpArrow?.visibility = if (scrollY > dp(8)) View.VISIBLE else View.GONE
        leftDownArrow?.visibility = if (maxScroll > 0 && scrollY < maxScroll - dp(8)) View.VISIBLE else View.GONE
    }

    private fun updateRightArrows() {
        val child = rightScroll.getChildAt(0) ?: return
        val scrollY = rightScroll.scrollY
        val maxScroll = child.height - rightScroll.height
        rightUpArrow?.visibility = if (scrollY > dp(8)) View.VISIBLE else View.GONE
        rightDownArrow?.visibility = if (maxScroll > 0 && scrollY < maxScroll - dp(8)) View.VISIBLE else View.GONE
    }

    // ========== Recent selections persistence ==========

    private fun getRecentTargetIds(): List<String> {
        val raw = prefs.getString(KEY_RECENTS, "") ?: ""
        return raw.split(",").filter { it.isNotEmpty() }
    }

    private fun addRecentTarget(targetId: String) {
        val recents = getRecentTargetIds().toMutableList()
        recents.remove(targetId)
        recents.add(0, targetId)
        val trimmed = recents.take(MAX_RECENTS + 1)  // Keep extra for filtering out current
        prefs.edit().putString(KEY_RECENTS, trimmed.joinToString(",")).apply()
    }

    // ========== Speaker recents (right column) ==========

    private fun getSpeakerRecentIds(): List<String> {
        val raw = prefs.getString(KEY_SPEAKER_RECENTS, "") ?: ""
        return raw.split(",").filter { it.isNotEmpty() }
    }

    private fun addSpeakerRecent(speakerId: String) {
        val recents = getSpeakerRecentIds().toMutableList()
        recents.remove(speakerId)
        recents.add(0, speakerId)
        prefs.edit().putString(KEY_SPEAKER_RECENTS, recents.take(MAX_SPEAKER_RECENTS + 3).joinToString(",")).apply()
    }

    // ========== Public API ==========

    fun update(speakers: List<SpeakerInfo>, groups: List<GroupInfo> = emptyList()) {
        allSpeakers = speakers
        allGroups = groups
        if (selectedTargetId.isNotEmpty() && localMemberOverrides.isNotEmpty()) {
            val group = groups.find { it.groupId == selectedTargetId }
            val serverMembers = group?.memberIds?.toSet() ?: run {
                // Include ad-hoc sync followers so overrides for them are preserved
                val syncedFollowers = speakers.filter { it.syncedTo == selectedTargetId }.map { it.playerId }
                setOf(selectedTargetId) + syncedFollowers
            }
            val resolved = localMemberOverrides.filter { (pid, joined) ->
                if (joined) pid !in serverMembers else pid in serverMembers
            }
            localMemberOverrides.clear()
            localMemberOverrides.putAll(resolved)
        }

        if (speakers.isEmpty() && groups.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        // Don't force visibility — the renderer controls show/hide.
        // Only rebuild content if we have data.

        rebuildLeftColumn()

        val autoTarget = resolveCurrentPlayerId().ifEmpty {
            groups.firstOrNull()?.groupId ?: speakers.firstOrNull()?.playerId ?: ""
        }
        if (autoTarget.isNotEmpty()) {
            if (autoTarget == selectedTargetId && speakerItems.isNotEmpty()) {
                // Same target — check if membership changed (e.g., dynamic sync added/removed)
                val group = allGroups.find { it.groupId == selectedTargetId }
                val currentMembers = if (group != null) {
                    group.memberIds.toSet()
                } else {
                    val syncedFollowers = allSpeakers.filter { it.syncedTo == selectedTargetId }.map { it.playerId }
                    setOf(selectedTargetId) + syncedFollowers
                }
                val knownActive = speakerItems.filter { it.value.isInGroup }.keys
                if (currentMembers != knownActive) {
                    // Membership changed — full rebuild needed
                    selectTarget(autoTarget)
                } else {
                    updateRightColumnInPlace()
                }
            } else {
                selectTarget(autoTarget)
            }
        }
    }

    fun setCurrentlyPlaying(targetId: String) {
        currentlyPlayingTargetId = targetId
    }

    private fun resolveCurrentPlayerId(): String {
        if (currentlyPlayingTargetId.isNotEmpty()) {
            if (allGroups.any { it.groupId == currentlyPlayingTargetId }) return currentlyPlayingTargetId
            if (allSpeakers.any { it.playerId == currentlyPlayingTargetId }) return currentlyPlayingTargetId
        }
        if (thisDevicePlayerId.isNotEmpty() &&
            allSpeakers.any { it.playerId.contains(thisDevicePlayerId) }) {
            return allSpeakers.first { it.playerId.contains(thisDevicePlayerId) }.playerId
        }
        return allSpeakers.firstOrNull { it.state == "playing" }?.playerId ?: ""
    }

    // ========== Left column ==========

    private fun rebuildLeftColumn() {
        leftPinned.removeAllViews()
        leftScrollBody.removeAllViews()
        val currentId = resolveCurrentPlayerId()

        // ── Pinned section: clickable title with collapse chevron + current player ──
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(2))
            isClickable = true; isFocusable = true
            setOnClickListener {
                leftBodyCollapsed = !leftBodyCollapsed
                leftScrollWrapper?.visibility = if (leftBodyCollapsed) View.GONE else View.VISIBLE
                (getChildAt(1) as? ImageView)?.setImageResource(
                    if (leftBodyCollapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up
                )
            }
        }
        TextView(context).apply {
            text = "PLAYERS"
            setTextColor(MusicPlayerStyles.ACCENT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * sizeMultiplier)
            typeface = Typeface.DEFAULT_BOLD
            titleRow.addView(this)
        }
        ImageView(context).apply {
            val s = dp(14)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(4) }
            setImageResource(if (leftBodyCollapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
            setColorFilter(MusicPlayerStyles.ACCENT_COLOR)
            scaleType = ImageView.ScaleType.FIT_CENTER
            titleRow.addView(this)
        }
        leftPinned.addView(titleRow)

        // ── PLAYING + IDLE sections ──
        // MA reports "idle" (not "paused") when a track is paused, but includes current_media.title.
        // Split into: PLAYING (actively playing) and IDLE (paused/idle with a track, clearable).
        // De-duplicate: speakers that are members of an active group don't show individually.
        val hiddenIds = visibilityStore.getAllHidden()

        fun effectiveState(state: String, track: String): String = when {
            state == "playing" -> "playing"
            state == "paused" -> "paused"
            track.isNotEmpty() -> "paused"  // idle with track = paused
            else -> state
        }

        fun hasTrackOrPlaying(state: String, track: String) = state == "playing" || state == "paused" || track.isNotEmpty()

        val activeGroups = allGroups.filter { hasTrackOrPlaying(it.state, it.currentTrack) }
        val activeGroupMemberIds = activeGroups.flatMap { it.memberIds }.toSet()

        // Speakers that are members of an active group belong in the SPEAKERS
        // section, not PLAYING/IDLE — the group entry represents them here.
        // Sync followers (syncedTo set) are also excluded from active entries
        // but DO appear in the SPEAKERS section as individual targets.

        val allActiveEntries = mutableListOf<TargetEntry>()
        for (grp in activeGroups) {
            allActiveEntries.add(TargetEntry(grp.groupId, grp.displayName, grp.groupType,
                isCurrent = grp.groupId == currentId,
                state = effectiveState(grp.state, grp.currentTrack), currentTrack = grp.currentTrack,
                memberCount = grp.memberIds.size))
        }
        for (spk in allSpeakers) {
            // Group members show in SPEAKERS section, not here
            if (spk.playerId in activeGroupMemberIds) continue
            // Sync followers are represented by the group entry
            if (spk.syncedTo.isNotEmpty()) continue
            if (hasTrackOrPlaying(spk.state, spk.currentTrack) && spk.playerId !in hiddenIds) {
                val suffix = if (thisDevicePlayerId.isNotEmpty() && spk.playerId.contains(thisDevicePlayerId)) " (this device)" else ""
                // Ad-hoc sync: count followers pointing to this speaker as lead
                val adHocFollowers = allSpeakers.count { it.syncedTo == spk.playerId }
                val adHocCount = if (adHocFollowers > 0) 1 + adHocFollowers else 0
                val entryType = if (adHocFollowers > 0) "sync_group" else "speaker"
                allActiveEntries.add(TargetEntry(spk.playerId, "${spk.displayName}$suffix", entryType,
                    isCurrent = spk.playerId == currentId,
                    state = effectiveState(spk.state, spk.currentTrack), currentTrack = spk.currentTrack,
                    memberCount = adHocCount))
            }
        }

        // Sort: this device first, then by name
        val thisDeviceGroupIds = activeGroups
            .filter { grp -> grp.memberIds.any { mid -> thisDevicePlayerId.isNotEmpty() && mid.contains(thisDevicePlayerId) } }
            .map { it.groupId }.toSet()
        fun isThisDevice(entry: TargetEntry) =
            entry.id in thisDeviceGroupIds || (thisDevicePlayerId.isNotEmpty() && entry.id.contains(thisDevicePlayerId))
        val sortComparator = compareByDescending<TargetEntry> { isThisDevice(it) }.thenBy { it.name }

        // Split into playing vs idle
        val playingEntries = allActiveEntries.filter { it.state == "playing" }.sortedWith(sortComparator)
        val idleEntries = allActiveEntries.filter { it.state != "playing" }.sortedWith(sortComparator)

        if (playingEntries.isNotEmpty()) {
            addLabel(leftPinned, "PLAYING")
            for (entry in playingEntries) {
                addTargetRow(leftPinned, entry, isActive = entry.isCurrent)
            }
            addDivider(leftPinned)
        }
        if (idleEntries.isNotEmpty()) {
            addLabel(leftPinned, "IDLE")
            for (entry in idleEntries) {
                addTargetRow(leftPinned, entry, isActive = entry.isCurrent, showClearButton = true)
            }
            addDivider(leftPinned)
        }

        // ── Scrollable section: recents, then all remaining speakers/groups ──
        // Don't exclude activeGroupMemberIds or syncedToIds — group members
        // and sync followers should appear in SPEAKERS as individual targets
        val playingIds = allActiveEntries.map { it.id }.toSet()

        // Recents (excluding items already in PLAYING/IDLE)
        val recentIds = getRecentTargetIds().filter { it !in playingIds }
        val recentEntries = recentIds.take(MAX_RECENTS).mapNotNull { id ->
            allGroups.find { it.groupId == id }?.let { grp ->
                TargetEntry(grp.groupId, grp.displayName, grp.groupType, false,
                    state = grp.state, currentTrack = grp.currentTrack, memberCount = grp.memberIds.size)
            } ?: allSpeakers.find { it.playerId == id && it.playerId !in hiddenIds }?.let { spk ->
                TargetEntry(spk.playerId, spk.displayName, "speaker", false)
            }
        }
        if (recentEntries.isNotEmpty()) {
            addLabel(leftScrollBody, "RECENTS")
            for (entry in recentEntries) {
                addTargetRow(leftScrollBody, entry)
            }
            addDivider(leftScrollBody)
        }

        // All remaining speakers and groups (excluding PLAYING/IDLE and recents)
        val recentIdsSet = recentEntries.map { it.id }.toSet()
        val excludedIds = playingIds + recentIdsSet
        val remainingGroups = allGroups.filter { it.groupId !in excludedIds }
        val remainingSpeakers = allSpeakers.filter { it.playerId !in excludedIds && it.playerId !in hiddenIds }
        val remainingEntries = mutableListOf<TargetEntry>()
        for (grp in remainingGroups) {
            remainingEntries.add(TargetEntry(grp.groupId, grp.displayName, grp.groupType, false,
                state = grp.state, currentTrack = grp.currentTrack, memberCount = grp.memberIds.size))
        }
        for (spk in remainingSpeakers) {
            val suffix = if (spk.playerId == defaultEntityId) " (default)" else ""
            remainingEntries.add(TargetEntry(spk.playerId, "${spk.displayName}$suffix", "speaker", false))
        }
        if (remainingEntries.isNotEmpty()) {
            addLabel(leftScrollBody, "SPEAKERS")
            for (entry in remainingEntries) {
                addTargetRow(leftScrollBody, entry)
            }
        }

        // Defer arrow visibility check
        leftScroll.post { updateLeftArrows() }
    }

    private fun addLabel(parent: LinearLayout, text: String, isTitle: Boolean = false) {
        TextView(context).apply {
            this.text = text
            if (isTitle) {
                setTextColor(MusicPlayerStyles.ACCENT_COLOR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * sizeMultiplier)
            } else {
                setTextColor(textSecondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_LABEL_SIZE_SP * sizeMultiplier)
            }
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(4), 0, dp(2))
            parent.addView(this)
        }
    }

    private fun addDivider(parent: LinearLayout) {
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(DIVIDER_HEIGHT_DP)
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            setBackgroundColor(if (forceDarkColors) 0x1AFFFFFF.toInt() else 0x1A000000.toInt())
            parent.addView(this)
        }
    }

    private fun addTargetRow(parent: LinearLayout, entry: TargetEntry, isActive: Boolean = false, showClearButton: Boolean = false) {
        val isSelected = entry.id == selectedTargetId
        val isGroup = entry.type == "sync_group" || entry.type == "universal_group" || entry.type == "group"
        val isPlaying = entry.state == "playing"
        val isPaused = entry.state == "paused"
        val hasTrack = entry.currentTrack.isNotEmpty()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            minimumHeight = dp(32)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            isClickable = true; isFocusable = true

            if (isSelected && !isActive) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(MusicPlayerStyles.ACCENT_COLOR and 0x30FFFFFF.toInt())
                }
            }

            setOnClickListener {
                if (isActive) return@setOnClickListener
                addRecentTarget(entry.id)
                onTransferQueue?.invoke(entry.id, entry.name)
            }
        }

        // Play/pause icon for active entries, speaker/group icon for others
        if (isPlaying || isPaused) {
            ImageView(context).apply {
                val iconSize = dp(14)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(4) }
                setImageResource(if (isPlaying) R.drawable.ic_play else R.drawable.ic_pause)
                setColorFilter(MusicPlayerStyles.ACCENT_COLOR)
                scaleType = ImageView.ScaleType.FIT_CENTER
                row.addView(this)
            }
        } else {
            ImageView(context).apply {
                val iconSize = dp(16)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(4) }
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(if (isGroup || entry.memberCount > 0) R.drawable.ic_speaker_group else R.drawable.ic_box_speaker)
                setColorFilter(if (isActive) MusicPlayerStyles.ACCENT_COLOR
                    else if (isSelected) textPrimary() else textSecondary())
                row.addView(this)
            }
        }

        // Name + optional track title stacked vertically
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        TextView(context).apply {
            text = entry.name
            setTextColor(if (isActive || isSelected || isPlaying) textPrimary() else textSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TARGET_TEXT_SIZE_SP * sizeMultiplier)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (isActive || isPlaying) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(2), 0, 0, 0)
            textColumn.addView(this)
        }
        if ((isPlaying || isPaused) && hasTrack) {
            TextView(context).apply {
                text = entry.currentTrack
                setTextColor(MusicPlayerStyles.ACCENT_COLOR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * sizeMultiplier)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), 0, 0, 0)
                textColumn.addView(this)
            }
        }
        row.addView(textColumn)

        // Member count badge for groups and ad-hoc syncs
        if (entry.memberCount > 0) {
            TextView(context).apply {
                text = "${entry.memberCount}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f * sizeMultiplier)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                val size = dp(18)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginStart = dp(4) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MusicPlayerStyles.ACCENT_COLOR)
                }
                row.addView(this)
            }
        }

        // × clear button for idle entries — stops queue, frees speakers
        if (showClearButton) {
            TextView(context).apply {
                text = "×"
                setTextColor(textSecondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * sizeMultiplier)
                setPadding(dp(8), 0, dp(4), 0)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    onClearQueue?.invoke(entry.id)
                }
                row.addView(this)
            }
        }

        parent.addView(row)
    }

    // ========== Right column ==========

    /** Update existing speaker items in place without rebuilding (avoids flicker). */
    private fun updateRightColumnInPlace() {
        val leadState = allSpeakers.find { it.playerId == selectedTargetId }?.state ?: "idle"
        val selectedGroup = allGroups.find { it.groupId == selectedTargetId }
        for (spk in allSpeakers) {
            val item = speakerItems[spk.playerId] ?: continue
            val effectiveState = if (spk.syncedTo.isNotEmpty() && spk.state == "idle" && leadState == "playing") "playing" else spk.state
            // Update lock state for static members and stream host
            val isStaticLocked = selectedGroup != null && spk.playerId in selectedGroup.staticMemberIds
            val isStreamHost = selectedTargetId.isNotEmpty()
                && spk.playerId == selectedTargetId && item.isInGroup
            item.updateLocked(isStaticLocked || isStreamHost)
            item.update(effectiveState, spk.volumePercent, spk.isMuted, item.isInGroup)
        }
    }

    fun selectTarget(targetId: String) {
        selectedTargetId = targetId
        rebuildLeftColumn()
        rebuildRightColumn()
    }

    // Inactive section collapse state
    private var inactiveCollapsed = false
    private var inactiveContainer: LinearLayout? = null

    private fun rebuildRightColumn() {
        rightList.removeAllViews()
        speakerItems.clear()

        val currentId = resolveCurrentPlayerId()

        val group = allGroups.find { it.groupId == selectedTargetId }
        val serverMembers = if (group != null) {
            group.memberIds.toSet()
        } else {
            // Include speakers dynamically synced to this target (syncedTo field)
            val syncedFollowers = allSpeakers.filter { it.syncedTo == selectedTargetId }.map { it.playerId }
            (setOf(selectedTargetId) + syncedFollowers)
        }

        val effectiveMembers = serverMembers.toMutableSet()
        for ((pid, joined) in localMemberOverrides) {
            if (joined) effectiveMembers.add(pid) else effectiveMembers.remove(pid)
        }

        if (currentId.isNotEmpty() && localMemberOverrides[currentId] != false) {
            effectiveMembers.add(currentId)
        }

        // Split speakers — propagate lead player's state to synced followers
        val leadState = allSpeakers.find { it.playerId == selectedTargetId }?.state ?: "idle"
        val activeSpeakers = allSpeakers.filter { it.playerId in effectiveMembers }
            .map { spk ->
                // Synced followers inherit the lead player's state
                if (spk.syncedTo.isNotEmpty() && spk.state == "idle" && leadState == "playing") {
                    spk.copy(state = "playing")
                } else spk
            }
            .sortedWith(
                compareByDescending<SpeakerInfo> {
                    thisDevicePlayerId.isNotEmpty() && it.playerId.contains(thisDevicePlayerId)
                }.thenBy { it.displayName }
            )
        val inactiveSpeakers = allSpeakers.filter { it.playerId !in effectiveMembers }

        // Sort inactive by recency
        val speakerRecents = getSpeakerRecentIds()
        val sortedInactive = inactiveSpeakers.sortedWith(
            compareBy<SpeakerInfo> {
                val idx = speakerRecents.indexOf(it.playerId)
                if (idx >= 0) idx else Int.MAX_VALUE
            }.thenBy { it.displayName }
        )

        // Recent inactive speakers (top 3 from recents that are currently inactive)
        val recentInactiveIds = speakerRecents.filter { id -> inactiveSpeakers.any { it.playerId == id } }
            .take(MAX_SPEAKER_RECENTS)

        // Filter out hidden speakers from active/inactive — hidden speakers appear
        // in dedicated "HIDDEN" sections in manage mode, not in the regular lists.
        val hiddenIds = visibilityStore.getAllHidden()
        val visibleActive = activeSpeakers.filter { it.playerId !in hiddenIds }
        val visibleInactive = sortedInactive.filter { it.playerId !in hiddenIds }
        val visibleRecentIds = if (isManageMode) emptyList() else recentInactiveIds.filter { it !in hiddenIds }

        // ── SPEAKERS title row: [SPEAKERS] [edit]  ...spacer...  [Group vol tab] ──
        val isGroupTarget = group != null
            || allSpeakers.any { it.syncedTo == selectedTargetId }
            || coordinator?.isGroupActive == true
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), 0, 0)
        }
        TextView(context).apply {
            text = "SPEAKERS"
            setTextColor(MusicPlayerStyles.ACCENT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * sizeMultiplier)
            typeface = Typeface.DEFAULT_BOLD
            titleRow.addView(this)
        }

        // Edit button — right after SPEAKERS text, with "close" text to its right in manage mode
        ImageButton(context).apply {
            val s = dp(34)
            layoutParams = LinearLayout.LayoutParams(s, s)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isManageMode) (MusicPlayerStyles.ACCENT_COLOR and 0x30FFFFFF.toInt()) else 0x00000000.toInt())
            }
            setImageResource(R.drawable.ic_edit)
            setColorFilter(MusicPlayerStyles.ACCENT_COLOR)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(5), dp(5), dp(5), dp(5))
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (isLocked) {
                    android.widget.Toast.makeText(context, "Unlock tablet to manage speakers", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    isManageMode = !isManageMode
                    rebuildRightColumn()
                }
            }
            titleRow.addView(this)
        }
        // "close" text — appears to the right of edit icon in manage mode
        if (isManageMode) {
            TextView(context).apply {
                text = "close"
                setTextColor(MusicPlayerStyles.ACCENT_COLOR)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sizeMultiplier)
                setPadding(dp(4), 0, 0, 0)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    isManageMode = false
                    rebuildRightColumn()
                }
                titleRow.addView(this)
            }
        }

        // Spacer pushes group volume tab to far right
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            titleRow.addView(this)
        }

        // Group volume tab — read from coordinator, but preserve local state during debounce
        if (isGroupTarget && !isManageMode) {
            val coord = coordinator
            val gv: Int
            val gm: Boolean
            if (isGroupVolumeDebounceActive()) {
                // User recently changed volume — keep local values
                gv = groupVolumePercent
                gm = groupVolumeMuted
            } else {
                gv = coord?.groupVolumePercent?.takeIf { it >= 0 } ?: 50
                gm = coord?.groupVolumeMuted ?: false
            }
            addGroupVolumeInline(titleRow, gv, gm)
        }

        rightList.addView(titleRow)

        // ── Active speakers (with [-] in manage mode, [✓] in normal mode) ──
        for (speaker in visibleActive) {
            addSpeakerItem(speaker, inGroup = true, manageMode = isManageMode)
        }

        // ── Recents (normal mode only) ──
        if (!isManageMode && visibleRecentIds.isNotEmpty()) {
            addLabel(rightList, "RECENTS")
            for (id in visibleRecentIds) {
                val speaker = allSpeakers.find { it.playerId == id } ?: continue
                addSpeakerItem(speaker, inGroup = false, compact = false)
            }
        }

        // ── Inactive speakers (with [-] in manage mode, [ ] in normal mode) ──
        val remainingInactive = visibleInactive.filter { it.playerId !in visibleRecentIds }
        if (remainingInactive.isNotEmpty()) {
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(6), dp(4), dp(4))
                isClickable = true; isFocusable = true
                setOnClickListener {
                    inactiveCollapsed = !inactiveCollapsed
                    inactiveContainer?.visibility = if (inactiveCollapsed) View.GONE else View.VISIBLE
                    (getChildAt(1) as? ImageView)?.setImageResource(
                        if (inactiveCollapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up
                    )
                }
            }
            TextView(context).apply {
                text = "INACTIVE"
                setTextColor(textSecondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * sizeMultiplier)
                typeface = Typeface.DEFAULT_BOLD
                headerRow.addView(this)
            }
            ImageView(context).apply {
                val s = dp(14)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginStart = dp(4) }
                setImageResource(if (inactiveCollapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
                setColorFilter(textSecondary())
                scaleType = ImageView.ScaleType.FIT_CENTER
                headerRow.addView(this)
            }
            rightList.addView(headerRow)

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (inactiveCollapsed) View.GONE else View.VISIBLE
            }
            for (speaker in remainingInactive) {
                addSpeakerItem(speaker, inGroup = false, manageMode = isManageMode, parent = container)
            }
            inactiveContainer = container
            rightList.addView(container)
        }

        // ── Hidden sections (manage mode only) ──
        if (isManageMode) {
            val localHidden = visibilityStore.getLocalHidden()
            val localHiddenSpeakers = allSpeakers.filter { it.playerId in localHidden }
            if (localHiddenSpeakers.isNotEmpty()) {
                addSectionHeader(rightList, "HIDDEN FROM THIS TABLET")
                for (speaker in localHiddenSpeakers) {
                    addHiddenSpeakerRow(speaker, isGlobal = false)
                }
            }

            val globalHidden = visibilityStore.getGlobalHidden()
            val globalHiddenSpeakers = allSpeakers.filter { it.playerId in globalHidden }
            if (globalHiddenSpeakers.isNotEmpty()) {
                addSectionHeader(rightList, "HIDDEN FROM ALL DASHIE TABLETS")
                for (speaker in globalHiddenSpeakers) {
                    addHiddenSpeakerRow(speaker, isGlobal = true)
                }
            }
        }

        rightScroll.post { updateRightArrows() }
    }

    private fun addSectionHeader(parent: LinearLayout, text: String) {
        TextView(context).apply {
            this.text = text
            setTextColor(textSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_LABEL_SIZE_SP * sizeMultiplier)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(8), 0, dp(4))
            parent.addView(this)
        }
    }

    private fun addHiddenSpeakerRow(speaker: SpeakerInfo, isGlobal: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
            )
            setPadding(dp(16), dp(2), dp(16), dp(2))
        }

        // Plus button to unhide — orange circle with white plus
        ImageView(context).apply {
            val size = dp(24)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(6) }
            setImageResource(R.drawable.ic_add)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MusicPlayerStyles.ACCENT_COLOR)
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener {
                if (isGlobal) {
                    // Show option: restore on this tablet or all tablets
                    showUnhideDialog(speaker.playerId)
                } else {
                    visibilityStore.showLocal(speaker.playerId)
                    rebuildRightColumn()
                }
            }
            row.addView(this)
        }

        // Speaker name
        TextView(context).apply {
            text = speaker.displayName
            setTextColor(textSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * sizeMultiplier)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            row.addView(this)
        }

        rightList.addView(row)
    }

    private fun showHideDialog(playerId: String) {
        val activity = context as? android.app.Activity ?: return
        val speakerName = allSpeakers.find { it.playerId == playerId }?.displayName ?: "Speaker"
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Hide Speaker"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = "Hide $speakerName from:"

        val negBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonNegative)
        val posBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonPositive)
        val buttonsRow = negBtn.parent as? LinearLayout

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView).setCancelable(true).create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        buttonsRow?.orientation = LinearLayout.VERTICAL
        negBtn.apply {
            text = "This Tablet Only"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f; marginEnd = 0; bottomMargin = dp(8)
            }
            setOnClickListener { dialog.dismiss(); visibilityStore.hideLocal(playerId); rebuildRightColumn(); rebuildLeftColumn() }
        }
        posBtn.apply {
            text = "All ${context.brandName()} Tablets"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f; marginStart = 0
            }
            setOnClickListener { dialog.dismiss(); visibilityStore.hideGlobal(playerId); rebuildRightColumn(); rebuildLeftColumn() }
        }
        dialog.show()
    }

    private fun showUnhideDialog(playerId: String) {
        val activity = context as? android.app.Activity ?: return
        val speakerName = allSpeakers.find { it.playerId == playerId }?.displayName ?: "Speaker"
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Show Speaker"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = "Show $speakerName on:"

        val negBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonNegative)
        val posBtn = dialogView.findViewById<android.widget.Button>(R.id.buttonPositive)
        val buttonsRow = negBtn.parent as? LinearLayout

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView).setCancelable(true).create()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        buttonsRow?.orientation = LinearLayout.VERTICAL
        negBtn.apply {
            text = "This Tablet Only"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f; marginEnd = 0; bottomMargin = dp(8)
            }
            // Remove from local global-hidden cache only — other tablets still hide it
            setOnClickListener { dialog.dismiss(); visibilityStore.showGlobalLocalOnly(playerId); rebuildRightColumn(); rebuildLeftColumn() }
        }
        posBtn.apply {
            text = "All ${context.brandName()} Tablets"
            (layoutParams as? LinearLayout.LayoutParams)?.apply {
                width = LinearLayout.LayoutParams.MATCH_PARENT; weight = 0f; marginStart = 0
            }
            // Remove from global hidden + sync removal to HA
            setOnClickListener { dialog.dismiss(); visibilityStore.showGlobal(playerId); rebuildRightColumn(); rebuildLeftColumn() }
        }
        dialog.show()
    }

    private fun addSpeakerItem(speaker: SpeakerInfo, inGroup: Boolean, compact: Boolean = !inGroup, manageMode: Boolean = false, parent: LinearLayout? = null) {
        val isThisDev = thisDevicePlayerId.isNotEmpty() && speaker.playerId.contains(thisDevicePlayerId)
        // Lock the checkbox for:
        // 1. Static sync group members — MA rejects ungroup with 500
        // 2. The stream host (selected target) — use mute to silence, not uncheck
        val selectedGroup = allGroups.find { it.groupId == selectedTargetId }
        val isStaticMember = selectedGroup != null
            && speaker.playerId in selectedGroup.staticMemberIds
        val isStreamHost = selectedTargetId.isNotEmpty()
            && speaker.playerId == selectedTargetId && inGroup
        val shouldLock = isStaticMember || isStreamHost
        val item = SpeakerGroupItem(
            context = context,
            forceDarkColors = forceDarkColors,
            sizeMultiplier = sizeMultiplier,
            playerId = speaker.playerId,
            displayName = speaker.displayName,
            isInGroup = if (manageMode) false else inGroup,
            isHighlighted = if (manageMode) false else inGroup,
            isCompact = compact,
            isManageMode = manageMode,
            isLocked = shouldLock,
            state = speaker.state,
            volumePercent = speaker.volumePercent,
            isMuted = speaker.isMuted,
            isThisDevice = isThisDev,
            onToggleGroup = { pid, join ->
                localMemberOverrides[pid] = join
                if (!join) addSpeakerRecent(pid)  // Track unchecked speakers for recents
                // All speakers (including host device) use group/ungroup via MA API.
                // The separate mute button handles local audio muting.
                onSpeakerToggle?.invoke(pid, join)
                view.post {
                    resortAfterToggle()
                    onSpeakerBarTextChanged?.invoke(getFormattedSpeakerBarText())
                }
            },
            onVolumeChange = onSpeakerVolumeChange,
            onMuteToggle = onSpeakerMuteToggle,
            onHideClicked = if (manageMode) { pid -> showHideDialog(pid) } else null
        )
        speakerItems[speaker.playerId] = item
        (parent ?: rightList).addView(item.view)
    }

    // ========== Group volume row ==========

    private var groupVolumeValue: TextView? = null
    private var groupMuteIcon: ImageView? = null
    internal var groupVolumeMuted = false
    internal var groupVolumePercent = 50

    /** Timestamp of last local group volume change. Poll rebuilds skip volume overwrite within window. */
    private var lastGroupVolumeChangeAt: Long = 0
    private fun markGroupVolumeChange() { lastGroupVolumeChangeAt = System.currentTimeMillis() }
    private fun isGroupVolumeDebounceActive(): Boolean = lastGroupVolumeChangeAt > 0 && (System.currentTimeMillis() - lastGroupVolumeChangeAt) < 3000

    /** Convert MA 0-100 percent to display 0-10 scale, rounded. Non-zero always shows at least 1. */
    private fun volumeToScale(percent: Int): Int = coordinator?.volumeScale(percent)
        ?: if (percent <= 0) 0 else ((percent / 10.0) + 0.5).toInt().coerceIn(1, 10)
    /** Convert display 0-10 scale to MA 0-100 percent. */
    private fun scaleToVolume(scale: Int): Int = (scale * 10).coerceIn(0, 100)

    private fun addGroupVolumeInline(parent: LinearLayout, volume: Int, muted: Boolean) {
        groupVolumePercent = volume
        groupVolumeMuted = muted
        // Use the same accent highlight as grouped/active speakers
        val tabColor = MusicPlayerStyles.ACCENT_COLOR and 0x30FFFFFF.toInt()
        val scale = 1.25f  // 25% larger elements

        // Tab container — right-aligned, bottom padding extends to touch speaker area
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            // Bottom padding connects tab flush to speaker highlight below
            setPadding(dp(6), dp(2), dp(6), dp(4))
            background = GradientDrawable().apply {
                setCornerRadii(floatArrayOf(
                    dp(6).toFloat(), dp(6).toFloat(),  // top-left
                    dp(6).toFloat(), dp(6).toFloat(),  // top-right
                    0f, 0f,                             // bottom-right (no radius — flush)
                    0f, 0f                              // bottom-left (no radius — flush)
                ))
                setColor(tabColor)
            }
        }

        // "GROUP" label — matches PLAYING/RECENTS section label style
        TextView(context).apply {
            text = "GROUP"
            setTextColor(textSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, SECTION_LABEL_SIZE_SP * sizeMultiplier)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(6), 0)
            box.addView(this)
        }

        // Mute button
        val muteSize = dp((22 * scale).toInt())
        groupMuteIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(muteSize, muteSize).apply { marginEnd = dp(4) }
            setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
            setColorFilter(if (muted) MusicPlayerStyles.ACCENT_COLOR else textSecondary())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener {
                val coord = coordinator
                markGroupVolumeChange()
                groupVolumeMuted = !groupVolumeMuted
                coord?.pinMuteState(groupVolumeMuted)  // Pin display mute state to prevent oscillation
                setImageResource(if (groupVolumeMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
                setColorFilter(if (groupVolumeMuted) MusicPlayerStyles.ACCENT_COLOR else textSecondary())
                coord?.markLocalVolumeChange()
                if (groupVolumeMuted) {
                    // Save per-speaker volumes and group pre-mute level
                    savePerSpeakerVolumes()
                    coord?.savePreMutePercent(groupVolumePercent)
                    coord?.groupVolumeMuted = true
                    groupVolumeValue?.text = "0"
                    setAllSpeakersMutedDisplay(true)
                    onGroupMuteToggle?.invoke(true)
                } else {
                    // Restore per-speaker volumes to their individual pre-mute levels.
                    // Falls back to single group volume if no snapshots available.
                    val restore = coord?.getPreMutePercent() ?: groupVolumePercent
                    groupVolumePercent = restore
                    coord?.groupVolumeMuted = false
                    coord?.groupVolumePercent = restore
                    groupVolumeValue?.text = "${volumeToScale(restore)}"
                    setAllSpeakersMutedDisplay(false)
                    onGroupMuteToggle?.invoke(false)
                    if (!restorePerSpeakerVolumes()) {
                        onGroupVolumeChange?.invoke(restore)
                    }
                }
            }
            box.addView(this)
        }

        // Minus button
        val btnSize = dp((24 * scale).toInt())
        val btnPad = dp(4)
        ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setImageResource(R.drawable.ic_remove)
            setColorFilter(textPrimary())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener {
                val coord = coordinator
                markGroupVolumeChange()
                val oldPercent = groupVolumePercent
                val currentScale = volumeToScale(groupVolumePercent)
                val newScale = (currentScale - 1).coerceIn(1, 10)  // Floor at 1 — use mute button to mute
                groupVolumePercent = scaleToVolume(newScale)
                coord?.groupVolumePercent = groupVolumePercent
                coord?.markLocalVolumeChange()
                groupVolumeValue?.text = "$newScale"
                // Use per-speaker commands with floor to prevent zeroing out speakers
                if (!applyGroupVolumeWithFloor(oldPercent, groupVolumePercent)) {
                    onGroupVolumeChange?.invoke(groupVolumePercent)
                }
            }
            box.addView(this)
        }

        // Volume value (1-10 scale) — use coordinator's scale function if available
        val coord = coordinator
        val displayScale = if (muted) 0 else (coord?.volumeScale(volume) ?: volumeToScale(volume))
        groupVolumeValue = TextView(context).apply {
            text = "$displayScale"
            setTextColor(textPrimary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (14f * scale) * sizeMultiplier)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp((28 * scale).toInt()), LinearLayout.LayoutParams.WRAP_CONTENT)
            box.addView(this)
        }

        // Plus button
        ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setImageResource(R.drawable.ic_add)
            setColorFilter(textPrimary())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true; isFocusable = true
            setOnClickListener {
                val coord2 = coordinator
                markGroupVolumeChange()
                val oldPercent = groupVolumePercent
                val currentScale = volumeToScale(groupVolumePercent)
                val newScale = (currentScale + 1).coerceIn(0, 10)
                groupVolumePercent = scaleToVolume(newScale)
                coord2?.groupVolumePercent = groupVolumePercent
                coord2?.markLocalVolumeChange()
                groupVolumeValue?.text = "$newScale"
                // Use per-speaker commands with floor to prevent zeroing out speakers
                if (!applyGroupVolumeWithFloor(oldPercent, groupVolumePercent)) {
                    onGroupVolumeChange?.invoke(groupVolumePercent)
                }
            }
            box.addView(this)
        }

        parent.addView(box)
    }

    /** Per-speaker volume snapshots saved before group mute.
     *  Map of playerId → volumePercent at time of mute. */
    private val preMuteSpeakerVolumes = mutableMapOf<String, Int>()

    /** Save each active speaker's current volume before a group mute. */
    fun savePerSpeakerVolumes() {
        preMuteSpeakerVolumes.clear()
        for ((id, item) in speakerItems) {
            if (item.isInGroup) {
                preMuteSpeakerVolumes[id] = item.volumePercent.coerceAtLeast(10)
            }
        }
    }

    /** Restore each speaker to its individual pre-mute volume.
     *  Returns true if per-speaker restore was performed, false if no snapshots available. */
    fun restorePerSpeakerVolumes(): Boolean {
        if (preMuteSpeakerVolumes.isEmpty()) return false
        for ((id, percent) in preMuteSpeakerVolumes) {
            val item = speakerItems[id]
            if (item != null) {
                item.volumePercent = percent
                item.isMuted = false
                // Fire the per-speaker volume and unmute callbacks → MA API
                onSpeakerVolumeChange?.invoke(id, percent)
                onSpeakerMuteToggle?.invoke(id, false)
            }
        }
        preMuteSpeakerVolumes.clear()
        return true
    }

    /** Apply a group volume change as individual per-speaker commands with a floor of 10%.
     *  Computes the ratio between old and new group volume and scales each speaker proportionally,
     *  clamping at MIN_SPEAKER_PERCENT so no speaker gets muted by a group volume decrease.
     *  Returns true if per-speaker commands were sent, false if no speaker items available. */
    fun applyGroupVolumeWithFloor(oldGroupPercent: Int, newGroupPercent: Int): Boolean {
        val activeItems = speakerItems.values.filter { it.isInGroup }
        if (activeItems.isEmpty()) return false
        val oldGroup = oldGroupPercent.coerceAtLeast(1)  // avoid /0
        for (item in activeItems) {
            val scaled = (item.volumePercent.toLong() * newGroupPercent / oldGroup).toInt()
            val clamped = scaled.coerceIn(MIN_SPEAKER_PERCENT, 100)
            item.volumePercent = clamped
            item.isMuted = false
            item.markLocalChange()
            onSpeakerVolumeChange?.invoke(item.playerId, clamped)
        }
        return true
    }

    /** Optimistically update all active speaker items to show muted/unmuted state. */
    fun setAllSpeakersMutedDisplay(muted: Boolean) {
        for ((_, item) in speakerItems) {
            if (item.isInGroup) item.setMutedDisplay(muted)
        }
    }

    /** Refresh the group volume tab from the coordinator's current state.
     *  Called by the strip renderer after volume/mute changes so the drawer stays in sync. */
    fun refreshGroupVolumeFromCoordinator() {
        val coord = coordinator ?: return
        if (isGroupVolumeDebounceActive()) return  // Don't overwrite if drawer itself just changed volume
        val muted = coord.getDisplayMuted()
        val pct = coord.groupVolumePercent.takeIf { it >= 0 } ?: return
        groupVolumePercent = pct
        groupVolumeMuted = muted
        val scale = if (muted) 0 else volumeToScale(pct)
        groupVolumeValue?.text = "$scale"
        groupMuteIcon?.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        groupMuteIcon?.setColorFilter(if (muted) MusicPlayerStyles.ACCENT_COLOR else textSecondary())
    }

    /** Returns display names of unavailable speakers that are active group members.
     *  Falls back to all unavailable speakers if group membership can't be resolved
     *  (e.g. sync groups whose member IDs aren't in allSpeakers). */
    fun getOfflineGroupMembers(targetEntityId: String = ""): List<String> {
        val targetId = targetEntityId.ifEmpty { selectedTargetId }

        // Try to resolve group members for the target
        if (targetId.isNotEmpty()) {
            val group = allGroups.find { it.groupId == targetId }
            if (group != null) {
                val offline = allSpeakers.filter { it.playerId in group.memberIds && !it.available }
                if (offline.isNotEmpty()) return offline.map { it.displayName }
            }
        }

        // Fallback: return all unavailable speakers (any could be the culprit)
        return allSpeakers.filter { !it.available }.map { it.displayName }
    }

    /** Find a speaker's display name by player ID (supports partial match for Sendspin "upXXX" IDs). */
    fun findSpeakerName(playerId: String): String? {
        return allSpeakers.find { it.playerId == playerId || it.playerId.contains(playerId) || playerId.contains(it.playerId) }
            ?.displayName
    }

    // ========== Public API ==========

    fun updateSpeaker(playerId: String, state: String, volume: Int, muted: Boolean, inGroup: Boolean) {
        speakerItems[playerId]?.update(state, volume, muted, inGroup)
    }

    fun updateSpeakerVolume(playerId: String, volumePercent: Int) {
        speakerItems[playerId]?.updateVolumeOnly(volumePercent)
    }

    fun getFormattedSpeakerBarText(): String {
        val currentId = resolveCurrentPlayerId()

        val group = allGroups.find { it.groupId == currentId }
        if (group != null) {
            val serverMembers = group.memberIds.toMutableSet()
            for ((pid, joined) in localMemberOverrides) {
                if (joined) serverMembers.add(pid) else serverMembers.remove(pid)
            }
            val text = group.displayName
            persistBarText(text, true)
            return text
        }
        // Single speaker — check for dynamic sync followers
        val currentSpeaker = allSpeakers.find { it.playerId == currentId }
        val syncFollowers = allSpeakers.count { it.syncedTo == currentId }
        val name = currentSpeaker?.displayName ?: return ""
        val text = if (syncFollowers > 0) "$name +$syncFollowers" else name
        persistBarText(text, syncFollowers > 0)
        return text
    }

    /** Persist the current bar text so it can be restored on next player open. */
    private fun persistBarText(text: String, isGroup: Boolean) {
        val count = getSpeakerCount()
        prefs.edit()
            .putString(KEY_LAST_BAR_TEXT, text)
            .putBoolean(KEY_LAST_IS_GROUP, isGroup)
            .putInt(KEY_LAST_SPEAKER_COUNT, count)
            .apply()
    }

    /** Get the last persisted speaker bar text (for restoring on player open). */
    fun getPersistedBarText(): String = prefs.getString(KEY_LAST_BAR_TEXT, "") ?: ""

    /** Get the last persisted speaker count (for badge on player open). */
    fun getPersistedSpeakerCount(): Int = prefs.getInt(KEY_LAST_SPEAKER_COUNT, 0)

    /** Whether the last persisted target was a group. */
    fun getPersistedIsGroup(): Boolean = prefs.getBoolean(KEY_LAST_IS_GROUP, false)

    /** Get the speaker count for the badge (0 = no badge). */
    fun getSpeakerCount(): Int {
        val currentId = resolveCurrentPlayerId()
        val group = allGroups.find { it.groupId == currentId }
        if (group != null) {
            val serverMembers = group.memberIds.toMutableSet()
            for ((pid, joined) in localMemberOverrides) {
                if (joined) serverMembers.add(pid) else serverMembers.remove(pid)
            }
            return serverMembers.size
        }
        // Ad-hoc sync: lead + followers
        val syncFollowers = allSpeakers.count { it.syncedTo == currentId }
        if (syncFollowers > 0) return 1 + syncFollowers
        return speakerItems.values.count { it.isInGroup }
    }

    /** Whether the current target is a group or ad-hoc sync lead (for speaker bar icon). */
    fun isCurrentTargetGroup(): Boolean {
        val currentId = resolveCurrentPlayerId()
        return allGroups.any { it.groupId == currentId }
            || allSpeakers.any { it.syncedTo == currentId }
    }

    fun resortAfterToggle() {
        rebuildRightColumn()
    }

    fun getBounds(card: View): Rect? {
        if (view.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val cardLoc = IntArray(2)
        card.getLocationInWindow(cardLoc)
        return Rect(
            loc[0] - cardLoc[0], loc[1] - cardLoc[1],
            loc[0] - cardLoc[0] + view.width, loc[1] - cardLoc[1] + view.height
        )
    }

    // ========== Data classes ==========

    data class SpeakerInfo(
        val playerId: String,
        val displayName: String,
        val state: String = "idle",
        val volumePercent: Int = 50,
        val isMuted: Boolean = false,
        val isInGroup: Boolean = false,
        val provider: String = "",
        val currentTrack: String = "",
        val currentArtist: String = "",
        val currentImageUrl: String = "",
        val currentDuration: Int = 0,
        val available: Boolean = true,
        val syncedTo: String = ""
    )

    data class GroupInfo(
        val groupId: String,
        val displayName: String,
        val groupType: String = "sync_group",
        val memberIds: List<String> = emptyList(),
        val staticMemberIds: List<String> = emptyList(),
        val state: String = "idle",
        val currentTrack: String = "",
        val currentArtist: String = "",
        val currentImageUrl: String = "",
        val currentDuration: Int = 0,
        val groupVolume: Int = -1,
        val groupVolumeMuted: Boolean = false
    )

    private data class TargetEntry(
        val id: String,
        val name: String,
        val type: String,
        val isCurrent: Boolean,
        val state: String = "idle",
        val currentTrack: String = "",
        val memberCount: Int = 0
    )
}
