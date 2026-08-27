package com.dashieapp.Dashie.halite.screensaver

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Shows a thumbnail of the last screensaver photo in the top-right corner
 * when the screen wakes from photo screensaver mode.
 *
 * - Tapping the "X" button dismisses the thumbnail
 * - Tapping anywhere else on the thumbnail opens the full photo explorer
 * - Auto-dismisses after 4 seconds with a fade-out animation
 */
class PhotoPreviewOnWake(private val context: Context) {

    companion object {
        private const val TAG = "PhotoPreviewOnWake"
        private const val AUTO_DISMISS_MS = 5000L
        private const val FADE_DURATION_MS = 300L
        private const val THUMBNAIL_WIDTH_DP = 250
        private const val THUMBNAIL_HEIGHT_DP = 156
        private const val CORNER_RADIUS_DP = 12f
        private const val MARGIN_DP = 24
        private const val TOP_OFFSET_PERCENT = 0.10f  // 10% down from top
        private const val CLOSE_BUTTON_SIZE_DP = 30
        private const val CLOSE_BUTTON_MARGIN_DP = 6
    }

    private val handler = Handler(Looper.getMainLooper())
    private var container: FrameLayout? = null
    private var rootContainer: ViewGroup? = null
    private var isDismissed = false

    /** Callback when user taps thumbnail (not the X). Provides the tapped photo and full list. */
    var onOpenExplorer: ((photo: PhotoItem, allPhotos: List<PhotoItem>) -> Unit)? = null

    private val autoDismissRunnable = Runnable { dismiss() }

    /**
     * Show the thumbnail preview in the top-right corner of the given root container.
     */
    fun show(photo: PhotoItem, allPhotos: List<PhotoItem>, root: ViewGroup) {
        if (isDismissed) return

        val density = context.resources.displayMetrics.density
        val thumbW = (THUMBNAIL_WIDTH_DP * density).toInt()
        val thumbH = (THUMBNAIL_HEIGHT_DP * density).toInt()
        val margin = (MARGIN_DP * density).toInt()
        val cornerRadius = CORNER_RADIUS_DP * density
        val closeBtnSize = (CLOSE_BUTTON_SIZE_DP * density).toInt()
        val closeBtnMargin = (CLOSE_BUTTON_MARGIN_DP * density).toInt()

        rootContainer = root

        // Outer container positioned top-right, 10% down from top
        val screenH = context.resources.displayMetrics.heightPixels
        val topOffset = (screenH * TOP_OFFSET_PERCENT).toInt()

        val frame = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(thumbW, thumbH).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = topOffset
                marginEnd = margin
            }
            elevation = 8f * density
            alpha = 0f // Start invisible for fade-in
        }

        // Thumbnail image with rounded corners
        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#1A1A2E"))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true
        }

        // Load the photo bitmap with EXIF rotation correction
        try {
            val filePath = photo.uri.path
            if (filePath == null) {
                Log.w(TAG, "Photo URI has no path: ${photo.uri}")
                return
            }
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val rawBitmap = BitmapFactory.decodeFile(filePath, options)
            if (rawBitmap == null) {
                Log.w(TAG, "Failed to decode bitmap: $filePath")
                return
            }
            val bitmap = applyExifRotation(rawBitmap, filePath)
            imageView.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading preview photo", e)
            return
        }

        // Close (X) button — gray circle with white X, matches video feed style
        val closeBtn = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(closeBtnSize, closeBtnSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = closeBtnMargin
                marginEnd = closeBtnMargin
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x99000000.toInt())  // 60% black circle
            }
            setImageResource(com.dashieapp.Dashie.R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            elevation = 4f * density
        }

        frame.addView(imageView)
        frame.addView(closeBtn)

        // Touch handling
        closeBtn.setOnClickListener {
            dismiss()
        }

        imageView.setOnClickListener {
            dismiss()
            onOpenExplorer?.invoke(photo, allPhotos)
        }

        container = frame
        root.addView(frame)

        // Fade in
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = FADE_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { frame.alpha = it.animatedValue as Float }
            start()
        }

        // Schedule auto-dismiss
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)

        Log.i(TAG, "Showing photo preview thumbnail (${photo.filename})")
    }

    /**
     * Dismiss the thumbnail with a fade-out animation.
     */
    fun dismiss() {
        if (isDismissed) return
        isDismissed = true
        handler.removeCallbacks(autoDismissRunnable)

        val frame = container ?: return
        val root = rootContainer ?: return

        ValueAnimator.ofFloat(frame.alpha, 0f).apply {
            duration = FADE_DURATION_MS
            interpolator = AccelerateInterpolator()
            addUpdateListener { frame.alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.removeView(frame)
                    container = null
                    rootContainer = null
                }
            })
            start()
        }

        Log.d(TAG, "Dismissing photo preview thumbnail")
    }

    /**
     * Read EXIF orientation and rotate bitmap if needed.
     */
    private fun applyExifRotation(bitmap: Bitmap, filePath: String): Bitmap {
        return try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> return bitmap
            }
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF orientation", e)
            bitmap
        }
    }
}
