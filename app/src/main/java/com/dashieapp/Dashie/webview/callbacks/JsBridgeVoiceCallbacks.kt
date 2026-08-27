package com.dashieapp.Dashie.webview.callbacks

import android.util.Log
import com.dashieapp.Dashie.halite.voice.HaliteVoiceController
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeVoiceCallbacks(
    private val voiceControllerProvider: () -> HaliteVoiceController?
) : DashieJSBridge.VoiceCallbacks {

    override fun onVoicePipelineModeChanged() {
        Log.i("DashieJSBridge", "🎤 Voice pipeline mode changed - reinitializing voice")
        voiceControllerProvider()?.reinitialize()
    }

    override fun onResponseHandlingChanged(showResponses: Boolean) {
        Log.i("DashieJSBridge", "🔊 Response handling changed - showResponses=$showResponses")
        voiceControllerProvider()?.setShowResponses(showResponses)
    }

    override fun onMicMutedChanged(muted: Boolean) {
        voiceControllerProvider()?.setMicMuted(muted)
    }

    override fun onSampleCollectionChanged(enabled: Boolean) {
        voiceControllerProvider()?.setSampleCollectionEnabled(enabled)
    }

    override fun onRequestMicrophonePermission() {
        voiceControllerProvider()?.checkPermissionAndInit()
    }

    override fun onShowVoiceNotice(message: String, detail: String) {
        voiceControllerProvider()?.showVoiceNotice(message, detail)
    }

    override fun onShowHaCommandResult(message: String, command: String) {
        voiceControllerProvider()?.showHaCommandResult(message, command)
    }
}
