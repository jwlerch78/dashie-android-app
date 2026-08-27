package com.dashieapp.Dashie.halite.widgets

import com.dashieapp.Dashie.R

/**
 * Maps string icon keys (sent from JS coordinators) to Android drawable
 * resource ids. Keys are stable identifiers that JS can reference without
 * knowing about Android resource ids.
 *
 * Add a new icon: drop the vector drawable in res/drawable/ic_*.xml and
 * add a key here. JS can then reference it by name in its bar item list.
 */
object WidgetIconRegistry {
    private val icons: Map<String, Int> = mapOf(
        // Generic
        "settings"    to R.drawable.ic_settings,
        "pause"       to R.drawable.ic_pause,
        "play"        to R.drawable.ic_play,

        // Photos
        "maximize-2"  to R.drawable.ic_maximize_2,
        "upload-photo" to R.drawable.ic_upload_photo,

        // Rotator widget types — same set used by RotatorViewStrip so the
        // settings widget chooser shows matching glyphs.
        "weather_hourly"   to R.drawable.ic_view_weather_hourly,
        "weather_daily"    to R.drawable.ic_view_weather_daily,
        "family_cards"     to R.drawable.ic_view_family_cards,
        // Single-purpose family card variants reuse the sidebar glyphs the
        // user already associates with chores / locations.
        "family_chores"    to R.drawable.ic_sidebar_tasks,
        "family_locations" to R.drawable.ic_sidebar_location
    )

    /** Resolve an icon key to a drawable resource id. Returns 0 if unknown
     *  (callers should treat 0 as "leave existing icon unchanged" — the
     *  generic overlay's setItem skips icon updates on 0). */
    fun resolve(key: String?): Int {
        if (key.isNullOrEmpty()) return 0
        return icons[key] ?: 0
    }

    /** Whether a key resolves to a known icon. */
    fun isKnown(key: String?): Boolean = !key.isNullOrEmpty() && icons.containsKey(key)
}
