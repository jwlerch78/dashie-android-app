package com.dashieapp.Dashie.halite.voice

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.CalendarEvent

/**
 * Central agenda popup for a multi-event ( >3 ) voice calendar answer — too large for
 * the narrow voice sidebar. Replicates the dashboard day-modal style
 * (js/widgets/calendar/core/calendar-day-modal.js): a dim-backdrop modal added straight
 * to the Activity decor view (the SidebarPopoutManager pattern — owns the full-screen
 * touch region for tap-outside-to-dismiss), with day-grouped rows of
 * `time · [calendar color dot] · title` (built by [VoiceAgendaRenderer]). Centered in the
 * space LEFT of the voice sidebar so the two don't overlap.
 *
 * This is the SIDEBAR-mode treatment. Full-screen mode renders the same agenda body inline
 * as a right-1/3 artifact panel (see [VoiceConversationView.showCalendarAgenda]).
 *
 * Useful for HA users whose dashboards lack the full Dashie calendar.
 * Build plan: 20260623_VOICE_TIER1_STRUCTURED_TOOLS.md §3.
 */
object VoiceCalendarAgendaPopup {

    private var overlay: View? = null

    /**
     * Show the agenda for [events], titled by [member] when filtered. [rightInset] is the
     * width (px) of the voice sidebar on the right, so the popup centers in the space to
     * its left. Replaces any open popup.
     */
    fun show(context: Context, events: List<CalendarEvent>, member: String?, rightInset: Int = 0) {
        val activity = context as? Activity ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        dismiss()
        if (events.isEmpty()) return

        val container = FrameLayout(context)

        // Invisible touch-catcher — tap outside the card dismisses, but DON'T dim the
        // rest of the screen (the voice overlay already provides its own scrim).
        container.addView(View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        // Content card.
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true   // swallow taps so they don't reach the backdrop
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18f).toFloat()
                setColor(Color.parseColor("#1E1E1E"))
            }
            val pad = dp(context, 20f)
            setPadding(pad, dp(context, 16f), pad, pad)
        }
        // Header row: title + an "✕" close affordance.
        val who = member?.takeIf { it.isNotEmpty() && it != "null" }  // "null" guard (org.json)
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = if (who != null) "$who's agenda" else "Agenda"
                setTextColor(Color.WHITE)
                textSize = 20f
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(TextView(context).apply {
                text = "✕"
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 20f
                val p = dp(context, 6f); setPadding(p, p, p, p)
                isClickable = true
                setOnClickListener { dismiss() }
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(context, 8f) })

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        VoiceAgendaRenderer.render(context, body, events)
        card.addView(ScrollView(context).apply { addView(body); isFillViewport = false },
            LinearLayout.LayoutParams(MATCH, WRAP))

        // Center in the area LEFT of the voice sidebar (rightInset), not the whole screen.
        val screenW = context.resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.55f).toInt().coerceAtMost(dp(context, 560f))
        val avail = (screenW - rightInset).coerceAtLeast(cardW)
        container.addView(card, FrameLayout.LayoutParams(cardW, WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            leftMargin = ((avail - cardW) / 2).coerceAtLeast(dp(context, 16f))
        })

        decor.addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = container
    }

    /** Remove the popup if shown. Safe to call when nothing is open. */
    fun dismiss() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
