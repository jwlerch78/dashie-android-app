package com.dashieapp.Dashie.halite.schedule

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dashieapp.Dashie.R

/**
 * Full-screen, center-stage alert shown when a scheduled action fires. Stays up until the
 * user taps Dismiss. Added to the activity overlay container (no SYSTEM_ALERT_WINDOW),
 * mirroring TimerOverlayManager. High-contrast (dim scrim + dark card) so it reads on any
 * dashboard theme.
 *
 * Two modes:
 *  - Reminder/alarm (default): "REMINDER"/"ALARM" header, short text ALL-CAPS.
 *  - Announcement ([announcement] = true): a fired AI-turn's answer — "Dashie" header,
 *    natural-case body at a readable size, wider card, and scrollable so a longer answer
 *    (weather, a list) isn't clipped.
 */
class ReminderAlertView(
    context: Context,
    action: ScheduledAction,
    private val announcement: Boolean = false,
    private val haCommand: Boolean = false,
    // LLM-set label of the ORIGINAL request ("Turn off the string lights") — shown as the card
    // title above the icon so the outcome ("Turned off the switch") reads with its context.
    private val title: String? = null,
    // When the action FIRED — cards persist until dismissed, so "when did this happen" matters.
    private val firedAtMs: Long = System.currentTimeMillis(),
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    // Declared BEFORE init so the init block can add it to the card (Kotlin initializes
    // properties and init blocks in declaration order).
    private val queueBadge = TextView(context).apply {
        setTextColor(Color.parseColor("#9E9E9E"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        visibility = GONE
    }

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(Color.parseColor("#B3000000")) // 70% black scrim
        // Draw above the screensaver/sleep layer (screenDimOverlay = 200dp elevation).
        elevation = dp(300).toFloat()
        isClickable = true // swallow taps so they don't fall through to the dashboard

        val orange = ContextCompat.getColor(context, R.color.dashie_orange)
        val isAlarm = action.vernacular == ScheduledAction.VERNACULAR_ALARM

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(32), dp(36), dp(32))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#1E1E1E"))
            }
        }

        // LLM-set request title, above the icon — QUOTED, so it reads as what was asked
        // ("Turn off the dining room light") next to the outcome body ("Turned off the light").
        title?.takeIf { it.isNotBlank() }?.let { t ->
            card.addView(TextView(context).apply {
                text = "“$t”"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }

        card.addView(ImageView(context).apply {
            if (haCommand) {
                setImageResource(R.drawable.icon_homeassistant_blue) // brand logo, keep its own color
            } else {
                setImageResource(R.drawable.ic_alert_bell)
                setColorFilter(orange)
            }
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { bottomMargin = dp(8) })

        // No "Home Assistant" text under the HA logo — the logo alone says it (2026-07-18).
        if (!haCommand) {
            card.addView(TextView(context).apply {
                text = when {
                    announcement -> "Dashie"
                    isAlarm -> "ALARM"
                    else -> "REMINDER"
                }
                setTextColor(orange)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                if (!announcement) letterSpacing = 0.08f
            })
        }

        val body = TextView(context).apply {
            text = action.notifyText.ifBlank {
                when {
                    announcement -> "…"
                    isAlarm -> "Alarm"
                    else -> "Reminder"
                }
            }
            isAllCaps = !announcement
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (announcement) 24f else 30f)
            typeface = if (announcement) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            // An HA ack is always ONE short line ("Turned on String Lights") — left-aligning it in
            // a 600dp card just looked off-centre. A Dashie announcement can be a weather paragraph
            // or a list (it's scrollable for that reason), and centring body copy reads badly, so
            // long-form stays left.
            gravity = if (announcement && !haCommand) Gravity.START else Gravity.CENTER
            setLineSpacing(0f, 1.18f)
            setPadding(0, dp(14), 0, dp(28))
        }

        if (announcement) {
            // Cap height so a long answer scrolls instead of overflowing the screen.
            val scroll = object : ScrollView(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    super.onMeasure(
                        widthSpec,
                        MeasureSpec.makeMeasureSpec(dp(480), MeasureSpec.AT_MOST)
                    )
                }
            }.apply { isVerticalScrollBarEnabled = true }
            scroll.addView(body)
            card.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        } else {
            card.addView(body)
        }

        // Fired-at timestamp — the card stays up until dismissed, so say WHEN it happened.
        card.addView(TextView(context).apply {
            val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
                .format(java.util.Date(firedAtMs))
            text = time
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })

        card.addView(Button(context).apply {
            text = "Dismiss"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(dp(56), dp(18), dp(56), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(orange)
            }
            setOnClickListener { onDismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Fired-card queue counter (item 13, John 2026-07-18): when more fires are waiting
        // behind this card, say so — dismiss/timeout reveals the next one.
        card.addView(queueBadge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        addView(card, LayoutParams(
            dp(if (announcement) 600 else 440), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Show/refresh the "+N more" counter for fires waiting in the queue behind this card. */
    fun updateQueueBadge(waiting: Int) {
        queueBadge.visibility = if (waiting > 0) VISIBLE else GONE
        if (waiting > 0) queueBadge.text = "+$waiting more"
    }
}
