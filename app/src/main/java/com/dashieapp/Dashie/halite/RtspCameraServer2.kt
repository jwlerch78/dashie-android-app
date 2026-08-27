package com.dashieapp.Dashie.halite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.diagnostics.DiagnosticBuffer
import com.dashieapp.Dashie.halite.diagnostics.MediaCodecCapabilities
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.audio.SharedBufferAudioSource
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.utils.CodecUtil
import com.pedro.rtspserver.RtspServerStream

/**
 * RTSP Camera Server v2 for Dashie Lite.
 *
 * Uses RtspServerStream with a custom Camera2MotionVideoSource that provides
 * frames to both the RTSP encoder AND an ImageReader for motion detection.
 *
 * This solves the limitation of the original RtspCameraServer which used
 * FPS-based motion detection (unreliable) because RootEncoder's internal
 * camera management doesn't expose frames for analysis.
 *
 * Key features:
 * - Hardware-accelerated H.264 encoding via MediaCodec
 * - Real pixel-based motion detection from camera frames
 * - Multi-client RTSP server support
 * - Single camera instance serves both streaming and motion detection
 *
 * Stream URL: rtsp://[device-ip]:[port]/
 */
class RtspCameraServer2(
    private val context: Context,
    private val prefs: HalitePreferences
) : ConnectChecker {

    companion object {
        private const val TAG = "RtspCameraServer2"

        // Buffer size for RTSP sending queue (default RootEncoder is 400 frames!)
        // At 10 FPS, 50 frames = 5 seconds of buffer - enough for network jitter
        // but won't balloon memory during grace period before pause kicks in.
        // This prevents ~280MB memory spike we saw during 30s grace periods.
        private const val RTSP_CACHE_SIZE = 50

        // Socket health watchdog
        private const val SOCKET_CHECK_INTERVAL_MS = 30_000L   // 30 seconds between checks
        private const val SOCKET_GRACE_PERIOD_MS = 10_000L     // Wait after start before first check
        // TCP-connect timeout for the localhost probe. Bumped from 3s → 8s after
        // observing spurious failures on memory-pressured Android 8 devices
        // (Lenovo StarView, ~2GB RAM with active swap): a healthy localhost
        // connect completes in <50ms, but under swap pressure / thread starvation
        // a 3s budget regularly times out and causes a death-spiral of restarts.
        // 8s gives the kernel + RTSP accept thread enough headroom on the
        // weakest devices while still detecting real ONN-stick socket deaths.
        private const val SOCKET_CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_WATCHDOG_RESTARTS = 3             // Give up after this many restarts

    }

    // Custom video source with motion detection
    private var camera2Source: Camera2MotionVideoSource? = null

    // RTSP server stream
    private var rtspServer: RtspServerStream? = null

    // Server state
    private var isServerRunning = false
    private var isStreaming = false
    private var clientCount = 0

    // Error state
    private var hasFailed = false
    private var failureReason: String? = null

    // Shared audio buffer (for sharing microphone with wake word detection)
    private var sharedAudioBuffer: SharedAudioBuffer? = null
    private var useSharedAudio = false

    // Motion callback passthrough
    private var motionCallback: ((Boolean) -> Unit)? = null

    // Face frame callback (stored at this level so it survives auto-restarts)
    private var faceFrameCallback: ((android.graphics.Bitmap, Int) -> Unit)? = null

    // HA sensor face frame callback (parallel to wake face detector, also survives restarts)
    private var haSensorFaceFrameCallback: ((android.graphics.Bitmap, Int) -> Unit)? = null

    // Motion threshold override (stored so it survives auto-restarts, e.g. face mode 1.5%)
    private var motionThresholdOverride: Double? = null

    // Auto-restart state (for camera disconnect recovery)
    private var lastRestartAttempt = 0L
    private val RESTART_DEBOUNCE_MS = 10_000L  // Don't restart more than once every 10 seconds
    private var isRestarting = false
    private var consecutiveRestartFailures = 0
    private val MAX_RESTART_ATTEMPTS = 5  // After this many failures, give up (camera is broken)

    // When true, camera disconnect callbacks are suppressed (intentional stop, not an error)
    private var stoppingIntentionally = false

    // Socket health watchdog — detects when RTSP TCP socket silently dies
    // while camera pipeline keeps running (isServerRunning=true but port not listening)
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunning = false
    private var lastServerStartTime = 0L
    private var watchdogRestartCount = 0
    private var watchdogExhausted = false

    // Set by restartServer() so the release() it triggers internally doesn't
    // reset the watchdog counter — that reset is what made MAX_WATCHDOG_RESTARTS
    // never fire in practice (counter went 0→1→reset→1→reset forever, observed
    // in StarView OOM crash report). External release() calls (user stops RTSP,
    // server destruction) still reset cleanly.
    private var inWatchdogRestart = false

    // Single dedicated thread for TCP-connect probes. Previously each probe
    // spawned a fresh Thread{}.start() (one per 30s), accumulating across the
    // process lifetime — over a 117-minute uptime that's ~234 thread spawns
    // just for socket probing, on top of restart-cycle threads. The StarView
    // OOM crash showed pthread_create EAGAIN failing during NSD init after the
    // process exhausted its thread budget; this is one of the contributing sources.
    private val watchdogProbeExecutor: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "RtspWatchdogProbe").apply { isDaemon = true }
        }
    }

    /**
     * Set the shared audio buffer for sharing microphone with wake word detection.
     * Must be called BEFORE initialize() to take effect.
     *
     * @param buffer The SharedAudioBuffer from AudioCaptureService, or null to use dedicated mic
     */
    fun setSharedAudioBuffer(buffer: SharedAudioBuffer?) {
        sharedAudioBuffer = buffer
        useSharedAudio = buffer != null
        Log.i(TAG, "🔊 setSharedAudioBuffer: id=${if (buffer != null) System.identityHashCode(buffer) else "null"} (useSharedAudio=$useSharedAudio)")
    }

    /**
     * Initialize the RTSP server with custom Camera2 source for motion detection.
     */
    fun initialize() {
        if (rtspServer != null) {
            Log.w(TAG, "Server already initialized")
            return
        }

        try {
            Log.i(TAG, "Initializing RtspCameraServer2 with Camera2MotionVideoSource...")

            // Create our custom video source with motion detection
            camera2Source = Camera2MotionVideoSource(context)

            // Set motion threshold from preferences
            val threshold = prefs.screensaver.cameraWakeThresholdDouble
            Log.i(TAG, "Setting motion threshold to ${threshold}% (from prefs: ${prefs.screensaver.cameraWakeThresholdTenths} tenths)")
            camera2Source?.setMotionThreshold(threshold)

            // Set up camera disconnect callback for auto-recovery
            // This handles USB camera disconnects, camera errors, etc.
            camera2Source?.setCameraDisconnectCallback { reason ->
                Log.w(TAG, "Camera disconnect detected: $reason")
                DiagnosticBuffer.warn("RTSP", "Camera disconnect: $reason")
                handleCameraDisconnect(reason)
            }

            // Note: Motion callback should be set AFTER initialize() via setMotionCallback()
            // to ensure camera2Source exists. See DashieServiceManager.startRtspServerV2()

            // Create audio source - use SharedBufferAudioSource if shared buffer is provided,
            // otherwise use MicrophoneSource (which creates its own AudioRecord)
            // Wrap in try-catch to handle missing RECORD_AUDIO permission gracefully
            val audioSource: AudioSource = try {
                if (useSharedAudio && sharedAudioBuffer != null) {
                    Log.i(TAG, "Using SharedBufferAudioSource (sharing mic with wake word detection)")
                    SharedBufferAudioSource(sharedAudioBuffer!!)
                } else {
                    Log.i(TAG, "Using MicrophoneSource (dedicated AudioRecord)")
                    MicrophoneSource()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create audio source (${e.message}) - RTSP will be video-only")
                // Use a NoOpAudioSource that doesn't actually record audio
                // This allows RTSP to work for camera preview even without microphone permission
                object : AudioSource() {
                    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
                        return true  // Pretend initialization succeeded
                    }
                    override fun start(getMicrophoneData: com.pedro.encoder.input.audio.GetMicrophoneData) {
                        // No-op - don't actually start recording
                    }
                    override fun stop() {
                        // No-op
                    }
                    override fun isRunning(): Boolean = false
                    override fun release() {
                        // No-op
                    }
                }
            }

            // Create RTSP server stream with our custom video source
            rtspServer = RtspServerStream(
                context,
                prefs.camera.rtspPort,
                this,  // ConnectChecker
                camera2Source!!,
                audioSource
            )

            Log.i(TAG, "RtspCameraServer2 initialized on port ${prefs.camera.rtspPort} (sharedAudio=$useSharedAudio)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}", e)
            hasFailed = true
            failureReason = e.message
        }
    }

    /**
     * Start the RTSP server and camera streaming.
     */
    fun startServer(): Boolean {
        val server = rtspServer
        if (server == null) {
            Log.e(TAG, "Cannot start: server not initialized")
            return false
        }

        if (isServerRunning) {
            Log.w(TAG, "Server already running")
            return true
        }

        if (hasFailed) {
            Log.w(TAG, "Server previously failed: $failureReason")
            return false
        }

        // Clear intentional stop flag — we're starting fresh, auto-restart should work
        stoppingIntentionally = false

        // Note: Pre-flight camera check was removed - it caused buffer contention issues on
        // devices with emulated/external cameras (e.g., ONN stick). The frame validation timeout
        // in Camera2MotionVideoSource handles broken cameras without the double-open sequence.

        var width = prefs.camera.rtspResolutionWidth
        var height = prefs.camera.rtspResolutionHeight
        val fps = prefs.camera.rtspFps
        val bitrate = prefs.camera.rtspBitrate

        // Portrait mode: swap encoder dimensions so the stream matches a portrait-mounted device.
        // Without this, a portrait device's camera frames get squished into landscape dimensions.
        val portraitStream = prefs.camera.rtspPortraitStream
        if (portraitStream && width > height) {
            val temp = width
            width = height
            height = temp
            Log.i(TAG, "Portrait stream: swapped encoder dimensions to ${width}x${height}")
            DiagnosticBuffer.info("RTSP", "Portrait mode: encoder ${width}x${height}")
        }

        // Encoder rotation metadata stays at 0 — we never rely on H.264 rotation tags
        // because HA's native stream integration and Fully Kiosk ignore them. Instead,
        // when the user enables "Rotate Stream 180°", we apply pixel-level rotation via
        // RotateVideoFilter in the GPU pre-encode pipeline (see filter setup further down).
        val sensorOrientation = camera2Source?.querySensorOrientation() ?: 0
        val displayRotationDegrees = getDisplayRotationDegrees()
        val isFrontCamera = camera2Source?.isFrontCamera() ?: true
        val rotateStream180 = prefs.camera.rtspRotateStream180
        val rotation = 0  // Encoder metadata rotation — always 0, pixels are rotated via filter

        Log.i(TAG, "Stream orientation: sensor=$sensorOrientation° display=$displayRotationDegrees° front=$isFrontCamera portrait=$portraitStream rotate180=$rotateStream180")
        DiagnosticBuffer.info("RTSP", "Orientation: sensor=$sensorOrientation° display=$displayRotationDegrees° portrait=$portraitStream rotate180=$rotateStream180")
        // Keyframe every 1 second for better HA compatibility
        // HA's stream component needs keyframes to start decoding; shorter interval
        // means less wait time when a client connects. Tradeoff: ~5% higher bandwidth.
        val iFrameInterval = 1

        // Check if software encoding is forced (for devices with buggy hardware encoders)
        val forceSoftware = prefs.camera.rtspForceSoftwareEncoding
        val encodingMode = if (forceSoftware) "SOFTWARE" else "HARDWARE"

        Log.i(TAG, "Starting RTSP server: ${width}x${height}@${fps}fps, ${bitrate/1_000_000}Mbps, rotation=$rotation (sensorOrientation=$sensorOrientation), encoding=$encodingMode")
        DiagnosticBuffer.info("RTSP", "Starting server: ${width}x${height}@${fps}fps, ${bitrate/1_000_000}Mbps, encoding=$encodingMode")

        // Log encoder capabilities for diagnostics and clamp resolution to encoder limits
        val caps = MediaCodecCapabilities.getH264EncoderCapabilities()
        if (caps != null) {
            DiagnosticBuffer.info("RTSP", "Encoder: ${caps.encoderName} (HW: ${caps.isHardwareAccelerated})")
            val resSupported = caps.isResolutionSupported(width, height)
            val fpsSupported = fps in caps.supportedFrameRates
            if (!resSupported) {
                val clampedWidth = width.coerceIn(caps.widthRange)
                val clampedHeight = height.coerceIn(caps.heightRange)
                DiagnosticBuffer.warn("RTSP", "Resolution ${width}x${height} outside encoder range (${caps.widthRange.first}-${caps.widthRange.last} x ${caps.heightRange.first}-${caps.heightRange.last}), clamping to ${clampedWidth}x${clampedHeight}")
                Log.w(TAG, "Resolution ${width}x${height} outside encoder range, clamping to ${clampedWidth}x${clampedHeight}")
                width = clampedWidth
                height = clampedHeight
            }
            if (!fpsSupported) {
                DiagnosticBuffer.warn("RTSP", "FPS $fps may not be supported (range: ${caps.supportedFrameRates.first}-${caps.supportedFrameRates.last})")
            }
        }

        // Validate resolution for potential issues
        val validation = MediaCodecCapabilities.validateResolution(width, height)
        if (validation.warnings.isNotEmpty()) {
            DiagnosticBuffer.warn("RTSP", "Resolution warnings: ${validation.warnings.joinToString("; ")}")
        }
        DiagnosticBuffer.info("RTSP", "Aspect ratio: ${validation.aspectRatioName}")

        // Log camera capabilities
        val cameraCaps = MediaCodecCapabilities.getCameraCapabilities(context, useFrontCamera = true)
        if (cameraCaps != null) {
            DiagnosticBuffer.info("RTSP", "Camera ratios: ${cameraCaps.nativeAspectRatios.joinToString(", ")}")
            if (!cameraCaps.supportsAspectRatio(width, height)) {
                DiagnosticBuffer.warn("RTSP", "Camera may not natively support ${validation.aspectRatioName} - possible scaling/cropping")
            }
            val bestMatch = cameraCaps.findBestMatch(width, height)
            if (bestMatch != null && (bestMatch.width != width || bestMatch.height != height)) {
                DiagnosticBuffer.info("RTSP", "Best camera match: ${bestMatch.width}x${bestMatch.height}")
            }
        }

        // Run comprehensive diagnostic check for potential issues
        val diagnostic = MediaCodecCapabilities.diagnoseVideoConfig(context, width, height, fps, bitrate, useFrontCamera = true)
        if (!diagnostic.isLikelyOk) {
            diagnostic.issues.forEach { DiagnosticBuffer.error("RTSP", "Config issue: $it") }
            diagnostic.warnings.forEach { DiagnosticBuffer.warn("RTSP", "Config warning: $it") }
            diagnostic.recommendations.take(2).forEach { DiagnosticBuffer.info("RTSP", "Recommendation: $it") }
        } else {
            DiagnosticBuffer.info("RTSP", "Configuration diagnostic: OK")
        }

        try {
            // Determine encoding mode and prepare video encoder
            var usingSoftware = forceSoftware
            var prepareSuccess = false

            if (forceSoftware) {
                // User explicitly requested software encoding
                Log.i(TAG, "Forcing SOFTWARE video encoding (user preference)")
                server.forceCodecType(CodecUtil.CodecType.SOFTWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
                DiagnosticBuffer.info("RTSP", "Using SOFTWARE encoding (user preference)")
                prepareSuccess = server.prepareVideo(width, height, bitrate, fps, iFrameInterval, rotation)
            } else {
                // Try FIRST_COMPATIBLE_FOUND first (lets RootEncoder pick the best encoder)
                Log.i(TAG, "Trying FIRST_COMPATIBLE_FOUND encoding...")
                server.forceCodecType(CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
                prepareSuccess = server.prepareVideo(width, height, bitrate, fps, iFrameInterval, rotation)

                if (!prepareSuccess) {
                    // Try hardware encoding explicitly
                    Log.w(TAG, "FIRST_COMPATIBLE failed, trying HARDWARE encoding...")
                    server.forceCodecType(CodecUtil.CodecType.HARDWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
                    prepareSuccess = server.prepareVideo(width, height, bitrate, fps, iFrameInterval, rotation)
                }

                if (!prepareSuccess) {
                    // Fall back to software encoding
                    Log.w(TAG, "HARDWARE encoding failed, falling back to SOFTWARE encoding...")
                    server.forceCodecType(CodecUtil.CodecType.SOFTWARE, CodecUtil.CodecType.FIRST_COMPATIBLE_FOUND)
                    DiagnosticBuffer.info("RTSP", "Auto-fallback to SOFTWARE encoding")
                    prepareSuccess = server.prepareVideo(width, height, bitrate, fps, iFrameInterval, rotation)
                    usingSoftware = true
                }
            }

            if (!prepareSuccess) {
                Log.e(TAG, "Failed to prepare video encoder (all methods failed)")
                DiagnosticBuffer.error("RTSP", "Failed to prepare video encoder: ${width}x${height}@${fps}fps (all methods)")
                return false
            }

            // Log encoder info for diagnostics
            val encoderInfo = MediaCodecCapabilities.getH264EncoderCapabilities()
            val encoderDesc = if (encoderInfo != null) {
                "${encoderInfo.encoderName} (hw=${encoderInfo.isHardwareAccelerated})"
            } else {
                "unknown"
            }
            Log.i(TAG, "Video encoder prepared successfully: $encoderDesc, software_mode=$usingSoftware")
            DiagnosticBuffer.info("RTSP", "Encoder: $encoderDesc, software_mode=$usingSoftware")
            // Log to PersistentLog so it survives OOM kills
            PersistentLog.info("RTSP_CONFIG", "Started: ${width}x${height}@${fps}fps, ${bitrate/1_000_000}Mbps, encoder=$encoderDesc, software=$usingSoftware")

            // Prepare audio encoder
            // Use 16000Hz when sharing mic with wake word detection, 44100Hz otherwise
            val audioSampleRate = if (useSharedAudio) 16000 else 44100
            Log.i(TAG, "Preparing audio: sampleRate=$audioSampleRate, sharedAudio=$useSharedAudio")
            try {
                if (!server.prepareAudio(audioSampleRate, false, 64 * 1000)) {
                    Log.w(TAG, "Failed to prepare audio - continuing without audio")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prepare audio (exception: ${e.message}) - continuing without audio")
            }

            // Start the stream (this starts camera and RTSP server)
            // Retry up to 3 times with backoff - socket may still be bound from previous process
            var startAttempt = 0
            val maxAttempts = 3
            var streamStarted = false

            while (startAttempt < maxAttempts && !streamStarted) {
                startAttempt++
                if (startAttempt > 1) {
                    val waitTime = startAttempt * 1000L
                    Log.i(TAG, "Retry $startAttempt/$maxAttempts - waiting ${waitTime}ms for socket release...")
                    DiagnosticBuffer.info("RTSP", "Retry $startAttempt - waiting for socket")
                    Thread.sleep(waitTime)
                }

                try {
                    server.startStream()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start stream (attempt $startAttempt): ${e.message}", e)
                    if (e.message?.contains("microphone") == true || e.message?.contains("audio") == true) {
                        Log.w(TAG, "Microphone error - RTSP audio will not be available (video-only stream)")
                    }
                    // Don't return false here - check if streaming succeeded despite the error
                }

                // Verify the stream actually started - RootEncoder's startStream() doesn't throw
                // on socket bind failure, so we need to check isStreaming after a brief delay
                Thread.sleep(500)  // Give RootEncoder time to bind socket
                streamStarted = server.isStreaming

                // Set the server's IP/port in the RTSP command manager so the
                // Content-Base header is correct (e.g. rtsp://192.168.1.50:8554/).
                // Without this, pedroSG94 returns Content-Base: rtsp://:0/ which
                // causes ExoPlayer to send SETUP to malformed URLs.
                if (streamStarted) {
                    try {
                        val deviceIp = getLocalIpAddress()
                        val rtspField = server.javaClass.getDeclaredField("rtspServer")
                        rtspField.isAccessible = true
                        val rtspServerObj = rtspField.get(server)
                        val cmdField = rtspServerObj.javaClass.getDeclaredField("serverCommandManager")
                        cmdField.isAccessible = true
                        val cmdManager = cmdField.get(rtspServerObj)
                        val setInfoMethod = cmdManager.javaClass.getMethod("setServerInfo", String::class.java, Int::class.javaPrimitiveType)
                        setInfoMethod.invoke(cmdManager, deviceIp, prefs.camera.rtspPort)
                        Log.i(TAG, "Set RTSP Content-Base to rtsp://$deviceIp:${prefs.camera.rtspPort}/")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not set RTSP server info: ${e.message}")
                    }
                }

                if (!streamStarted && startAttempt < maxAttempts) {
                    Log.w(TAG, "RTSP server failed to start (attempt $startAttempt) - will retry")
                    // Stop the failed attempt before retrying
                    try { server.stopStream() } catch (e: Exception) { /* ignore */ }
                }
            }

            if (!streamStarted) {
                Log.e(TAG, "RTSP server failed to start after $maxAttempts attempts")
                DiagnosticBuffer.error("RTSP", "Server failed to start after $maxAttempts attempts (socket bind failed?)")
                return false
            }

            if (startAttempt > 1) {
                Log.i(TAG, "RTSP server started successfully on attempt $startAttempt")
                DiagnosticBuffer.info("RTSP", "Server started on retry $startAttempt")
            }

            // Reduce cache/buffer size to prevent memory balloon during HA outages
            // Default RootEncoder cache is 400 frames - way too much for low-memory devices
            try {
                val streamClient = server.getStreamClient()
                streamClient.resizeCache(RTSP_CACHE_SIZE)
                Log.i(TAG, "Reduced RTSP cache to $RTSP_CACHE_SIZE frames (was 400)")
                DiagnosticBuffer.info("RTSP", "Cache reduced to $RTSP_CACHE_SIZE frames")
                PersistentLog.info("RTSP_CONFIG", "Cache: $RTSP_CACHE_SIZE frames")
            } catch (e: Exception) {
                Log.w(TAG, "Could not resize cache: ${e.message}")
                // This is important! If cache resize fails, we have 400 frames = potential OOM
                PersistentLog.warn("RTSP_CONFIG", "Cache resize FAILED - using default 400 frames: ${e.message}")
            }

            isServerRunning = true
            isStreaming = true

            Log.i(TAG, "RTSP server started successfully")
            Log.i(TAG, "Stream URL: rtsp://${getLocalIpAddress()}:${prefs.camera.rtspPort}/")
            DiagnosticBuffer.info("RTSP", "Server started: rtsp://${getLocalIpAddress()}:${prefs.camera.rtspPort}/")

            // Start socket health watchdog to auto-recover from silent socket death
            startSocketWatchdog()

            // Build the GPU filter chain for the encoder.
            // Two transforms may apply, in this order:
            //   1. RotateVideoFilter (180°) — corrects upside-down sensor mounts so the
            //      stream is upright for HA/Fully which ignore H.264 rotation metadata.
            //   2. HorizontalFlipFilter — un-mirrors front cameras (selfie mirror).
            // Order matters: rotate first so the flip operates on the upright image.
            // The rotation is auto-detected from sensor + display orientation up top.
            val disableMirrorCorrection = prefs.camera.rtspDisableMirrorCorrection
            val appliedFilters = mutableListOf<String>()

            try {
                val glInterface = server.getGlInterface()
                glInterface.clearFilters()

                // Portrait streaming: tell the GL layer the stream is portrait.
                //
                // Enabling "Portrait Stream" swaps the ENCODER dimensions to 1080x1920 (up top),
                // but RootEncoder's GL layer kept isPortrait=false. GlStreamInterface passes that
                // flag to MainRender.drawScreenEncoder(w, h, orientation, ...) as `orientation`,
                // so the landscape sensor frame got FIT into the portrait frame — landscape
                // content centred with black bars top/bottom, baked into the encoded frame.
                // That is the reported letterbox bug, and it reproduces on any device: nothing
                // ever told the GL layer the stream was portrait.
                //
                // setStreamIsPortrait(true) makes drawScreenEncoder orient the sensor frame to
                // the portrait frame and fill it — crop-to-fill, aspect preserved, no bars.
                //
                // This is HALF the fix and does not stand alone: it fills the frame with whatever
                // the camera produced, so it must be paired with capturing a NATIVE landscape
                // buffer (chooseLandscapeCaptureSize in Camera2MotionVideoSource). Without that,
                // the HAL squeezes the landscape frame into the portrait buffer and this happily
                // fills the frame with distorted pixels — bars gone, geometry wrong.
                //
                // Gotchas worth keeping:
                //  - setAspectRatioMode(Fill) is NOT the fix: it is only read by
                //    drawScreenPreview() (the on-device preview). drawScreenEncoder() never
                //    consults it, so it does nothing to the stream (verified, SM-X200).
                //  - A BaseFilterRender cannot fix it either: the fit happens AFTER the filter
                //    chain, so a filter zooms the content but the bars survive (also verified).
                //  - setStreamIsPortrait lives on the concrete GlStreamInterface, not on the
                //    GlInterface interface — hence the safe cast.
                //
                // Portrait only; the landscape path is untouched.
                if (portraitStream) {
                    val glStream = glInterface as? com.pedro.library.view.GlStreamInterface
                    if (glStream != null) {
                        glStream.setStreamIsPortrait(true)
                        Log.i(TAG, "Portrait: setStreamIsPortrait(true) — encoder draw treats stream as portrait")
                        DiagnosticBuffer.info("RTSP", "Portrait: setStreamIsPortrait(true)")
                    } else {
                        Log.w(TAG, "Portrait: GlInterface is not GlStreamInterface (${glInterface.javaClass.name}) — cannot set portrait, stream will letterbox")
                        DiagnosticBuffer.warn("RTSP", "Portrait: could not setStreamIsPortrait — stream will letterbox")
                    }
                }

                if (rotateStream180) {
                    glInterface.addFilter(RotateVideoFilter())
                    appliedFilters.add("RotateVideoFilter(180°)")
                    Log.i(TAG, "Filter chain: added 180° rotation (user preference)")
                    DiagnosticBuffer.info("RTSP", "180° rotation filter added (user preference)")
                }

                if (disableMirrorCorrection) {
                    Log.i(TAG, "Filter chain: skipping mirror correction (user preference)")
                    DiagnosticBuffer.info("RTSP", "Mirror correction disabled (user preference)")
                } else if (isFrontCamera) {
                    glInterface.addFilter(HorizontalFlipFilter())
                    appliedFilters.add("HorizontalFlipFilter")
                    Log.i(TAG, "Filter chain: added horizontal flip (front camera)")
                    DiagnosticBuffer.info("RTSP", "Horizontal flip filter added (front camera)")
                } else {
                    Log.i(TAG, "Filter chain: skipping horizontal flip (back/external camera)")
                }

                if (appliedFilters.isNotEmpty()) {
                    Log.i(TAG, "Applied ${appliedFilters.size} GL filter(s) to encoder pipeline: ${appliedFilters.joinToString()}")
                    DiagnosticBuffer.info("RTSP", "Filter chain: ${appliedFilters.joinToString()}")
                } else {
                    Log.i(TAG, "Filter chain: no filters needed (sensor upright, back camera)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply filter chain: ${e.message}")
                DiagnosticBuffer.warn("RTSP", "Failed to apply filter chain: ${e.message}")
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
            DiagnosticBuffer.error("RTSP", "Failed to start server: ${e.message}")
            hasFailed = true
            failureReason = e.message
            return false
        }
    }

    // Flag to indicate server is paused (keeps camera running but ignores clients)
    private var isPaused = false

    /**
     * Pause the RTSP server - keeps camera AND stream running for motion detection.
     * Use this when HA goes offline to prevent OOM from buffered frames.
     *
     * NOTE: We don't call stopStream() because that would stop the Camera2MotionVideoSource
     * and close the camera. Instead, we:
     * 1. Set isPaused flag to reject new client connections
     * 2. Clear the cache to free buffered frames
     * 3. Let the stream continue running (hardware encoding is low overhead)
     *
     * The cache will continue to fill but since we have RTSP_CACHE_SIZE=50 (5s at 10fps),
     * memory usage stays bounded. We can periodically clear it if needed.
     */
    fun pauseServer() {
        val server = rtspServer ?: return

        if (!isServerRunning) {
            Log.d(TAG, "Server not running, nothing to pause")
            return
        }

        if (isPaused) {
            Log.d(TAG, "Server already paused")
            return
        }

        try {
            // Clear the cache to free buffered frames
            clearCache()

            // Mark as paused - new client connections will be rejected
            isPaused = true
            isStreaming = false
            clientCount = 0

            // DON'T call stopStream() - that stops the camera!
            // The stream keeps running but frames are just cached (and periodically cleared)
            // This keeps the camera alive for motion detection

            Log.i(TAG, "RTSP server paused (camera still running for motion detection)")
            DiagnosticBuffer.info("RTSP", "Server paused (camera still running)")
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing server: ${e.message}", e)
            DiagnosticBuffer.error("RTSP", "Error pausing server: ${e.message}")
        }
    }

    /**
     * Resume the RTSP server after pause.
     *
     * IMPORTANT: We do a FULL RESTART because the simple resume approach
     * (just clearing flags) doesn't properly restore the video pipeline.
     * After a pause, only audio packets would flow - video would be black.
     *
     * This full restart approach is more reliable and ensures clients get
     * a working video stream.
     */
    fun resumeServer(): Boolean {
        if (!isPaused && isServerRunning) {
            Log.d(TAG, "Server not paused, nothing to resume")
            return true
        }

        Log.i(TAG, "RTSP server resuming via full restart...")
        DiagnosticBuffer.info("RTSP", "Server resuming (full restart)")

        // Do a full stop and restart to ensure video pipeline is properly restored
        return restartServer()
    }

    /**
     * Restart the RTSP server completely.
     * Useful when video stops flowing after a pause/resume cycle.
     */
    fun restartServer(): Boolean {
        Log.i(TAG, "RTSP server restarting...")
        DiagnosticBuffer.info("RTSP", "Server restarting")

        // Save motion callback BEFORE release() clears it
        val savedMotionCallback = motionCallback

        // Stop completely (releases camera and encoder).
        // Setting inWatchdogRestart suppresses the watchdog-state reset inside
        // release() so MAX_WATCHDOG_RESTARTS can actually accumulate.
        inWatchdogRestart = true
        stopServer()
        release()
        inWatchdogRestart = false

        // Reset error state so we can reinitialize
        hasFailed = false
        failureReason = null

        // Wait for camera HAL to fully release resources.
        // Camera2's close() is asynchronous — the HAL may still hold resources.
        // If we reopen too quickly, CameraService evicts the old (still-closing) client,
        // triggering onDisconnected on the NEW camera → infinite restart loop.
        // 3s is enough for Fire tablet, Samsung tablet, and ONN stick.
        Thread.sleep(3000)

        // Reinitialize and start
        try {
            initialize()
            if (hasFailed()) {
                Log.e(TAG, "Failed to reinitialize RTSP server: ${getFailureReason()}")
                DiagnosticBuffer.error("RTSP", "Restart failed: ${getFailureReason()}")
                return false
            }

            val success = startServer()
            if (success) {
                // Verify the server is actually streaming (not just "started")
                val actuallyStreaming = rtspServer?.isStreaming ?: false
                if (actuallyStreaming) {
                    Log.i(TAG, "✅ RTSP server restarted successfully (isStreaming=true)")
                    DiagnosticBuffer.info("RTSP", "Server restarted successfully")
                } else {
                    Log.w(TAG, "⚠️ RTSP server started but isStreaming=false - stream may not work")
                    DiagnosticBuffer.warn("RTSP", "Server started but not streaming - possible HAL issue")
                }

                // Re-apply motion callback if one was set (use saved callback since release() cleared it)
                savedMotionCallback?.let { callback ->
                    Log.i(TAG, "Re-applying motion callback after restart")
                    setMotionCallback(callback)
                }

                // Re-apply face frame callback (stored at this level, survives release())
                faceFrameCallback?.let { callback ->
                    Log.i(TAG, "Re-applying face frame callback after restart")
                    camera2Source?.setFaceFrameCallback(callback)
                }

                // Re-apply HA sensor face frame callback
                haSensorFaceFrameCallback?.let { callback ->
                    Log.i(TAG, "Re-applying HA sensor face frame callback after restart")
                    camera2Source?.setHaSensorFaceFrameCallback(callback)
                }

                // Re-apply motion threshold override (e.g. face mode 1.5%)
                motionThresholdOverride?.let { threshold ->
                    Log.i(TAG, "Re-applying motion threshold override: ${threshold}%")
                    camera2Source?.setMotionThreshold(threshold)
                }

                // Re-enable motion detection
                enableMotionDetection()
            } else {
                Log.e(TAG, "❌ Failed to start RTSP server after restart (startServer returned false)")
                DiagnosticBuffer.error("RTSP", "Restart failed: could not start server")
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting RTSP server: ${e.message}", e)
            DiagnosticBuffer.error("RTSP", "Restart error: ${e.message}")
            return false
        }
    }

    /**
     * Handle camera disconnect - attempt automatic recovery.
     * Called when the camera disconnects unexpectedly (USB disconnect, error, etc.)
     */
    private fun handleCameraDisconnect(reason: String) {
        // Don't auto-restart if we're being intentionally stopped/released
        if (stoppingIntentionally) {
            Log.i(TAG, "Camera disconnect during intentional stop - skipping auto-restart: $reason")
            return
        }

        val now = System.currentTimeMillis()

        // Check if we've exceeded max restart attempts (camera is probably broken)
        if (consecutiveRestartFailures >= MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "❌ Max restart attempts ($MAX_RESTART_ATTEMPTS) exceeded - camera appears broken, giving up")
            DiagnosticBuffer.error("RTSP", "Camera broken - giving up after $MAX_RESTART_ATTEMPTS failures: $reason")
            hasFailed = true
            failureReason = "Camera broken - no frames received after $MAX_RESTART_ATTEMPTS attempts"
            stopServer()
            return
        }

        // Debounce: don't restart too frequently
        if (now - lastRestartAttempt < RESTART_DEBOUNCE_MS) {
            Log.w(TAG, "Camera disconnect ignored - too soon since last restart (debounce)")
            return
        }

        // Prevent concurrent restart attempts
        if (isRestarting) {
            Log.w(TAG, "Camera disconnect ignored - restart already in progress")
            return
        }

        lastRestartAttempt = now
        isRestarting = true
        consecutiveRestartFailures++

        // Exponential backoff: 2s, 4s, 8s, 16s, 32s
        // Samsung "Camera disabled by policy" is transient and clears after ~5-10s,
        // so we need to wait long enough for it to clear.
        val delayMs = 2000L * (1L shl (consecutiveRestartFailures - 1).coerceAtMost(4))
        Log.i(TAG, "🔄 Auto-restarting RTSP server due to camera disconnect: $reason (attempt $consecutiveRestartFailures/$MAX_RESTART_ATTEMPTS, delay ${delayMs}ms)")
        DiagnosticBuffer.info("RTSP", "Auto-restart triggered ($consecutiveRestartFailures/$MAX_RESTART_ATTEMPTS, delay ${delayMs}ms): $reason")

        // Run restart on main thread after delay to let camera fully release
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val success = restartServer()
                if (success) {
                    Log.i(TAG, "✅ Auto-restart successful - camera recovered")
                    DiagnosticBuffer.info("RTSP", "Auto-restart successful")
                    // Delay resetting failure counter until camera has been stable for 10s.
                    // If the camera disconnects again immediately (restart loop), the counter
                    // keeps incrementing so we eventually hit MAX_RESTART_ATTEMPTS and stop.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (isServerRunning && !isRestarting) {
                            consecutiveRestartFailures = 0
                            Log.d(TAG, "Camera stable for 10s - reset restart failure counter")
                        }
                    }, 10_000)
                } else {
                    Log.e(TAG, "❌ Auto-restart failed (attempt $consecutiveRestartFailures)")
                    DiagnosticBuffer.error("RTSP", "Auto-restart failed (attempt $consecutiveRestartFailures)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Auto-restart exception: ${e.message}", e)
                DiagnosticBuffer.error("RTSP", "Auto-restart exception: ${e.message}")
            } finally {
                isRestarting = false
            }
        }, delayMs)
    }

    // ========== Socket Health Watchdog ==========
    // Periodically TCP-probes localhost:rtspPort to verify the RTSP socket is still
    // accepting connections. If the socket silently dies (common on ONN sticks),
    // triggers restartServer() to recover.

    private val socketCheckRunnable = object : Runnable {
        override fun run() {
            checkSocketHealth()
            if (watchdogRunning) {
                watchdogHandler.postDelayed(this, SOCKET_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun startSocketWatchdog() {
        stopSocketWatchdog()
        lastServerStartTime = System.currentTimeMillis()
        watchdogRunning = true
        watchdogHandler.postDelayed(socketCheckRunnable, SOCKET_GRACE_PERIOD_MS)
        Log.i(TAG, "Socket watchdog started (${SOCKET_CHECK_INTERVAL_MS / 1000}s interval, ${SOCKET_GRACE_PERIOD_MS / 1000}s grace)")
    }

    private fun stopSocketWatchdog() {
        if (watchdogRunning) {
            watchdogRunning = false
            watchdogHandler.removeCallbacks(socketCheckRunnable)
            Log.d(TAG, "Socket watchdog stopped")
        }
    }

    private fun resetSocketWatchdog() {
        if (watchdogRestartCount > 0) {
            Log.i(TAG, "Socket watchdog: client connected, resetting failure count (was $watchdogRestartCount)")
            DiagnosticBuffer.info("RTSP", "Socket healthy: client connected, reset watchdog count")
        }
        watchdogRestartCount = 0
        watchdogExhausted = false
    }

    private fun checkSocketHealth() {
        if (!isServerRunning || isPaused || isRestarting || watchdogExhausted) return

        // Skip during grace period after start/restart
        val elapsed = System.currentTimeMillis() - lastServerStartTime
        if (elapsed < SOCKET_GRACE_PERIOD_MS) return

        // TCP probe on a dedicated background thread (shared executor) to avoid
        // blocking main + avoid spawning a fresh OS thread per check.
        watchdogProbeExecutor.execute {
            val port = prefs.camera.rtspPort
            val socketAlive = try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress("127.0.0.1", port), SOCKET_CONNECT_TIMEOUT_MS)
                    true
                }
            } catch (e: java.net.ConnectException) {
                Log.w(TAG, "Socket watchdog: port $port REFUSED")
                false
            } catch (e: java.net.SocketTimeoutException) {
                Log.w(TAG, "Socket watchdog: port $port TIMEOUT (${SOCKET_CONNECT_TIMEOUT_MS}ms)")
                false
            } catch (e: Exception) {
                Log.w(TAG, "Socket watchdog: port $port error: ${e.message}")
                false
            }

            if (!socketAlive) {
                watchdogHandler.post { handleSocketDead(port) }
            }
        }
    }

    private fun handleSocketDead(port: Int) {
        // Re-check guards (state may have changed while probe was running)
        if (!isServerRunning || isPaused || isRestarting || watchdogExhausted) return

        watchdogRestartCount++
        Log.e(TAG, "Socket watchdog: port $port DEAD — restart $watchdogRestartCount/$MAX_WATCHDOG_RESTARTS")
        DiagnosticBuffer.error("RTSP", "Socket dead on port $port (attempt $watchdogRestartCount/$MAX_WATCHDOG_RESTARTS)")
        PersistentLog.error("RTSP_WATCHDOG", "Socket dead on port $port, restart #$watchdogRestartCount")

        if (watchdogRestartCount > MAX_WATCHDOG_RESTARTS) {
            Log.e(TAG, "Socket watchdog: exhausted $MAX_WATCHDOG_RESTARTS restarts — giving up")
            DiagnosticBuffer.error("RTSP", "Socket watchdog exhausted — giving up")
            PersistentLog.error("RTSP_WATCHDOG", "Exhausted $MAX_WATCHDOG_RESTARTS restarts")
            watchdogExhausted = true
            stopSocketWatchdog()
            return
        }

        stopSocketWatchdog()
        isRestarting = true
        try {
            val success = restartServer()
            if (success) {
                Log.i(TAG, "Socket watchdog: restart successful")
                DiagnosticBuffer.info("RTSP", "Socket watchdog restart successful")
                PersistentLog.info("RTSP_WATCHDOG", "Restart successful")
                // startSocketWatchdog() called by startServer() on success
            } else {
                Log.e(TAG, "Socket watchdog: restart FAILED")
                DiagnosticBuffer.error("RTSP", "Socket watchdog restart failed")
                startSocketWatchdog()  // Keep checking
            }
        } catch (e: Exception) {
            Log.e(TAG, "Socket watchdog: restart exception: ${e.message}", e)
            DiagnosticBuffer.error("RTSP", "Socket watchdog restart exception: ${e.message}")
            startSocketWatchdog()
        } finally {
            isRestarting = false
        }
    }

    fun getWatchdogStatus(): String = when {
        watchdogExhausted -> "exhausted($watchdogRestartCount restarts)"
        !watchdogRunning -> "stopped"
        watchdogRestartCount > 0 -> "active(restarts=$watchdogRestartCount)"
        else -> "healthy"
    }

    /**
     * Stop the RTSP server completely (including camera).
     */
    fun stopServer() {
        val server = rtspServer ?: return

        if (!isServerRunning) {
            Log.d(TAG, "Server not running")
            return
        }

        stopSocketWatchdog()
        stoppingIntentionally = true

        try {
            // Clear the cache before stopping to immediately free buffered frames
            clearCache()

            server.stopStream()
            isServerRunning = false
            isStreaming = false
            isPaused = false
            clientCount = 0
            Log.i(TAG, "RTSP server stopped")
            DiagnosticBuffer.info("RTSP", "Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server: ${e.message}", e)
            DiagnosticBuffer.error("RTSP", "Error stopping server: ${e.message}")
        }
    }

    /**
     * Clear the RTSP frame buffer to immediately free memory.
     * Call this when pausing to prevent OOM from buffered frames.
     */
    fun clearCache() {
        val server = rtspServer ?: return
        try {
            val streamClient = server.getStreamClient()
            val itemsBefore = streamClient.getItemsInCache()
            streamClient.clearCache()
            Log.i(TAG, "Cleared RTSP cache (was $itemsBefore frames)")
            DiagnosticBuffer.info("RTSP", "Cache cleared ($itemsBefore frames freed)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear cache: ${e.message}")
        }
    }

    /**
     * Get current cache statistics for diagnostics.
     */
    fun getCacheStats(): Pair<Int, Int>? {
        val server = rtspServer ?: return null
        return try {
            val streamClient = server.getStreamClient()
            Pair(streamClient.getItemsInCache(), streamClient.getCacheSize())
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Release all resources.
     */
    fun release() {
        stopSocketWatchdog()
        // Only reset the watchdog state when this release() is an external/
        // user-initiated teardown. When it's a step inside restartServer's
        // own restart cycle, leaving the counter intact is what allows
        // MAX_WATCHDOG_RESTARTS to actually exhaust after repeated failures
        // (previously the counter was reset every cycle → infinite loop).
        if (!inWatchdogRestart) {
            watchdogRestartCount = 0
            watchdogExhausted = false
        }

        stoppingIntentionally = true
        stopServer()

        camera2Source?.release()
        camera2Source = null

        rtspServer = null
        motionCallback = null

        Log.i(TAG, "RtspCameraServer2 released")
    }

    // ========== State Queries ==========

    fun isRunning(): Boolean {
        // Return the server running state
        // Note: Don't check camera2Source.isRunning() here because there's a timing
        // window during startup where the server is started but camera isn't fully ready yet.
        // Camera disconnect detection is handled by the camera callbacks instead.
        return isServerRunning
    }

    fun isActivelyStreaming(): Boolean = isStreaming && clientCount > 0 && !isPaused

    /**
     * True if the camera is currently in a failure/recovery state.
     * Used by memory recovery to avoid restarting RTSP during camera errors
     * (which would make Samsung "Camera disabled by policy" worse).
     */
    fun isInCameraRecovery(): Boolean = isRestarting || consecutiveRestartFailures > 0

    fun getClientCount(): Int = clientCount

    fun isPaused(): Boolean = isPaused

    /**
     * Get a status string for memory diagnostics.
     * Includes cache stats and server state.
     */
    fun getMemoryStatus(): String {
        val cacheStats = getCacheStats()
        val cacheInfo = if (cacheStats != null) {
            "${cacheStats.first}/${cacheStats.second} frames"
        } else {
            "N/A"
        }
        val stateInfo = when {
            !isServerRunning -> "stopped"
            isPaused -> "paused"
            isStreaming -> "streaming($clientCount clients)"
            else -> "running"
        }
        return "state=$stateInfo cache=$cacheInfo watchdog=${getWatchdogStatus()}"
    }

    fun getStreamUrl(): String {
        val ip = getLocalIpAddress()
        val port = prefs.camera.rtspPort
        return if (prefs.camera.rtspAuthEnabled && prefs.camera.hasRtspCredentials) {
            "rtsp://${prefs.camera.rtspAuthUser}:****@$ip:$port/"
        } else {
            "rtsp://$ip:$port/"
        }
    }

    fun hasFailed(): Boolean = hasFailed

    fun getFailureReason(): String? = failureReason

    fun resetFailedState() {
        hasFailed = false
        failureReason = null
        Log.i(TAG, "Failed state reset - can retry")
    }

    // ========== Motion Detection ==========

    /**
     * Set callback for motion detection events.
     */
    fun setMotionCallback(callback: (Boolean) -> Unit) {
        motionCallback = callback
        Log.i(TAG, "setMotionCallback: Setting motion callback on camera2Source (exists=${camera2Source != null})")
        camera2Source?.setMotionCallback { motionDetected ->
            if (motionDetected) {
                Log.d(TAG, "Motion callback received from camera2Source - invoking parent callback")
                callback(true)
            }
        }
    }

    /**
     * Enable motion detection (arm it).
     */
    fun enableMotionDetection() {
        camera2Source?.enableMotionDetection()
        Log.d(TAG, "Motion detection enabled")
    }

    /**
     * Disable motion detection.
     */
    fun disableMotionDetection() {
        camera2Source?.disableMotionDetection()
        Log.d(TAG, "Motion detection disabled")
    }

    /**
     * Check if motion detection is enabled.
     */
    fun isMotionDetectionEnabled(): Boolean = camera2Source?.isMotionDetectionEnabled() ?: false

    /**
     * Re-arm motion detection (reset the delay timer).
     * Call this when the screen dims to give the camera time to stabilize.
     */
    fun rearmMotionDetection() {
        camera2Source?.rearmMotionDetection()
    }

    /**
     * Set motion detection threshold (higher = less sensitive).
     * Stored at this level so it survives auto-restarts.
     */
    fun setMotionThreshold(threshold: Double) {
        motionThresholdOverride = threshold
        camera2Source?.setMotionThreshold(threshold)
    }

    // ========== Camera Control ==========

    /**
     * Switch between front and back camera.
     */
    fun switchCamera(useFrontCamera: Boolean) {
        val source = camera2Source ?: return
        if (source.isFrontCamera() != useFrontCamera) {
            source.switchCamera()
        }
    }

    /**
     * Check if using front camera.
     */
    fun isFrontCamera(): Boolean = camera2Source?.isFrontCamera() ?: true

    // ========== Frame Capture for getCamshot API ==========

    /**
     * Get the latest JPEG frame from the camera stream.
     * Returns null if no frame is available or RTSP is not running.
     */
    fun getLatestJpegFrame(): ByteArray? {
        return camera2Source?.getLatestJpegFrame()
    }

    /**
     * Check if a JPEG frame is available.
     */
    fun hasJpegFrame(): Boolean = camera2Source?.hasJpegFrame() ?: false

    /**
     * Set the rotation angle for JPEG capture.
     * @param degrees Rotation in degrees (0, 90, 180, 270)
     */
    fun setJpegRotation(degrees: Int) {
        camera2Source?.setJpegRotation(degrees)
    }

    /**
     * Set face detection frame callback.
     * When set, camera frames are converted to Bitmap and forwarded for ML Kit face detection.
     * Stored at this level so it survives auto-restarts (Camera2MotionVideoSource is recreated).
     */
    fun setFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) {
        faceFrameCallback = callback
        camera2Source?.setFaceFrameCallback(callback)
    }

    /** HA sensor face frame callback (parallel to wake detector, survives restarts) */
    fun setHaSensorFaceFrameCallback(callback: ((android.graphics.Bitmap, Int) -> Unit)?) {
        haSensorFaceFrameCallback = callback
        camera2Source?.setHaSensorFaceFrameCallback(callback)
    }

    // ── Hardware face detection passthrough ──

    fun isHwFaceDetectSupported(): Boolean = camera2Source?.isHwFaceDetectSupported() ?: false

    fun setHwFaceResultCallback(callback: ((Int) -> Unit)?) {
        camera2Source?.setHwFaceResultCallback(callback)
    }

    fun setHwFaceDetectedCallback(callback: (() -> Unit)?) {
        camera2Source?.setHwFaceDetectedCallback(callback)
    }

    fun setHaSensorHwFaceResultCallback(callback: ((Int) -> Unit)?) {
        camera2Source?.setHaSensorHwFaceResultCallback(callback)
    }

    fun setHwFaceMinSizePercent(percent: Float) {
        camera2Source?.hwFaceMinSizePercent = percent
    }

    /**
     * Get the current motion score from camera detection for graph visualization.
     * Returns 0.0 if camera is not active or no score available yet.
     */
    fun getCurrentMotionScore(): Double {
        return camera2Source?.getCurrentMotionScore() ?: 0.0
    }

    // ========== ConnectChecker Implementation ==========

    override fun onConnectionStarted(url: String) {
        clientCount++
        Log.i(TAG, "Client connected. Total clients: $clientCount")
        DiagnosticBuffer.info("RTSP", "Client connected (total: $clientCount)")

        // Client connected proves socket is alive — reset watchdog failure counter
        resetSocketWatchdog()

        // Request a keyframe immediately when a client connects
        // This ensures HA gets an I-frame quickly and can start decoding
        // Without this, the client may wait up to iFrameInterval seconds for a keyframe
        try {
            rtspServer?.requestKeyframe()
            Log.d(TAG, "Requested keyframe for new client connection")
        } catch (e: Exception) {
            Log.w(TAG, "Could not request keyframe: ${e.message}")
        }

        // Note: Don't clear cache on connect - HA native stream needs existing frames
        // in the cache while waiting for the next keyframe to arrive.
    }

    override fun onConnectionSuccess() {
        Log.i(TAG, "Connection successful")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
        DiagnosticBuffer.error("RTSP", "Connection failed: $reason")
    }

    override fun onDisconnect() {
        clientCount = (clientCount - 1).coerceAtLeast(0)
        Log.i(TAG, "Client disconnected. Total clients: $clientCount")
        DiagnosticBuffer.info("RTSP", "Client disconnected (remaining: $clientCount)")
    }

    override fun onAuthError() {
        Log.e(TAG, "Authentication error")
        DiagnosticBuffer.error("RTSP", "Authentication error")
    }

    override fun onAuthSuccess() {
        Log.i(TAG, "Authentication successful")
    }

    // Track bitrate changes for diagnostics (don't log every change, just significant ones)
    private var lastLoggedBitrate: Long = 0
    private var lastBitrateLogTime: Long = 0

    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "New bitrate: ${bitrate / 1000} kbps")

        // Log to diagnostics, but throttle to avoid spam (max every 30 seconds, or if change > 50%)
        val now = System.currentTimeMillis()
        val bitrateChangePercent = if (lastLoggedBitrate > 0) {
            kotlin.math.abs(bitrate - lastLoggedBitrate).toDouble() / lastLoggedBitrate * 100
        } else 100.0

        if (now - lastBitrateLogTime > 30_000 || bitrateChangePercent > 50) {
            val targetBitrate = prefs.camera.rtspBitrate
            val actualKbps = bitrate / 1000
            val targetKbps = targetBitrate / 1000
            val ratio = (bitrate.toDouble() / targetBitrate * 100).toInt()
            DiagnosticBuffer.info("RTSP", "Bitrate: ${actualKbps}kbps (${ratio}% of ${targetKbps}kbps target)")
            lastLoggedBitrate = bitrate
            lastBitrateLogTime = now
        }
    }

    // ========== Utility ==========

    /**
     * Query the current display rotation in degrees (0/90/180/270).
     *
     * Used together with the camera sensor orientation to compute how many
     * degrees the encoded RTSP stream needs to be rotated so that viewers
     * which ignore H.264 rotation metadata (HA, Fully) display it upright.
     */
    private fun getDisplayRotationDegrees(): Int {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            val rotation = wm.defaultDisplay.rotation
            when (rotation) {
                android.view.Surface.ROTATION_0 -> 0
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query display rotation, defaulting to 0°: ${e.message}")
            0
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress?.contains(".") == true && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}")
        }
        return "0.0.0.0"
    }
}
