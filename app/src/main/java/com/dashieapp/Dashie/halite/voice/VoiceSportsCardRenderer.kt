package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.dashieapp.Dashie.R

/**
 * Renders a sports score card (VoiceOverlayBridge.SportsCard) for the voice sidebar.
 * Inflates view_voice_sports_card.xml and loads team logos/flags via Coil from the
 * URLs the brain's structured_data already carries.
 */
object VoiceSportsCardRenderer {

    /**
     * @param scale          content multiplier (1.0 = sidebar; ~1.3 in full-screen so
     *                       logos/text read at a distance).
     * @param widthFraction  card width as a fraction of the screen, or 1f to fill the
     *                       container. In full-screen the container is the whole screen,
     *                       so <1 keeps the card from stretching edge-to-edge.
     */
    fun addCardToContainer(
        context: Context,
        container: LinearLayout,
        card: VoiceOverlayBridge.SportsCard,
        scale: Float = 1f,
        widthFraction: Float = 1f,
    ) {
        container.removeAllViews()
        val view = createCard(context, card, scale)
        val widthPx = if (widthFraction < 1f) {
            (context.resources.displayMetrics.widthPixels * widthFraction).toInt()
        } else {
            LinearLayout.LayoutParams.MATCH_PARENT
        }
        container.addView(view, LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })
    }

    /** Stack 1–3 slate games as full cards in [container] (the inline tier). Each entry
     *  becomes a single-game card (no records/stats — a slate row carries only the matchup,
     *  score-or-time, and state). >3 games use the agenda popup instead. */
    fun addSlateCardsToContainer(
        context: Context,
        container: LinearLayout,
        games: List<VoiceOverlayBridge.SportsSlateEntry>,
        scale: Float = 1f,
    ) {
        container.removeAllViews()
        games.forEachIndexed { i, g ->
            val view = createCard(context, slateToCard(g), scale)
            container.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) topMargin = (12 * context.resources.displayMetrics.density).toInt() })
        }
    }

    /** Project a compact slate entry onto the single-card model the renderer draws. */
    private fun slateToCard(g: VoiceOverlayBridge.SportsSlateEntry) = VoiceOverlayBridge.SportsCard(
        state = g.state,
        statusLine = g.detail,
        venue = null,
        home = g.home,
        away = g.away,
        lines = null,
        highlights = null,
        scorers = null,
    )

    fun createCard(context: Context, card: VoiceOverlayBridge.SportsCard, scale: Float = 1f): View {
        val view = LayoutInflater.from(context).inflate(R.layout.view_voice_sports_card, null)

        view.findViewById<TextView>(R.id.sportsVenue).apply {
            if (!card.venue.isNullOrEmpty()) { text = card.venue; visibility = View.VISIBLE }
            else visibility = View.GONE
        }

        view.findViewById<TextView>(R.id.sportsStatus).text = card.statusLine ?: when (card.state) {
            "post" -> "Final"
            "in" -> "Live"
            "pre" -> "Scheduled"
            else -> ""
        }

        bindTeam(view, R.id.sportsHomeLogo, R.id.sportsHomeName, R.id.sportsHomeScore, card.home)
        bindTeam(view, R.id.sportsAwayLogo, R.id.sportsAwayName, R.id.sportsAwayScore, card.away)

        // Generic stats subtext: paired `lines` (R/H/E…) then labeled `highlights`
        // (soccer goals by team, baseball HR/W/L/S…). Falls back to legacy `scorers`.
        val statsView = view.findViewById<TextView>(R.id.sportsScorers)
        val sb = SpannableStringBuilder()
        fun appendLabeled(label: String, detail: String) {
            if (detail.isEmpty()) return
            if (sb.isNotEmpty()) sb.append("\n\n")
            if (label.isNotEmpty()) {
                val start = sb.length
                sb.append("$label: ")
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append(detail)
        }

        // Paired team numbers on one row, e.g. "R 4–1 · H 9–5 · E 0–1".
        card.lines?.takeIf { it.isNotEmpty() }?.let { lines ->
            val row = lines.joinToString("   ·   ") { "${it.label} ${it.home}–${it.away}" }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(row)
        }

        val highlights = card.highlights
        if (!highlights.isNullOrEmpty()) {
            highlights.forEach { appendLabeled(it.label, it.detail) }
        } else {
            // Legacy fallback: older server payloads only carry `scorers`.
            val scorers = card.scorers
            if (!scorers.isNullOrEmpty()) {
                fun fmt(list: List<VoiceOverlayBridge.SportsScorer>) =
                    list.joinToString(", ") { it.player + if (it.clocks.isNotEmpty()) " (${it.clocks})" else "" }
                scorers.filter { it.side == "home" }.takeIf { it.isNotEmpty() }?.let { appendLabeled(card.home.name, fmt(it)) }
                scorers.filter { it.side == "away" }.takeIf { it.isNotEmpty() }?.let { appendLabeled(card.away.name, fmt(it)) }
                scorers.filter { it.side == null }.takeIf { it.isNotEmpty() }?.let { appendLabeled("", fmt(it)) }
            }
        }

        if (sb.isNotEmpty()) { statsView.text = sb; statsView.visibility = View.VISIBLE }
        else statsView.visibility = View.GONE

        if (scale != 1f) scaleCard(view, scale)
        return view
    }

    /**
     * Scale the card for full-screen. The matchup is the focal point, so logos /
     * country names / scores get the full [scale]; the venue, status and scorers
     * subtext get a smaller bump so the matchup dominates.
     */
    private fun scaleCard(view: View, scale: Float) {
        val secondary = scale * 0.75f
        mapOf(
            R.id.sportsHomeName to scale, R.id.sportsAwayName to scale,
            R.id.sportsHomeScore to scale, R.id.sportsAwayScore to scale,
            R.id.sportsVenue to secondary, R.id.sportsStatus to secondary, R.id.sportsScorers to secondary,
        ).forEach { (id, s) ->
            view.findViewById<TextView>(id)?.let { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, it.textSize * s) }
        }
        // Logos (country flags) — scale fully so they read at a distance.
        listOf(R.id.sportsHomeLogo, R.id.sportsAwayLogo).forEach { id ->
            view.findViewById<ImageView>(id)?.let { iv ->
                iv.layoutParams = iv.layoutParams.apply {
                    width = (width * scale).toInt()
                    height = (height * scale).toInt()
                }
            }
        }
    }

    private fun bindTeam(view: View, logoId: Int, nameId: Int, scoreId: Int, team: VoiceOverlayBridge.SportsTeam) {
        view.findViewById<ImageView>(logoId).apply {
            if (!team.logo.isNullOrEmpty()) load(team.logo) else setImageDrawable(null)
        }
        // Record renders a step smaller + gray than the team name (same-size records
        // pushed long names into ellipsis — "Washington Nationals (48…").
        view.findViewById<TextView>(nameId).text = android.text.SpannableStringBuilder().apply {
            append(team.name)
            if (!team.record.isNullOrEmpty()) {
                val start = length
                append("  (${team.record})")
                setSpan(android.text.style.RelativeSizeSpan(0.72f), start, length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#AAAAAA")),
                    start, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        view.findViewById<TextView>(scoreId).text = team.score ?: ""
    }
}
