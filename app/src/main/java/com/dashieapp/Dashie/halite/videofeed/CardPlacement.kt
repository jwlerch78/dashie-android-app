package com.dashieapp.Dashie.halite.videofeed

import android.widget.FrameLayout

/**
 * A video feed card is always in exactly one CardPlacement.
 *
 * Placement is the single source of truth for:
 *  - which parent view contains the card
 *  - its layout params (size / position)
 *  - its visual mode (NORMAL / MAXIMIZED / PLAYBACK)
 *  - its interactivity flags (draggable / resizable / tappable)
 *
 * Transitions between placements are atomic and go through VideoFeedCoordinator.
 * Callers never set card.parent / card.layoutParams / card.mode / isDraggable directly —
 * they call coordinator.transition(ruleId, placement) and the coordinator handles
 * everything.
 *
 * See build-plans/20260417_VIDEO_FEED_STATE_MACHINE_REFACTOR.md for rationale.
 */
sealed class CardPlacement {

    /** Description of what container the card lives in for this placement. */
    abstract val parentKind: ParentKind

    /** The card's visual mode while in this placement. */
    abstract val cardMode: VideoFeedCard.Mode

    /** Gesture/interactivity flags while in this placement. */
    abstract val interactivity: Interactivity

    /**
     * Card is not currently attached to any view.
     * Used after dismiss or before a card is first placed.
     */
    object None : CardPlacement() {
        override val parentKind = ParentKind.Detached
        override val cardMode = VideoFeedCard.Mode.NORMAL
        override val interactivity = Interactivity.none()
    }

    /** Thumbnail inside the strip renderer. Identifier lets us locate the wrapper view. */
    data class Strip(val ruleId: String) : CardPlacement() {
        override val parentKind = ParentKind.StripWrapper
        override val cardMode = VideoFeedCard.Mode.NORMAL
        override val interactivity = Interactivity(
            draggable = false, resizable = false,
            tapToMaximize = false, canDragToUnpin = false, canDragUpToFloat = true,
        )
    }

    /** Focal feed in the drawer (large card above the strip). */
    object DrawerFocal : CardPlacement() {
        override val parentKind = ParentKind.DrawerContent
        override val cardMode = VideoFeedCard.Mode.NORMAL
        override val interactivity = Interactivity(
            draggable = true, resizable = true,
            tapToMaximize = true, canDragToUnpin = false, canDragUpToFloat = false,
        )
    }

    /** Free-floating pinned card. savedLp remembers the user's placement. */
    data class Floating(
        val savedLp: FrameLayout.LayoutParams,
    ) : CardPlacement() {
        override val parentKind = ParentKind.Overlay
        override val cardMode = VideoFeedCard.Mode.NORMAL
        override val interactivity = Interactivity(
            draggable = true, resizable = true,
            tapToMaximize = true, canDragToUnpin = false, canDragUpToFloat = false,
        )
    }

    /** Full-screen non-playback (live view at max size). */
    object Maximized : CardPlacement() {
        override val parentKind = ParentKind.Overlay
        override val cardMode = VideoFeedCard.Mode.MAXIMIZED
        override val interactivity = Interactivity(
            draggable = false, resizable = false,
            tapToMaximize = false, canDragToUnpin = false, canDragUpToFloat = false,
        )
    }

    /** Frigate playback UI — full-screen, owned by a FrigatePlaybackController. */
    object Playback : CardPlacement() {
        override val parentKind = ParentKind.Overlay
        override val cardMode = VideoFeedCard.Mode.PLAYBACK
        override val interactivity = Interactivity(
            draggable = false, resizable = false,
            tapToMaximize = false, canDragToUnpin = false, canDragUpToFloat = false,
        )
    }

    /** Mini live view inside the screensaver grid. No controls, no gestures. */
    data class Screensaver(val slotIndex: Int) : CardPlacement() {
        override val parentKind = ParentKind.ScreensaverSlot
        override val cardMode = VideoFeedCard.Mode.NORMAL
        override val interactivity = Interactivity.none()
    }

    /** Short label for logging/telemetry. */
    val name: String
        get() = when (this) {
            is None -> "None"
            is Strip -> "Strip"
            is DrawerFocal -> "DrawerFocal"
            is Floating -> "Floating"
            is Maximized -> "Maximized"
            is Playback -> "Playback"
            is Screensaver -> "Screensaver[$slotIndex]"
        }
}

/** Logical kind of the card's current parent view. Resolved to an actual ViewGroup by VideoFeedCoordinator. */
enum class ParentKind {
    Detached,
    StripWrapper,      // wrapper inside VideoFeedStripRenderer
    DrawerContent,     // inner container of VideoFeedDrawer
    Overlay,           // overlayContainer (full-screen area above everything)
    Panel,             // sidebar panel (legacy maximized path)
    ScrollContainer,   // scrollable list when there are many feeds
    ScreensaverSlot,   // grid slot inside the screensaver container
}

/**
 * Gesture / interactivity flags applied to the card while in a given placement.
 * Mirrors the var flags on VideoFeedCard; replacing them long-term but currently
 * applied by the coordinator on attach.
 */
data class Interactivity(
    val draggable: Boolean,
    val resizable: Boolean,
    val tapToMaximize: Boolean,
    val canDragToUnpin: Boolean,
    val canDragUpToFloat: Boolean,
    /**
     * Whether a long-press on the card body begins an audio "talk" capture
     * (feature in progress — touch-to-talk). Declared per-placement so callers
     * never need to toggle this on the card manually.
     */
    val longPressToTalk: Boolean = false,
) {
    companion object {
        fun none() = Interactivity(
            draggable = false, resizable = false,
            tapToMaximize = false, canDragToUnpin = false, canDragUpToFloat = false,
            longPressToTalk = false,
        )
    }
}
