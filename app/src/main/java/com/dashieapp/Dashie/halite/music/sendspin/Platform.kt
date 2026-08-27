package com.dashieapp.Dashie.halite.music.sendspin

import android.os.Build
import android.os.SystemClock

/**
 * Platform utilities for Sendspin protocol.
 * Adapted from SendSpinDroid's multiplatform Platform expect/actual.
 */
object Platform {
    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    fun currentTimeMillis(): Long = System.currentTimeMillis()
    fun base64Decode(input: String): ByteArray =
        // android.util.Base64, not java.util.Base64: the latter is API 26+ and
        // this app ships minSdk 23 with no core-library desugaring — any Sendspin
        // session on an API 23-25 device (Fire OS 5) crashed with NoClassDefFoundError.
        android.util.Base64.decode(input, android.util.Base64.DEFAULT)
    fun manufacturer(): String = Build.MANUFACTURER
}
