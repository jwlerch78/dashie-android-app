package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.ANIMATION_DURATION_MS
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.SPEAKING_DISPLAY_DURATION_MS
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.readingBufferMs

/**
 * The "notification" result card: the bottom-centered card showing the spoken
 * response + the user's command, with a slide-up entrance and an auto-dismiss
 * timer. Extracted from [VoiceIndicatorController].
 *
 * Holds the card's own `isShowing` state and dismiss timer; reads/writes only the
 * card + overlay views via [views], so it survives a reattach (rebind).
 */
internal class VoiceResultCardView(
    private val views: VoiceIndicatorViews,
    private val animator: VoiceIndicatorAnimator,
    private val handler: Handler
) {
    /** Result-card icon: drawable resource id, supplied by the controller so a
     *  settings change between HA / Dashie Cloud takes effect on the next card. */
    var iconProvider: (() -> Int?)? = null

    /** Invoked after the card finishes dismissing — controller clears lastCommand. */
    var onDismissed: (() -> Unit)? = null

    var isShowing: Boolean = false
        private set

    private var dismissRunnable: Runnable? = null

    /**
     * Show the result card with response and command.
     * Independent of voice state — stays for [SPEAKING_DISPLAY_DURATION_MS] then
     * auto-dismisses (or until [onTtsSpeechEnd] reschedules it).
     */
    fun show(response: String, command: String, chrome: Boolean = true, iconRes: Int? = null, compact: Boolean = false) {
        // Cancel any pending dismiss
        dismissRunnable?.let { handler.removeCallbacks(it) }

        // Clear transient state UI (listening line, thinking dots, error) and stop
        // their animations. On the fast LISTENING->SPEAKING path the 1s processing
        // runnable is cancelled before it fires, which previously left the orange
        // listening bar over the response.
        views.hideTransientElements()
        animator.stopAll()

        // Ensure overlay is visible (without backdrop)
        if (views.overlay.visibility != View.VISIBLE) {
            views.overlay.visibility = View.VISIBLE
            views.overlay.alpha = 1f
        }

        // Set the text content. An EMPTY command line must go GONE, not just blank —
        // its 10dp top margin otherwise pushes the response text off vertical center
        // (visible on the FB19/FB28 notices, which have no command).
        views.resultResponseText.text = response
        views.resultCommandText.text = if (command.isNotEmpty()) "\"$command\"" else ""
        views.resultCommandText.visibility = if (command.isNotEmpty()) View.VISIBLE else View.GONE

        // Chrome = the Dashie icon + X close button. A response shows both; a status
        // notice (FB19 no-speech) is chrome-less to match the JS overlay's confirmation
        // toast (voice-command-router "didn't understand"). Restored to visible here so a
        // normal response after a notice gets its chrome back.
        val chromeVis = if (chrome) View.VISIBLE else View.GONE
        views.resultHaIcon.visibility = chromeVis
        views.resultCloseButton.visibility = chromeVis
        // iconRes (e.g. the HA logo for an HA command confirmation) wins over the generic
        // iconProvider so these cards match the JS 'ha' toast's Home Assistant badge.
        if (chrome) (iconRes ?: iconProvider?.invoke())?.let { views.resultHaIcon.setImageResource(it) }

        // The card reserves 48dp of end padding for the X button; with no chrome the text
        // would sit left-of-center, so make the horizontal padding symmetric when chrome
        // is off, and restore the 48dp end reserve when it's on. Notices (chrome-less)
        // also get ~25% tighter padding + a smaller width floor so the box hugs the text.
        val d = views.rootView.resources.displayMetrics.density
        // compact = a smaller confirmation toast (HA command acks): ~25% tighter padding + width
        // floor, so a frequent "Turned on X" ack isn't as prominent as a full reply. Padding only —
        // text/icon sizes are shared state, so mutating them would shrink later non-compact cards.
        val k = if (compact) 0.75f else 1f
        val padH = ((if (chrome) 24f else 18f) * k * d).toInt()
        val padV = ((if (chrome) 20f else 14f) * k * d).toInt()
        val padEnd = if (chrome) (48f * k * d).toInt() else padH
        views.resultCardContent.setPadding(padH, padV, padEnd, padV)
        views.resultCardContent.minimumWidth = ((if (chrome) (if (compact) 220f else 300f) else 225f) * d).toInt()

        // Show the result card with slide-up animation
        views.resultCard.visibility = View.VISIBLE
        views.resultCard.alpha = 0f
        views.resultCard.translationY = 50f
        views.resultCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        isShowing = true

        // Auto-dismiss after 5 seconds
        dismissRunnable = Runnable { dismiss() }
        handler.postDelayed(dismissRunnable!!, SPEAKING_DISPLAY_DURATION_MS)
    }

    /** Update the response text on an already-visible card (deferred response arrival). */
    fun updateResponse(text: String) {
        if (views.resultCard.visibility == View.VISIBLE) {
            views.thinkingIndicator.visibility = View.GONE
            views.resultResponseText.text = text
        }
    }

    /**
     * Dismiss the result card with animation. When nothing else transient is
     * visible, fades the overlay out too.
     */
    fun dismiss() {
        isShowing = false

        views.resultCard.animate()
            .alpha(0f)
            .translationY(50f)
            .setDuration(ANIMATION_DURATION_MS)
            .withEndAction {
                views.resultCard.visibility = View.GONE
                onDismissed?.invoke()

                // If nothing else is visible, hide the overlay. But NOT while a conversation/dialog
                // panel owns the overlay — a compact HA-command card shown over an active dialog
                // would otherwise fade the whole dialog out when it auto-dismisses (premature close).
                if (views.listeningLine.visibility != View.VISIBLE &&
                    views.thinkingIndicator.visibility != View.VISIBLE &&
                    views.errorIndicator.visibility != View.VISIBLE &&
                    views.sidebarPanel.visibility != View.VISIBLE
                ) {
                    views.fadeOutOverlay()
                }
            }
            .start()
    }

    /**
     * Called when TTS finishes — reset the dismiss timer to a short post-speech
     * reading buffer scaled by on-screen text length.
     */
    fun onTtsSpeechEnd() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        if (isShowing) {
            dismissRunnable = Runnable { dismiss() }
            handler.postDelayed(dismissRunnable!!, readingBufferMs(views.resultResponseText.text.length))
        }
    }

    /** Cancel pending timers (detach/force-dismiss). Does not animate. */
    fun cancelTimers() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
    }

    /** Clear showing state + timers without animating (rebind / force-dismiss when not visible). */
    fun reset() {
        cancelTimers()
        isShowing = false
    }
}
