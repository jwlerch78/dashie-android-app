package com.dashieapp.Dashie.halite.videofeed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Expandable drawer that sits above the video feed strip.
 * Shows a single large video feed with maximize and close buttons.
 * Animates slide-up on open, crossfade on feed swap, slide-down on close.
 */
class VideoFeedDrawer(
    private val context: Context,
    private val onClose: () -> Unit,
    private val onMaximize: (ruleId: String) -> Unit
) {

    companion object {
        private const val TAG = "VideoFeedDrawer"
        private const val ANIMATE_DURATION_MS = 250L
        private const val CROSSFADE_DURATION_MS = 200L
    }

    /** The drawer's root view — add this to the strip container above the strip content. */
    val view: FrameLayout

    private val isDark get() = VideoFeedStyles.isDarkMode(context)
    private fun dp(dp: Int) = VideoFeedStyles.dpToPx(context, dp)

    private var currentCard: VideoFeedCard? = null
    private var currentRuleId: String? = null

    /** Expose current card for external use (e.g., Frigate playback controller). */
    fun getCurrentCard(): VideoFeedCard? = currentCard
    private var isVisible = false

    /** Height of the drawer in dp (large feed + button overlay padding). */
    val heightDp: Int
        get() {
            val cardHeightPx = VideoFeedStyles.cardHeightPx(context, "large")
            return (cardHeightPx / context.resources.displayMetrics.density).toInt() + 16
        }

    init {
        view = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
            clipChildren = false
        }
    }

    /**
     * Show the drawer with a feed card, animating a slide-up entrance.
     * If already showing a different feed, crossfade to the new one.
     */
    fun showFeed(card: VideoFeedCard, ruleId: String) {
        if (currentRuleId == ruleId && isVisible) {
            Log.d(TAG, "Already showing feed: $ruleId")
            return
        }

        if (isVisible && currentCard != null) {
            // Crossfade swap
            crossfadeToFeed(card, ruleId)
            return
        }

        // Fresh open — build drawer content and slide up
        currentCard?.release()
        removeCurrentCard()

        currentCard = card
        currentRuleId = ruleId

        val container = buildDrawerContent(card, ruleId)
        view.addView(container)

        // Slide-up entrance
        view.visibility = View.VISIBLE
        view.translationY = view.height.toFloat().let { if (it > 0f) it else dp(200).toFloat() }
        view.animate()
            .translationY(0f)
            .setDuration(ANIMATE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        isVisible = true
        Log.i(TAG, "Drawer opened with feed: $ruleId")
    }

    /**
     * Crossfade from the current feed to a new feed.
     */
    private fun crossfadeToFeed(newCard: VideoFeedCard, newRuleId: String) {
        val oldCard = currentCard
        val oldContainer = if (view.childCount > 0) view.getChildAt(0) else null

        currentCard = newCard
        currentRuleId = newRuleId

        val newContainer = buildDrawerContent(newCard, newRuleId)
        newContainer.alpha = 0f
        view.addView(newContainer)

        // Crossfade: old fades out, new fades in
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CROSSFADE_DURATION_MS
            addUpdateListener { anim ->
                val progress = anim.animatedValue as Float
                newContainer.alpha = progress
                oldContainer?.alpha = 1f - progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (oldContainer != null) {
                        view.removeView(oldContainer)
                    }
                    oldCard?.release()
                }
            })
        }
        animator.start()
        Log.i(TAG, "Drawer crossfade: → $newRuleId")
    }

    /**
     * Close the drawer with a slide-down animation.
     */
    fun close() {
        if (!isVisible) return

        view.animate()
            .translationY(view.height.toFloat())
            .setDuration(ANIMATE_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    view.translationY = 0f
                    currentCard?.release()
                    removeCurrentCard()
                    currentCard = null
                    currentRuleId = null
                    isVisible = false
                    view.animate().setListener(null)
                }
            })
            .start()
        Log.i(TAG, "Drawer closing")
    }

    /**
     * Immediately destroy the drawer (no animation).
     */
    fun destroy() {
        currentCard?.release()
        removeCurrentCard()
        currentCard = null
        currentRuleId = null
        isVisible = false
        view.removeAllViews()
        view.visibility = View.GONE
    }

    val isOpen: Boolean get() = isVisible
    val activeRuleId: String? get() = currentRuleId

    /**
     * If the current drawer card is pinned, detach it from the drawer and return it
     * (caller should add it to overlayContainer as floating). Returns null if not pinned.
     */
    fun detachPinnedCard(): Pair<VideoFeedCard, String>? {
        val card = currentCard ?: return null
        val ruleId = currentRuleId ?: return null
        if (!card.isPinned) return null
        return detachCard()
    }

    /**
     * Detach the current card from the drawer regardless of pin state.
     * Returns the card and ruleId, or null if no card.
     */
    fun detachCard(): Pair<VideoFeedCard, String>? {
        val card = currentCard ?: return null
        val ruleId = currentRuleId ?: return null

        // Detach from drawer without releasing
        (card.parent as? ViewGroup)?.removeView(card)
        currentCard = null
        currentRuleId = null
        isVisible = false
        view.removeAllViews()
        view.visibility = View.GONE
        return Pair(card, ruleId)
    }

    private fun removeCurrentCard() {
        if (view.childCount > 0) {
            view.removeAllViews()
        }
    }

    private fun buildDrawerContent(card: VideoFeedCard, ruleId: String): FrameLayout {
        val cardWidth = VideoFeedStyles.cardWidthPx(context, "large")
        val cardHeight = VideoFeedStyles.cardHeightPx(context, "large")

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                cardHeight + dp(16)
            )
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // Configure card for drawer mode — card uses its own overlay controls
        // isDraggable = true so user can drag feed out of drawer to auto-pin as floating
        // tapToMaximize = true so the expand button shows on the drawer focal card
        // and the user can go fullscreen. Must match CardPlacement.DrawerFocal.
        card.isDraggable = true
        card.isResizable = true
        card.tapToMaximize = true
        card.canDragToUnpin = false
        card.canDragUpToFloat = false

        container.addView(card, FrameLayout.LayoutParams(
            cardWidth, cardHeight, Gravity.CENTER_HORIZONTAL
        ))
        container.clipChildren = false
        container.clipToPadding = false

        return container
    }
}
