package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Media browser panel — always-expanded sidebar + grid layout.
 * Replaces RecentlyPlayedStrip in the music player renderers.
 *
 * Collapsed: ~115dp horizontal strip of recent tiles with "Browse Music" header
 * Expanded: 324dp panel — left sidebar (30%) with categories, right area (70%) with search + grid
 */
class MediaBrowserPanel(
    private val context: Context,
    private val forceDarkColors: Boolean = false,
    private val sizeMultiplier: Float = 1f,
    private val onItemTapped: (RecentlyPlayedItem) -> Unit,
    private val onHeightChanged: (() -> Unit)? = null,
    private val onProfileTapped: (() -> Unit)? = null,
    var onProfileSwitched: ((MusicProfile?) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MediaBrowserPanel"
        const val COLLAPSED_HEIGHT_DP = 120  // Same as RECENTLY_PLAYED_STRIP_HEIGHT_DP
        const val EXPANDED_HEIGHT_DP = 324   // Same as SpeakerGroupDrawer.DRAWER_HEIGHT_DP
    }

    val view: FrameLayout
    var isExpanded = true
        private set

    // Expanded state: sidebar + grid
    private var expandedView: LinearLayout? = null
    private var sidebar: MediaBrowserSidebar? = null
    private var grid: MediaBrowserGrid? = null

    // Data
    private var dataSource: MediaBrowserDataSource? = null
    private var imageLoader: coil.ImageLoader? = null
    private var cachedRecentData: RecentlyPlayedData? = null
    private var cachedActiveProfile: MusicProfile? = null
    private var hasData = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Drill header shown above the grid when the user has drilled into a
    // playlist, album, or genre. Same UX as MusicExplorerPanel (maximized
    // player). Hidden at top-level category view.
    private var drillHeader: LinearLayout? = null
    private var drillHeaderArt: ImageView? = null
    private var drillHeaderLabel: TextView? = null
    private var drillHeaderPlayAll: View? = null
    private var drillBackAction: (() -> Unit)? = null
    private var drillPlayAllItem: RecentlyPlayedItem? = null

    init {
        // Cap height: screen height minus strip content (drawer sits below top bar)
        val screenHeight = context.resources.displayMetrics.heightPixels
        val stripHeightPx = dp(MusicPlayerStyles.STRIP_HEIGHT_DP)
        val desiredHeight = dp(EXPANDED_HEIGHT_DP)
        val maxHeight = screenHeight - stripHeightPx
        val panelHeight = minOf(desiredHeight, maxHeight)

        view = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, panelHeight
            )
            visibility = View.GONE
        }

        buildExpandedView()
    }

    /** Set the active music profile displayed at the top of the sidebar */
    fun setActiveProfile(profile: MusicProfile?) {
        cachedActiveProfile = profile
        sidebar?.setActiveProfile(profile)
    }

    /** Select the profile category in the sidebar (opens profile grid) */
    fun selectProfileInSidebar() {
        sidebar?.selectProfileFromExternal()
    }

    /** Music profile manager (set externally for profile grid display) */
    var musicProfileManager: MusicProfileManager? = null

    /** Called when profile is selected as category — show profile cards in grid area */
    private fun showProfileGrid() {
        val mgr = musicProfileManager ?: return
        val profiles = mgr.getAllSwitchableProfiles()
        if (profiles.isEmpty()) return

        // Build a custom vertical list of profile cards and set it as the grid content
        val activeId = mgr.getActiveProfile()?.profileId
        grid?.showCustomView(buildProfileList(profiles, activeId))
    }

    /** Build a scrollable vertical list of profile cards */
    private fun buildProfileList(profiles: List<MusicProfile>, activeProfileId: String?): android.view.View {
        val isDark = forceDarkColors || MusicPlayerStyles.isDarkMode(context)
        val scrollView = android.widget.ScrollView(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }
        val list = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        for (profile in profiles) {
            val isActive = profile.profileId == activeProfileId
            val card = buildProfileCard(profile, isActive, isDark)
            card.setOnClickListener {
                val pid = profile.profileId ?: return@setOnClickListener
                val mgr = musicProfileManager ?: return@setOnClickListener
                mgr.setActiveProfileId(pid)
                val newProfile = mgr.getActiveProfile()
                sidebar?.setActiveProfile(newProfile)
                onProfileSwitched?.invoke(newProfile)

                // Swap to user-scoped API client in background, then refresh data
                grid?.showLoading()
                sidebar?.deselectProfile()
                Thread {
                    val userClient = mgr.getUserScopedClient()
                    if (userClient != null) {
                        dataSource?.swapApiClient(userClient)
                    }
                    // Re-fetch categorized recents with the new user's library
                    val ds = dataSource
                    if (ds != null) {
                        ds.clearCache()
                        val sections = ds.fetchRecentsCategorized()
                        val allItems = sections.flatMap { it.items }
                        mainHandler.post {
                            grid?.setImageLoader(imageLoader)
                            if (sections.isNotEmpty()) {
                                grid?.updateSections(sections, "No recently played items")
                            } else {
                                grid?.updateItems(allItems, "No recently played items")
                            }
                        }
                    }
                }.start()
            }
            list.addView(card)
        }

        scrollView.addView(list)
        return scrollView
    }

    /** Build a single profile card row: [Avatar] Name \n Provider   [Spotify logo] */
    private fun buildProfileCard(profile: MusicProfile, isActive: Boolean, isDark: Boolean): android.widget.LinearLayout {
        val avatarSize = dp(48)
        val providerIconSize = dp(24)

        return android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true

            // Card background
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (isActive) 0x33FF9500.toInt() else if (isDark) 0xFF2A2A2A.toInt() else 0xFFF0F0F0.toInt())
                cornerRadius = dp(10).toFloat()
                if (isActive) {
                    setStroke(dp(2), MusicPlayerStyles.ACCENT_COLOR)
                }
            }

            // Avatar
            val avatarView = android.widget.ImageView(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                    marginEnd = dp(14)
                }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
            addView(avatarView)

            // Load avatar
            if (profile.avatarUrl != null) {
                val request = coil.request.ImageRequest.Builder(context)
                    .data(profile.avatarUrl)
                    .target(avatarView)
                    .transformations(coil.transform.CircleCropTransformation())
                    .listener(onError = { _, _ -> setAvatarFallback(avatarView, profile, avatarSize) })
                    .build()
                coil.ImageLoader(context).enqueue(request)
            } else {
                setAvatarFallback(avatarView, profile, avatarSize)
            }

            // Name + provider text (vertical stack)
            val textColumn = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textColumn.addView(android.widget.TextView(context).apply {
                text = profile.memberName
                setTextColor(if (isDark) MusicPlayerStyles.TEXT_PRIMARY_DARK else MusicPlayerStyles.textPrimary(context))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * sizeMultiplier)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (profile.providerLabel != null) {
                textColumn.addView(android.widget.TextView(context).apply {
                    text = profile.providerLabel
                    setTextColor(if (isDark) MusicPlayerStyles.TEXT_SECONDARY_DARK else MusicPlayerStyles.textSecondary(context))
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * sizeMultiplier)
                    maxLines = 1
                })
            }
            addView(textColumn)

            // Provider icon on the right
            val iconRes = providerIconRes(profile.providerLabel)
            if (iconRes != 0) {
                addView(android.widget.ImageView(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(providerIconSize, providerIconSize).apply {
                        marginStart = dp(8)
                    }
                    setImageResource(iconRes)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                })
            }
        }
    }

    private fun setAvatarFallback(view: android.widget.ImageView, profile: MusicProfile, size: Int) {
        val color = try { android.graphics.Color.parseColor(profile.assignedColor) } catch (_: Exception) { 0xFF888888.toInt() }
        val initial = profile.memberName.firstOrNull()?.uppercase() ?: "?"
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = android.graphics.Color.WHITE
        paint.textSize = size * 0.4f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, textY, paint)
        view.setImageBitmap(bitmap)
    }

    private fun providerIconRes(label: String?): Int = MusicProviderIcons.iconRes(label)

    /** Set the data source for library browsing (called once when MA API client is available). */
    fun setDataSource(ds: MediaBrowserDataSource) {
        dataSource = ds
        // If panel has a data source, it can show content (categories) even without recents
        if (!hasData && ds != null) {
            hasData = true
        }
    }

    fun hasDataSource(): Boolean = dataSource != null

    /** Re-apply cached data to the grid (e.g., when panel becomes visible after data arrived). */
    fun refreshFromCache() {
        val data = cachedRecentData ?: return
        if (imageLoader == null) {
            imageLoader = MediaBrowserTile.configureImageLoader(
                context, data.authToken, data.haBaseUrl, data.maApiToken, data.maApiUrl
            )
        }
        grid?.setImageLoader(imageLoader)
        if (sidebar?.getSelectedCategory() == MediaBrowserDataSource.Category.RECENTS) {
            if (data.sections.isNotEmpty()) {
                grid?.updateSections(data.sections, "No recently played items")
            } else {
                grid?.updateItems(data.items)
            }
        }
    }

    private fun showSearchPopup() {
        val activity = context as? android.app.Activity ?: return
        MusicSearchPopup(
            activity = activity,
            dataSource = dataSource,
            imageLoader = imageLoader,
            forceDarkColors = forceDarkColors,
            sizeMultiplier = sizeMultiplier,
            activeProfile = cachedActiveProfile,
            onItemTapped = { item -> onItemTapped(item) }
        ).show()
    }

    /** Update with recently played data (called by renderer, same as old strip). */
    fun update(data: RecentlyPlayedData) {
        Log.i(TAG, "update: ${data.items.size} items, grid=${grid != null}, sidebar=${sidebar != null}, " +
            "expanded=$isExpanded, viewVis=${view.visibility}, viewW=${view.width}x${view.height}")
        cachedRecentData = data
        hasData = data.items.isNotEmpty()

        // Configure image loader from auth data
        imageLoader = MediaBrowserTile.configureImageLoader(
            context, data.authToken, data.haBaseUrl, data.maApiToken, data.maApiUrl
        )

        // Cache recents in data source for instant switching
        dataSource?.setCachedRecents(data.items)

        // Update the grid if expanded and viewing recents (don't overwrite profile view)
        if (isExpanded && sidebar?.isProfileSelected() != true) {
            grid?.setImageLoader(imageLoader)
            if (sidebar?.getSelectedCategory() == MediaBrowserDataSource.Category.RECENTS) {
                if (data.sections.isNotEmpty()) {
                    grid?.updateSections(data.sections, "No recently played items")
                } else {
                    grid?.updateItems(data.items)
                }
            }
        }

        view.visibility = if (hasData) View.VISIBLE else View.GONE
    }

    fun toggle() {
        // Panel is always expanded — no-op, kept for interface compatibility
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

    val heightDp: Int
        get() = EXPANDED_HEIGHT_DP

    // ── Private: Build Views ────────────────────────────────────────

    private fun buildExpandedView() {
        expandedView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(EXPANDED_HEIGHT_DP)
            )
        }

        // Main content: sidebar (30%) + grid (70%)
        val contentRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        sidebar = MediaBrowserSidebar(
            context = context,
            forceDarkColors = forceDarkColors,
            sizeMultiplier = sizeMultiplier,
            onCategorySelected = { category -> onCategoryChanged(category) },
            onProfileTapped = { showProfileGrid() },
            onSearchTapped = { showSearchPopup() }
        )
        contentRow.addView(sidebar!!.view)

        // Vertical divider between sidebar and grid
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(borderColor())
            contentRow.addView(this)
        }

        grid = MediaBrowserGrid(
            context = context,
            forceDarkColors = forceDarkColors,
            sizeMultiplier = sizeMultiplier,
            onItemTapped = { item -> onGridItemTapped(item) },
            onSearchChanged = { query -> onSearchQuery(query) }
        )

        // Wrap grid with a drill header that becomes visible when the user
        // drills into a playlist, album, or genre. Wrapper takes the 0.70f
        // horizontal weight previously owned by the grid itself (sidebar holds
        // 0.30f), so the 30/70 split is preserved.
        val gridWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.70f)
        }
        gridWrapper.addView(buildDrillHeader())
        gridWrapper.addView(grid!!.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        contentRow.addView(gridWrapper)

        expandedView!!.addView(contentRow)
        view.addView(expandedView)
    }

    // ── Drill header ────────────────────────────────────────────────

    private fun buildDrillHeader(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        val arrowSize = dp(24)
        val artSize = dp(48)

        // Back arrow
        bar.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(arrowSize, arrowSize).apply { marginEnd = dp(10) }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_back_arrow)
            setColorFilter(if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
                else MusicPlayerStyles.textPrimary(context))
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { drillBackAction?.invoke() }
        })

        // Artwork
        val art = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(artSize, artSize).apply { marginEnd = dp(10) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(0xFF2A2A2A.toInt())
            }
        }
        bar.addView(art)

        // Name
        val label = TextView(context).apply {
            setTextColor(if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
                else MusicPlayerStyles.textPrimary(context))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * sizeMultiplier)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(label)

        // ▶ Play All
        val playAll = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(14), dp(6))
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(MusicPlayerStyles.ACCENT_COLOR)
            }
            setOnClickListener {
                drillPlayAllItem?.let { item -> onItemTapped(item) }
            }
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_playback)
                setColorFilter(android.graphics.Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            addView(TextView(context).apply {
                text = "Play All"
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * sizeMultiplier)
                typeface = Typeface.DEFAULT_BOLD
            })
        }
        bar.addView(playAll)

        drillHeader = bar
        drillHeaderArt = art
        drillHeaderLabel = label
        drillHeaderPlayAll = playAll
        return bar
    }

    private fun showDrillHeader(
        name: String,
        imageUrl: String?,
        playAllItem: RecentlyPlayedItem?,
        onBack: () -> Unit
    ) {
        drillHeaderLabel?.text = name
        drillBackAction = onBack
        drillPlayAllItem = playAllItem

        val art = drillHeaderArt
        if (art != null) {
            if (!imageUrl.isNullOrBlank()) {
                art.visibility = View.VISIBLE
                val loader = imageLoader ?: coil.ImageLoader(context)
                val req = coil.request.ImageRequest.Builder(context)
                    .data(imageUrl)
                    .target(art)
                    .placeholder(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
                    .error(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
                    .crossfade(150)
                    .build()
                loader.enqueue(req)
            } else {
                art.visibility = View.GONE
                art.setImageDrawable(null)
            }
        }
        drillHeaderPlayAll?.visibility = if (playAllItem != null) View.VISIBLE else View.GONE
        drillHeader?.visibility = View.VISIBLE
    }

    private fun hideDrillHeader() {
        drillHeader?.visibility = View.GONE
        drillBackAction = null
        drillPlayAllItem = null
        drillHeaderArt?.setImageDrawable(null)
    }

    // ── Private: Tap Handling ────────────────────────────────────────

    /**
     * Route item taps: playlists/albums/genres drill into their contents;
     * everything else plays.
     */
    private fun onGridItemTapped(item: RecentlyPlayedItem) {
        when (item.mediaType) {
            "genre" -> browseGenre(item.name)
            "playlist" -> browsePlaylist(item)
            "album" -> browseAlbum(item)
            else -> onItemTapped(item)
        }
    }

    /** Browse into a genre: show its tracks in the grid. */
    private fun browseGenre(genreName: String) {
        val ds = dataSource ?: return
        // Capture the sidebar-selected category at drill-in time. Back must
        // restore the view the user came from (e.g. Recents → drilled into
        // an album → back returns to Recents, not to Albums).
        val sourceCategory = sidebar?.getSelectedCategory()
            ?: MediaBrowserDataSource.Category.GENRES
        grid?.showLoading()
        showDrillHeader(
            name = "Genres  ›  $genreName",
            imageUrl = null,
            playAllItem = null,
            onBack = { onCategoryChanged(sourceCategory) }
        )
        Thread {
            try {
                val items = ds.fetchGenreContents(genreName)
                mainHandler.post {
                    grid?.setImageLoader(imageLoader)
                    grid?.updateItems(items, "No tracks found in \"$genreName\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to browse genre '$genreName': ${e.message}")
                mainHandler.post {
                    grid?.updateItems(emptyList(), "Failed to load genre")
                }
            }
        }.start()
    }

    /** Browse into a playlist: show its tracks in the grid with art + Play All. */
    private fun browsePlaylist(playlistItem: RecentlyPlayedItem) {
        val ds = dataSource ?: return
        val sourceCategory = sidebar?.getSelectedCategory()
            ?: MediaBrowserDataSource.Category.PLAYLISTS
        grid?.showLoading()
        showDrillHeader(
            name = playlistItem.name,
            imageUrl = playlistItem.imageUrl,
            playAllItem = playlistItem,
            onBack = { onCategoryChanged(sourceCategory) }
        )
        Thread {
            try {
                val items = ds.fetchPlaylistContents(playlistItem.uri)
                mainHandler.post {
                    grid?.setImageLoader(imageLoader)
                    grid?.updateItems(items, "No tracks found in \"${playlistItem.name}\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to browse playlist '${playlistItem.name}': ${e.message}")
                mainHandler.post {
                    grid?.updateItems(emptyList(), "Failed to load playlist")
                }
            }
        }.start()
    }

    /** Browse into an album: show its tracks in the grid with art + Play All. */
    private fun browseAlbum(albumItem: RecentlyPlayedItem) {
        val ds = dataSource ?: return
        val sourceCategory = sidebar?.getSelectedCategory()
            ?: MediaBrowserDataSource.Category.ALBUMS
        grid?.showLoading()
        showDrillHeader(
            name = albumItem.name,
            imageUrl = albumItem.imageUrl,
            playAllItem = albumItem,
            onBack = { onCategoryChanged(sourceCategory) }
        )
        Thread {
            try {
                val items = ds.fetchAlbumContents(albumItem.uri)
                mainHandler.post {
                    grid?.setImageLoader(imageLoader)
                    grid?.updateItems(items, "No tracks found in \"${albumItem.name}\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to browse album '${albumItem.name}': ${e.message}")
                mainHandler.post {
                    grid?.updateItems(emptyList(), "Failed to load album")
                }
            }
        }.start()
    }

    // ── Private: Data Loading ───────────────────────────────────────

    private fun onCategoryChanged(category: MediaBrowserDataSource.Category) {
        // Always clear any drill header when navigating to a new top-level
        // category — the user is "leaving" the drilled view.
        hideDrillHeader()
        grid?.clearSearch()
        grid?.setImageLoader(imageLoader)

        if (category == MediaBrowserDataSource.Category.RECENTS) {
            val data = cachedRecentData
            if (data != null && data.sections.isNotEmpty()) {
                grid?.updateSections(data.sections, "No recently played items")
            } else {
                grid?.updateItems(data?.items ?: emptyList(), "No recently played items")
            }
            return
        }
        grid?.showLoading()
        fetchCategoryAsync(category)
    }

    private fun onSearchQuery(query: String) {
        if (query.isEmpty()) {
            // Restore current category
            val category = sidebar?.getSelectedCategory() ?: MediaBrowserDataSource.Category.RECENTS
            onCategoryChanged(category)
            return
        }
        val ds = dataSource ?: return
        Thread {
            try {
                val results = ds.search(query)
                mainHandler.post {
                    grid?.setImageLoader(imageLoader)
                    grid?.updateItems(results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}")
            }
        }.start()
    }

    private fun emptyMessageFor(category: MediaBrowserDataSource.Category): String = when (category) {
        MediaBrowserDataSource.Category.RECENTS -> "No recently played items"
        MediaBrowserDataSource.Category.RADIO -> "No radio stations configured"
        MediaBrowserDataSource.Category.GENRES -> "No genres found"
        else -> "No ${category.displayName.lowercase()} found"
    }

    private fun fetchCategoryAsync(category: MediaBrowserDataSource.Category) {
        val ds = dataSource ?: run {
            Log.w(TAG, "fetchCategoryAsync: dataSource is null for ${category.displayName}")
            mainHandler.post { grid?.updateItems(emptyList(), "Not connected") }
            return
        }

        Thread {
            try {
                Log.i(TAG, "Fetching ${category.displayName}...")
                val items = ds.fetchCategory(category)
                Log.i(TAG, "Fetched ${items.size} items for ${category.displayName}")
                mainHandler.post {
                    if (sidebar?.getSelectedCategory() == category) {
                        grid?.setImageLoader(imageLoader)
                        grid?.updateItems(items, emptyMessageFor(category))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch ${category.displayName}: ${e.message}")
                mainHandler.post {
                    if (sidebar?.getSelectedCategory() == category) {
                        grid?.updateItems(emptyList(), "Failed to load ${category.displayName.lowercase()}")
                    }
                }
            }
        }.start()
    }

    private fun borderColor(): Int = if (forceDarkColors) MusicPlayerStyles.BORDER_COLOR_DARK
        else if (MusicPlayerStyles.isDarkMode(context)) MusicPlayerStyles.BORDER_COLOR_DARK
        else MusicPlayerStyles.BORDER_COLOR_LIGHT
    private fun scaledSp(sp: Float): Float = sp * sizeMultiplier
    private fun dp(dp: Int): Int = (MusicPlayerStyles.dpToPx(context, dp) * sizeMultiplier).toInt()
}
