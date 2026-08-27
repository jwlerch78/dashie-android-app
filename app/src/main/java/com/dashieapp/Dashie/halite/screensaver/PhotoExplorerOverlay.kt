package com.dashieapp.Dashie.halite.screensaver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Full-screen photo explorer overlay.
 *
 * Opened from the photo preview thumbnail on wake. Displays the current photo
 * full-screen with swipe left/right navigation through the entire cached photo list.
 *
 * For Unsplash photos, shows a tappable attribution bar at the bottom:
 *   "Photo by [Name] · Unsplash"
 * where [Name] links to the photographer's profile and "Unsplash" links to unsplash.com,
 * both with UTM parameters (required by Unsplash API guidelines).
 *
 * For other photo sources, shows date/location metadata via PhotoSlideshowView's built-in ribbon.
 */
class PhotoExplorerOverlay(
    private val context: Context,
    private val prefs: ScreensaverPreferences
) {

    companion object {
        private const val TAG = "PhotoExplorer"
        private const val FADE_DURATION_MS = 250L
        private const val SWIPE_THRESHOLD = 100
        private const val SWIPE_VELOCITY_THRESHOLD = 200
        private const val CLOSE_BUTTON_SIZE_DP = 36
        private const val CLOSE_BUTTON_MARGIN_DP = 16
    }

    private var overlay: FrameLayout? = null
    private var rootContainer: ViewGroup? = null
    private var slideshowView: PhotoSlideshowView? = null
    private var slideshow: PhotoSlideshow? = null
    private var attributionBar: LinearLayout? = null
    private var isDismissed = false

    /**
     * Show the explorer overlay starting at the given photo.
     *
     * @param fetchNextPhoto Optional streaming fetcher (from the source that
     *   backs the widget slideshow). When supplied, the explorer's internal
     *   slideshow uses it to fetch fresh photos when the cached list has
     *   entries whose files have been evicted (e.g. Immich rolling cache).
     */
    fun show(
        startPhoto: PhotoItem,
        allPhotos: List<PhotoItem>,
        root: ViewGroup,
        fetchNextPhoto: (suspend () -> PhotoItem?)? = null
    ) {
        if (isDismissed || allPhotos.isEmpty()) return

        rootContainer = root
        val density = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels

        // Full-screen overlay
        val frame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            isClickable = true  // Consume touches so dashboard behind isn't interactive
        }

        // PhotoSlideshowView for displaying photos
        val view = PhotoSlideshowView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // Full-screen explorer always letterboxes with a blurred backdrop,
            // matching the embedded photo widget and the screensaver. Without
            // this, landscape photos viewed in the full-screen overlay
            // CENTER_CROP and the user loses the top/bottom of every photo.
            fitLandscapePhotos = true
        }
        view.configure(
            showMetadata = true,  // Show date/location for all photos; hidden when Unsplash bar is active
            showClock = false
        )
        slideshowView = view
        frame.addView(view)

        // Attribution bar at bottom (for Unsplash photos)
        val attrBar = createAttributionBar(density)
        frame.addView(attrBar)
        attributionBar = attrBar

        // Close button (X) — top-right
        val closeBtnSize = (CLOSE_BUTTON_SIZE_DP * density).toInt()
        val closeBtnMargin = (CLOSE_BUTTON_MARGIN_DP * density).toInt()
        val closeBtn = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(closeBtnSize, closeBtnSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = closeBtnMargin
                marginEnd = closeBtnMargin
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.WHITE)
            alpha = 0.7f
            setOnClickListener { dismiss() }
        }
        frame.addView(closeBtn)

        // Set up slideshow controller (no auto-advance in explorer mode)
        val ss = PhotoSlideshow(view, prefs)
        slideshow = ss

        // Wire streaming fetcher so next/previous can download fresh photos
        // when the cached list points to evicted files (Immich rolling cache).
        if (fetchNextPhoto != null) {
            ss.fetchNextPhoto = fetchNextPhoto
        }

        // Ensure the starting photo is in the list (it's guaranteed valid —
        // it's the currently displayed photo). Filter out photos whose files
        // no longer exist so the user doesn't hit a blank first frame.
        val validPhotos = allPhotos.filter { photo ->
            photo.id == startPhoto.id ||
                photo.uri.path?.let { java.io.File(it).exists() } ?: true
        }
        val listToLoad = if (validPhotos.any { it.id == startPhoto.id }) validPhotos
                         else listOf(startPhoto) + validPhotos
        val startIndex = listToLoad.indexOfFirst { it.id == startPhoto.id }.coerceAtLeast(0)

        // Load photos and navigate to the start photo
        ss.loadPhotos(listToLoad, shuffle = false, startAt = startIndex)

        // Set up swipe gesture detection
        setupGestures(frame)

        // Show initial attribution
        updateAttribution(startPhoto)

        // Listen for photo changes to update attribution
        ss.onPhotoDisplayed = { photoId ->
            val photo = allPhotos.find { it.id == photoId }
            if (photo != null) {
                updateAttribution(photo)
            }
        }

        overlay = frame
        root.addView(frame)

        // Fade in
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { frame.alpha = it.animatedValue as Float }
            start()
        }

        Log.i(TAG, "Opened photo explorer (${allPhotos.size} photos, starting at ${startPhoto.filename})")
    }

    /**
     * Create the attribution bar at the bottom of the screen.
     * Uses a gradient background that fades from transparent to semi-transparent black.
     */
    private fun createAttributionBar(density: Float): LinearLayout {
        val bar = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
            }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = (24 * density).toInt()
            val padV = (16 * density).toInt()
            setPadding(padH, padV * 2, padH, padV)

            // Gradient background: transparent at top → semi-transparent black at bottom
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.argb(180, 0, 0, 0))
            )
            visibility = View.GONE
        }

        val textView = TextView(context).apply {
            tag = "attribution_text"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            highlightColor = Color.TRANSPARENT
        }
        bar.addView(textView)

        return bar
    }

    /**
     * Update the attribution bar for the current photo.
     * Shows linked attribution for Unsplash, hides for other sources.
     */
    private fun updateAttribution(photo: PhotoItem) {
        val bar = attributionBar ?: return
        val textView = bar.findViewWithTag<TextView>("attribution_text") ?: return

        val photographer = photo.metadata?.get("photographer") as? String
        val photographerUrl = photo.metadata?.get("photographer_url") as? String

        if (photo.source == PhotoSourceType.UNSPLASH && photographer != null) {
            // Unsplash: show our custom attribution bar, hide built-in metadata ribbon
            slideshowView?.configure(showMetadata = false, showClock = false)

            val fullText = "Photo by $photographer · Unsplash"
            val hyperlinksEnabled = prefs.unsplashArtistHyperlinks

            if (hyperlinksEnabled) {
                val spannable = SpannableString(fullText)

                // Make photographer name clickable
                val nameStart = "Photo by ".length
                val nameEnd = nameStart + photographer.length
                if (photographerUrl != null) {
                    spannable.setSpan(
                        object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                openUrl(photographerUrl)
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                ds.color = Color.WHITE
                                ds.isUnderlineText = true
                                ds.typeface = Typeface.DEFAULT_BOLD
                            }
                        },
                        nameStart, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // Make "Unsplash" clickable
                val unsplashStart = fullText.indexOf("Unsplash")
                val unsplashEnd = unsplashStart + "Unsplash".length
                spannable.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            openUrl("https://unsplash.com/?utm_source=dashie&utm_medium=referral")
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = Color.WHITE
                            ds.isUnderlineText = true
                            ds.typeface = Typeface.DEFAULT_BOLD
                        }
                    },
                    unsplashStart, unsplashEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                textView.movementMethod = LinkMovementMethod.getInstance()
                textView.text = spannable
            } else {
                // Plain text credits — no clickable links
                textView.movementMethod = null
                textView.text = fullText
            }

            bar.visibility = View.VISIBLE
        } else {
            // Non-Unsplash: hide our bar, let built-in metadata ribbon show date/location
            bar.visibility = View.GONE
            slideshowView?.configure(showMetadata = true, showClock = false)
        }
    }

    /**
     * Open a URL in an in-app WebView overlay (keeps user inside Dashie).
     */
    private fun openUrl(url: String) {
        val root = rootContainer ?: return
        Log.i(TAG, "Opening URL in-app: $url")

        val density = context.resources.displayMetrics.density
        val closeBtnSize = (CLOSE_BUTTON_SIZE_DP * density).toInt()
        val closeBtnMargin = (CLOSE_BUTTON_MARGIN_DP * density).toInt()

        val webFrame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
            isClickable = true
        }

        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            loadUrl(url)
        }
        webFrame.addView(webView)

        // Close button to return to photo explorer
        val closeBtn = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(closeBtnSize, closeBtnSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = closeBtnMargin
                marginEnd = closeBtnMargin
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.DKGRAY)
            setBackgroundColor(Color.argb(180, 255, 255, 255))
            elevation = 4f * density
            setOnClickListener {
                webView.destroy()
                root.removeView(webFrame)
            }
        }
        webFrame.addView(closeBtn)

        root.addView(webFrame)
    }

    /**
     * Set up swipe and tap gesture detection.
     * - Swipe left/right: navigate photos
     * - Single tap: dismiss explorer
     */
    private fun setupGestures(container: FrameLayout) {
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Don't dismiss if tap was on the attribution bar
                val bar = attributionBar
                if (bar != null && bar.visibility == View.VISIBLE) {
                    val barTop = bar.top
                    if (e.y >= barTop) return false  // Let LinkMovementMethod handle it
                }
                dismiss()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                if (Math.abs(diffX) > Math.abs(e2.y - e1.y) &&
                    Math.abs(diffX) > SWIPE_THRESHOLD &&
                    Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD
                ) {
                    if (diffX < 0) slideshow?.next() else slideshow?.previous()
                    return true
                }
                return false
            }
        })

        container.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    /** Advance the explorer's internal slideshow forward. */
    fun next() { slideshow?.next() }

    /** Step the explorer's internal slideshow backward. */
    fun previous() { slideshow?.previous() }

    /**
     * Dismiss the explorer with a fade-out animation.
     */
    fun dismiss() {
        if (isDismissed) return
        isDismissed = true

        val frame = overlay ?: return
        val root = rootContainer ?: return

        slideshow?.stop()
        slideshow = null

        ValueAnimator.ofFloat(frame.alpha, 0f).apply {
            duration = FADE_DURATION_MS
            interpolator = AccelerateInterpolator()
            addUpdateListener { frame.alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.removeView(frame)
                    overlay = null
                    rootContainer = null
                    slideshowView = null
                    attributionBar = null
                }
            })
            start()
        }

        Log.i(TAG, "Closed photo explorer")
    }
}
