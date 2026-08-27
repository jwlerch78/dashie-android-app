package com.dashieapp.Dashie.sidebar

import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import com.dashieapp.Dashie.R

/**
 * Handles volume popout logic for both tablet (slider) and TV (toggles) layouts.
 * Tablet layout: horizontal slider + step buttons (-/+) + mute/mic circle buttons.
 */
class VolumePopout(
    private val onVolumeChanged: (Int) -> Unit,
    private val onToggleMute: () -> Unit,
    private val onToggleMicMute: () -> Unit,
    private val onToggleAlertsMute: () -> Unit,
    private val onInteraction: () -> Unit
) {
    /**
     * Bind tablet volume popout with compact horizontal slider.
     */
    fun bindTablet(
        contentView: View,
        currentVolume: Int,
        isMuted: Boolean,
        isMicMuted: Boolean,
        isAlertsMuted: Boolean,
    ) {
        val slider = contentView.findViewById<SeekBar>(R.id.volumeSlider)
        val level = contentView.findViewById<TextView>(R.id.volumeLevel)
        val muteBtn = contentView.findViewById<ImageView>(R.id.volumeMuteBtn)
        val micBtn = contentView.findViewById<ImageView>(R.id.volumeMicBtn)
        val alertsBtn = contentView.findViewById<ImageView>(R.id.volumeAlertsBtn)
        val btnMinus = contentView.findViewById<View>(R.id.volumeBtnMinus)
        val btnPlus = contentView.findViewById<View>(R.id.volumeBtnPlus)

        slider.progress = currentVolume
        level.text = if (isMuted) "0" else currentVolume.toString()
        updateMuteIcon(muteBtn, isMuted)
        updateMicIcon(micBtn, isMicMuted)
        updateAlertsIcon(alertsBtn, isAlertsMuted)

        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    level.text = progress.toString()
                    onInteraction()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) { onInteraction() }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let { onVolumeChanged(it.progress) }
            }
        })

        btnMinus.setOnClickListener {
            onInteraction()
            val newVal = (slider.progress - 1).coerceAtLeast(0)
            slider.progress = newVal
            level.text = newVal.toString()
            onVolumeChanged(newVal)
        }

        btnPlus.setOnClickListener {
            onInteraction()
            val newVal = (slider.progress + 1).coerceAtMost(10)
            slider.progress = newVal
            level.text = newVal.toString()
            onVolumeChanged(newVal)
        }

        var localMuted = isMuted
        var localMicMuted = isMicMuted
        var localAlertsMuted = isAlertsMuted
        var preMuteVolume = currentVolume

        muteBtn.setOnClickListener {
            onInteraction()
            onToggleMute()
            localMuted = !localMuted
            updateMuteIcon(muteBtn, localMuted)
            if (localMuted) {
                preMuteVolume = slider.progress
                level.text = "0"
                slider.progress = 0
            } else {
                slider.progress = preMuteVolume
                level.text = preMuteVolume.toString()
                onVolumeChanged(preMuteVolume)
            }
        }

        micBtn.setOnClickListener {
            onInteraction()
            onToggleMicMute()
            localMicMuted = !localMicMuted
            updateMicIcon(micBtn, localMicMuted)
        }

        alertsBtn.setOnClickListener {
            onInteraction()
            onToggleAlertsMute()
            localAlertsMuted = !localAlertsMuted
            updateAlertsIcon(alertsBtn, localAlertsMuted)
        }
    }

    /**
     * Bind TV volume popout with toggle buttons.
     */
    fun bindTV(contentView: View, isMuted: Boolean, isMicMuted: Boolean) {
        val muteLabel = contentView.findViewById<TextView>(R.id.tvVolumeMuteLabel)
        val micLabel = contentView.findViewById<TextView>(R.id.tvVolumeMicLabel)

        muteLabel.text = if (isMuted) "Unmute Speaker" else "Mute Speaker"
        micLabel.text = if (isMicMuted) "Unmute Microphone" else "Mute Microphone"

        val muteBtn = contentView.findViewById<View>(R.id.tvVolumeBtnMute)
        muteBtn.setOnClickListener {
            onInteraction()
            onToggleMute()
        }
        contentView.findViewById<View>(R.id.tvVolumeBtnMicMute).setOnClickListener {
            onInteraction()
            onToggleMicMute()
        }
        // Default focus to Mute Speaker button for d-pad navigation
        muteBtn.post { muteBtn.requestFocus() }
    }

    private fun updateMuteIcon(icon: ImageView, muted: Boolean) {
        icon.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun updateMicIcon(icon: ImageView, muted: Boolean) {
        icon.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
    }

    private fun updateAlertsIcon(icon: ImageView, muted: Boolean) {
        icon.setImageResource(if (muted) R.drawable.ic_alert_bell_off else R.drawable.ic_alert_bell)
    }
}
