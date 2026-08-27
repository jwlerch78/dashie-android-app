package com.dashieapp.Dashie.webview.callbacks

import com.dashieapp.Dashie.halite.videofeed.VideoFeedOverlayManager
import com.dashieapp.Dashie.halite.preferences.VideoFeedPreferences
import com.dashieapp.Dashie.halite.voice.HaliteVoiceController
import com.dashieapp.Dashie.webview.DashieJSBridge

class JsBridgeVideoFeedCallbacks(
    private val videoFeedManagerProvider: () -> VideoFeedOverlayManager?,
    private val videoFeedPrefsProvider: () -> VideoFeedPreferences?,
    private val runOnUiThread: (Runnable) -> Unit,
    /** Only the native side knows whether Frigate actually HAS footage at the requested
     *  time (the JS handler has already spoken by then), so the miss is reported here. */
    private val voiceControllerProvider: () -> HaliteVoiceController? = { null }
) : DashieJSBridge.VideoFeedCallbacks {

    override fun isVideoFeedsEnabled(): Boolean =
        videoFeedPrefsProvider()?.enabled ?: false

    override fun areVideoFeedsPaused(): Boolean =
        videoFeedManagerProvider()?.isPaused ?: false

    override fun pauseVideoFeeds() {
        runOnUiThread { videoFeedManagerProvider()?.pause() }
    }

    override fun resumeVideoFeeds() {
        runOnUiThread { videoFeedManagerProvider()?.resume() }
    }

    override fun getActiveVideoFeedRuleIds(): String {
        return videoFeedManagerProvider()?.getActiveRuleIds()
            ?.let { org.json.JSONArray(it).toString() } ?: "[]"
    }

    override fun showVideoFeedByRuleId(ruleId: String) {
        runOnUiThread { videoFeedManagerProvider()?.showFeedByRuleId(ruleId) }
    }

    override fun dismissVideoFeedByRuleId(ruleId: String) {
        runOnUiThread { videoFeedManagerProvider()?.dismissFeed(ruleId) }
    }

    /** "show me the pool camera" (web JS voice lane). The strip-tap experience:
     *  opens the video strip with the feed focused in the focal drawer — same as
     *  opening the strip and selecting the feed by hand (2026-07-20: not the
     *  PiP alert card, not the old lightweight drawer). Matches the native voice
     *  lane (VideoFeedVoiceTool). Method name kept for old-JS bridge compat. */
    override fun showVideoFeedFocal(ruleId: String) {
        runOnUiThread { videoFeedManagerProvider()?.showStripWithFocalFeed(ruleId) }
    }

    /** "hide the pool camera" — handles the focal drawer AND the PiP card; dismissFeed()
     *  alone leaves a voice-opened drawer on screen. */
    override fun dismissVideoFeedByVoice(ruleId: String) {
        runOnUiThread { videoFeedManagerProvider()?.dismissFeedByVoice(ruleId) }
    }

    /** "show me the pool camera from ten minutes ago" — full-screen Frigate playback, seeked. */
    override fun showVideoFeedPlaybackAt(ruleId: String, timestampSec: Double) {
        runOnUiThread {
            videoFeedManagerProvider()?.showFeedPlaybackAt(
                ruleId,
                timestampSec.toLong(),
                onNoFootage = {
                    voiceControllerProvider()?.showVoiceNotice(
                        "No recording from then",
                        "Showing the live view instead"
                    )
                }
            )
        }
    }

    override fun areVideoFeedAlertsMuted(): Boolean =
        videoFeedManagerProvider()?.isAlertsMuted ?: false

    override fun setVideoFeedAlertsMuted(muted: Boolean) {
        videoFeedManagerProvider()?.isAlertsMuted = muted
    }

    override fun getVideoFeedLayout(): String =
        videoFeedManagerProvider()?.feedLayout ?: "grid"

    override fun cycleVideoFeedLayout(): String {
        val mgr = videoFeedManagerProvider() ?: return "sidebar"
        val next = if (mgr.feedLayout == "sidebar") "float" else "sidebar"
        runOnUiThread { mgr.setFeedLayoutAndApply(next) }
        return next
    }

    override fun setVideoFeedMenuOpen(open: Boolean) {
        runOnUiThread { videoFeedManagerProvider()?.setMenuOpen(open) }
    }
}
