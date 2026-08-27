package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R
import kotlin.math.roundToInt

/**
 * Single speaker row in the group drawer.
 *
 * Layout: [checkbox] [state dot] [name]  [speaker icon] [-] [bar+value] [+]
 *
 * The volume control reuses the MusicVolumeBar pattern (speaker icon for mute,
 * +/- buttons, value display) but adds an orange progress bar behind the value
 * that grows with volume level for visual feedback.
 */
class SpeakerGroupItem(
    private val context: Context,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    val playerId: String,
    var displayName: String,
    var isInGroup: Boolean = false,
    var isHighlighted: Boolean = false,  // Orange background highlight for active speakers
    var isCompact: Boolean = false,      // Smaller font/icons for inactive speakers
    var state: String = "idle",        // "playing", "paused", "idle", "off"
    var volumePercent: Int = 50,
    var isMuted: Boolean = false,
    var isThisDevice: Boolean = false,
    var isManageMode: Boolean = false,    // Show minus button instead of checkbox
    var isLocked: Boolean = false,        // Static sync group member while playing — checkbox grayed out
    private val onToggleGroup: ((playerId: String, join: Boolean) -> Unit)? = null,
    private val onVolumeChange: ((playerId: String, percent: Int) -> Unit)? = null,
    private val onMuteToggle: ((playerId: String, muted: Boolean) -> Unit)? = null,
    private val onHideClicked: ((playerId: String) -> Unit)? = null
) {
    companion object {
        private const val VOLUME_STEP = 10  // percent per tap
        private const val ROW_HEIGHT_DP = 44
        private const val DOT_SIZE_DP = 10
        private const val CHECKBOX_SIZE_DP = 26
        private const val BTN_SIZE_DP = 30
    }

    private fun textPrimary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
        else MusicPlayerStyles.textPrimary(context)
    private fun textSecondary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_SECONDARY_DARK
        else MusicPlayerStyles.textSecondary(context)
    private fun dp(dp: Int): Int = (MusicPlayerStyles.dpToPx(context, dp) * sizeMultiplier).toInt()

    private lateinit var checkboxIcon: ImageView
    private lateinit var stateDot: View
    private lateinit var nameText: TextView
    private lateinit var speakerIcon: ImageView
    private lateinit var valueText: TextView
    private lateinit var progressBar: View
    private lateinit var progressContainer: FrameLayout
    private var preMuteVolume: Int = 50

    /** Timestamp of last local volume/mute change. Poll updates are skipped within debounce window. */
    private var lastLocalVolumeChangeAt: Long = 0
    fun markLocalChange() { lastLocalVolumeChangeAt = System.currentTimeMillis() }
    private fun isDebounceActive(): Boolean = lastLocalVolumeChangeAt > 0 && (System.currentTimeMillis() - lastLocalVolumeChangeAt) < 3000

    // Active speakers: +2dp on fonts/icons vs inactive (compact)
    private val nameSizeSp = if (isCompact) 12f else 14f
    private val volTextSizeSp = if (isCompact) 9f else 11f
    private val rowHeight = if (isCompact) 38 else ROW_HEIGHT_DP
    private val checkboxSize = if (isCompact) 22 else CHECKBOX_SIZE_DP
    private val buttonSize = if (isCompact) 26 else BTN_SIZE_DP

    val view: LinearLayout = buildView()

    private fun buildView(): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(rowHeight)
            )
            setPadding(dp(16), dp(2), dp(16), dp(2))
            // Orange highlight background for active/checked speakers
            if (isHighlighted) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(MusicPlayerStyles.ACCENT_COLOR and 0x30FFFFFF.toInt())
                }
            }
        }

        // Checkbox or minus button (manage mode)
        checkboxIcon = ImageView(context).apply {
            val size = dp(checkboxSize)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(6) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            if (isManageMode) {
                setImageResource(R.drawable.ic_remove)
                imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MusicPlayerStyles.ACCENT_COLOR)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { onHideClicked?.invoke(playerId) }
            } else if (isLocked && isInGroup) {
                // Locked AND in group — can't remove, show grayed out
                isClickable = false
                isFocusable = false
                alpha = 0.4f
            } else {
                // Normal toggle, OR locked but not in group (allow joining)
                if (isLocked) alpha = 0.6f  // Slightly dimmed to hint at locked state
                setOnClickListener {
                    if (isLocked && isInGroup) return@setOnClickListener  // Safety: can't remove locked speaker
                    isInGroup = !isInGroup
                    updateCheckbox()
                    onToggleGroup?.invoke(playerId, isInGroup)
                }
            }
        }
        row.addView(checkboxIcon)

        // State dot
        stateDot = View(context).apply {
            val size = dp(DOT_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(6) }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
            }
        }
        row.addView(stateDot)

        // Speaker name (fills available space)
        nameText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, nameSizeSp * sizeMultiplier)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        row.addView(nameText)

        // Volume control: [speaker] [-] [bar+value] [+]
        val btnSize = dp(buttonSize)
        val btnPad = dp(4)

        speakerIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply { marginStart = dp(12) }
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setImageResource(R.drawable.ic_volume_up)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleMute() }
        }
        row.addView(speakerIcon)

        val minusBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setImageResource(R.drawable.ic_remove)
            imageTintList = ColorStateList.valueOf(textPrimary())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { adjustVolume(-VOLUME_STEP) }
        }
        row.addView(minusBtn)

        // Progress bar + value overlay
        val barWidth = dp(44)
        val barHeight = dp(16)
        progressContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(barWidth, barHeight)
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(if (forceDarkColors) 0x33FFFFFF.toInt() else 0x1A000000.toInt())
            }
        }

        progressBar = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(MusicPlayerStyles.ACCENT_COLOR)
            }
        }
        progressContainer.addView(progressBar)

        valueText = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, volTextSizeSp * sizeMultiplier)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        progressContainer.addView(valueText)
        row.addView(progressContainer)

        val plusBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(textPrimary())
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { adjustVolume(VOLUME_STEP) }
        }
        row.addView(plusBtn)

        // Initial state
        if (!isManageMode) updateCheckbox()
        updateStateDot()
        updateNameText()
        updateVolume()

        return row
    }

    private fun adjustVolume(delta: Int) {
        markLocalChange()
        volumePercent = (volumePercent + delta).coerceIn(0, 100)
        isMuted = volumePercent == 0
        updateVolume()
        onVolumeChange?.invoke(playerId, volumePercent)
    }

    private fun toggleMute() {
        markLocalChange()
        if (isMuted) {
            volumePercent = preMuteVolume.coerceAtLeast(10)
            isMuted = false
        } else {
            preMuteVolume = volumePercent.coerceAtLeast(10)
            volumePercent = 0
            isMuted = true
        }
        updateVolume()
        onMuteToggle?.invoke(playerId, isMuted)
    }

    fun update(newState: String, newVolume: Int, newMuted: Boolean, newInGroup: Boolean) {
        state = newState
        isInGroup = newInGroup
        updateCheckbox()
        updateStateDot()
        // Skip volume overwrite from poll if user recently changed volume locally
        if (!isDebounceActive()) {
            volumePercent = newVolume
            isMuted = newMuted || newVolume == 0
            updateVolume()
        }
    }

    /** Update locked state (static sync group member while playing).
     *  Locked speakers that are in the group can't be removed (grayed out).
     *  Locked speakers that are NOT in the group (e.g., came back online) can be added. */
    fun updateLocked(locked: Boolean) {
        if (locked == isLocked) return
        isLocked = locked
        checkboxIcon?.apply {
            if (locked && isInGroup) {
                // In group + locked — prevent removal
                isClickable = false
                isFocusable = false
                alpha = 0.4f
            } else {
                // Not locked, or locked but not in group — allow toggle (join)
                isClickable = true
                isFocusable = true
                alpha = if (locked) 0.6f else 1.0f
                setOnClickListener {
                    if (isLocked && isInGroup) return@setOnClickListener
                    isInGroup = !isInGroup
                    updateCheckbox()
                    onToggleGroup?.invoke(playerId, isInGroup)
                }
            }
        }
    }

    /** Optimistically show muted/unmuted state (e.g. from group mute toggle).
     *  Updates display only — does not fire callbacks or mark debounce. */
    fun setMutedDisplay(muted: Boolean) {
        if (muted) {
            if (!isMuted) preMuteVolume = volumePercent.coerceAtLeast(10)
            isMuted = true
            volumePercent = 0
        } else {
            isMuted = false
            volumePercent = preMuteVolume.coerceAtLeast(10)
        }
        markLocalChange()
        updateVolume()
    }

    /** Update only the volume display (e.g. from main player volume change). */
    fun updateVolumeOnly(newVolume: Int) {
        if (isDebounceActive()) return
        volumePercent = newVolume
        isMuted = newVolume == 0
        updateVolume()
    }

    private fun updateCheckbox() {
        if (isManageMode) {
            // Manage mode: orange circle with white minus (hide button)
            checkboxIcon.setImageResource(R.drawable.ic_remove)
            checkboxIcon.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
            checkboxIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            checkboxIcon.setPadding(dp(6), dp(6), dp(6), dp(6))
            checkboxIcon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MusicPlayerStyles.ACCENT_COLOR)
            }
        } else {
            checkboxIcon.setImageResource(
                if (isInGroup) R.drawable.ic_check_box else R.drawable.ic_check_box_outline
            )
            checkboxIcon.imageTintList = ColorStateList.valueOf(
                if (isInGroup) MusicPlayerStyles.ACCENT_COLOR else textSecondary()
            )
            checkboxIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            checkboxIcon.setPadding(0, 0, 0, 0)
            checkboxIcon.background = null
        }
    }

    private fun updateStateDot() {
        val color = when (state) {
            "playing" -> 0xFF4CAF50.toInt()  // green
            "paused" -> 0xFFFF9800.toInt()   // orange
            else -> 0xFF888888.toInt()        // gray
        }
        (stateDot.background as? GradientDrawable)?.setColor(color)
    }

    private fun updateNameText() {
        val suffix = if (isThisDevice) " (This device)" else ""
        nameText.text = "$displayName$suffix"
        nameText.setTextColor(if (isInGroup) textPrimary() else textSecondary())
    }

    private fun updateVolume() {
        val displayVol = if (isMuted) 0 else if (volumePercent > 0) ((volumePercent / 10.0) + 0.5).toInt().coerceIn(1, 10) else 0
        valueText.text = "$displayVol"
        valueText.setTextColor(if (isMuted) textSecondary() else textPrimary())

        // Update progress bar width proportionally
        progressContainer.post {
            val totalWidth = progressContainer.width
            val fillWidth = (totalWidth * displayVol / 10).coerceAtLeast(0)
            progressBar.layoutParams = FrameLayout.LayoutParams(fillWidth, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Update speaker icon
        speakerIcon.setImageResource(
            if (isMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
        speakerIcon.imageTintList = ColorStateList.valueOf(
            if (isMuted) textSecondary() else textPrimary()
        )
    }
}
