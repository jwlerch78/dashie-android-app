package com.dashieapp.Dashie.halite.music

/**
 * Callbacks for music player user interactions.
 * Shared between the card orchestrator and its renderers.
 */
data class MusicPlayerCallbacks(
    val onPlayPause: () -> Unit,
    val onMinimize: () -> Unit,
    val onMaximize: () -> Unit,
    val onNormal: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onStop: () -> Unit,
    val onShuffleToggle: (() -> Unit)? = null,
    val onRepeatCycle: (() -> Unit)? = null,
    val onSeek: ((positionMs: Long) -> Unit)? = null,
    val onStrip: () -> Unit = {},
    val onFloat: () -> Unit = {},
    val onVolumeChange: ((Float) -> Unit)? = null,
    val onMuteToggle: ((Boolean) -> Unit)? = null,
    val onSpeakerClicked: (() -> Unit)? = null,
    val onPlayRecentItem: ((String) -> Unit)? = null,
    val onExplorerToggle: (() -> Unit)? = null,
    val onHide: () -> Unit = {},
    // Speaker group drawer callbacks
    val onSpeakerDrawerToggle: (() -> Unit)? = null,
    val onSpeakerJoin: ((playerId: String, join: Boolean) -> Unit)? = null,
    val onSpeakerVolumeChange: ((playerId: String, percent: Int) -> Unit)? = null,
    val onSpeakerMuteToggle: ((playerId: String, muted: Boolean) -> Unit)? = null,
    val onGroupVolumeChange: ((percent: Int) -> Unit)? = null,
    val onGroupMuteToggle: ((muted: Boolean) -> Unit)? = null,
    val onTransferQueue: ((targetPlayerId: String, targetName: String) -> Unit)? = null,
    val onClearQueue: ((targetPlayerId: String) -> Unit)? = null,
    // Media browser panel callbacks
    val onMediaBrowserToggle: (() -> Unit)? = null
)
