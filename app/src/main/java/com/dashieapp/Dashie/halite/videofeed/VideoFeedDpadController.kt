package com.dashieapp.Dashie.halite.videofeed

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * D-pad / TV-remote driver for the video feed strip + Frigate playback.
 *
 * Zones (where the d-pad "focus" currently lives):
 *   STRIP    — horizontal feed selection. left/right move the highlight,
 *              center opens the highlighted feed (→ SCRUBBER for Frigate
 *              cameras, → FOCAL for everything else), back closes the strip.
 *   FOCAL    — a non-Frigate feed is open in the focal drawer (no timeline).
 *              Only back is meaningful (closes the drawer → STRIP).
 *   Playback has three highlightable areas (scrubber / video / clips):
 *   SCRUBBER — the timeline. left/right scrub (tiered: each consecutive press
 *              jumps further), up → VIDEO, center short = play/pause, center
 *              long-press = return to live, back exits playback → STRIP.
 *   VIDEO    — the main video display. down → SCRUBBER, right → CLIPS, center =
 *              enter fullscreen (hides timeline + clips); in fullscreen center =
 *              play/pause and back exits fullscreen. back (windowed) exits → STRIP.
 *   CLIPS    — the right-hand event/clip list. up/down move the selection,
 *              center plays it (→ VIDEO), left → VIDEO, back exits playback → STRIP.
 *
 * Owns no views — it drives the manager ([Host]) and the active
 * [FrigatePlaybackController]. Lives at the manager level so it can span the
 * strip → playback → strip lifecycle while the manager keeps d-pad capture on.
 */
class VideoFeedDpadController(private val host: Host) {

    /** Bridge to the [VideoFeedOverlayManager]'s state + actions. */
    interface Host {
        /** Visible, selectable feed rule ids in display order. */
        fun dpadSelectableRuleIds(): List<String>
        /** Highlight the given strip card (or clear when null) + scroll it into view. */
        fun dpadHighlightStripCard(ruleId: String?)
        /** Open the feed. Returns true if it entered Frigate playback (→ SCRUBBER),
         *  false if it opened the non-Frigate focal drawer (→ FOCAL). */
        fun dpadOpenFeed(ruleId: String): Boolean
        /** The active Frigate playback controller, or null if none. */
        fun dpadActivePlayback(): FrigatePlaybackController?
        /** Close playback and reopen the strip (re-highlight happens via onStripRebuilt). */
        fun dpadExitPlaybackToStrip()
        /** Close the non-Frigate focal drawer, returning to the strip. */
        fun dpadCloseFocalToStrip()
        /** Close the strip entirely and release d-pad capture. */
        fun dpadCloseStrip()
        /** Whether the strip is currently visible. */
        fun dpadStripVisible(): Boolean
    }

    private enum class Zone { STRIP, FOCAL, SCRUBBER, VIDEO, CLIPS, DAYS }

    companion object {
        private const val TAG = "VideoFeedDpad"
        private const val CENTER_HOLD_MS = 500L
        private const val SCRUB_RESET_MS = 800L
        // Tiered rewind/ff: each consecutive same-direction press jumps further.
        // Single press = 30s; holding (OS auto-repeat) or rapid taps accelerate.
        private val SCRUB_TIERS_SEC = intArrayOf(30, 60, 300, 900, 1800)
        // Fullscreen scrub is finer-grained — first press = 10s (normal replay feel).
        private val FS_SCRUB_TIERS_SEC = intArrayOf(10, 30, 60, 300, 900)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var zone = Zone.STRIP
    private var selectedRuleId: String? = null

    // Tiered scrub state
    private var scrubTier = 0
    private var scrubDir = 0
    private val scrubResetRunnable = Runnable { scrubTier = 0; scrubDir = 0 }

    // Center hold state (SCRUBBER): tap = play/pause, hold = go-to-live.
    private var centerHeld = false
    private var centerFired = false
    private val centerHoldRunnable = Runnable {
        centerFired = true
        host.dpadActivePlayback()?.dpadReturnToLive()
    }

    // ── Lifecycle ───────────────────────────────────────────────────

    /** Engage in STRIP zone and highlight the first selectable feed. */
    fun startInStrip() {
        cancelTimers()
        zone = Zone.STRIP
        val ids = host.dpadSelectableRuleIds()
        selectedRuleId = ids.firstOrNull()
        handler.post { host.dpadHighlightStripCard(selectedRuleId) }
        Log.i(TAG, "startInStrip: selected=$selectedRuleId of ${ids.size}")
    }

    /** The strip was rebuilt (HA sync, or returning from playback) — re-apply
     *  the highlight to the previously selected feed (or the first one). */
    fun onStripRebuilt() {
        zone = Zone.STRIP
        val ids = host.dpadSelectableRuleIds()
        if (selectedRuleId !in ids) selectedRuleId = ids.firstOrNull()
        host.dpadHighlightStripCard(selectedRuleId)
    }

    fun release() {
        cancelTimers()
        zone = Zone.STRIP
        selectedRuleId = null
    }

    private fun cancelTimers() {
        handler.removeCallbacks(scrubResetRunnable)
        handler.removeCallbacks(centerHoldRunnable)
        scrubTier = 0; scrubDir = 0
        centerHeld = false; centerFired = false
    }

    // ── Key handling ────────────────────────────────────────────────

    /**
     * Handle a d-pad / center [KeyEvent] (both DOWN and UP edges). BACK is NOT
     * routed here — it comes through [handleBack]. Returns true if consumed.
     */
    fun handleKey(event: KeyEvent): Boolean {
        // Auto-release if the surface we'd control has gone away.
        when (zone) {
            Zone.STRIP, Zone.FOCAL ->
                if (!host.dpadStripVisible()) { release(); return false }
            Zone.SCRUBBER, Zone.VIDEO, Zone.CLIPS, Zone.DAYS ->
                if (host.dpadActivePlayback() == null) { release(); return false }
        }

        val down = event.action == KeyEvent.ACTION_DOWN
        val firstDown = down && event.repeatCount == 0
        val up = event.action == KeyEvent.ACTION_UP

        return when (zone) {
            Zone.STRIP -> handleStrip(event.keyCode, down, firstDown)
            Zone.FOCAL -> true  // consume all; only back is meaningful (handleBack)
            Zone.SCRUBBER -> handleScrubber(event.keyCode, down, firstDown, up)
            Zone.VIDEO -> handleVideo(event.keyCode, down, firstDown, up)
            Zone.CLIPS -> handleClips(event.keyCode, down, firstDown)
            Zone.DAYS -> handleDays(event.keyCode, down, firstDown)
        }
    }

    /** Handle BACK. Returns true if consumed. */
    fun handleBack(): Boolean {
        when (zone) {
            Zone.VIDEO -> {
                // In fullscreen, back just exits fullscreen (stay in VIDEO).
                if (host.dpadActivePlayback()?.dpadExitFullscreen() == true) return true
                cancelTimers()
                zone = Zone.STRIP
                host.dpadExitPlaybackToStrip()
            }
            Zone.SCRUBBER, Zone.CLIPS, Zone.DAYS -> {
                cancelTimers()
                zone = Zone.STRIP
                host.dpadExitPlaybackToStrip()
            }
            Zone.FOCAL -> {
                zone = Zone.STRIP
                host.dpadCloseFocalToStrip()
                handler.post { host.dpadHighlightStripCard(selectedRuleId) }
            }
            Zone.STRIP -> host.dpadCloseStrip()
        }
        return true
    }

    // ── Zone handlers ───────────────────────────────────────────────

    private fun handleStrip(keyCode: Int, down: Boolean, firstDown: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) moveStrip(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) moveStrip(+1)
            KeyEvent.KEYCODE_DPAD_CENTER -> if (firstDown) openSelected()
            // up/down: no-op in the strip
        }
        return true
    }

    private fun handleScrubber(keyCode: Int, down: Boolean, firstDown: Boolean, up: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) scrub(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) scrub(+1)
            KeyEvent.KEYCODE_DPAD_UP -> if (firstDown) toVideo()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (firstDown) toDays()
            KeyEvent.KEYCODE_DPAD_CENTER -> handleCenterHold(firstDown, up)
        }
        return true
    }

    private fun handleDays(keyCode: Int, down: Boolean, firstDown: Boolean): Boolean {
        val pb = host.dpadActivePlayback()
        when (keyCode) {
            // Today is on the right, older days to the left: left = older, right = newer.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) pb?.dpadDayNext()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) pb?.dpadDayPrev()
            KeyEvent.KEYCODE_DPAD_UP -> if (firstDown) {
                pb?.dpadClearDayFocus()
                pb?.dpadFocusScrubber()
                zone = Zone.SCRUBBER
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> if (firstDown) pb?.dpadDaySelect()
            // down: no-op
        }
        return true
    }

    private fun toDays() {
        host.dpadActivePlayback()?.dpadFocusDays()
        zone = Zone.DAYS
    }

    private fun handleVideo(keyCode: Int, down: Boolean, firstDown: Boolean, up: Boolean): Boolean {
        val pb = host.dpadActivePlayback()
        val fullscreen = pb?.dpadIsFullscreen() == true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (fullscreen && down) {
                // Fullscreen scrub back. The timeline overlay only appears once the
                // user keeps going (tier > 0) — a single quick step stays clean.
                scrub(-1, fine = true)
                if (scrubTier > 0) pb?.dpadShowFullscreenScrub()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (fullscreen) {
                if (down) {
                    scrub(+1, fine = true)
                    if (scrubTier > 0) pb?.dpadShowFullscreenScrub()
                }
            } else if (firstDown) {
                pb?.dpadFocusClips(); zone = Zone.CLIPS
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (firstDown && !fullscreen) {
                pb?.dpadFocusScrubber(); zone = Zone.SCRUBBER
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> if (fullscreen) {
                // Fullscreen: tap = play/pause, hold = go to live (same as scrubber).
                handleCenterHold(firstDown, up)
            } else if (firstDown) {
                pb?.dpadEnterFullscreen()
            }
        }
        return true
    }

    /** Center tap = play/pause, center hold = return to live. Shared by the
     *  scrubber zone and fullscreen video. */
    private fun handleCenterHold(firstDown: Boolean, up: Boolean) {
        if (firstDown) {
            centerHeld = true; centerFired = false
            handler.postDelayed(centerHoldRunnable, CENTER_HOLD_MS)
        } else if (up) {
            handler.removeCallbacks(centerHoldRunnable)
            if (centerHeld && !centerFired) host.dpadActivePlayback()?.dpadTogglePlayPause()
            centerHeld = false
        }
    }

    private fun handleClips(keyCode: Int, down: Boolean, firstDown: Boolean): Boolean {
        val pb = host.dpadActivePlayback()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) pb?.dpadClipPrev()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) pb?.dpadClipNext()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (firstDown) toVideo()
            KeyEvent.KEYCODE_DPAD_CENTER -> if (firstDown) {
                pb?.dpadClipSelect()
                toVideo()
            }
            // right: no-op
        }
        return true
    }

    private fun toVideo() {
        host.dpadActivePlayback()?.dpadFocusVideo()
        zone = Zone.VIDEO
    }

    // ── Actions ─────────────────────────────────────────────────────

    private fun moveStrip(delta: Int) {
        val ids = host.dpadSelectableRuleIds()
        if (ids.isEmpty()) return
        val cur = ids.indexOf(selectedRuleId).coerceAtLeast(0)
        val next = (cur + delta).coerceIn(0, ids.size - 1)
        selectedRuleId = ids[next]
        host.dpadHighlightStripCard(selectedRuleId)
    }

    private fun openSelected() {
        val id = selectedRuleId ?: return
        host.dpadHighlightStripCard(null)
        cancelTimers()
        if (host.dpadOpenFeed(id)) {
            zone = Zone.SCRUBBER
            host.dpadActivePlayback()?.dpadFocusScrubber()
        } else {
            zone = Zone.FOCAL
        }
        Log.i(TAG, "openSelected: $id → zone=$zone")
    }

    private fun scrub(dir: Int, fine: Boolean = false) {
        val pb = host.dpadActivePlayback() ?: return
        val tiers = if (fine) FS_SCRUB_TIERS_SEC else SCRUB_TIERS_SEC
        if (dir != scrubDir) {
            scrubTier = 0; scrubDir = dir
        } else {
            scrubTier = (scrubTier + 1).coerceAtMost(tiers.lastIndex)
        }
        pb.dpadScrub(tiers[scrubTier] * dir)
        handler.removeCallbacks(scrubResetRunnable)
        handler.postDelayed(scrubResetRunnable, SCRUB_RESET_MS)
    }
}
