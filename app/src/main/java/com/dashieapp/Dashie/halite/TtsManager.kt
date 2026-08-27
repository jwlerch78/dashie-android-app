package com.dashieapp.Dashie.halite

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.preferences.GeneralPreferences
import java.util.Locale

/**
 * Manages Text-to-Speech initialization for the Fully Kiosk API.
 *
 * Handles:
 * - TTS engine selection based on device type (Fire Tablet vs Fire TV vs other)
 * - Fallback to alternative engines when primary fails
 * - Language configuration
 * - Audio attribute setup for proper output routing
 *
 * Engine Priority:
 * - Fire Tablets: Ivona → Pico → Default → Google
 * - Fire TV: Pico → Ivona → Default → Google (Ivona often fails on TV sticks)
 * - Other devices: Default → Google → Pico → Samsung
 */
class TtsManager(
    private val context: Context,
    private val onTtsReady: (TextToSpeech) -> Unit
) {
    companion object {
        private const val TAG = "TtsManager"
        private const val INIT_DELAY_MS = 3000L
        private const val RETRY_DELAY_MS = 1500L
    }

    private var tts: TextToSpeech? = null
    private val handler = Handler(Looper.getMainLooper())

    /**
     * TTS engine order based on device type.
     */
    private val ttsEngines: List<String?> by lazy {
        val isAmazon = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        val isFireTV = isAmazon && (Build.MODEL.contains("AFT", ignoreCase = true) ||
                                     Build.MODEL.contains("Fire TV", ignoreCase = true))
        val isFireTablet = isAmazon && !isFireTV

        Log.i(TAG, "Device detection: isAmazon=$isAmazon, isFireTV=$isFireTV, isFireTablet=$isFireTablet, model=${Build.MODEL}")

        when {
            isFireTablet -> listOf(
                "com.ivona.tts.oem",      // Ivona first on Fire Tablets (better voice)
                "com.svox.pico",          // Pico fallback
                null,                     // Default engine
                "com.google.android.tts"
            )
            isFireTV -> listOf(
                "com.svox.pico",          // Pico first on Fire TV (Ivona often fails)
                "com.ivona.tts.oem",      // Ivona fallback
                null,                     // Default engine
                "com.google.android.tts"
            )
            else -> listOf(
                null,                     // Default engine first for non-Amazon devices
                "com.google.android.tts",
                "com.svox.pico",
                "com.samsung.SMT"
            )
        }
    }

    /**
     * Start TTS initialization.
     * Delays initialization to let the activity fully start.
     */
    fun initialize() {
        Log.i(TAG, "Initializing TTS...")
        listAvailableEngines()

        handler.postDelayed({
            tryInitWithEngineIndex(0)
        }, INIT_DELAY_MS)
    }

    /**
     * List all available TTS engines for debugging.
     */
    private fun listAvailableEngines() {
        try {
            val tempTts = TextToSpeech(context, null)
            handler.postDelayed({
                try {
                    val engines = tempTts.engines
                    val engineNames = engines.joinToString(", ") { it.name }
                    Log.i(TAG, "Available TTS engines (${engines.size}):")
                    DiagnosticBuffer.info("TTS", "Available engines (${engines.size}): $engineNames")
                    engines.forEach { engine ->
                        Log.i(TAG, "  - ${engine.name} (label: ${engine.label})")
                    }
                    tempTts.shutdown()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not list TTS engines: ${e.message}")
                    DiagnosticBuffer.warn("TTS", "Could not list engines: ${e.message}")
                }
            }, 500)
        } catch (e: Exception) {
            Log.w(TAG, "Could not create temp TTS: ${e.message}")
            DiagnosticBuffer.error("TTS", "Could not create temp TTS: ${e.message}")
        }
    }

    /**
     * Try to initialize TTS with the engine at the given index.
     * Falls back to next engine on failure.
     */
    private fun tryInitWithEngineIndex(engineIndex: Int) {
        if (engineIndex >= ttsEngines.size) {
            Log.e(TAG, "TTS initialization failed with all engines")
            DiagnosticBuffer.error("TTS", "Init failed with ALL engines on ${Build.MANUFACTURER} ${Build.MODEL}")
            return
        }

        val currentEngine = ttsEngines[engineIndex]
        Log.i(TAG, "Trying TTS engine [$engineIndex]: ${currentEngine ?: "default"}")

        try {
            // Shutdown any previous instance
            tts?.shutdown()
            tts = null

            // Create new TTS instance
            tts = if (currentEngine != null) {
                TextToSpeech(context, { status ->
                    handler.post {
                        handleInitResult(status, engineIndex)
                    }
                }, currentEngine)
            } else {
                TextToSpeech(context) { status ->
                    handler.post {
                        handleInitResult(status, engineIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS init exception with engine $currentEngine: ${e.message}")
            // Try next engine after delay
            handler.postDelayed({
                tryInitWithEngineIndex(engineIndex + 1)
            }, RETRY_DELAY_MS)
        }
    }

    /**
     * Handle TTS initialization result.
     */
    private fun handleInitResult(status: Int, engineIndex: Int) {
        val currentEngine = ttsEngines.getOrNull(engineIndex)
        Log.i(TAG, "TTS init result: status=$status (SUCCESS=${TextToSpeech.SUCCESS}, ERROR=${TextToSpeech.ERROR}) for engine ${currentEngine ?: "default"}")

        if (status == TextToSpeech.SUCCESS) {
            // Resolve the app Language setting to a locale this engine can speak
            // (falls back: configured language -> device locale -> English).
            val applied = tts?.let {
                TtsLocaleResolver.apply(it, GeneralPreferences(context).language)
            }
            Log.i(TAG, "TTS language applied: $applied")

            // If even the fallback locale isn't supported, try the next engine
            if (applied == null) {
                Log.w(TAG, "TTS language not supported by ${currentEngine ?: "default"}, trying next engine")
                tts?.shutdown()
                tts = null
                val nextIndex = engineIndex + 1
                if (nextIndex < ttsEngines.size) {
                    handler.postDelayed({
                        tryInitWithEngineIndex(nextIndex)
                    }, 500)
                } else {
                    Log.e(TAG, "❌ No TTS engine supports a usable language")
                    DiagnosticBuffer.error("TTS", "❌ No engine supports a usable language on ${Build.MANUFACTURER} ${Build.MODEL}")
                }
                return
            }

            // Set audio attributes to use STREAM_MUSIC (same stream as other audio)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)
                Log.i(TAG, "TTS audio attributes set to USAGE_MEDIA/CONTENT_TYPE_SPEECH")
            }

            // Notify callback
            tts?.let { onTtsReady(it) }
            Log.i(TAG, "✅ TTS initialized successfully with engine: ${currentEngine ?: "default"}")
            DiagnosticBuffer.info("TTS", "✅ Init OK: engine=${currentEngine ?: "default"}, device=${Build.MANUFACTURER} ${Build.MODEL}")
        } else {
            Log.w(TAG, "TTS init failed with engine ${currentEngine ?: "default"}, status=$status")
            DiagnosticBuffer.warn("TTS", "Init failed: engine=${currentEngine ?: "default"}, status=$status")

            // Shutdown the failed instance
            tts?.shutdown()
            tts = null

            // Try next engine after delay
            val nextIndex = engineIndex + 1
            if (nextIndex < ttsEngines.size) {
                handler.postDelayed({
                    tryInitWithEngineIndex(nextIndex)
                }, RETRY_DELAY_MS)
            } else {
                Log.e(TAG, "❌ TTS initialization failed with all engines")
                DiagnosticBuffer.error("TTS", "❌ All engines failed on ${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }
    }

    /**
     * Get the current TTS instance, if initialized.
     */
    fun getTts(): TextToSpeech? = tts

    /**
     * Shutdown TTS and release resources.
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        handler.removeCallbacksAndMessages(null)
    }
}
