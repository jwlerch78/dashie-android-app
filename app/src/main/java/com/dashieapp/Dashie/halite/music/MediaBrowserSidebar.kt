package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Active music profile data for the sidebar header.
 */
data class MusicProfile(
    val memberName: String,        // e.g. "Mary"
    val avatarUrl: String?,        // URL for avatar image
    val assignedColor: String,     // Hex color for fallback avatar, e.g. "#FF6B6B"
    val providerLabel: String?,    // e.g. "Spotify", "YouTube Music", null if unknown
    val profileId: String? = null  // Family member ID or MA user ID (for switching)
)

/**
 * Left sidebar (30% width) for the media browser panel.
 * Shows a vertical list of browsing categories with selection highlight.
 * Optionally shows an active music profile header at the top.
 */
class MediaBrowserSidebar(
    private val context: Context,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    private val onCategorySelected: (MediaBrowserDataSource.Category) -> Unit,
    private val onProfileTapped: (() -> Unit)? = null,
    private val onSearchTapped: (() -> Unit)? = null
) {
    companion object {
        private const val ROW_HEIGHT_DP = 36
        private const val ICON_SIZE_DP = 18
        private const val TEXT_SIZE_SP = 17f
        private const val PADDING_DP = 8
        private const val SELECTED_BG = 0x33FF9500.toInt() // Orange accent at 20% opacity
    }

    val view: LinearLayout
    private var selectedCategory = MediaBrowserDataSource.Category.RECENTS
    private var profileSelected = false  // True when profile header is the active "category"
    private val rowViews = mutableMapOf<MediaBrowserDataSource.Category, LinearLayout>()
    private var profileContainer: LinearLayout? = null
    private var profileAvatarView: ImageView? = null
    private var profileNameView: TextView? = null
    private var profileProviderView: TextView? = null
    private var profileDivider: View? = null

    init {
        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 0.30f
            )
        }

        // Scrollable category list
        val scrollWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val scrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }
        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }

        // Profile header (hidden until setActiveProfile is called)
        profileContainer = createProfileHeader()
        profileContainer!!.visibility = View.GONE
        listContainer.addView(profileContainer)

        // Divider below profile (hidden until profile is set)
        profileDivider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
                marginStart = dp(PADDING_DP + 4)
                marginEnd = dp(PADDING_DP)
            }
            setBackgroundColor(borderColor())
            visibility = View.GONE
        }
        listContainer.addView(profileDivider)

        for (category in MediaBrowserDataSource.Category.values()) {
            // Hide genres for now — genre browsing needs more work
            if (category == MediaBrowserDataSource.Category.GENRES) continue

            val row = createCategoryRow(category)
            rowViews[category] = row
            listContainer.addView(row)

            // Insert search row right after Recents
            if (category == MediaBrowserDataSource.Category.RECENTS) {
                listContainer.addView(createSearchRow())
            }
        }

        scrollView.addView(listContainer)
        scrollWrapper.addView(scrollView)

        // Scroll down indicator
        val downArrow = ImageView(context).apply {
            val s = dp(ICON_SIZE_DP)
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (forceDarkColors) 0xCC444444.toInt() else 0xCCCCCCCC.toInt())
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_chevron_down)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(3), dp(3), dp(3), dp(3))
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        scrollWrapper.addView(downArrow)

        // Show/hide indicator based on scroll position
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            val child = scrollView.getChildAt(0) ?: return@addOnScrollChangedListener
            val maxScroll = child.height - scrollView.height
            downArrow.visibility = if (maxScroll > 0 && scrollView.scrollY < maxScroll - dp(8)) View.VISIBLE else View.GONE
        }
        // Check after layout
        scrollView.post {
            val child = scrollView.getChildAt(0) ?: return@post
            val maxScroll = child.height - scrollView.height
            downArrow.visibility = if (maxScroll > dp(8)) View.VISIBLE else View.GONE
        }

        view.addView(scrollWrapper)

        // Apply initial selection
        updateSelection()
    }

    /** Set or update the active music profile shown at the top of the sidebar */
    fun setActiveProfile(profile: MusicProfile?) {
        if (profile == null) {
            profileContainer?.visibility = View.GONE
            profileDivider?.visibility = View.GONE
            return
        }
        profileContainer?.visibility = View.VISIBLE
        profileDivider?.visibility = View.VISIBLE
        profileNameView?.text = "${profile.memberName}'s Music"

        // Provider badge with brand color
        if (profile.providerLabel != null) {
            profileProviderView?.text = profile.providerLabel
            profileProviderView?.setTextColor(providerColor(profile.providerLabel))
            profileProviderView?.visibility = View.VISIBLE
        } else {
            profileProviderView?.visibility = View.GONE
        }

        // Avatar: try loading image, fallback to color-initial circle
        val avatarView = profileAvatarView ?: return
        if (profile.avatarUrl != null) {
            val request = coil.request.ImageRequest.Builder(context)
                .data(profile.avatarUrl)
                .target(avatarView)
                .transformations(coil.transform.CircleCropTransformation())
                .listener(onError = { _, _ -> setAvatarFallback(avatarView, profile) })
                .build()
            coil.ImageLoader(context).enqueue(request)
        } else {
            setAvatarFallback(avatarView, profile)
        }
    }

    private fun setAvatarFallback(avatarView: ImageView, profile: MusicProfile) {
        val color = try { Color.parseColor(profile.assignedColor) } catch (_: Exception) { 0xFF888888.toInt() }
        val initial = profile.memberName.firstOrNull()?.uppercase() ?: "?"
        // Create a colored circle with initial letter as a bitmap
        val size = dp(28)
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        paint.textSize = size * 0.45f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, textY, paint)
        avatarView.setImageBitmap(bitmap)
    }

    fun selectCategory(category: MediaBrowserDataSource.Category) {
        if (selectedCategory == category && !profileSelected) return
        profileSelected = false
        selectedCategory = category
        updateSelection()
        onCategorySelected(category)
    }

    /** Select the profile header as the active item (shows profile grid) */
    private fun selectProfileCategory() {
        if (profileSelected) return
        profileSelected = true
        updateSelection()
        onProfileTapped?.invoke()
    }

    /** Select profile category from external code (e.g., speaker bar tap) */
    fun selectProfileFromExternal() {
        selectProfileCategory()
    }

    fun getSelectedCategory(): MediaBrowserDataSource.Category = selectedCategory
    fun isProfileSelected(): Boolean = profileSelected

    private fun createProfileHeader(): LinearLayout {
        val avatarSize = dp(28)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(PADDING_DP + 4), dp(10), dp(PADDING_DP), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { selectProfileCategory() }

            // Avatar
            profileAvatarView = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    marginEnd = dp(8)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            addView(profileAvatarView)

            // Name + provider (vertical stack)
            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            profileNameView = TextView(context).apply {
                setTextColor(textPrimary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(14f))
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            textColumn.addView(profileNameView)

            profileProviderView = TextView(context).apply {
                setTextColor(0xFF1DB954.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(10f))
                visibility = View.GONE
                maxLines = 1
            }
            textColumn.addView(profileProviderView)
            addView(textColumn)

            // Chevron
            val chevronSize = dp(ICON_SIZE_DP)
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(chevronSize, chevronSize).apply {
                    marginStart = dp(4)
                }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_chevron_right)
                setColorFilter(textSecondary())
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }
    }

    private fun createCategoryRow(category: MediaBrowserDataSource.Category): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
            )
            setPadding(dp(PADDING_DP + 4), 0, dp(PADDING_DP), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { selectCategory(category) }

            // Category icon
            val iconRes = getCategoryIconRes(category)
            if (iconRes != 0) {
                ImageView(context).apply {
                    val iconSize = dp(ICON_SIZE_DP)
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginEnd = dp(8)
                    }
                    setImageResource(iconRes)
                    setColorFilter(textSecondary())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    tag = "icon" // For updating color on selection
                    addView(this)
                }
            }

            // Category label
            TextView(context).apply {
                text = category.displayName
                setTextColor(textSecondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(TEXT_SIZE_SP))
                tag = "label" // For updating color on selection
                addView(this)
            }
        }
    }

    private fun createSearchRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)
            )
            setPadding(dp(PADDING_DP + 4), 0, dp(PADDING_DP), 0)
            isClickable = true; isFocusable = true
            setOnClickListener { onSearchTapped?.invoke() }

            ImageView(context).apply {
                val iconSize = dp(ICON_SIZE_DP)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply { marginEnd = dp(8) }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_search)
                setColorFilter(textSecondary())
                scaleType = ImageView.ScaleType.FIT_CENTER
                addView(this)
            }
            TextView(context).apply {
                text = "Search"
                setTextColor(textSecondary())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(TEXT_SIZE_SP))
                addView(this)
            }
        }
    }

    private fun updateSelection() {
        // Profile header highlight
        profileContainer?.background = if (profileSelected) {
            GradientDrawable().apply {
                setColor(SELECTED_BG)
                cornerRadius = dp(6).toFloat()
            }
        } else null

        // Category rows
        for ((category, row) in rowViews) {
            val isSelected = category == selectedCategory && !profileSelected
            row.background = if (isSelected) {
                GradientDrawable().apply {
                    setColor(SELECTED_BG)
                    cornerRadius = dp(6).toFloat()
                }
            } else null

            // Update icon and label colors
            val iconColor = if (isSelected) MusicPlayerStyles.ACCENT_COLOR else textSecondary()
            val labelColor = if (isSelected) textPrimary() else textSecondary()
            val labelTypeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                when (child.tag) {
                    "icon" -> (child as ImageView).setColorFilter(iconColor)
                    "label" -> (child as TextView).apply {
                        setTextColor(labelColor)
                        typeface = labelTypeface
                    }
                }
            }
        }
    }

    /** Deselect profile and go back to a category (called after profile is switched) */
    fun deselectProfile() {
        if (!profileSelected) return
        profileSelected = false
        selectedCategory = MediaBrowserDataSource.Category.RECENTS
        updateSelection()
        onCategorySelected(selectedCategory)
    }

    private fun getCategoryIconRes(category: MediaBrowserDataSource.Category): Int = when (category) {
        MediaBrowserDataSource.Category.RECENTS -> com.dashieapp.Dashie.R.drawable.ic_type_song
        MediaBrowserDataSource.Category.FAVORITES -> com.dashieapp.Dashie.R.drawable.ic_sidebar_star
        MediaBrowserDataSource.Category.ALBUMS -> com.dashieapp.Dashie.R.drawable.ic_type_album
        MediaBrowserDataSource.Category.ARTISTS -> com.dashieapp.Dashie.R.drawable.ic_type_artist
        MediaBrowserDataSource.Category.PLAYLISTS -> com.dashieapp.Dashie.R.drawable.ic_type_playlist
        MediaBrowserDataSource.Category.RADIO -> com.dashieapp.Dashie.R.drawable.ic_type_playlist // Reuse playlist icon for now
        MediaBrowserDataSource.Category.GENRES -> com.dashieapp.Dashie.R.drawable.ic_type_album    // Reuse album icon for now
    }

    private fun providerColor(label: String): Int = when {
        label.contains("Spotify", ignoreCase = true) -> 0xFF1DB954.toInt()
        label.contains("YouTube", ignoreCase = true) -> 0xFFFF0000.toInt()
        label.contains("Apple", ignoreCase = true) -> 0xFFFC3C44.toInt()
        label.contains("Tidal", ignoreCase = true) -> 0xFF000000.toInt()
        else -> textSecondary()
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
