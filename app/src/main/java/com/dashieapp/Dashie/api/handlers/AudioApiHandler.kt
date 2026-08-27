package com.dashieapp.Dashie.api.handlers

import com.dashieapp.Dashie.api.DashieApiCallbacks
import fi.iki.elonen.NanoHTTPD

/**
 * Handles audio-related API endpoints for the Dashie REST API.
 *
 * Endpoints: setAudioVolume, textToSpeech, stopTextToSpeech,
 * playSound, stopSound, pauseSound, resumeSound, seekSound,
 * muteAudio, unmuteAudio
 */
class AudioApiHandler(
    private val callbacks: DashieApiCallbacks
) {
    /**
     * GET /?cmd=setAudioVolume&level=[0-100]&stream=[1-5]
     *
     * Stream types:
     * 1 = STREAM_VOICE_CALL
     * 2 = STREAM_SYSTEM
     * 3 = STREAM_RING
     * 4 = STREAM_MUSIC (default)
     * 5 = STREAM_ALARM
     */
    fun handleSetAudioVolume(params: Map<String, String>): NanoHTTPD.Response {
        val level = params["level"]?.toIntOrNull() ?: return errorResponse("Missing or invalid 'level' parameter")
        val stream = params["stream"]?.toIntOrNull() ?: 4 // Default to STREAM_MUSIC

        callbacks.setVolume(level, stream)
        return successResponse("Volume set to $level for stream $stream")
    }

    /**
     * GET /?cmd=textToSpeech&text=[text]
     *
     * Speak text using Android's built-in TTS engine.
     */
    fun handleTextToSpeech(params: Map<String, String>): NanoHTTPD.Response {
        val text = params["text"] ?: return errorResponse("Missing 'text' parameter")
        callbacks.speakText(text)
        return successResponse("Speaking: $text")
    }

    /**
     * GET /?cmd=stopTextToSpeech
     */
    fun handleStopTextToSpeech(): NanoHTTPD.Response {
        callbacks.stopSpeaking()
        return successResponse("TTS stopped")
    }

    /**
     * GET /?cmd=playSound&url=[url]
     */
    fun handlePlaySound(params: Map<String, String>): NanoHTTPD.Response {
        val url = params["url"] ?: return errorResponse("Missing 'url' parameter")
        callbacks.playSound(url)
        return successResponse("Playing sound: $url")
    }

    /**
     * GET /?cmd=stopSound
     */
    fun handleStopSound(): NanoHTTPD.Response {
        callbacks.stopSound()
        return successResponse("Sound stopped")
    }

    /**
     * GET /?cmd=pauseSound
     */
    fun handlePauseSound(): NanoHTTPD.Response {
        callbacks.pauseSound()
        return successResponse("Sound paused")
    }

    /**
     * GET /?cmd=resumeSound
     */
    fun handleResumeSound(): NanoHTTPD.Response {
        callbacks.resumeSound()
        return successResponse("Sound resumed")
    }

    /**
     * GET /?cmd=seekSound&position=[ms]
     */
    fun handleSeekSound(params: Map<String, String>): NanoHTTPD.Response {
        val position = params["position"]?.toIntOrNull()
            ?: return errorResponse("Missing or invalid 'position' parameter")
        callbacks.seekSound(position)
        return successResponse("Sound seeked to $position ms")
    }

    /**
     * GET /?cmd=muteAudio
     */
    fun handleMuteAudio(): NanoHTTPD.Response {
        callbacks.muteAudio(true)
        return successResponse("Audio muted")
    }

    /**
     * GET /?cmd=unmuteAudio
     */
    fun handleUnmuteAudio(): NanoHTTPD.Response {
        callbacks.muteAudio(false)
        return successResponse("Audio unmuted")
    }

    /**
     * GET /?cmd=setMediaInfo&title=[title]&artist=[artist]&album=[album]&imageUrl=[url]&duration=[ms]&entityId=[id]&maServerUrl=[url]
     *
     * Push track metadata from Music Assistant to the device.
     * This updates the on-screen music player and deviceInfo response.
     * entityId is the HA entity (e.g. media_player.sm_x200_speaker) for speaker transfer.
     * maServerUrl is the MA server's base URL for direct API calls (next/prev/etc).
     */
    fun handleSetMediaInfo(params: Map<String, String>): NanoHTTPD.Response {
        val title = params["title"] ?: ""
        val artist = params["artist"] ?: ""
        val album = params["album"] ?: ""
        val imageUrl = params["imageUrl"] ?: ""
        val durationMs = params["duration"]?.toIntOrNull() ?: 0
        val entityId = params["entityId"] ?: ""
        val maServerUrl = params["maServerUrl"] ?: ""

        callbacks.setMediaInfo(title, artist, album, imageUrl, durationMs, entityId, maServerUrl)
        return successResponse("Media info set: $title - $artist")
    }

    /**
     * Set this device's MA player ID and server URL. Called by the Dashie MA provider on first connect
     * so the device knows its own entity ID and MA server URL before any playback starts.
     * Params: playerId (e.g. "media_player.sm_x200_speaker"), maServerUrl (e.g. "http://192.168.1.50:8097")
     */
    fun handleSetPlayerId(params: Map<String, String>): NanoHTTPD.Response {
        val playerId = params["playerId"] ?: ""
        if (playerId.isEmpty()) {
            return errorResponse("Missing playerId parameter")
        }
        val maServerUrl = params["maServerUrl"] ?: ""
        callbacks.setPlayerId(playerId, maServerUrl)
        return successResponse("Player ID set: $playerId (server=$maServerUrl)")
    }
}
