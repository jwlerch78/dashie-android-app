package com.dashieapp.Dashie

import android.content.res.Resources
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Handles the HA disconnected indicator UI for MainActivity.
 * Extracted from MainActivity to reduce its size and improve maintainability.
 *
 * Responsibilities:
 * - Show/hide the indicator with fade animations
 * - Scale the indicator for consistent visual sizing across screens
 * - Find and cache view references
 */
class MainHaDisconnectedIndicator(
    private val resources: Resources,
    private val findViewByIdCallback: (Int) -> View?,
    /** Visibility gate — registered OVERLAY_KIOSK_OR_FULL (event-triggered
     *  on HA connection state, kiosk-relevant). Gate force-hides on
     *  transition to OFF mode (login screen). */
    private val visibilityGate: com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate? = null
) {
    companion object {
        private const val TAG = "HaDisconnectedIndicator"
    }

    private var indicator: View? = null
    private var haIcon: ImageView? = null

    /**
     * Initialize the indicator by finding view references.
     * Call this after setContentView().
     */
    fun initialize() {
        indicator = findViewByIdCallback(R.id.haDisconnectedIndicator)
        haIcon = findViewByIdCallback(R.id.haDisconnectedIcon) as? ImageView
        indicator?.let {
            visibilityGate?.register(
                it,
                com.dashieapp.Dashie.halite.widgets.NativeWidgetVisibilityGate.WidgetKind.OVERLAY_KIOSK_OR_FULL
            )
        }
        scale()
    }

    /**
     * Show the HA disconnected indicator with fade-in animation.
     * Called when Home Assistant connection is lost.
     */
    fun show() {
        indicator?.let { view ->
            Log.i(TAG, "🏠 Showing HA disconnected indicator")
            view.visibility = View.VISIBLE
            view.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        }
    }

    /**
     * Hide the HA disconnected indicator with fade-out animation.
     * Called when Home Assistant connection is restored.
     */
    fun hide() {
        indicator?.let { view ->
            Log.i(TAG, "🏠 Hiding HA disconnected indicator")
            view.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    view.visibility = View.GONE
                }
                .start()
        }
    }

    /**
     * Scale the HA disconnected indicator for consistent visual sizing across screens.
     * Uses density-normalized sizing so elements appear the same physical size
     * regardless of screen density (important for mixed TV/tablet deployments).
     * Base sizes (for 1080px at density 1.0): HA icon 36dp, badge 27dp, wifi icon 15dp
     * (These are 1.5x the original sizes for better visibility)
     */
    private fun scale() {
        val container = indicator as? FrameLayout ?: return

        // Calculate scale factor using density-normalized short side
        // This compensates for Android's density scaling to achieve consistent physical sizes
        // Example: ONN Stick (1080px, density 2.0) → normalized = 540 → scale = 0.5
        // Example: Mio 15" (1080px, density 1.0) → normalized = 1080 → scale = 1.0
        val dm = resources.displayMetrics
        val shortSidePx = minOf(dm.widthPixels, dm.heightPixels)
        val normalizedShortSide = shortSidePx / dm.density
        val baseNormalizedSize = 1080f
        val scaleFactor = (normalizedShortSide / baseNormalizedSize).coerceIn(0.5f, 1.2f)

        Log.d(TAG, "🏠 HA indicator scale factor: $scaleFactor (screen: ${dm.widthPixels}x${dm.heightPixels}, " +
                "density: ${dm.density}, normalizedShortSide: $normalizedShortSide)")

        // Helper to convert base dp to scaled pixels
        fun scaledDp(dp: Int): Int = (dp * dm.density * scaleFactor).toInt()

        // Scale the HA icon (first child) - 1.5x larger: 24dp → 36dp
        haIcon?.layoutParams?.let { params ->
            params.width = scaledDp(36)
            params.height = scaledDp(36)
            haIcon?.layoutParams = params
        }

        // Scale the wifi badge container (second child of the main container)
        // It's nested: container > HA icon + badge FrameLayout > wifi icon
        // 1.5x larger: 18dp → 27dp
        if (container.childCount >= 2) {
            val badgeContainer = container.getChildAt(1) as? FrameLayout
            badgeContainer?.layoutParams?.let { params ->
                params.width = scaledDp(27)
                params.height = scaledDp(27)
                if (params is FrameLayout.LayoutParams) {
                    params.topMargin = -scaledDp(9)
                    params.marginEnd = -scaledDp(9)
                }
                badgeContainer.layoutParams = params
            }

            // Scale the wifi icon inside the badge - 1.5x larger: 10dp → 15dp
            if (badgeContainer != null && badgeContainer.childCount >= 1) {
                val wifiIcon = badgeContainer.getChildAt(0) as? ImageView
                wifiIcon?.layoutParams?.let { params ->
                    params.width = scaledDp(15)
                    params.height = scaledDp(15)
                    wifiIcon.layoutParams = params
                }
            }
        }

        // Scale the container padding and margins (1.5x larger)
        container.layoutParams?.let { params ->
            if (params is FrameLayout.LayoutParams) {
                params.topMargin = scaledDp(12)
                params.marginEnd = scaledDp(12)
            }
            container.layoutParams = params
        }
        container.setPadding(0, scaledDp(9), scaledDp(9), 0)
    }
}
