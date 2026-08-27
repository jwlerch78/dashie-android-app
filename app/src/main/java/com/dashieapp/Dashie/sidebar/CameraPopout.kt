package com.dashieapp.Dashie.sidebar

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dashieapp.Dashie.R
import org.json.JSONArray

/**
 * Handles camera/video feed popout logic.
 *
 * Shows action buttons (layout toggle, feeds on/off, alerts on/off) with
 * icon + label pattern matching JS camera-popout, and a dynamic list of
 * configured camera streams with show/hide toggles.
 */
class CameraPopout(
    private val onCycleLayout: () -> Unit,
    private val onTogglePause: () -> Unit,
    private val onToggleMuteAlerts: () -> Unit,
    private val onShowFeed: (ruleId: String) -> Unit,
    private val onDismissFeed: (ruleId: String) -> Unit,
    private val onInteraction: () -> Unit
) {
    data class FeedInfo(val ruleId: String, val label: String, val isActive: Boolean, val isAvailable: Boolean = true)

    fun bind(
        contentView: View,
        context: Context,
        isPaused: Boolean,
        alertsMuted: Boolean,
        currentLayout: String,
        isDarkMode: Boolean,
        feeds: List<FeedInfo>
    ) {
        // Default text color based on theme
        val defaultTextColor = if (isDarkMode) 0xFFFFFFFF.toInt() else 0xFF212121.toInt()

        // ── Layout toggle button (Sidebar ↔ Float) ──
        val layoutIcon = contentView.findViewById<ImageView>(R.id.cameraIconLayout)
        val layoutLabel = contentView.findViewById<TextView>(R.id.cameraLabelLayout)
        var localLayout = currentLayout

        fun updateLayoutIcon() {
            if (localLayout == "sidebar") {
                layoutIcon.setImageResource(R.drawable.ic_sidebar_layout)
                layoutLabel.text = "Sidebar"
            } else {
                layoutIcon.setImageResource(R.drawable.ic_float_layout)
                layoutLabel.text = "Float"
            }
        }
        updateLayoutIcon()

        contentView.findViewById<View>(R.id.cameraBtnLayout).setOnClickListener {
            onInteraction()
            onCycleLayout()
            localLayout = if (localLayout == "sidebar") "float" else "sidebar"
            updateLayoutIcon()
        }

        // ── Feeds On/Off toggle ──
        val pauseIcon = contentView.findViewById<ImageView>(R.id.cameraIconPause)
        val pauseLabel = contentView.findViewById<TextView>(R.id.cameraLabelPause)
        val pauseStrike = contentView.findViewById<View>(R.id.cameraPauseStrike)
        var localPaused = isPaused

        fun updatePauseState() {
            pauseLabel.text = if (localPaused) "Feeds Off" else "Feeds On"
            pauseIcon.alpha = if (localPaused) 0.4f else 1.0f
            pauseStrike.visibility = if (localPaused) View.VISIBLE else View.GONE
        }
        updatePauseState()

        contentView.findViewById<View>(R.id.cameraBtnPause).setOnClickListener {
            onInteraction()
            onTogglePause()
            localPaused = !localPaused
            updatePauseState()
        }

        // ── Alerts On/Off toggle ──
        val alertsIcon = contentView.findViewById<ImageView>(R.id.cameraIconMuteAlerts)
        val alertsLabel = contentView.findViewById<TextView>(R.id.cameraLabelMuteAlerts)
        val alertsStrike = contentView.findViewById<View>(R.id.cameraAlertsStrike)
        var localAlertsMuted = alertsMuted

        fun updateAlertsState() {
            alertsLabel.text = if (localAlertsMuted) "Alerts Off" else "Alerts On"
            alertsIcon.alpha = if (localAlertsMuted) 0.4f else 1.0f
            alertsStrike.visibility = if (localAlertsMuted) View.VISIBLE else View.GONE
        }
        updateAlertsState()

        contentView.findViewById<View>(R.id.cameraBtnMuteAlerts).setOnClickListener {
            onInteraction()
            onToggleMuteAlerts()
            localAlertsMuted = !localAlertsMuted
            updateAlertsState()
        }

        // ── Stream list ──
        val streamList = contentView.findViewById<LinearLayout>(R.id.cameraStreamList)
        streamList.removeAllViews()

        // Constrain scroll container height so feeds don't overflow off-screen
        val scrollView = contentView.findViewById<android.widget.ScrollView>(R.id.cameraStreamListScroll)

        val density = context.resources.displayMetrics.density
        val orangeColor = 0xFFFF9500.toInt()
        val grayBgColor = 0x33000000
        val offlineTextColor = if (isDarkMode) 0xFF888888.toInt() else 0xFF999999.toInt()

        // Sort: online cameras first, offline at the bottom
        val sortedFeeds = feeds.sortedBy { if (it.isAvailable) 0 else 1 }

        for (feed in sortedFeeds) {
            // Row is the focusable/clickable unit (shows bg_popout_item focus highlight)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (8 * density).toInt(), (8 * density).toInt(),
                    (8 * density).toInt(), (8 * density).toInt()
                )
                setBackgroundResource(R.drawable.bg_popout_item)
                isClickable = true
                isFocusable = true
            }

            // Toggle icon on LEFT (circular: orange for -, gray for +)
            val btnSize = (36 * density).toInt()
            val toggleBtn = TextView(context).apply {
                tag = "feedToggle_${feed.ruleId}"
                text = if (feed.isActive) "\u2212" else "+"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                this.gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    marginEnd = (12 * density).toInt()
                }
                val bgDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (feed.isActive) orangeColor else grayBgColor)
                }
                background = bgDrawable
            }
            row.addView(toggleBtn)

            // Label (orange when active, grayed when offline, theme-aware default otherwise)
            val labelColor = when {
                feed.isActive -> orangeColor
                !feed.isAvailable -> offlineTextColor
                else -> defaultTextColor
            }
            val label = TextView(context).apply {
                tag = "feedLabel_${feed.ruleId}"
                text = feed.label
                setTextColor(labelColor)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(label)

            // Offline badge
            if (!feed.isAvailable) {
                row.addView(TextView(context).apply {
                    text = "offline"
                    textSize = 10f
                    setTextColor(offlineTextColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = (6 * density).toInt() }
                })
            }

            // Track local active state for immediate UI updates
            var localActive = feed.isActive

            row.setOnClickListener {
                onInteraction()
                if (localActive) onDismissFeed(feed.ruleId) else onShowFeed(feed.ruleId)
                localActive = !localActive

                // Immediately update button appearance
                toggleBtn.text = if (localActive) "\u2212" else "+"
                val newBg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (localActive) orangeColor else grayBgColor)
                }
                toggleBtn.background = newBg

                // Immediately update label color (offline stays grayed unless active)
                label.setTextColor(if (localActive) orangeColor else if (!feed.isAvailable) offlineTextColor else defaultTextColor)
            }

            streamList.addView(row)
        }

        // Cap scroll height to ~4 rows max so popout doesn't go off-screen
        val scrollDownIndicator = contentView.findViewById<ImageView>(R.id.cameraScrollIndicator)
        val scrollUpIndicator = contentView.findViewById<ImageView>(R.id.cameraScrollUpIndicator)
        if (feeds.size > 4) {
            val maxHeightPx = (4 * 52 * density).toInt()  // ~52dp per row
            scrollView?.layoutParams = scrollView?.layoutParams?.apply {
                height = maxHeightPx
            }

            fun updateScrollIndicators() {
                val sv = scrollView ?: return
                val child = sv.getChildAt(0) ?: return
                val threshold = (8 * density).toInt()
                scrollDownIndicator?.visibility = if (sv.scrollY + sv.height < child.height - threshold) View.VISIBLE else View.GONE
                scrollUpIndicator?.visibility = if (sv.scrollY > threshold) View.VISIBLE else View.GONE
            }

            scrollDownIndicator?.visibility = View.VISIBLE
            scrollUpIndicator?.visibility = View.GONE
            scrollView?.viewTreeObserver?.addOnScrollChangedListener { updateScrollIndicators() }
        } else {
            scrollDownIndicator?.visibility = View.GONE
            scrollUpIndicator?.visibility = View.GONE
        }
    }

    companion object {
        /**
         * Parse active rule IDs from JSON array string (from DashieNative.getActiveVideoFeedRuleIds()).
         */
        fun parseActiveRuleIds(json: String): Set<String> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }
    }
}
