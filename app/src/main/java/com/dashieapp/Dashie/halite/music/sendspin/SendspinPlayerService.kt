package com.dashieapp.Dashie.halite.music.sendspin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dashieapp.Dashie.MainActivity
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.music.sendspin.decoder.AudioDecoderFactory

/**
 * Foreground service that runs the Sendspin client for synchronized multi-room audio.
 *
 * When running, the Dashie tablet acts as a Sendspin speaker endpoint that
 * Music Assistant can group with Chromecast, AirPlay, and other Sendspin speakers.
 *
 * Connects via WebSocket to the MA Sendspin server (port 8927) and receives
 * timestamped audio chunks for synchronized playback via AudioTrack.
 *
 * Lifecycle: started when music is enabled + MA server is known,
 * stopped when music is disabled.
 */
class SendspinPlayerService : Service() {

    companion object {
        private const val TAG = "SendspinService"
        private const val NOTIFICATION_CHANNEL_ID = "dashie_sendspin"
        private const val NOTIFICATION_ID = 8927  // Sendspin default port

        private const val EXTRA_SERVER_HOST = "server_host"
        private const val EXTRA_SERVER_PORT = "server_port"
        private const val EXTRA_SERVER_PATH = "server_path"
        private const val EXTRA_CLIENT_NAME = "client_name"
        private const val EXTRA_CLIENT_ID = "client_id"

        // App-internal broadcast fired by SettingsCallbackWiring when the Performance → WiFi Lock
        // toggle changes, so the running service can release/re-acquire its lock live. Lock policy
        // itself lives in SendspinLockManager.
        private const val ACTION_WIFI_LOCK_CHANGED = "com.dashieapp.Dashie.ACTION_WIFI_LOCK_CHANGED"

        /**
         * Static callback for player data updates.
         * Set by HaliteComponentWiring before starting the service.
         * Uses a static reference because the service is started (not bound).
         */
        @Volatile
        var globalPlayerDataCallback: ((com.dashieapp.Dashie.halite.music.MusicPlayerData) -> Unit)? = null

        /**
         * Static callback for connection manager events.
         */
        @Volatile
        var globalConnectionManager: SendspinConnectionManager? = null

        /**
         * Static reference to the active SendSpinClient for command routing.
         * Set when the client connects, cleared on destroy.
         */
        @Volatile
        var activeClient: SendSpinClient? = null

        /**
         * Static reference to the active SyncAudioPlayer for instant local pause/resume.
         */
        @Volatile
        var activeAudioPlayer: SyncAudioPlayer? = null

        /** Timestamp of last user command — used by instances to debounce server state updates. */
        @Volatile
        private var lastUserCommandTimestamp = 0L

        /** Call before sending any user-initiated command. */
        fun notifyUserCommand() {
            lastUserCommandTimestamp = System.currentTimeMillis()
        }

        /** Reset position to 0 immediately (call before play_media to prevent stale position flash). */
        @Volatile
        var pendingPositionReset = false

        fun start(context: Context, serverHost: String, serverPort: Int = 8927, clientName: String, clientId: String? = null, serverPath: String = "/sendspin") {
            val intent = Intent(context, SendspinPlayerService::class.java).apply {
                putExtra(EXTRA_SERVER_HOST, serverHost)
                putExtra(EXTRA_SERVER_PORT, serverPort)
                putExtra(EXTRA_SERVER_PATH, serverPath)
                putExtra(EXTRA_CLIENT_NAME, clientName)
                if (clientId != null) putExtra(EXTRA_CLIENT_ID, clientId)
            }
            // Foreground-aware start: plain startService() while the app is
            // foreground avoids the 5s startForeground() deadline entirely —
            // the deadline was still being blown on ANR-stalled low-RAM devices
            // even with the promote-in-onCreate fix (onCreate never ran).
            com.dashieapp.Dashie.halite.util.ServiceStartHelper.startForegroundAware(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SendspinPlayerService::class.java))
        }
    }

    private var client: SendSpinClient? = null
    private var audioPlayer: SyncAudioPlayer? = null
    private var connectionManager: SendspinConnectionManager? = null

    /**
     * WiFi + CPU wake locks held for the lifetime of the service. Without the WiFi lock,
     * Android's power-save kicks in during screen-off and stale-outs the WebSocket connection
     * to MA's Sendspin server (speaker goes "unavailable" until app restart). The WiFi lock
     * honors the Performance → WiFi Lock toggle; the wake lock is always held. Released in
     * onDestroy. Lazy so applicationContext is valid (constructed before attachBaseContext).
     */
    private val lockManager by lazy { SendspinLockManager(this) }

    /**
     * ConnectivityManager callback wired to client.setNetworkAvailable(). Without
     * this, the smart "pause reconnects when offline / immediate retry on
     * network return" path in SendSpinClient is dead code (no caller). Hooking
     * it up means a WiFi resume short-circuits the 30s steady-state backoff.
     */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Receiver for ACTION_SCREEN_ON broadcast. When the user wakes the screen
     * (face detect, power button, touch), we force a reconnect-check so the
     * player comes back even if ConnectivityManager didn't fire an event
     * (e.g., WiFi was just in power-save, not formally disconnected).
     */
    private var screenOnReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private val positionTickRunnable = object : Runnable {
        override fun run() {
            if (currentIsPlaying && !isInUserCommandDebounce()) {
                pushPlayerData()
            }
            pushHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Promote to foreground FIRST: the ~5s startForegroundService() deadline spans both
        // onCreate and onStartCommand, and the lock/receiver/init work below can blow it on
        // slow/idle devices before startForeground() is reached. onStartCommand re-asserts.
        promoteToForeground("Starting…")

        SendspinSettings.init(applicationContext)
        // Pick up static callbacks set by HaliteComponentWiring
        onPlayerDataChanged = globalPlayerDataCallback
        connectionManager = globalConnectionManager
        // Start position tick for smooth progress bar
        pushHandler.postDelayed(positionTickRunnable, 1000)
        // Observe device volume changes (hardware buttons, Android sidebar)
        startVolumeObserver()

        // Acquire WiFi + wake locks BEFORE the WebSocket connects, so the
        // initial handshake isn't disrupted by power-save. Locks released in
        // onDestroy.
        lockManager.acquireWifiLock()
        lockManager.acquireWakeLock()

        // Wire ConnectivityManager → client.setNetworkAvailable() so the
        // existing smart-reconnect path actually fires on network events.
        registerNetworkCallback()

        // Trigger reconnect-check on screen wake. Backstop for the case where
        // WiFi never formally disconnected (just power-saved) so ConnectivityManager
        // doesn't fire.
        registerScreenOnReceiver()

        PersistentLog.info("SENDSPIN", "Service created — wifi/wake locks held, network + screen-on receivers registered")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-assert foreground — onCreate already promoted us, but onStartCommand can also
        // arrive on a START_NOT_STICKY re-delivery without a fresh onCreate. Idempotent.
        // Android requires startForeground() within ~5s of startForegroundService(), even on
        // early-return/stopSelf paths, or it throws ForegroundServiceDidNotStartInTimeException.
        promoteToForeground("Starting…")

        val host = intent?.getStringExtra(EXTRA_SERVER_HOST)
        val port = intent?.getIntExtra(EXTRA_SERVER_PORT, 8927) ?: 8927
        val path = intent?.getStringExtra(EXTRA_SERVER_PATH) ?: "/sendspin"
        val clientName = intent?.getStringExtra(EXTRA_CLIENT_NAME) ?: Build.MODEL
        val clientId = intent?.getStringExtra(EXTRA_CLIENT_ID)

        if (host.isNullOrBlank()) {
            Log.w(TAG, "No server host provided, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        updateNotification("Connecting to $host...")

        // Set persistent player ID if provided
        if (clientId != null) {
            SendspinSettings.setPlayerId(clientId)
        }

        // Stop any existing client
        destroyClient()

        // Store device name for friendly name display
        registeredDeviceName = clientName

        // Create audio player and client
        val address = "$host:$port"
        startClient(address, clientName, path)

        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "App task removed — stopping Sendspin service")
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        PersistentLog.info("SENDSPIN", "Service destroying")
        pushHandler.removeCallbacks(positionTickRunnable)
        stopVolumeObserver()
        unregisterScreenOnReceiver()
        unregisterNetworkCallback()
        lockManager.releaseAll()
        destroyClient()
        super.onDestroy()
    }

    // ── Wifi/Wake locks ───────────────────────────────────────────────

    // ── Network awareness ─────────────────────────────────────────────

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network available — notifying client")
                    PersistentLog.info("SENDSPIN", "Network available")
                    activeClient?.setNetworkAvailable(true)
                }

                override fun onLost(network: Network) {
                    Log.i(TAG, "Network lost — notifying client")
                    PersistentLog.info("SENDSPIN", "Network lost")
                    activeClient?.setNetworkAvailable(false)
                }
            }
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
            PersistentLog.warn("SENDSPIN", "Failed to register network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cb = networkCallback ?: return
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback: ${e.message}")
        }
        networkCallback = null
    }

    // ── Screen-on reconnect trigger ───────────────────────────────────

    private fun registerScreenOnReceiver() {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            val c = activeClient ?: return
                            Log.i(TAG, "ACTION_SCREEN_ON — triggering reconnect check")
                            PersistentLog.info("SENDSPIN", "Screen on — triggering reconnect check")
                            c.triggerReconnectCheck()
                        }
                        ACTION_WIFI_LOCK_CHANGED -> {
                            val enabled = intent.getBooleanExtra("enabled", true)
                            Log.i(TAG, "WiFi lock toggle changed: enabled=$enabled")
                            PersistentLog.info("SENDSPIN", "WiFi lock toggle changed: enabled=$enabled")
                            lockManager.applyWifiLockPreference(enabled)
                        }
                    }
                }
            }
            // ACTION_SCREEN_ON is a protected system broadcast — register as-is (no export flag).
            registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
            // The WiFi-lock toggle is an app-internal broadcast — must declare NOT_EXPORTED on API 33+.
            val wifiFilter = IntentFilter(ACTION_WIFI_LOCK_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, wifiFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, wifiFilter)
            }
            screenOnReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen-on receiver: ${e.message}")
            PersistentLog.warn("SENDSPIN", "Failed to register screen-on receiver: ${e.message}")
        }
    }

    private fun unregisterScreenOnReceiver() {
        try {
            screenOnReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen-on receiver: ${e.message}")
        }
        screenOnReceiver = null
    }

    private fun startClient(address: String, clientName: String, path: String = "/sendspin") {
        Log.i(TAG, "Starting Sendspin client: address=$address, path=$path, name=$clientName")

        // Audio player is created when stream starts (needs format info from server)
        val sendspinClient = SendSpinClient(
            context = this,
            deviceName = clientName,
            callback = ClientCallback()
        )
        client = sendspinClient

        // Connect to local MA Sendspin server
        sendspinClient.connect(address, path)
        activeClient = sendspinClient
    }

    private fun destroyClient() {
        activeClient = null
        activeAudioPlayer = null
        client?.destroy()
        client = null
        audioPlayer?.stop()
        audioPlayer = null
    }

    fun getClient(): SendSpinClient? = client
    fun getAudioPlayer(): SyncAudioPlayer? = audioPlayer

    /** Callback for music player UI updates. Called on WebSocket thread — post to main thread. */
    var onPlayerDataChanged: ((com.dashieapp.Dashie.halite.music.MusicPlayerData) -> Unit)? = null

    // Accumulated player state from Sendspin events
    private var currentTrack = ""
    private var currentArtist = ""
    private var currentAlbum = ""
    private var currentArtworkUrl: String? = null
    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    private var currentPlaybackSpeed = 0  // 0 = stopped, 1000 = normal
    private var _currentIsPlaying = false
    private var currentIsPlaying: Boolean
        get() = _currentIsPlaying
        set(value) {
            if (value != _currentIsPlaying) {
                android.util.Log.i(TAG, "🎵 [SS-STATE] currentIsPlaying: $_currentIsPlaying → $value", Throwable("stack trace"))
            }
            _currentIsPlaying = value
        }
    private var lastPositionUpdateAt = 0L  // SystemClock.elapsedRealtime() when positionMs was last set
    private var currentVolume = 100
    private var currentMuted = false
    private var currentGroupName = ""
    private var registeredDeviceName = ""  // Name this device registered as with Sendspin
    private val pushHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPush: Runnable? = null

    private val USER_COMMAND_DEBOUNCE_MS = 4000L

    private fun isInUserCommandDebounce(): Boolean {
        return System.currentTimeMillis() - lastUserCommandTimestamp < USER_COMMAND_DEBOUNCE_MS
    }

    private fun pushPlayerData() {
        // Debounce: coalesce rapid updates (e.g., metadata + state + group all arrive within ms)
        pendingPush?.let { pushHandler.removeCallbacks(it) }
        val runnable = Runnable { doPushPlayerData() }
        pendingPush = runnable
        pushHandler.postDelayed(runnable, 50)  // 50ms debounce
    }

    /** Get the current device volume as 0-100 percentage. */
    private fun getDeviceVolumePercent(): Int {
        return try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val current = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max > 0) (current * 100 / max) else 50
        } catch (_: Exception) { 50 }
    }

    /** Report the device's current volume to the Sendspin server. */
    private fun syncDeviceVolumeToServer() {
        val devicePct = getDeviceVolumePercent()
        currentVolume = devicePct
        Log.i(TAG, "Syncing device volume to MA: $devicePct%")
        client?.setVolume(devicePct / 100.0)
        pushPlayerData()
    }

    // ContentObserver to detect device volume changes (hardware buttons, Android sidebar)
    private var volumeObserver: android.database.ContentObserver? = null

    private fun startVolumeObserver() {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        volumeObserver = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val newPct = getDeviceVolumePercent()
                if (newPct != currentVolume) {
                    Log.d(TAG, "Device volume changed: $currentVolume → $newPct")
                    currentVolume = newPct
                    client?.setVolume(newPct / 100.0)
                    pushPlayerData()
                }
            }
        }
        contentResolver.registerContentObserver(
            android.provider.Settings.System.CONTENT_URI, true, volumeObserver!!
        )
    }

    private fun stopVolumeObserver() {
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeObserver = null
    }

    /**
     * Set the Android device speaker volume (STREAM_MUSIC).
     * @param percent 0-100
     */
    private fun applyDeviceVolume(percent: Int) {
        // Voice-ducked: no stream writes — TTS/Gemini Live share STREAM_MUSIC (VoiceDuckController).
        if (VoiceDuckController.active) { Log.i(TAG, "Volume $percent% suppressed (voice duck)"); return }
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val targetVol = (percent * maxVol / 100).coerceIn(0, maxVol)
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
            Log.d(TAG, "Device volume set to $targetVol/$maxVol ($percent%)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set device volume: ${e.message}")
        }
    }

    /**
     * Freeze the current interpolated position as the new baseline.
     * Call when pausing to prevent position snapping back to last server update.
     */
    private fun freezePosition() {
        if (lastPositionUpdateAt > 0) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastPositionUpdateAt
            currentPositionMs += elapsed
            lastPositionUpdateAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun doPushPlayerData() {
        // Check for pending position reset (set before play_media to prevent stale position)
        if (pendingPositionReset) {
            pendingPositionReset = false
            currentPositionMs = 0
            lastPositionUpdateAt = android.os.SystemClock.elapsedRealtime()
        }

        // Interpolate position when playing
        val interpolatedPositionMs = if (currentIsPlaying && lastPositionUpdateAt > 0) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - lastPositionUpdateAt
            currentPositionMs + elapsed
        } else {
            currentPositionMs
        }

        val durSec = (currentDurationMs / 1000).toInt()
        val posSec = (interpolatedPositionMs / 1000).toInt()
        if (currentTrack.isNotEmpty()) {
            Log.d(TAG, "🎵 [SS-PUSH] track='${currentTrack.take(25)}' playing=$currentIsPlaying pos=$posSec dur=$durSec (rawDurMs=$currentDurationMs)")
        }
        val data = com.dashieapp.Dashie.halite.music.MusicPlayerData(
            trackName = currentTrack,
            artistName = currentArtist,
            albumName = currentAlbum,
            albumArtUrl = currentArtworkUrl,
            isPlaying = currentIsPlaying,
            positionSeconds = posSec,
            durationSeconds = durSec,
            volumeLevel = currentVolume / 100f,
            isVolumeMuted = currentMuted,
            entityId = "sendspin_${SendspinSettings.getPlayerId()}",
            friendlyName = currentGroupName.ifEmpty { registeredDeviceName.ifEmpty { Build.MODEL } }
        )
        onPlayerDataChanged?.invoke(data)
    }

    /**
     * Callback that bridges SendSpinClient events to the SyncAudioPlayer.
     * Creates/destroys the audio player on stream start/end.
     * Also updates the notification with connection/playback status.
     */
    private inner class ClientCallback : SendSpinClient.Callback {

        private var decoder: com.dashieapp.Dashie.halite.music.sendspin.decoder.AudioDecoder? = null

        override fun onServerDiscovered(name: String, address: String) {
            Log.i(TAG, "Server discovered: $name at $address")
        }

        override fun onConnected(serverName: String) {
            Log.i(TAG, "Connected to: $serverName")
            updateNotification("Connected to $serverName")
            connectionManager?.onConnected()
            // Push idle state so UI transitions from "connecting" to "idle-connected"
            pushPlayerData()
            // Sync MA volume to match the device's current volume
            syncDeviceVolumeToServer()
        }

        override fun onDisconnected(wasUserInitiated: Boolean, wasReconnectExhausted: Boolean) {
            Log.i(TAG, "Disconnected (userInitiated=$wasUserInitiated, exhausted=$wasReconnectExhausted)")
            audioPlayer?.stop()
            if (wasReconnectExhausted) {
                updateNotification("Connection lost")
                connectionManager?.onDisconnected()
            }
        }

        override fun onStateChanged(state: String) {
            val nowPlaying = state == "playing"
            Log.i(TAG, "🎵 [SS-STATE] onStateChanged: '$state' → playing=$nowPlaying (was=$currentIsPlaying)")
            if (currentIsPlaying && !nowPlaying) freezePosition()
            if (!currentIsPlaying && nowPlaying) lastPositionUpdateAt = android.os.SystemClock.elapsedRealtime()
            applyExternalPlayState(currentIsPlaying, nowPlaying, "onStateChanged", state)
            currentIsPlaying = nowPlaying
            if (!isInUserCommandDebounce()) pushPlayerData()
        }

        override fun onGroupUpdate(groupId: String, groupName: String, playbackState: String) {
            val nowPlaying = playbackState == "playing"
            Log.i(TAG, "🎵 [SS-STATE] onGroupUpdate: '$playbackState' → playing=$nowPlaying (was=$currentIsPlaying) group=$groupName")
            currentGroupName = groupName
            if (currentIsPlaying && !nowPlaying) freezePosition()
            if (!currentIsPlaying && nowPlaying) lastPositionUpdateAt = android.os.SystemClock.elapsedRealtime()
            applyExternalPlayState(currentIsPlaying, nowPlaying, "onGroupUpdate", playbackState)
            currentIsPlaying = nowPlaying
            if (!isInUserCommandDebounce()) pushPlayerData()
        }

        /**
         * Drive the LOCAL audio player from an EXTERNAL play-state change.
         *
         * 🔴 The prod beta defect (M-status case 3, diagnostics 6b8eeae2, "5-10s delay when
         * pausing music"): Dashie's own UI pause goes MusicCommandRouter.playPause() →
         * activeAudioPlayer.pause(), which is instant. Every OTHER origin — Music Assistant, HA,
         * another client — arrives here as a server state update, which froze the position and
         * repainted the UI but **never touched the audio player**. So the stream kept playing
         * until the read-ahead drained; 5-10s is MA's buffer depth. The UI said paused while
         * sound continued, which is why it reads as "delay" rather than "broken".
         *
         * Idempotent by construction, so the echo of a LOCAL pause costs nothing:
         * SyncAudioPlayer.pause() is flag-guarded, and resume() explicitly handles the
         * not-actually-paused case (it even re-plays a stalled AudioTrack).
         *
         * pause(), NOT enterIdle() — enterIdle drops the queue, which would turn a pause into a
         * stop and lose the resume point.
         */
        private fun applyExternalPlayState(wasPlaying: Boolean, nowPlaying: Boolean, origin: String, rawState: String) {
            if (wasPlaying && !nowPlaying) {
                Log.i(TAG, "🎵 [SS-STATE] $origin paused externally ('$rawState') — stopping local audio")
                // PersistentLog, not logcat: MISSING from a 15k-line prod music-bug report (M-status
                // case 3). rawState is verbatim and is the point: locally (MA 2.8.8) every pause is
                // 'stopped' + a stream/end that stops audio itself, masking the defect; a true
                // 'paused' with no stream/end is the reporter's. PAIR with the stream/end marker.
                PersistentLog.info("SENDSPIN", "Playback PAUSED externally ($origin, state='$rawState')")
                audioPlayer?.pause()
            } else if (!wasPlaying && nowPlaying) {
                Log.i(TAG, "🎵 [SS-STATE] $origin resumed externally ('$rawState') — resuming local audio")
                PersistentLog.info("SENDSPIN", "Playback RESUMED externally ($origin, state='$rawState')")
                audioPlayer?.resume()
            }
        }

        override fun onMetadataUpdate(
            title: String, artist: String, album: String,
            artworkUrl: String, durationMs: Long, positionMs: Long, playbackSpeed: Int
        ) {
            Log.i(TAG, "Now playing: $title by $artist (durMs=$durationMs posMs=$positionMs speed=$playbackSpeed)")
            updateNotification("$title — $artist")
            // Detect track change — reset position tracking
            val trackChanged = title.isNotEmpty() && title != currentTrack
            if (title.isNotEmpty()) currentTrack = title
            if (artist.isNotEmpty()) currentArtist = artist
            if (album.isNotEmpty()) currentAlbum = album
            if (artworkUrl.isNotEmpty()) currentArtworkUrl = artworkUrl
            val prevDurMs = currentDurationMs
            if (durationMs > 0) currentDurationMs = durationMs
            if (durationMs != prevDurMs) Log.i(TAG, "🎵 [SS-DUR] duration changed: ${prevDurMs}ms → ${durationMs}ms (track='${title.take(25)}')")

            if (trackChanged) {
                // New track — always accept the server position
                currentPositionMs = positionMs
                lastPositionUpdateAt = android.os.SystemClock.elapsedRealtime()
            } else {
                // Same track — accept server position unless it's a small backward jump (pause artifact).
                // A large backward jump (>5s behind current) means restart/seek — always accept.
                val interpolated = if (currentIsPlaying && lastPositionUpdateAt > 0) {
                    currentPositionMs + (android.os.SystemClock.elapsedRealtime() - lastPositionUpdateAt)
                } else {
                    currentPositionMs
                }
                val diff = positionMs - interpolated
                val isRestart = diff < -5000  // Jumped back more than 5s = restart/seek
                if (isRestart || diff > -2000 || interpolated == 0L) {
                    currentPositionMs = positionMs
                    lastPositionUpdateAt = android.os.SystemClock.elapsedRealtime()
                }
            }
            currentPlaybackSpeed = playbackSpeed
            // Don't infer play state from playbackSpeed — server/state and group/update
            // are the authoritative sources for play/pause. playbackSpeed > 0 just means
            // the metadata describes a track that *can* play, not that it *is* playing.
            pushPlayerData()
        }

        override fun onArtwork(imageData: ByteArray) {}
        override fun onArtworkCleared() {}

        override fun onError(message: String) {
            Log.e(TAG, "Client error: $message")
            updateNotification("Error: $message")
        }

        override fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, codecHeader: ByteArray?) {
            Log.i(TAG, "Stream start: codec=$codec, rate=$sampleRate, ch=$channels, bits=$bitDepth, header=${codecHeader?.size ?: 0} bytes")

            // Create and configure decoder for the stream's codec
            val newDecoder = AudioDecoderFactory.create(codec)
            newDecoder.configure(sampleRate, channels, bitDepth, codecHeader)
            decoder?.release()
            decoder = newDecoder

            // Create audio player with the time filter from the client for synchronized playback
            val timeFilter = client?.getTimeFilter() ?: return
            val existingPlayer = audioPlayer
            if (existingPlayer != null && existingPlayer.matchesFormat(sampleRate, channels, bitDepth)) {
                // Reuse existing player if format matches (e.g., track change)
                existingPlayer.clearBuffer()
                existingPlayer.start()  // Restart playback loop (may have been stopped)
            } else {
                existingPlayer?.release()
                val player = SyncAudioPlayer(
                    timeFilter = timeFilter,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitDepth = bitDepth
                )
                player.initialize()
                player.start()
                audioPlayer = player
                activeAudioPlayer = player
            }
        }

        override fun onStreamClear() {
            audioPlayer?.clearBuffer()
        }

        override fun onStreamEnd() {
            // Second half of the pause diagnostic. stream/end stops audio by itself (enterIdle
            // flushes queue AND AudioTrack), so its PRESENCE is why pause is instant here and its
            // ABSENCE is the reporter's drain — indistinguishable in a report without this marker.
            PersistentLog.info("SENDSPIN", "stream/end received — audio stopped (idle)")
            // Enter idle instead of full stop — keeps AudioTrack alive for quick restart
            audioPlayer?.enterIdle()
            currentIsPlaying = false
            pushPlayerData()
        }

        override fun onAudioChunk(serverTimeMicros: Long, audioData: ByteArray) {
            // Decode the encoded audio to PCM, then queue for synchronized playback
            val pcmData = decoder?.decode(audioData) ?: audioData
            audioPlayer?.queueChunk(serverTimeMicros, pcmData)
        }

        override fun onVolumeChanged(volume: Int) {
            Log.d(TAG, "Volume: $volume")
            currentVolume = volume
            applyDeviceVolume(volume)
            pushPlayerData()
        }

        override fun onMutedChanged(muted: Boolean) {
            Log.d(TAG, "Muted: $muted")
            currentMuted = muted
            if (muted) {
                applyDeviceVolume(0)
            } else {
                applyDeviceVolume(currentVolume)
            }
            pushPlayerData()
        }

        override fun onSyncOffsetApplied(offsetMs: Double, source: String) {
            Log.d(TAG, "Sync offset: ${offsetMs}ms from $source")
        }

        override fun onNetworkChanged() {}

        override fun onReconnecting(attempt: Int, serverName: String) {
            Log.i(TAG, "Reconnecting to $serverName (attempt $attempt)")
            updateNotification("Reconnecting ($attempt)...")
        }

        override fun onReconnected() {
            Log.i(TAG, "Reconnected!")
        }
    }

    fun setConnectionManager(manager: SendspinConnectionManager) {
        connectionManager = manager
    }

    // ========== Notification ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sendspin Audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Multi-room audio speaker active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("${getString(R.string.brand_name)} Speaker")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Post the foreground notification and promote the service (idempotent). Called from
     * both onCreate and onStartCommand so the ~5s startForegroundService() deadline is met.
     */
    private fun promoteToForeground(text: String) {
        val notification = createNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val notification = createNotification(text)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }
}
