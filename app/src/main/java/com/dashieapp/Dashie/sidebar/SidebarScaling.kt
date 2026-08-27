package com.dashieapp.Dashie.sidebar

import android.content.Context
import android.content.res.Configuration
import com.dashieapp.Dashie.halite.preferences.DisplayPreferences
import kotlin.math.min

/**
 * Computes the effective sidebar/popout scale factor.
 *
 * Android's dp base sizes (30dp icons, 286dp popouts) are already larger than
 * the JS CSS pixel equivalents (20px icons), so the boost values are scaled
 * down compared to the JS --sidebar-boost (1.15/1.3/1.5).
 *
 * TV devices get no boost (1.0) since they're viewed from a distance and the
 * native dp sizes are already appropriate.
 *
 * Effective scale = deviceBoost × userSetting (sidebarIconSize preference).
 */
object SidebarScaling {

    /**
     * Device-based boost factor derived from physical screen dimensions.
     * Reduced from JS values since Android dp base sizes are already larger.
     *
     * TV devices: 0.75 (icons viewed at 10ft distance — need slim footprint)
     * Tablets (bucket by physicalShort — pixels on the short axis, AFTER any
     * system decoration cut-outs from the display metrics):
     *   physicalShort < 700    → 1.0   (Echo Show 5, ONN — 30dp is fine)
     *   physicalShort 700–999  → 0.85  (Fire tablet-class — noticeable shrink)
     *   physicalShort >= 1000  → 1.2   (Samsung, Mio — moderate bump)
     *
     * Note on the 700 threshold: Fire HD 10 in landscape reports widthPixels=1280,
     * heightPixels=736 (hardware 800 short, minus ~64px for system decorations),
     * so the bucket must be < 736 to catch it. Echo Show 5 is ~480 short.
     */
    fun computeDeviceBoost(context: Context): Float {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return 0.75f

        val dm = context.resources.displayMetrics
        val physicalShort = min(dm.widthPixels, dm.heightPixels)
        return when {
            physicalShort >= 1000 -> 1.2f
            physicalShort >= 700  -> 0.85f
            else                  -> 1.0f
        }
    }

    /**
     * Vertical-spacing boost — applied to button height and inter-button gaps.
     * Scaled MORE aggressively than icons on smaller devices so the absolute
     * air around each icon decreases (not just proportionally). Samsung/Mio
     * stay at 1.0 (unchanged from XML base); smaller devices get tighter.
     *
     * Derivation: the "air" around an icon = buttonHeight - iconSize. If both
     * shrink by the same %, air shrinks proportionally but the RATIO of air
     * to icon stays the same, so visually the icons still look equally spread
     * out. Shrinking buttons faster than icons reduces the absolute air gap.
     */
    fun computeSpacingBoost(context: Context): Float {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        if (uiMode == Configuration.UI_MODE_TYPE_TELEVISION) return 0.60f  // was 0.75

        val dm = context.resources.displayMetrics
        val physicalShort = min(dm.widthPixels, dm.heightPixels)
        return when {
            physicalShort >= 1000 -> 1.0f   // Samsung, Mio — keep XML-base 50dp / 8dp
            physicalShort >= 700  -> 0.70f  // Fire tablet — tighter than icons (see bucket note above)
            else                  -> 1.0f   // Echo Show — keep XML base
        }
    }

    /**
     * Combined multiplier: deviceBoost × sidebarIconSize × DisplaySize.
     * Used for both sidebar strip icons and popout scaling.
     *
     * Display Size is the new per-device "make native chrome bigger" knob; it
     * composes with the existing Sidebar Icon Size fine-tune (so the sidebar =
     * device boost × icon-size pref × display-size). At Display Size 100% the
     * factor is 1.0 (unchanged behavior).
     */
    fun effectiveMultiplier(context: Context): Float {
        val boost = computeDeviceBoost(context)
        val userSetting = DisplayPreferences(context).sidebarIconSize.toFloatOrNull() ?: 1f
        val displaySize = com.dashieapp.Dashie.halite.preferences.DisplaySizeScale.scale(context)
        return boost * userSetting * displaySize
    }
}
