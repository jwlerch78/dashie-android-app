package com.dashieapp.Dashie.halite.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.webkit.WebView

/**
 * Fixes and measures the "dashboard WebView blank/stale after screensaver wake"
 * bug: while the URL/HTML screensaver overlay covers the dashboard and the
 * backlight is off, the OS reclaims the dashboard WebView's GPU compositing
 * surface; the page was never told hidden (a sibling view covered it, the
 * Activity stayed resumed), so on reveal Chromium re-blits a wrong buffer (gray,
 * a frozen/stale frame, or a partial raster) and never re-rasterizes.
 *
 * THE FIX ([recompose]): on screensaver reveal, force a content-preserving
 * re-composite — toggle the WebView INVISIBLE→VISIBLE so the view rebuilds its
 * hardware layer and Chromium re-rasterizes the *current* DOM. No reload, so
 * DOM/JS state is preserved.
 *
 * THE MEASUREMENT (before/after diff): `recompose` only re-rasters the existing
 * DOM — it never changes content — so a correctly-rendered "before" frame equals
 * the "after" frame, while a gray/stale/partial "before" differs substantially.
 * We therefore capture the dashboard region just BEFORE recompose (frame A) and
 * after it settles (frame B); a large A→B pixel change means the pre-reveal
 * buffer was wrong and the fix corrected it. This catches every form of the
 * failure (not just uniform gray) and isolates it from legitimate content
 * updates (which don't move between A and B, since the DOM is identical). Logged
 * as `BLANK_DETECTED` for field ground-truth even where we can't force the bug.
 *
 * A natively-injected rAF paint-liveness probe ([PAINT_PROBE_JS]) annotates each
 * event with whether the JS render loop is alive (it usually IS in this bug —
 * the page thinks it's visible — which is why pixels, not JS, are the real
 * signal). PixelCopy window capture is API 26+; older devices still get the fix.
 */
class BlankScreenWatchdog(
    private val activity: Activity,
    private val webViewProvider: () -> WebView?,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {
    companion object {
        private const val TAG = "BlankScreenWatchdog"

        /**
         * Tiny in-page paint-liveness probe, injected via evaluateJavascript on
         * page-finished (NOT page source) so it covers every dashboard source —
         * HA kiosk-shell, calendar, etc. Idempotent.
         */
        const val PAINT_PROBE_JS = "(function(){if(window.__dashiePaint)return;" +
            "window.__dashiePaint={frames:0,last:Date.now()};" +
            "function t(){window.__dashiePaint.frames++;window.__dashiePaint.last=Date.now();requestAnimationFrame(t);}" +
            "requestAnimationFrame(t);})();"

        private const val PROBE_READ_JS = "JSON.stringify({f:window.__dashiePaint?window.__dashiePaint.frames:-1," +
            "age:Date.now()-(window.__dashiePaint?window.__dashiePaint.last:0)," +
            "vis:document.visibilityState})"

        // Let the screensaver overlay clear so frame A reflects the dashboard, not the overlay.
        private const val OVERLAY_CLEAR_MS = 250L
        // Let the re-composite re-raster complete before frame B.
        private const val RECOMPOSE_SETTLE_MS = 450L
        // Downscaled capture — a few ms, plenty of signal.
        private const val SAMPLE_W = 32
        private const val SAMPLE_H = 20
        // Per-pixel luma delta (0..255) to count a pixel as "changed" between A and B.
        private const val PIXEL_DELTA = 36.0
        // Fraction of pixels that must change substantially for "surface was wrong".
        private const val CHANGE_FRACTION_THRESHOLD = 0.45
        // Below this luma variance a single frame is "uniform" (blank verdict for checkNow).
        private const val UNIFORM_VARIANCE = 8.0
        // Recent rAF tick → render loop alive.
        private const val LIVENESS_FRESH_MS = 2000L
    }

    /** Master kill-switch. When false, reveal still re-composites (the fix) but skips measurement. */
    @Volatile var enabled: Boolean = true
    /** Reserved for future non-reveal (periodic) auto-recovery; reveal always re-composites. */
    @Volatile var autoRecoverEnabled: Boolean = false

    @Volatile private var screensaverActive = false
    @Volatile private var foreground = true
    private var lastRevealMs = 0L

    /** Screensaver activated — dashboard legitimately covered; suppress checks. */
    fun onScreensaverActive() { screensaverActive = true }

    /**
     * Screensaver revealed. THE FIX runs unconditionally (re-composite). When
     * detection is enabled, it's sandwiched in a before/after capture so the
     * field log records whether the surface was actually wrong.
     */
    fun onScreensaverRevealed() {
        screensaverActive = false
        foreground = true
        lastRevealMs = SystemClock.elapsedRealtime()
        val wv = webViewProvider() ?: return
        wv.onResume()
        wv.resumeTimers()

        if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            recompose(wv)
            DiagnosticBuffer.info("BLANK", "reveal: re-composited dashboard WebView (no measure)")
            return
        }

        // Measure: capture A (after overlay clears) → recompose → capture B → diff.
        handler.postDelayed({
            captureFrame { a ->
                recompose(wv)
                DiagnosticBuffer.info("BLANK", "reveal: re-composited dashboard WebView")
                if (a == null) return@captureFrame
                handler.postDelayed({
                    captureFrame { b -> if (b != null) evaluateDiff(a, b) }
                }, RECOMPOSE_SETTLE_MS)
            }
        }, OVERLAY_CLEAR_MS)
    }

    /** Foreground state, for correctly gating any future non-reveal checks. */
    fun setForeground(value: Boolean) { foreground = value }

    /**
     * On-demand single-frame paint oracle (for the regression rig): captures the
     * dashboard region and logs PAINTED/BLANK by variance. Does NOT re-composite.
     */
    fun checkNow(label: String = "manual") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            DiagnosticBuffer.info("BLANK", "paint-check[$label]: PixelCopy unavailable (<API26)")
            return
        }
        captureFrame { f ->
            if (f == null) { DiagnosticBuffer.info("BLANK", "paint-check[$label]: capture-failed"); return@captureFrame }
            val verdict = if (f.variance < UNIFORM_VARIANCE) "BLANK" else "PAINTED"
            val msg = "paint-check[$label]: $verdict variance=${"%.1f".format(f.variance)} meanLuma=${"%.0f".format(f.mean)}"
            Log.i(TAG, msg)
            DiagnosticBuffer.info("BLANK", msg)
            PersistentLog.info("BLANK", msg)
        }
    }

    /**
     * Force a content-preserving re-composite: toggle INVISIBLE→VISIBLE so the
     * view rebuilds its hardware layer and Chromium re-rasterizes. Unlike
     * invalidate() (re-blits the same buffer) this rebuilds the surface; unlike
     * reload() it keeps DOM/JS state.
     */
    fun recompose(webView: WebView? = webViewProvider()) {
        val wv = webView ?: return
        wv.visibility = View.INVISIBLE
        handler.post { wv.visibility = View.VISIBLE }
    }

    private data class LumaFrame(val luma: DoubleArray, val variance: Double, val mean: Double)

    private fun captureFrame(cb: (LumaFrame?) -> Unit) {
        val wv = webViewProvider()
        if (wv == null || wv.width == 0 || wv.height == 0 || wv.visibility != View.VISIBLE) { cb(null); return }
        val loc = IntArray(2)
        wv.getLocationInWindow(loc)
        val src = Rect(loc[0], loc[1], loc[0] + wv.width, loc[1] + wv.height)
        val bmp = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, src, bmp, { copyResult ->
                if (copyResult != PixelCopy.SUCCESS) { cb(null); return@request }
                Thread {
                    val frame = analyze(bmp)
                    handler.post { cb(frame) }
                }.start()
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "PixelCopy failed: ${e.message}")
            cb(null)
        }
    }

    private fun analyze(bmp: Bitmap): LumaFrame {
        val n = SAMPLE_W * SAMPLE_H
        val px = IntArray(n)
        bmp.getPixels(px, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        val luma = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            val c = px[i]
            val y = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
            luma[i] = y
            sum += y
        }
        val mean = sum / n
        var varSum = 0.0
        for (i in 0 until n) { val d = luma[i] - mean; varSum += d * d }
        return LumaFrame(luma, varSum / n, mean)
    }

    /** Compare the pre-recompose frame (A) with the post-recompose frame (B). */
    private fun evaluateDiff(a: LumaFrame, b: LumaFrame) {
        val n = a.luma.size
        var changed = 0
        var absSum = 0.0
        for (i in 0 until n) {
            val d = Math.abs(a.luma[i] - b.luma[i])
            absSum += d
            if (d > PIXEL_DELTA) changed++
        }
        val changeFraction = if (n == 0) 0.0 else changed.toDouble() / n
        val meanAbsDelta = absSum / n
        if (changeFraction > CHANGE_FRACTION_THRESHOLD) {
            reportBlank(a, b, changeFraction, meanAbsDelta)
        } else {
            Log.i(TAG, "reveal pixel-check OK changeFraction=${"%.2f".format(changeFraction)} aVar=${"%.1f".format(a.variance)} bVar=${"%.1f".format(b.variance)}")
        }
    }

    private fun reportBlank(a: LumaFrame, b: LumaFrame, changeFraction: Double, meanAbsDelta: Double) {
        webViewProvider()?.evaluateJavascript(PROBE_READ_JS) { raw ->
            val json = raw?.trim()?.trim('"')?.replace("\\\"", "\"") ?: ""
            val frames = Regex("\"f\":(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            val age = Regex("\"age\":(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val vis = Regex("\"vis\":\"(\\w+)\"").find(json)?.groupValues?.get(1) ?: "unknown"
            val alive = when {
                frames < 0 -> "unknown"
                age in 0..LIVENESS_FRESH_MS -> "true"
                else -> "false"
            }
            val msSinceReveal = if (lastRevealMs > 0) SystemClock.elapsedRealtime() - lastRevealMs else -1
            val (pssMb, ramPct, uptimeMin) = memorySnapshot()
            val msg = "BLANK_DETECTED alive=$alive vis=$vis frames=$frames " +
                "changeFraction=${"%.2f".format(changeFraction)} meanDelta=${"%.0f".format(meanAbsDelta)} " +
                "aVar=${"%.1f".format(a.variance)} bVar=${"%.1f".format(b.variance)} " +
                "msSinceReveal=$msSinceReveal pss=${pssMb}MB ram=${ramPct}% uptime=${uptimeMin}min " +
                "(fix re-composited the surface)"
            Log.w(TAG, msg)
            DiagnosticBuffer.warn("BLANK", msg)
            PersistentLog.warn("BLANK", msg)
        }
    }

    /** (pssMb, ramPercentUsed, uptimeMinutes) — only read at a (rare) blank event. */
    private fun memorySnapshot(): Triple<Int, Int, Long> {
        val pssMb = try { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss / 1024 } catch (_: Exception) { -1 }
        val ramPct = try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            if (mi.totalMem > 0) (((mi.totalMem - mi.availMem) * 100) / mi.totalMem).toInt() else -1
        } catch (_: Exception) { -1 }
        return Triple(pssMb, ramPct, SystemClock.elapsedRealtime() / 60000)
    }
}
