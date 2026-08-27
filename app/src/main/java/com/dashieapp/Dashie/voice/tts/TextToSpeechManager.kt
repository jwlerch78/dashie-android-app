package com.dashieapp.Dashie.voice.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * TextToSpeechManager
 * Handles all Text-to-Speech functionality
 *
 * Extracted from VoiceAssistantManager for better separation of concerns
 */
class TextToSpeechManager(private val context: Context) {
    private val TAG = "TextToSpeechManager"
    private val handler = Handler(Looper.getMainLooper())

    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false

    // Callbacks
    var onReady: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingCompleted: (() -> Unit)? = null

    /**
     * Initialize Text-to-Speech engine
     */
    fun initialize() {
        Log.d(TAG, "Initializing TTS...")

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.let { tts ->
                    // Set default language
                    val result = tts.setLanguage(Locale.US)

                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "Language not supported")
                        handler.post {
                            onError?.invoke("Language not supported")
                        }
                    } else {
                        isInitialized = true
                        Log.d(TAG, "TTS initialized successfully")

                        // Set up progress listener
                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                Log.d(TAG, "TTS started speaking: $utteranceId")
                                handler.post {
                                    onSpeakingStarted?.invoke()
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                Log.d(TAG, "TTS finished speaking: $utteranceId")
                                handler.post {
                                    onSpeakingCompleted?.invoke()
                                }
                            }

                            override fun onError(utteranceId: String?) {
                                Log.e(TAG, "TTS error: $utteranceId")
                                handler.post {
                                    this@TextToSpeechManager.onError?.invoke("TTS playback error")
                                }
                            }
                        })

                        handler.post {
                            onReady?.invoke()
                        }
                    }
                }
            } else {
                Log.e(TAG, "TTS initialization failed")
                handler.post {
                    onError?.invoke("TTS initialization failed")
                }
            }
        }
    }

    /**
     * Speak text with customizable parameters
     */
    fun speak(
        text: String,
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
        utteranceId: String = "dashie_tts_${System.currentTimeMillis()}"
    ) {
        if (!isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak")
            onError?.invoke("TTS not ready")
            return
        }

        textToSpeech?.apply {
            setSpeechRate(rate)
            setPitch(pitch)

            Log.d(TAG, "Speaking: '$text' (rate=$rate, pitch=$pitch)")
            speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        }
    }

    /**
     * Stop speaking immediately
     */
    fun stopSpeaking() {
        if (textToSpeech?.isSpeaking == true) {
            Log.d(TAG, "Stopping TTS")
            textToSpeech?.stop()
        }
    }

    /**
     * Check if TTS is currently speaking
     */
    fun isSpeaking(): Boolean {
        return textToSpeech?.isSpeaking == true
    }

    /**
     * Get available voices
     */
    fun getAvailableVoices(): Set<Voice>? {
        return textToSpeech?.voices
    }

    /**
     * Set a specific voice
     */
    fun setVoice(voice: Voice): Boolean {
        return textToSpeech?.setVoice(voice) == TextToSpeech.SUCCESS
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down TTS")
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
    }
}
