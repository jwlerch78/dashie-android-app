package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.graphics.Color
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
 * Left panel of the split-view maximized player.
 * "Media Selector" title with close button, then sidebar + grid directly embedded
 * (not using MediaBrowserPanel, to avoid container/visibility/height issues in split-view).
 */
class MusicExplorerPanel(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val sizeMultiplier: Float = 1.5f,
    private val onPlayItem: (String) -> Unit,
    private val onClose: (() -> Unit)? = null,
    private val onProfileTapped: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "MusicExplorerPanel"
    }

    private var sidebar: MediaBrowserSidebar? = null
    private var grid: MediaBrowserGrid? = null
    private var dataSource: MediaBrowserDataSource? = null
    private var imageLoader: coil.ImageLoader? = null
    private var cachedRecentData: RecentlyPlayedData? = null
    private var cachedActiveProfile: MusicProfile? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Drill header shown above the grid when the user has navigated into a
     * playlist, album, or genre. Layout: [← back] [art?] [name] [▶ Play All?].
     * Hidden when at the top-level category view.
     */
    private var drillHeader: LinearLayout? = null
    private var drillHeaderArt: ImageView? = null
    private var drillHeaderLabel: TextView? = null
    private var drillHeaderPlayAll: View? = null
    private var drillBackAction: (() -> Unit)? = null
    private var drillPlayAllUri: String? = null

    /** Music profile manager for inline profile switching */
    var musicProfileManager: MusicProfileManager? = null
    /** Called when user switches profile inline */
    var onProfileSwitched: ((MusicProfile?) -> Unit)? = null

    private fun dp(dp: Int) = MusicPlayerStyles.dpToPx(context, dp)

    fun build(): FrameLayout {
        val panel = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Title row: "Media Selector" + red X close
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(16), dp(12))
        }
        titleRow.addView(TextView(context).apply {
            text = "Media Selector"
            setTextColor(MusicPlayerStyles.TEXT_PRIMARY_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val closeSize = dp(28)
        titleRow.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(closeSize, closeSize).apply {
                marginStart = dp(12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(MusicPlayerStyles.ACCENT_COLOR)
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            isClickable = true; isFocusable = true
            setOnClickListener { onClose?.invoke() }
        })
        content.addView(titleRow)

        // Sidebar (30%) + Grid (70%) — embedded directly, no MediaBrowserPanel wrapper
        val browserRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        sidebar = MediaBrowserSidebar(
            context = context,
            forceDarkColors = true,
            sizeMultiplier = sizeMultiplier,
            onCategorySelected = { category -> onCategoryChanged(category) },
            onProfileTapped = { showProfileGrid() },
            onSearchTapped = {
                val act = context as? android.app.Activity ?: return@MediaBrowserSidebar
                MusicSearchPopup(
                    activity = act, dataSource = dataSource, imageLoader = imageLoader,
                    forceDarkColors = true, sizeMultiplier = 1.5f,
                    activeProfile = cachedActiveProfile,
                    onItemTapped = { item -> onPlayItem(item.uri) }
                ).show()
            }
        )
        browserRow.addView(sidebar!!.view)

        // Vertical divider
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(MusicPlayerStyles.BORDER_COLOR_DARK)
            browserRow.addView(this)
        }

        grid = MediaBrowserGrid(
            context = context,
            forceDarkColors = true,
            sizeMultiplier = sizeMultiplier,
            onItemTapped = { item ->
                when (item.mediaType) {
                    "genre" -> browseGenre(item.name)
                    "playlist" -> browsePlaylist(item)
                    "album" -> browseAlbum(item)
                    else -> onPlayItem(item.uri)
                }
            },
            onSearchChanged = { } // Search disabled for now
        )

        // Wrap grid with a breadcrumb header that becomes visible when the
        // user drills into a playlist or genre. Layout: [breadcrumb][grid].
        // Wrapper takes the 0.70f horizontal weight previously owned by the
        // grid itself (sidebar holds 0.30f), so the 30/70 split is preserved.
        val gridWrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.70f)
        }
        gridWrapper.addView(buildDrillHeader())
        gridWrapper.addView(grid!!.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        browserRow.addView(gridWrapper)

        content.addView(browserRow)
        panel.addView(content)

        return panel
    }

    /** Set the active music profile shown at the top of the sidebar */
    fun setActiveProfile(profile: MusicProfile?) {
        cachedActiveProfile = profile
        sidebar?.setActiveProfile(profile)
    }

    fun setDataSource(ds: MediaBrowserDataSource) {
        dataSource = ds
    }

    fun update(items: List<RecentlyPlayedData>) {
        val firstData = items.firstOrNull() ?: return
        cachedRecentData = firstData

        imageLoader = MediaBrowserTile.configureImageLoader(
            context, firstData.authToken, firstData.haBaseUrl, firstData.maApiToken, firstData.maApiUrl
        )
        dataSource?.setCachedRecents(firstData.items)

        // Update grid if viewing recents
        if (sidebar?.getSelectedCategory() == MediaBrowserDataSource.Category.RECENTS) {
            grid?.setImageLoader(imageLoader)
            if (firstData.sections.isNotEmpty()) {
                grid?.updateSections(firstData.sections, "No recently played items")
            } else {
                grid?.updateItems(firstData.items)
            }
        }
    }

    // ── Profile switching (inline in grid) ───────────────────────────

    private fun showProfileGrid() {
        val mgr = musicProfileManager ?: run {
            onProfileTapped?.invoke()  // Fallback to external handler
            return
        }
        val profiles = mgr.getAllSwitchableProfiles()
        if (profiles.isEmpty()) return
        val activeId = mgr.getActiveProfile()?.profileId
        grid?.showCustomView(buildProfileList(profiles, activeId))
    }

    private fun buildProfileList(profiles: List<MusicProfile>, activeProfileId: String?): android.view.View {
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
            val card = buildProfileCard(profile, isActive)
            card.setOnClickListener {
                val pid = profile.profileId ?: return@setOnClickListener
                val mgr = musicProfileManager ?: return@setOnClickListener
                mgr.setActiveProfileId(pid)
                val newProfile = mgr.getActiveProfile()
                sidebar?.setActiveProfile(newProfile)
                cachedActiveProfile = newProfile
                onProfileSwitched?.invoke(newProfile)
                // Swap API client and refresh recents
                grid?.showLoading()
                Thread {
                    val userClient = mgr.getUserScopedClient()
                    if (userClient != null) dataSource?.swapApiClient(userClient)
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

    private fun buildProfileCard(profile: MusicProfile, isActive: Boolean): android.widget.LinearLayout {
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
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (isActive) 0x33FF9500.toInt() else 0xFF2A2A2A.toInt())
                cornerRadius = dp(10).toFloat()
                if (isActive) setStroke(dp(2), MusicPlayerStyles.ACCENT_COLOR)
            }
            // Avatar
            val avatarView = android.widget.ImageView(context).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize).apply { marginEnd = dp(14) }
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            }
            addView(avatarView)
            if (profile.avatarUrl != null) {
                val request = coil.request.ImageRequest.Builder(context)
                    .data(profile.avatarUrl).target(avatarView)
                    .transformations(coil.transform.CircleCropTransformation())
                    .listener(onError = { _, _ -> setAvatarFallback(avatarView, profile, avatarSize) })
                    .build()
                coil.ImageLoader(context).enqueue(request)
            } else {
                setAvatarFallback(avatarView, profile, avatarSize)
            }
            // Name + provider
            val textColumn = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textColumn.addView(android.widget.TextView(context).apply {
                text = profile.memberName
                setTextColor(MusicPlayerStyles.TEXT_PRIMARY_DARK)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                maxLines = 1
            })
            if (profile.providerLabel != null) {
                textColumn.addView(android.widget.TextView(context).apply {
                    text = profile.providerLabel
                    setTextColor(MusicPlayerStyles.TEXT_SECONDARY_DARK)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    maxLines = 1
                })
            }
            addView(textColumn)
            // Provider icon
            val iconRes = MusicProviderIcons.iconRes(profile.providerLabel)
            if (iconRes != 0) {
                addView(android.widget.ImageView(context).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(providerIconSize, providerIconSize).apply { marginStart = dp(8) }
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

    // ── Drill header ─────────────────────────────────────────────────

    /**
     * Build the drill header shown above the grid when the user has navigated
     * into a playlist, album, or genre. Layout (left to right):
     *   [← back arrow]  [art?]  [name]                          [▶ Play All?]
     * Back arrow is always shown when the header is visible. Art and Play All
     * are shown only when applicable (i.e. for playlist/album, not for genre).
     */
    private fun buildDrillHeader(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
        }
        val arrowSize = dp(24)
        val artSize = dp(48)

        // Back arrow — clickable region just around the arrow itself
        bar.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(arrowSize, arrowSize).apply { marginEnd = dp(10) }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_back_arrow)
            setColorFilter(MusicPlayerStyles.TEXT_PRIMARY_DARK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { drillBackAction?.invoke() }
        })

        // Artwork (hidden by default — shown by show*WithArt variants)
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

        // Name — takes available space
        val label = TextView(context).apply {
            setTextColor(MusicPlayerStyles.TEXT_PRIMARY_DARK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(label)

        // ▶ Play All button (hidden by default; shown for playlist/album)
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
                drillPlayAllUri?.let { uri -> onPlayItem(uri) }
            }
            // Play icon
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(6) }
                setImageResource(com.dashieapp.Dashie.R.drawable.ic_playback)
                setColorFilter(android.graphics.Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
            })
            addView(TextView(context).apply {
                text = "Play All"
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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

    /**
     * Show the drill header. If imageUrl is provided, artwork is shown and
     * loaded asynchronously via Coil. If playAllUri is provided, the Play All
     * button is shown (genre drill skips both since neither applies).
     */
    private fun showDrillHeader(
        name: String,
        imageUrl: String?,
        playAllUri: String?,
        onBack: () -> Unit
    ) {
        drillHeaderLabel?.text = name
        drillBackAction = onBack
        drillPlayAllUri = playAllUri

        val art = drillHeaderArt
        if (art != null) {
            if (!imageUrl.isNullOrBlank()) {
                Log.d(TAG, "Drill header: loading art from $imageUrl")
                art.visibility = View.VISIBLE
                // Fall back to a fresh ImageLoader if the panel's loader hasn't
                // been configured yet — matches MediaBrowserTile's pattern.
                val loader = imageLoader ?: coil.ImageLoader(context)
                val req = coil.request.ImageRequest.Builder(context)
                    .data(imageUrl)
                    .target(art)
                    .placeholder(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
                    .error(com.dashieapp.Dashie.R.drawable.ic_type_playlist)
                    .crossfade(150)
                    .listener(
                        onError = { _, result ->
                            Log.w(TAG, "Drill header: art load FAILED for $imageUrl: ${result.throwable.message}")
                        },
                        onSuccess = { _, _ ->
                            Log.d(TAG, "Drill header: art loaded OK")
                        }
                    )
                    .build()
                loader.enqueue(req)
            } else {
                art.visibility = View.GONE
                art.setImageDrawable(null)
            }
        }
        drillHeaderPlayAll?.visibility = if (playAllUri != null) View.VISIBLE else View.GONE
        drillHeader?.visibility = View.VISIBLE
    }

    private fun hideDrillHeader() {
        drillHeader?.visibility = View.GONE
        drillBackAction = null
        drillPlayAllUri = null
        drillHeaderArt?.setImageDrawable(null)
    }

    /** Browse into a genre: show its tracks in the grid. */
    private fun browseGenre(genreName: String) {
        val ds = dataSource ?: return
        // Capture the SIDEBAR-selected category at drill-in time. Back must
        // restore the view the user came from (e.g. Recents → drilled into
        // an album → back returns to Recents, not to Albums).
        val sourceCategory = sidebar?.getSelectedCategory()
            ?: MediaBrowserDataSource.Category.GENRES
        grid?.showLoading()
        // Genre has no parent URI to "play all" and no single artwork — just
        // show the name + back arrow.
        showDrillHeader(
            name = "Genres  ›  $genreName",
            imageUrl = null,
            playAllUri = null,
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
            playAllUri = playlistItem.uri,
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
            playAllUri = albumItem.uri,
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

    // ── Category handling (same logic as MediaBrowserPanel) ─────────

    private fun onCategoryChanged(category: MediaBrowserDataSource.Category) {
        // Always clear any drill header when navigating to a new top-level
        // category — the user is "leaving" the drilled view.
        hideDrillHeader()
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
        if (category == MediaBrowserDataSource.Category.FAVORITES) {
            grid?.showLoading()
            fetchFavoritesAsync()
            return
        }
        grid?.showLoading()
        fetchCategoryAsync(category)
    }

    /**
     * Fetch favorites as sections (Songs / Albums / Artists / Playlists / Radio).
     * Background-threaded mirror of the RECENTS sectioned-render path.
     */
    private fun fetchFavoritesAsync() {
        val ds = dataSource ?: run {
            mainHandler.post { grid?.updateItems(emptyList(), "Not connected") }
            return
        }
        Thread {
            try {
                val sections = ds.fetchFavoritesCategorized()
                mainHandler.post {
                    if (sidebar?.getSelectedCategory() != MediaBrowserDataSource.Category.FAVORITES) return@post
                    grid?.setImageLoader(imageLoader)
                    if (sections.isNotEmpty()) {
                        grid?.updateSections(sections, "No favorites yet")
                    } else {
                        grid?.updateItems(emptyList(), "No favorites yet")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch favorites: ${e.message}")
                mainHandler.post {
                    if (sidebar?.getSelectedCategory() == MediaBrowserDataSource.Category.FAVORITES) {
                        grid?.updateItems(emptyList(), "Failed to load favorites")
                    }
                }
            }
        }.start()
    }

    private fun emptyMessageFor(category: MediaBrowserDataSource.Category): String = when (category) {
        MediaBrowserDataSource.Category.RECENTS -> "No recently played items"
        MediaBrowserDataSource.Category.FAVORITES -> "No favorites yet"
        MediaBrowserDataSource.Category.RADIO -> "No radio stations configured"
        MediaBrowserDataSource.Category.GENRES -> "No genres found"
        else -> "No ${category.displayName.lowercase()} found"
    }

    private fun fetchCategoryAsync(category: MediaBrowserDataSource.Category) {
        val ds = dataSource ?: run {
            mainHandler.post { grid?.updateItems(emptyList(), "Not connected") }
            return
        }

        Thread {
            try {
                val items = ds.fetchCategory(category)
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
}
