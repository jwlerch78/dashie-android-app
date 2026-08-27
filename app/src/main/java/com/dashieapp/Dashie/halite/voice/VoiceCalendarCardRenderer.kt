package com.dashieapp.Dashie.halite.voice

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders calendar event cards for the voice sidebar.
 * Inflates voice_calendar_event_card.xml and populates with event data.
 */
object VoiceCalendarCardRenderer {

    /**
     * Create a card view for a single calendar event. [scale] multiplies every text
     * size (the conversation panel passes 1.25 — John 2026-07-13: cards read small).
     */
    fun createCard(context: Context, event: VoiceOverlayBridge.CalendarEvent, scale: Float = 1f): View {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.voice_calendar_event_card, null)
        if (scale != 1f) {
            for (id in intArrayOf(R.id.calendarMonth, R.id.calendarDay, R.id.calendarDayName,
                    R.id.calendarTitle, R.id.calendarTime, R.id.calendarLocation)) {
                val tv: TextView = view.findViewById(id)
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tv.textSize * scale)
            }
        }

        val monthText: TextView = view.findViewById(R.id.calendarMonth)
        val dayText: TextView = view.findViewById(R.id.calendarDay)
        val dayNameText: TextView = view.findViewById(R.id.calendarDayName)
        val titleText: TextView = view.findViewById(R.id.calendarTitle)
        val timeText: TextView = view.findViewById(R.id.calendarTime)
        val locationText: TextView = view.findViewById(R.id.calendarLocation)

        // Parse date from event
        val (month, day, dayName) = parseDateParts(event)
        monthText.text = month
        dayText.text = day
        dayNameText.text = dayName

        // Title
        titleText.text = event.title

        // Time
        if (event.isAllDay) {
            timeText.text = "All Day"
        } else {
            timeText.text = formatTimeRange(event)
        }

        // Location
        if (!event.location.isNullOrEmpty()) {
            locationText.text = cleanLocation(event.location)
            locationText.visibility = View.VISIBLE
        }

        return view
    }

    /**
     * Add multiple calendar event cards to a container.
     */
    fun addCardsToContainer(
        context: Context,
        container: LinearLayout,
        events: List<VoiceOverlayBridge.CalendarEvent>,
        scale: Float = 1f
    ) {
        // Limit to 3 events to avoid overcrowding the sidebar
        events.take(3).forEach { event ->
            val card = createCard(context, event, scale)
            container.addView(card)
        }
    }

    private data class DateParts(val month: String, val day: String, val dayName: String)

    private fun parseDateParts(event: VoiceOverlayBridge.CalendarEvent): DateParts {
        try {
            val dateTime = event.startDateTime
            val date = event.startDate

            if (!dateTime.isNullOrEmpty()) {
                val zdt = try {
                    ZonedDateTime.parse(dateTime).withZoneSameInstant(java.time.ZoneId.systemDefault())
                } catch (e: Exception) {
                    LocalDateTime.parse(dateTime.substringBefore("Z").substringBefore("+").substringBefore("-"))
                        .atZone(java.time.ZoneId.systemDefault())
                }
                return DateParts(
                    month = zdt.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                    day = zdt.dayOfMonth.toString(),
                    dayName = zdt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
                )
            } else if (!date.isNullOrEmpty()) {
                val ld = LocalDate.parse(date)
                return DateParts(
                    month = ld.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(),
                    day = ld.dayOfMonth.toString(),
                    dayName = ld.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
                )
            }
        } catch (e: Exception) {
            // Fall through to default
        }
        return DateParts("", "?", "")
    }

    private fun formatTimeRange(event: VoiceOverlayBridge.CalendarEvent): String {
        try {
            val fmt = DateTimeFormatter.ofPattern("h:mm a")
            val startStr = event.startDateTime ?: return ""
            val start = try {
                ZonedDateTime.parse(startStr).withZoneSameInstant(java.time.ZoneId.systemDefault())
            } catch (e: Exception) {
                LocalDateTime.parse(startStr.substringBefore("Z").substringBefore("+").substringBefore("-"))
                    .atZone(java.time.ZoneId.systemDefault())
            }

            val endStr = event.endDateTime
            if (!endStr.isNullOrEmpty()) {
                val end = try {
                    ZonedDateTime.parse(endStr).withZoneSameInstant(java.time.ZoneId.systemDefault())
                } catch (e: Exception) {
                    LocalDateTime.parse(endStr.substringBefore("Z").substringBefore("+").substringBefore("-"))
                        .atZone(java.time.ZoneId.systemDefault())
                }
                return "${start.format(fmt)} - ${end.format(fmt)}"
            }
            return start.format(fmt)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * Clean up location string (remove URL-like suffixes, etc.)
     */
    private fun cleanLocation(location: String): String {
        // Remove Google Maps URLs that some calendar events include
        return location
            .replace(Regex("https?://\\S+"), "")
            .trim()
            .trimEnd(',')
    }
}
