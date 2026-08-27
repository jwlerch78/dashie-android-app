package com.dashieapp.Dashie.sidebar

import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Handles brightness popout logic for both tablet (slider) and TV (toggle) layouts.
 * Tablet layout: horizontal slider + step buttons (-/+) + dark mode toggle.
 * Uses 0-10 scale (each step = 10% brightness).
 */
class BrightnessPopout(
    private val onBrightnessChanged: (Int) -> Unit,
    private val onToggleDarkMode: () -> Boolean,
    private val onInteraction: () -> Unit,
    private val canWriteBrightnessSettings: () -> Boolean = { true },
    private val onBrightnessPermissionNeeded: () -> Unit = {}
) {
    /**
     * Bind tablet brightness popout with compact horizontal slider.
     * @param currentBrightness 0-100 percent value (converted to 0-10 for display)
     */
    fun bindTablet(contentView: View, currentBrightness: Int, isDarkMode: Boolean) {
        val slider = contentView.findViewById<SeekBar>(R.id.brightnessSlider)
        val level = contentView.findViewById<TextView>(R.id.brightnessLevel)
        val darkModeLabel = contentView.findViewById<TextView>(R.id.brightnessDarkModeLabel)
        val btnMinus = contentView.findViewById<View>(R.id.brightnessBtnMinus)
        val btnPlus = contentView.findViewById<View>(R.id.brightnessBtnPlus)

        // Convert 0-100 to 0-10 for slider
        val sliderVal = (currentBrightness / 10).coerceIn(0, 10)
        slider.progress = sliderVal
        level.text = sliderVal.toString()
        darkModeLabel.text = if (isDarkMode) "Light" else "Dark"

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (!canWriteBrightnessSettings()) {
                        seekBar?.progress = sliderVal
                        level.text = sliderVal.toString()
                        onBrightnessPermissionNeeded()
                        return
                    }
                    level.text = progress.toString()
                    onInteraction()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { onInteraction() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                if (!canWriteBrightnessSettings()) return
                seekBar?.let { onBrightnessChanged(it.progress * 10) }
            }
        })

        btnMinus.setOnClickListener {
            if (!canWriteBrightnessSettings()) { onBrightnessPermissionNeeded(); return@setOnClickListener }
            onInteraction()
            val newVal = (slider.progress - 1).coerceAtLeast(0)
            slider.progress = newVal
            level.text = newVal.toString()
            onBrightnessChanged(newVal * 10)
        }

        btnPlus.setOnClickListener {
            if (!canWriteBrightnessSettings()) { onBrightnessPermissionNeeded(); return@setOnClickListener }
            onInteraction()
            val newVal = (slider.progress + 1).coerceAtMost(10)
            slider.progress = newVal
            level.text = newVal.toString()
            onBrightnessChanged(newVal * 10)
        }

        var localDark = isDarkMode

        contentView.findViewById<View>(R.id.brightnessBtnDarkMode).setOnClickListener {
            onInteraction()
            val toggled = onToggleDarkMode()
            if (toggled) {
                localDark = !localDark
                darkModeLabel.text = if (localDark) "Light" else "Dark"
            }
        }
    }

    /**
     * Bind TV brightness popout with dark/light toggle only.
     */
    fun bindTV(contentView: View, isDarkMode: Boolean) {
        val darkModeLabel = contentView.findViewById<TextView>(R.id.tvBrightnessDarkModeLabel)
        darkModeLabel.text = if (isDarkMode) "Light Mode" else "Dark Mode"

        contentView.findViewById<View>(R.id.tvBrightnessBtnDarkMode).setOnClickListener {
            onInteraction()
            onToggleDarkMode()
        }
    }
}
