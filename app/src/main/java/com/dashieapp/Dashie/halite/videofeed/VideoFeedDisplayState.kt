package com.dashieapp.Dashie.halite.videofeed

/**
 * Represents the complete display state for the video feed overlay system.
 * Every transition computes a new state, and the overlay manager derives all
 * visual properties from it — eliminating scattered boolean flags.
 *
 * Unified sidebar approach: all feeds (triggered and manual) go to the sidebar.
 * Cards can be dragged out to free-float. The sidebar card count drives compact
 * vs full-height sizing.
 */
sealed class FeedDisplayState {

    /** No feeds on the sidebar (may still have floating cards). */
    data object Idle : FeedDisplayState()

    /** Feeds displayed in the sidebar. Compact bg for 1-2, full at 3+. */
    data class Sidebar(
        val feedSize: String,
        val cardCount: Int  // Number of cards on the sidebar panel
    ) : FeedDisplayState()

    /** Screensaver mode: all feeds forced to medium sidebar. */
    data class Screensaver(
        val cardCount: Int
    ) : FeedDisplayState()

    /** Strip mode: feeds in bottom strip with optional drawer. */
    data object Strip : FeedDisplayState()

    // ── Derived Properties ─────────────────────────────────────

    /** Where new cards live: panel (coordinator) or none. */
    val cardContainer: CardContainer get() = when (this) {
        is Idle, is Strip -> CardContainer.NONE
        else -> CardContainer.PANEL
    }

    /** Background style for the panel area. */
    val bgStyle: BgStyle get() = when (this) {
        is Idle, is Strip -> BgStyle.NONE
        is Sidebar -> if (cardCount < 3) BgStyle.COMPACT_SIDEBAR else BgStyle.FULL_SIDEBAR
        is Screensaver -> BgStyle.FULL_SIDEBAR
    }

    /** Dismiss button style. */
    val dismissStyle: DismissStyle get() = when (this) {
        is Idle, is Strip -> DismissStyle.NONE
        is Sidebar -> DismissStyle.SIDEBAR_WIDTH
        is Screensaver -> DismissStyle.SIDEBAR_WIDTH
    }

    /** Feed size to use (screensaver forces medium). */
    val effectiveFeedSize: String get() = when (this) {
        is Screensaver -> "medium"
        is Sidebar -> feedSize
        is Strip -> "small"
        is Idle -> "medium"
    }

    /** Whether the dim overlay (tap-to-dismiss full-screen layer) is visible. */
    val showDimOverlay: Boolean get() = false

    /** Whether cards should use the scroll container (3+ in sidebar modes). */
    val useScrollMode: Boolean get() = when (this) {
        is Sidebar -> cardCount >= 3
        is Screensaver -> cardCount >= 3
        else -> false
    }

    /** Coordinator layout mode string. Always sidebar now. */
    val coordinatorLayoutMode: String get() = "sidebar"

    /** Whether tapping a card body should maximize it. */
    val tapToMaximize: Boolean get() = when (this) {
        is Sidebar -> true
        else -> false
    }

    /** Whether cards are draggable (sidebar cards are not — they use drag-to-unpin). */
    val cardsDraggable: Boolean get() = false

    /** Whether cards are resizable (sidebar cards are not). */
    val cardsResizable: Boolean get() = false

    /** Whether coordinator sidebar should be active. */
    val coordinatorSidebar: Boolean get() = when (this) {
        is Sidebar -> true
        else -> false
    }
}

enum class CardContainer { NONE, PANEL }

enum class BgStyle {
    NONE,
    COMPACT_SIDEBAR,    // 1-2 cards, bottom-right, height matches content
    FULL_SIDEBAR,       // 3+ cards or screensaver, full-height right column
}

enum class DismissStyle {
    NONE,
    SIDEBAR_WIDTH,      // matches panel width, bottom-right
    BOTTOM_CENTER,      // fixed-width pill centered at the bottom of the dashboard
                        // area — used for feedLocation="notification" alerts that
                        // float over the dashboard rather than sit in the sidebar
}
