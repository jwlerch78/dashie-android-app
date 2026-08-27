package com.dashieapp.Dashie.devtools

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dashieapp.Dashie.audio.ContinuousCaptureRecorder

/**
 * Dev-only Continuous Audio Capture — starts/stops [ContinuousCaptureRecorder],
 * which taps the wake-word detector's exact input stream and writes rolling WAV
 * segments on device. Run it during a Zoom/speakerphone call to harvest real
 * mic-path VoIP audio for training negatives (staging/local flavors only).
 *
 * Requires voice detection to be running (that's what feeds the mic stream) — if
 * the size stays at 0 MB while recording, enable voice/wake-word first.
 */
class ContinuousCaptureActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            status.text = ContinuousCaptureRecorder.statusLine()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Continuous Audio Capture (dev)"

        val pad = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // Explicit back — kiosk/TV surfaces often have no system back gesture, and
        // finishing here does NOT stop recording (the recorder is a process singleton),
        // so you can start capture, return to settings, and it keeps running.
        val back = Button(this).apply {
            text = "← Back"
            setOnClickListener { finish() }
        }

        status = TextView(this).apply {
            text = ContinuousCaptureRecorder.statusLine()
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }

        toggle = Button(this).apply {
            text = if (ContinuousCaptureRecorder.enabled) "Stop" else "Start"
            setOnClickListener {
                if (ContinuousCaptureRecorder.enabled) {
                    ContinuousCaptureRecorder.stop(); text = "Start"
                } else {
                    ContinuousCaptureRecorder.start(applicationContext); text = "Stop"
                }
                status.text = ContinuousCaptureRecorder.statusLine()
            }
        }

        val hint = TextView(this).apply {
            text = "Records the detector's exact mic input (16 kHz mono) in 5-min WAV " +
                "segments, capped at 2 GB.\n\nRequires voice detection to be running.\n\n" +
                "Pull with:\nadb pull ${ContinuousCaptureRecorder.outputDirPath()}"
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }

        root.addView(back)
        root.addView(status)
        root.addView(toggle)
        root.addView(hint)
        setContentView(root)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }
}
