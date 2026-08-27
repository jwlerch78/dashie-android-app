package com.dashieapp.Dashie.halite.voice.speakerid.enrollment

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.edition.brandName

/**
 * Full-screen overlay view for the guided voice-enrollment wizard.
 * Pure presentation — VoiceEnrollmentController owns all state and drives
 * this via the render methods. Hosted in the activity overlay container
 * (ReminderAlertView pattern: scrim FrameLayout, high elevation, swallows
 * touches, Cancel always available).
 */
class VoiceEnrollmentView(context: Context) : FrameLayout(context) {

    var onCancel: (() -> Unit)? = null
    var onMemberSelected: ((id: String, name: String) -> Unit)? = null
    var onPrimaryAction: (() -> Unit)? = null

    private val titleText = TextView(context).apply {
        textSize = 26f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val bodyText = TextView(context).apply {
        textSize = 30f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.2f)
    }
    private val statusText = TextView(context).apply {
        textSize = 20f
        setTextColor(Color.parseColor("#BBBBBB"))
        gravity = Gravity.CENTER
    }
    private val progressText = TextView(context).apply {
        textSize = 22f
        setTextColor(Color.parseColor("#77CCFF"))
        gravity = Gravity.CENTER
        letterSpacing = 0.3f
    }
    private val memberList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }
    private val primaryButton = wizardButton("Start").apply {
        setOnClickListener { onPrimaryAction?.invoke() }
    }
    private val cancelButton = wizardButton("Cancel", subtle = true).apply {
        setOnClickListener { onCancel?.invoke() }
    }

    init {
        setBackgroundColor(Color.parseColor("#E6101418"))
        isClickable = true
        isFocusable = true
        elevation = 300f * resources.displayMetrics.density

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(28)
            setPadding(pad, pad, pad, pad)
        }
        card.addView(titleText, lp(bottomMargin = dp(18)))
        card.addView(bodyText, lp(bottomMargin = dp(18)))
        card.addView(memberList, lp(bottomMargin = dp(10)))
        card.addView(statusText, lp(bottomMargin = dp(14)))
        card.addView(progressText, lp(bottomMargin = dp(22)))

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(cancelButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(16) })
        buttons.addView(primaryButton)
        card.addView(buttons)

        addView(card, LayoutParams(dp(560), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    // ── render states (controller-driven) ───────────────────────────────────

    fun renderMemberSelect(members: List<Pair<String, String>>) {
        titleText.text = "Whose voice am I learning?"
        bodyText.text = ""
        bodyText.visibility = GONE
        statusText.text = if (members.isEmpty())
            "No family members found — set up your family in Settings first." else ""
        progressText.text = ""
        primaryButton.visibility = GONE
        memberList.visibility = VISIBLE
        memberList.removeAllViews()
        for ((id, name) in members) {
            memberList.addView(wizardButton(name).apply {
                setOnClickListener { onMemberSelected?.invoke(id, name) }
            }, lp(bottomMargin = dp(10)))
        }
        memberList.getChildAt(0)?.requestFocus()
    }

    fun renderIntro(memberName: String, lineCount: Int) {
        memberList.visibility = GONE
        bodyText.visibility = VISIBLE
        titleText.text = "Okay $memberName!"
        bodyText.text = "I'll show you $lineCount short lines.\n" +
            "Read each one naturally from where you usually talk to ${context.brandName()}."
        statusText.text = ""
        progressText.text = ""
        primaryButton.text = "Start"
        primaryButton.visibility = VISIBLE
        primaryButton.requestFocus()
    }

    fun renderLine(line: String, index: Int, total: Int, status: LineStatus) {
        memberList.visibility = GONE
        bodyText.visibility = VISIBLE
        titleText.text = "Line ${index + 1} of $total"
        bodyText.text = "“$line”"
        statusText.text = when (status) {
            LineStatus.GET_READY -> "Get ready…"
            LineStatus.RECORDING -> "🔴 Listening — read the line now"
            LineStatus.PROCESSING -> "Processing…"
            LineStatus.GOOD_TAKE -> "✓ Got it!"
            LineStatus.RETRY -> "I didn't catch that — let's try the same line again."
        }
        progressText.text = buildString {
            for (i in 0 until total) append(if (i < index) "● " else "○ ")
        }
        primaryButton.visibility = GONE
    }

    fun renderVerify() {
        titleText.text = "Last step!"
        bodyText.text = "Say anything you like,\nand I'll try to guess who you are."
        statusText.text = "🔴 Listening…"
        progressText.text = ""
        primaryButton.visibility = GONE
    }

    fun renderDone(message: String) {
        titleText.text = "All done! 🎉"
        bodyText.text = message
        statusText.text = ""
        progressText.text = ""
        primaryButton.text = "Finish"
        primaryButton.visibility = VISIBLE
        primaryButton.requestFocus()
    }

    fun renderError(message: String) {
        titleText.text = "Hmm, that didn't work"
        bodyText.text = message
        statusText.text = ""
        progressText.text = ""
        primaryButton.text = "Close"
        primaryButton.visibility = VISIBLE
        primaryButton.requestFocus()
    }

    enum class LineStatus { GET_READY, RECORDING, PROCESSING, GOOD_TAKE, RETRY }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun wizardButton(label: String, subtle: Boolean = false): Button =
        Button(context).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor(if (subtle) "#33445566" else "#2266AA"))
            }
            setPadding(dp(28), dp(12), dp(28), dp(12))
            isFocusable = true
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun lp(bottomMargin: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        this.bottomMargin = bottomMargin
    }
}
