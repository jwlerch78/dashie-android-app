package com.dashieapp.Dashie.halite.voice

import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate
import com.dashieapp.Dashie.sidebar.SidebarScaling
import com.dashieapp.Dashie.halite.voice.VoiceIndicatorConstants.ANIMATION_DURATION_MS

/**
 * Owns every View reference for the voice indicator overlay plus the low-level
 * overlay/backdrop show-hide primitives.
 *
 * Split out of [VoiceIndicatorController] so the view layer can be (re)bound to a
 * fresh Activity content view at any time via [bind] — this is the reattach seam.
 * Before this split the indicator's view refs were captured once at construction
 * (`private val x = rootView.findViewById(...)`), so any time voice re-initialized
 * against a new/refreshed view tree without re-running setup, the retained
 * controller kept pointing at detached views and every show() silently no-op'd
 * (the "orange line / cards don't render" class of bugs). [bind] re-resolves all
 * refs, so calling it on resume rebinds to whatever the live Activity is now.
 */
internal class VoiceIndicatorViews {

    // Common view references
    lateinit var rootView: View
        private set
    lateinit var overlay: FrameLayout
    lateinit var backdrop: View
    lateinit var listeningLine: View
    /** Thin orange line at the bottom of the conversation panel (follow-up listen). */
    lateinit var sidebarConversationListenLine: View
    lateinit var thinkingIndicator: FrameLayout
    lateinit var thinkingDot1: View
    lateinit var thinkingDot2: View
    lateinit var thinkingDot3: View
    lateinit var thinkingText: TextView
    lateinit var thinkingCloseButton: ImageView
    lateinit var errorIndicator: LinearLayout
    lateinit var errorIcon: ImageView
    lateinit var errorText: TextView
    lateinit var creditLowBadge: TextView   // FB27: floating orange "Credits low" badge

    // Notification (result card) format views
    lateinit var resultCard: FrameLayout
    lateinit var resultResponseText: TextView
    lateinit var resultCommandText: TextView
    lateinit var resultCardContent: LinearLayout
    lateinit var resultCloseButton: ImageView
    lateinit var resultHaIcon: ImageView

    // Sidebar format views
    lateinit var sidebarPanel: FrameLayout
    lateinit var sidebarCloseButton: ImageView
    lateinit var sidebarUserMessage: TextView
    lateinit var sidebarThinking: LinearLayout
    lateinit var sidebarThinkingDot1: View
    lateinit var sidebarThinkingDot2: View
    lateinit var sidebarThinkingDot3: View
    lateinit var sidebarResponseText: TextView
    lateinit var sidebarAdditionalText: TextView
    lateinit var sidebarCalendarEvents: LinearLayout
    lateinit var sidebarSportsCard: LinearLayout
    lateinit var sidebarMetadata: LinearLayout
    lateinit var sidebarMetadataModel: TextView
    lateinit var sidebarMetadataCost: TextView

    // Artifact panel (full-screen two-column mode — right 1/3; holds any tool card)
    lateinit var voiceArtifactPanel: FrameLayout
    lateinit var voiceArtifactClose: ImageView
    lateinit var voiceArtifactBody: LinearLayout

    // Realtime conversation-mode transcript (stack of turns)
    lateinit var sidebarConversationScroll: ScrollView
    lateinit var sidebarConversationTurns: LinearLayout
    lateinit var sidebarResponseScroll: ScrollView   // cascade response area (toggled off in conversation mode)
    // Conversation-mode status row (top): "Thinking…" + dots
    lateinit var sidebarConversationStatus: LinearLayout
    lateinit var sidebarConversationStatusText: TextView
    lateinit var sidebarConvDot1: View
    lateinit var sidebarConvDot2: View
    lateinit var sidebarConvDot3: View

    // Scale factor for large tablets (Mio 15", 32") — matches SidebarScaling pattern,
    // folded with the widget_zoom knob. 1.0 at 100% zoom on devices without a boost.
    var scaleFactor: Float = 1f
        private set

    private var bound = false
    val isBound: Boolean get() = bound

    private var visibilityGate: NativeWidgetVisibilityGate? = null

    // The computed right-panel width (set in applySidebarScaling); restored when
    // toggling back out of full-screen mode.
    private var sidebarBaseWidthPx: Int = 0

    // Sidebar text sizes (px) captured after applySidebarScaling — the unscaled
    // baseline that full-screen mode multiplies up. Captured in [bind].
    private var sbResponsePx = 0f
    private var sbAdditionalPx = 0f
    private var sbUserPx = 0f
    private var sbThinkingPx = 0f
    private var sbConvStatusPx = 0f

    /**
     * Switch the sidebar panel between the right-edge panel (sidebar mode) and a
     * full-width fill (full-screen mode). The panel reuses the same content views in
     * both; only its width + gravity change. Entrance/exit animation is the caller's
     * (VoiceSidebarPanelView) — slide for sidebar, fade for full-screen. Also boosts
     * the text size in full-screen so it reads at a distance.
     */
    fun setSidebarFullscreen(fullscreen: Boolean) {
        if (!bound) return
        sidebarPanel.layoutParams = (sidebarPanel.layoutParams as FrameLayout.LayoutParams).apply {
            width = if (fullscreen) android.view.ViewGroup.LayoutParams.MATCH_PARENT else sidebarBaseWidthPx
            gravity = if (fullscreen) android.view.Gravity.FILL else android.view.Gravity.END
        }
        applyFullscreenTextBoost(fullscreen)
    }

    /**
     * Enter/exit the full-screen two-column layout for a tool-card artifact (sports,
     * image, or calendar). When [show] is true the conversation panel ([sidebarPanel])
     * collapses to the LEFT 60% and the artifact panel takes the RIGHT 40% (widened
     * from 1/3, 2026-07-12 — slate matchup rows wrapped at 1/3); when false the
     * artifact hides and the conversation panel is restored to full-width (the normal
     * full-screen fill). Only meaningful in full-screen mode — sidebar mode renders cards
     * inline / uses the centered popup instead.
     */
    fun setArtifactTwoColumn(show: Boolean) {
        if (!bound) return
        val screenW = rootView.resources.displayMetrics.widthPixels
        if (show) {
            val artifactW = screenW * 2 / 5
            sidebarPanel.layoutParams = (sidebarPanel.layoutParams as FrameLayout.LayoutParams).apply {
                width = screenW - artifactW
                gravity = android.view.Gravity.START
            }
            voiceArtifactPanel.layoutParams = (voiceArtifactPanel.layoutParams as FrameLayout.LayoutParams).apply {
                width = artifactW
                gravity = android.view.Gravity.END
            }
            voiceArtifactPanel.visibility = View.VISIBLE
            voiceArtifactPanel.bringToFront()
            // The artifact's top-right ✕ is the single close affordance in two-column
            // mode — hide the conversation panel's own close so there's only one.
            sidebarCloseButton.visibility = View.GONE
        } else {
            voiceArtifactPanel.visibility = View.GONE
            sidebarCloseButton.visibility = View.VISIBLE
            // Restore the conversation panel to the full-screen fill it had before the split.
            sidebarPanel.layoutParams = (sidebarPanel.layoutParams as FrameLayout.LayoutParams).apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                gravity = android.view.Gravity.FILL
            }
        }
    }

    /** Multiply the sidebar text up for full-screen (idempotent — works off the
     *  captured baseline, so toggling back to 1.0 restores the sidebar size). */
    private fun applyFullscreenTextBoost(fullscreen: Boolean) {
        val m = if (fullscreen) FULLSCREEN_TEXT_MULT else 1f
        sidebarResponseText.setTextSize(TypedValue.COMPLEX_UNIT_PX, sbResponsePx * m)
        sidebarAdditionalText.setTextSize(TypedValue.COMPLEX_UNIT_PX, sbAdditionalPx * m)
        sidebarUserMessage.setTextSize(TypedValue.COMPLEX_UNIT_PX, sbUserPx * m)
        if (sbThinkingPx > 0f) sidebarThinkingText?.setTextSize(TypedValue.COMPLEX_UNIT_PX, sbThinkingPx * m)
        if (sbConvStatusPx > 0f) sidebarConversationStatusText.setTextSize(TypedValue.COMPLEX_UNIT_PX, sbConvStatusPx * m)
    }

    /**
     * The sidebar "Thinking…" label. Resolved into this backing field in [bind]
     * BEFORE [applySidebarScaling] runs, so the scale pass can size it.
     *
     * It used to be a lazy getter gated on `bound`, but [bind] calls
     * applySidebarScaling() before setting `bound = true`, so the getter returned
     * null and `?.setTextSize` silently no-op'd — leaving the thinking text at its
     * 16sp base on big tablets (Mio) while every other sidebar label scaled. (The
     * other labels are stored fields, so they were unaffected.)
     */
    private var _sidebarThinkingText: TextView? = null
    val sidebarThinkingText: TextView?
        get() = _sidebarThinkingText

    /**
     * Bind (or rebind) all view references against [root]. Safe to call repeatedly —
     * each call re-resolves findViewById, so a fresh Activity view tree cleanly
     * replaces a stale one. The caller must re-wire button click listeners after
     * bind (the buttons are freshly resolved here).
     */
    fun bind(root: View, gate: NativeWidgetVisibilityGate?) {
        // If we were previously bound to a different overlay, drop its gate
        // registration so the gate's widget map doesn't accumulate stale views.
        if (bound) visibilityGate?.unregister(overlay)

        rootView = root
        overlay = root.findViewById(R.id.voiceIndicatorOverlay)
        backdrop = root.findViewById(R.id.voiceBackdrop)
        listeningLine = root.findViewById(R.id.listeningLine)
        sidebarConversationListenLine = root.findViewById(R.id.sidebarConversationListenLine)
        thinkingIndicator = root.findViewById(R.id.thinkingIndicator)
        thinkingDot1 = root.findViewById(R.id.thinkingDot1)
        thinkingDot2 = root.findViewById(R.id.thinkingDot2)
        thinkingDot3 = root.findViewById(R.id.thinkingDot3)
        thinkingText = root.findViewById(R.id.thinkingText)
        thinkingCloseButton = root.findViewById(R.id.thinkingCloseButton)
        errorIndicator = root.findViewById(R.id.errorIndicator)
        errorIcon = root.findViewById(R.id.errorIcon)
        errorText = root.findViewById(R.id.errorText)
        creditLowBadge = root.findViewById(R.id.creditLowBadge)

        resultCard = root.findViewById(R.id.resultCard)
        resultCardContent = root.findViewById(R.id.resultCardContent)
        resultResponseText = root.findViewById(R.id.resultResponseText)
        resultCommandText = root.findViewById(R.id.resultCommandText)
        resultCloseButton = root.findViewById(R.id.resultCloseButton)
        resultHaIcon = root.findViewById(R.id.resultHaIcon)

        sidebarPanel = root.findViewById(R.id.sidebarPanel)
        sidebarCloseButton = root.findViewById(R.id.sidebarCloseButton)
        sidebarUserMessage = root.findViewById(R.id.sidebarUserMessage)
        sidebarThinking = root.findViewById(R.id.sidebarThinking)
        _sidebarThinkingText = root.findViewById(R.id.sidebarThinkingText)
        sidebarThinkingDot1 = root.findViewById(R.id.sidebarThinkingDot1)
        sidebarThinkingDot2 = root.findViewById(R.id.sidebarThinkingDot2)
        sidebarThinkingDot3 = root.findViewById(R.id.sidebarThinkingDot3)
        sidebarResponseText = root.findViewById(R.id.sidebarResponseText)
        sidebarAdditionalText = root.findViewById(R.id.sidebarAdditionalText)
        sidebarCalendarEvents = root.findViewById(R.id.sidebarCalendarEvents)
        sidebarSportsCard = root.findViewById(R.id.sidebarSportsCard)
        sidebarMetadata = root.findViewById(R.id.sidebarMetadata)
        sidebarMetadataModel = root.findViewById(R.id.sidebarMetadataModel)
        sidebarMetadataCost = root.findViewById(R.id.sidebarMetadataCost)
        voiceArtifactPanel = root.findViewById(R.id.voiceArtifactPanel)
        voiceArtifactClose = root.findViewById(R.id.voiceArtifactClose)
        voiceArtifactBody = root.findViewById(R.id.voiceArtifactBody)
        sidebarConversationScroll = root.findViewById(R.id.sidebarConversationScroll)
        sidebarConversationTurns = root.findViewById(R.id.sidebarConversationTurns)
        sidebarResponseScroll = root.findViewById(R.id.sidebarResponseScroll)
        sidebarConversationStatus = root.findViewById(R.id.sidebarConversationStatus)
        sidebarConversationStatusText = root.findViewById(R.id.sidebarConversationStatusText)
        sidebarConvDot1 = root.findViewById(R.id.sidebarConvDot1)
        sidebarConvDot2 = root.findViewById(R.id.sidebarConvDot2)
        sidebarConvDot3 = root.findViewById(R.id.sidebarConvDot3)

        scaleFactor = SidebarScaling.effectiveMultiplier(root.context) *
            com.dashieapp.Dashie.halite.preferences.DisplaySizeScale.scale(root.context)

        applySidebarScaling()

        // Single registration covers every sub-view (backdrop, listening line,
        // thinking dots, result card, sidebar panel) — they're all children of
        // `overlay`, so hiding the parent hides them all.
        visibilityGate = gate
        gate?.register(
            overlay,
            NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
        )

        bound = true
    }

    /**
     * Scale the sidebar panel and result card for the current screen.
     *
     * Sidebar width matches the JS voice-overlay--sidebar formula:
     *   width = 30% of available grid width (screen - sidebar strip - padding)
     *   = 0.30 * (screenWidth - 60px_sidebar - 20px_padding)
     *
     * Text sizes are scaled using SidebarScaling (device boost x user pref).
     */
    private fun applySidebarScaling() {
        val dm = rootView.resources.displayMetrics
        val screenWidthPx = dm.widthPixels

        // Match JS: right column = 30% of (screen - sidebar strip 60dp - padding 20dp)
        val sidebarStripPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, dm)
        val paddingPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, dm)
        val availableWidth = screenWidthPx - sidebarStripPx - paddingPx
        val sidebarWidthPx = (availableWidth * 0.30f).toInt()
            .coerceAtLeast(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 280f, dm).toInt())

        sidebarBaseWidthPx = sidebarWidthPx
        sidebarPanel.layoutParams = (sidebarPanel.layoutParams as FrameLayout.LayoutParams).apply {
            width = sidebarWidthPx
            gravity = android.view.Gravity.END
        }

        // NOTE: the result card is intentionally left as wrap_content (no fixed
        // width). It hugs its content and is centered by the parent overlay's
        // bottom|center_horizontal gravity, which keeps the close (x) button in
        // the card's own top-right corner even for short responses. Line length
        // is bounded by the text views' maxWidth (scaled below for big tablets).

        // Scale text sizes using device boost (1.2x on Mio)
        if (scaleFactor > 1.0f) {
            // Sidebar text — spoken (voiced) text larger, additional (written) text smaller
            sidebarResponseText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f * scaleFactor)
            sidebarAdditionalText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scaleFactor)
            sidebarUserMessage.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scaleFactor)

            // Sidebar thinking indicator + conversation-mode status row (same sizes so
            // the cascade and live-conversation "thinking" graphics stay in lockstep).
            sidebarThinkingText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scaleFactor)
            sidebarConversationStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scaleFactor)
            val dotSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f * scaleFactor, dm).toInt()
            listOf(
                sidebarThinkingDot1, sidebarThinkingDot2, sidebarThinkingDot3,
                sidebarConvDot1, sidebarConvDot2, sidebarConvDot3,
            ).forEach { dot ->
                dot.layoutParams = dot.layoutParams.apply {
                    width = dotSizePx
                    height = dotSizePx
                }
            }

            // Result card text
            resultResponseText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * scaleFactor)
            resultCommandText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scaleFactor)
            // Widen the line-length cap proportionally so scaled-up text still
            // wraps to a sensibly-sized card instead of clipping early.
            resultResponseText.maxWidth =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 470f * scaleFactor, dm).toInt()
            resultCommandText.maxWidth =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 520f * scaleFactor, dm).toInt()

            // Error indicator text
            errorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scaleFactor)

            // Metadata bar (model name + cost) — base 10sp/8sp, never scaled before
            sidebarMetadataModel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * scaleFactor)
            sidebarMetadataCost.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f * scaleFactor)
        }

        // Capture the resolved sidebar text sizes (px) — the baseline that full-screen
        // mode multiplies up. getTextSize() works whether the sizes came from the
        // device-boost block above or the XML defaults (normal devices).
        sbResponsePx = sidebarResponseText.textSize
        sbAdditionalPx = sidebarAdditionalText.textSize
        sbUserPx = sidebarUserMessage.textSize
        sbThinkingPx = sidebarThinkingText?.textSize ?: 0f
        sbConvStatusPx = sidebarConversationStatusText.textSize
    }

    companion object {
        private const val TAG = "VoiceIndicatorViews"
        // Full-screen text/card boost — reads at a distance on a TV/big tablet.
        const val FULLSCREEN_TEXT_MULT = 1.3f
        // The sports card matchup (logos/names/scores) is the focal point in full-screen,
        // so it scales up more than the body text.
        const val FULLSCREEN_CARD_SCALE = 1.7f
        // Full-screen sports card width as a fraction of the screen (avoids the card
        // stretching edge-to-edge in full-screen mode).
        const val FULLSCREEN_CARD_WIDTH_FRACTION = 0.6f
    }

    /**
     * Show the overlay with optional backdrop.
     *
     * 🔴 **A FADING overlay is still `VISIBLE`, and that is what made this a silent no-op.**
     * (field, 2026-08-23: *"a wake word is said just prior to the overlay timing out … the
     * orange bar disappears from the screen with the overlay as it's dismissed, even if the user
     * is mid sentence. The stt and command still work, it's just the ux."*)
     *
     * The sequence: the result card's dismiss timer fires → `VoiceResultCardView.dismiss()` →
     * its end-action checks `listeningLine.visibility != VISIBLE`, which is true because the wake
     * has not been processed yet → `fadeOutOverlay()` starts an alpha→0 animation. The overlay
     * only becomes `GONE` when that animation *ends*, so for the whole fade it still reports
     * `VISIBLE`. A wake landing in that window reaches `showListening()` → here → the guard below
     * sees `VISIBLE` and **does nothing at all**: it neither cancels the fade nor restores alpha.
     * The listening line is then made visible on an overlay that is on its way to alpha 0 and
     * `GONE`. The pipeline is untouched, which is exactly why STT and the command still worked
     * and only the indicator vanished.
     *
     * ⚠️ The narrower-looking fix — making the card's end-action re-check — does NOT close it.
     * That check is already correct at the instant it runs; the losing window opens *after* it.
     * The fade has to be cancellable by whatever wants the overlay back, which is here.
     *
     * `ViewPropertyAnimator.withEndAction` does not run when the animation is cancelled, so the
     * `visibility = GONE` in `fadeOutOverlay` is cancelled with it rather than firing late.
     */
    fun showOverlay(showBackdrop: Boolean) {
        // Cancel any in-flight fade — including a fade-OUT, whose end action would otherwise take
        // the overlay GONE underneath the state we are about to show.
        overlay.animate().cancel()

        if (overlay.visibility == View.VISIBLE && overlay.alpha < 1f) {
            // Rescued mid-fade: the alpha is stranded wherever cancel() caught it, so it has to be
            // driven back up. Animated rather than snapped, so a wake that interrupts a dismiss
            // reads as the overlay coming back rather than as a flicker.
            //
            // Logged because this race is otherwise only observable by eye, on a ~200 ms window,
            // and "did the fix fire?" and "did the race not happen this run?" look identical
            // without it — the same reason the wake-word discards got their DROP: markers.
            Log.i(TAG, "RESCUE: wake arrived mid-dismiss — cancelled the overlay fade-out at " +
                    "alpha=${"%.2f".format(overlay.alpha)} and restored it")
            overlay.bringToFront()
            overlay.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .start()
        }

        if (overlay.visibility != View.VISIBLE) {
            overlay.visibility = View.VISIBLE
            overlay.alpha = 0f
            // Ensure voice indicator renders above any dynamically-added overlays
            // (e.g., video feed dim overlay in overlayContainer)
            overlay.bringToFront()
            overlay.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .start()
        }

        if (showBackdrop) {
            backdrop.visibility = View.VISIBLE
            backdrop.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .start()
        } else {
            backdrop.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION_MS)
                .withEndAction { backdrop.visibility = View.GONE }
                .start()
        }
    }

    /** Fade the backdrop out (keep overlay up for a card/sidebar with its own timer). */
    fun fadeOutBackdrop() {
        backdrop.animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION_MS)
            .withEndAction { backdrop.visibility = View.GONE }
            .start()
    }

    /** Fade the whole overlay out and run [onHidden] when it's GONE. */
    fun fadeOutOverlay(onHidden: () -> Unit = {}) {
        overlay.animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION_MS)
            .withEndAction {
                overlay.visibility = View.GONE
                onHidden()
            }
            .start()
    }

    /**
     * Hide the transient state elements (listening line, thinking dots, error).
     * Does NOT touch the result card or sidebar — they own their own lifecycle/timers.
     * Animation cancellation is the animator's job and is done by the caller.
     */
    fun hideTransientElements() {
        listeningLine.visibility = View.GONE
        thinkingIndicator.visibility = View.GONE
        errorIndicator.visibility = View.GONE
    }

    /** FB27: show/hide the floating "Credits low" badge (orange/white). Driven from the
     *  credit cache by the surfaces that own an active voice interaction. */
    fun setCreditLowBadge(show: Boolean) {
        creditLowBadge.visibility = if (show) View.VISIBLE else View.GONE
    }
}
