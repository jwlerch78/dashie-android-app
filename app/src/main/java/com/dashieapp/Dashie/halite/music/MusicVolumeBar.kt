package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.content.res.ColorStateList
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R
import kotlin.math.roundToInt

/**
 * Compact volume control: [ speaker ] [ - ] [ value ] [ + ] centered in a row.
 * Speaker icon toggles mute (sends volume 0 / restores previous level).
 * +/- adjust volume in steps of 1 on a 0–10 scale (mapped to HA's 0.0–1.0).
 * Use scale=1f for normal player, scale=2f for maximized player.
 */
class MusicVolumeBar(
    private val context: Context,
    private val scale: Float = 1f,
    private val buttonSizeDp: Int? = null,  // Override button size (dp) to match playback buttons
    private val accentColor: Int = 0xFFFF9500.toInt(),
    private val textColor: Int = 0xB3FFFFFF.toInt(),
    private val iconColor: Int = 0xFFFFFFFF.toInt(),
    private val onVolumeChange: ((Float) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MusicVolumeBar"
        private const val VOLUME_STEP = 0.1f  // 1 on the 0–10 scale
    }

    private var currentLevel: Float = 0f
    private var isMuted: Boolean = false
    private var lastLocalVolumeChangeAt: Long = 0
    private var isUnavailable: Boolean = true  // Start unavailable until HA reports real volume
    private var preMuteLevel: Float = 0.5f
    private lateinit var valueText: TextView
    private lateinit var speakerIcon: ImageView
    private lateinit var minusBtn: ImageView
    private lateinit var plusBtn: ImageView
    // Derive grayed-out color from the passed-in iconColor with reduced alpha.
    // This ensures correct contrast regardless of system theme — the maximized player
    // always has a dark background, so we can't rely on isDarkMode(context).
    private val grayedOutColor = (iconColor and 0x00FFFFFF) or 0x60000000

    val view: LinearLayout = buildView()

    private fun dp(base: Int): Int {
        val scaled = (base * scale).toInt()
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, scaled.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun sp(base: Float): Float = base * scale

    private fun buildView(): LinearLayout {
        // When buttonSizeDp is set, use fixed dp sizes matching playback buttons
        val btnSize = if (buttonSizeDp != null)
            MusicPlayerStyles.dpToPx(context, buttonSizeDp) else dp(32)
        val speakerSize = if (buttonSizeDp != null)
            MusicPlayerStyles.dpToPx(context, buttonSizeDp) else dp(36)
        val btnPad = if (buttonSizeDp != null)
            MusicPlayerStyles.dpToPx(context, buttonSizeDp / 5) else dp(6)
        val speakerPad = if (buttonSizeDp != null)
            MusicPlayerStyles.dpToPx(context, buttonSizeDp / 8) else dp(4)
        val gap = if (buttonSizeDp != null)
            MusicPlayerStyles.dpToPx(context, buttonSizeDp / 4) else dp(12)
        val valueWidth = if (buttonSizeDp != null)
            MusicPlayerStyles.dpToPx(context, buttonSizeDp) else dp(36)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        speakerIcon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(speakerSize, speakerSize).apply {
                marginEnd = gap
            }
            setPadding(speakerPad, speakerPad, speakerPad, speakerPad)
            setImageResource(R.drawable.ic_volume_up)
            imageTintList = ColorStateList.valueOf(grayedOutColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleMute() }
        }
        row.addView(speakerIcon)

        minusBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setImageResource(R.drawable.ic_remove)
            imageTintList = ColorStateList.valueOf(grayedOutColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { adjustVolume(-VOLUME_STEP) }
        }
        row.addView(minusBtn)

        val textSizeSp = if (buttonSizeDp != null) (buttonSizeDp * 0.35f) else sp(14f)
        valueText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(valueWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
            setTextColor(grayedOutColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            text = "-"
        }
        row.addView(valueText)

        plusBtn = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setImageResource(R.drawable.ic_add)
            imageTintList = ColorStateList.valueOf(grayedOutColor)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { adjustVolume(VOLUME_STEP) }
        }
        row.addView(plusBtn)

        return row
    }

    private fun adjustVolume(delta: Float) {
        if (isUnavailable) {
            // HA doesn't report volume — start from 0.5 and track locally
            isUnavailable = false
            currentLevel = 0.5f
            valueText.setTextColor(textColor)
            speakerIcon.imageTintList = ColorStateList.valueOf(iconColor)
            minusBtn.imageTintList = ColorStateList.valueOf(iconColor)
            plusBtn.imageTintList = ColorStateList.valueOf(iconColor)
        }
        val newLevel = (currentLevel + delta).coerceIn(0f, 1f)
        currentLevel = newLevel
        isMuted = newLevel < 0.01f
        valueText.text = formatLevel(newLevel)
        updateSpeakerIcon(isMuted)
        lastLocalVolumeChangeAt = System.currentTimeMillis()
        onVolumeChange?.invoke(newLevel)
    }

    private fun toggleMute() {
        if (isUnavailable) {
            // HA doesn't report volume — start from 0.5 and track locally
            isUnavailable = false
            currentLevel = 0.5f
            valueText.setTextColor(textColor)
            speakerIcon.imageTintList = ColorStateList.valueOf(iconColor)
            minusBtn.imageTintList = ColorStateList.valueOf(iconColor)
            plusBtn.imageTintList = ColorStateList.valueOf(iconColor)
        }
        if (isMuted) {
            // Unmute: restore previous level
            currentLevel = preMuteLevel
            isMuted = false
            valueText.text = formatLevel(currentLevel)
            updateSpeakerIcon(false)
            onVolumeChange?.invoke(currentLevel)
        } else {
            // Mute: save current level, set to 0
            preMuteLevel = currentLevel.coerceAtLeast(0.1f)
            currentLevel = 0f
            isMuted = true
            valueText.text = formatLevel(0f)
            updateSpeakerIcon(true)
            onVolumeChange?.invoke(0f)
        }
        Log.i(TAG, "Mute toggled: $isMuted (preMuteLevel=$preMuteLevel)")
    }

    private fun updateSpeakerIcon(muted: Boolean) {
        speakerIcon.setImageResource(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
        )
    }

    private fun formatLevel(level: Float): String {
        return "${(level * 10).roundToInt()}"
    }

    /**
     * Update display from HA entity state (called on each state update).
     */
    fun updateVolume(level: Float, muted: Boolean, unavailable: Boolean = false) {
        // During debounce window, ignore ALL external volume updates (including unavailable flashes)
        val localAge = System.currentTimeMillis() - lastLocalVolumeChangeAt
        if (lastLocalVolumeChangeAt > 0 && localAge < 10_000) {
            if (unavailable || Math.abs(level - currentLevel) > 0.01f) return
        }
        if (localAge >= 10_000) lastLocalVolumeChangeAt = 0

        if (unavailable) {
            isUnavailable = true
            valueText.text = "-"
            valueText.setTextColor(grayedOutColor)
            speakerIcon.imageTintList = ColorStateList.valueOf(grayedOutColor)
            speakerIcon.setImageResource(R.drawable.ic_volume_up)
            minusBtn.imageTintList = ColorStateList.valueOf(grayedOutColor)
            plusBtn.imageTintList = ColorStateList.valueOf(grayedOutColor)
            return
        }
        // Volume became available — restore colors if needed
        if (isUnavailable) {
            isUnavailable = false
            valueText.setTextColor(textColor)
            speakerIcon.imageTintList = ColorStateList.valueOf(iconColor)
            minusBtn.imageTintList = ColorStateList.valueOf(iconColor)
            plusBtn.imageTintList = ColorStateList.valueOf(iconColor)
        }
        currentLevel = level
        isMuted = muted || level < 0.01f
        valueText.text = formatLevel(if (isMuted) 0f else level)
        updateSpeakerIcon(isMuted)
        if (!isMuted && level > 0.01f) {
            preMuteLevel = level
        }
    }

    fun destroy() {
        // No-op — no observers to clean up (HA volume is callback-based)
    }
}
