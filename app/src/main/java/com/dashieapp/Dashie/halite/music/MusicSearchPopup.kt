package com.dashieapp.Dashie.halite.music

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Full-screen search popup with 2-column layout:
 * Left (~30%): search input + recent searches
 * Right (~70%): results grouped by type in horizontal-scrolling rows (MA-style)
 */
class MusicSearchPopup(
    private val activity: Activity,
    private val dataSource: MediaBrowserDataSource?,
    private val imageLoader: coil.ImageLoader?,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    private val activeProfile: MusicProfile? = null,
    private val onItemTapped: (RecentlyPlayedItem) -> Unit
) {
    companion object {
        private const val TAG = "MusicSearchPopup"
        private const val DEBOUNCE_MS = 400L
        private const val PREFS_NAME = "dashie_music_search"
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val MAX_RECENT_SEARCHES = 8
        private val RESULT_CATEGORIES = listOf(
            "track" to "Tracks",
            "album" to "Albums",
            "artist" to "Artists",
            "playlist" to "Playlists"
        )
    }

    private var dialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun dp(dp: Int) = (MusicPlayerStyles.dpToPx(activity, dp) * sizeMultiplier).toInt()

    // ── Recent searches persistence ──

    private fun getRecentSearches(): List<String> {
        val raw = prefs.getString(KEY_RECENT_SEARCHES, "") ?: ""
        return raw.split("\n").filter { it.isNotEmpty() }
    }

    private fun addRecentSearch(query: String) {
        val recents = getRecentSearches().toMutableList()
        recents.remove(query)
        recents.add(0, query)
        val trimmed = recents.take(MAX_RECENT_SEARCHES)
        prefs.edit().putString(KEY_RECENT_SEARCHES, trimmed.joinToString("\n")).apply()
    }

    fun show() {
        val isDark = forceDarkColors || MusicPlayerStyles.isDarkMode(activity)
        val bgColor = if (isDark) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
        val textPrimary = if (isDark) MusicPlayerStyles.TEXT_PRIMARY_DARK else MusicPlayerStyles.textPrimary(activity)
        val textSecondary = if (isDark) MusicPlayerStyles.TEXT_SECONDARY_DARK else MusicPlayerStyles.textSecondary(activity)
        val cardBg = if (isDark) 0xFF222222.toInt() else 0xFFFFFFFF.toInt()

        // Outer wrapper with rounded corners and margin for "floating window" look
        val outerWrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }

        // ── Title bar: centered [spotify icon] Name - Search  [×] right ──
        val titleBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
            )
            background = GradientDrawable().apply {
                // Match speaker bar exactly: HEADER_BG_DARK in dark mode, semi-transparent black in light
                setColor(if (isDark) MusicPlayerStyles.HEADER_BG_DARK else 0x80000000.toInt())
                cornerRadii = floatArrayOf(
                    dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(dp(12), 0, dp(8), 0)
        }

        // Centered content: provider icon + title
        val titleCenter = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        // Provider icon
        val providerIconRes = MusicProviderIcons.iconRes(activeProfile?.providerLabel)
        if (providerIconRes != 0) {
            ImageView(activity).apply {
                val s = dp(16)
                layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(6) }
                setImageResource(providerIconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                titleCenter.addView(this)
            }
        }
        val titleText = if (activeProfile != null) "${activeProfile.memberName} - Search" else "Search Music"
        TextView(activity).apply {
            text = titleText
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * sizeMultiplier)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            titleCenter.addView(this)
        }
        titleBar.addView(titleCenter)

        // Close button (right-aligned)
        ImageView(activity).apply {
            val s = dp(24)
            layoutParams = LinearLayout.LayoutParams(s, s)
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            isClickable = true; isFocusable = true
            setOnClickListener { dismiss() }
            titleBar.addView(this)
        }
        outerWrapper.addView(titleBar)

        // ── Content: 2-column layout ──
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        // ── Left column: search input + recent searches ──
        val leftColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.30f)
            setPadding(0, 0, dp(8), 0)
        }

        val searchInput = EditText(activity).apply {
            hint = "Search..."
            setHintTextColor(textSecondary)
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * sizeMultiplier)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (isDark) 0xFF2A2A2A.toInt() else 0xFFE0E0E0.toInt())
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        leftColumn.addView(searchInput)

        // Recent searches container (below search input)
        val recentsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        leftColumn.addView(recentsContainer)

        fun rebuildRecents() {
            recentsContainer.removeAllViews()
            val recents = getRecentSearches()
            if (recents.isEmpty()) return
            TextView(activity).apply {
                text = "RECENT"
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f * sizeMultiplier)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dp(4), 0, 0, dp(4))
                recentsContainer.addView(this)
            }
            for (q in recents) {
                TextView(activity).apply {
                    text = q
                    setTextColor(textPrimary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sizeMultiplier)
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        searchInput.setText(q)
                        searchInput.setSelection(q.length)
                    }
                    recentsContainer.addView(this)
                }
            }
        }
        rebuildRecents()

        // Spacer
        leftColumn.addView(View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        content.addView(leftColumn)

        // ── Right column: results by category ──
        val rightWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.70f)
        }

        val resultsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        val resultsScroll = ScrollView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
            addView(resultsContainer)
        }
        rightWrapper.addView(resultsScroll)

        // Vertical scroll indicator
        val vDownArrow = ImageView(activity).apply {
            val s = dp(20)
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) 0xCC444444.toInt() else 0xCCCCCCCC.toInt())
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_chevron_down)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        rightWrapper.addView(vDownArrow)

        resultsScroll.viewTreeObserver.addOnScrollChangedListener {
            val child = resultsScroll.getChildAt(0) ?: return@addOnScrollChangedListener
            val maxScroll = child.height - resultsScroll.height
            vDownArrow.visibility = if (maxScroll > 0 && resultsScroll.scrollY < maxScroll - dp(8)) View.VISIBLE else View.GONE
        }

        // Empty state
        val emptyLabel = TextView(activity).apply {
            text = "Search for songs, albums, artists..."
            setTextColor(textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sizeMultiplier)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }
        resultsContainer.addView(emptyLabel)

        content.addView(rightWrapper)
        outerWrapper.addView(content)

        // ── Populate results by category with horizontal scroll + arrows ──
        fun populateResults(resultsByType: Map<String, List<RecentlyPlayedItem>>) {
            handler.post {
                resultsContainer.removeAllViews()
                val totalResults = resultsByType.values.sumOf { it.size }
                if (totalResults == 0) {
                    resultsContainer.addView(TextView(activity).apply {
                        text = "No results found"
                        setTextColor(textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sizeMultiplier)
                        gravity = Gravity.CENTER
                        setPadding(dp(16), dp(40), dp(16), dp(16))
                    })
                    return@post
                }

                for ((type, label) in RESULT_CATEGORIES) {
                    val items = resultsByType[type] ?: continue
                    if (items.isEmpty()) continue

                    // Category header
                    TextView(activity).apply {
                        text = label
                        setTextColor(textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sizeMultiplier)
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setPadding(dp(4), dp(8), 0, dp(4))
                        resultsContainer.addView(this)
                    }

                    // Horizontal scroll with right-arrow indicator
                    val hWrapper = FrameLayout(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val hScroll = HorizontalScrollView(activity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        isHorizontalScrollBarEnabled = false
                    }
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, dp(8), 0)
                    }
                    for (item in items) {
                        val tile = MediaBrowserTile.createTile(
                            context = activity, item = item, imageLoader = imageLoader,
                            forceDarkColors = forceDarkColors, sizeMultiplier = sizeMultiplier
                        ) { tappedItem ->
                            // Save search on result tap
                            val query = searchInput.text.toString().trim()
                            if (query.length >= 2) { addRecentSearch(query); rebuildRecents() }
                            onItemTapped(tappedItem)
                            dismiss()
                        }
                        row.addView(tile)
                    }
                    hScroll.addView(row)
                    hWrapper.addView(hScroll)

                    // Right arrow indicator
                    if (items.size > 3) {
                        val hArrow = ImageView(activity).apply {
                            val s = dp(20)
                            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            }
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(if (isDark) 0xCC444444.toInt() else 0xCCCCCCCC.toInt())
                            }
                            setImageResource(com.dashieapp.Dashie.R.drawable.ic_chevron_down)
                            rotation = -90f  // Point right
                            setColorFilter(Color.WHITE)
                            scaleType = ImageView.ScaleType.CENTER_INSIDE
                            setPadding(dp(4), dp(4), dp(4), dp(4))
                            elevation = dp(4).toFloat()
                        }
                        hWrapper.addView(hArrow)
                        // Hide when scrolled to end
                        hScroll.viewTreeObserver.addOnScrollChangedListener {
                            val child = hScroll.getChildAt(0) ?: return@addOnScrollChangedListener
                            val maxH = child.width - hScroll.width
                            hArrow.visibility = if (maxH > 0 && hScroll.scrollX < maxH - dp(8)) View.VISIBLE else View.GONE
                        }
                    }

                    resultsContainer.addView(hWrapper)
                }

                // Update vertical scroll indicator after results populate
                resultsScroll.post {
                    val child = resultsScroll.getChildAt(0) ?: return@post
                    val maxScroll = child.height - resultsScroll.height
                    vDownArrow.visibility = if (maxScroll > dp(8)) View.VISIBLE else View.GONE
                }
            }
        }

        // ── Search logic ──
        fun doSearch(query: String) {
            if (dataSource == null) return
            if (query.length < 2) {
                handler.post {
                    resultsContainer.removeAllViews()
                    resultsContainer.addView(emptyLabel.apply {
                        text = "Search for songs, albums, artists..."
                    })
                }
                return
            }
            handler.post {
                resultsContainer.removeAllViews()
                resultsContainer.addView(TextView(activity).apply {
                    text = "Searching..."
                    setTextColor(textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sizeMultiplier)
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(20), dp(16), dp(16))
                })
            }
            Thread {
                try {
                    val results = dataSource.search(query, 30)
                    Log.d(TAG, "Search '$query': ${results.size} results")
                    val grouped = results.groupBy { it.mediaType }
                    populateResults(grouped)
                } catch (e: Exception) {
                    Log.e(TAG, "Search failed: ${e.message}")
                    handler.post {
                        resultsContainer.removeAllViews()
                        resultsContainer.addView(TextView(activity).apply {
                            text = "Search failed"
                            setTextColor(textSecondary)
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * sizeMultiplier)
                            gravity = Gravity.CENTER
                            setPadding(dp(16), dp(20), dp(16), dp(16))
                        })
                    }
                }
            }.start()
        }

        // Wire search input
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pendingSearch?.let { handler.removeCallbacks(it) }
                val query = s?.toString()?.trim() ?: ""
                pendingSearch = Runnable { doSearch(query) }
                handler.postDelayed(pendingSearch!!, DEBOUNCE_MS)
            }
        })
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                pendingSearch?.let { handler.removeCallbacks(it) }
                val query = searchInput.text.toString().trim()
                doSearch(query)
                // Save on explicit submit
                if (query.length >= 2) {
                    addRecentSearch(query)
                    rebuildRecents()
                }
                true
            } else false
        }

        // ── Show dialog with margin for floating look ──
        dialog = AlertDialog.Builder(activity)
            .setView(outerWrapper)
            .setCancelable(true)
            .create()

        dialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply {
                dimAmount = 0.6f
                width = (activity.resources.displayMetrics.widthPixels * 0.92).toInt()
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP
            }
            // Add padding inside the decor view to create space at top and bottom
            decorView.setPadding(0, dp(8), 0, dp(16))
        }

        dialog?.show()

        searchInput.requestFocus()
        handler.postDelayed({
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    fun dismiss() {
        pendingSearch?.let { handler.removeCallbacks(it) }
        dialog?.dismiss()
        dialog = null
    }
}
