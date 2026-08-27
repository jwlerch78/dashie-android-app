package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.CalendarEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds a day-grouped agenda body (rows of `time · [calendar color dot] · title`) into
 * a caller-supplied [LinearLayout]. Day headers are chronological; within a day, all-day
 * events sort first then by start time. Honors the user's 24-hour-clock + date-format
 * display settings ([HalitePreferences].display). URL-ish locations (Zoom links etc.) are
 * dropped so only a human place shows.
 *
 * Shared by two surfaces so they render identically:
 * - [VoiceCalendarAgendaPopup] — the centered modal for the >3 case in SIDEBAR mode.
 * - [VoiceConversationView] — the right-1/3 artifact panel for the >3 case in FULL-SCREEN
 *   mode (two-column layout).
 *
 * [scale] multiplies the text sizes up for full-screen (read at a distance); pass 1f for
 * the sidebar popup.
 */
object VoiceAgendaRenderer {

    private const val TAG = "VoiceAgendaRenderer"
    private const val DEFAULT_DOT = "#1976D2"

    /** Append the day-grouped agenda for [events] into [body]. Reads display settings
     *  (24hr clock, m/d/y vs d/m/y) from [HalitePreferences]. */
    fun render(context: Context, body: LinearLayout, events: List<CalendarEvent>, scale: Float = 1f) {
        val display = try { HalitePreferences(context).display } catch (e: Exception) { null }
        val use24 = display?.use24HourClock ?: false
        val mdy = (display?.dateFormat ?: "mdy") != "dmy"

        // Group by day; within a day, all-day first then by start time; days chronological.
        val byDay = events.groupBy { dayOf(it) ?: LocalDate.MAX }
        byDay.keys.sorted().forEach { day ->
            val dayEvents = byDay.getValue(day).sortedWith(
                compareByDescending<CalendarEvent> { it.isAllDay }.thenBy { startMillis(it) }
            )
            if (day != LocalDate.MAX) body.addView(dayHeader(context, day, mdy, scale))
            dayEvents.forEach { body.addView(eventRow(context, it, use24, scale)) }
        }
    }

    private fun dayHeader(context: Context, day: LocalDate, mdy: Boolean, scale: Float): TextView = TextView(context).apply {
        val pattern = if (mdy) "EEEE, MMM d" else "EEEE, d MMM"
        text = day.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        setTextColor(Color.parseColor("#E8E8E8"))
        textSize = 17f * scale
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 16f), 0, dp(context, 6f))
    }

    /** One row: time (left, fixed) · color dot · title (location). */
    private fun eventRow(context: Context, ev: CalendarEvent, use24: Boolean, scale: Float): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 7f), 0, dp(context, 7f))

        addView(TextView(context).apply {
            text = timeLabel(ev, use24)
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f * scale
        }, LinearLayout.LayoutParams(dp(context, (if (use24) 60f else 78f) * scale), WRAP))

        addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(parseColorOr(ev.color, DEFAULT_DOT))
            }
        }, LinearLayout.LayoutParams(dp(context, 9f * scale), dp(context, 9f * scale)).apply {
            marginEnd = dp(context, 10f)
        })

        addView(TextView(context).apply {
            // Drop URL-ish locations (e.g. Zoom links) — show only a human place.
            val loc = ev.location?.substringBefore(",")?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) }
            text = if (loc != null) "${ev.title}  ($loc)" else ev.title
            setTextColor(Color.WHITE)
            textSize = 15f * scale
            maxLines = 2
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    // ── date/time helpers ────────────────────────────────────────────────────

    private fun dayOf(ev: CalendarEvent): LocalDate? = try {
        ev.startDateTime?.let { zoned(it).toLocalDate() }
            ?: ev.startDate?.let { LocalDate.parse(it) }
    } catch (e: Exception) { null }

    private fun startMillis(ev: CalendarEvent): Long = try {
        ev.startDateTime?.let { zoned(it).toInstant().toEpochMilli() } ?: 0L
    } catch (e: Exception) { 0L }

    private fun timeLabel(ev: CalendarEvent, use24: Boolean): String {
        if (ev.isAllDay) return "All day"
        return try {
            val pattern = if (use24) "H:mm" else "h:mm a"
            zoned(ev.startDateTime ?: return "").format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        } catch (e: Exception) { "" }
    }

    /** Parse an ISO timestamp and CONVERT it to the device zone, tolerant of missing
     *  offsets. A 'Z'/UTC (or any-offset) time is shown in local time — without the
     *  conversion a 'Z' timestamp formatted in its own (UTC) zone, e.g. 06:00Z printed
     *  as "6:00 AM" instead of "2:00 AM" ET (mismatch vs the dashboard agenda). */
    private fun zoned(iso: String): ZonedDateTime = try {
        ZonedDateTime.parse(iso).withZoneSameInstant(ZoneId.systemDefault())            // has offset/zone → device zone
    } catch (e: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()) // offset, no zone id → device zone
        } catch (e2: Exception) {
            LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()) // no offset (ISO local) — already device zone
        }
    }

    private fun parseColorOr(hex: String?, fallback: String): Int = try {
        Color.parseColor(hex ?: fallback)
    } catch (e: Exception) {
        Log.w(TAG, "bad color '$hex'"); Color.parseColor(fallback)
    }

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
