package com.dashieapp.Dashie.halite.timer

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelCoordinator
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPanelParticipant
import com.dashieapp.Dashie.halite.audio.AlertSoundService
import com.dashieapp.Dashie.halite.notify.FamilyAlertNotifier
import com.dashieapp.Dashie.webview.DashieJSBridge
import org.json.JSONObject

/**
 * Manages all timer overlay cards.
 * Handles creation, updates, removal, position persistence, and stacking.
 *
 * Now uses Activity-level views (ViewGroup) instead of WindowManager overlays,
 * eliminating the need for SYSTEM_ALERT_WINDOW permission.
 *
 * Implements [ScreensaverPanelParticipant] to show timer cards on the
 * screensaver overlay panel when the screensaver is active.
 */
class TimerOverlayManager(
    private val context: Context,
    private val overlayContainer: ViewGroup,
    private val prefs: HalitePreferences,
    private val jsBridgeProvider: () -> DashieJSBridge?,
    private val alertSoundService: AlertSoundService,
    /** Visibility gate — registers each TimerCard as OVERLAY_KIOSK_OR_FULL
     *  on creation, unregisters on dismissal. Force-hides cards on
     *  transition to OFF mode (login / logout) but doesn't force them
     *  visible in FULL/KIOSK — manager controls when to show. The
     *  underlying timer countdown is in-memory data and keeps ticking
     *  regardless of card visibility (see _TECHNICAL_DEBT.md for the
     *  app-restart persistence story). */
    private val visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null
) : ScreensaverPanelParticipant {
    companion object {
        private const val TAG = "TimerOverlayMgr"
        private const val INITIAL_X = 50 // Top-right corner (from right edge)
        private const val INITIAL_Y = 50 // Top margin
        private const val STACK_SPACING = 12 // dp spacing between stacked timers
    }

    // Re-resolve the JS bridge each call. After a memory-recovery WebView
    // recreation, MainWebViewRecreation builds a fresh DashieJSBridge — a
    // captured `private val jsBridge` would point at the destroyed bridge
    // and every minimize/expand/cancel would silently no-op. Provider
    // returns the current registry.jsBridge so calls always reach the live
    // bridge connected to the live WebView.
    private val jsBridge: DashieJSBridge?
        get() = jsBridgeProvider()

    private val timerCards = mutableMapOf<String, TimerCard>()

    /** Timer ids we've already sent a "timer done" family push for. updateTimer re-enters
     *  on every tick while the alarm rings, so we dedupe on the RUNNING→COMPLETED transition
     *  and clear the id in removeTimer so a reused slot can notify again. */
    private val notifiedTimerCompletions = mutableSetOf<String>()

    /** Screensaver panel coordinator — set after init by HaliteComponentRegistry */
    var coordinator: ScreensaverPanelCoordinator? = null

    /** Timer IDs whose cards are currently on the screensaver panel */
    private val cardsOnPanel = mutableSetOf<String>()

    // ── ScreensaverPanelParticipant ─────────────────────────────────

    override val participantId: String = "timer"
    override val participantPriority: Int = 0  // Highest priority (top of panel)

    override fun getScreensaverCardCount(): Int =
        cardsOnPanel.count { timerCards.containsKey(it) }

    override fun getScreensaverCards(): List<View> =
        cardsOnPanel.mapNotNull { timerCards[it] }

    override fun onPanelItemCountChanged(totalItems: Int) {
        if (cardsOnPanel.isEmpty()) return
        // Minimize timer cards when 3+ items on panel, expand otherwise
        for (id in cardsOnPanel) {
            val card = timerCards[id] ?: continue
            val data = getTimerData(card) ?: continue
            if (totalItems >= 3 && !data.isMinimized) {
                jsBridge?.minimizeTimer(id)
            } else if (totalItems < 3 && data.isMinimized) {
                jsBridge?.expandTimer(id)
            }
        }
    }

    // Aggregate timer state tracked for voice query access (updated on every create/update/remove)
    private var _hasAlarmPlaying = false
    private var _firstActiveRemaining: Int? = null

    /** True if any timer is in COMPLETED state (alarm ringing) */
    fun hasAlarmPlaying(): Boolean = _hasAlarmPlaying

    /** Remaining seconds of the first RUNNING timer, or null if none */
    fun getFirstActiveTimerRemaining(): Int? = _firstActiveRemaining

    /** Recalculate aggregate state from all tracked timer data */
    private fun refreshAggregateState(latestData: TimerData? = null) {
        // Use latestData if provided (avoids reflection for the just-updated card)
        // Fall back to reflection for other cards
        var foundAlarm = false
        var firstRemaining: Int? = null

        if (latestData != null) {
            if (latestData.state == TimerState.COMPLETED) foundAlarm = true
            if ((latestData.state == TimerState.RUNNING || latestData.state == TimerState.PAUSED) && firstRemaining == null) {
                firstRemaining = latestData.remainingSeconds
            }
        }

        // Check remaining cards (skip latestData's ID to avoid double-counting)
        for ((id, card) in timerCards) {
            if (latestData != null && id == latestData.id) continue
            val data = getTimerData(card) ?: continue
            if (data.state == TimerState.COMPLETED) foundAlarm = true
            if ((data.state == TimerState.RUNNING || data.state == TimerState.PAUSED) && firstRemaining == null) {
                firstRemaining = data.remainingSeconds
            }
        }

        _hasAlarmPlaying = foundAlarm
        _firstActiveRemaining = firstRemaining
    }

    /**
     * Create and display a new timer card
     */
    fun createTimer(timerData: TimerData) {
        if (timerCards.containsKey(timerData.id)) {
            Log.w(TAG, "Timer ${timerData.id} already exists, updating instead")
            updateTimer(timerData)
            return
        }

        Log.i(TAG, "⏱️ Creating timer card: id=${timerData.id}, slot=${timerData.slot}, label=${timerData.label}")

        val card = TimerCard(
            context = context,
            overlayContainer = overlayContainer,
            initialData = timerData,
            onPositionChanged = ::saveTimerPosition,
            onPauseResume = ::handlePauseResume,
            onCancel = ::handleCancel,
            onMinimizeExpand = ::handleMinimizeExpand
        )
        // Register with the gate — force-hides on OFF mode (login screen /
        // logout). Countdown keeps running in TimerCard's internal state.
        visibilityGate?.register(
            card,
            com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )

        // Decide target container: screensaver panel if active, else overlay container
        val targetPanel = if (coordinator?.isScreensaverActive == true) coordinator?.getPanel() else null

        if (targetPanel != null) {
            // Add to screensaver panel — coordinator handles layout
            try {
                targetPanel.addView(card, card.cardLayoutParams)
                timerCards[timerData.id] = card
                cardsOnPanel.add(timerData.id)
                refreshAggregateState(timerData)
                coordinator?.notifyCardAdded()
                Log.i(TAG, "⏱️ Timer card added to screensaver panel: ${timerData.id}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to add timer card to screensaver panel", e)
            }
        } else {
            // Get saved position or calculate initial stacked position
            val position = getSavedPosition(timerData.id) ?: calculateInitialPosition()

            // Set position FIRST (sets margins in layoutParams)
            card.setPosition(position.first, position.second)

            // Add to overlay container (no SYSTEM_ALERT_WINDOW permission needed!)
            try {
                overlayContainer.addView(card, card.cardLayoutParams)
                timerCards[timerData.id] = card
                refreshAggregateState(timerData)
                Log.i(TAG, "⏱️ Timer card added to overlay container at (${position.first}, ${position.second})")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to add timer card to overlay container", e)
            }
        }
    }

    /**
     * Update an existing timer card
     */
    fun updateTimer(timerData: TimerData) {
        val card = timerCards[timerData.id]
        if (card == null) {
            Log.w(TAG, "Timer ${timerData.id} not found for update, creating instead")
            createTimer(timerData)
            return
        }

        Log.v(TAG, "⏱️ Updating timer ${timerData.id}: remaining=${timerData.remainingSeconds}s, state=${timerData.state}")
        card.updateTimer(timerData)
        refreshAggregateState(timerData)

        // Start/stop alarm based on aggregate state
        if (_hasAlarmPlaying) {
            alertSoundService.startAlarm()
        } else {
            alertSoundService.stopAlarm()
        }

        // Notify the family's phones once, on the RUNNING→COMPLETED transition
        // (add() returns true only the first time we see this id complete).
        if (timerData.state == TimerState.COMPLETED && notifiedTimerCompletions.add(timerData.id)) {
            val label = timerData.label.trim()
            FamilyAlertNotifier.notifyFamily(
                source = FamilyAlertNotifier.SOURCE_TIMER,
                refId = timerData.id,
                title = if (label.isNotEmpty()) "Timer: $label" else "Timer done",
                body = if (label.isNotEmpty()) "\"$label\" is done" else "Your timer is done"
            )
        }
    }

    /**
     * Remove a timer card
     */
    fun removeTimer(timerId: String) {
        val card = timerCards.remove(timerId)
        if (card == null) {
            Log.w(TAG, "Timer $timerId not found for removal")
            return
        }

        Log.i(TAG, "⏱️ Removing timer card: $timerId")
        notifiedTimerCompletions.remove(timerId)
        val wasOnPanel = cardsOnPanel.remove(timerId)
        try {
            visibilityGate?.unregister(card)
            card.destroy()
            (card.parent as? ViewGroup)?.removeView(card)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to remove timer card", e)
        }
        if (wasOnPanel) {
            coordinator?.notifyCardRemoved()
        }
        refreshAggregateState()

        // Stop alarm if no more completed timers
        if (!_hasAlarmPlaying) {
            alertSoundService.stopAlarm()
        }
    }

    /**
     * Calculate initial position for a new timer (stacked below existing timers)
     */
    private fun calculateInitialPosition(): Pair<Int, Int> {
        if (timerCards.isEmpty()) {
            // First timer: top-right corner. Use the SCALED card width — the card
            // scales from pivot (0,0) by Display Size, so an unscaled width here
            // would push the right edge off-screen at >100% (the "half offscreen"
            // bug). cardWidth × scale keeps the scaled right edge on-screen.
            val displayMetrics = context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val scale = com.dashieapp.Dashie.halite.preferences.DisplaySizeScale.scale(context)
            val cardWidth = (dpToPx(390) * scale).toInt() // Expanded card width × Display Size
            return Pair(screenWidth - cardWidth - dpToPx(INITIAL_X), dpToPx(INITIAL_Y))
        }

        // Stack below the last timer
        val lastCard = timerCards.values.last()
        val lastParams = lastCard.cardLayoutParams
        val y = lastParams.topMargin + lastCard.height + dpToPx(STACK_SPACING)
        return Pair(lastParams.leftMargin, y)
    }

    /**
     * Get saved position for a timer ID, or null if not saved
     */
    private fun getSavedPosition(timerId: String): Pair<Int, Int>? {
        try {
            val positionsJson = JSONObject(prefs.display.timerPositions)
            if (positionsJson.has(timerId)) {
                val pos = positionsJson.getJSONObject(timerId)
                return Pair(pos.getInt("x"), pos.getInt("y"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved timer positions", e)
        }
        return null
    }

    /**
     * Save timer position to preferences
     */
    fun saveTimerPosition(timerId: String, x: Int, y: Int) {
        try {
            val positionsJson = JSONObject(prefs.display.timerPositions)
            positionsJson.put(timerId, JSONObject().apply {
                put("x", x)
                put("y", y)
            })
            prefs.display.timerPositions = positionsJson.toString()
            Log.d(TAG, "Saved position for timer $timerId: ($x, $y)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save timer position", e)
        }
    }

    /**
     * Handle pause/resume button click
     */
    private fun handlePauseResume(timerId: String) {
        val timer = timerCards[timerId]?.let { getTimerData(it) }
        if (timer == null) {
            Log.w(TAG, "Timer $timerId not found for pause/resume")
            return
        }

        when (timer.state) {
            TimerState.PAUSED -> {
                Log.i(TAG, "⏱️ Resuming timer $timerId")
                jsBridge?.resumeTimer(timerId)
            }
            TimerState.RUNNING -> {
                Log.i(TAG, "⏱️ Pausing timer $timerId")
                jsBridge?.pauseTimer(timerId)
            }
            else -> {
                Log.w(TAG, "Timer $timerId in state ${timer.state}, cannot pause/resume")
            }
        }
    }

    /**
     * Handle cancel button click
     */
    private fun handleCancel(timerId: String) {
        Log.i(TAG, "⏱️ Cancelling timer $timerId")
        jsBridge?.cancelTimer(timerId)
        // Timer will be removed when JavaScript sends timer-cancelled event
    }

    /**
     * Handle minimize/expand button click
     */
    private fun handleMinimizeExpand(timerId: String) {
        val timer = timerCards[timerId]?.let { getTimerData(it) }
        if (timer == null) {
            Log.w(TAG, "Timer $timerId not found for minimize/expand")
            return
        }

        if (timer.isMinimized) {
            Log.i(TAG, "⏱️ Expanding timer $timerId")
            jsBridge?.expandTimer(timerId)
        } else {
            Log.i(TAG, "⏱️ Minimizing timer $timerId")
            jsBridge?.minimizeTimer(timerId)
        }
    }

    /**
     * Get timer data from a card (helper for reflection access)
     */
    private fun getTimerData(card: TimerCard): TimerData? {
        try {
            val field = TimerCard::class.java.getDeclaredField("timerData")
            field.isAccessible = true
            return field.get(card) as? TimerData
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get timerData via reflection", e)
            return null
        }
    }

    /**
     * Convert dp to pixels
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    // ── Screensaver Integration ────────────────────────────────────

    /**
     * Called when screensaver activates.
     * Migrate all active timer cards from overlayContainer to the screensaver panel.
     */
    fun onScreensaverActivated() {
        val panel = coordinator?.getPanel() ?: return
        if (timerCards.isEmpty()) return

        Log.i(TAG, "⏱️ Screensaver activated — migrating ${timerCards.size} timer(s) to panel")
        for ((id, card) in timerCards) {
            if (cardsOnPanel.contains(id)) continue
            (card.parent as? ViewGroup)?.removeView(card)
            panel.addView(card, card.cardLayoutParams)
            cardsOnPanel.add(id)
            coordinator?.notifyCardAdded()
        }
    }

    // ── Sidebar Integration ─────────────────────────────────────────

    override fun onSidebarActivated() {
        val panel = coordinator?.getPanel() ?: return
        if (timerCards.isEmpty()) return

        Log.i(TAG, "⏱️ Sidebar activated — migrating ${timerCards.size} timer(s) to panel")
        for ((id, card) in timerCards) {
            if (cardsOnPanel.contains(id)) continue
            (card.parent as? ViewGroup)?.removeView(card)
            panel.addView(card, card.cardLayoutParams)
            cardsOnPanel.add(id)
            coordinator?.notifyCardAdded()
        }
    }

    override fun onSidebarDeactivated() {
        if (cardsOnPanel.isEmpty()) return
        Log.i(TAG, "⏱️ Sidebar deactivated — migrating ${cardsOnPanel.size} timer(s) back to overlayContainer")
        for (id in cardsOnPanel.toList()) {
            val card = timerCards[id] ?: continue
            val lp = card.layoutParams as? android.widget.FrameLayout.LayoutParams
                ?: card.cardLayoutParams
            (card.parent as? ViewGroup)?.removeView(card)
            overlayContainer.addView(card, lp)
            coordinator?.notifyCardRemoved()
        }
        cardsOnPanel.clear()
    }

    /**
     * Called when screensaver deactivates.
     * Cards stay in place — coordinator handles removing background.
     */
    fun onScreensaverDeactivated() {
        if (cardsOnPanel.isEmpty()) return
        Log.i(TAG, "⏱️ Screensaver deactivated — migrating ${cardsOnPanel.size} timer(s) back to overlayContainer")
        for (id in cardsOnPanel.toList()) {
            val card = timerCards[id] ?: continue
            // Use current layoutParams (preserves coordinator's position), not cardLayoutParams (recomputes defaults)
            val lp = card.layoutParams as? android.widget.FrameLayout.LayoutParams
                ?: card.cardLayoutParams
            (card.parent as? ViewGroup)?.removeView(card)
            overlayContainer.addView(card, lp)
        }
        cardsOnPanel.clear()
    }

    /**
     * Stop the alarm and cancel the completed timer(s).
     * Called from voice "stop alarm" command.
     */
    fun stopAlarm() {
        alertSoundService.stopAlarm()

        // Find and cancel all completed timers
        val completedIds = timerCards.entries
            .filter { (_, card) -> getTimerData(card)?.state == TimerState.COMPLETED }
            .map { it.key }

        for (timerId in completedIds) {
            Log.i(TAG, "⏱️ Cancelling completed timer $timerId via stop alarm")
            jsBridge?.cancelTimer(timerId)
        }
    }

    /**
     * Forward configuration change to all active timer cards.
     * Called when system dark/light mode changes so cards can re-render.
     */
    fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        for (card in timerCards.values) {
            card.dispatchConfigurationChanged(newConfig)
        }
    }

    /**
     * Cancel every timer + stop the alarm. Called on top-level navigation
     * (page reload or process restart simulated as reload) to prevent a
     * "ringing alarm with no card to dismiss" state. Without this, the
     * audio survives the reload (in-process AudioTrack) while the cards
     * get gate-hidden and aren't re-created — leaving the user with no
     * UI affordance to silence the alarm.
     *
     * Distinct from destroy(): manager stays alive, just clears all
     * in-memory timer state + audio.
     *
     * NOTE: this is the deliberate temporary behavior per
     * _TECHNICAL_DEBT.md "Timer Persistence Across App Restart" —
     * timers don't survive a reload today. When persistence ships, this
     * will be replaced with a rehydration path that restores cards and
     * recomputes alarm state from disk.
     */
    fun cancelAllTimers() {
        if (timerCards.isEmpty() && !alertSoundService.isAlarmPlaying()) return
        Log.i(TAG, "⏱️ cancelAllTimers: ${timerCards.size} card(s), alarmPlaying=${alertSoundService.isAlarmPlaying()}")
        alertSoundService.stopAlarm()
        timerCards.keys.toList().forEach { removeTimer(it) }
    }

    /**
     * Clean up all timer cards
     */
    fun destroy() {
        // AlertSoundService lifecycle is managed by HaliteComponentRegistry
        timerCards.keys.toList().forEach { timerId ->
            removeTimer(timerId)
        }
    }
}
