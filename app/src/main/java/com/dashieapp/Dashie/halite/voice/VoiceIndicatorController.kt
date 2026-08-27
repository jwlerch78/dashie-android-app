package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate
import com.dashieapp.Dashie.voice.realtime.ConversationEngine

/**
 * Orchestrator for the voice indicator overlay UI.
 *
 * Matches standard Dashie voice UI:
 * - Full-width orange gradient line when listening (pulsing animation)
 * - Three bouncing orange dots when processing ("Thinking...")
 * - Result card / sidebar panel for the spoken response
 *
 * Decomposed (2026-06-24) from a single 990-line class into focused collaborators:
 * - [VoiceIndicatorViews]    — the View references + bind/scaling + overlay primitives
 * - [VoiceIndicatorAnimator] — line pulse / thinking dots / sidebar dots
 * - [VoiceResultCardView]    — the notification result card
 * - [VoiceSidebarPanelView]  — the sidebar response panel
 *
 * This class owns the voice-state machine (`show(state)`), the deferred-show
 * timers, and the transient command/processing state. The view layer is bound via
 * [attach] and can be **rebound** at any time (e.g. on resume after the Activity
 * recreates) — this is what fixes the "orange line / cards silently don't render"
 * class of bugs: the indicator no longer holds a single set of view refs captured
 * at construction that go stale when voice re-initializes against a new view tree.
 */
class VoiceIndicatorController {

    private val views = VoiceIndicatorViews()
    private val animator = VoiceIndicatorAnimator(views)
    private val handler = Handler(Looper.getMainLooper())
    private val resultCard = VoiceResultCardView(views, animator, handler)
    private val sidebar = VoiceSidebarPanelView(views, animator, handler)
    private val conversation = VoiceConversationView(views)

    // ---- Public config (set by HaliteVoiceController.setupVoiceIndicator) ----

    /** Whether responses are shown on screen. */
    var showResponses: Boolean = true

    /** Display format: "notification", "sidebar", "fullscreen". Read dynamically so
     *  a settings change takes effect on the next interaction. */
    var displayFormatProvider: (() -> String)? = null
    private val displayFormat: String
        get() = displayFormatProvider?.invoke() ?: "notification"

    // "sidebar" and "fullscreen" share the sidebar panel rendering; fullscreen just
    // fills the screen + fades (vs the 320dp right-slide). "notification" uses the card.
    private val usesSidebarLayout: Boolean
        get() = displayFormat == "sidebar" || displayFormat == "fullscreen"

    /** Set the sidebar panel's layout + animation mode before a sidebar render. */
    private fun applySidebarMode() {
        val fullscreen = displayFormat == "fullscreen"
        views.setSidebarFullscreen(fullscreen)
        sidebar.isFullscreen = fullscreen
    }

    /** Result-card icon drawable (HA vs Dashie Cloud), read dynamically. */
    var resultIconProvider: (() -> Int)? = null

    /** Fired when the thinking UI actually appears (after the 1s delay) — pause music. */
    var onThinkingShown: (() -> Unit)? = null

    /** Fired when the user taps cancel (x) on the thinking card — abort the request. */
    var onCancelRequested: (() -> Unit)? = null

    // ---- Overlay mode (the single arbiter) ----
    //
    // The overlay is driven by TWO independent producers — the one-shot CASCADE
    // pipeline (show()/setSubtitle()/cards) and the realtime CONVERSATION engine
    // (startConversation()/conversationTranscript()). They render into overlapping
    // views, so exactly ONE may own the surface at a time. This mode is the single
    // source of truth: while CONVERSATION, every cascade entry point is a no-op, so
    // the cascade's wake-word chrome can never paint over the transcript.
    enum class OverlayMode { NONE, CASCADE, CONVERSATION }
    private var mode = OverlayMode.NONE

    // ---- Transient state ----

    private var lastCommand: String = ""
    private var waitingForResponse: Boolean = false
    // Latest brain-supplied progress label ("Searching the web…", "Finalizing…").
    // null → default "Thinking...". Survives the 1s show-delay.
    private var latestProcessingStatus: String? = null
    private var processingShowRunnable: Runnable? = null

    init {
        resultCard.iconProvider = { resultIconProvider?.invoke() }
        resultCard.onDismissed = { lastCommand = "" }
        // Closing the panel is a deliberate "cut off the response" — abort any in-flight
        // voice op (stops cascade TTS AND the realtime conversation engine's playback) and
        // dismiss the calendar agenda popup so it doesn't outlive the sidebar.
        sidebar.onDismissed = { lastCommand = ""; onCancelRequested?.invoke(); VoiceCalendarAgendaPopup.dismiss() }
    }

    // ============================================================
    // Attach / detach lifecycle (the reattach seam)
    // ============================================================

    /**
     * Bind the indicator's views to [rootView] (the Activity content view) and wire
     * the close-button listeners. Safe to call repeatedly: a second call rebinds to
     * a fresh view tree, tearing down any in-flight UI from the previous binding.
     */
    fun attach(rootView: View, visibilityGate: NativeWidgetVisibilityGate? = null) {
        // Already bound to this exact content view — nothing to do (a normal resume
        // re-binds to the same tree; don't tear down an interaction in flight).
        if (views.isBound && views.rootView === rootView) return
        if (views.isBound) {
            Log.i(TAG, "🎤 Voice indicator REATTACH — rebinding to a new content view")
            teardownTransient()
        }
        views.bind(rootView, visibilityGate)

        // Re-wire listeners against the freshly-resolved buttons.
        views.resultCloseButton.setOnClickListener { resultCard.dismiss() }
        views.sidebarCloseButton.setOnClickListener { sidebar.dismiss() }
        // In two-column agenda mode the artifact's ✕ is the sole close affordance — it
        // closes the whole voice interface (same as the conversation panel's close).
        views.voiceArtifactClose.setOnClickListener { sidebar.dismiss() }
        views.thinkingCloseButton.setOnClickListener { cancelCurrentOperation() }
    }

    /** Tear down in-flight UI/timers (e.g. before rebind). */
    fun detach() = teardownTransient()

    private fun teardownTransient() {
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        resultCard.reset()
        sidebar.cancelTimers()
        animator.stopAll()
        lastCommand = ""
        waitingForResponse = false
        latestProcessingStatus = null
    }

    // ============================================================
    // State machine
    // ============================================================

    /** Show the voice indicator with the given state. */
    fun show(state: HaVoiceService.VoiceState, subtitle: String? = null) {
        if (!views.isBound) return
        // A live conversation owns the surface — ignore cascade chrome entirely.
        if (mode == OverlayMode.CONVERSATION) {
            Log.d(TAG, "🗣️ ignoring cascade show($state) — conversation owns the overlay")
            return
        }
        mode = if (state == HaVoiceService.VoiceState.IDLE) OverlayMode.NONE else OverlayMode.CASCADE
        when (state) {
            HaVoiceService.VoiceState.IDLE -> hide()
            HaVoiceService.VoiceState.CONNECTING -> showConnecting()
            HaVoiceService.VoiceState.LISTENING -> showListening(subtitle)
            HaVoiceService.VoiceState.PROCESSING -> showProcessing(subtitle)
            HaVoiceService.VoiceState.SPEAKING -> showSpeaking(subtitle)
            HaVoiceService.VoiceState.ERROR -> showError(subtitle ?: "An error occurred")
        }
    }

    /** Hide the voice indicator (but keep a result card / responding sidebar up). */
    fun hide() {
        if (!views.isBound) return
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        animator.stopAll()

        views.listeningLine.visibility = View.GONE
        views.thinkingIndicator.visibility = View.GONE
        views.errorIndicator.visibility = View.GONE

        // Keep the overlay up only for content that has its own dismiss timer: a
        // result card, or a sidebar showing an actual RESPONSE. A sidebar showing
        // ONLY thinking dots (e.g. a silent action_done in tone mode that crossed
        // the 1s show-delay) has no response and no speech-end to dismiss it, so it
        // must be fully hidden here or it lingers forever (the "stuck spinner" bug).
        if (resultCard.isShowing || sidebar.hasResponse) {
            views.fadeOutBackdrop()
            return
        }

        // Fully dismissing — clear stored command + deferred state and the sidebar.
        lastCommand = ""
        waitingForResponse = false
        views.sidebarThinking.visibility = View.GONE
        views.sidebarPanel.visibility = View.GONE
        views.setCreditLowBadge(false)   // FB27: badge shouldn't outlive the interaction

        views.fadeOutOverlay { hideAllElements() }
    }

    /**
     * CONNECTING renders as LISTENING: the STT providers buffer mic audio from the
     * wake moment, so the device genuinely is listening while the socket connects —
     * and the "Connecting..." dots were a distracting extra state flash (John,
     * 2026-07-06). A hung connect still errors via HaVoiceService's connect watchdog
     * (state-based, not UI-based).
     */
    private fun showConnecting() {
        showListening(null)
    }

    /** Show listening state with pulsing orange line and light backdrop. */
    private fun showListening(subtitle: String?) {
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        // A new listen = a new question: drop the previous turn's command so it can't
        // render in the thinking bubble before the new transcript arrives.
        lastCommand = ""
        views.showOverlay(showBackdrop = true)
        hideAllElements()
        views.listeningLine.visibility = View.VISIBLE
        animator.startLinePulse()
    }

    /** DLG-6: the re-armed follow-up listen — show ONLY the bottom orange line. No dim backdrop and
     *  no hideAllElements, so the prior command's response card stays and this reads as a subtle
     *  "still listening" cue rather than a full voice overlay. */
    fun showListeningMinimal() {
        if (!views.isBound || mode == OverlayMode.CONVERSATION) return
        handler.post {
            processingShowRunnable?.let { handler.removeCallbacks(it) }
            views.showOverlay(showBackdrop = false)
            views.listeningLine.visibility = View.VISIBLE
            animator.startLinePulse()
        }
    }

    /**
     * Show processing state with bouncing dots ("Thinking...").
     * Delayed 1s so local commands (which complete in ~500ms) don't flash it.
     */
    private fun showProcessing(subtitle: String?) {
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        // New processing cycle → start at the default label; brain stages update it.
        latestProcessingStatus = null

        processingShowRunnable = Runnable {
            if (usesSidebarLayout) {
                applySidebarMode()
                sidebar.showThinking(lastCommand, latestProcessingStatus ?: "Thinking")
            } else {
                views.showOverlay(showBackdrop = true)
                hideAllElements()
                views.thinkingIndicator.visibility = View.VISIBLE
                views.thinkingText.text = latestProcessingStatus ?: "Thinking"
                animator.startDots()
            }
            // Notify listeners that thinking is now visible — pause music for TTS.
            onThinkingShown?.invoke()
        }
        handler.postDelayed(processingShowRunnable!!, 1000L)
    }

    /**
     * Show speaking state — display the response. If [subtitle] is null/empty (AI
     * response hasn't arrived yet), keep thinking visible and wait for [setSubtitle].
     */
    private fun showSpeaking(subtitle: String?) {
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        if (!showResponses) {
            hide()
            return
        }
        if (subtitle.isNullOrEmpty()) {
            waitingForResponse = true
            return
        }
        waitingForResponse = false
        if (usesSidebarLayout) {
            applySidebarMode()
            sidebar.showResponse(subtitle, lastCommand)
        } else {
            resultCard.show(subtitle, lastCommand)
        }
    }

    /** Show error state. */
    private fun showError(message: String) {
        views.showOverlay(showBackdrop = true)
        hideAllElements()
        views.errorIndicator.visibility = View.VISIBLE
        views.errorText.text = message
    }

    /**
     * FB19: subtle no-speech notice. Rendered through the SAME result card as a spoken
     * response / the "too short" popup (bottom-anchored, text centered in the box, slide-up,
     * auto-dismiss) — with an empty command so no stale prompt shows, and no red error dot.
     * A live conversation owns the overlay, so skip it there.
     */
    fun showNoSpeechNotice(message: String = "I didn't hear anything") = showVoiceNotice(message)

    /**
     * FB28: chrome-less native voice-feedback toast — used for BOTH the no-speech notice and
     * (via the JS bridge) the "didn't understand" short-command toast, so the two look identical
     * AND paint over native overlays (music player). [detail] is an optional subline (e.g. the
     * command heard); empty for a plain notice.
     */
    fun showVoiceNotice(message: String, detail: String = "") {
        if (!views.isBound || mode == OverlayMode.CONVERSATION) return
        resultCard.show(message, detail, chrome = false)
    }

    /**
     * Native HA command confirmation card ("Turned on String Lights"), ported from the JS 'ha'
     * toast — which rendered in the WebView, always BEHIND the opaque native voice overlay. Shows
     * WITH chrome (the HA icon) + the command, and — unlike [showVoiceNotice] — it shows even in
     * CONVERSATION mode: the result card sits at elevation 24dp, above the full-screen conversation
     * panel (16dp), so the confirmation is visible over the dialog instead of trapped behind it.
     */
    fun showHaCommandResult(message: String, command: String) {
        if (!views.isBound) return
        lastCommand = command
        // Force the Home Assistant logo (matching the JS 'ha' toast) + a compact size — these
        // are frequent command acks, not full replies.
        resultCard.show(
            message, command, chrome = true,
            iconRes = com.dashieapp.Dashie.R.drawable.icon_homeassistant_blue,
            compact = true,
        )
    }

    /**
     * Locally-intercepted MUSIC command ack ("Playing music by …") — chrome-less AND compact,
     * the closest native twin of full mode's small success toast. Chrome-less so it reads as a
     * passing notice (the music player appearing is the real confirmation), compact so it isn't
     * mistaken for a response card.
     */
    fun showMusicResult(message: String, command: String) {
        if (!views.isBound || mode == OverlayMode.CONVERSATION) return
        resultCard.show(message, command, chrome = false, compact = true)
    }

    /**
     * Scheduled-action CREATION confirmation ("Reminder · Today at 5:12 PM") — the native twin of
     * full mode's creation text line (reminder-action-handler.js). Bell icon, compact, and — like
     * [showHaCommandResult] — visible even in CONVERSATION mode (the result card draws above the
     * conversation panel), so a mid-dialog "remind me…" still gets a visual ack. Fire-time
     * presentation is separate (ReminderAlertView via ScheduledActionManager).
     */
    fun showScheduleConfirmation(message: String, detail: String) {
        if (!views.isBound) return
        resultCard.show(
            message, detail, chrome = true,
            iconRes = com.dashieapp.Dashie.R.drawable.ic_alert_bell,
            compact = true,
        )
    }

    // ============================================================
    // Content updates (called by HaliteVoiceController as data arrives)
    // ============================================================

    /**
     * Update the thinking label mid-PROCESSING with brain-supplied progress copy
     * (e.g. "Searching the web…", "Finalizing…"). Bridged from JS via
     * DashieNative.onVoiceProgress → JsBridgeVoiceDelegate → here.
     */
    fun updateProcessingStatus(text: String) {
        if (text.isBlank() || mode == OverlayMode.CONVERSATION) return
        handler.post {
            latestProcessingStatus = text
            if (!views.isBound) return@post
            if (views.thinkingIndicator.visibility == View.VISIBLE) views.thinkingText.text = text
            sidebar.updateThinkingStatus(text)
        }
    }

    /**
     * Conversation-mode (cascade Dialog) sibling of [updateProcessingStatus]: live brain
     * progress ("Searching the web…", "Finalizing…") shown on the dialog overlay's thinking
     * label ([VoiceIndicatorViews.sidebarConversationStatusText], default "Thinking…").
     * No-ops outside CONVERSATION mode, so the caller can fire both without a mode check.
     */
    fun updateConversationStatus(text: String) {
        if (text.isBlank() || mode != OverlayMode.CONVERSATION) return
        handler.post {
            if (!views.isBound) return@post
            // Only while the thinking status row is up (not during listening/response).
            if (views.sidebarConversationStatus.visibility == View.VISIBLE) {
                views.sidebarConversationStatusText.text = text
            }
        }
    }

    /** Live brain progress (thinking → "Searching the web…" → "Finalizing…") → whichever
     *  surface the current mode owns. Each sibling self-guards on OverlayMode, so this
     *  routes to exactly one. */
    fun updateBrainProgress(text: String) {
        updateProcessingStatus(text)
        updateConversationStatus(text)
    }

    /**
     * Store the STT result (command) for display with the response.
     * No longer shows a separate toast — the command is shown below the response.
     */
    fun showSttToast(text: String) {
        lastCommand = SttDisplayNormalizer.normalize(text)
    }

    /**
     * Update response text (e.g. when the deferred AI response is received).
     * If we were waiting for a deferred response, this triggers the actual
     * card/sidebar display with the dismiss timer.
     */
    fun setSubtitle(text: String) {
        if (text.isEmpty() || !views.isBound || mode == OverlayMode.CONVERSATION) return

        if (waitingForResponse) {
            waitingForResponse = false
            if (usesSidebarLayout) {
                applySidebarMode()
                sidebar.showResponse(text, lastCommand)
            } else {
                resultCard.show(text, lastCommand)
            }
            return
        }

        // Otherwise update whichever card/sidebar is already visible. The response
        // has arrived, so the thinking indicator must go (else the dots linger above
        // the answer — the "Thinking… + response both showing" bug).
        resultCard.updateResponse(text)
        sidebar.updateResponse(text)
    }

    /**
     * Set the additional display text (shown below the voiced text in the sidebar).
     * This is the response.text field — extra info not spoken aloud.
     */
    fun setAdditionalText(text: String) {
        if (!views.isBound || mode == OverlayMode.CONVERSATION) return
        sidebar.setAdditionalText(text)
    }

    /**
     * Called when TTS finishes speaking the response. Resets the dismiss timer to a
     * short post-speech reading buffer.
     */
    fun onTtsSpeechEnd() {
        if (!views.isBound) return
        // In CONVERSATION mode the cascade loop owns the lifecycle (re-arm → 8s idle →
        // endConversation); the per-response auto-dismiss timer must NOT run, or in fullscreen
        // (where the sidebar surface IS visible) it fires sidebar.onDismissed → onCancelRequested
        // and tears down the dialog mid-follow-up. Matches the CONVERSATION guard on every other
        // cascade-display entry point.
        if (mode == OverlayMode.CONVERSATION) return
        if (!sidebar.isVisible && !resultCard.isShowing) return
        sidebar.onTtsSpeechEnd()
        resultCard.onTtsSpeechEnd()
    }

    // ============================================================
    // Realtime conversation mode (on-demand S2S, build plan §3.1)
    // ============================================================

    /** Open the conversation transcript panel and start listening. Honors the
     *  display-format setting: fullscreen fills the screen, otherwise the 320dp
     *  right panel. */
    fun startConversation() {
        if (!views.isBound) return
        handler.post {
            mode = OverlayMode.CONVERSATION       // conversation now owns the surface; cascade calls become no-ops
            conversationLingerRunnable?.let { handler.removeCallbacks(it) }  // cancel any pending linger fade
            lastConversationCardKey = null        // fresh conversation → any card may render
            hideCascadeChrome()                   // clear any cascade thinking/response left from the wake-word STT
            Log.d(TAG, "🗣️ startConversation (mode=CONVERSATION, cascade chrome cleared)")
            val fullscreen = displayFormat == "fullscreen"
            views.setSidebarFullscreen(fullscreen)
            conversation.prepare(fullscreen)      // arm, but DON'T show the panel yet
            views.showOverlay(showBackdrop = true) // so the listening/thinking affordance renders
            // Open on THINKING dots, not the orange line: every entry path starts with a
            // command already captured/in-flight (cascade dialog runs the brain turn; Live
            // connects then commits initialText), so the mic is NOT open yet. The engines
            // drive the real state — Live emits LISTENING from activate() when its mic
            // starts; the cascade emits it from cascadeReListen after TTS ends. Asserting
            // listening here raced those events and left the line on through the whole
            // first turn (mic closed, Piper still speaking — John, 2026-07-13).
            setConversationListening(false)
        }
    }

    /** Append a streaming transcript chunk to the conversation stack. [additionalText]
     *  is the written (unspoken) elaboration shown below the spoken line; null for
     *  user turns and Live (S2S has no separate written text). */
    fun conversationTranscript(speaker: ConversationEngine.Speaker, text: String, additionalText: String?, isFinal: Boolean) {
        if (!views.isBound) return
        // Display-only "dashi/dashy/dashee" → "dashie" fix; the raw transcript
        // already went to the engine/brain before this UI hop.
        val shown = SttDisplayNormalizer.normalize(text)
        handler.post { conversation.onTranscript(speaker, shown, additionalText, isFinal) }
    }

    /** Identity of the last card rendered this conversation — the replay de-dupe key.
     *  Reset when the panel starts/clears so a NEW conversation can re-show anything. */
    private var lastConversationCardKey: String? = null

    /** A card's identity for the replay de-dupe: images by URL (the same photo re-fetched
     *  on a follow-up is the field case), everything else by full payload — a sports card
     *  with a CHANGED score must still render. Calendar-write flow cards are exempt: they
     *  REPLACE each other by design (20260713 §2.7) and never stack. */
    private fun conversationCardKey(cardJson: org.json.JSONObject): String? {
        val type = cardJson.optString("type").takeIf { it.isNotEmpty() } ?: return null
        if (type == "calendar" && cardJson.optString("op").startsWith("write")) return null
        if (type == "image") return "image:" + cardJson.optString("url")
        return "$type:$cardJson"
    }

    /** Render a tool card (e.g. a sports scorecard) inline in the current turn. The
     *  payload is the registry tool's card JSON, forwarded by the relay.
     *
     *  Replay de-dupe: in conversation mode the model/relay re-emits the PRIOR turn's card
     *  on unrelated follow-ups (field 2026-06-29: the same sports score re-rendered; 07-10:
     *  the same image_search photo stacked on every follow-up). A card identical to the one
     *  just rendered is skipped — it is already on screen in the transcript stack. */
    fun conversationCard(cardJson: org.json.JSONObject) {
        if (!views.isBound) return
        val key = conversationCardKey(cardJson)
        if (key != null && key == lastConversationCardKey) {
            Log.w(TAG, "DROP: duplicate ${cardJson.optString("type")} card suppressed — identical to the prior turn's (replay)")
            return
        }
        if (key != null) lastConversationCardKey = key
        // Dispatch on card.type → the matching renderer (a new tool card type adds a
        // branch here + a parser/renderer; the relay/engine/coordinator stay type-agnostic).
        when (cardJson.optString("type")) {
            "sports" -> VoiceOverlayBridge.parseSportsCard(cardJson)?.let { card ->
                val slate = card.games
                if (slate.isNullOrEmpty()) {
                    handler.post { conversation.showCard(card) }   // single rich card
                } else handler.post {
                    val fullscreen = displayFormat == "fullscreen"
                    when {
                        // >3 games, full-screen → right-1/3 agenda artifact.
                        slate.size > 3 && fullscreen -> conversation.showSportsAgenda(slate, card.league)
                        // >3 games, sidebar → central agenda popup left of the voice sidebar.
                        slate.size > 3 -> VoiceSportsAgendaPopup.show(views.rootView.context, slate, card.league, views.sidebarPanel.width)
                        // 1–3 → inline full cards in the current turn.
                        else -> conversation.showSportsCards(slate)
                    }
                }
            }
            "image" -> VoiceOverlayBridge.parseImageCard(cardJson)?.let { card -> handler.post { conversation.showImageCard(card) } }
            "calendar" -> {
                val events = VoiceOverlayBridge.parseCalendarEvents(cardJson)
                // org.json renders a JSON null as the string "null" — treat it as no member.
                val member = cardJson.optString("member", "").takeIf { it.isNotEmpty() && it != "null" }
                // Calendar-WRITE flow cards (op write_preview/write_receipt, always 1 event):
                // each REPLACES the flow's previous card so preview → corrected preview →
                // receipt never stacks (design 20260713 §2.7; old JS sends no op → read path).
                if (cardJson.optString("op").startsWith("write")) {
                    if (events.isNotEmpty()) handler.post { conversation.showCalendarWriteCard(events) }
                    return
                }
                if (events.isNotEmpty()) handler.post {
                    val fullscreen = displayFormat == "fullscreen"
                    when {
                        // >3 events, full-screen → two-column agenda artifact (right 1/3); the
                        // transcript collapses to the left 2/3.
                        events.size > 3 && fullscreen -> conversation.showCalendarAgenda(events, member)
                        // >3 events, sidebar → central agenda popup, centered in the space LEFT
                        // of the voice sidebar (pass its width as the inset).
                        events.size > 3 -> VoiceCalendarAgendaPopup.show(views.rootView.context, events, member, views.sidebarPanel.width)
                        // ≤3 → inline date-block cards in the current turn.
                        else -> conversation.showCalendarCards(events)
                    }
                }
            }
        }
    }

    /**
     * Toggle the activity indicator between LISTENING (the orange pulsing line, its
     * normal affordance) and THINKING/SPEAKING (the dots below the bubble) — reusing
     * the existing visuals in place, per the design.
     */
    fun setConversationListening(listening: Boolean) {
        if (!views.isBound) return
        handler.post {
            animator.stopLinePulse()
            animator.stopConvLinePulse()
            animator.stopConversationDots()
            Log.d(TAG, "🗣️ setConversationListening($listening) panelVisible=${conversation.isVisible}")
            if (listening) {
                views.sidebarConversationStatus.visibility = View.GONE
                // ALWAYS the full-width bottom-of-screen line (it sits at 20dp elevation,
                // above the 16dp panel, so it overlays the panel edge too). The old
                // panel-bottom variant only spanned the panel, which read as a broken
                // half-line whenever a card/pictures panel was up (2026-07-13).
                views.sidebarConversationListenLine.visibility = View.GONE
                views.listeningLine.visibility = View.VISIBLE
                animator.startLinePulse()
            } else {
                views.listeningLine.visibility = View.GONE
                views.sidebarConversationListenLine.visibility = View.GONE
                views.sidebarConversationStatus.visibility = View.VISIBLE
                animator.startConversationDots()
            }
        }
    }

    /**
     * End conversation mode. Always stops the listening/thinking affordances (orange
     * lines, dots). When [leaveResponseUp] is true (a quiet idle timeout), the last
     * response stays on screen and auto-dismisses on the normal reading timer — same
     * as a one-shot cascade answer. Otherwise tear the panel down immediately.
     */
    fun endConversation(leaveResponseUp: Boolean = false) {
        if (!views.isBound) return
        handler.post {
            Log.d(TAG, "🗣️ endConversation(leaveResponseUp=$leaveResponseUp)")
            mode = OverlayMode.NONE   // release the surface back to the cascade
            animator.stopAll()
            views.listeningLine.visibility = View.GONE
            views.sidebarConversationListenLine.visibility = View.GONE
            views.sidebarConversationStatus.visibility = View.GONE

            lastConversationCardKey = null   // panel is ending — a new session may re-show anything
            if (leaveResponseUp && conversation.isVisible) {
                // Keep the transcript panel up; fade it out after a reading window
                // sized to the last reply (mirrors the cascade's post-speech dwell).
                val dwell = VoiceIndicatorConstants.readingBufferMs(conversation.lastResponseChars)
                conversationLingerRunnable?.let { handler.removeCallbacks(it) }
                conversationLingerRunnable = Runnable {
                    conversation.clear()
                    views.sidebarPanel.visibility = View.GONE
                    views.fadeOutOverlay()
                }
                handler.postDelayed(conversationLingerRunnable!!, dwell)
            } else {
                conversationLingerRunnable?.let { handler.removeCallbacks(it) }
                conversation.clear()
                views.sidebarPanel.visibility = View.GONE
                views.fadeOutOverlay()
            }
        }
    }

    private var conversationLingerRunnable: Runnable? = null

    /** Hard-hide every cascade (one-shot) chrome element so only the conversation
     *  panel renders. The cascade pipeline shows these during the wake-word STT that
     *  precedes a conversation; without clearing them they stack on the transcript
     *  ("two thinking bars / two sidebars"). Future cascade shows are gated upstream
     *  (HaliteVoiceController.conversationUiActive). */
    private fun hideCascadeChrome() {
        // Cancel any DELAYED cascade shows still queued (the 1s/1.5s show-delays) — else
        // they fire after the conversation panel is up and re-paint thinking on top.
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        animator.stopDots()
        animator.stopSidebarDots()
        views.thinkingIndicator.visibility = View.GONE
        views.resultCard.visibility = View.GONE
        views.sidebarThinking.visibility = View.GONE
        views.sidebarUserMessage.visibility = View.GONE
        views.sidebarResponseText.visibility = View.GONE
        views.sidebarAdditionalText.visibility = View.GONE
        views.sidebarResponseScroll.visibility = View.GONE
        views.sidebarCalendarEvents.visibility = View.GONE
        views.sidebarSportsCard.visibility = View.GONE
        views.sidebarMetadata.visibility = View.GONE
    }

    // ============================================================
    // Cancel / interrupt
    // ============================================================

    /**
     * Cancel the current in-flight operation from the thinking-card x button. Tears
     * down all voice UI immediately, then asks the owner to abort the request.
     */
    private fun cancelCurrentOperation() {
        forceDismiss()
        onCancelRequested?.invoke()
    }

    /**
     * Force-dismiss everything (sidebar, result card, thinking, overlay).
     * Called when a wake word interrupts an in-flight voice interaction.
     */
    fun forceDismiss() {
        if (!views.isBound) return
        mode = OverlayMode.NONE
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        conversationLingerRunnable?.let { handler.removeCallbacks(it) }
        resultCard.cancelTimers()
        sidebar.cancelTimers()
        waitingForResponse = false
        views.sidebarConversationListenLine.visibility = View.GONE
        views.sidebarConversationStatus.visibility = View.GONE
        lastConversationCardKey = null
        conversation.clear()

        if (sidebar.isVisible) sidebar.dismiss()
        if (views.resultCard.visibility == View.VISIBLE) resultCard.dismiss() else resultCard.reset()

        views.listeningLine.visibility = View.GONE
        views.thinkingIndicator.visibility = View.GONE
        views.errorIndicator.visibility = View.GONE
        views.sidebarThinking.visibility = View.GONE
        animator.stopAll()
    }

    /** Clean up resources. */
    fun destroy() {
        processingShowRunnable?.let { handler.removeCallbacks(it) }
        animator.stopAll()
        resultCard.cancelTimers()
        sidebar.cancelTimers()
    }

    // ============================================================
    // Helpers
    // ============================================================

    /**
     * Hide all state-specific elements (listening line, thinking, error) and stop
     * their animations. The result card and sidebar are NOT touched here — they own
     * their own lifecycle/timers.
     */
    private fun hideAllElements() {
        views.hideTransientElements()
        animator.stopAll()
    }

    companion object {
        private const val TAG = "VoiceIndicator"
    }
}
