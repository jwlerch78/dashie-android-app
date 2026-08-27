package com.dashieapp.Dashie.wakeword.microwakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug receiver to trigger MWW WAV file test via adb.
 *
 * Usage:
 *   adb push test.wav /sdcard/Download/test.wav
 *   adb shell am broadcast -a com.dashieapp.MWW_TEST -e wav "/sdcard/Download/test.wav"
 *
 * Then read logcat:
 *   adb logcat -s MWWWavTest
 */
class MicroWakeWordTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val wavPath = intent.getStringExtra("wav")
        if (wavPath == null) {
            Log.e("MWWTestReceiver", "Missing 'wav' extra. Usage: adb shell am broadcast -a com.dashieapp.MWW_TEST -e wav /path/to/file.wav")
            return
        }

        val model = intent.getStringExtra("model") ?: "models/mww/hey_dashie.tflite"
        Log.i("MWWTestReceiver", "Starting MWW test: wav=$wavPath model=$model")

        // Run on background thread to avoid blocking
        Thread {
            MicroWakeWordWavTest.processWavFile(context, wavPath, model)
        }.start()
    }
}
