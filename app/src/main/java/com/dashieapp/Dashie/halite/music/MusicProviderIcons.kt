package com.dashieapp.Dashie.halite.music

import com.dashieapp.Dashie.R

/**
 * Resolves provider icon drawable resources from a provider label string.
 * Centralized so all UI components (strip, panel, search popup) use the same mapping.
 */
object MusicProviderIcons {

    /** Returns a drawable resource ID for the given provider label, or 0 if unknown. */
    fun iconRes(providerLabel: String?): Int = when {
        providerLabel == null -> 0
        providerLabel.contains("Spotify", ignoreCase = true) -> R.drawable.ic_spotify
        providerLabel.contains("YouTube", ignoreCase = true) -> R.drawable.ic_youtube_music
        providerLabel.contains("Apple", ignoreCase = true) -> R.drawable.ic_apple_music
        providerLabel.contains("Tidal", ignoreCase = true) -> R.drawable.ic_tidal
        providerLabel.contains("Plex", ignoreCase = true) -> R.drawable.ic_plex
        providerLabel.contains("Jellyfin", ignoreCase = true) -> R.drawable.ic_jellyfin
        providerLabel.contains("SoundCloud", ignoreCase = true) -> R.drawable.ic_soundcloud
        providerLabel.contains("Deezer", ignoreCase = true) -> R.drawable.ic_deezer
        providerLabel.contains("Subsonic", ignoreCase = true) ||
            providerLabel.contains("Navidrome", ignoreCase = true) -> R.drawable.ic_subsonic
        else -> 0
    }
}
