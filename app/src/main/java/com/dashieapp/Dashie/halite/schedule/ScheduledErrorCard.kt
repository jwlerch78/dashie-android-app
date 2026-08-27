package com.dashieapp.Dashie.halite.schedule

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R

/**
 * A small, dismissible card in the bottom-right corner. Two uses:
 *  - a scheduled action FAILED (its original job), and
 *  - the DEMOTED resting state of a fired success card — the center-stage modal steps down
 *    here after ScheduledActionExecutor.DEMOTE_TO_CORNER_MS, scrim gone, until ✕.
 *
 * Deliberately NOT the center-stage [ReminderAlertView]: a failure is a "you should know this
 * happened" notice, not a demand. Before this existed, a fired AI turn whose brain call errored
 * spoke "Sorry, I couldn't process that" and drew NOTHING (VoicePipelineCoordinator's !success
 * branch) — so if you were out of the room or muted, a scheduled check that failed left ZERO
 * trace. You'd never know it ran, let alone failed.
 *
 * Two things it must not do, both different from the modal:
 *  - No scrim, and the root is NOT clickable — a corner card must let taps fall through to the
 *    dashboard behind it. (The modal deliberately swallows them.)
 *  - It never blocks; it auto-dismisses (see ScheduledActionExecutor's dismiss windows) and the
 *    ✕ works at any time.
 *
 * Header matches the success cards so the branding stays coherent: "Home Assistant" for a failed
 * scheduled command, "Dashie" for a failed AI turn.
 */
/**
 * @param occurredAtMs when the action actually FAILED — not when this card is drawn. The two can
 *   differ by minutes: an HA error card is parked behind a live conversation (ConversationIdleGate),
 *   so stamping it at draw time would report when your chat ended, not when the action failed.
 */
class ScheduledErrorCard(
    context: Context,
    message: String,
    isHaCommand: Boolean = false,
    occurredAtMs: Long = System.currentTimeMillis(),
    onDismiss: () -> Unit
) : FrameLayout(context) {

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // No scrim + not clickable: touches pass through to the dashboard underneath. A
        // full-screen host is only used so the corner gravity works whatever the container is.
        isClickable = false
        // Above the screensaver/sleep layer (screenDimOverlay = 200dp), same as the modal.
        elevation = dp(300).toFloat()

        val orange = ContextCompat.getColor(context, R.color.dashie_orange)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#1E1E1E"))
                setStroke(dp(1), Color.parseColor("#3A3A3A"))
            }
            isClickable = true   // the CARD swallows its own taps; the transparent host doesn't
        }

        // Header row: icon + source + ✕
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(context).apply {
            if (isHaCommand) {
                setImageResource(R.drawable.icon_homeassistant_blue)
            } else {
                setImageResource(R.drawable.ic_alert_bell)
                setColorFilter(orange)
            }
        }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })

        header.addView(TextView(context).apply {
            text = if (isHaCommand) "Home Assistant" else "Dashie"
            // White for Home Assistant, orange for Dashie: orange is DASHIE's brand colour, and
            // sitting it next to HA's blue logo just read as wrong.
            setTextColor(if (isHaCommand) Color.WHITE else orange)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // When it failed — the card can be read minutes later (it lingers, and an HA error is
        // parked behind a live chat), so "it broke" without "when" is half a message.
        // DateFormat.getTimeFormat honors the device's 12/24-hour setting.
        header.addView(TextView(context).apply {
            text = android.text.format.DateFormat.getTimeFormat(context)
                .format(java.util.Date(occurredAtMs))
            setTextColor(Color.parseColor("#9A9A9A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        header.addView(TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#9A9A9A"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(10), dp(2), dp(2), dp(2))
            setOnClickListener { onDismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        card.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        card.addView(TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
            maxLines = 4
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        addView(card, LayoutParams(dp(340), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(20)
            bottomMargin = dp(20)
        })
    }
}
