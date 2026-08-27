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
import com.dashieapp.Dashie.halite.voice.VoiceOverlayBridge.SportsSlateEntry

/**
 * Central agenda popup for a multi-game ( >3 ) voice sports answer — the sports twin of
 * [VoiceCalendarAgendaPopup], same modal idiom (decor-view overlay, tap-outside-to-dismiss,
 * centered in the space LEFT of the voice sidebar). Body is the compact slate built by
 * [VoiceSportsAgendaRenderer]. 1–3 games render as full cards inline instead.
 *
 * This is the SIDEBAR-mode treatment; full-screen renders the same body as a right-1/3
 * artifact (see [VoiceConversationView.showSportsAgenda]).
 */
object VoiceSportsAgendaPopup {

    private var overlay: View? = null

    /** Show the slate [games], titled by [league] when known. [rightInset] is the voice
     *  sidebar width (px) so the popup centers in the space to its left. Replaces any open
     *  popup. */
    fun show(context: Context, games: List<SportsSlateEntry>, league: String?, rightInset: Int = 0) {
        val activity = context as? Activity ?: return
        val decor = activity.window?.decorView as? ViewGroup ?: return
        dismiss()
        if (games.isEmpty()) return

        val container = FrameLayout(context)

        // Invisible touch-catcher (the voice overlay supplies its own scrim).
        container.addView(View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(MATCH, MATCH))

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 18f).toFloat()
                setColor(Color.parseColor("#1E1E1E"))
            }
            val pad = dp(context, 20f)
            setPadding(pad, dp(context, 16f), pad, pad)
        }
        // Header: title (league, e.g. "World Cup"; "mlb" → "MLB") + ✕.
        val title = VoiceSportsAgendaRenderer.leagueLabel(league?.takeIf { it.isNotEmpty() && it != "null" })
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title ?: "Games"
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
        VoiceSportsAgendaRenderer.render(context, body, games)
        card.addView(ScrollView(context).apply { addView(body); isFillViewport = false },
            LinearLayout.LayoutParams(MATCH, WRAP))

        val screenW = context.resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.55f).toInt().coerceAtMost(dp(context, 560f))
        val avail = (screenW - rightInset).coerceAtLeast(cardW)
        container.addView(card, FrameLayout.LayoutParams(cardW, WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply {
            leftMargin = ((avail - cardW) / 2).coerceAtLeast(dp(context, 16f))
        })

        decor.addView(container, FrameLayout.LayoutParams(MATCH, MATCH))
        overlay = container
    }

    /** Remove the popup if shown. Safe when nothing is open. */
    fun dismiss() {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
    }

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
