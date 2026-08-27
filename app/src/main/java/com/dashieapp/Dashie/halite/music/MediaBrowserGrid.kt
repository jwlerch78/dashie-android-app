package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.util.Log
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Right content area (70% width) for the media browser panel.
 * Persistent search bar on top, wrapping tile grid with vertical scroll below.
 */
class MediaBrowserGrid(
    private val context: Context,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    private val onItemTapped: (RecentlyPlayedItem) -> Unit,
    private val onSearchChanged: (String) -> Unit
) {
    companion object {
        private const val SEARCH_BAR_HEIGHT_DP = 32
        private const val SEARCH_TEXT_SIZE_SP = 12f
        private const val GRID_PADDING_DP = 8
        private const val SEARCH_DEBOUNCE_MS = 400L
        private const val ARROW_SIZE_DP = 20
    }

    val view: LinearLayout
    private val gridContainer: LinearLayout
    private val scrollView: ScrollView
    private val searchInput: EditText
    private val emptyLabel: TextView
    private var imageLoader: coil.ImageLoader? = null
    private var items: List<RecentlyPlayedItem> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private var suppressTextWatcher = false

    // Scroll arrows
    private var upArrow: ImageView? = null
    private var downArrow: ImageView? = null

    init {
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.70f
            )
            setPadding(dp(GRID_PADDING_DP), dp(GRID_PADDING_DP), dp(GRID_PADDING_DP), 0)
        }

        // Search bar hidden for now — keyboard covers results in overlay window.
        // TODO: implement as a floating search dialog positioned above keyboard
        searchInput = EditText(context).apply { visibility = View.GONE }

        // Scroll wrapper with floating arrows
        val scrollWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(6) }
        }

        gridContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }

        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(gridContainer)
        }
        scrollWrapper.addView(scrollView)

        // Empty / loading state label
        emptyLabel = TextView(context).apply {
            text = "Loading..."
            setTextColor(textSecondary())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(12f))
            gravity = Gravity.CENTER
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        scrollWrapper.addView(emptyLabel)

        // Up arrow
        upArrow = createArrowButton(pointingUp = true).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(2)
            }
            visibility = View.GONE
            setOnClickListener { scrollView.smoothScrollBy(0, -dp(100)) }
        }
        scrollWrapper.addView(upArrow)

        // Down arrow
        downArrow = createArrowButton(pointingUp = false).apply {
            layoutParams = FrameLayout.LayoutParams(dp(ARROW_SIZE_DP), dp(ARROW_SIZE_DP)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(2)
            }
            visibility = View.GONE
            setOnClickListener { scrollView.smoothScrollBy(0, dp(100)) }
        }
        scrollWrapper.addView(downArrow)

        view.addView(scrollWrapper)

        scrollView.viewTreeObserver.addOnScrollChangedListener { updateArrowVisibility() }
    }

    /** Replace the grid tiles with a custom view (e.g., profile cards). Call updateItems to restore tiles. */
    fun showCustomView(customView: View) {
        items = emptyList()
        currentSections = emptyList()
        gridContainer.removeAllViews()
        gridContainer.addView(customView)
        gridContainer.visibility = View.VISIBLE
        emptyLabel.visibility = View.GONE
        upArrow?.visibility = View.GONE
        downArrow?.visibility = View.GONE
    }

    fun showLoading() {
        items = emptyList()
        currentSections = emptyList()
        gridContainer.removeAllViews()
        gridContainer.visibility = View.GONE
        emptyLabel.text = "Loading..."
        emptyLabel.visibility = View.VISIBLE
        upArrow?.visibility = View.GONE
        downArrow?.visibility = View.GONE
    }

    fun updateItems(newItems: List<RecentlyPlayedItem>, emptyMessage: String = "No items found") {
        // Skip rebuild if the items list is unchanged (prevents flash on periodic refresh)
        if (newItems.size == items.size && newItems.isNotEmpty() && gridContainer.childCount > 0) {
            val unchanged = newItems.zip(items).all { (a, b) -> a.uri == b.uri && a.name == b.name }
            if (unchanged) {
                Log.d("MediaBrowserGrid", "updateItems: skipped rebuild — ${newItems.size} items unchanged")
                return
            }
        }
        Log.i("MediaBrowserGrid", "updateItems: ${newItems.size} items, viewW=${view.width}x${view.height}, vis=${view.visibility}")
        items = newItems
        if (items.isEmpty()) {
            gridContainer.removeAllViews()
            gridContainer.visibility = View.GONE
            emptyLabel.text = emptyMessage
            emptyLabel.visibility = View.VISIBLE
            upArrow?.visibility = View.GONE
            downArrow?.visibility = View.GONE
            return
        }
        gridContainer.visibility = View.VISIBLE
        emptyLabel.visibility = View.GONE
        rebuildGrid()
    }

    /** Cached sections for diff check on periodic refresh */
    private var currentSections: List<RecentlyPlayedSection> = emptyList()

    /** Display categorized horizontal rows (Songs, Albums, Artists, Playlists). */
    fun updateSections(sections: List<RecentlyPlayedSection>, emptyMessage: String = "No recently played items") {
        // Skip rebuild if sections haven't changed
        if (sections.size == currentSections.size && sections.isNotEmpty() && gridContainer.childCount > 0) {
            val unchanged = sections.zip(currentSections).all { (a, b) ->
                a.title == b.title && a.items.size == b.items.size &&
                    a.items.zip(b.items).all { (ai, bi) -> ai.uri == bi.uri }
            }
            if (unchanged) {
                Log.d("MediaBrowserGrid", "updateSections: skipped rebuild — sections unchanged")
                return
            }
        }

        currentSections = sections
        // Also update flat items list for compatibility
        items = sections.flatMap { it.items }

        if (sections.isEmpty() || items.isEmpty()) {
            gridContainer.removeAllViews()
            gridContainer.visibility = View.GONE
            emptyLabel.text = emptyMessage
            emptyLabel.visibility = View.VISIBLE
            upArrow?.visibility = View.GONE
            downArrow?.visibility = View.GONE
            return
        }

        Log.i("MediaBrowserGrid", "updateSections: ${sections.size} sections, ${items.size} total items")
        gridContainer.removeAllViews()
        gridContainer.visibility = View.VISIBLE
        emptyLabel.visibility = View.GONE

        for (section in sections) {
            val row = MediaBrowserCategoryRow(
                context = context,
                title = section.title,
                items = section.items,
                imageLoader = imageLoader,
                forceDarkColors = forceDarkColors,
                sizeMultiplier = sizeMultiplier,
                onItemTapped = onItemTapped
            )
            gridContainer.addView(row.view)
        }

        scrollView.post { updateArrowVisibility() }
    }

    fun setImageLoader(loader: coil.ImageLoader?) {
        imageLoader = loader
    }

    fun clearSearch() {
        suppressTextWatcher = true
        searchInput.setText("")
        suppressTextWatcher = false
    }

    private var pendingRebuild = false
    private var retryCount = 0

    private fun rebuildGrid() {
        gridContainer.removeAllViews()
        pendingRebuild = true
        retryCount = 0
        attemptRebuild()
    }

    private fun attemptRebuild() {
        view.post {
            if (!pendingRebuild) return@post
            gridContainer.removeAllViews()
            val availableWidth = view.width - dp(GRID_PADDING_DP * 2)
            Log.i("MediaBrowserGrid", "rebuildGrid attempt #$retryCount: viewW=${view.width}, availW=$availableWidth, items=${items.size}")
            if (availableWidth <= 0) {
                retryCount++
                if (retryCount < 20) {
                    // View not measured yet — retry after a short delay
                    handler.postDelayed({ attemptRebuild() }, 50)
                }
                return@post
            }
            pendingRebuild = false

            val tileWidth = dp(MediaBrowserTile.WRAPPER_WIDTH_DP)
            val gap = dp(MediaBrowserTile.GAP_DP)
            val columns = maxOf(1, (availableWidth + gap) / (tileWidth + gap))

            var row: LinearLayout? = null
            for ((index, item) in items.withIndex()) {
                if (index % columns == 0) {
                    row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.START
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (index > 0) topMargin = gap
                        }
                    }
                    gridContainer.addView(row)
                }

                if (index % columns != 0) {
                    // Gap spacer between tiles in a row
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(gap, 0)
                        row!!.addView(this)
                    }
                }

                val tile = MediaBrowserTile.createTile(
                    context, item, imageLoader, forceDarkColors, sizeMultiplier, onItemTapped
                )
                row!!.addView(tile)
            }

            // Update scroll arrows after grid is built
            scrollView.post { updateArrowVisibility() }
        }
    }

    private fun createArrowButton(pointingUp: Boolean): ImageView {
        return ImageView(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC000000.toInt())
            }
            setImageDrawable(MusicPlayerStyles.createTriangleDrawable(
                context, pointingUp = pointingUp, color = Color.WHITE))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(4).toFloat()
            isClickable = true
            isFocusable = true
        }
    }

    private fun updateArrowVisibility() {
        val scrollY = scrollView.scrollY
        val maxScroll = gridContainer.height - scrollView.height
        upArrow?.visibility = if (scrollY > dp(8)) View.VISIBLE else View.GONE
        downArrow?.visibility = if (maxScroll > 0 && scrollY < maxScroll - dp(8)) View.VISIBLE else View.GONE
    }

    private fun textPrimary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
        else MusicPlayerStyles.textPrimary(context)
    private fun textSecondary(): Int = if (forceDarkColors) MusicPlayerStyles.TEXT_SECONDARY_DARK
        else MusicPlayerStyles.textSecondary(context)
    private fun borderColor(): Int = if (forceDarkColors) MusicPlayerStyles.BORDER_COLOR_DARK
        else if (MusicPlayerStyles.isDarkMode(context)) MusicPlayerStyles.BORDER_COLOR_DARK
        else MusicPlayerStyles.BORDER_COLOR_LIGHT
    private fun scaledSp(sp: Float): Float = sp * sizeMultiplier
    private fun dp(dp: Int): Int = (MusicPlayerStyles.dpToPx(context, dp) * sizeMultiplier).toInt()
}
