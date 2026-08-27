package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Build
import android.provider.MediaStore
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Custom view for displaying photo slideshow with smooth transitions.
 *
 * Features:
 * - Crossfade transitions between photos
 * - Blurred background for portrait photos (zoomed version behind)
 * - Metadata ribbon showing date and location
 * - EXIF data extraction for date/location
 */
class PhotoSlideshowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PhotoSlideshowView"
        private const val FADE_DURATION_MS = 1000L
        private const val BLUR_RADIUS = 25f  // Max blur radius for RenderScript

        // US state abbreviations
        private val US_STATE_ABBREVIATIONS = mapOf(
            "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR",
            "California" to "CA", "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE",
            "Florida" to "FL", "Georgia" to "GA", "Hawaii" to "HI", "Idaho" to "ID",
            "Illinois" to "IL", "Indiana" to "IN", "Iowa" to "IA", "Kansas" to "KS",
            "Kentucky" to "KY", "Louisiana" to "LA", "Maine" to "ME", "Maryland" to "MD",
            "Massachusetts" to "MA", "Michigan" to "MI", "Minnesota" to "MN", "Mississippi" to "MS",
            "Missouri" to "MO", "Montana" to "MT", "Nebraska" to "NE", "Nevada" to "NV",
            "New Hampshire" to "NH", "New Jersey" to "NJ", "New Mexico" to "NM", "New York" to "NY",
            "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH", "Oklahoma" to "OK",
            "Oregon" to "OR", "Pennsylvania" to "PA", "Rhode Island" to "RI", "South Carolina" to "SC",
            "South Dakota" to "SD", "Tennessee" to "TN", "Texas" to "TX", "Utah" to "UT",
            "Vermont" to "VT", "Virginia" to "VA", "Washington" to "WA", "West Virginia" to "WV",
            "Wisconsin" to "WI", "Wyoming" to "WY", "District of Columbia" to "DC"
        )

        // Canadian province abbreviations
        private val CA_PROVINCE_ABBREVIATIONS = mapOf(
            "Alberta" to "AB", "British Columbia" to "BC", "Manitoba" to "MB",
            "New Brunswick" to "NB", "Newfoundland and Labrador" to "NL",
            "Northwest Territories" to "NT", "Nova Scotia" to "NS", "Nunavut" to "NU",
            "Ontario" to "ON", "Prince Edward Island" to "PE", "Quebec" to "QC",
            "Saskatchewan" to "SK", "Yukon" to "YT"
        )
    }

    // Data class for photo with extracted metadata
    data class PhotoWithMetadata(
        val bitmap: Bitmap,
        val isPortrait: Boolean,
        val dateTaken: Date?,
        val location: String?,
        val exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL
    )

    // OkHttpClient for Nominatim geocoding (Fire tablets don't have Google Play Services)
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // Background ImageViews (blurred, zoomed for portrait photos)
    private val bgImageView1: ImageView
    private val bgImageView2: ImageView
    private var activeBgImageView: ImageView
    private var inactiveBgImageView: ImageView

    // Foreground ImageViews for actual photo display
    private val fgImageView1: ImageView
    private val fgImageView2: ImageView
    private var activeFgImageView: ImageView
    private var inactiveFgImageView: ImageView

    // Metadata ribbon
    private val metadataRibbon: LinearLayout
    private val dateTextView: TextView
    private val locationTextView: TextView

    // Empty state text
    private val emptyTextView: TextView

    // Dashie Cloud empty state (icon + "Click to add photos") — used when
    // source is supabase and there's nothing to show yet. Owns its own
    // container so it can be hidden independently from the plain-text empty
    // state used by other sources.
    private val dashieCloudEmptyContainer: LinearLayout
    private val dashieCloudEmptyIcon: ImageView
    private val dashieCloudEmptyText: TextView

    // Track current photo for debugging
    private var currentPhotoId: String? = null

    // RenderScript for blur (nullable for API < 17 fallback)
    private var renderScript: RenderScript? = null

    // Metadata extraction job - runs independently of photo display
    private var metadataJob: kotlinx.coroutines.Job? = null
    private val metadataScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    // Guard against animation race conditions — incremented each display cycle
    private var displayGeneration = 0

    // Settings
    private var showMetadataEnabled = true

    private var metadataTextSizeSp = 38f
    /** When true, landscape photos use FIT_CENTER (fit within bounds) instead of CENTER_CROP (fill + crop). */
    var fitLandscapePhotos = false
    private var showClockEnabled = false
    private var use24HourClock = false

    // Clock overlay
    private val clockContainer: LinearLayout
    private val clockTextView: TextView
    private val clockDateTextView: TextView
    private val clockWeatherTextView: TextView
    private var clockHandler: android.os.Handler? = null
    private var clockRunnable: Runnable? = null
    private var showDateEnabled = false
    private var dateFormatDmy = false
    private var weatherMode = "disabled"
    private var clockOnRight = true
    private var isVideoFeedActive = false
    private var isForecastCardVisible = false

    // UI scale factor for consistent visual sizing across different screens
    // Problem: Android's dp system scales with density, but we want elements to look
    // the same SIZE on screen regardless of density (e.g., a clock should be readable
    // from similar distances on a 15" tablet and a TV viewed from a couch)
    //
    // Solution: Scale based on physical screen short side (pixels / density gives inches * 160)
    // This approximates physical size. Target is ~1080 "physical pixels" at density 1.0
    //
    // Device examples:
    // - ONN Stick: 1080px / 2.0 density = 540 → scale = 0.5 (smaller, good for TV)
    // - Mio 15": 1080px / 1.0 density = 1080 → scale = 1.0 (baseline)
    // - Fire Tablet: 800px / 1.33 density = 601 → scale = 0.56 (smaller screen)
    private val uiScaleFactor: Float by lazy {
        val dm = resources.displayMetrics
        val shortSidePx = minOf(dm.widthPixels, dm.heightPixels)
        // Normalize by density to get consistent physical sizing
        val normalizedShortSide = shortSidePx / dm.density
        val baseNormalizedSize = 1080f  // Target: 1080px at density 1.0
        val scale = normalizedShortSide / baseNormalizedSize
        Log.d(TAG, "UI scale factor: $scale (screen: ${dm.widthPixels}x${dm.heightPixels}, " +
                "density: ${dm.density}, normalizedShortSide: $normalizedShortSide)")
        // Clamp between 0.5 and 1.2 to avoid extreme sizes
        scale.coerceIn(0.5f, 1.2f)
    }

    init {
        setBackgroundColor(Color.BLACK)

        // Try to initialize RenderScript for blur
        try {
            renderScript = RenderScript.create(context)
        } catch (e: Exception) {
            Log.w(TAG, "RenderScript not available, blur effect will be disabled")
        }

        // Create background ImageViews (for blurred effect)
        bgImageView1 = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = INVISIBLE
        }
        addView(bgImageView1)

        bgImageView2 = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = INVISIBLE
        }
        addView(bgImageView2)

        activeBgImageView = bgImageView1
        inactiveBgImageView = bgImageView2

        // Create foreground ImageViews (actual photo)
        fgImageView1 = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER  // Fit entirely, letterbox if needed
            visibility = INVISIBLE
        }
        addView(fgImageView1)

        fgImageView2 = ImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = INVISIBLE
        }
        addView(fgImageView2)

        activeFgImageView = fgImageView1
        inactiveFgImageView = fgImageView2

        // Create metadata ribbon at bottom - padding scaled with display
        // Bottom margin ensures text isn't cut off by system bars or screen overscan
        metadataRibbon = LinearLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(scaledDpToPx(14), scaledDpToPx(6), scaledDpToPx(14), scaledDpToPx(10))

            // Semi-transparent gradient background
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#80000000"), Color.parseColor("#00000000"))
            )
            visibility = GONE
        }
        addView(metadataRibbon)

        // Date text (left side) - scaled based on display resolution
        dateTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.WHITE)
            textSize = 38f * uiScaleFactor  // Base 38sp, scaled
            gravity = Gravity.START
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        metadataRibbon.addView(dateTextView)

        // Location text (right side) - scaled based on display resolution
        locationTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            setTextColor(Color.WHITE)
            textSize = 38f * uiScaleFactor  // Base 38sp, scaled
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        metadataRibbon.addView(locationTextView)

        // Empty state text - scaled based on display resolution
        // Base size increased 30% from 16sp to 21sp for better readability
        emptyTextView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            setTextColor(Color.WHITE)
            textSize = 21f * uiScaleFactor  // Base 21sp, scaled
            text = "No photos found\n\nCopy photos to:\n/Pictures/Dashie/"
            gravity = Gravity.CENTER
            visibility = GONE
        }
        addView(emptyTextView)

        // Dashie Cloud empty state — icon + "Click to add photos". Replaces
        // the plain-text empty state for the supabase source so a freshly
        // signed-in user gets a friendlier prompt instead of "Not signed in
        // — Dashie Cloud requires login" or "No photos in Dashie Cloud".
        dashieCloudEmptyContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER
            visibility = GONE
        }
        dashieCloudEmptyIcon = ImageView(context).apply {
            val sizePx = scaledDpToPx(64)
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = scaledDpToPx(12)
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_photos)
        }
        dashieCloudEmptyContainer.addView(dashieCloudEmptyIcon)
        dashieCloudEmptyText = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setTextColor(Color.WHITE)
            textSize = 21f * uiScaleFactor
            text = "Click to add photos"
            gravity = Gravity.CENTER
        }
        dashieCloudEmptyContainer.addView(dashieCloudEmptyText)
        addView(dashieCloudEmptyContainer)

        // Clock + date container
        clockContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, scaledDpToPx(96), scaledDpToPx(36), 0)
            }
            visibility = GONE
        }

        clockTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(Color.WHITE)
            textSize = 62f * uiScaleFactor
            setShadowLayer(5f * uiScaleFactor, 3f * uiScaleFactor, 3f * uiScaleFactor, Color.BLACK)
        }
        clockContainer.addView(clockTextView)

        clockDateTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 37f * uiScaleFactor
            setShadowLayer(5f * uiScaleFactor, 3f * uiScaleFactor, 3f * uiScaleFactor, Color.BLACK)
            visibility = GONE
        }
        clockContainer.addView(clockDateTextView)

        clockWeatherTextView = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setTextColor(0xCCFFFFFF.toInt())  // Match date text opacity
            textSize = 37f * uiScaleFactor
            setShadowLayer(5f * uiScaleFactor, 3f * uiScaleFactor, 3f * uiScaleFactor, Color.BLACK)
            visibility = GONE
        }
        clockContainer.addView(clockWeatherTextView)

        addView(clockContainer)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    /**
     * Convert DP to pixels with UI scale factor applied.
     * Use this for positions/margins that should scale with the display.
     */
    private fun scaledDpToPx(dp: Int): Int {
        return (dpToPx(dp) * uiScaleFactor).toInt()
    }

    /**
     * Recycle the bitmap currently held by an ImageView.
     * This immediately frees the bitmap memory instead of waiting for GC.
     * Critical for memory-constrained devices when cycling cached photos.
     *
     * @param imageView The ImageView to recycle the bitmap from
     * @param excludeBitmap Optional bitmap to NOT recycle (e.g., the incoming bitmap)
     */
    private fun recycleImageViewBitmap(imageView: ImageView, excludeBitmap: Bitmap? = null) {
        try {
            val drawable = imageView.drawable
            if (drawable is BitmapDrawable) {
                val bitmap = drawable.bitmap
                if (bitmap != null && bitmap != excludeBitmap && !bitmap.isRecycled) {
                    imageView.setImageBitmap(null)
                    bitmap.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error recycling bitmap: ${e.message}")
        }
    }

    /**
     * Narrow-portrait mode: constrain the foreground photos (and metadata
     * ribbon) to the TOP of the screen, reserving [bottomPanelHeightPx] at
     * the bottom for the music card / camera strip. Blur backgrounds stay
     * full-screen (they extend behind the panel, same pattern as the
     * horizontal sidebar case). Pass 0 to restore full-height photos.
     */
    fun setBottomPanelInset(bottomPanelHeightPx: Int) {
        val screenHeight = resources.displayMetrics.heightPixels
        val active = bottomPanelHeightPx > 0
        val contentHeight = if (active) {
            (screenHeight - bottomPanelHeightPx).coerceAtLeast(0)
        } else {
            LayoutParams.MATCH_PARENT
        }

        // Blur backgrounds: always full-screen
        for (iv in listOf(bgImageView1, bgImageView2)) {
            val lp = iv.layoutParams as? LayoutParams ?: continue
            lp.width = LayoutParams.MATCH_PARENT
            lp.height = LayoutParams.MATCH_PARENT
            lp.topMargin = 0
            lp.bottomMargin = 0
            lp.gravity = android.view.Gravity.NO_GRAVITY
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.layoutParams = lp
        }

        // Foreground photos: constrain to top portion, top-aligned.
        for (iv in listOf(fgImageView1, fgImageView2)) {
            val lp = iv.layoutParams as? LayoutParams ?: continue
            lp.width = LayoutParams.MATCH_PARENT
            lp.height = contentHeight
            lp.topMargin = 0
            lp.bottomMargin = 0
            lp.gravity = if (active) android.view.Gravity.TOP else android.view.Gravity.NO_GRAVITY
            iv.layoutParams = lp
        }

        // Metadata ribbon: pin to the bottom of the photo region (just above the panel).
        val ribbonLp = metadataRibbon.layoutParams as? LayoutParams ?: return
        ribbonLp.width = LayoutParams.MATCH_PARENT
        ribbonLp.bottomMargin = if (active) bottomPanelHeightPx else 0
        ribbonLp.gravity = android.view.Gravity.BOTTOM
        metadataRibbon.layoutParams = ribbonLp
    }

    /**
     * Toggle video feed mode — blur backgrounds stay full-width (extend behind sidebar).
     * Foreground photos and metadata are constrained to the left portion.
     */
    fun setVideoFeedMode(active: Boolean, feedAreaWidth: Int = 0) {
        val screenWidth = resources.displayMetrics.widthPixels
        val contentWidth = if (active) screenWidth - feedAreaWidth else LayoutParams.MATCH_PARENT

        // Blur backgrounds: always full-width
        for (iv in listOf(bgImageView1, bgImageView2)) {
            val lp = iv.layoutParams as? LayoutParams ?: continue
            lp.width = LayoutParams.MATCH_PARENT
            lp.height = LayoutParams.MATCH_PARENT
            lp.rightMargin = 0
            lp.leftMargin = 0
            lp.gravity = android.view.Gravity.NO_GRAVITY
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.layoutParams = lp
        }

        // Foreground photos: constrain to left portion via explicit width
        for (iv in listOf(fgImageView1, fgImageView2)) {
            val lp = iv.layoutParams as? LayoutParams ?: continue
            lp.width = contentWidth
            lp.rightMargin = 0
            lp.gravity = if (active) android.view.Gravity.START else android.view.Gravity.NO_GRAVITY
            iv.layoutParams = lp
        }

        // Metadata ribbon: constrain to left portion
        val ribbonLp = metadataRibbon.layoutParams as? LayoutParams ?: return
        ribbonLp.width = contentWidth
        ribbonLp.rightMargin = 0
        if (active) {
            ribbonLp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
        } else {
            ribbonLp.gravity = android.view.Gravity.BOTTOM
        }
        metadataRibbon.layoutParams = ribbonLp

        // Track state and reposition clock to left when sidebar active
        isVideoFeedActive = active
        if (clockContainer.visibility == VISIBLE) {
            val params = clockContainer.layoutParams as? LayoutParams ?: return
            val position = if ((params.gravity and Gravity.TOP) != 0) "top" else "bottom"
            applyClockContainerPosition(params, position)
            clockContainer.layoutParams = params
        }
    }

    /**
     * Notify the slideshow that the forecast card overlay is visible/hidden.
     * Used to avoid positioning the clock on top of the forecast card.
     */
    /**
     * Re-apply themed text colors to the empty-state placeholders.
     * The "Click to add photos" and generic empty text were hardcoded to white,
     * which went invisible against the now-themed light backgrounds. Called by
     * PhotoWidgetController.applyTheme so the placeholder text flips with the
     * theme alongside the card frame. Date/location ribbon text intentionally
     * stays white — they overlay photos and need to read against any image.
     */
    fun refreshThemedColors(isDark: Boolean) {
        val color = com.dashieapp.Dashie.halite.theming.NativeThemeManager
            .getTextPrimaryOr(if (isDark) Color.WHITE else 0xFF1A1A1A.toInt())
        emptyTextView.setTextColor(color)
        dashieCloudEmptyText.setTextColor(color)
    }

    fun setForecastCardVisible(visible: Boolean) {
        isForecastCardVisible = visible
        if (clockContainer.visibility == VISIBLE) {
            val params = clockContainer.layoutParams as? LayoutParams ?: return
            val position = if ((params.gravity and Gravity.TOP) != 0) "top" else "bottom"
            applyClockContainerPosition(params, position)
            clockContainer.layoutParams = params
        }
    }

    /**
     * Set the metadata text scale (relative to base 38sp).
     * When scale < 1.0, applies frosted glass widget style (semi-transparent background).
     * When scale >= 1.0, restores the full-screen screensaver gradient style.
     */
    fun setMetadataScale(scale: Float) {
        metadataTextSizeSp = 38f * scale
        val textSize = metadataTextSizeSp * uiScaleFactor
        dateTextView.textSize = textSize
        locationTextView.textSize = textSize

        if (scale < 1.0f) {
            // Frosted glass widget style — compact with rounded background
            metadataRibbon.background = GradientDrawable().apply {
                setColor(Color.parseColor("#66808080"))
                cornerRadius = dpToPx(4).toFloat()
            }
            val lp = metadataRibbon.layoutParams as? LayoutParams
            if (lp != null) {
                lp.setMargins(dpToPx(5), dpToPx(5), dpToPx(5), dpToPx(5))
                metadataRibbon.layoutParams = lp
            }
            metadataRibbon.setPadding(dpToPx(9), dpToPx(6), dpToPx(9), dpToPx(6))
            dateTextView.setTextColor(Color.WHITE)
            dateTextView.setShadowLayer(3f, 1f, 1f, Color.BLACK)
            locationTextView.setTextColor(Color.WHITE)
            locationTextView.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        } else {
            // Restore full-screen screensaver gradient style
            metadataRibbon.background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#80000000"), Color.parseColor("#00000000"))
            )
            val lp = metadataRibbon.layoutParams as? LayoutParams
            if (lp != null) {
                lp.setMargins(0, 0, 0, 0)
                metadataRibbon.layoutParams = lp
            }
            metadataRibbon.setPadding(scaledDpToPx(14), scaledDpToPx(6), scaledDpToPx(14), scaledDpToPx(10))
            dateTextView.setTextColor(Color.WHITE)
            dateTextView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            locationTextView.setTextColor(Color.WHITE)
            locationTextView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        Log.d(TAG, "Metadata scale set to $scale → textSize=${textSize}sp, style=${if (scale < 1.0f) "frosted-glass" else "gradient"}")
    }


    /**
     * Configure display settings.
     */
    fun configure(
        showMetadata: Boolean,
        showClock: Boolean,
        use24Hour: Boolean = false,
        clockPosition: String = "top",
        clockFontSize: Int = 40,
        showDate: Boolean = false,
        weatherMode: String = "disabled",
        useDmyDateFormat: Boolean = false
    ) {
        showMetadataEnabled = showMetadata
        showClockEnabled = showClock
        showDateEnabled = showDate
        this.weatherMode = weatherMode
        use24HourClock = use24Hour
        dateFormatDmy = useDmyDateFormat

        // Apply clock font size
        clockTextView.textSize = clockFontSize.toFloat() * uiScaleFactor

        // Show/hide date (60% of clock size)
        if (showDate) {
            clockDateTextView.textSize = (clockFontSize * 0.60f) * uiScaleFactor
            clockDateTextView.visibility = VISIBLE
        } else {
            clockDateTextView.visibility = GONE
        }

        // Weather text (65% of clock size, matching ScreenDimmer)
        if (weatherMode == "current") {
            clockWeatherTextView.textSize = (clockFontSize * 0.65f) * uiScaleFactor
            clockWeatherTextView.visibility = VISIBLE
        } else {
            clockWeatherTextView.visibility = GONE
        }

        // Apply clock position: top/bottom + right (default) side
        val params = clockContainer.layoutParams as? LayoutParams
        if (params != null) {
            applyClockContainerPosition(params, clockPosition)
            clockContainer.layoutParams = params
        }

        if (showClock) {
            startClock()
        } else {
            stopClock()
        }
    }

    /**
     * Sync the clock side with ScreenDimmer's flip state.
     * Called when the slideshow is created after a flip has already occurred.
     */
    fun setClockSide(onRight: Boolean) {
        if (clockOnRight != onRight) {
            clockOnRight = onRight
            val params = clockContainer.layoutParams as? LayoutParams ?: return
            applyClockContainerPosition(params, if ((params.gravity and Gravity.TOP) != 0) "top" else "bottom")
            clockContainer.layoutParams = params
        }
    }

    /**
     * Flip the clock to the opposite side for anti-burn-in.
     */
    fun flipClockSide() {
        clockOnRight = !clockOnRight
        val params = clockContainer.layoutParams as? LayoutParams ?: return
        applyClockContainerPosition(params, if ((params.gravity and Gravity.TOP) != 0) "top" else "bottom")
        clockContainer.layoutParams = params
    }

    private fun applyClockContainerPosition(params: LayoutParams, position: String) {
        val margin = scaledDpToPx(48)
        params.setMargins(0, 0, 0, 0)
        params.marginStart = 0
        params.marginEnd = 0

        var isTop = position == "top"
        // When screensaver sidebar (video feeds/music) is active, force clock to left side
        val effectiveRight = if (isVideoFeedActive) false else clockOnRight

        // Avoid overlap with metadata ribbon: the ribbon renders at the
        // bottom of the screen whenever a photo with metadata is loaded.
        // Even if the current photo has no metadata, the ribbon appears
        // as soon as one with data loads — so keep the clock at the top
        // any time the user has metadata enabled.
        if (showMetadataEnabled && !isTop) {
            isTop = true
        }

        // Avoid overlap with forecast card: if both would be on the same side at top,
        // move clock to bottom so they don't stack. Skipped when metadata is enabled
        // since we just forced top above; the forecast card vs metadata trade-off
        // favors keeping clock readable above the (always-visible) photo strip.
        if (!showMetadataEnabled && isForecastCardVisible && isTop) {
            val weatherOnLeft = clockOnRight  // forecast uses logical clockOnRight
            val clockOnLeft = !effectiveRight
            if (clockOnLeft && weatherOnLeft) {
                isTop = false  // Both top-left — move clock to bottom
            } else if (!clockOnLeft && !weatherOnLeft) {
                isTop = false  // Both top-right — move clock to bottom
            }
        }

        val vertGravity = if (isTop) Gravity.TOP else Gravity.BOTTOM
        val horizGravity = if (effectiveRight) Gravity.END else Gravity.START

        if (isTop) params.topMargin = margin else params.bottomMargin = margin
        if (effectiveRight) params.marginEnd = margin else params.marginStart = margin

        params.gravity = vertGravity or horizGravity

        // Align text to match side
        val textGravity = if (effectiveRight) Gravity.END else Gravity.START
        clockTextView.gravity = textGravity
        clockDateTextView.gravity = textGravity
        clockWeatherTextView.gravity = textGravity
    }

    /**
     * Start the clock display.
     */
    private fun startClock() {
        if (clockHandler != null) return

        clockHandler = android.os.Handler(android.os.Looper.getMainLooper())
        clockRunnable = object : Runnable {
            override fun run() {
                updateClock()
                clockHandler?.postDelayed(this, 1000)
            }
        }
        updateClock()
        clockContainer.visibility = VISIBLE
        clockTextView.visibility = VISIBLE
        clockRunnable?.let { clockHandler?.post(it) }
    }

    /**
     * Update the inline current weather display (icon + temp).
     */
    fun updateCurrentWeather(text: CharSequence) {
        if (weatherMode == "current") {
            clockWeatherTextView.text = text
            clockWeatherTextView.visibility = VISIBLE
        }
    }

    /**
     * Expose the weather text view so ScreenDimmer can read its text size for icon scaling.
     */
    fun getWeatherTextView(): TextView = clockWeatherTextView

    /**
     * Stop the clock display.
     */
    private fun stopClock() {
        clockRunnable?.let { clockHandler?.removeCallbacks(it) }
        clockHandler = null
        clockRunnable = null
        clockContainer.visibility = GONE
        clockTextView.visibility = GONE
    }

    /**
     * Update the clock display.
     */
    private fun updateClock() {
        val now = Date()
        if (use24HourClock) {
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            clockTextView.text = timeFormat.format(now)
        } else {
            val timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
            val amPmFormat = SimpleDateFormat("a", Locale.getDefault())
            val time = timeFormat.format(now)
            val amPm = amPmFormat.format(now).lowercase()
            clockTextView.text = "$time $amPm"
        }

        if (showDateEnabled && clockDateTextView.visibility == VISIBLE) {
            val datePattern = if (dateFormatDmy) "EEE d MMM" else "EEE MMM d"
            val dateFormat = SimpleDateFormat(datePattern, Locale.getDefault())
            clockDateTextView.text = dateFormat.format(now)
        }
    }

    /**
     * Show photo with fade transition.
     * Returns true if photo was loaded successfully.
     *
     * If the PhotoItem has pre-extracted metadata (from HaMediaPhotoSource), it's used immediately.
     * Otherwise, metadata is extracted asynchronously from EXIF (for local photos).
     */
    suspend fun showPhoto(photo: PhotoItem, transition: TransitionType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Phase 1: Quick bitmap decode
                val quickPhotoData = loadPhotoBitmapOnly(photo.uri)
                if (quickPhotoData != null) {
                    // Check if photo has pre-extracted metadata
                    val preExtractedDate = photo.metadata?.get("dateFormatted") as? String
                    val preExtractedLocation = photo.metadata?.get("location") as? String
                    val hasPreExtractedMetadata = preExtractedDate != null || preExtractedLocation != null

                    // Check for Unsplash attribution metadata
                    val unsplashPhotographer = photo.metadata?.get("photographer") as? String

                    // Display immediately
                    withContext(Dispatchers.Main) {
                        displayPhoto(quickPhotoData, transition)
                        currentPhotoId = photo.id
                        emptyTextView.visibility = GONE

                        if (unsplashPhotographer != null) {
                            // Unsplash photo: show "Photo by X on Unsplash" attribution
                            updateAttributionRibbon(unsplashPhotographer)
                        } else if (hasPreExtractedMetadata) {
                            val dateTaken = (photo.metadata?.get("dateTaken") as? Long)?.let { Date(it) }
                            updateMetadataRibbon(dateTaken, preExtractedLocation)
                            Log.d(TAG, "Using pre-extracted metadata: date=$preExtractedDate, loc=$preExtractedLocation")
                        } else {
                            // Clear metadata ribbon until async extraction completes
                            metadataRibbon.visibility = GONE
                        }
                    }

                    // Phase 2: If no pre-extracted metadata, extract from EXIF async
                    // Skip for Unsplash photos (no EXIF data, attribution already shown)
                    if (!hasPreExtractedMetadata && unsplashPhotographer == null) {
                        val photoId = photo.id
                        val filePath = photo.uri.path
                        metadataJob?.cancel() // Cancel any previous metadata extraction
                        metadataJob = metadataScope.launch {
                            try {
                                val file = if (filePath != null) File(filePath) else null
                                if (file != null && file.exists()) {
                                    val (dateTaken, location, _) = extractExifData(file.absolutePath)
                                    withContext(Dispatchers.Main) {
                                        // Only update if we're still showing this photo
                                        if (currentPhotoId == photoId) {
                                            updateMetadataRibbon(dateTaken, location)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Don't log cancellation exceptions - they're expected
                                if (e !is kotlinx.coroutines.CancellationException) {
                                    Log.w(TAG, "Metadata extraction failed for $photoId: ${e.message}")
                                }
                            }
                        }
                    }

                    true
                } else {
                    Log.w(TAG, "Failed to load bitmap: ${photo.uri}")
                    false
                }
            } catch (e: Exception) {
                // Don't log cancellation exceptions
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error loading photo: ${photo.uri}", e)
                }
                false
            }
        }
    }

    /**
     * Load just the bitmap without metadata extraction.
     * This is fast - just decode the image.
     */
    private fun loadPhotoBitmapOnly(uri: Uri): PhotoWithMetadata? {
        return try {
            val file = File(uri.path ?: return null)
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: ${uri.path}")
                return null
            }
            Log.d(TAG, "Quick loading photo from: ${file.absolutePath}")

            // Get image dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val imageWidth = options.outWidth
            val imageHeight = options.outHeight

            // Calculate sample size
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val sampleSize = calculateInSampleSize(
                imageWidth, imageHeight,
                screenWidth, screenHeight
            )

            // Decode with sample size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                ?: return null

            // Quick EXIF orientation check (this is fast, no network call)
            val orientation = try {
                val exif = ExifInterface(file)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            // Apply rotation
            bitmap = applyExifRotation(bitmap, orientation)

            val isPortrait = bitmap.height > bitmap.width

            PhotoWithMetadata(
                bitmap = bitmap,
                isPortrait = isPortrait,
                dateTaken = null,  // Will be filled in async
                location = null,   // Will be filled in async
                exifOrientation = orientation
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in quick bitmap load", e)
            null
        }
    }

    /**
     * Show empty state message.
     */
    fun showEmptyState(message: String? = null) {
        fgImageView1.visibility = INVISIBLE
        fgImageView2.visibility = INVISIBLE
        bgImageView1.visibility = INVISIBLE
        bgImageView2.visibility = INVISIBLE
        metadataRibbon.visibility = GONE
        dashieCloudEmptyContainer.visibility = GONE
        // Plain-text empty state keeps the original black backdrop so the
        // white text stays readable. Only the Dashie Cloud icon empty state
        // goes transparent.
        setBackgroundColor(Color.BLACK)
        emptyTextView.text = message ?: "No photos found\n\nCopy photos to:\n/Pictures/Dashie/"
        emptyTextView.visibility = VISIBLE
    }

    /**
     * Show the Dashie Cloud empty state (photo icon + "Click to add photos").
     * Used when the source is supabase and either the user isn't signed in
     * yet or there are no photos uploaded — a friendlier hand-off than the
     * generic empty-state text. _isShowingDashieCloudEmpty also flips so the
     * JS photos menu can skip the activated/hint state and jump straight to
     * the Upload bar.
     *
     * Drops the slideshow's solid-black backdrop while showing this state
     * so the cardContainer behind it (white on light theme, #2A2A3E on
     * dark) bleeds through and the empty state matches the surrounding
     * dashboard chrome instead of looking like a black hole on the light
     * theme.
     */
    fun showDashieCloudEmptyState() {
        fgImageView1.visibility = INVISIBLE
        fgImageView2.visibility = INVISIBLE
        bgImageView1.visibility = INVISIBLE
        bgImageView2.visibility = INVISIBLE
        metadataRibbon.visibility = GONE
        emptyTextView.visibility = GONE
        dashieCloudEmptyContainer.visibility = VISIBLE
        setBackgroundColor(Color.TRANSPARENT)
        // Theme-aware caption color: white text would be invisible on the
        // light theme's white cardContainer (which now bleeds through the
        // transparent backdrop). Match the body-text contrast the rest of
        // the dashboard uses — dark on light, light on dark.
        val isDark = com.dashieapp.Dashie.devicecontrols.DarkModeManager
            .getStoredPreference(context) ?: false
        dashieCloudEmptyText.setTextColor(
            if (isDark) Color.WHITE else Color.parseColor("#222222")
        )
        _isShowingDashieCloudEmpty = true
    }

    /**
     * Show loading state message.
     */
    fun showLoading(message: String = "Loading...") {
        fgImageView1.visibility = INVISIBLE
        fgImageView2.visibility = INVISIBLE
        bgImageView1.visibility = INVISIBLE
        bgImageView2.visibility = INVISIBLE
        metadataRibbon.visibility = GONE
        dashieCloudEmptyContainer.visibility = GONE
        setBackgroundColor(Color.BLACK)
        emptyTextView.text = message
        emptyTextView.visibility = VISIBLE
    }

    /**
     * Hide empty state (both flavors). Restores the slideshow's black
     * backdrop so photo transitions don't bleed through to the cardContainer.
     */
    fun hideEmptyState() {
        emptyTextView.visibility = GONE
        dashieCloudEmptyContainer.visibility = GONE
        setBackgroundColor(Color.BLACK)
        _isShowingDashieCloudEmpty = false
    }

    private var _isShowingDashieCloudEmpty = false
    /** True if the Dashie Cloud "Click to add photos" empty state is currently
     *  on screen. JS reads this via PhotoWidgetController to decide whether
     *  to skip the activated/hint state and open the photos menu directly. */
    fun isShowingDashieCloudEmptyState(): Boolean = _isShowingDashieCloudEmpty

    /**
     * Apply EXIF rotation to bitmap.
     */
    private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(-90f)
                matrix.preScale(-1f, 1f)
            }
            else -> return bitmap // No rotation needed
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rotate bitmap: ${e.message}")
            bitmap
        }
    }

    /**
     * Data class for extracted EXIF data.
     */
    private data class ExifData(
        val dateTaken: Date?,
        val location: String?,
        val orientation: Int
    )

    /**
     * Extract date taken, location, and orientation from EXIF data.
     * Uses metadata-extractor library for robust GPS reading.
     * On Android 10+, uses MediaStore.setRequireOriginal() to access unredacted GPS data.
     */
    private fun extractExifData(filePath: String): ExifData {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                Log.w(TAG, "File does not exist: $filePath")
                return ExifData(null, null, ExifInterface.ORIENTATION_NORMAL)
            }

            // First, get orientation using AndroidX ExifInterface (more reliable for orientation)
            val orientation = try {
                val exif = ExifInterface(file)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                ).also {
                    if (it != ExifInterface.ORIENTATION_NORMAL && it != ExifInterface.ORIENTATION_UNDEFINED) {
                        Log.d(TAG, "EXIF orientation for ${file.name}: $it")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read EXIF orientation: ${e.message}")
                ExifInterface.ORIENTATION_NORMAL
            }

            // For Android 10+, need to query MediaStore for content:// URI then use setRequireOriginal
            val inputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // Query MediaStore to get the content:// URI for this file
                    val contentUri = getContentUriForFile(file)
                    if (contentUri != null) {
                        // Request original (unredacted) content
                        val originalUri = MediaStore.setRequireOriginal(contentUri)
                        Log.d(TAG, "Using setRequireOriginal with content URI for: ${file.name}")
                        context.contentResolver.openInputStream(originalUri)
                    } else {
                        Log.d(TAG, "No content URI found, using direct file for: ${file.name}")
                        FileInputStream(file)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "setRequireOriginal failed: ${e.message}, falling back to direct file")
                    FileInputStream(file)
                }
            } else {
                FileInputStream(file)
            }

            // Use metadata-extractor library for robust EXIF reading
            val metadata = inputStream?.use { stream ->
                ImageMetadataReader.readMetadata(stream)
            }

            if (metadata == null) {
                Log.w(TAG, "Failed to read metadata for: ${file.name}")
                return ExifData(null, null, orientation)
            }

            // Extract date from EXIF SubIFD directory
            val exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val dateTaken = exifDir?.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)

            // Extract GPS from GPS directory
            val gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
            val location = if (gpsDir != null) {
                val geoLocation = gpsDir.geoLocation
                if (geoLocation != null && !geoLocation.isZero) {
                    val lat = geoLocation.latitude
                    val lon = geoLocation.longitude
                    // Log line KEPT on purpose: it distinguishes "this photo had coordinates and
                    // we chose not to resolve them" from "there were no coordinates" — see the
                    // REVERSE GEOCODING REMOVED note.
                    Log.d(TAG, "EXIF GPS - File: ${file.name}, lat=$lat, lon=$lon (not resolved — geocoding removed)")
                    null
                } else {
                    Log.d(TAG, "EXIF GPS - File: ${file.name}, no GPS or 0,0")
                    null
                }
            } else {
                Log.d(TAG, "EXIF GPS - File: ${file.name}, no GPS directory")
                null
            }

            if (dateTaken != null || location != null) {
                Log.d(TAG, "EXIF extracted - Date: $dateTaken, Location: $location")
            }

            ExifData(dateTaken, location, orientation)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF data: ${e.message}")
            ExifData(null, null, ExifInterface.ORIENTATION_NORMAL)
        }
    }

    /**
     * Get the content:// URI for a file from MediaStore.
     * Required for setRequireOriginal() to work on Android 10+.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private fun getContentUriForFile(file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null
        }

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        val selectionArgs = arrayOf(file.absolutePath)

        return try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    android.content.ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get content URI for ${file.name}: ${e.message}")
            null
        }
    }

    /* ── REVERSE GEOCODING REMOVED, product decision 2026-08-21 ───────────────────────────────
     * This view used to resolve a local photo's EXIF lat/lon into a town name for the caption,
     * via the Android Geocoder and then nominatim.openstreetmap.org as the Fire-tablet fallback.
     *
     * 📌 THIS was the local-folder path — the one the decision is about. It is reached from
     * extractExifData() when a photo arrives with no pre-extracted metadata, i.e. the offline
     * local-folder source, whose users chose it precisely because they wanted nothing leaving the
     * house. Deleted rather than gated: "we can just remove geocoding from that source."
     *
     * 🔴 DO NOT RE-ADD, in either provider form. The Android Geocoder is not a local alternative
     * — it resolves through a Google backend on most devices, so restoring "just the Android one"
     * would quietly reinstate the very egress this removes, under a different vendor.
     *
     * The EXIF read is deliberately KEPT (date, and the GPS log line that distinguishes "no
     * coordinates" from "chose not to resolve"). Captions now show a location only when the photo
     * SOURCE supplied one. Removed with this: geocodeCache, tryAndroidGeocoder,
     * tryNominatimGeocoder, abbreviateState and its state/province tables.
     */

    /**
     * Calculate sample size for downsampling large images.
     */
    private fun calculateInSampleSize(
        srcWidth: Int, srcHeight: Int,
        reqWidth: Int, reqHeight: Int
    ): Int {
        var inSampleSize = 1

        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * Create a blurred version of the bitmap for background.
     * Optimized for memory: scales to ~10% of screen size since heavy blur
     * makes fine detail invisible anyway. This reduces memory by ~90%.
     */
    private fun createBlurredBitmap(source: Bitmap): Bitmap? {
        val rs = renderScript ?: return null

        return try {
            // Scale down aggressively for blurred backgrounds
            // For a heavily blurred background (radius 25), we don't need high resolution
            // Target ~10% of screen dimensions - blur hides all detail anyway
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels
            val targetSize = maxOf(screenWidth, screenHeight) / 10  // ~10% of screen

            // Calculate scale to fit target size while maintaining aspect ratio
            val maxDim = maxOf(source.width, source.height)
            val scale = (targetSize.toFloat() / maxDim).coerceAtMost(0.25f)

            val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
            var scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

            // RenderScript blur requires ARGB_8888 format - convert if necessary
            if (scaledBitmap.config != Bitmap.Config.ARGB_8888) {
                val converted = scaledBitmap.copy(Bitmap.Config.ARGB_8888, false)
                if (converted != null) {
                    if (scaledBitmap != source) {
                        scaledBitmap.recycle()
                    }
                    scaledBitmap = converted
                } else {
                    Log.w(TAG, "Failed to convert bitmap to ARGB_8888")
                    return null
                }
            }

            val input = Allocation.createFromBitmap(rs, scaledBitmap)
            val output = Allocation.createTyped(rs, input.type)
            val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))

            script.setRadius(BLUR_RADIUS)
            script.setInput(input)
            script.forEach(output)

            val blurred = Bitmap.createBitmap(scaledWidth, scaledHeight, Bitmap.Config.ARGB_8888)
            output.copyTo(blurred)

            input.destroy()
            output.destroy()
            script.destroy()
            if (scaledBitmap != source) {
                scaledBitmap.recycle()
            }

            blurred
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create blurred bitmap: ${e.message}")
            null
        }
    }

    /**
     * Display photo with the specified transition.
     */
    private fun displayPhoto(photoData: PhotoWithMetadata, transition: TransitionType) {
        // 🔴 THE INVARIANT: a photo on screen means NO empty state on screen.
        //
        // Enforced HERE, at the one place a photo goes up, rather than by asking every caller to
        // remember hideEmptyState(). It previously had exactly ONE caller in the whole app —
        // PhotoSourceFactory's Dashie-Cloud poll — so the empty state could only be cleared by
        // the single success path that happened to know about it.
        //
        // 🖼️ The reported symptom (staging Samsung, 2026-08-25): a full-bleed Immich photo
        // with metadata working, and the orange "Click to add photos" icon dead-centre ON TOP of
        // it. The route: Dashie Cloud is the source and is empty (or has no JWT) →
        // showDashieCloudEmptyState() → the user switches the source to Immich →
        // PhotoWidgetController.reconfigure() REUSES the same view (`slideshowView ?: return`) and
        // resets no visibility → Immich photos display underneath a container nobody cleared.
        //
        // ⚠️ Not the "cloud lane leaking onto Immich" it looks like: the empty state is correctly
        // scoped to the cloud source. It is a VIEW-level state outliving the source that set it —
        // the same shape as the device-state-outliving-the-account class from row 55.
        if (dashieCloudEmptyContainer.visibility == VISIBLE ||
            emptyTextView.visibility == VISIBLE
        ) {
            Log.w(TAG, "DROP: empty state was still on screen when a photo arrived — clearing it. " +
                    "Something showed an empty state and never took it down (source switch?)")
            hideEmptyState()
        }

        when (transition) {
            TransitionType.NONE -> displayImmediate(photoData)
            TransitionType.FADE -> displayWithFade(photoData)
            TransitionType.SLIDE -> displayWithSlide(photoData)
        }

        // Update metadata ribbon
        updateMetadataRibbon(photoData.dateTaken, photoData.location)
    }

    /**
     * Update the metadata ribbon with date and location.
     */
    private fun updateMetadataRibbon(dateTaken: Date?, location: String?) {
        // Check if metadata display is enabled
        if (!showMetadataEnabled) {
            metadataRibbon.visibility = GONE
            return
        }

        val hasDate = dateTaken != null
        // Defensive: any source that round-trips JSON via Android's optString
        // can leak the literal "null" string when the value was JSON-null.
        // Treat it as missing rather than rendering "null" in the ribbon.
        val hasLocation = !location.isNullOrBlank() && location != "null"

        if (!hasDate && !hasLocation) {
            metadataRibbon.visibility = GONE
            return
        }

        // Restore normal text size (may have been reduced for Unsplash attribution)
        val normalTextSize = metadataTextSizeSp * uiScaleFactor
        dateTextView.textSize = normalTextSize
        locationTextView.textSize = normalTextSize

        // Format date (e.g., "Aug 1, 2025")
        if (hasDate) {
            val formatted = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(dateTaken!!)
            dateTextView.text = formatted
            dateTextView.visibility = VISIBLE
        } else {
            dateTextView.text = ""
            dateTextView.visibility = GONE
        }

        // Show location
        if (hasLocation) {
            locationTextView.text = location
            locationTextView.visibility = VISIBLE
        } else {
            locationTextView.text = ""
            locationTextView.visibility = GONE
        }

        metadataRibbon.visibility = VISIBLE
    }

    /**
     * Update the metadata ribbon with Unsplash attribution.
     * Shows "Photo by [photographer]" on the left and "on Unsplash" on the right.
     * Uses 75% of normal metadata text size for a subtler appearance.
     * Required by Unsplash API guidelines.
     */
    private fun updateAttributionRibbon(photographer: String) {
        if (!showMetadataEnabled) {
            metadataRibbon.visibility = GONE
            return
        }

        // 75% of the *current* metadata size — metadataTextSizeSp tracks
        // the active scale (PhotoWidgetController applies 0.5x for widget
        // mode via setMetadataScale). Hardcoding 28.5sp here ignored that
        // scale, so unsplash photos in widget mode rendered at full size.
        val attributionTextSize = (metadataTextSizeSp * 0.75f) * uiScaleFactor
        dateTextView.text = "Photo by $photographer  |  on Unsplash"
        dateTextView.textSize = attributionTextSize
        dateTextView.visibility = VISIBLE
        locationTextView.visibility = GONE
        metadataRibbon.visibility = VISIBLE
    }

    /**
     * Display photo immediately with no transition.
     */
    private fun displayImmediate(photoData: PhotoWithMetadata) {
        val bitmap = photoData.bitmap

        // Recycle old bitmaps before setting new ones
        recycleImageViewBitmap(inactiveBgImageView)
        recycleImageViewBitmap(inactiveFgImageView, bitmap)
        recycleImageViewBitmap(activeBgImageView)
        recycleImageViewBitmap(activeFgImageView, bitmap)

        // Setup background and scale type based on orientation
        if (photoData.isPortrait) {
            inactiveFgImageView.scaleType = ImageView.ScaleType.FIT_CENTER
            val blurred = createBlurredBitmap(bitmap)
            if (blurred != null) {
                inactiveBgImageView.setImageBitmap(blurred)
                inactiveBgImageView.alpha = 1f
                inactiveBgImageView.visibility = VISIBLE
            } else {
                // Fallback: use original as background
                inactiveBgImageView.setImageBitmap(bitmap)
                inactiveBgImageView.alpha = 0.3f  // Darken since not blurred
                inactiveBgImageView.visibility = VISIBLE
            }
        } else {
            // Landscape: fill + crop for screensaver, fit with blur for widget
            if (fitLandscapePhotos) {
                inactiveFgImageView.scaleType = ImageView.ScaleType.FIT_CENTER
                val blurred = createBlurredBitmap(bitmap)
                if (blurred != null) {
                    inactiveBgImageView.setImageBitmap(blurred)
                    inactiveBgImageView.alpha = 1f
                    inactiveBgImageView.visibility = VISIBLE
                } else {
                    inactiveBgImageView.setImageBitmap(bitmap)
                    inactiveBgImageView.alpha = 0.3f
                    inactiveBgImageView.visibility = VISIBLE
                }
            } else {
                inactiveFgImageView.scaleType = ImageView.ScaleType.CENTER_CROP
                inactiveBgImageView.visibility = INVISIBLE
            }
        }
        activeBgImageView.visibility = INVISIBLE

        // Setup foreground
        inactiveFgImageView.setImageBitmap(bitmap)
        inactiveFgImageView.alpha = 1f
        inactiveFgImageView.visibility = VISIBLE
        activeFgImageView.visibility = INVISIBLE

        // Swap references
        val tempBg = activeBgImageView
        activeBgImageView = inactiveBgImageView
        inactiveBgImageView = tempBg

        val tempFg = activeFgImageView
        activeFgImageView = inactiveFgImageView
        inactiveFgImageView = tempFg
    }

    /**
     * Display photo with crossfade transition.
     */
    private fun displayWithFade(photoData: PhotoWithMetadata) {
        val bitmap = photoData.bitmap
        val gen = ++displayGeneration
        Log.d(TAG, "displayWithFade[gen=$gen]: isPortrait=${photoData.isPortrait}, " +
            "bitmap=${bitmap.width}x${bitmap.height}, fitLandscape=$fitLandscapePhotos")

        // Cancel any in-flight animations to prevent stale withEndAction callbacks
        // from recycling the new photo's bitmap
        inactiveFgImageView.animate().cancel()
        activeFgImageView.animate().cancel()
        inactiveBgImageView.animate().cancel()
        activeBgImageView.animate().cancel()

        // Capture references before swapping
        val incomingBg = inactiveBgImageView
        val outgoingBg = activeBgImageView
        val incomingFg = inactiveFgImageView
        val outgoingFg = activeFgImageView

        // Setup scale type and background based on orientation
        if (photoData.isPortrait) {
            incomingFg.scaleType = ImageView.ScaleType.FIT_CENTER
            val blurred = createBlurredBitmap(bitmap)
            if (blurred != null) {
                Log.d(TAG, "displayWithFade: Using blurred background")
                incomingBg.setImageBitmap(blurred)
                incomingBg.alpha = 0f
                incomingBg.visibility = VISIBLE
                incomingBg.animate()
                    .alpha(1f)
                    .setDuration(FADE_DURATION_MS)
                    .start()
            } else {
                // Blur failed - use darkened original as fallback
                Log.d(TAG, "displayWithFade: Blur failed, using darkened original as background")
                incomingBg.setImageBitmap(bitmap)
                incomingBg.alpha = 0f
                incomingBg.visibility = VISIBLE
                incomingBg.animate()
                    .alpha(0.3f)
                    .setDuration(FADE_DURATION_MS)
                    .start()
            }
        } else {
            // Landscape: fill + crop for screensaver, fit with blur for widget
            if (fitLandscapePhotos) {
                incomingFg.scaleType = ImageView.ScaleType.FIT_CENTER
                val blurred = createBlurredBitmap(bitmap)
                if (blurred != null) {
                    incomingBg.setImageBitmap(blurred)
                    incomingBg.alpha = 0f
                    incomingBg.visibility = VISIBLE
                    incomingBg.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
                } else {
                    incomingBg.setImageBitmap(bitmap)
                    incomingBg.alpha = 0f
                    incomingBg.visibility = VISIBLE
                    incomingBg.animate().alpha(0.3f).setDuration(FADE_DURATION_MS).start()
                }
            } else {
                incomingFg.scaleType = ImageView.ScaleType.CENTER_CROP
                incomingBg.visibility = INVISIBLE
            }
        }

        // Fade out outgoing background
        if (outgoingBg.visibility == VISIBLE) {
            outgoingBg.animate()
                .alpha(0f)
                .setDuration(FADE_DURATION_MS)
                .withEndAction {
                    if (displayGeneration != gen) return@withEndAction // stale callback
                    outgoingBg.visibility = INVISIBLE
                    recycleImageViewBitmap(outgoingBg)
                }
                .start()
        }

        // Setup incoming foreground - this is the main photo and MUST always display
        incomingFg.setImageBitmap(bitmap)
        incomingFg.visibility = VISIBLE
        incomingFg.alpha = 0f
        incomingFg.animate()
            .alpha(1f)
            .setDuration(FADE_DURATION_MS)
            .start()

        // Fade out outgoing foreground
        outgoingFg.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction {
                if (displayGeneration != gen) return@withEndAction // stale callback
                outgoingFg.visibility = INVISIBLE
                recycleImageViewBitmap(outgoingFg, bitmap)
            }
            .start()

        // Swap references
        activeBgImageView = incomingBg
        inactiveBgImageView = outgoingBg
        activeFgImageView = incomingFg
        inactiveFgImageView = outgoingFg
    }

    /**
     * Display photo with slide transition.
     */
    private fun displayWithSlide(photoData: PhotoWithMetadata) {
        val bitmap = photoData.bitmap
        val gen = ++displayGeneration
        Log.d(TAG, "displayWithSlide[gen=$gen]: isPortrait=${photoData.isPortrait}, " +
            "bitmap=${bitmap.width}x${bitmap.height}, fitLandscape=$fitLandscapePhotos")

        // Cancel any in-flight animations to prevent stale withEndAction callbacks
        inactiveFgImageView.animate().cancel()
        activeFgImageView.animate().cancel()
        inactiveBgImageView.animate().cancel()
        activeBgImageView.animate().cancel()

        // Capture references before swapping
        val incomingBg = inactiveBgImageView
        val outgoingBg = activeBgImageView
        val incomingFg = inactiveFgImageView
        val outgoingFg = activeFgImageView

        // Setup scale type and background based on orientation
        if (photoData.isPortrait) {
            incomingFg.scaleType = ImageView.ScaleType.FIT_CENTER
            val blurred = createBlurredBitmap(bitmap)
            if (blurred != null) {
                Log.d(TAG, "displayWithSlide: Using blurred background")
                incomingBg.setImageBitmap(blurred)
                incomingBg.translationX = width.toFloat()
                incomingBg.alpha = 1f
                incomingBg.visibility = VISIBLE
                incomingBg.animate()
                    .translationX(0f)
                    .setDuration(FADE_DURATION_MS)
                    .start()
            } else {
                Log.d(TAG, "displayWithSlide: Blur failed, using darkened original as background")
                incomingBg.setImageBitmap(bitmap)
                incomingBg.translationX = width.toFloat()
                incomingBg.alpha = 0.3f
                incomingBg.visibility = VISIBLE
                incomingBg.animate()
                    .translationX(0f)
                    .setDuration(FADE_DURATION_MS)
                    .start()
            }
        } else {
            // Landscape: fill + crop for screensaver, fit with blur for widget
            if (fitLandscapePhotos) {
                incomingFg.scaleType = ImageView.ScaleType.FIT_CENTER
                val blurred = createBlurredBitmap(bitmap)
                if (blurred != null) {
                    incomingBg.setImageBitmap(blurred)
                    incomingBg.translationX = width.toFloat()
                    incomingBg.visibility = VISIBLE
                    incomingBg.animate().translationX(0f).setDuration(FADE_DURATION_MS).start()
                } else {
                    incomingBg.setImageBitmap(bitmap)
                    incomingBg.alpha = 0.3f
                    incomingBg.translationX = width.toFloat()
                    incomingBg.visibility = VISIBLE
                    incomingBg.animate().translationX(0f).setDuration(FADE_DURATION_MS).start()
                }
            } else {
                incomingFg.scaleType = ImageView.ScaleType.CENTER_CROP
                incomingBg.visibility = INVISIBLE
            }
        }

        // Slide out outgoing background
        if (outgoingBg.visibility == VISIBLE) {
            outgoingBg.animate()
                .translationX(-width.toFloat())
                .setDuration(FADE_DURATION_MS)
                .withEndAction {
                    if (displayGeneration != gen) return@withEndAction
                    outgoingBg.visibility = INVISIBLE
                    outgoingBg.translationX = 0f
                    recycleImageViewBitmap(outgoingBg)
                }
                .start()
        }

        // Setup incoming foreground
        incomingFg.setImageBitmap(bitmap)
        incomingFg.visibility = VISIBLE
        incomingFg.translationX = width.toFloat()
        incomingFg.alpha = 1f
        incomingFg.animate()
            .translationX(0f)
            .setDuration(FADE_DURATION_MS)
            .start()

        // Slide out outgoing foreground
        outgoingFg.animate()
            .translationX(-width.toFloat())
            .setDuration(FADE_DURATION_MS)
            .withEndAction {
                if (displayGeneration != gen) return@withEndAction
                outgoingFg.visibility = INVISIBLE
                outgoingFg.translationX = 0f
                recycleImageViewBitmap(outgoingFg, bitmap)
            }
            .start()

        // Swap references
        activeBgImageView = incomingBg
        inactiveBgImageView = outgoingBg
        activeFgImageView = incomingFg
        inactiveFgImageView = outgoingFg
    }

    /**
     * Clear all images and free memory.
     */
    fun clear() {
        // Recycle all bitmaps to free memory immediately
        recycleImageViewBitmap(fgImageView1)
        recycleImageViewBitmap(fgImageView2)
        recycleImageViewBitmap(bgImageView1)
        recycleImageViewBitmap(bgImageView2)
        fgImageView1.visibility = INVISIBLE
        fgImageView2.visibility = INVISIBLE
        bgImageView1.visibility = INVISIBLE
        bgImageView2.visibility = INVISIBLE
        metadataRibbon.visibility = GONE
        stopClock()
        currentPhotoId = null
    }

    /**
     * Release RenderScript resources and cancel metadata extraction.
     */
    fun release() {
        stopClock()
        metadataJob?.cancel()
        metadataScope.cancel()
        renderScript?.destroy()
        renderScript = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }

    /**
     * Transition types for photo changes.
     */
    enum class TransitionType {
        NONE,
        FADE,
        SLIDE
    }
}
