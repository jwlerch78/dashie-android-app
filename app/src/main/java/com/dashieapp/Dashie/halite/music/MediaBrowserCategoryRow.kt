package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A single categorized row: header label + horizontally scrollable tiles.
 * Used in the sectioned recents view (Songs, Albums, Artists, Playlists).
 */
class MediaBrowserCategoryRow(
    private val context: Context,
    private val title: String,
    private val items: List<RecentlyPlayedItem>,
    private val imageLoader: coil.ImageLoader?,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    private val onItemTapped: (RecentlyPlayedItem) -> Unit
) {
    val view: LinearLayout

    init {
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        // Header
        val header = TextView(context).apply {
            text = title
            setTextColor(textPrimary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(13f))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(2), 0, dp(4))
        }
        view.addView(header)

        // Horizontal scroll with tiles
        val scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tilesRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(8), 0)
        }

        for ((index, item) in items.withIndex()) {
            if (index > 0) {
                // Gap spacer
                android.view.View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(MediaBrowserTile.GAP_DP), 0)
                    tilesRow.addView(this)
                }
            }
            val tile = MediaBrowserTile.createTile(
                context, item, imageLoader, forceDarkColors, sizeMultiplier, onItemTapped
            )
            tilesRow.addView(tile)
        }

        scrollView.addView(tilesRow)
        view.addView(scrollView)
    }

    private fun textPrimary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
        else MusicPlayerStyles.textPrimary(context)

    private fun scaledSp(sp: Float): Float = sp * sizeMultiplier
    private fun dp(dp: Int): Int = (MusicPlayerStyles.dpToPx(context, dp) * sizeMultiplier).toInt()
}
