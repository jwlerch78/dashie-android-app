package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.ANIMATION_DURATION_MS
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.readingBufferMs

/**
 * The sidebar response panel: slides in from the right, shows the user's command,
 * thinking dots, the voiced response + optional additional text, a metadata bar
 * (model + cost), and calendar/sports cards. Extracted from
 * [VoiceIndicatorController].
 *
 * Owns its own slide animation + dismiss timer; reads/writes only sidebar + overlay
 * views via [views], so it survives a reattach (rebind).
 */
internal class VoiceSidebarPanelView(
    private val views: VoiceIndicatorViews,
    private val animator: VoiceIndicatorAnimator,
    private val handler: Handler
) {
    /** Invoked after the panel finishes dismissing — controller clears lastCommand. */
    var onDismissed: (() -> Unit)? = null

    /** Full-screen mode (panel fills the screen): fade in/out instead of the right-slide.
     *  Set by the controller before each render based on the display-format setting. */
    var isFullscreen: Boolean = false

    private var dismissRunnable: Runnable? = null

    /** Reveal the panel: fade for full-screen, slide-in-from-right for the sidebar. */
    private fun enterPanel() {
        views.sidebarPanel.visibility = View.VISIBLE
        if (isFullscreen) {
            views.sidebarPanel.translationX = 0f
            views.sidebarPanel.alpha = 0f
            views.sidebarPanel.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .start()
        } else {
            views.sidebarPanel.translationX = views.sidebarPanel.width.toFloat().takeIf { it > 0f } ?: 320f
            views.sidebarPanel.alpha = 1f
            views.sidebarPanel.animate()
                .translationX(0f)
                .setDuration(ANIMATION_DURATION_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    val isVisible: Boolean
        get() = views.sidebarPanel.visibility == View.VISIBLE

    /** Visible AND actually showing a response (not just thinking dots). */
    val hasResponse: Boolean
        get() = isVisible && views.sidebarResponseText.visibility == View.VISIBLE

    /**
     * Show the sidebar with thinking state: user's command + bouncing dots.
     * [statusText] is the label to show (brain progress, or "Thinking...").
     */
    fun showThinking(command: String, statusText: String) {
        views.showOverlay(showBackdrop = true)
        views.hideTransientElements()
        animator.stopAll()
        restoreCascadeArea()

        enterPanel()

        // Show user message — and HIDE it when there is none: leaving it merely
        // unset kept the PREVIOUS turn's question visible in the bubble until the
        // new transcript arrived (2026-07-06).
        if (command.isNotEmpty()) {
            views.sidebarUserMessage.text = command
            views.sidebarUserMessage.visibility = View.VISIBLE
        } else {
            views.sidebarUserMessage.visibility = View.GONE
        }

        // Show thinking dots
        views.sidebarThinking.visibility = View.VISIBLE
        views.sidebarResponseText.visibility = View.GONE
        // Reset the label — never leave the PREVIOUS turn's "Finalizing…" on a fresh query.
        views.sidebarThinkingText?.text = statusText
        animator.startSidebarDots()
    }

    /**
     * Show the sidebar with the AI response. Separates "voiced" text (bold, centered)
     * from "additional" text (smaller, below).
     */
    fun showResponse(
        response: String,
        command: String,
        additionalText: String? = null,
        modelName: String? = null,
        cost: String? = null
    ) {
        views.showOverlay(showBackdrop = true)
        views.hideTransientElements()
        animator.stopAll()
        restoreCascadeArea()

        // Ensure sidebar is visible (may already be from thinking state)
        if (views.sidebarPanel.visibility != View.VISIBLE) {
            enterPanel()
        }

        // Show user message in right-aligned bubble (hide when none — see showThinking)
        if (command.isNotEmpty()) {
            views.sidebarUserMessage.text = command
            views.sidebarUserMessage.visibility = View.VISIBLE
        } else {
            views.sidebarUserMessage.visibility = View.GONE
        }

        // Hide thinking, show response
        views.sidebarThinking.visibility = View.GONE
        animator.stopSidebarDots()

        // Voiced text (large, bold, centered)
        views.sidebarResponseText.text = response
        views.sidebarResponseText.visibility = View.VISIBLE

        // Additional text (smaller, centered below voiced text)
        if (!additionalText.isNullOrEmpty()) {
            views.sidebarAdditionalText.text = additionalText
            views.sidebarAdditionalText.visibility = View.VISIBLE
        } else {
            views.sidebarAdditionalText.visibility = View.GONE
        }

        // Metadata bar (model name + cost).
        if (!modelName.isNullOrEmpty()) {
            views.sidebarMetadataModel.text = modelName
            val costLine = cost?.takeIf { it.isNotEmpty() } ?: ""
            views.sidebarMetadataCost.text = costLine
            views.sidebarMetadataCost.visibility = if (costLine.isEmpty()) View.GONE else View.VISIBLE
            views.sidebarMetadata.visibility = View.VISIBLE
        } else {
            views.sidebarMetadata.visibility = View.GONE
        }

        // CR4 State 1 (FB27): prominent floating orange "Credits low" badge (replaces the old
        // small metadata-line note). Shown while low but still spendable.
        views.setCreditLowBadge(CreditStateHolder.billable && CreditStateHolder.low && CreditStateHolder.spendable)

        // Dismiss timer: estimate TTS speaking time from text length (~12 chars/sec)
        // plus a 5s reading buffer. onTtsSpeechEnd() can override with a shorter timer
        // if the JS signal arrives. This ensures reasonable dismiss even without it.
        val estimatedSpeakMs = (response.length / 12.0 * 1000).toLong()
        val dismissDelay = (estimatedSpeakMs + 5000L).coerceIn(8_000L, 45_000L)
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = Runnable { dismiss() }
        handler.postDelayed(dismissRunnable!!, dismissDelay)
    }

    /** Update the thinking label mid-PROCESSING (brain progress copy). */
    fun updateThinkingStatus(text: String) {
        if (views.sidebarThinking.visibility == View.VISIBLE) {
            views.sidebarThinkingText?.text = text
        }
    }

    /** Set additional (written, not spoken) text below the voiced response. */
    fun setAdditionalText(text: String) {
        if (text.isEmpty()) return
        if (isVisible) {
            views.sidebarAdditionalText.text = text
            views.sidebarAdditionalText.visibility = View.VISIBLE
        }
    }

    /** Update the metadata bar (model name + cost) once it becomes available. */
    fun setMetadata(modelName: String?, cost: String?) {
        if (!isVisible) return
        if (modelName.isNullOrEmpty()) return

        views.sidebarMetadataModel.text = modelName
        views.sidebarMetadataCost.text = cost ?: ""
        views.sidebarMetadataCost.visibility = if (cost.isNullOrEmpty()) View.GONE else View.VISIBLE
        views.sidebarMetadata.visibility = View.VISIBLE
    }

    // Sports/calendar/image cards moved to the conversation surface (VoiceConversationView)
    // in Phase 2 (build plan 20260720) — the legacy sidebar renderers were deleted. The
    // sidebar panel now carries only response TEXT (local-intercept acks, HA confirmations).

    /** Push the response text onto an already-visible panel (deferred arrival). */
    fun updateResponse(text: String) {
        if (isVisible) {
            views.sidebarThinking.visibility = View.GONE
            views.sidebarResponseText.text = text
            views.sidebarResponseText.visibility = View.VISIBLE
        }
    }

    /**
     * Called when TTS finishes — reset the dismiss timer to a short post-speech
     * reading buffer scaled by how much text/card is on screen.
     */
    fun onTtsSpeechEnd() {
        if (!isVisible) return
        dismissRunnable?.let { handler.removeCallbacks(it) }
        val sidebarChars = views.sidebarResponseText.text.length +
            (if (views.sidebarAdditionalText.visibility == View.VISIBLE) views.sidebarAdditionalText.text.length else 0) +
            (if (views.sidebarSportsCard.visibility == View.VISIBLE) 200 else 0)  // card has more to read than the spoken line
        dismissRunnable = Runnable { dismiss() }
        handler.postDelayed(dismissRunnable!!, readingBufferMs(sidebarChars))
    }

    /** Dismiss the panel — fade out for full-screen, slide-out-right for the sidebar. */
    fun dismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        animator.stopSidebarDots()

        val exit = views.sidebarPanel.animate().setDuration(ANIMATION_DURATION_MS)
        if (isFullscreen) {
            exit.alpha(0f)
        } else {
            exit.translationX(views.sidebarPanel.width.toFloat().takeIf { it > 0f } ?: 320f)
        }
        exit
            .withEndAction {
                views.sidebarPanel.visibility = View.GONE
                views.sidebarUserMessage.visibility = View.GONE
                views.sidebarThinking.visibility = View.GONE
                views.sidebarResponseText.visibility = View.GONE
                views.sidebarAdditionalText.visibility = View.GONE
                views.sidebarCalendarEvents.removeAllViews()
                views.sidebarCalendarEvents.visibility = View.GONE
                views.sidebarSportsCard.removeAllViews()
                views.sidebarSportsCard.visibility = View.GONE
                views.sidebarMetadata.visibility = View.GONE
                onDismissed?.invoke()

                // Hide overlay if nothing else is showing
                if (views.listeningLine.visibility != View.VISIBLE &&
                    views.thinkingIndicator.visibility != View.VISIBLE &&
                    views.resultCard.visibility != View.VISIBLE &&
                    views.errorIndicator.visibility != View.VISIBLE
                ) {
                    views.fadeOutOverlay()
                }
            }
            .start()
    }

    /** Cancel pending timers (detach/force-dismiss). Does not animate. */
    fun cancelTimers() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
    }

    /** Restore the cascade response area + hide the conversation-mode transcript
     *  (they share the sidebar; only one is active at a time). */
    private fun restoreCascadeArea() {
        views.sidebarConversationScroll.visibility = View.GONE
        views.sidebarConversationTurns.removeAllViews()
        views.sidebarResponseScroll.visibility = View.VISIBLE
    }
}
