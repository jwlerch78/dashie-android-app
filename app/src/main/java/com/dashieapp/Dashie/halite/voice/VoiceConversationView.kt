package com.dashieapp.Dashie.halite.voice

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.voice.realtime.ConversationEngine

/**
 * Realtime "conversation mode" transcript — a downward-growing stack of turns in
 * the sidebar panel (build plan §3.1). Each turn is the user's prompt (white
 * bubble, reusing the cascade's sidebar bubble style) above Dashie's response.
 * The newest turn sits at the TOP in full color; as a new turn begins the prior
 * one is demoted (dimmed/gray) and pushed down. Listening vs thinking is shown
 * by the existing orange line / dots (driven by the controller), not here.
 *
 * [VoicePromoMode] (adb-set screenshot flag) flips both conventions: turns build
 * in chat order (newest at BOTTOM) and demotion is skipped, so a finished
 * conversation reads top-to-bottom at full contrast.
 *
 * Drives only views in [VoiceIndicatorViews], so it survives a reattach (rebind).
 */
internal class VoiceConversationView(
    private val views: VoiceIndicatorViews,
) {
    private class Turn(val container: LinearLayout, val bubble: TextView, val card: LinearLayout, val response: TextView, val additional: TextView) {
        var answered = false   // a final Dashie reply landed → next user prompt starts a new turn
    }

    private var current: Turn? = null
    private var fullscreen = false

    // Promo/screenshot mode — read live so an adb toggle applies to the next
    // conversation without an app restart (SharedPreferences reads are in-memory).
    private val promoMode: Boolean
        get() = VoicePromoMode.isEnabled(views.rootView.context)

    // DLG-2: single-game sports cards already shown this conversation, so a follow-up about
    // the same game (e.g. "who scored?") doesn't stack an identical card. Keyed on the visible
    // identity (teams + scores + state) so a genuinely updated live score still renders. Cleared
    // per conversation in [prepare]/[clear].
    private val shownSportsCards = HashSet<String>()

    // Calendar cards read small in the conversation panel — render them 25% larger
    // (2026-07-13). Applies to read AND write-flow cards for consistency.
    private val CALENDAR_CARD_SCALE = 1.25f

    /** Char count of the most recent Dashie reply — sizes the post-session reading
     *  timer when an idle close leaves the response on screen. */
    var lastResponseChars = 0
        private set

    // Text-size multiplier: device/zoom scale × fullscreen boost.
    private val mult: Float
        get() = views.scaleFactor * (if (fullscreen) 1.3f else 1f)

    val isVisible: Boolean
        get() = views.sidebarConversationScroll.visibility == View.VISIBLE

    /** Arm a new session WITHOUT showing the panel: reset turns + remember the
     *  fullscreen flag, and keep the panel hidden. The panel is revealed lazily by
     *  [onTranscript] once the first STT result arrives (until then it's just the
     *  orange line). */
    fun prepare(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        views.sidebarConversationTurns.removeAllViews()
        current = null
        shownSportsCards.clear()
        lastCalendarWriteCard = null
        views.sidebarConversationScroll.visibility = View.GONE
        views.sidebarPanel.visibility = View.GONE
    }

    /** Reveal the conversation panel; hide the cascade's single-turn sidebar views. */
    fun show(fullscreen: Boolean) {
        this.fullscreen = fullscreen
        views.showOverlay(showBackdrop = true)
        views.sidebarPanel.visibility = View.VISIBLE
        views.sidebarPanel.translationX = 0f
        views.sidebarPanel.alpha = 1f
        // The cascade's static turn views aren't used in conversation mode. Hiding
        // the cascade response ScrollView frees its layout_weight so the transcript
        // scroll gets the full panel height.
        views.sidebarUserMessage.visibility = View.GONE
        views.sidebarResponseText.visibility = View.GONE
        views.sidebarAdditionalText.visibility = View.GONE
        views.sidebarResponseScroll.visibility = View.GONE
        views.sidebarConversationScroll.visibility = View.VISIBLE

        // CR4 State 1 (FB27): prominent floating orange "Credits low" badge — shows on BOTH
        // the sidebar and full-screen conversation modes (the old small metadata-line note only
        // showed in sidebar mode and was too small).
        views.setCreditLowBadge(CreditStateHolder.billable && CreditStateHolder.low && CreditStateHolder.spendable)
    }

    /**
     * Feed one streaming transcript chunk. [text] is the running text for the
     * current turn; [isFinal] marks the turn's side complete.
     */
    fun onTranscript(speaker: ConversationEngine.Speaker, text: String, additionalText: String?, isFinal: Boolean) {
        if (text.isBlank()) return
        if (!isVisible) show(fullscreen)

        when (speaker) {
            ConversationEngine.Speaker.USER -> {
                // A new user prompt after the prior turn was answered starts a new turn.
                val t = current
                if (t == null || t.answered) startTurn().bubble.apply { this.text = text; visibility = View.VISIBLE }
                else { t.bubble.text = text; t.bubble.visibility = View.VISIBLE }
            }
            ConversationEngine.Speaker.DASHIE -> {
                val t = current ?: startTurn()
                t.response.text = text
                t.response.visibility = View.VISIBLE
                // DLG-4: the written (unspoken) elaboration, shown smaller below the spoken
                // reply — mirrors single-mode's sidebarResponseText / sidebarAdditionalText split.
                if (!additionalText.isNullOrBlank()) {
                    t.additional.text = additionalText
                    t.additional.visibility = View.VISIBLE
                }
                lastResponseChars = text.length
                if (isFinal) t.answered = true
            }
        }
        scrollToLatest()
    }

    /** Remove all turns and hide the panel (session end). */
    fun clear() {
        VoiceCalendarAgendaPopup.dismiss()   // the agenda shouldn't outlive the conversation
        VoiceSportsAgendaPopup.dismiss()
        views.setArtifactTwoColumn(false)    // collapse the two-column artifact, restore full width
        views.voiceArtifactBody.removeAllViews()   // drop accumulated full-screen artifacts
        views.sidebarConversationTurns.removeAllViews()
        current = null
        shownSportsCards.clear()
        lastCalendarWriteCard = null
        views.sidebarConversationScroll.visibility = View.GONE
        views.setCreditLowBadge(false)   // FB27: badge shouldn't outlive the conversation
    }

    // ── turn building ────────────────────────────────────────────────────

    private fun startTurn(): Turn {
        current?.let { if (!promoMode) demote(it) }
        val ctx = views.rootView.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                // Promo mode never gets demotion's 40dp history gap, so give consecutive
                // turns a slightly bigger margin to still read as separate exchanges.
            ).apply { topMargin = dp(if (promoMode) 16f else 10f) }
        }
        val bubble = TextView(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.sidebar_user_message_background)
            setTextColor(0xFF000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * mult)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            // LEFT-anchored (like the single-turn sidebarUserMessage): Gravity.END tracked the
            // panel's right edge, so the bubble jumped sideways when the panel collapsed to the
            // left 2/3 as the artifact (visual-aid) panel opened. START pins its left edge at the
            // panel's x=0 in both full-screen states — no jump.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START; marginEnd = dp(48f) }
            visibility = View.GONE
        }
        // Card slot (e.g. a sports scorecard) — sits ABOVE the response, which builds
        // below it. Empty/hidden until a tool emits a card for this turn.
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16f) }   // breathing room before the card
            visibility = View.GONE
        }
        val response = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * mult)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6f) }
            visibility = View.GONE
        }
        // DLG-4: written elaboration below the spoken reply — smaller, not bold, dimmer,
        // matching the single-mode sidebarAdditionalText styling. Hidden until a turn has it.
        val additional = TextView(ctx).apply {
            setTextColor(0xFFCCCCCC.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * mult)
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) }
            visibility = View.GONE
        }
        container.addView(bubble)
        container.addView(card)
        container.addView(response)
        container.addView(additional)
        if (promoMode) views.sidebarConversationTurns.addView(container)      // chat order: newest at bottom
        else views.sidebarConversationTurns.addView(container, 0)             // newest at top
        return Turn(container, bubble, card, response, additional).also { current = it }
    }

    /** Render a sports card — in the right artifact panel (full-screen) or inline in the
     *  current turn (sidebar). DLG-2: a single-game card identical to one already shown this
     *  conversation is skipped (a follow-up about the same game shouldn't stack a dup card). */
    fun showCard(card: VoiceOverlayBridge.SportsCard) {
        if (card.games == null) {   // single-game card (slate cards go through showSportsCards)
            val key = "${card.home.name}:${card.home.score}|${card.away.name}:${card.away.score}|${card.state}|${card.statusLine}"
            if (!shownSportsCards.add(key)) return   // already shown → no duplicate
        }
        if (!isVisible) show(fullscreen)
        val view = VoiceSportsCardRenderer.createCard(views.rootView.context, card, mult)
        if (fullscreen) addArtifact(view) else inlineCard(view)
    }

    /** Render 1–3 slate games as full sports cards — artifact panel (full-screen) or inline
     *  (sidebar). The >3 case uses the agenda ([showSportsAgenda]) / popup. */
    fun showSportsCards(games: List<VoiceOverlayBridge.SportsSlateEntry>) {
        if (games.isEmpty()) return
        if (!isVisible) show(fullscreen)
        val ctx = views.rootView.context
        val block = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        VoiceSportsCardRenderer.addSlateCardsToContainer(ctx, block, games, mult)
        if (fullscreen) addArtifact(block) else inlineCard(block)
    }

    /** Render a long ( >3 ) sports slate in the right-1/3 artifact panel (full-screen),
     *  titled by [league]. Sidebar mode uses the centered popup instead. */
    fun showSportsAgenda(games: List<VoiceOverlayBridge.SportsSlateEntry>, league: String?) {
        if (games.isEmpty()) return
        if (!isVisible) show(fullscreen)
        val ctx = views.rootView.context
        val block = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        block.addView(TextView(ctx).apply {
            // leagueLabel: "mlb" reads as an acronym → "MLB"; "World Cup" passes through.
            text = VoiceSportsAgendaRenderer.leagueLabel(league?.takeIf { it.isNotEmpty() }) ?: "Games"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * mult)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        VoiceSportsAgendaRenderer.render(ctx, block, games, mult)
        addArtifact(block)
    }

    /** Render an image card — artifact panel (full-screen) or inline (sidebar). */
    fun showImageCard(card: VoiceOverlayBridge.ImageCard) {
        if (!isVisible) show(fullscreen)
        val view = VoiceImageCardRenderer.createCard(views.rootView.context, card, mult)
        if (fullscreen) addArtifact(view) else inlineCard(view)
    }

    /** Render up to 3 calendar event cards — artifact panel (full-screen) or inline
     *  (sidebar). The >3 case uses the agenda ([showCalendarAgenda]) / popup. */
    fun showCalendarCards(events: List<VoiceOverlayBridge.CalendarEvent>) {
        if (events.isEmpty()) return
        if (!isVisible) show(fullscreen)
        val ctx = views.rootView.context
        val block = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        VoiceCalendarCardRenderer.addCardsToContainer(ctx, block, events, CALENDAR_CARD_SCALE)
        if (fullscreen) addArtifact(block) else inlineCard(block)
    }

    // The calendar-WRITE flow's card (op write_preview/write_receipt) — each new one
    // REPLACES the previous within the conversation, so a preview → corrected preview →
    // receipt shows ONE card, not a stack (2026-07-13; the 3-card screenshot).
    private var lastCalendarWriteCard: View? = null

    /** Render a calendar-write preview/receipt card, replacing the flow's prior card. */
    fun showCalendarWriteCard(events: List<VoiceOverlayBridge.CalendarEvent>) {
        if (events.isEmpty()) return
        if (!isVisible) show(fullscreen)
        (lastCalendarWriteCard?.parent as? ViewGroup)?.removeView(lastCalendarWriteCard)
        lastCalendarWriteCard = null
        val ctx = views.rootView.context
        val block = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        VoiceCalendarCardRenderer.addCardsToContainer(ctx, block, events, CALENDAR_CARD_SCALE)
        lastCalendarWriteCard = block
        if (fullscreen) addArtifact(block) else inlineCard(block)
    }

    /** Render a long ( >3 ) calendar agenda in the right-1/3 artifact panel (full-screen),
     *  titled by [member] when filtered. Sidebar mode uses the centered popup instead. */
    fun showCalendarAgenda(events: List<VoiceOverlayBridge.CalendarEvent>, member: String?) {
        if (events.isEmpty()) return
        if (!isVisible) show(fullscreen)
        val ctx = views.rootView.context
        val block = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // Self-labeling title (the fixed header is just the ✕ now, so accumulated
        // artifacts each carry their own label).
        block.addView(TextView(ctx).apply {
            text = if (!member.isNullOrEmpty()) "$member's agenda" else "Agenda"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * mult)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        VoiceAgendaRenderer.render(ctx, block, events, mult)
        addArtifact(block)
    }

    /** Inline a card view in the current turn's card slot (sidebar conversation mode). */
    private fun inlineCard(view: View) {
        val t = current ?: startTurn()
        t.card.removeAllViews()
        t.card.addView(view)
        t.card.visibility = View.VISIBLE
        scrollToLatest()
    }

    /** Stack an artifact in the right panel (full-screen): activate two-column and add it
     *  newest-on-top. The body is vertically centered when short, scrollable when several
     *  accumulate (e.g. multiple images across turns). */
    private fun addArtifact(view: View) {
        views.setArtifactTwoColumn(true)
        view.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12f); bottomMargin = dp(12f) }
        if (promoMode) views.voiceArtifactBody.addView(view)   // chat order: newest at bottom
        else views.voiceArtifactBody.addView(view, 0)          // newest on top
        scrollArtifactToLatest()
    }

    private fun scrollArtifactToLatest() {
        (views.voiceArtifactBody.parent as? ScrollView)?.let { sv ->
            sv.post { sv.fullScroll(if (promoMode) ScrollView.FOCUS_DOWN else ScrollView.FOCUS_UP) }
        }
    }

    /** Demote a completed turn: dim it, add a gap above so it reads as history,
     *  and shrink the text now that it's no longer the active turn. */
    private fun demote(t: Turn) {
        t.container.alpha = 0.5f
        // Bigger gap so the newest turn sits clearly apart from history (which scrolls
        // down into the lower half of the panel).
        (t.container.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.topMargin = dp(40f)
            t.container.layoutParams = it
        }
        t.bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * mult)
        t.response.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * mult)
        t.response.setTextColor(0xFFB0B0B0.toInt())
        t.additional.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * mult)
        t.additional.setTextColor(0xFF9A9A9A.toInt())
    }

    private fun scrollToLatest() {
        views.sidebarConversationScroll.post {
            views.sidebarConversationScroll.fullScroll(
                if (promoMode) ScrollView.FOCUS_DOWN else ScrollView.FOCUS_UP
            )
        }
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, views.rootView.resources.displayMetrics
    ).toInt()
}
