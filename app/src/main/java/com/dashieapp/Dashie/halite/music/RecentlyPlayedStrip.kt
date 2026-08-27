package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Horizontal scrollable strip of recently played album/artist tiles.
 * 3 tiles visible at a time with floating scroll arrows on edges.
 */
class RecentlyPlayedStrip(
    private val context: Context,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    private val onCloseStrip: (() -> Unit)? = null,
    private val onItemTapped: (RecentlyPlayedItem) -> Unit
) {
    companion object {
        private const val TAG = "RecentlyPlayedStrip"
        const val STRIP_PADDING_DP = 12
        const val HEADER_SIZE_SP = 11f
        const val STRIP_HEIGHT_DP = 115
        const val ARROW_SIZE_DP = 28
    }

    private fun textPrimary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
        else MusicPlayerStyles.textPrimary(context)
    private fun textSecondary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_SECONDARY_DARK
        else MusicPlayerStyles.textSecondary(context)
    private fun borderColor(): Int = if (forceDarkColors) MusicPlayerStyles.BORDER_COLOR_DARK
        else if (MusicPlayerStyles.isDarkMode(context)) MusicPlayerStyles.BORDER_COLOR_DARK
        else MusicPlayerStyles.BORDER_COLOR_LIGHT
    private fun scaledSp(sp: Float): Float = sp * sizeMultiplier

    val view: LinearLayout
    private val tileContainer: LinearLayout
    private val scrollView: HorizontalScrollView
    private var leftArrow: ImageView? = null
    private var rightArrow: ImageView? = null
    private var items: List<RecentlyPlayedItem> = emptyList()
    private var imageLoader: coil.ImageLoader? = null

    init {
        view = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }

        // Left section: down arrow + "Recently Played" heading (clickable to close)
        val leftSection = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(STRIP_PADDING_DP), 0, dp(8), 0)
            isClickable = true; isFocusable = true
            setOnClickListener { onCloseStrip?.invoke() }
        }

        // Down arrow icon (orange accent) — collapses the strip
        ImageView(context).apply {
            val iconSize = dp(20)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = dp(6)
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_chevron_down)
            setColorFilter(MusicPlayerStyles.ACCENT_COLOR)
            scaleType = ImageView.ScaleType.FIT_CENTER
            leftSection.addView(this)
        }

        // "Recently Played" heading — orange accent
        TextView(context).apply {
            text = "Recently Played"
            setTextColor(MusicPlayerStyles.ACCENT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            leftSection.addView(this)
        }

        // Media selector icon — right of heading text (orange accent)
        ImageView(context).apply {
            val iconSize = dp(24)
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginStart = dp(8)
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
            setColorFilter(MusicPlayerStyles.ACCENT_COLOR)
            scaleType = ImageView.ScaleType.FIT_CENTER
            leftSection.addView(this)
        }
        view.addView(leftSection)

        // Scroll area with floating arrows (fills remaining space)
        val scrollWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        tileContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(STRIP_PADDING_DP), dp(4), dp(STRIP_PADDING_DP), dp(4))
        }

        scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tileContainer)
        }
        scrollWrapper.addView(scrollView)

        // Left arrow
        leftArrow = createArrowButton(pointingRight = false).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = dp(2)
            }
            visibility = View.GONE
            setOnClickListener { scrollByTiles(-1) }
        }
        scrollWrapper.addView(leftArrow)

        // Right arrow
        rightArrow = createArrowButton(pointingRight = true).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = dp(2)
            }
            visibility = View.GONE
            setOnClickListener { scrollByTiles(1) }
        }
        scrollWrapper.addView(rightArrow)

        view.addView(scrollWrapper)

        // Update arrow visibility on scroll
        scrollView.viewTreeObserver.addOnScrollChangedListener { updateArrowVisibility() }
    }

    fun update(data: RecentlyPlayedData) {
        items = data.items
        if (items.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        configureImageLoader(data.authToken, data.haBaseUrl, data.maApiToken, data.maApiUrl)
        // Preload all images immediately so they're cached when tiles render
        val loader = imageLoader ?: coil.ImageLoader(context)
        for (item in items) {
            val url = item.imageUrl ?: continue
            val preload = coil.request.ImageRequest.Builder(context)
                .data(url)
                .size(dp(MediaBrowserTile.SIZE_DP), dp(MediaBrowserTile.SIZE_DP))
                .build()
            loader.enqueue(preload)
        }
        rebuildTiles()
        // Use layout listener to ensure tiles are measured before showing arrows
        scrollView.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                scrollView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                updateArrowVisibility()
            }
        })
    }

    fun getBounds(card: View): Rect? {
        if (view.visibility != View.VISIBLE) return null
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val cardLoc = IntArray(2)
        card.getLocationInWindow(cardLoc)
        return Rect(
            loc[0] - cardLoc[0],
            loc[1] - cardLoc[1],
            loc[0] - cardLoc[0] + view.width,
            loc[1] - cardLoc[1] + view.height
        )
    }

    private fun createArrowButton(pointingRight: Boolean): ImageView {
        return ImageView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            setImageDrawable(MusicPlayerStyles.createChevronDrawable(
                context, pointingRight = pointingRight, color = Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(4).toFloat()
            isClickable = true
            isFocusable = true
        }
    }

    private fun scrollByTiles(direction: Int) {
        val scrollAmount = dp(MediaBrowserTile.WRAPPER_WIDTH_DP + MediaBrowserTile.GAP_DP) * direction
        scrollView.smoothScrollBy(scrollAmount, 0)
    }

    private fun updateArrowVisibility() {
        val scrollX = scrollView.scrollX
        val maxScroll = tileContainer.width - scrollView.width
        leftArrow?.visibility = if (scrollX > dp(8)) View.VISIBLE else View.GONE
        rightArrow?.visibility = if (maxScroll > 0 && scrollX < maxScroll - dp(8)) View.VISIBLE else View.GONE
    }

    private fun configureImageLoader(authToken: String?, haBaseUrl: String?, maApiToken: String? = null, maApiUrl: String? = null) {
        imageLoader = MediaBrowserTile.configureImageLoader(context, authToken, haBaseUrl, maApiToken, maApiUrl)
    }

    private fun rebuildTiles() {
        tileContainer.removeAllViews()
        for ((index, item) in items.withIndex()) {
            if (index > 0) {
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(MediaBrowserTile.GAP_DP), 0)
                    tileContainer.addView(this)
                }
            }
            tileContainer.addView(MediaBrowserTile.createTile(
                context, item, imageLoader, forceDarkColors, sizeMultiplier
            ) { tapped ->
                Log.i(TAG, "🎵 Tile tapped: ${tapped.name} (${tapped.mediaType}) uri=${tapped.uri}")
                onItemTapped(tapped)
            })
        }
    }

    private fun dp(dp: Int): Int = (MusicPlayerStyles.dpToPx(context, dp) * sizeMultiplier).toInt()
}
