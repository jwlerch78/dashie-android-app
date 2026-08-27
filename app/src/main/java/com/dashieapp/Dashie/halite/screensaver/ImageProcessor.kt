package com.dashieapp.Dashie.halite.screensaver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore

/**
 * Shared image processing utility for screensaver photos.
 *
 * Handles:
 * - HEIC to JPEG conversion (WebView can't render HEIC natively)
 * - EXIF orientation correction (WebView doesn't reliably support image-orientation CSS)
 * - Screen-resolution downsizing (critical for WebView memory efficiency)
 *
 * Used by:
 * - HaliteScreenController: When prefetching photos to cache
 * - DashieWebViewClient: When serving uncached photos from network
 *
 * Memory note: WebView renderer decodes images at full resolution, consuming
 * massive memory (e.g., 4032x3024 = 48MB per image). By resizing to screen
 * resolution before serving to WebView, we reduce decoder memory to ~8MB/image.
 *
 * Concurrency: Uses a semaphore to limit concurrent processing to 1 image at a time.
 * This prevents memory spikes when multiple OkHttp threads receive images simultaneously.
 * Each decode can temporarily use 10-20MB; with 4 concurrent threads that's 40-80MB spike.
 */
object ImageProcessor {
    private const val TAG = "ImageProcessor"

    // Target screen resolution for downsizing
    // We use 1920x1080 as max dimension - most displays are this or smaller
    // This dramatically reduces WebView renderer memory usage
    private const val MAX_DIMENSION = 1920

    // Low-res for blurred background - we just need shape and color, not detail
    private const val BACKGROUND_MAX_DIMENSION = 480

    // Semaphore to serialize image processing - only 1 image decoded at a time
    // This prevents memory spikes from concurrent decoding on multiple OkHttp threads
    private val processingSemaphore = Semaphore(1)

    /**
     * Process image for WebView display:
     * - Efficiently decode at reduced resolution using inSampleSize
     * - Read EXIF orientation and apply rotation
     * - Convert HEIC to JPEG if needed
     * - Scale to screen resolution
     * - Re-encode as JPEG with correct orientation
     *
     * @param imageBytes Original image data
     * @param isHeic Whether the image is HEIC format
     * @return Processed JPEG bytes at screen resolution
     */
    fun processForWebView(imageBytes: ByteArray, isHeic: Boolean): ByteArray {
        // Acquire semaphore to ensure only one image is decoded at a time
        // This prevents memory spikes from concurrent processing
        processingSemaphore.acquire()
        return try {
            // Step 1: Read image dimensions without decoding full bitmap
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.w(TAG, "📷 Could not read image dimensions")
                return imageBytes
            }

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            // Step 2: Read EXIF orientation
            val orientation = try {
                val exif = ExifInterface(ByteArrayInputStream(imageBytes))
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (e: Exception) {
                Log.d(TAG, "📷 Could not read EXIF orientation: ${e.message}")
                ExifInterface.ORIENTATION_NORMAL
            }

            val needsRotation = orientation != ExifInterface.ORIENTATION_NORMAL &&
                               orientation != ExifInterface.ORIENTATION_UNDEFINED

            // Step 3: Calculate inSampleSize for efficient memory usage during decode
            // inSampleSize must be power of 2 for efficiency
            val inSampleSize = calculateInSampleSize(originalWidth, originalHeight, MAX_DIMENSION)

            // Step 4: Decode at reduced resolution
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565  // Use RGB_565 for less memory (2 bytes/pixel vs 4)

            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
            if (bitmap == null) {
                Log.w(TAG, "📷 Failed to decode image")
                return imageBytes
            }

            // Step 5: Fine-scale to exact target dimensions if still too large
            val scaledBitmap = scaleToFit(bitmap, MAX_DIMENSION)
            if (scaledBitmap != bitmap) {
                bitmap.recycle()
                bitmap = scaledBitmap
            }

            // Step 6: Apply EXIF rotation if needed
            if (needsRotation) {
                val rotatedBitmap = applyExifRotation(bitmap, orientation)
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = rotatedBitmap
                }
            }

            Log.d(TAG, "📷 Processed: ${originalWidth}x${originalHeight} -> ${bitmap.width}x${bitmap.height} " +
                      "(inSampleSize=$inSampleSize, rotation=$orientation)")

            // Step 7: Compress to JPEG
            val outputStream = ByteArrayOutputStream()
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            bitmap.recycle()

            if (success) {
                Log.d(TAG, "📷 Output: ${outputStream.size() / 1024}KB")
                outputStream.toByteArray()
            } else {
                Log.w(TAG, "📷 Failed to compress bitmap to JPEG")
                imageBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "📷 Image processing error: ${e.message}")
            imageBytes
        } finally {
            // Always release semaphore to allow next image to process
            processingSemaphore.release()
        }
    }

    /**
     * Calculate inSampleSize for efficient memory usage during decode.
     * Returns power of 2 for BitmapFactory efficiency.
     */
    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var inSampleSize = 1
        val maxOriginal = maxOf(width, height)

        // Keep doubling until we're at or below target
        while (maxOriginal / inSampleSize > maxDimension * 2) {
            inSampleSize *= 2
        }

        return inSampleSize
    }

    /**
     * Scale bitmap to fit within maxDimension while maintaining aspect ratio.
     */
    private fun scaleToFit(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        // Already small enough
        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        // Calculate scale factor
        val scaleFactor = minOf(
            maxDimension.toFloat() / width,
            maxDimension.toFloat() / height
        )

        val newWidth = (width * scaleFactor).toInt()
        val newHeight = (height * scaleFactor).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Process image for blurred background display.
     * Uses very low resolution since it's just decorative blur.
     * Max 480px to save significant memory - the blur hides all detail anyway.
     *
     * @param imageBytes Original image data
     * @param isHeic Whether the image is HEIC format
     * @return Processed low-res JPEG bytes
     */
    fun processForBackground(imageBytes: ByteArray, isHeic: Boolean): ByteArray {
        // Acquire semaphore to ensure only one image is decoded at a time
        processingSemaphore.acquire()
        return try {
            // Read dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return imageBytes
            }

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            // Aggressive inSampleSize for low-res background
            val inSampleSize = calculateInSampleSize(originalWidth, originalHeight, BACKGROUND_MAX_DIMENSION)

            // Read EXIF orientation
            val orientation = try {
                val exif = ExifInterface(ByteArrayInputStream(imageBytes))
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            // Decode at low resolution
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565

            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
                ?: return imageBytes

            // Scale to background size
            val scaledBitmap = scaleToFit(bitmap, BACKGROUND_MAX_DIMENSION)
            if (scaledBitmap != bitmap) {
                bitmap.recycle()
                bitmap = scaledBitmap
            }

            // Apply rotation
            if (orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED) {
                val rotatedBitmap = applyExifRotation(bitmap, orientation)
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                    bitmap = rotatedBitmap
                }
            }

            Log.d(TAG, "📷 Background: ${originalWidth}x${originalHeight} -> ${bitmap.width}x${bitmap.height}")

            // Compress with lower quality for background
            val outputStream = ByteArrayOutputStream()
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            bitmap.recycle()

            if (success) outputStream.toByteArray() else imageBytes
        } catch (e: Exception) {
            Log.e(TAG, "📷 Background processing error: ${e.message}")
            imageBytes
        } finally {
            processingSemaphore.release()
        }
    }

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
            Log.w(TAG, "📷 Failed to rotate bitmap: ${e.message}")
            bitmap
        }
    }
}
