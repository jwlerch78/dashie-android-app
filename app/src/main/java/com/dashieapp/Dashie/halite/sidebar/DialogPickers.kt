package com.dashieapp.Dashie.halite.sidebar

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.FaceWakeDetector
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences
import com.dashieapp.Dashie.halite.voice.HaAssistClient
import android.widget.ScrollView
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import com.dashieapp.Dashie.edition.brandName

/**
 * Handles dialog picker UIs including:
 * - API settings dialog
 * - Motion wake mode picker (with camera preview)
 * - Response handling picker
 * - Voice pipeline picker
 * - Camera permission explanation
 * - Lock navigation confirmation
 *
 * Screensaver-related dialogs have been extracted to ScreensaverDialogs.kt
 */
class DialogPickers(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val webViewProvider: (() -> WebView?)? = null
) {
    private val webView: WebView? get() = webViewProvider?.invoke()

    companion object {
        private const val TAG = "DialogPickers"
    }

    // Delegate screensaver dialogs to extracted class
    private val screensaverDialogs = SidebarScreensaverDialogs(activity, halitePrefs, webViewProvider)

    // Callbacks for actions that need external handling
    var onRestartRequired: ((String) -> Unit)? = null
    var settingsCallbacks: SettingsCallbacks? = null
        set(value) {
            field = value
            screensaverDialogs.settingsCallbacks = value
        }

    /**
     * Show dual picker for screensaver settings (timeout + mode + photo source).
     * Delegated to ScreensaverDialogs.
     */
    fun showScreensaverPicker(textView: TextView?, onSettingsChanged: (timeout: Int, mode: String) -> Unit) =
        screensaverDialogs.showScreensaverPicker(textView, onSettingsChanged)

    /**
     * Show HA Media folder picker from the JS bridge.
     * Delegated to ScreensaverDialogs.
     */
    fun showHaMediaFolderPicker(onFolderSelected: (String) -> Unit) =
        screensaverDialogs.showHaMediaFolderPickerFromBridge(onFolderSelected)

    // ============================================
    // API Settings Dialog
    // ============================================

    /**
     * Show motion wake mode picker dialog (matches Screensaver picker layout)
     * Includes +/- buttons for Camera mode threshold (0.5% increments)
     */
    fun showMotionWakeModePicker(textView: TextView?, onModeSet: (String) -> Unit) {
        val modeOptions = listOf(
            "Touch Only" to "disabled",
            "Motion (Camera)" to "camera",
            "Face (Camera)" to "face",
            "Brightness Sensor" to "brightness"
        )

        val currentMode = halitePrefs.screensaver.motionWakeMode
        val currentIndex = modeOptions.indexOfFirst { it.second == currentMode }.coerceAtLeast(0)
        // Store threshold in tenths (5.0% = 50) for 0.5% precision
        var currentThresholdTenths = halitePrefs.screensaver.cameraWakeThresholdTenths

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_motion_wake_picker, null)

        // Get threshold UI elements
        val thresholdContainer = dialogView.findViewById<LinearLayout>(R.id.thresholdContainer)
        val buttonThresholdPlus = dialogView.findViewById<ImageView>(R.id.buttonThresholdPlus)
        val buttonThresholdMinus = dialogView.findViewById<ImageView>(R.id.buttonThresholdMinus)
        val textThresholdValue = dialogView.findViewById<TextView>(R.id.textThresholdValue)
        val seekBarThreshold = dialogView.findViewById<SeekBar>(R.id.seekBarThreshold)

        // SeekBar maps 0-99 to 2-200 tenths (0.2% to 20.0% in 0.2% steps)
        // progress 0 = 2 tenths (0.2%), progress 99 = 200 tenths (20.0%)
        fun tenthsToProgress(tenths: Int): Int = ((tenths - 2) / 2).coerceIn(0, 99)
        fun progressToTenths(progress: Int): Int = (progress * 2 + 2).coerceIn(2, 200)

        // Snap tenths to nearest 0.5% (5 tenths)
        fun snapToHalf(tenths: Int): Int = ((tenths + 2) / 5) * 5

        // Camera preview and motion graph views (declare early so they can be used in functions)
        val cameraPreviewContainer = dialogView.findViewById<FrameLayout>(R.id.cameraPreviewContainer)
        val imageCameraPreview = dialogView.findViewById<android.widget.ImageView>(R.id.imageCameraPreview)
        val textLoadingPreview = dialogView.findViewById<TextView>(R.id.textLoadingPreview)
        val motionGraphView = dialogView.findViewById<MotionGraphView>(R.id.motionGraphView)
        val textFaceIndicator = dialogView.findViewById<TextView>(R.id.textFaceIndicator)
        val distanceContainer = dialogView.findViewById<LinearLayout>(R.id.distanceContainer)
        val distanceButtonRow = dialogView.findViewById<LinearLayout>(R.id.distanceButtonRow)

        // Distance selector state
        val distanceOptions = listOf(
            "Far" to ScreensaverPreferences.FACE_DISTANCE_FAR,
            "Near" to ScreensaverPreferences.FACE_DISTANCE_NEAR,
            "Close" to ScreensaverPreferences.FACE_DISTANCE_CLOSE
        )
        var selectedDistance = halitePrefs.screensaver.faceWakeDistance
        val distanceButtons = mutableListOf<TextView>()
        val orangeColor = activity.getColor(R.color.dashie_orange)
        val defaultBgColor = 0xFF2A2A2A.toInt()
        val selectedBgColor = orangeColor

        // Create distance buttons programmatically
        fun updateDistanceButtonStyles() {
            distanceButtons.forEachIndexed { i, btn ->
                val isSelected = distanceOptions[i].second == selectedDistance
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12f * activity.resources.displayMetrics.density
                    setColor(if (isSelected) selectedBgColor else defaultBgColor)
                }
                btn.background = bg
                btn.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            }
        }

        val density = activity.resources.displayMetrics.density
        val buttonWidthDp = (62 * density).toInt()  // Fixed width for all pills

        distanceOptions.forEach { (label, value) ->
            val btn = TextView(activity).apply {
                text = label
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                val hPad = (6 * density).toInt()
                val vPad = (4 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                val lp = LinearLayout.LayoutParams(
                    buttonWidthDp,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = (3 * density).toInt()
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }
            btn.setOnClickListener {
                selectedDistance = value
                updateDistanceButtonStyles()
                // Update live face detector distance for real-time preview
                val percent = ScreensaverPreferences.faceDistanceToPercent(value)
                settingsCallbacks?.updateFaceDistance(percent)
            }
            distanceButtons.add(btn)
            distanceButtonRow?.addView(btn)
        }
        updateDistanceButtonStyles()

        var previewHandler: android.os.Handler? = null
        var previewRunnable: Runnable? = null
        var graphHandler: android.os.Handler? = null
        var graphRunnable: Runnable? = null
        var loadingDotsHandler: android.os.Handler? = null
        var loadingDotsRunnable: Runnable? = null
        var startedRtspTemporarily = false  // Track if we started RTSP for debugging
        var hasReceivedFirstFrame = false  // Track if we've received the first camera frame

        // Create a single background thread for camera operations to avoid spawning hundreds of threads
        val backgroundThread = android.os.HandlerThread("CameraPreviewThread")
        backgroundThread.start()
        val backgroundHandler = android.os.Handler(backgroundThread.looper)

        // Helper to update threshold display and seekbar
        fun updateThresholdUI() {
            textThresholdValue.text = SidebarFormatters.formatThreshold(currentThresholdTenths)
            seekBarThreshold?.progress = tenthsToProgress(currentThresholdTenths)
            motionGraphView?.setThreshold(currentThresholdTenths)
        }

        // Initialize threshold display
        updateThresholdUI()

        // Function to start the animated loading dots
        fun startLoadingDotsAnimation() {
            var dotCount = 0
            loadingDotsHandler = android.os.Handler(android.os.Looper.getMainLooper())
            loadingDotsRunnable = object : Runnable {
                override fun run() {
                    dotCount = (dotCount + 1) % 4
                    val dots = ".".repeat(if (dotCount == 0) 3 else dotCount)
                    textLoadingPreview?.text = "Loading\npreview$dots"
                    loadingDotsHandler?.postDelayed(this, 400)
                }
            }
            loadingDotsHandler?.post(loadingDotsRunnable!!)
        }

        // Function to stop the loading dots animation
        fun stopLoadingDotsAnimation() {
            loadingDotsRunnable?.let { loadingDotsHandler?.removeCallbacks(it) }
            loadingDotsHandler = null
            loadingDotsRunnable = null
        }

        // Track current bitmap to recycle it when replaced
        var currentPreviewBitmap: android.graphics.Bitmap? = null

        // Detect if this is a low-memory device and adjust quality/interval
        val isLowMemory = com.dashieapp.Dashie.halite.diagnostics.DeviceCapabilities.isLowMemoryDevice(activity)
        val previewInterval = if (isLowMemory) 3000L else 1000L  // 3s for low-mem, 1s for normal
        val previewScale = if (isLowMemory) 0.5f else 1.0f  // Half resolution for low-mem devices

        // Log device capabilities at dialog open
        com.dashieapp.Dashie.halite.diagnostics.DeviceCapabilities.logMemoryState(activity, "PREVIEW_INIT")
        if (isLowMemory) {
            Log.i(TAG, "📹 Low-memory device detected - using reduced preview quality (${(previewScale * 100).toInt()}% scale, ${previewInterval}ms interval)")
        }

        // Track preview performance metrics
        var previewAttempts = 0
        var previewFailures = 0

        // Function to update camera preview (JPEG snapshot - serial processing)
        // This function schedules itself after each snapshot completes to avoid queueing tasks
        fun updateCameraPreview() {
            val startTime = System.currentTimeMillis()
            previewAttempts++

            // Do all work in background thread (getCameraPreviewFrame() blocks for up to 2.5s)
            backgroundHandler.post {
                try {
                    // Log memory state every 10 attempts to track degradation
                    if (previewAttempts % 10 == 1) {
                        com.dashieapp.Dashie.halite.diagnostics.DeviceCapabilities.logMemoryState(activity, "PREVIEW_MEM")
                    }

                    val frame = settingsCallbacks?.getCameraPreviewFrame()
                    if (frame != null) {
                        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                            // Downsample on low-memory devices (50% scale = 25% memory usage)
                            if (isLowMemory) {
                                inSampleSize = 2  // Decode at half resolution (160x120 instead of 320x240)
                            }
                        }
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.size, decodeOptions)

                        // Rotate 180 degrees to match RTSP stream orientation (front camera is upside down)
                        val matrix = android.graphics.Matrix().apply { postRotate(180f) }
                        val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        // Recycle original bitmap immediately after creating rotated version
                        bitmap.recycle()

                        val duration = System.currentTimeMillis() - startTime
                        if (duration > 2000) {
                            // Log slow captures to PersistentLog
                            com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn(
                                "PREVIEW_SLOW",
                                "Snapshot took ${duration}ms (attempt $previewAttempts, failures: $previewFailures)"
                            )
                        }

                        // Update UI on main thread
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            // Recycle old bitmap before setting new one
                            currentPreviewBitmap?.recycle()
                            currentPreviewBitmap = rotatedBitmap

                            imageCameraPreview?.setImageBitmap(rotatedBitmap)
                            imageCameraPreview?.visibility = View.VISIBLE
                            // Hide loading indicator on first frame
                            if (!hasReceivedFirstFrame) {
                                hasReceivedFirstFrame = true
                                textLoadingPreview?.visibility = View.GONE
                                stopLoadingDotsAnimation()
                            }

                            // Schedule next snapshot after displaying this one (serial processing)
                            previewHandler?.postDelayed(previewRunnable!!, previewInterval)
                        }
                    } else {
                        // No frame available, retry after delay
                        previewFailures++
                        if (previewFailures % 5 == 0) {
                            com.dashieapp.Dashie.halite.diagnostics.PersistentLog.warn(
                                "PREVIEW_FAIL",
                                "Failed to get frame (attempt $previewAttempts, failures: $previewFailures)"
                            )
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            previewHandler?.postDelayed(previewRunnable!!, previewInterval)
                        }
                    }
                } catch (e: Exception) {
                    // Error occurred, log and retry after delay
                    previewFailures++
                    val duration = System.currentTimeMillis() - startTime
                    android.util.Log.w("DialogPickers", "Failed to get/decode preview frame after ${duration}ms: ${e.message}")
                    com.dashieapp.Dashie.halite.diagnostics.PersistentLog.error(
                        "PREVIEW_ERROR",
                        "Exception after ${duration}ms (attempt $previewAttempts): ${e.message}"
                    )

                    // Log memory state on errors
                    com.dashieapp.Dashie.halite.diagnostics.DeviceCapabilities.logMemoryState(activity, "PREVIEW_ERR")

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        previewHandler?.postDelayed(previewRunnable!!, previewInterval)
                    }
                }
            }
        }

        // Function to update motion graph (fast, 500ms interval)
        fun updateMotionGraph() {
            val motionScore = settingsCallbacks?.getCurrentMotionScore() ?: 0.0
            motionGraphView?.addDataPoint(motionScore)
        }

        // Show/hide controls based on initial mode
        // Camera preview shown for both camera and face modes; threshold/graph only for camera
        val showCameraUI = currentMode == "camera" || currentMode == "face"
        val showThresholdAndGraph = currentMode == "camera"
        thresholdContainer.visibility = if (showThresholdAndGraph) View.VISIBLE else View.GONE

        // Function to start RTSP for debugging if needed (uses startedRtspTemporarily from outer scope)
        fun ensureRtspForDebugging() {
            if (!startedRtspTemporarily && settingsCallbacks?.isRtspServerRunning() != true) {
                startedRtspTemporarily = settingsCallbacks?.startRtspForDebugging() ?: false
                if (startedRtspTemporarily) {
                    Log.i(TAG, "📹 Started RTSP temporarily for motion wake debugging")
                }
            }
        }

        // Start preview and graph if camera mode is initially selected
        if (showCameraUI) {
            motionGraphView?.visibility = if (showThresholdAndGraph) View.VISIBLE else View.GONE

            // Check memory FIRST before starting RTSP (to avoid stealing camera from motion wake unnecessarily)
            val disablePreview = com.dashieapp.Dashie.halite.diagnostics.DeviceCapabilities.shouldDisableCameraPreview(activity)

            if (disablePreview) {
                // Memory critically low - disable preview to prevent ANR
                cameraPreviewContainer?.visibility = View.VISIBLE
                textLoadingPreview?.visibility = View.VISIBLE
                textLoadingPreview?.text = "Video preview disabled\n(low available memory)"
                imageCameraPreview?.visibility = View.GONE
                stopLoadingDotsAnimation()
                Log.i(TAG, "📹 Camera preview disabled due to low available memory")
                com.dashieapp.Dashie.halite.diagnostics.PersistentLog.info("PREVIEW_DISABLED", "Disabled due to low available memory")
            } else {
                // Start RTSP for debugging if not running
                ensureRtspForDebugging()

                // Show loading indicator and start preview timer
                // Note: RTSP startup is async, so we don't check isRtspV2Running() upfront
                // Instead, updateCameraPreview() will handle failures gracefully
                cameraPreviewContainer?.visibility = View.VISIBLE
                textLoadingPreview?.visibility = View.VISIBLE
                imageCameraPreview?.visibility = View.GONE
                hasReceivedFirstFrame = false
                startLoadingDotsAnimation()

                // Camera preview - serial processing (each snapshot schedules the next)
                // Add 2 second initial delay to allow RTSP/camera initialization
                previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
                previewRunnable = object : Runnable {
                    override fun run() {
                        updateCameraPreview()
                        // Note: updateCameraPreview() schedules the next call after completion
                    }
                }
                previewHandler?.postDelayed(previewRunnable!!, 2000)
            }

            // Enable graph mode so camera scores motion even when screen isn't dimmed
            settingsCallbacks?.enableMotionGraphMode()

            // Motion graph timer (fast - 250ms for smooth graph updates)
            // Add 1 second initial delay to allow RTSP/camera initialization
            graphHandler = android.os.Handler(android.os.Looper.getMainLooper())
            graphRunnable = object : Runnable {
                override fun run() {
                    updateMotionGraph()
                    graphHandler?.postDelayed(this, 250)
                }
            }
            graphHandler?.postDelayed(graphRunnable!!, 1000)

            // Enable face test mode if face mode is initially selected
            if (currentMode == "face") {
                textFaceIndicator?.visibility = View.VISIBLE
                distanceContainer?.visibility = View.VISIBLE
                // Set initial distance on the live detector
                settingsCallbacks?.updateFaceDistance(ScreensaverPreferences.faceDistanceToPercent(selectedDistance))
                settingsCallbacks?.setFaceTestCallback { faceResult ->
                    activity.runOnUiThread {
                        val borderWidth = (6 * activity.resources.displayMetrics.density).toInt()
                        when (faceResult) {
                            FaceWakeDetector.FACE_RESULT_DETECTED -> {
                                textFaceIndicator?.text = "Face Detected"
                                textFaceIndicator?.setTextColor(0xFF4CAF50.toInt())  // Green
                                val border = android.graphics.drawable.GradientDrawable()
                                border.setStroke(borderWidth, 0xFF4CAF50.toInt())
                                cameraPreviewContainer?.foreground = border
                            }
                            FaceWakeDetector.FACE_RESULT_TOO_FAR -> {
                                textFaceIndicator?.text = "Too Far"
                                textFaceIndicator?.setTextColor(0xFFFFC107.toInt())  // Yellow
                                val border = android.graphics.drawable.GradientDrawable()
                                border.setStroke(borderWidth, 0xFFFFC107.toInt())
                                cameraPreviewContainer?.foreground = border
                            }
                            else -> {
                                textFaceIndicator?.text = "No Face"
                                textFaceIndicator?.setTextColor(0xFF888888.toInt())  // Gray
                                cameraPreviewContainer?.foreground = null
                            }
                        }
                    }
                }
            }
        }

        // SeekBar listener for fine-tuning (each step is 0.2%)
        seekBarThreshold?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentThresholdTenths = progressToTenths(progress)
                    textThresholdValue.text = SidebarFormatters.formatThreshold(currentThresholdTenths)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // + button: use 0.1% (1 tenth) increments below 1%, otherwise 0.5% (5 tenths)
        buttonThresholdPlus.setOnClickListener {
            if (currentThresholdTenths < 10) {
                // Below 1%: use 0.1% increments
                currentThresholdTenths = (currentThresholdTenths + 1).coerceAtMost(200)
            } else {
                // 1% and above: snap to nearest 0.5%, then increase by 0.5%
                val snapped = snapToHalf(currentThresholdTenths)
                currentThresholdTenths = (snapped + 5).coerceAtMost(200)
            }
            updateThresholdUI()
        }

        // - button: use 0.1% (1 tenth) increments below 1%, otherwise 0.5% (5 tenths)
        buttonThresholdMinus.setOnClickListener {
            if (currentThresholdTenths <= 10) {
                // At or below 1%: use 0.1% increments
                currentThresholdTenths = (currentThresholdTenths - 1).coerceAtLeast(2)  // Min 0.2%
            } else {
                // Above 1%: snap to nearest 0.5%, then decrease by 0.5%
                val snapped = snapToHalf(currentThresholdTenths)
                currentThresholdTenths = (snapped - 5).coerceAtLeast(2)  // Min 0.2%
            }
            updateThresholdUI()
        }

        // Populate radio buttons
        val optionsGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioGroupMotionWake)
        val radioButtonIds = mutableListOf<Int>()

        modeOptions.forEachIndexed { index, (label, mode) ->
            val radioButton = android.widget.RadioButton(activity).apply {
                id = View.generateViewId()
                text = label
                textSize = 14f
                setTextColor(activity.getColor(R.color.text_primary))
                buttonTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.dashie_orange))
                isChecked = index == currentIndex
                setPadding(0, 8, 0, 8)
            }
            radioButtonIds.add(radioButton.id)
            optionsGroup.addView(radioButton)
        }

        // Listen for radio button changes to show/hide threshold controls
        optionsGroup.setOnCheckedChangeListener { group, checkedId ->
            val selectedIndex = radioButtonIds.indexOf(checkedId)
            val selectedMode = if (selectedIndex >= 0) modeOptions[selectedIndex].second else "disabled"
            val usesCameraUI = selectedMode == "camera" || selectedMode == "face"
            val showThreshold = selectedMode == "camera"
            thresholdContainer.visibility = if (showThreshold) View.VISIBLE else View.GONE

            // Enable/disable face test mode based on selected mode
            if (selectedMode == "face") {
                textFaceIndicator?.visibility = View.VISIBLE
                distanceContainer?.visibility = View.VISIBLE
                // Set initial distance on the live detector
                settingsCallbacks?.updateFaceDistance(ScreensaverPreferences.faceDistanceToPercent(selectedDistance))
                settingsCallbacks?.setFaceTestCallback { faceResult ->
                    activity.runOnUiThread {
                        val borderWidth = (6 * activity.resources.displayMetrics.density).toInt()
                        when (faceResult) {
                            FaceWakeDetector.FACE_RESULT_DETECTED -> {
                                textFaceIndicator?.text = "Face Detected"
                                textFaceIndicator?.setTextColor(0xFF4CAF50.toInt())
                                val border = android.graphics.drawable.GradientDrawable()
                                border.setStroke(borderWidth, 0xFF4CAF50.toInt())
                                cameraPreviewContainer?.foreground = border
                            }
                            FaceWakeDetector.FACE_RESULT_TOO_FAR -> {
                                textFaceIndicator?.text = "Too Far"
                                textFaceIndicator?.setTextColor(0xFFFFC107.toInt())
                                val border = android.graphics.drawable.GradientDrawable()
                                border.setStroke(borderWidth, 0xFFFFC107.toInt())
                                cameraPreviewContainer?.foreground = border
                            }
                            else -> {
                                textFaceIndicator?.text = "No Face"
                                textFaceIndicator?.setTextColor(0xFF888888.toInt())
                                cameraPreviewContainer?.foreground = null
                            }
                        }
                    }
                }
            } else {
                textFaceIndicator?.visibility = View.GONE
                distanceContainer?.visibility = View.GONE
                cameraPreviewContainer?.foreground = null
                settingsCallbacks?.setFaceTestCallback(null)
            }

            // Start/stop preview based on mode
            if (usesCameraUI) {
                // Request camera permission when camera mode is selected
                val hasPermission = settingsCallbacks?.hasCameraPermission() ?: false
                if (!hasPermission) {
                    settingsCallbacks?.requestCameraPermission { granted ->
                        if (granted) {
                            Log.i(TAG, "📷 Camera permission granted for motion wake")
                            // Start RTSP for debugging if not running
                            ensureRtspForDebugging()
                            // Start preview and graph after permission granted
                            cameraPreviewContainer?.visibility = View.VISIBLE
                            textLoadingPreview?.visibility = View.VISIBLE
                            imageCameraPreview?.visibility = View.GONE
                            hasReceivedFirstFrame = false
                            startLoadingDotsAnimation()
                            motionGraphView?.visibility = if (showThreshold) View.VISIBLE else View.GONE
                            if (previewHandler == null) {
                                // Camera preview timer - serial processing (each snapshot schedules the next)
                                // Add 2 second initial delay to allow RTSP/camera initialization after permission grant
                                previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
                                previewRunnable = object : Runnable {
                                    override fun run() {
                                        updateCameraPreview()
                                        // Note: updateCameraPreview() schedules the next call after completion
                                    }
                                }
                                previewHandler?.postDelayed(previewRunnable!!, 2000)

                                // Enable graph mode so camera scores motion even when screen isn't dimmed
                                settingsCallbacks?.enableMotionGraphMode()

                                // Motion graph timer (250ms)
                                // Add 1 second initial delay to allow camera initialization
                                graphHandler = android.os.Handler(android.os.Looper.getMainLooper())
                                graphRunnable = object : Runnable {
                                    override fun run() {
                                        updateMotionGraph()
                                        graphHandler?.postDelayed(this, 250)
                                    }
                                }
                                graphHandler?.postDelayed(graphRunnable!!, 1000)
                            }
                        } else {
                            Log.w(TAG, "📷 Camera permission denied")
                        }
                    }
                } else {
                    // Already has permission - start RTSP for debugging and preview/graph
                    ensureRtspForDebugging()
                    cameraPreviewContainer?.visibility = View.VISIBLE
                    textLoadingPreview?.visibility = View.VISIBLE
                    imageCameraPreview?.visibility = View.GONE
                    hasReceivedFirstFrame = false
                    startLoadingDotsAnimation()
                    motionGraphView?.visibility = if (showThreshold) View.VISIBLE else View.GONE
                    if (previewHandler == null) {
                        // Camera preview timer - serial processing (each snapshot schedules the next)
                        // Add 1 second initial delay to allow RTSP/camera initialization
                        previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        previewRunnable = object : Runnable {
                            override fun run() {
                                updateCameraPreview()
                                // Note: updateCameraPreview() schedules the next call after completion
                            }
                        }
                        previewHandler?.postDelayed(previewRunnable!!, 1000)

                        // Enable graph mode so camera scores motion even when screen isn't dimmed
                        settingsCallbacks?.enableMotionGraphMode()

                        // Motion graph timer (250ms)
                        // Add 1 second initial delay to allow camera initialization
                        graphHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        graphRunnable = object : Runnable {
                            override fun run() {
                                updateMotionGraph()
                                graphHandler?.postDelayed(this, 250)
                            }
                        }
                        graphHandler?.postDelayed(graphRunnable!!, 1000)
                    }
                }
            } else {
                // Stop preview and graph when not in camera mode
                previewRunnable?.let { previewHandler?.removeCallbacks(it) }
                previewHandler = null
                graphRunnable?.let { graphHandler?.removeCallbacks(it) }
                graphHandler = null
                stopLoadingDotsAnimation()
                cameraPreviewContainer?.visibility = View.GONE
                motionGraphView?.visibility = View.GONE
                motionGraphView?.clear()
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            // Stop preview when dialog is cancelled
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            // Get selected mode
            val selectedIndex = (0 until optionsGroup.childCount).indexOfFirst {
                (optionsGroup.getChildAt(it) as? android.widget.RadioButton)?.isChecked == true
            }
            val selectedMode = if (selectedIndex >= 0) modeOptions[selectedIndex].second else currentMode
            val previousMode = halitePrefs.screensaver.motionWakeMode

            // Save threshold only for camera mode (face mode uses fixed 3%)
            if (selectedMode == "camera") {
                halitePrefs.screensaver.cameraWakeThresholdTenths = currentThresholdTenths
            }

            // Save face distance for face mode
            if (selectedMode == "face") {
                halitePrefs.screensaver.faceWakeDistance = selectedDistance
            }

            // Save mode and update UI
            halitePrefs.screensaver.motionWakeMode = selectedMode
            textView?.text = SidebarFormatters.formatMotionWakeMode(selectedMode, halitePrefs.screensaver.cameraWakeThresholdTenths, selectedDistance)
            onModeSet(selectedMode)
            Log.i(TAG, "🔧 Motion wake mode = $selectedMode" +
                if (selectedMode == "camera") ", threshold = ${SidebarFormatters.formatThreshold(currentThresholdTenths)}"
                else if (selectedMode == "face") ", distance = $selectedDistance"
                else "")

            // Stop preview when dialog is closed
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            dialog.dismiss()
        }

        // Add dismiss listener to ensure preview/graph stops and RTSP cleanup
        dialog.setOnDismissListener {
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            graphRunnable?.let { graphHandler?.removeCallbacks(it) }
            stopLoadingDotsAnimation()
            // Disable graph mode and face test mode when dialog closes
            settingsCallbacks?.disableMotionGraphMode()
            settingsCallbacks?.setFaceTestCallback(null)
            // Stop temporary RTSP if we started it
            if (startedRtspTemporarily) {
                settingsCallbacks?.stopRtspForDebugging()
                Log.i(TAG, "📹 Stopped temporary RTSP for motion wake debugging")
            }
            // Recycle bitmap to free memory
            currentPreviewBitmap?.recycle()
            currentPreviewBitmap = null
            // Clean up background thread to avoid memory leaks
            backgroundThread.quitSafely()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Show calibration-only dialog for motion/face wake mode.
     * Same camera preview, motion graph, threshold/distance controls as the full picker,
     * but without the mode radio buttons (mode is already set in the schema settings page).
     *
     * @param mode "camera" or "face" — determines which calibration UI to show
     * @param onDismiss Called when the dialog is dismissed (for refreshing settings page)
     */
    fun showMotionWakeCalibrationDialog(mode: String, onDismiss: () -> Unit) {
        // Store threshold in tenths (5.0% = 50) for 0.5% precision
        var currentThresholdTenths = halitePrefs.screensaver.cameraWakeThresholdTenths

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_motion_wake_picker, null)

        // Hide the radio group — mode is already selected in settings
        val optionsGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.radioGroupMotionWake)
        optionsGroup.visibility = View.GONE

        // Update title to reflect calibration mode
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)
        titleView.text = if (mode == "face") "Calibrate Face Detection" else "Calibrate Motion Detection"

        // Get threshold UI elements
        val thresholdContainer = dialogView.findViewById<LinearLayout>(R.id.thresholdContainer)
        val buttonThresholdPlus = dialogView.findViewById<ImageView>(R.id.buttonThresholdPlus)
        val buttonThresholdMinus = dialogView.findViewById<ImageView>(R.id.buttonThresholdMinus)
        val textThresholdValue = dialogView.findViewById<TextView>(R.id.textThresholdValue)
        val seekBarThreshold = dialogView.findViewById<SeekBar>(R.id.seekBarThreshold)

        // SeekBar maps 0-99 to 2-200 tenths (0.2% to 20.0% in 0.2% steps)
        fun tenthsToProgress(tenths: Int): Int = ((tenths - 2) / 2).coerceIn(0, 99)
        fun progressToTenths(progress: Int): Int = (progress * 2 + 2).coerceIn(2, 200)
        fun snapToHalf(tenths: Int): Int = ((tenths + 2) / 5) * 5

        // Camera preview and motion graph views
        val cameraPreviewContainer = dialogView.findViewById<FrameLayout>(R.id.cameraPreviewContainer)
        val imageCameraPreview = dialogView.findViewById<android.widget.ImageView>(R.id.imageCameraPreview)
        val textLoadingPreview = dialogView.findViewById<TextView>(R.id.textLoadingPreview)
        val motionGraphView = dialogView.findViewById<MotionGraphView>(R.id.motionGraphView)
        val textFaceIndicator = dialogView.findViewById<TextView>(R.id.textFaceIndicator)
        val distanceContainer = dialogView.findViewById<LinearLayout>(R.id.distanceContainer)
        val distanceButtonRow = dialogView.findViewById<LinearLayout>(R.id.distanceButtonRow)

        // Distance selector state (face mode only)
        val distanceOptions = listOf(
            "Far" to ScreensaverPreferences.FACE_DISTANCE_FAR,
            "Near" to ScreensaverPreferences.FACE_DISTANCE_NEAR,
            "Close" to ScreensaverPreferences.FACE_DISTANCE_CLOSE
        )
        var selectedDistance = halitePrefs.screensaver.faceWakeDistance
        val distanceButtons = mutableListOf<TextView>()
        val orangeColor = activity.getColor(R.color.dashie_orange)
        val defaultBgColor = 0xFF2A2A2A.toInt()
        val selectedBgColor = orangeColor

        fun updateDistanceButtonStyles() {
            distanceButtons.forEachIndexed { i, btn ->
                val isSelected = distanceOptions[i].second == selectedDistance
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12f * activity.resources.displayMetrics.density
                    setColor(if (isSelected) selectedBgColor else defaultBgColor)
                }
                btn.background = bg
                btn.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt())
            }
        }

        val density = activity.resources.displayMetrics.density
        val buttonWidthDp = (62 * density).toInt()

        distanceOptions.forEach { (label, value) ->
            val btn = TextView(activity).apply {
                text = label
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                val hPad = (6 * density).toInt()
                val vPad = (4 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                val lp = LinearLayout.LayoutParams(buttonWidthDp, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = (3 * density).toInt()
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }
            btn.setOnClickListener {
                selectedDistance = value
                updateDistanceButtonStyles()
                val percent = ScreensaverPreferences.faceDistanceToPercent(value)
                settingsCallbacks?.updateFaceDistance(percent)
            }
            distanceButtons.add(btn)
            distanceButtonRow?.addView(btn)
        }
        updateDistanceButtonStyles()

        var previewHandler: android.os.Handler? = null
        var previewRunnable: Runnable? = null
        var graphHandler: android.os.Handler? = null
        var graphRunnable: Runnable? = null
        var loadingDotsHandler: android.os.Handler? = null
        var loadingDotsRunnable: Runnable? = null
        var startedRtspTemporarily = false
        var hasReceivedFirstFrame = false

        val backgroundThread = android.os.HandlerThread("CameraCalibrationThread")
        backgroundThread.start()
        val backgroundHandler = android.os.Handler(backgroundThread.looper)

        fun updateThresholdUI() {
            textThresholdValue.text = SidebarFormatters.formatThreshold(currentThresholdTenths)
            seekBarThreshold?.progress = tenthsToProgress(currentThresholdTenths)
            motionGraphView?.setThreshold(currentThresholdTenths)
        }

        updateThresholdUI()

        fun startLoadingDotsAnimation() {
            var dotCount = 0
            loadingDotsHandler = android.os.Handler(android.os.Looper.getMainLooper())
            loadingDotsRunnable = object : Runnable {
                override fun run() {
                    dotCount = (dotCount + 1) % 4
                    val dots = ".".repeat(if (dotCount == 0) 3 else dotCount)
                    textLoadingPreview?.text = "Loading\npreview$dots"
                    loadingDotsHandler?.postDelayed(this, 400)
                }
            }
            loadingDotsHandler?.post(loadingDotsRunnable!!)
        }

        fun stopLoadingDotsAnimation() {
            loadingDotsRunnable?.let { loadingDotsHandler?.removeCallbacks(it) }
            loadingDotsHandler = null
            loadingDotsRunnable = null
        }

        var currentPreviewBitmap: android.graphics.Bitmap? = null
        val isLowMemory = com.dashieapp.Dashie.halite.diagnostics.DeviceCapabilities.isLowMemoryDevice(activity)
        val previewInterval = if (isLowMemory) 3000L else 1000L

        // Show/hide controls based on mode
        val showThresholdAndGraph = mode == "camera"
        thresholdContainer.visibility = if (showThresholdAndGraph) View.VISIBLE else View.GONE

        fun ensureRtspForDebugging() {
            if (!startedRtspTemporarily && settingsCallbacks?.isRtspServerRunning() != true) {
                startedRtspTemporarily = settingsCallbacks?.startRtspForDebugging() ?: false
                if (startedRtspTemporarily) {
                    Log.i(TAG, "📹 Started RTSP temporarily for wake calibration")
                }
            }
        }

        // Camera preview update function
        fun updateCameraPreview() {
            backgroundHandler.post {
                try {
                    val frame = settingsCallbacks?.getCameraPreviewFrame()
                    if (frame != null) {
                        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                            if (isLowMemory) inSampleSize = 2
                        }
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(frame, 0, frame.size, decodeOptions)
                        val matrix = android.graphics.Matrix().apply { postRotate(180f) }
                        val rotatedBitmap = android.graphics.Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        bitmap.recycle()

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            currentPreviewBitmap?.recycle()
                            currentPreviewBitmap = rotatedBitmap
                            imageCameraPreview?.setImageBitmap(rotatedBitmap)
                            imageCameraPreview?.visibility = View.VISIBLE
                            if (!hasReceivedFirstFrame) {
                                hasReceivedFirstFrame = true
                                textLoadingPreview?.visibility = View.GONE
                                stopLoadingDotsAnimation()
                            }
                            previewHandler?.postDelayed(previewRunnable!!, previewInterval)
                        }
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            previewHandler?.postDelayed(previewRunnable!!, previewInterval)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("DialogPickers", "Calibration preview frame error: ${e.message}")
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        previewHandler?.postDelayed(previewRunnable!!, previewInterval)
                    }
                }
            }
        }

        fun updateMotionGraph() {
            val motionScore = settingsCallbacks?.getCurrentMotionScore() ?: 0.0
            motionGraphView?.addDataPoint(motionScore)
        }

        // Start camera preview and graph
        val disablePreview = com.dashieapp.Dashie.halite.diagnostics.DeviceCapabilities.shouldDisableCameraPreview(activity)
        if (disablePreview) {
            cameraPreviewContainer?.visibility = View.VISIBLE
            textLoadingPreview?.visibility = View.VISIBLE
            textLoadingPreview?.text = "Video preview disabled\n(low available memory)"
            imageCameraPreview?.visibility = View.GONE
        } else {
            ensureRtspForDebugging()
            cameraPreviewContainer?.visibility = View.VISIBLE
            textLoadingPreview?.visibility = View.VISIBLE
            imageCameraPreview?.visibility = View.GONE
            hasReceivedFirstFrame = false
            startLoadingDotsAnimation()

            previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
            previewRunnable = object : Runnable {
                override fun run() { updateCameraPreview() }
            }
            previewHandler?.postDelayed(previewRunnable!!, 2000)
        }

        settingsCallbacks?.enableMotionGraphMode()
        motionGraphView?.visibility = if (showThresholdAndGraph) View.VISIBLE else View.GONE

        graphHandler = android.os.Handler(android.os.Looper.getMainLooper())
        graphRunnable = object : Runnable {
            override fun run() {
                updateMotionGraph()
                graphHandler?.postDelayed(this, 250)
            }
        }
        graphHandler?.postDelayed(graphRunnable!!, 1000)

        // Face mode: show face indicator + distance selector
        if (mode == "face") {
            textFaceIndicator?.visibility = View.VISIBLE
            distanceContainer?.visibility = View.VISIBLE
            settingsCallbacks?.updateFaceDistance(ScreensaverPreferences.faceDistanceToPercent(selectedDistance))
            settingsCallbacks?.setFaceTestCallback { faceResult ->
                activity.runOnUiThread {
                    val borderWidth = (6 * activity.resources.displayMetrics.density).toInt()
                    when (faceResult) {
                        FaceWakeDetector.FACE_RESULT_DETECTED -> {
                            textFaceIndicator?.text = "Face Detected"
                            textFaceIndicator?.setTextColor(0xFF4CAF50.toInt())
                            val border = android.graphics.drawable.GradientDrawable()
                            border.setStroke(borderWidth, 0xFF4CAF50.toInt())
                            cameraPreviewContainer?.foreground = border
                        }
                        FaceWakeDetector.FACE_RESULT_TOO_FAR -> {
                            textFaceIndicator?.text = "Too Far"
                            textFaceIndicator?.setTextColor(0xFFFFC107.toInt())
                            val border = android.graphics.drawable.GradientDrawable()
                            border.setStroke(borderWidth, 0xFFFFC107.toInt())
                            cameraPreviewContainer?.foreground = border
                        }
                        else -> {
                            textFaceIndicator?.text = "No Face"
                            textFaceIndicator?.setTextColor(0xFF888888.toInt())
                            cameraPreviewContainer?.foreground = null
                        }
                    }
                }
            }
        }

        // SeekBar listener (camera mode)
        seekBarThreshold?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentThresholdTenths = progressToTenths(progress)
                    textThresholdValue.text = SidebarFormatters.formatThreshold(currentThresholdTenths)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // +/- buttons (camera mode)
        buttonThresholdPlus.setOnClickListener {
            if (currentThresholdTenths < 10) {
                currentThresholdTenths = (currentThresholdTenths + 1).coerceAtMost(200)
            } else {
                val snapped = snapToHalf(currentThresholdTenths)
                currentThresholdTenths = (snapped + 5).coerceAtMost(200)
            }
            updateThresholdUI()
        }

        buttonThresholdMinus.setOnClickListener {
            if (currentThresholdTenths <= 10) {
                currentThresholdTenths = (currentThresholdTenths - 1).coerceAtLeast(2)
            } else {
                val snapped = snapToHalf(currentThresholdTenths)
                currentThresholdTenths = (snapped - 5).coerceAtLeast(2)
            }
            updateThresholdUI()
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            // SettingsActivity finishes itself before sending the broadcast, so this
            // dialog shows normally on MainActivity — no overlay type needed
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            // Save threshold (camera mode) or distance (face mode)
            if (mode == "camera") {
                halitePrefs.screensaver.cameraWakeThresholdTenths = currentThresholdTenths
            }
            if (mode == "face") {
                halitePrefs.screensaver.faceWakeDistance = selectedDistance
            }
            Log.i(TAG, "🔧 Wake calibration saved: mode=$mode" +
                if (mode == "camera") ", threshold=${SidebarFormatters.formatThreshold(currentThresholdTenths)}"
                else ", distance=$selectedDistance")

            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            previewRunnable?.let { previewHandler?.removeCallbacks(it) }
            graphRunnable?.let { graphHandler?.removeCallbacks(it) }
            stopLoadingDotsAnimation()
            settingsCallbacks?.disableMotionGraphMode()
            settingsCallbacks?.setFaceTestCallback(null)
            if (startedRtspTemporarily) {
                settingsCallbacks?.stopRtspForDebugging()
                Log.i(TAG, "📹 Stopped temporary RTSP for wake calibration")
            }
            currentPreviewBitmap?.recycle()
            currentPreviewBitmap = null
            backgroundThread.quitSafely()
            onDismiss()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Show camera permission explanation dialog
     */
    fun showCameraPermissionExplanation(onContinue: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Camera Permission"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Camera motion detection uses your device's front camera to detect movement and wake the screen.\n\n" +
            "The camera feed is processed locally and is never stored or transmitted."

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Enable"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            onContinue()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Show confirmation dialog when enabling Lock Nav Bar
     */
    fun showLockNavigationConfirmationDialog(onConfirm: () -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Lock Nav Bar?"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "This prevents the navigation bar from opening while ${activity.brandName()} is running.\n\n" +
            "To unlock, go to Settings > Lock Nav Bar and disable."

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Lock"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Show a toast hint (used for settings that require restart)
     */
    private fun showRestartHint(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
    // Removed: showVoicePipelinePicker + buildPipelineDescription + fetchPipelinesFromHa
    // (migrated to PipelinePickerFragment + VoiceApiDialogs public helpers).

}
