package com.dashieapp.Dashie.halite.preferences

import android.content.Context

/**
 * Single source of truth for the "Display Size" scale factor applied to NATIVE
 * dashboard chrome/overlays (sidebar, control center, music player, video feed
 * cards, timers, voice indicator).
 *
 * Display Size is one of three independent, per-device size knobs (all in the
 * Display settings section):
 *   - **Font Size**   → scales web widget TEXT via --widget-font-scale.
 *   - **Display Size** (this) → scales NATIVE chrome/overlays.
 *   - **Widget Zoom** → legacy web layout zoom.
 *
 * Keeping them separate is what lets a dense panel (e.g. the 15" Mio: 1920x1080
 * @ density 160 ≈ 147 PPI) get larger native chrome AND larger web text without
 * zooming the web layout (which made the calendar show too few hours).
 *
 * At the default 100% [scale] returns exactly 1.0f, so wiring this into a
 * `dpToPx`/`setTextSize` chokepoint is a no-op for every existing user — native
 * chrome only grows once Display Size is raised. Clamped to [MIN_SCALE,
 * MAX_SCALE]; never shrinks native chrome below its design size.
 */
object DisplaySizeScale {

    const val MIN_SCALE = 1.0f
    const val MAX_SCALE = 2.0f

    /**
     * Multiplier for native overlay dimensions/text, e.g. 150% -> 1.5f.
     * Reads the live pref on every call so a re-render picks up changes.
     */
    fun scale(context: Context): Float {
        val pct = DisplayPreferences(context).displaySize  // 100..200
        return (pct / 100f).coerceIn(MIN_SCALE, MAX_SCALE)
    }
}
