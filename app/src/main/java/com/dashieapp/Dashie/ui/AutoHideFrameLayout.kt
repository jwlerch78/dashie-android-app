package com.dashieapp.Dashie.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * FrameLayout that's GONE when it has no children, VISIBLE when it does.
 *
 * Removes the layer from compositing entirely when empty so Chromium's
 * occlusion detection on the underlying WebView doesn't mark the WebView
 * region as covered. That occlusion-marking is what causes embedded
 * <video> compositing layers to freeze (the texture goes stale until any
 * reflow re-paints it).
 *
 * Used for `widgetContainer` and `overlayContainer` in activity_main.xml,
 * which are both always-present transparent FrameLayouts that only have
 * children part of the time. With this class:
 *   - widgetContainer is GONE in kiosk mode (no native widgets)
 *   - overlayContainer is GONE between music player / timer / perf overlay shows
 *
 * Visibility flips happen synchronously inside addView/removeView so the
 * layout pass sees the correct state immediately. Width/height reads done
 * inside a child's `post {}` (e.g. MusicPlayerCard:160) are guaranteed to
 * fire after the next layout pass with the parent fully measured.
 *
 * Reported by Matt Philips 2026-04-29 — Advanced Camera Card video
 * blanking on Galaxy Tab A9+ (Android 13 / older Chromium WebView).
 * Newer Chromium handles overlay-occluded compositing correctly; older
 * Chromium needs us to literally remove the empty overlays.
 */
class AutoHideFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        // Start hidden — addView flips to VISIBLE if anything is added.
        // (XML may also declare android:visibility=gone for clarity.)
        if (childCount == 0) visibility = View.GONE
    }

    override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
        if (visibility != View.VISIBLE) visibility = View.VISIBLE
        super.addView(child, index, params)
    }

    override fun removeView(view: View?) {
        super.removeView(view)
        if (childCount == 0) visibility = View.GONE
    }

    override fun removeViewAt(index: Int) {
        super.removeViewAt(index)
        if (childCount == 0) visibility = View.GONE
    }

    override fun removeViews(start: Int, count: Int) {
        super.removeViews(start, count)
        if (childCount == 0) visibility = View.GONE
    }

    override fun removeAllViews() {
        super.removeAllViews()
        visibility = View.GONE
    }

    override fun removeAllViewsInLayout() {
        super.removeAllViewsInLayout()
        visibility = View.GONE
    }
}
