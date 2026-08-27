package com.dashieapp.Dashie.sidebar.callbacks

import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.sidebar.CameraPopout
import com.dashieapp.Dashie.sidebar.NativeSidebarController

class SidebarVideoFeedCallbacks(
    private val registryProvider: () -> HaliteComponentRegistry?,
    private val runOnUiThread: (Runnable) -> Unit
) : NativeSidebarController.VideoFeedControlCallbacks {

    override fun isVideoFeedsEnabled(): Boolean =
        registryProvider()?.prefs?.videoFeed?.enabled ?: false

    override fun areVideoFeedsPaused(): Boolean =
        registryProvider()?.videoFeedManager?.isPaused ?: false

    override fun togglePauseVideoFeeds() {
        runOnUiThread {
            val mgr = registryProvider()?.videoFeedManager ?: return@runOnUiThread
            if (mgr.isPaused) mgr.resume() else mgr.pause()
        }
    }

    override fun areVideoFeedAlertsMuted(): Boolean =
        registryProvider()?.videoFeedManager?.isAlertsMuted ?: false

    override fun toggleMuteVideoFeedAlerts() {
        val mgr = registryProvider()?.videoFeedManager ?: return
        mgr.isAlertsMuted = !mgr.isAlertsMuted
    }

    override fun cycleVideoFeedLayout() {
        runOnUiThread {
            val mgr = registryProvider()?.videoFeedManager ?: return@runOnUiThread
            val next = if (mgr.feedLayout == "sidebar") "float" else "sidebar"
            mgr.setFeedLayoutAndApply(next)
        }
    }

    override fun getVideoFeedLayout(): String =
        registryProvider()?.videoFeedManager?.feedLayout ?: "sidebar"

    override fun refreshVideoFeedAvailability(onComplete: () -> Unit) {
        registryProvider()?.prefs?.videoFeed?.pullFeedsFromHa { onComplete() } ?: onComplete()
    }

    override fun getVideoFeedList(): List<CameraPopout.FeedInfo> {
        val prefs = registryProvider()?.prefs?.videoFeed ?: return emptyList()
        val mgr = registryProvider()?.videoFeedManager ?: return emptyList()
        val activeIds = mgr.getActiveRuleIds().toSet()
        return prefs.getEnabledRules().mapNotNull { rule ->
            val ruleId = rule.optString("id", "")
            if (ruleId.isEmpty()) return@mapNotNull null
            val label = rule.optString("cameraName", "Feed")
            CameraPopout.FeedInfo(
                ruleId = ruleId,
                label = label,
                isActive = ruleId in activeIds,
                isAvailable = rule.optBoolean("available", true)
            )
        }
    }

    override fun showVideoFeed(ruleId: String) {
        runOnUiThread { registryProvider()?.videoFeedManager?.showFeedByRuleId(ruleId) }
    }

    override fun dismissVideoFeed(ruleId: String) {
        runOnUiThread { registryProvider()?.videoFeedManager?.dismissFeed(ruleId) }
    }

    override fun setVideoFeedMenuOpen(open: Boolean) {
        runOnUiThread { registryProvider()?.videoFeedManager?.setMenuOpen(open) }
    }

    override fun toggleVideoFeedStrip() {
        runOnUiThread { registryProvider()?.videoFeedManager?.toggleStrip() }
    }
}
