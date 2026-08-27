package com.dashieapp.Dashie

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Handles all permission-related logic for MainActivity.
 * Extracted to reduce MainActivity size and improve maintainability.
 *
 * Responsibilities:
 * - Registering permission launchers
 * - Checking and requesting permissions (microphone, camera, storage)
 * - RTSP permission flow (camera + audio)
 * - Showing permission dialogs and instructions
 */
class MainPermissionDelegate(
    private val activity: ComponentActivity,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "MainPermissionDelegate"

        // Persisted progress of the onboarding permission sweep. Individual grant
        // states are NOT a reliable "user finished the sweep" signal — the sweep is
        // best-effort (it continues past denials, device-admin activation silently
        // fails on Fire OS, the battery-exemption dialog is flaky/deniable). What IS
        // reliable: whether the sweep itself reached its end, persisted at "All done".
        private const val SWEEP_PREFS = "dashie_onboarding_sweep"
        private const val KEY_SWEEP_COMPLETED = "sweep_completed"

        internal fun sweepPrefs(context: Context) =
            context.getSharedPreferences(SWEEP_PREFS, Context.MODE_PRIVATE)

        /**
         * True when the user already went through the onboarding permission sweep
         * ([requestAllOnboardingPermissions]) — used by the onboarding JS resume path
         * (DashieNative.areOnboardingPermissionsGranted) to complete onboarding
         * directly after a mid-onboarding process kill (memory pressure on low-RAM
         * devices) instead of replaying the wizard.
         *
         *  1. sweep recorded as completed → true
         *  2. fallback: every permission the sweep requests is already satisfied
         *     (covers installs that completed the sweep before completion was
         *     persisted; battery/device-admin excluded as unreliable on Fire OS)
         */
        fun areOnboardingPermissionsSatisfied(context: Context): Boolean {
            val isTv = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
                com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(context)
            if (isTv) return true // sweep no-ops on TV (see requestAllOnboardingPermissions)

            // 1: persisted sweep progress — the authoritative signal
            if (sweepPrefs(context).getBoolean(KEY_SWEEP_COMPLETED, false)) return true

            // 2: grant-state fallback. Battery exemption and device admin are
            // deliberately NOT checked — both are best-effort in the sweep
            // (deniable / silently failing on Fire OS), so requiring them would
            // make this never-true on exactly the devices that need it.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) return false
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                if (alarmManager?.canScheduleExactAlarms() != true) return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.System.canWrite(context)
            ) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !android.provider.Settings.canDrawOverlays(context)
            ) return false
            return true
        }
    }

    interface Callbacks {
        fun isAllowUrlConfig(): Boolean
        fun initializeVoiceAssistant()
        fun notifyWebView(event: String, data: String)
        fun getHaliteScreenController(): Any?
        fun getHaliteVoiceController(): Any?
        fun getDialogHost(): Any?
        fun getHalitePrefs(): Any?
        fun startRtspServerWithCameraRelease()
        fun getDashieServiceManager(): Any?
        fun refreshMotionWake()
        fun disableRtspMotionMode()
        fun isDeviceAdminEnabled(): Boolean
    }

    // Permission launchers
    lateinit var permissionLauncher: ActivityResultLauncher<String>
        private set
    lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
        private set
    lateinit var storagePermissionLauncher: ActivityResultLauncher<String>
        private set
    lateinit var folderPickerLauncher: ActivityResultLauncher<Uri?>
        private set
    lateinit var haliteVoicePermissionLauncher: ActivityResultLauncher<String>
        private set
    lateinit var rtspPermissionLauncher: ActivityResultLauncher<Array<String>>
        private set

    // Track if mic permission dialog was shown (only show once per session)
    private var micPermissionDialogShown = false

    // Track if we're actively requesting API permissions (prevents duplicate dialogs on app resume)
    private var isRequestingApiPermissions = false

    // Track if we have pending settings-based permissions (Device Admin, Exact Alarm)
    private var hasPendingSettingsPermissions = false

    // Track last time we checked permissions (prevents rapid duplicate calls)
    private var lastPermissionCheckTime = 0L
    private val PERMISSION_CHECK_THROTTLE_MS = 60000L  // Don't check more than once per 60 seconds

    /**
     * Register all permission launchers. Must be called in onCreate before setContentView.
     */
    fun registerLaunchers() {
        // Initialize permission launcher for microphone access
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Microphone permission granted")
                callbacks.initializeVoiceAssistant()
            } else {
                Log.e(TAG, "Microphone permission denied")
                callbacks.notifyWebView("voicePermissionDenied", "")
            }
        }

        // Onboarding mic permission launcher (continues sequential flow after grant/deny)
        onboardingMicLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            Log.i(TAG, "🔐 Onboarding mic permission ${if (isGranted) "granted" else "denied"}")
            if (isGranted) callbacks.initializeVoiceAssistant()
            advanceOnboardingPermissions()
        }

        // Onboarding camera permission launcher (continues sequential flow after grant/deny)
        onboardingCameraLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            Log.i(TAG, "🔐 Onboarding camera permission ${if (isGranted) "granted" else "denied"}")
            advanceOnboardingPermissions()
        }

        // Initialize camera permission launcher for motion detection (Halite)
        cameraPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            Log.i(TAG, "🔧 Halite: Camera permission ${if (isGranted) "granted" else "denied"}")
            (callbacks.getHaliteScreenController() as? com.dashieapp.Dashie.halite.HaliteScreenController)
                ?.onCameraPermissionResultReceived(isGranted)
        }

        // Initialize storage permission launcher for photo screensaver (Halite)
        storagePermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            Log.i(TAG, "🔧 Halite: Storage permission ${if (isGranted) "granted" else "denied"}")
            (callbacks.getHaliteScreenController() as? com.dashieapp.Dashie.halite.HaliteScreenController)
                ?.onStoragePermissionResult(isGranted)
        }

        // Initialize folder picker launcher for photo screensaver (Halite)
        folderPickerLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                Log.i(TAG, "🔧 Halite: Folder selected: $uri")
                // Take persistable permission so we can access the folder after restart
                try {
                    activity.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "🔧 Could not take persistable permission: ${e.message}")
                }
                (callbacks.getHaliteScreenController() as? com.dashieapp.Dashie.halite.HaliteScreenController)
                    ?.onFolderSelected(uri)
            } else {
                Log.i(TAG, "🔧 Halite: Folder picker cancelled")
            }
        }

        // Initialize Halite voice permission launcher
        haliteVoicePermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            val haliteVoiceController = callbacks.getHaliteVoiceController()
                as? com.dashieapp.Dashie.halite.voice.HaliteVoiceController
            if (isGranted) {
                haliteVoiceController?.onPermissionGranted()
            } else {
                haliteVoiceController?.onPermissionDenied()
            }
        }

        // Initialize RTSP permission launcher (needs both CAMERA and RECORD_AUDIO)
        rtspPermissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val cameraGranted = permissions[Manifest.permission.CAMERA] == true
            val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
            Log.i(TAG, "🔧 RTSP permissions callback: camera=$cameraGranted, audio=$audioGranted, isRequestingApiPermissions=$isRequestingApiPermissions")
            onRtspPermissionsResult(cameraGranted && audioGranted)
        }
    }

    /**
     * Check microphone permission and initialize voice assistant if granted.
     * For Standard Dashie (non-Halite) only.
     */
    fun checkMicrophonePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            when {
                ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                    callbacks.initializeVoiceAssistant()
                }
                activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                    // Show explanation and request permission
                    Log.d(TAG, "Should show permission rationale")
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                else -> {
                    // Request permission
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        } else {
            // Permission granted by default on older Android versions
            callbacks.initializeVoiceAssistant()
        }
    }

    /**
     * Check if microphone permission is granted.
     */
    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Handle RTSP enabled toggle change from sidebar.
     * Checks for required permissions before enabling RTSP.
     */
    fun handleRtspEnabledChange(enabled: Boolean) {
        Log.i(TAG, "handleRtspEnabledChange: enabled=$enabled")
        if (enabled) {
            // Check both camera and microphone permissions for RTSP streaming
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val hasMicPermission = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasCameraPermission || !hasMicPermission) {
                Log.i(TAG, "handleRtspEnabledChange: Missing permissions (camera=$hasCameraPermission, mic=$hasMicPermission)")

                // If API is enabled, use checkAndRequestApiPermissions() which shows a
                // coordinated dialog for all API-related permissions
                val halitePrefs = callbacks.getHalitePrefs() as? com.dashieapp.Dashie.halite.HalitePreferences
                if (halitePrefs?.connection?.apiEnabled == true) {
                    Log.i(TAG, "handleRtspEnabledChange: API enabled - requesting via checkAndRequestApiPermissions()")
                    checkAndRequestApiPermissions()
                    return
                }

                // API not enabled - request permissions immediately for standalone RTSP use
                Log.i(TAG, "handleRtspEnabledChange: API not enabled - requesting permissions directly")
                rtspPermissionLauncher.launch(arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                ))
                return
            }

            // Both permissions already granted, start RTSP
            callbacks.startRtspServerWithCameraRelease()
        } else {
            // User is explicitly disabling RTSP streaming
            Log.i(TAG, "handleRtspEnabledChange: User disabled RTSP - stopping server")
            val dashieServiceManager = callbacks.getDashieServiceManager()
                as? com.dashieapp.Dashie.api.DashieServiceManager
            dashieServiceManager?.stopRtspServer()

            // Add delay before notifying screen controller
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Log.i(TAG, "handleRtspEnabledChange: Notifying screen controller after RTSP stop")
                callbacks.refreshMotionWake()
                callbacks.disableRtspMotionMode()
            }, 500) // 500ms delay for RTSP release
        }
    }

    /**
     * Handle RTSP permission result (camera + microphone).
     */
    private fun onRtspPermissionsResult(allGranted: Boolean) {
        val halitePrefs = callbacks.getHalitePrefs()
            as? com.dashieapp.Dashie.halite.HalitePreferences
        // Clear the API permission request flag if it was set
        // But only if we don't have pending settings-based permissions (Device Admin, Exact Alarm)
        if (isRequestingApiPermissions && !hasPendingSettingsPermissions) {
            isRequestingApiPermissions = false
            Log.w(TAG, "🔐 FLAG CLEARED: isRequestingApiPermissions = false (permission callback, no settings pending)")
        } else if (isRequestingApiPermissions && hasPendingSettingsPermissions) {
            Log.w(TAG, "🔐 FLAG NOT CLEARED: Settings-based permissions still pending")
        }

        if (allGranted) {
            Log.i(TAG, "onRtspPermissionsResult: All permissions granted, starting RTSP")
            callbacks.startRtspServerWithCameraRelease()
        } else {
            Log.w(TAG, "onRtspPermissionsResult: Permissions denied, disabling RTSP")
            // Revert the setting since permission was denied
            halitePrefs?.camera?.rtspEnabled = false

            // Check if permissions are permanently denied (user selected "Don't ask again")
            val hasCameraPermission = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val hasMicPermission = ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val cameraPermanentlyDenied = !hasCameraPermission &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            val micPermanentlyDenied = !hasMicPermission &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)

            if (cameraPermanentlyDenied || micPermanentlyDenied) {
                // Permission was permanently denied - show settings dialog
                showPermissionSettingsDialog()
            } else {
                // Permission was just denied this time (user can be asked again)
                Toast.makeText(
                    activity,
                    "Camera and microphone permissions required for streaming",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Show dialog directing user to app settings to enable permissions.
     * Called when permissions were permanently denied ("Don't ask again").
     */
    private fun showPermissionSettingsDialog() {
        Log.i(TAG, "showPermissionSettingsDialog: Showing settings dialog for permanently denied permissions")
        val halitePrefs = callbacks.getHalitePrefs()
            as? com.dashieapp.Dashie.halite.HalitePreferences
        // Revert the setting since we can't enable streaming without permissions
        halitePrefs?.camera?.rtspEnabled = false

        AlertDialog.Builder(activity)
            .setTitle("Permissions Required")
            .setMessage("Camera and microphone permissions are required for video streaming. Please enable them in app settings.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", activity.packageName, null)
                activity.startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show instructions for granting microphone permission on Fire TV.
     * Fire TV doesn't show permission dialogs, so users need to grant manually.
     */
    fun showMicrophonePermissionInstructions() {
        // Only show once per session to avoid being annoying
        if (micPermissionDialogShown) return
        micPermissionDialogShown = true

        val appName = activity.getString(R.string.app_name)
        val message = "Microphone permission is required for voice control.\n\n" +
                "Go to Settings → Applications → Manage Installed Applications → $appName → Permissions " +
                "and enable Microphone access."

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_simple_message, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle)?.text = "Microphone Permission Required"
        dialogView.findViewById<TextView>(R.id.dialogMessage)?.text = message

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Hide cancel button, only show OK
        dialogView.findViewById<Button>(R.id.buttonNegative)?.visibility = View.GONE
        dialogView.findViewById<Button>(R.id.buttonPositive)?.apply {
            text = "OK"
            setOnClickListener { dialog.dismiss() }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnConfirm(dialogView)
        Log.i(TAG, "🎤 Showing microphone permission instructions dialog")
    }

    /**
     * Check and request permissions needed for API control features.
     * Shows a dialog explaining what's needed, then requests missing permissions.
     *
     * This is called when:
     * - User enables API in settings
     * - App launches and detects API is enabled but permissions are missing
     */
    fun checkAndRequestApiPermissions(force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        Log.i(TAG, "🔐 checkAndRequestApiPermissions: ENTRY - isRequestingApiPermissions=$isRequestingApiPermissions")

        // Throttle rapid duplicate calls (prevents multiple dialogs from stacking)
        val timeSinceLastCheck = currentTime - lastPermissionCheckTime
        if (timeSinceLastCheck < PERMISSION_CHECK_THROTTLE_MS) {
            Log.w(TAG, "🔐 checkAndRequestApiPermissions: THROTTLED - Called too soon (${timeSinceLastCheck}ms since last check)")
            return
        }

        // Skip if already actively requesting permissions (prevents duplicate dialogs on app resume)
        if (isRequestingApiPermissions) {
            Log.w(TAG, "🔐 checkAndRequestApiPermissions: SKIPPED - Already requesting permissions (flag is true)")
            return
        }

        val halitePrefs = callbacks.getHalitePrefs() as? com.dashieapp.Dashie.halite.HalitePreferences
        // Proceed when the on-device API server OR Home Assistant is enabled.
        // An HA kiosk needs the same camera/mic/device-admin/exact-alarm/overlay
        // permissions HA-first onboarding requests, even if the API server is off.
        val haEnabled = halitePrefs?.connection?.haEnabled == true
        if (halitePrefs?.connection?.apiEnabled != true && !haEnabled) {
            Log.d(TAG, "checkAndRequestApiPermissions: API and HA both disabled, skipping")
            return
        }

        // Skip if user permanently declined the permission prompt (unless forced from settings)
        if (!force && halitePrefs.display.permissionPromptDeclined) {
            Log.d(TAG, "checkAndRequestApiPermissions: User permanently declined, skipping")
            return
        }
        // If forced, reset the decline flag so auto-prompts resume
        if (force && halitePrefs.display.permissionPromptDeclined) {
            halitePrefs.display.permissionPromptDeclined = false
            Log.i(TAG, "🔐 Reset permission prompt decline (forced from settings)")
        }

        // Update last check time
        lastPermissionCheckTime = currentTime

        Log.d(TAG, "checkAndRequestApiPermissions: API enabled, checking all permissions for remote control capability")

        val missingPermissions = mutableListOf<String>()
        val missingDescriptions = mutableListOf<String>()

        // Check camera and microphone permissions (needed for RTSP video streaming via API)
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val hasMicPermission = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            missingPermissions.add(Manifest.permission.CAMERA)
            missingDescriptions.add("Camera (for video streaming)")
        }
        if (!hasMicPermission) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
            missingDescriptions.add("Microphone (for video streaming)")
        }

        // Check Device Admin (needed for hardware screen off via API)
        // Skip on TV devices - screen off via device admin is not useful on TV/streaming sticks
        val isTvDevice = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
            com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(activity)
        val needsDeviceAdmin = !isTvDevice && !callbacks.isDeviceAdminEnabled()

        // Check SCHEDULE_EXACT_ALARM (needed for restart app via API on Android 12+)
        // This is also a special permission that requires Settings navigation
        val needsExactAlarm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val canSchedule = alarmManager.canScheduleExactAlarms()
            !canSchedule
        } else {
            false  // Not needed before Android 12
        }

        // Check Battery Optimization Exemption (critical for always-on dashboard stability)
        val needsBatteryOptimization = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            !com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(activity)
        } else {
            false
        }

        // If all permissions are granted, we're done
        if (missingPermissions.isEmpty() && !needsDeviceAdmin && !needsExactAlarm && !needsBatteryOptimization) {
            Log.d(TAG, "checkAndRequestApiPermissions: All required permissions granted")
            return
        }

        // Build the permission list message
        val permissionList = buildString {
            missingDescriptions.forEach { desc ->
                append("• $desc\n")
            }
            if (needsDeviceAdmin) {
                append("• Device Admin (for screen off capability)\n")
            }
            if (needsExactAlarm) {
                append("• Exact Alarms (for app restart via API)\n")
            }
            if (needsBatteryOptimization) {
                append("• Battery Optimization Exemption (keeps Android from restricting or closing app to conserve battery)\n")
            }
        }

        // Show dialog explaining what's needed
        Log.w(TAG, "🔐 SHOWING DIALOG: Device permissions dialog (missing: ${missingPermissions.size} perms, deviceAdmin=$needsDeviceAdmin, exactAlarm=$needsExactAlarm)")

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Device permissions"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Some of the permissions required for your configuration are not enabled:\n\n" +
            permissionList +
            "\nYour device will request them now."

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener {
                isRequestingApiPermissions = false
                hasPendingSettingsPermissions = false
                Log.w(TAG, "🔐 FLAGS CLEARED: isRequestingApiPermissions = false, hasPendingSettingsPermissions = false (dialog dismissed)")
            }
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Continue"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()

            // Set flag to prevent duplicate dialogs during permission flow
            isRequestingApiPermissions = true
            Log.w(TAG, "🔐 FLAG SET: isRequestingApiPermissions = true (user clicked Continue)")

            // Track if we need settings-based permissions
            hasPendingSettingsPermissions = needsDeviceAdmin || needsExactAlarm || needsBatteryOptimization
            if (hasPendingSettingsPermissions) {
                Log.w(TAG, "🔐 SETTINGS PENDING: Device Admin=$needsDeviceAdmin, Exact Alarm=$needsExactAlarm, Battery Opt=$needsBatteryOptimization")
            }

            var delayMs = 0L

            // Request standard permissions first (if any)
            if (missingPermissions.isNotEmpty()) {
                Log.i(TAG, "Requesting API permissions: ${missingPermissions.joinToString()}")
                rtspPermissionLauncher.launch(missingPermissions.toTypedArray())
                delayMs = 1000  // Delay next prompt
                // Note: Flag will be cleared in onRtspPermissionsResult() callback (if no settings pending)
            }

            // If Device Admin is needed, show that dialog
            if (needsDeviceAdmin) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    promptForDeviceAdmin()
                }, delayMs)
                delayMs += 1000  // Add delay for next prompt
            }

            // If Exact Alarms permission is needed, guide user to Settings
            if (needsExactAlarm) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    promptForExactAlarmPermission()
                }, delayMs)
                delayMs += 1000
            }

            // If Battery Optimization exemption is needed, request it
            if (needsBatteryOptimization) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    requestBatteryOptimizationExemption()
                }, delayMs)
            }

            // Only clear flags with timer if we have settings-based permissions
            // (Device Admin and Exact Alarm don't have callbacks, so we need a timer)
            // Use a longer timeout (30 seconds) to give user time to navigate Settings
            if (hasPendingSettingsPermissions) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    isRequestingApiPermissions = false
                    hasPendingSettingsPermissions = false
                    Log.w(TAG, "🔐 FLAGS CLEARED: isRequestingApiPermissions = false, hasPendingSettingsPermissions = false (timer)")
                }, delayMs + 30000)  // 30 seconds to allow user to navigate Settings
            }
            // If we're only requesting regular permissions (no settings), the flag will be cleared in the callback
        }

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Later"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
            // User cancelled - clear flags
            isRequestingApiPermissions = false
            hasPendingSettingsPermissions = false
            Log.w(TAG, "🔐 FLAGS CLEARED: isRequestingApiPermissions = false, hasPendingSettingsPermissions = false (user clicked Later)")
        }

        // "Don't ask again" link below the buttons — permanently suppresses the
        // auto-prompt (re-enabled via the settings force path). Plain text, not
        // a checkbox: theme checkbox tinting made the check mark invisible on
        // Echo Show 5, leaving no way to stop the prompt reappearing.
        val dontAskLink = dialogView.findViewById<TextView>(R.id.dialogLink)
        dontAskLink.text = "Don't ask again"
        dontAskLink.paintFlags = dontAskLink.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        dontAskLink.visibility = android.view.View.VISIBLE
        dontAskLink.setOnClickListener {
            dialog.dismiss()
            halitePrefs.display.permissionPromptDeclined = true
            isRequestingApiPermissions = false
            hasPendingSettingsPermissions = false
            Log.i(TAG, "🔐 User permanently declined permission prompt (Don't ask again)")
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)

        // Ensure both buttons are focusable for d-pad navigation
        // Note: do NOT set isFocusableInTouchMode — it causes double-tap on touchscreens
        val positiveBtn = dialogView.findViewById<Button>(R.id.buttonPositive)
        val negativeBtn = dialogView.findViewById<Button>(R.id.buttonNegative)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyBorderButtonFocusHighlight(negativeBtn, dialogStyle = true)
        positiveBtn.isFocusable = true
        negativeBtn.isFocusable = true
        positiveBtn.nextFocusLeftId = R.id.buttonNegative
        negativeBtn.nextFocusRightId = R.id.buttonPositive
        // D-pad traversal down to the link and back up — auto focus-search
        // can't reliably cross out of the nested horizontal button row
        dontAskLink.isFocusable = true
        positiveBtn.nextFocusDownId = R.id.dialogLink
        negativeBtn.nextFocusDownId = R.id.dialogLink
        dontAskLink.nextFocusUpId = R.id.buttonPositive
        positiveBtn.post { positiveBtn.requestFocus() }

        Log.i(TAG, "checkAndRequestApiPermissions: Showing permission dialog for ${missingPermissions.size} permissions + deviceAdmin=$needsDeviceAdmin + exactAlarm=$needsExactAlarm")
    }

    /**
     * Prompt for Device Admin permission.
     * Extracted from PerformanceDialogs for reuse.
     */
    private fun promptForDeviceAdmin() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Enable Screen Off"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Screen Off mode requires Device Admin permission to turn off the display hardware.\n\n" +
            "Enable Device Admin now?"

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Enable"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            activity.startActivity(
                com.dashieapp.Dashie.halite.DeviceAdminHelper.buildActivationIntent(
                    activity,
                    com.dashieapp.Dashie.halite.DeviceAdminHelper.screenOffExplanation(activity)
                )
            )
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Later"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)

        val positiveBtn = dialogView.findViewById<Button>(R.id.buttonPositive)
        val negativeBtn = dialogView.findViewById<Button>(R.id.buttonNegative)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyBorderButtonFocusHighlight(negativeBtn, dialogStyle = true)
        positiveBtn.isFocusable = true
        negativeBtn.isFocusable = true
        positiveBtn.nextFocusLeftId = R.id.buttonNegative
        negativeBtn.nextFocusRightId = R.id.buttonPositive
        positiveBtn.post { positiveBtn.requestFocus() }
    }

    /**
     * Prompt for Exact Alarm permission.
     * Needed for app restart via API on Android 12+.
     */
    private fun promptForExactAlarmPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return  // Not needed before Android 12
        }

        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_confirm, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Enable Exact Alarms"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "App restart via API requires Exact Alarms permission to schedule the restart reliably.\n\n" +
            "Enable Exact Alarms now?"

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Enable"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = android.net.Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                dialog.dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open exact alarm settings: ${e.message}")
                Toast.makeText(
                    activity,
                    "Could not open settings. Please enable Exact Alarms manually.",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
        }

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Later"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)

        val positiveBtn = dialogView.findViewById<Button>(R.id.buttonPositive)
        val negativeBtn = dialogView.findViewById<Button>(R.id.buttonNegative)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyBorderButtonFocusHighlight(negativeBtn, dialogStyle = true)
        positiveBtn.isFocusable = true
        negativeBtn.isFocusable = true
        positiveBtn.nextFocusLeftId = R.id.buttonNegative
        negativeBtn.nextFocusRightId = R.id.buttonPositive
        positiveBtn.post { positiveBtn.requestFocus() }
    }

    /**
     * Request battery optimization exemption via system dialog.
     */
    private fun requestBatteryOptimizationExemption() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return

        try {
            activity.startActivity(
                com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildRequestExemptionIntent(activity)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption: ${e.message}")
        }
    }

    // ============================================
    // Standalone Battery Optimization Check
    // ============================================

    /**
     * Check battery optimization on startup, independent of API permissions.
     * For a kiosk/always-on dashboard, battery exemption is critical to prevent
     * Android from aggressively killing the app — regardless of API being enabled.
     *
     * Only prompts once per install (respects batteryOptimizationPrompted flag).
     */
    fun checkBatteryOptimizationOnStartup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val halitePrefs = callbacks.getHalitePrefs() as? com.dashieapp.Dashie.halite.HalitePreferences
            ?: return

        // Don't prompt during onboarding — it handles battery optimization itself
        if (!halitePrefs.connection.isSetupComplete) return

        // Already prompted — don't nag
        if (halitePrefs.performance.batteryOptimizationPrompted) return

        // Already exempt — nothing to do
        if (com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(activity)) return

        // Skip if onboarding is in progress (it handles this itself)
        if (onboardingPermStep > 0) return

        // Skip if already showing a permission dialog
        if (isRequestingApiPermissions) return

        Log.i(TAG, "🔋 Battery optimization not exempt — prompting user")
        halitePrefs.performance.batteryOptimizationPrompted = true
        requestBatteryOptimizationExemption()
    }

    // ============================================
    // Onboarding: Sequential Permission Flow
    // ============================================

    /** Current step in onboarding permission sequence (0 = not active) */
    private var onboardingPermStep = 0

    /** Callback when all onboarding permissions have been requested */
    var onOnboardingPermissionsComplete: (() -> Unit)? = null

    /**
     * Request all needed permissions sequentially for the onboarding flow.
     * Walks through: battery → microphone → camera → device_admin → exact_alarm →
     * brightness → overlay → install-unknown-apps (self-update builds only).
     * Each settings-type permission opens a system screen; the flow continues on onResume.
     * When all are done, calls onOnboardingPermissionsComplete callback.
     *
     * TV devices (Fire TV / Android TV) skip the sweep entirely: there's no
     * camera, the mic is remote-only (requested on demand when voice is set
     * up, via requestOnboardingPermission()), and the device-admin/brightness
     * steps already no-op on TV. Bombarding a TV user with permission prompts
     * during onboarding isn't worth it.
     */
    fun requestAllOnboardingPermissions() {
        Log.i(TAG, "🔐 Starting onboarding permission flow (all at once)")
        val isTv = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
            com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(activity)
        if (isTv) {
            Log.i(TAG, "🔐 TV device — skipping the onboarding permission sweep")
            onboardingPermStep = 0
            onOnboardingPermissionsComplete?.invoke()
            return
        }
        onboardingPermStep = 0
        advanceOnboardingPermissions()
    }

    /**
     * Continue the onboarding permission flow after returning from system settings.
     * Called from MainLifecycleHandler.onResume().
     */
    fun continueOnboardingPermissionsIfNeeded() {
        if (onboardingPermStep > 0) {
            Log.i(TAG, "🔐 Continuing onboarding permissions from step $onboardingPermStep")
            advanceOnboardingPermissions()
        }
    }

    private fun advanceOnboardingPermissions() {
        val halitePrefs = callbacks.getHalitePrefs() as? com.dashieapp.Dashie.halite.HalitePreferences

        // Step 0→1: Battery Optimization Exemption (most critical for stability)
        // Without this, Android/Samsung may aggressively kill the app even while foreground
        if (onboardingPermStep == 0) {
            onboardingPermStep = 1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(activity)
            ) {
                Log.i(TAG, "🔐 Onboarding: requesting battery optimization exemption")
                try {
                    activity.startActivity(
                        com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildRequestExemptionIntent(activity)
                    )
                    return // Wait for onResume
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization: ${e.message}")
                }
            }
        }

        // Step 1→2: Microphone (runtime permission — shows system dialog, returns immediately)
        if (onboardingPermStep <= 1) {
            onboardingPermStep = 2
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "🔐 Onboarding: requesting microphone permission")
                // Use a dedicated launcher that continues the flow on completion
                onboardingMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
        }

        // Step 2→3: Camera (runtime permission — shows system dialog, returns immediately)
        if (onboardingPermStep <= 2) {
            onboardingPermStep = 3
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "🔐 Onboarding: requesting camera permission")
                onboardingCameraLauncher.launch(Manifest.permission.CAMERA)
                return
            }
        }

        // Step 3→4: Device admin (skip on TV — screen sleep/wake not relevant)
        if (onboardingPermStep <= 3) {
            onboardingPermStep = 4
            val isTvDevice = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
                com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(activity)
            if (!isTvDevice && !com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(activity)) {
                Log.i(TAG, "🔐 Onboarding: requesting device admin")
                try {
                    activity.startActivity(
                        com.dashieapp.Dashie.halite.DeviceAdminHelper.buildActivationIntent(activity)
                    )
                    return // Wait for onResume
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request device admin: ${e.message}")
                }
            }
        }

        // Step 4→5: Exact alarm
        if (onboardingPermStep <= 4) {
            onboardingPermStep = 5
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = activity.getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                if (alarmManager?.canScheduleExactAlarms() != true) {
                    Log.i(TAG, "🔐 Onboarding: requesting exact alarm")
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivity(intent)
                        return // Wait for onResume
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to request exact alarm: ${e.message}")
                    }
                }
            }
        }

        // Step 5→6: Brightness (WRITE_SETTINGS) — skip on TV (no screen brightness control)
        if (onboardingPermStep <= 5) {
            onboardingPermStep = 6
            val isTvForBrightness = com.dashieapp.Dashie.util.DeviceInfoHelper.isFireTV() ||
                com.dashieapp.Dashie.util.DeviceInfoHelper.isTVDevice(activity)
            if (!isTvForBrightness && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.System.canWrite(activity)) {
                Log.i(TAG, "🔐 Onboarding: requesting brightness/write settings")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    return // Wait for onResume
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request write settings: ${e.message}")
                }
            }
        }

        // Step 6→7: Overlay (SYSTEM_ALERT_WINDOW)
        if (onboardingPermStep <= 6) {
            onboardingPermStep = 7
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(activity)) {
                Log.i(TAG, "🔐 Onboarding: requesting overlay permission")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    return // Wait for onResume
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request overlay: ${e.message}")
                }
            }
        }

        // NOTE: install-unknown-apps (sideload self-update) is deliberately NOT
        // requested here. Granting that app-op force-restarts the app (Android
        // kills the process when REQUEST_INSTALL_PACKAGES changes), which used to
        // nuke onboarding right at the finish line. The update flow requests it
        // lazily instead — SideloadUpdateBackend checks ApkInstaller.canInstall()
        // and deep-links to the grant screen when an update actually installs.

        // All done
        Log.i(TAG, "🔐 Onboarding permission flow complete")
        onboardingPermStep = 0
        // Persist completion so the onboarding JS resume path can tell "user
        // finished the sweep" apart from "never reached it" after a process kill
        // (memory pressure on low-RAM devices) — individual grant states can't
        // distinguish those (denials are allowed, some grants are flaky on Fire OS).
        sweepPrefs(activity).edit().putBoolean(KEY_SWEEP_COMPLETED, true).apply()
        onOnboardingPermissionsComplete?.invoke()
    }

    /** Dedicated mic permission launcher for onboarding flow — continues to next step on result */
    lateinit var onboardingMicLauncher: ActivityResultLauncher<String>
        private set

    /** Dedicated camera permission launcher for onboarding flow — continues to next step on result */
    lateinit var onboardingCameraLauncher: ActivityResultLauncher<String>
        private set

    /**
     * Request a single permission by type string, used by the JS onboarding flow.
     * Each permission type opens the appropriate system dialog or settings page.
     */
    fun requestOnboardingPermission(type: String) {
        Log.i(TAG, "🔐 Onboarding permission request: $type")
        when (type) {
            "microphone" -> {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            "camera" -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            "brightness" -> {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open write settings: ${e.message}")
                }
            }
            "overlay" -> {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = android.net.Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open overlay settings: ${e.message}")
                }
            }
            "exact_alarm" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = android.net.Uri.parse("package:${activity.packageName}")
                        }
                        activity.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open exact alarm settings: ${e.message}")
                    }
                }
            }
            "battery_optimization" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        activity.startActivity(
                            com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildRequestExemptionIntent(activity)
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open battery optimization: ${e.message}")
                    }
                }
            }
            "device_admin" -> {
                callbacks.getHaliteScreenController()?.let {
                    if (!com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(activity)) {
                        activity.startActivity(
                            com.dashieapp.Dashie.halite.DeviceAdminHelper.buildActivationIntent(activity)
                        )
                    }
                }
            }
            else -> Log.w(TAG, "Unknown permission type: $type")
        }
    }
}
