package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.SportsSlateEntry
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a compact, day-grouped slate of games (rows of `matchup … detail`) into a
 * caller-supplied [LinearLayout] — the sports analog of [VoiceAgendaRenderer]. Used for the
 * >3-game tier (the central agenda popup in sidebar mode, the right-1/3 artifact in
 * full-screen). 1–3 games render as full cards via [VoiceSportsCardRenderer] instead.
 *
 * Each row: the matchup on the left (scores inline + winner bold for live/final games,
 * "Away vs Home" for upcoming) and the game's [detail] (kickoff time / live clock / "Final")
 * on the right. Day headers appear when the slate spans multiple days (a team schedule);
 * a single-day slate (today's league games) shows no header.
 *
 * [scale] multiplies text up for full-screen; pass 1f for the sidebar popup.
 */
object VoiceSportsAgendaRenderer {

    /** Display form of a slate's league key: a bare lowercase code ("mlb", "nfl",
     *  "fifa.world") reads as an acronym → uppercase; worded labels ("World Cup")
     *  pass through untouched. */
    fun leagueLabel(raw: String?): String? =
        raw?.let { if (it.matches(Regex("[a-z0-9.\\-]{2,12}"))) it.uppercase(Locale.US) else it }

    fun render(context: Context, body: LinearLayout, games: List<SportsSlateEntry>, scale: Float = 1f) {
        // Group by day; days chronological. A slate that's all one day (or has no parseable
        // times) shows no headers — just the rows in the order given.
        val byDay = games.groupBy { dayOf(it) ?: LocalDate.MAX }
        val multiDay = byDay.keys.count { it != LocalDate.MAX } > 1
        var first = true
        byDay.keys.sorted().forEach { day ->
            if (multiDay && day != LocalDate.MAX) body.addView(dayHeader(context, day, scale))
            byDay.getValue(day).forEach {
                if (!first) body.addView(divider(context))
                first = false
                body.addView(gameRow(context, it, scale))
            }
        }
    }

    private fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(Color.parseColor("#26FFFFFF"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1f))
    }

    private fun dayHeader(context: Context, day: LocalDate, scale: Float): TextView = TextView(context).apply {
        text = day.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()))
        setTextColor(Color.parseColor("#E8E8E8"))
        textSize = 17f * scale
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 16f), 0, dp(context, 6f))
    }

    /**
     * One game = a STACKED PAIR (redesign 2026-07-12 — the inline matchup wrapped and
     * hid the score column): away line over home line, each `logo · short name · score`,
     * winner bold, scores right-aligned into a shared column; the game's detail
     * ("Final · Today" / kickoff / live clock) sits in its own right column, centered
     * across the pair.
     */
    private fun gameRow(context: Context, g: SportsSlateEntry, scale: Float): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 8f), 0, dp(context, 8f))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(teamLine(context, g.away, g.winner == "away", scale))
            addView(teamLine(context, g.home, g.winner == "home", scale))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        g.detail?.takeIf { it.isNotEmpty() }?.let { d ->
            addView(TextView(context).apply {
                text = d
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 11.5f * scale
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(context, 10f) })
        }
    }

    /** `logo · name · score` — the short display name ("Diamondbacks") when the server
     *  sent one, else the full name (old cached cards). Winner renders bold. */
    private fun teamLine(context: Context, team: VoiceOverlayBridge.SportsTeam, won: Boolean, scale: Float): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 2f), 0, dp(context, 2f))

            val logoPx = dp(context, 18f * scale)
            addView(ImageView(context).apply {
                team.logo?.let { load(it) }
            }, LinearLayout.LayoutParams(logoPx, logoPx).apply { marginEnd = dp(context, 7f) })

            addView(TextView(context).apply {
                text = (team.short ?: team.name).ifEmpty { "TBD" }
                setTextColor(Color.WHITE)
                textSize = 13f * scale
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (won) setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))

            team.score?.takeIf { it.isNotEmpty() }?.let { s ->
                addView(TextView(context).apply {
                    text = s
                    setTextColor(Color.WHITE)
                    textSize = 13f * scale
                    gravity = Gravity.END
                    minWidth = dp(context, 22f)
                    if (won) setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(context, 8f) })
            }
        }

    // ── date helpers (tolerant of missing offsets, mirrors VoiceAgendaRenderer.zoned) ──

    private fun dayOf(g: SportsSlateEntry): LocalDate? = try {
        g.startTime?.let { zoned(it).toLocalDate() }
    } catch (e: Exception) { null }

    private fun zoned(iso: String): ZonedDateTime = try {
        ZonedDateTime.parse(iso)
    } catch (e: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toZonedDateTime()
        } catch (e2: Exception) {
            LocalDateTime.parse(iso).atZone(ZoneId.systemDefault())
        }
    }

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
