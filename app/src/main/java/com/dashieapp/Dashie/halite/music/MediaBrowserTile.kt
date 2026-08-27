package com.dashieapp.Dashie.halite.music

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared tile builder for media browser items (recently played strip + expanded grid).
 * Each tile: 64x64dp artwork with rounded corners, optional type badge, name + artist labels.
 */
object MediaBrowserTile {

    const val WRAPPER_WIDTH_DP = 104
    const val SIZE_DP = 64
    const val CORNER_RADIUS_DP = 8
    const val GAP_DP = 12
    const val NAME_SIZE_SP = 11f
    const val ARTIST_SIZE_SP = 10f

    fun createTile(
        context: Context,
        item: RecentlyPlayedItem,
        imageLoader: coil.ImageLoader?,
        forceDarkColors: Boolean = false,
        sizeMultiplier: Float = 1f,
        onTapped: (RecentlyPlayedItem) -> Unit
    ): View {
        val dp = { v: Int -> (MusicPlayerStyles.dpToPx(context, v) * sizeMultiplier).toInt() }
        val scaledSp = { sp: Float -> sp * sizeMultiplier }
        val textPrimary = if (forceDarkColors) MusicPlayerStyles.TEXT_PRIMARY_DARK
            else MusicPlayerStyles.textPrimary(context)
        val textSecondary = if (forceDarkColors) MusicPlayerStyles.TEXT_SECONDARY_DARK
            else MusicPlayerStyles.textSecondary(context)

        val wrapperWidth = dp(WRAPPER_WIDTH_DP)
        val tileSize = dp(SIZE_DP)

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(wrapperWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            clipChildren = false
            clipToPadding = false
            setOnClickListener { onTapped(item) }
        }

        // Outer frame allows badge to overflow the clipped art
        val artWrapper = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(tileSize, tileSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            clipChildren = false
            clipToPadding = false
        }

        val artFrame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            clipToOutline = true
            outlineProvider = RoundedOutlineProvider(dp(CORNER_RADIUS_DP).toFloat())
        }

        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF333333.toInt())
        }
        artFrame.addView(imageView)
        artWrapper.addView(artFrame)

        // Type badge icon (centered on right edge)
        val badgeIconRes = getTypeIconRes(item.mediaType)
        if (badgeIconRes != 0) {
            val badgeSize = dp(24)
            val badge = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    marginEnd = dp(-20)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4).toFloat()
                    setColor(0xDDCCCCCC.toInt())
                }
                elevation = dp(2).toFloat()
            }
            val icon = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setImageResource(badgeIconRes)
                setColorFilter(android.graphics.Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            badge.addView(icon)
            artWrapper.addView(badge)
        }

        wrapper.addView(artWrapper)

        // Load artwork
        loadTileImage(context, imageView, item.imageUrl, imageLoader, textSecondary, sizeMultiplier)

        // Name label
        TextView(context).apply {
            text = item.name
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(NAME_SIZE_SP))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(3), 0, 0)
            wrapper.addView(this)
        }

        // Artist label
        if (!item.artist.isNullOrBlank()) {
            TextView(context).apply {
                text = item.artist
                setTextColor(textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(ARTIST_SIZE_SP))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                wrapper.addView(this)
            }
        }

        return wrapper
    }

    /**
     * Configure a Coil ImageLoader with auth headers for HA and/or MA image proxies.
     * Returns null if no auth is needed (caller should use the default Coil loader).
     */
    fun configureImageLoader(
        context: Context,
        authToken: String?,
        haBaseUrl: String?,
        maApiToken: String? = null,
        maApiUrl: String? = null
    ): coil.ImageLoader? {
        val hasHaAuth = !authToken.isNullOrBlank() && !haBaseUrl.isNullOrBlank()
        val hasMaAuth = !maApiToken.isNullOrBlank() && !maApiUrl.isNullOrBlank()
        if (!hasHaAuth && !hasMaAuth) return null

        val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                if (hasMaAuth && url.startsWith(maApiUrl!!)) {
                    chain.proceed(request.newBuilder()
                        .header("Authorization", "Bearer $maApiToken")
                        .build())
                } else if (hasHaAuth && url.startsWith(haBaseUrl!!)) {
                    chain.proceed(request.newBuilder()
                        .header("Authorization", "Bearer $authToken")
                        .build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()
        return coil.ImageLoader.Builder(context)
            .okHttpClient(client)
            .build()
    }

    private fun loadTileImage(
        context: Context,
        imageView: ImageView,
        url: String?,
        imageLoader: coil.ImageLoader?,
        textSecondary: Int,
        sizeMultiplier: Float
    ) {
        if (url.isNullOrBlank()) {
            imageView.setImageResource(android.R.drawable.ic_media_play)
            imageView.setColorFilter(textSecondary)
            imageView.scaleType = ImageView.ScaleType.CENTER
            return
        }

        imageView.clearColorFilter()
        val loader = imageLoader ?: coil.ImageLoader(context)
        val dp = { v: Int -> (MusicPlayerStyles.dpToPx(context, v) * sizeMultiplier).toInt() }
        val request = coil.request.ImageRequest.Builder(context)
            .data(url)
            .target(imageView)
            .placeholder(android.R.drawable.ic_media_play)
            .error(android.R.drawable.ic_media_play)
            .crossfade(150)
            .build()
        loader.enqueue(request)
    }

    fun getTypeIconRes(mediaType: String): Int = when (mediaType.lowercase()) {
        "album" -> com.dashieapp.Dashie.R.drawable.ic_type_album
        "artist" -> com.dashieapp.Dashie.R.drawable.ic_type_artist
        "playlist" -> com.dashieapp.Dashie.R.drawable.ic_type_playlist
        "track", "song" -> com.dashieapp.Dashie.R.drawable.ic_type_song
        else -> 0
    }

    private class RoundedOutlineProvider(private val radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
}
