package com.dashieapp.Dashie.halite.videofeed

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Single source of truth for where every active video feed card currently lives.
 *
 * PHASE 1 SCOPE (instrumentation only, no behavior change):
 *  - Callers declaratively record a card's placement via record(ruleId, placement).
 *    They still move the card themselves using the existing manager code.
 *  - verifyAgainstCard(ruleId, card) compares the recorded placement to what the card
 *    actually looks like (parent, layout, mode, flags) and logs any drift.
 *  - This lets us land the new concept alongside old code, prove the placement model
 *    matches current behavior, and catch mismatches before Phase 2 starts executing
 *    transitions.
 *
 * Future phases:
 *  - Phase 2+: replace `record()` with `transition()` that actively moves the card.
 *  - Callers stop touching card.parent / card.layoutParams / card.mode directly.
 *
 * See build-plans/20260417_VIDEO_FEED_STATE_MACHINE_REFACTOR.md
 */
class VideoFeedCoordinator {

    companion object {
        private const val TAG = "VideoFeedPlacement"
    }

    // Current placement, per ruleId. None if not tracked yet.
    private val placements = mutableMapOf<String, CardPlacement>()

    // Most recent non-Screensaver placement, per ruleId. Used when exiting screensaver.
    private val lastNonScreensaver = mutableMapOf<String, CardPlacement>()

    /** Current placement for a rule, or None if not tracked. */
    fun placementOf(ruleId: String): CardPlacement =
        placements[ruleId] ?: CardPlacement.None

    /**
     * Record that a card is now in the given placement. Does NOT move the card;
     * Phase 1 leaves movement to existing manager code. Phase 2 will replace this
     * with an atomic transition.
     */
    fun record(ruleId: String, placement: CardPlacement) {
        val prev = placements[ruleId] ?: CardPlacement.None
        if (prev == placement) return
        placements[ruleId] = placement
        if (placement !is CardPlacement.Screensaver && placement !is CardPlacement.None) {
            lastNonScreensaver[ruleId] = placement
        }
        Log.i(TAG, "record: $ruleId: ${prev.name} → ${placement.name}")
    }

    /** Forget a ruleId entirely (e.g., after dismissFeed). */
    fun forget(ruleId: String) {
        val prev = placements.remove(ruleId)
        lastNonScreensaver.remove(ruleId)
        if (prev != null) Log.i(TAG, "forget: $ruleId (was ${prev.name})")
    }

    /**
     * Atomic transition INTO CardPlacement.Playback.
     *
     * Moves the card from its current parent (drawer / overlay / wherever) to
     * overlayContainer with MATCH_PARENT, ensures its Mode is PLAYBACK via the
     * supplied enterMode callback, and records the placement. Does NOT construct
     * the FrigatePlaybackController — the caller does that after transition
     * returns (the controller is the UI for the Playback placement, not part of
     * transition plumbing).
     *
     * @param drawer if the card is currently the drawer's focal feed, detaches
     *               it from the drawer cleanly so hideStrip() later doesn't
     *               "rescue" it and override our layout.
     * @param enterMode called on the card after attach — should transition its
     *                   internal mode to PLAYBACK (e.g., `card.setPlaybackMode()`).
     */
    fun transitionToPlayback(
        ruleId: String,
        card: VideoFeedCard,
        overlayContainer: FrameLayout,
        drawer: VideoFeedDrawer?,
        enterMode: () -> Unit,
    ) {
        val prev = placementOf(ruleId)
        Log.i(TAG, "transitionToPlayback: $ruleId: ${prev.name} → Playback")

        // Detach from drawer's state if hosted there so hideStrip()'s
        // detachPinnedCard() doesn't later re-add it with floating params.
        if (drawer?.getCurrentCard() === card) {
            drawer.detachCard()
        }

        // Move to overlayContainer at full-screen size (atomic parent swap).
        val currentParent = card.parent as? ViewGroup
        if (currentParent !== overlayContainer) {
            currentParent?.removeView(card)
            overlayContainer.addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        } else {
            card.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // Apply visual mode + interactivity.
        enterMode()
        applyInteractivity(card, CardPlacement.Playback.interactivity)

        // Remember where we came from so exit can go back.
        if (prev !is CardPlacement.Playback && prev !is CardPlacement.None) {
            lastNonScreensaver[ruleId] = prev
        }
        placements[ruleId] = CardPlacement.Playback
    }

    /**
     * Atomic transition OUT of CardPlacement.Playback back to the previous
     * placement (or a sensible default if none is remembered).
     *
     * For drawer-sourced cards, returns them to the drawer via the supplied
     * restoreToDrawer callback. For floating/maximized-sourced cards, leaves
     * them in overlayContainer with the appropriate layout params.
     *
     * @param exitMode called on the card to transition mode back to NORMAL
     *                  (e.g., `card.exitPlaybackMode()`).
     * @param restoreToDrawer called only if the previous placement was DrawerFocal.
     *                         Manager implements "put card back in drawer, show strip".
     */
    fun transitionFromPlayback(
        ruleId: String,
        card: VideoFeedCard,
        exitMode: () -> Unit,
        restoreToDrawer: () -> Unit,
    ) {
        val current = placementOf(ruleId)
        val target = lastNonScreensaver[ruleId] ?: CardPlacement.None
        Log.i(TAG, "transitionFromPlayback: $ruleId: ${current.name} → ${target.name}")

        // Visual mode first so the Frigate UI is torn down before any reparenting.
        exitMode()

        when (target) {
            is CardPlacement.DrawerFocal -> {
                Log.i(TAG, "  branch: DrawerFocal — invoking restoreToDrawer")
                restoreToDrawer()
                placements[ruleId] = CardPlacement.DrawerFocal
                lastNonScreensaver[ruleId] = CardPlacement.DrawerFocal
            }
            is CardPlacement.Floating -> {
                Log.i(TAG, "  branch: Floating — applying savedLp")
                card.layoutParams = target.savedLp
                applyInteractivity(card, target.interactivity)
                placements[ruleId] = target
                lastNonScreensaver[ruleId] = target
            }
            is CardPlacement.Maximized -> {
                Log.i(TAG, "  branch: Maximized — already at MATCH_PARENT")
                applyInteractivity(card, target.interactivity)
                placements[ruleId] = target
                lastNonScreensaver[ruleId] = target
            }
            else -> {
                Log.w(TAG, "  branch: FALL-THROUGH ($target) — card not being moved. " +
                    "lastNonScreensaver was null — check that placements.record() " +
                    "was called when the card was initially placed.")
                placements[ruleId] = CardPlacement.None
            }
        }
    }

    /**
     * Atomic transition: Floating → Maximized.
     *
     * Caller is responsible for the actual view-level work (setMaximized, lp
     * update, panel move) because that path is split across many helpers and
     * will be unified in Phase 3b. This method just tracks the placement +
     * saves the pre-maximize Floating lp so restore can return there.
     *
     * Returns the previous placement for callers that still read it.
     */
    fun recordMaximizeFromFloating(ruleId: String, floatingLp: FrameLayout.LayoutParams): CardPlacement {
        val prev = placementOf(ruleId)
        Log.i(TAG, "recordMaximizeFromFloating: $ruleId: ${prev.name} → Maximized " +
            "(savedLp=${floatingLp.width}x${floatingLp.height})")
        if (prev is CardPlacement.Floating) {
            lastNonScreensaver[ruleId] = prev  // preserve saved lp for restore
        }
        placements[ruleId] = CardPlacement.Maximized
        return prev
    }

    /**
     * Atomic transition: DrawerFocal → Maximized.
     *
     * Caller is still responsible for the view-level work (detach from drawer,
     * setMaximized, add to overlayContainer) because the drawer teardown is
     * non-trivial. This method just records the transition so the coordinator
     * remembers the card came from the drawer — so restore routes correctly.
     */
    fun recordMaximizeFromDrawer(ruleId: String) {
        val prev = placementOf(ruleId)
        Log.i(TAG, "recordMaximizeFromDrawer: $ruleId: ${prev.name} → Maximized")
        // Remember DrawerFocal as the restore target. If prev wasn't already
        // DrawerFocal, stamp it now so the coordinator's exit path takes the
        // drawer branch.
        lastNonScreensaver[ruleId] = CardPlacement.DrawerFocal
        placements[ruleId] = CardPlacement.Maximized
    }

    /**
     * Atomic transition: Maximized → DrawerFocal (restore to drawer).
     * Caller provides the restoreToDrawer lambda because the drawer's internal
     * state (showFeed, stripVisible, dim overlay) is owned by the manager.
     * Returns true if handled (prev was DrawerFocal), false otherwise so caller
     * can fall back to legacy paths.
     */
    fun transitionMaximizedToDrawer(ruleId: String, restoreToDrawer: () -> Unit): Boolean {
        val prev = lastNonScreensaver[ruleId]
        if (prev !is CardPlacement.DrawerFocal) {
            Log.i(TAG, "transitionMaximizedToDrawer: $ruleId has no DrawerFocal prev (prev=${prev?.let { it::class.simpleName }})")
            return false
        }
        Log.i(TAG, "transitionMaximizedToDrawer: $ruleId → DrawerFocal")
        restoreToDrawer()
        placements[ruleId] = CardPlacement.DrawerFocal
        lastNonScreensaver[ruleId] = CardPlacement.DrawerFocal
        return true
    }

    /**
     * Atomic transition: Maximized → Floating (restore the previously-saved
     * floating lp). If the previous placement wasn't Floating, does nothing
     * and returns false so caller can fall back to legacy code paths.
     */
    fun transitionMaximizedToFloating(ruleId: String, card: VideoFeedCard): Boolean {
        val prev = lastNonScreensaver[ruleId]
        if (prev !is CardPlacement.Floating) {
            Log.i(TAG, "transitionMaximizedToFloating: $ruleId has no Floating prev — " +
                "caller should handle (prev=${prev?.let { it::class.simpleName }})")
            return false
        }
        Log.i(TAG, "transitionMaximizedToFloating: $ruleId → Floating (savedLp=${prev.savedLp.width}x${prev.savedLp.height})")
        card.layoutParams = prev.savedLp
        applyInteractivity(card, prev.interactivity)
        placements[ruleId] = prev
        return true
    }

    private fun applyInteractivity(card: VideoFeedCard, i: Interactivity) {
        card.isDraggable = i.draggable
        card.isResizable = i.resizable
        card.tapToMaximize = i.tapToMaximize
        card.canDragToUnpin = i.canDragToUnpin
        card.canDragUpToFloat = i.canDragUpToFloat
        // longPressToTalk flag is stored in Interactivity but not yet applied to
        // the card (touch-to-talk feature not yet wired). When the feature lands,
        // read placementOf(ruleId).interactivity.longPressToTalk at the gesture
        // handler site.
    }

    /**
     * Preferred restoration target when exiting screensaver.
     * Playback is demoted to Maximized per the screensaver exit rule.
     */
    fun screensaverExitTarget(ruleId: String): CardPlacement {
        val previous = lastNonScreensaver[ruleId] ?: CardPlacement.None
        return if (previous is CardPlacement.Playback) CardPlacement.Maximized else previous
    }

    /** Last non-Screensaver, non-Playback placement recorded for this ruleId. */
    fun previousPlacementFor(ruleId: String): CardPlacement =
        lastNonScreensaver[ruleId] ?: CardPlacement.None

    /**
     * Cross-check the recorded placement against the card's actual state. Logs warnings
     * for any drift. Called by the manager at well-known integration points in Phase 1
     * so we can see in logcat when our placement tracking disagrees with reality.
     */
    fun verifyAgainstCard(
        ruleId: String,
        card: VideoFeedCard,
        overlayContainer: ViewGroup,
        drawerContainer: ViewGroup?,
        screensaverContainer: ViewGroup?,
        panelContainer: ViewGroup?,
        scrollContainer: ViewGroup?,
    ) {
        val placement = placements[ruleId] ?: return
        val issues = mutableListOf<String>()

        // Parent kind
        val actualParent = card.parent as? View
        val expectedKind = placement.parentKind
        val actualKind = classifyParent(
            actualParent,
            overlayContainer, drawerContainer, screensaverContainer, panelContainer, scrollContainer,
        )
        if (actualKind != expectedKind) {
            issues += "parent: expected=$expectedKind actual=$actualKind (${actualParent?.javaClass?.simpleName})"
        }

        // Mode
        if (card.mode != placement.cardMode) {
            issues += "mode: expected=${placement.cardMode} actual=${card.mode}"
        }

        // Interactivity (only compare fields we can read back)
        if (card.isDraggable != placement.interactivity.draggable) {
            issues += "draggable: expected=${placement.interactivity.draggable} actual=${card.isDraggable}"
        }
        if (card.isResizable != placement.interactivity.resizable) {
            issues += "resizable: expected=${placement.interactivity.resizable} actual=${card.isResizable}"
        }
        if (card.tapToMaximize != placement.interactivity.tapToMaximize) {
            issues += "tapToMaximize: expected=${placement.interactivity.tapToMaximize} actual=${card.tapToMaximize}"
        }

        // Layout (only check when placement specifies known values)
        val lp = card.layoutParams as? FrameLayout.LayoutParams
        val expectFullScreen = placement is CardPlacement.Maximized || placement is CardPlacement.Playback
        if (expectFullScreen && lp != null) {
            val mp = FrameLayout.LayoutParams.MATCH_PARENT
            if (lp.width != mp || lp.height != mp) {
                issues += "lp: expected=MATCH_PARENTxMATCH_PARENT actual=${lp.width}x${lp.height}"
            }
        }

        if (issues.isNotEmpty()) {
            Log.w(TAG, "MISMATCH $ruleId (${placement.name}): ${issues.joinToString("; ")}")
        }
    }

    private fun classifyParent(
        parent: View?,
        overlayContainer: ViewGroup,
        drawerContainer: ViewGroup?,
        screensaverContainer: ViewGroup?,
        panelContainer: ViewGroup?,
        scrollContainer: ViewGroup?,
    ): ParentKind = when {
        parent == null -> ParentKind.Detached
        parent === overlayContainer -> ParentKind.Overlay
        panelContainer != null && parent === panelContainer -> ParentKind.Panel
        scrollContainer != null && parent === scrollContainer -> ParentKind.ScrollContainer
        screensaverContainer != null && isAncestor(screensaverContainer, parent) -> ParentKind.ScreensaverSlot
        drawerContainer != null && isAncestor(drawerContainer, parent) -> ParentKind.DrawerContent
        // Anything else is likely a strip wrapper (each card has its own wrapper FrameLayout)
        else -> ParentKind.StripWrapper
    }

    private fun isAncestor(ancestor: ViewGroup, candidate: View): Boolean {
        var p: View? = candidate
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }

    /** For debugging / telemetry — not load-bearing. */
    fun dump(): String = buildString {
        appendLine("VideoFeedCoordinator:")
        if (placements.isEmpty()) appendLine("  (no placements tracked)")
        else placements.forEach { (id, p) -> appendLine("  $id → ${p.name}") }
    }
}
