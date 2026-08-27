package com.dashieapp.Dashie.api

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebView
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures JPEG screenshots of the dashboard for the getScreenshot API command.
 *
 * Why this exists / what it captures:
 *  - The original inline implementation captured `(context as Activity).decorView`. That
 *    reference is null when the context isn't an Activity, and goes stale across Activity
 *    recreation (rotation), screensaver overlays, and backgrounding — so the capture silently
 *    returned null. The HA integration then served its last cached frame with no staleness
 *    signal, producing the "13-minute-old screenshot" symptom.
 *
 * Two-tier capture (best fidelity first, reliable fallback second):
 *  1. PixelCopy of the Window (API 26+) for native chrome + WebView (incl. TextureView-backed
 *     NORMAL-mode video, which draws to the window), THEN a per-SurfaceView PixelCopy pass that
 *     composites each live SurfaceView back in at its window position. This is required because a
 *     SurfaceView renders on its OWN SurfaceFlinger layer — the window only has a transparent
 *     punch-through hole there, so a window copy (or any Canvas.draw) shows the video as black.
 *     Maximized / Playback video feed cards use SurfaceView, which is why they were missing.
 *  2. Software draw of the live WebView's rootView (the decorView) — used when PixelCopy is
 *     unavailable (< API 26) or the window copy fails (no backing surface while backgrounded /
 *     screen-off). Captures native chrome + WebView but NOT hardware video Surfaces.
 *
 * Both tiers resolve the view/window from the LIVE WebView ([webViewProvider]) every call, so
 * they're recreation-safe — no stale Activity reference.
 *
 * Threading: View drawing / PixelCopy issue happens from the main thread; JPEG compression and
 * the watermark draw run on a background thread to keep main-thread time well under the
 * integration's 5s budget.
 *
 * Watermark: every capture is stamped with its capture time (bottom-right). Because the
 * integration serves the last cached frame on failure, a stale frame still carries its original
 * stamp — so "stale is OK, but labeled" comes for free.
 */
class ScreenshotCaptureManager(
    private val context: Context,
    private val webViewProvider: () -> WebView
) {
    companion object {
        private const val TAG = "ScreenshotCapture"
        private const val JPEG_QUALITY = 80
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Background thread for PixelCopy callbacks + JPEG compression + watermark.
    private val captureThread = HandlerThread("screenshot-capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)

    // h:mm:ss a → "8:47:12 AM". Only touched on captureThread.
    private val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.US)

    fun registerCallbacks(service: DashieApiService) {
        service.getScreenshotCallback = { callback -> capture(callback) }
    }

    /**
     * Capture the full screen to a watermarked JPEG. Calls [callback] with the bytes, or null on
     * failure. Safe to call from any thread.
     */
    fun capture(callback: (ByteArray?) -> Unit) {
        val startMs = System.currentTimeMillis()
        mainHandler.post {
            // rootView is the window's decorView — full screen incl. native chrome.
            val rootView = webViewProvider().rootView
            val width = rootView.width
            val height = rootView.height
            if (width <= 0 || height <= 0) {
                Log.e(TAG, "capture: rootView has no size (${width}x${height}) — returning null")
                callback(null)
                return@post
            }

            val window = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) resolveWindow() else null
            if (window != null) {
                // Gather live SurfaceViews + their window positions NOW (main thread) — the
                // window PixelCopy can't see them (separate SurfaceFlinger layers), so we copy
                // each one and composite it in. getLocationInWindow must run on the main thread.
                val targets = ArrayList<SurfaceTarget>()
                collectSurfaceTargets(rootView, targets)
                capturePixelCopy(window, rootView, width, height, targets, startMs, callback)
            } else {
                captureSoftware(rootView, width, height, startMs, callback)
            }
        }
    }

    /** A live SurfaceView and where it sits in the window, snapshotted on the main thread. */
    private class SurfaceTarget(val view: SurfaceView, val x: Int, val y: Int, val w: Int, val h: Int)

    private fun collectSurfaceTargets(root: View, out: MutableList<SurfaceTarget>) {
        if (root is SurfaceView && root.isShown && root.width > 0 && root.height > 0) {
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            out.add(SurfaceTarget(root, loc[0], loc[1], root.width, root.height))
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) collectSurfaceTargets(root.getChildAt(i), out)
        }
    }

    /**
     * Tier 1: PixelCopy of the composited window Surface, then a per-SurfaceView PixelCopy pass
     * to composite video feeds (which live on separate layers) back in. Falls back to
     * [captureSoftware] if the window copy fails.
     */
    private fun capturePixelCopy(
        window: Window,
        rootView: View,
        width: Int,
        height: Int,
        targets: List<SurfaceTarget>,
        startMs: Long,
        callback: (ByteArray?) -> Unit
    ) {
        val base = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(window, base, { result ->
                // Runs on captureThread (background).
                if (result == PixelCopy.SUCCESS) {
                    compositeSurfaces(base, targets, 0, startMs, callback)
                } else {
                    Log.w(TAG, "capture: window PixelCopy failed (result=$result) — software fallback")
                    base.recycle()
                    mainHandler.post { captureSoftware(rootView, width, height, startMs, callback) }
                }
            }, captureHandler)
        } catch (e: Exception) {
            // e.g. "Window doesn't have a backing surface!" when not yet drawn.
            Log.w(TAG, "capture: window PixelCopy threw (${e.message}) — software fallback")
            if (!base.isRecycled) base.recycle()
            captureSoftware(rootView, width, height, startMs, callback)
        }
    }

    /**
     * Sequentially PixelCopy each SurfaceView and draw it onto [base] at its window position.
     * Recurses on the captureThread via the async listeners; finishes once all are composited.
     * A failed/invalid surface is skipped (stays as the window's black punch-through).
     */
    private fun compositeSurfaces(
        base: Bitmap,
        targets: List<SurfaceTarget>,
        index: Int,
        startMs: Long,
        callback: (ByteArray?) -> Unit
    ) {
        if (index >= targets.size) {
            val method = if (targets.isEmpty()) "pixelcopy" else "pixelcopy+${targets.size}sv"
            finishBitmap(base, startMs, method, callback)
            return
        }
        val t = targets[index]
        val sub = Bitmap.createBitmap(t.w, t.h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(t.view, sub, { result ->
                if (result == PixelCopy.SUCCESS) {
                    Canvas(base).drawBitmap(sub, t.x.toFloat(), t.y.toFloat(), null)
                } else {
                    Log.w(TAG, "capture: SurfaceView PixelCopy failed (result=$result) — skipping")
                }
                sub.recycle()
                compositeSurfaces(base, targets, index + 1, startMs, callback)
            }, captureHandler)
        } catch (e: Exception) {
            // e.g. "Surface isn't valid" if the surface isn't producing frames yet.
            Log.w(TAG, "capture: SurfaceView PixelCopy threw (${e.message}) — skipping")
            if (!sub.isRecycled) sub.recycle()
            compositeSurfaces(base, targets, index + 1, startMs, callback)
        }
    }

    /** Tier 2: software draw of the view hierarchy (no hardware Surfaces). Runs on main thread. */
    private fun captureSoftware(
        rootView: View,
        width: Int,
        height: Int,
        startMs: Long,
        callback: (ByteArray?) -> Unit
    ) {
        val bitmap: Bitmap
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK) // opaque backstop for transparent regions
            rootView.draw(canvas)
        } catch (e: Exception) {
            Log.e(TAG, "capture: software draw failed: ${e.message}", e)
            callback(null)
            return
        }
        finishBitmap(bitmap, startMs, "software", callback)
    }

    /** Watermark + JPEG compression on the background thread, then hand bytes to [callback]. */
    private fun finishBitmap(
        bitmap: Bitmap,
        startMs: Long,
        method: String,
        callback: (ByteArray?) -> Unit
    ) {
        captureHandler.post {
            try {
                drawTimestampWatermark(bitmap, Date())
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                bitmap.recycle()
                val bytes = stream.toByteArray()
                val totalMs = System.currentTimeMillis() - startMs
                Log.i(TAG, "capture: success via $method ${bytes.size} bytes (total=${totalMs}ms)")
                callback(bytes)
            } catch (e: Exception) {
                Log.e(TAG, "capture: compress/watermark failed: ${e.message}", e)
                if (!bitmap.isRecycled) bitmap.recycle()
                callback(null)
            }
        }
    }

    /** Resolve the current Activity's Window from the live WebView (then the held context). */
    private fun resolveWindow(): Window? =
        findActivity(webViewProvider().context)?.window ?: findActivity(context)?.window

    private fun findActivity(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /** Draws "Captured <time>" in a translucent pill at the bottom-right of [bitmap]. */
    private fun drawTimestampWatermark(bitmap: Bitmap, capturedAt: Date) {
        val density = context.resources.displayMetrics.density
        val pad = 6f * density
        val label = "Captured ${timeFormat.format(capturedAt)}"

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
            typeface = Typeface.DEFAULT_BOLD
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 0, 0, 0)
        }

        val bounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, bounds)
        val boxWidth = textPaint.measureText(label) + pad * 2
        val boxHeight = bounds.height() + pad * 2
        val left = bitmap.width - boxWidth - pad
        val top = bitmap.height - boxHeight - pad

        val canvas = Canvas(bitmap)
        val radius = 4f * density
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, radius, radius, bgPaint)
        // Baseline = box top + top padding - bounds.top (bounds.top is negative).
        canvas.drawText(label, left + pad, top + pad - bounds.top, textPaint)
    }

    fun destroy() {
        captureThread.quitSafely()
    }
}
