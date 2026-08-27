package com.dashieapp.Dashie.halite.voice

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.DOT_ANIMATION_DURATION_MS
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.LINE_PULSE_DURATION_MS

/**
 * All voice-indicator animations: the pulsing listening line, the bouncing
 * "thinking" dots (overlay), and the sidebar thinking dots.
 *
 * Extracted from [VoiceIndicatorController]. Operates on the views held by
 * [views], so after a [VoiceIndicatorViews.bind] (reattach) the animators
 * automatically target the freshly-resolved views. Animators reference concrete
 * View objects, so [stopAll] must be called before rebinding to release the old
 * view tree's animators (the controller does this in detach).
 */
internal class VoiceIndicatorAnimator(private val views: VoiceIndicatorViews) {

    private var linePulseAnimator: ObjectAnimator? = null
    private var convLinePulseAnimator: ObjectAnimator? = null
    private var dotsAnimatorSet: AnimatorSet? = null
    private var sidebarDotsAnimatorSet: AnimatorSet? = null
    private var conversationDotsAnimatorSet: AnimatorSet? = null

    /**
     * Start pulsing animation on the listening line.
     * Matches Dashie CSS: opacity 0.5 -> 1, scaleY 1 -> 1.5
     */
    fun startLinePulse() {
        stopLinePulse()

        linePulseAnimator = ObjectAnimator.ofFloat(views.listeningLine, "alpha", 0.5f, 1f, 0.5f).apply {
            duration = LINE_PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        // Also animate scale
        ObjectAnimator.ofFloat(views.listeningLine, "scaleY", 1f, 1.5f, 1f).apply {
            duration = LINE_PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun stopLinePulse() {
        linePulseAnimator?.cancel()
        linePulseAnimator = null
        views.listeningLine.alpha = 1f
        views.listeningLine.scaleY = 1f
    }

    /** Subtle alpha-only pulse for the bottom-of-panel follow-up listen line
     *  (no scale — deliberately understated vs the main listening line). */
    fun startConvLinePulse() {
        stopConvLinePulse()
        convLinePulseAnimator = ObjectAnimator.ofFloat(
            views.sidebarConversationListenLine, "alpha", 0.4f, 0.9f, 0.4f
        ).apply {
            duration = LINE_PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    fun stopConvLinePulse() {
        convLinePulseAnimator?.cancel()
        convLinePulseAnimator = null
        views.sidebarConversationListenLine.alpha = 1f
    }

    /**
     * Start bouncing dots animation (overlay thinking indicator).
     * Matches Dashie CSS @keyframes thinking-pulse
     */
    fun startDots() {
        stopDots()

        val dots = listOf(views.thinkingDot1, views.thinkingDot2, views.thinkingDot3)
        val animators = mutableListOf<ObjectAnimator>()

        dots.forEachIndexed { index, dot ->
            // Alpha animation: 0.3 -> 1 -> 0.3
            val alphaAnimator = ObjectAnimator.ofFloat(dot, "alpha", 0.3f, 1f, 0.3f).apply {
                duration = DOT_ANIMATION_DURATION_MS
                startDelay = (index * 200).toLong() // Stagger by 200ms
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            animators.add(alphaAnimator)

            // Scale animation: 0.8 -> 1.2 -> 0.8
            val scaleXAnimator = ObjectAnimator.ofFloat(dot, "scaleX", 0.8f, 1.2f, 0.8f).apply {
                duration = DOT_ANIMATION_DURATION_MS
                startDelay = (index * 200).toLong()
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            animators.add(scaleXAnimator)

            val scaleYAnimator = ObjectAnimator.ofFloat(dot, "scaleY", 0.8f, 1.2f, 0.8f).apply {
                duration = DOT_ANIMATION_DURATION_MS
                startDelay = (index * 200).toLong()
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            animators.add(scaleYAnimator)
        }

        dotsAnimatorSet = AnimatorSet().apply {
            playTogether(animators.toList())
            start()
        }
    }

    fun stopDots() {
        dotsAnimatorSet?.cancel()
        dotsAnimatorSet = null

        // Reset dots
        listOf(views.thinkingDot1, views.thinkingDot2, views.thinkingDot3).forEach { dot ->
            dot.alpha = 1f
            dot.scaleX = 1f
            dot.scaleY = 1f
        }
    }

    fun startSidebarDots() {
        stopSidebarDots()
        val dots = listOf(views.sidebarThinkingDot1, views.sidebarThinkingDot2, views.sidebarThinkingDot3)
        sidebarDotsAnimatorSet = AnimatorSet().apply {
            val animators = dots.mapIndexed { index, dot ->
                ObjectAnimator.ofFloat(dot, View.ALPHA, 0.3f, 1f, 0.3f).apply {
                    duration = DOT_ANIMATION_DURATION_MS
                    startDelay = index * (DOT_ANIMATION_DURATION_MS / 3)
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
            }
            playTogether(animators.map { it as Animator })
            start()
        }
    }

    fun stopSidebarDots() {
        sidebarDotsAnimatorSet?.cancel()
        sidebarDotsAnimatorSet = null
    }

    /** Conversation-mode "thinking" dots (top status row). Mirrors startSidebarDots. */
    fun startConversationDots() {
        stopConversationDots()
        val dots = listOf(views.sidebarConvDot1, views.sidebarConvDot2, views.sidebarConvDot3)
        conversationDotsAnimatorSet = AnimatorSet().apply {
            val animators = dots.mapIndexed { index, dot ->
                ObjectAnimator.ofFloat(dot, View.ALPHA, 0.3f, 1f, 0.3f).apply {
                    duration = DOT_ANIMATION_DURATION_MS
                    startDelay = index * (DOT_ANIMATION_DURATION_MS / 3)
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                }
            }
            playTogether(animators.map { it as Animator })
            start()
        }
    }

    fun stopConversationDots() {
        conversationDotsAnimatorSet?.cancel()
        conversationDotsAnimatorSet = null
    }

    /** Stop every animation. Called on hide and before a rebind/detach. */
    fun stopAll() {
        stopLinePulse()
        stopConvLinePulse()
        stopDots()
        stopSidebarDots()
        stopConversationDots()
    }
}
