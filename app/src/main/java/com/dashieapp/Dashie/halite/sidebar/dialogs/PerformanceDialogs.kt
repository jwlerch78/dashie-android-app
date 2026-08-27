package com.dashieapp.Dashie.halite.sidebar.dialogs

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.util.DeviceInfoHelper
import com.dashieapp.Dashie.halite.diagnostics.MediaCodecCapabilities
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.DashieDeviceAdminReceiver
import com.dashieapp.Dashie.halite.HaDiscovery
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.edition.brandName

/**
 * Handles Advanced settings dialogs for Dashie Lite.
 * Includes display (zoom), performance, battery optimization, WiFi lock, and auto-reload settings.
 *
 * Note: Formerly called "Performance" dialogs - renamed to "Advanced" to accommodate
 * display settings like zoom for smaller screens.
 */
class PerformanceDialogs(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "AdvancedDialogs"
    }

    // Motion wake diagnostics provider (set externally)
    var motionWakeDiagnosticsProvider: (() -> String)? = null
    var wifiLockHeldProvider: (() -> Boolean)? = null
    var cpuWakeLockHeldProvider: (() -> Boolean)? = null

    // Callbacks
    var onZoomChanged: ((Int) -> Unit)? = null  // Zoom percentage (50-200)
    var onWifiLockChanged: ((Boolean) -> Unit)? = null
    var onAutoReloadChanged: ((Int) -> Unit)? = null
    var onWebsocketPingChanged: ((Boolean) -> Unit)? = null
    var onSmartReconnectChanged: ((Boolean) -> Unit)? = null
    var onDialogDismissed: (() -> Unit)? = null

    // Store reference to hardware screen off switch for UI updates
    private var hardwareScreenOffSwitch: Switch? = null

    /**
     * Check if Device Admin is enabled (for hardware screen off).
     */
    private fun isDeviceAdminEnabled(): Boolean =
        com.dashieapp.Dashie.halite.DeviceAdminHelper.isActive(activity)

    /**
     * Prompt for Device Admin permission (same dialog as screensaver settings).
     */
    private fun promptForDeviceAdmin() {
        StyledConfirmDialog.show(
            activity,
            title = "Enable Screen Off",
            message = "Screen Off mode requires Device Admin permission — without it, the screen shows a black overlay instead of actually turning off.\n\n" +
                "Enable Device Admin now?",
            positiveText = "Enable",
            negativeText = "Later",
            onPositive = {
                activity.startActivity(
                    com.dashieapp.Dashie.halite.DeviceAdminHelper.buildActivationIntent(
                        activity,
                        com.dashieapp.Dashie.halite.DeviceAdminHelper.screenOffExplanation(activity)
                    )
                )
            }
        )
    }

    /**
     * Check if app is exempt from battery optimization
     */
    fun isBatteryOptimizationExempt(): Boolean =
        com.dashieapp.Dashie.halite.BatteryOptimizationHelper.isExempt(activity)

    /**
     * Request battery optimization exemption
     * This opens the system settings for the user to grant the exemption
     */
    fun requestBatteryOptimizationExemption() {
        try {
            activity.startActivity(
                com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildRequestExemptionIntent(activity)
            )
            halitePrefs.performance.batteryOptimizationPrompted = true
            Log.i(TAG, "Opened battery optimization settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization settings: ${e.message}")
            // Fallback to battery settings page
            try {
                activity.startActivity(
                    com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildSettingsListIntent()
                )
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open battery settings: ${e2.message}")
            }
        }
    }

    /**
     * Update the auto-reload value text
     */
    private fun updateAutoReloadText(textView: TextView) {
        val minutes = halitePrefs.performance.autoReloadStaleMinutes
        textView.text = when (minutes) {
            0 -> "Disabled"
            1 -> "1 min"
            else -> "$minutes min"
        }
    }

    /**
     * Update the zoom value text
     */
    private fun updateZoomText(textView: TextView?) {
        val zoom = halitePrefs.display.dashboardZoom
        textView?.text = "$zoom%"
    }

    /**
     * Show zoom level selection dialog
     */
    private fun showZoomOptionsDialog(valueTextView: TextView?) {
        val presetValues = intArrayOf(50, 75, 90, 100, 110, 125, 150, 175, 200)
        val currentValue = halitePrefs.display.dashboardZoom
        val isCustomValue = currentValue !in presetValues

        // Build options array - show current value in "Custom" label if it's a custom value
        val customLabel = if (isCustomValue) "Custom ($currentValue%)" else "Custom..."
        val options = arrayOf("50%", "75%", "90%", "100% (Default)", "110%", "125%", "150%", "175%", "200%", customLabel)
        val values = intArrayOf(50, 75, 90, 100, 110, 125, 150, 175, 200, -1)  // -1 = custom

        // If current value matches a preset, select it; otherwise select "Custom..."
        val presetIndex = presetValues.indexOfFirst { it == currentValue }
        val currentIndex = if (presetIndex >= 0) presetIndex else values.size - 1  // Custom is last option

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_picker, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Dashboard Zoom"

        val optionsGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.optionsGroup)
        val radioIds = mutableListOf<Int>()

        options.forEachIndexed { index, label ->
            val radioButton = LayoutInflater.from(activity).inflate(
                R.layout.dialog_picker_item, optionsGroup, false
            ) as android.widget.RadioButton
            radioButton.id = View.generateViewId()
            radioButton.text = label
            radioButton.isChecked = index == currentIndex
            radioIds.add(radioButton.id)
            optionsGroup.addView(radioButton)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            val selectedIdx = radioIds.indexOf(optionsGroup.checkedRadioButtonId)
            if (selectedIdx >= 0) {
                val newValue = values[selectedIdx]
                if (newValue == -1) {
                    // Custom value - show input dialog
                    dialog.dismiss()
                    showCustomZoomDialog(valueTextView)
                } else {
                    halitePrefs.display.dashboardZoom = newValue
                    updateZoomText(valueTextView)
                    onZoomChanged?.invoke(newValue)
                    Log.i(TAG, "Dashboard zoom set to $newValue%")
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        // Don't set default focus - let radio group handle it
        dialogView.post {
            optionsGroup.requestFocus()
        }
    }

    /**
     * Show dialog to enter a custom zoom value
     */
    private fun showCustomZoomDialog(valueTextView: TextView?) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Custom Zoom Level"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Enter a zoom percentage between ${HalitePreferences.MIN_DASHBOARD_ZOOM}% and ${HalitePreferences.MAX_DASHBOARD_ZOOM}%.\n\n" +
            "Values below 100% zoom out (show more content).\nValues above 100% zoom in (show less content, larger UI)."

        // Add EditText for custom value
        // Note: dialogMessage is inside a ScrollView, so we need to handle the hierarchy correctly
        val messageView = dialogView.findViewById<TextView>(R.id.dialogMessage)
        val scrollView = messageView.parent as? ScrollView

        val editText = EditText(activity).apply {
            hint = "e.g., 85"
            setText(halitePrefs.display.dashboardZoom.toString())
            textSize = 16f
            setTextColor(activity.getColor(R.color.text_primary))
            setHintTextColor(activity.getColor(R.color.text_disabled))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setPadding(24, 24, 24, 24)
            setBackgroundResource(R.drawable.edit_text_background)
        }

        // Create a container LinearLayout for message + edittext inside the ScrollView
        if (scrollView != null) {
            scrollView.removeView(messageView)
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(messageView)
            container.addView(editText)
            (editText.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 16
                bottomMargin = 8
            }
            scrollView.addView(container)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Apply"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            val input = editText.text.toString().trim()
            val zoomValue = input.toIntOrNull()

            if (zoomValue == null || zoomValue < HalitePreferences.MIN_DASHBOARD_ZOOM || zoomValue > HalitePreferences.MAX_DASHBOARD_ZOOM) {
                Toast.makeText(
                    activity,
                    "Please enter a value between ${HalitePreferences.MIN_DASHBOARD_ZOOM} and ${HalitePreferences.MAX_DASHBOARD_ZOOM}",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            halitePrefs.display.dashboardZoom = zoomValue
            updateZoomText(valueTextView)
            onZoomChanged?.invoke(zoomValue)
            Log.i(TAG, "Dashboard zoom set to custom value: $zoomValue%")
            dialog.dismiss()
            Toast.makeText(activity, "Zoom set to $zoomValue%", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)

        // Show keyboard
        editText.requestFocus()
        editText.selectAll()
    }

    /**
     * Show battery optimization explanation dialog
     */
    private fun showBatteryOptimizationExplanationDialog(onContinue: () -> Unit) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Keep Dashboard Connected"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text =
            "Android may close your dashboard's connection when the device is idle to save battery.\n\n" +
            "Exempting ${activity.brandName()} from battery optimization helps keep your dashboard live and up-to-date.\n\n" +
            "Tap Continue to open Android settings."

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Continue"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            dialog.dismiss()
            onContinue()
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    /**
     * Called when returning from Device Admin settings to update toggle state.
     * Should be called from MainActivity.onResume() when Device Admin is granted.
     */
    fun onDeviceAdminResult(granted: Boolean) {
        if (granted) {
            halitePrefs.sleep.hardwareScreenOff = true
            // Update the switch UI if the dialog is still open
            hardwareScreenOffSwitch?.isChecked = true
            Log.i(TAG, "Device Admin granted - hardware screen off enabled")
            Toast.makeText(activity, "Hardware screen off enabled", Toast.LENGTH_SHORT).show()
        } else {
            halitePrefs.sleep.hardwareScreenOff = false
            hardwareScreenOffSwitch?.isChecked = false
            Log.i(TAG, "Device Admin denied - using black overlay")
        }
    }

    /**
     * Show auto-reload options dialog
     */
    private fun showAutoReloadOptionsDialog(valueTextView: TextView) {
        val options = arrayOf("Disabled", "2 minutes", "3 minutes", "5 minutes", "10 minutes")
        val values = intArrayOf(0, 2, 3, 5, 10)
        val currentValue = halitePrefs.performance.autoReloadStaleMinutes
        val currentIndex = values.indexOf(currentValue).takeIf { it >= 0 } ?: 2  // Default to 3 min

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_picker, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Auto-Reload on HA Idle"

        val optionsGroup = dialogView.findViewById<android.widget.RadioGroup>(R.id.optionsGroup)
        val radioIds = mutableListOf<Int>()

        options.forEachIndexed { index, label ->
            val radioButton = LayoutInflater.from(activity).inflate(
                R.layout.dialog_picker_item, optionsGroup, false
            ) as android.widget.RadioButton
            radioButton.id = View.generateViewId()
            radioButton.text = label
            radioButton.isChecked = index == currentIndex
            radioIds.add(radioButton.id)
            optionsGroup.addView(radioButton)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonSave).setOnClickListener {
            val selectedIdx = radioIds.indexOf(optionsGroup.checkedRadioButtonId)
            if (selectedIdx >= 0) {
                val newValue = values[selectedIdx]
                halitePrefs.performance.autoReloadStaleMinutes = newValue
                updateAutoReloadText(valueTextView)
                onAutoReloadChanged?.invoke(newValue)
                Log.i(TAG, "Auto-reload set to $newValue minutes")
                dialog.dismiss()
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        // Don't set default focus - let radio group handle it
        dialogView.post {
            optionsGroup.requestFocus()
        }
    }

    /**
     * Show dialog to enter local HA URL with autodetection
     */
    private fun showLocalHaUrlDialog(onSaved: () -> Unit) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm, null)

        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Local Home Assistant URL"
        val messageView = dialogView.findViewById<TextView>(R.id.dialogMessage)

        // Initial message - will be updated if HA is detected
        val baseMessage = "Enter your local Home Assistant URL (without HTTPS proxy).\n\n" +
            "This is used to ping HA directly during reconnection to avoid triggering rate limits on your reverse proxy."
        messageView.text = "$baseMessage\n\nSearching for Home Assistant..."

        // Note: dialogMessage is inside a ScrollView, so we need to handle the hierarchy correctly
        val scrollView = messageView.parent as? ScrollView

        val editText = EditText(activity).apply {
            hint = "http://192.168.1.x:8123"
            setText(halitePrefs.performance.localHaUrl)
            textSize = 14f
            setTextColor(activity.getColor(R.color.text_primary))
            setHintTextColor(activity.getColor(R.color.text_disabled))
            setSingleLine(true)
            setPadding(24, 24, 24, 24)
            setBackgroundResource(R.drawable.edit_text_background)
        }

        // Create a container LinearLayout for message + edittext inside the ScrollView
        if (scrollView != null) {
            scrollView.removeView(messageView)
            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(messageView)
            container.addView(editText)
            (editText.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = 16
                bottomMargin = 8
            }
            scrollView.addView(container)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        // Start HA autodetection in background
        var discoveryThread: Thread? = null
        var isDiscoveryRunning = true
        val haDiscovery = HaDiscovery(activity)

        discoveryThread = Thread {
            haDiscovery.discoverInstances(object : HaDiscovery.DiscoveryCallback {
                override fun onInstanceFound(instance: HaDiscovery.DiscoveredInstance) {
                    if (!isDiscoveryRunning) return
                    activity.runOnUiThread {
                        val detectedUrl = instance.url
                        // Update message to show detected URL
                        messageView.text = "$baseMessage\n\nDetected: $detectedUrl"
                        // Auto-fill if empty (no existing URL set)
                        if (editText.text.isEmpty() || editText.text.toString() == "http://192.168.1.x:8123") {
                            editText.setText(detectedUrl)
                        }
                        Log.i(TAG, "Auto-detected HA at: $detectedUrl")
                    }
                }

                override fun onDiscoveryComplete(instances: List<HaDiscovery.DiscoveredInstance>) {
                    if (!isDiscoveryRunning) return
                    activity.runOnUiThread {
                        if (instances.isEmpty()) {
                            // No HA found - show example
                            messageView.text = "$baseMessage\n\nExample: http://192.168.1.100:8123"
                        } else if (instances.size > 1) {
                            // Multiple found - show first one (already set by onInstanceFound)
                            messageView.text = "$baseMessage\n\nFound ${instances.size} instances. Using: ${instances.first().url}"
                        }
                    }
                }

                override fun onProgress(scannedCount: Int, totalCount: Int) {
                    // Optional: could show scan progress
                }

                override fun onError(message: String) {
                    Log.w(TAG, "HA Discovery error: $message")
                    activity.runOnUiThread {
                        messageView.text = "$baseMessage\n\nExample: http://192.168.1.100:8123"
                    }
                }
            }, timeoutMs = 10000)  // 10 second timeout for dialog
        }
        discoveryThread.start()

        // Stop discovery when dialog is dismissed
        dialog.setOnDismissListener {
            isDiscoveryRunning = false
            discoveryThread?.interrupt()
        }

        dialogView.findViewById<Button>(R.id.buttonNegative).text = "Cancel"
        dialogView.findViewById<Button>(R.id.buttonNegative).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.buttonPositive).text = "Save"
        dialogView.findViewById<Button>(R.id.buttonPositive).setOnClickListener {
            val url = editText.text.toString().trim()
            halitePrefs.performance.localHaUrl = url
            Log.i(TAG, "Local HA URL set to: $url")
            dialog.dismiss()
            onSaved()

            if (url.isNotBlank()) {
                Toast.makeText(activity, "Local HA URL saved", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
        DialogHelper.setDefaultFocusOnCancel(dialogView)

        // Show keyboard
        editText.requestFocus()
    }

    /**
     * Show info dialog
     */
    private fun showInfoDialog(title: String, message: String) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Open Android Settings with multiple fallbacks for different devices.
     * Fire OS doesn't support ACTION_HOME_SETTINGS, so we try multiple approaches.
     * Uses resolveActivity() to check if intent can be handled before launching.
     */
    private fun openAndroidSettings() {
        val pm = activity.packageManager

        // Try different intents in order of preference
        // App details first since it works reliably on Fire OS
        val intentsToTry = listOf(
            // 1. App details for this app - works on Fire OS
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            },
            // 2. General settings
            Intent(Settings.ACTION_SETTINGS),
            // 3. Home app settings (standard Android)
            Intent(Settings.ACTION_HOME_SETTINGS),
            // 4. Manage default apps
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        )

        for (intent in intentsToTry) {
            // Check if this intent can actually be handled
            if (pm.resolveActivity(intent, 0) != null) {
                try {
                    activity.startActivity(intent)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Intent ${intent.action} failed: ${e.message}")
                }
            }
        }

        // All failed
        Toast.makeText(activity, "Unable to open settings on this device", Toast.LENGTH_LONG).show()
    }

    /**
     * Show dialog when battery optimization is already exempt
     * Gives user option to open settings to re-enable optimization
     */
    private fun showBatteryExemptDialog() {
        AlertDialog.Builder(activity)
            .setTitle("Battery Optimization Exempt")
            .setMessage(
                "${activity.brandName()} is exempt from battery optimization, which helps keep your dashboard connected.\n\n" +
                "To re-enable battery optimization, open Android settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    activity.startActivity(
                        com.dashieapp.Dashie.halite.BatteryOptimizationHelper.buildSettingsListIntent()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open battery settings: ${e.message}")
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Get status summary for sidebar display
     * Returns empty string - we no longer show "issues" count
     */
    fun getStatusSummary(): String = ""

    /**
     * Get the color for the status summary.
     */
    fun getStatusColor(): Int {
        return if (isBatteryOptimizationExempt()) {
            activity.getColor(R.color.success_green)
        } else {
            activity.getColor(R.color.warning_orange)
        }
    }

    /**
     * Show system performance details dialog
     */
    fun showSystemDetailsDialog() {
        val scrollView = ScrollView(activity).apply {
            setPadding(40, 40, 40, 40)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Build the details string and display
        val detailsText = buildDetailsString()

        val textView = TextView(activity).apply {
            text = detailsText
            textSize = 12f
            setTextColor(activity.getColor(R.color.text_primary))
            setLineSpacing(4f, 1f)
            typeface = android.graphics.Typeface.MONOSPACE
        }

        container.addView(textView)
        scrollView.addView(container)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("System Details")
            .setView(scrollView)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("System Details", detailsText))
                Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .create()

        dialog.show()
    }

    /**
     * Build the system details string
     */
    private fun buildDetailsString(): String {
        val sb = StringBuilder()

        // Device — same hardware-tied stable ID shown in the control center
        // footer, hashed to 10 hex chars for at-a-glance comparison.
        val deviceId = com.dashieapp.Dashie.util.StableDeviceId.read(activity)
            .takeIf { it.isNotEmpty() }
            ?.take(10)?.uppercase() ?: "Unknown"

        sb.appendLine("═══ DEVICE ═══")
        sb.appendLine("Device ID: $deviceId")
        sb.appendLine("Model: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Dashie: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        sb.appendLine("WebView: ${com.dashieapp.Dashie.halite.diagnostics.CrashHandler.getWebViewVersion(activity)}")
        sb.appendLine()

        // Memory
        val activityManager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val runtime = Runtime.getRuntime()

        sb.appendLine("═══ MEMORY ═══")
        sb.appendLine("Total RAM: ${memoryInfo.totalMem / (1024 * 1024)} MB")
        sb.appendLine("Available: ${memoryInfo.availMem / (1024 * 1024)} MB")
        sb.appendLine("App Heap: ${(runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)} / ${runtime.maxMemory() / (1024 * 1024)} MB")
        sb.appendLine("Low Memory: ${if (memoryInfo.lowMemory) "Yes" else "No"}")
        sb.appendLine()

        // Display
        val dm = activity.resources.displayMetrics
        sb.appendLine("═══ DISPLAY ═══")
        sb.appendLine("Resolution: ${dm.widthPixels} × ${dm.heightPixels}")
        sb.appendLine("Density: ${dm.density}x (${dm.densityDpi} dpi)")
        sb.appendLine("CPU Cores: ${runtime.availableProcessors()}")
        sb.appendLine()

        // Network
        sb.appendLine("═══ NETWORK ═══")
        val ipAddress = try {
            val deviceInfoJson = org.json.JSONObject(DeviceInfoHelper.getDeviceInfo(activity))
            deviceInfoJson.optString("localIpAddress", "Unknown")
        } catch (e: Exception) { "Unknown" }
        sb.appendLine("IP Address: $ipAddress")
        try {
            val wifiManager = activity.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            sb.appendLine("WiFi SSID: ${wifiInfo.ssid}")
            sb.appendLine("Signal: ${WifiManager.calculateSignalLevel(wifiInfo.rssi, 100)}%")
        } catch (e: Exception) {
            sb.appendLine("WiFi: Unable to read")
        }
        sb.appendLine("WiFi Lock: ${if (halitePrefs.performance.wifiLockEnabled) "Enabled" else "Disabled"} (held: ${wifiLockHeldProvider?.invoke() ?: "?"})")
        sb.appendLine("CPU Wake Lock: held=${cpuWakeLockHeldProvider?.invoke() ?: "?"}")
        sb.appendLine()

        // Power
        sb.appendLine("═══ POWER ═══")
        try {
            val batteryManager = activity.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = batteryManager.isCharging
            sb.appendLine("Battery: $batteryLevel%")
            sb.appendLine("Charging: ${if (isCharging) "Yes" else "No"}")
        } catch (e: Exception) {
            sb.appendLine("Battery: Unable to read")
        }
        sb.appendLine("Battery Exempt: ${if (isBatteryOptimizationExempt()) "Yes" else "No"}")
        sb.appendLine()

        // Features
        sb.appendLine("═══ FEATURES ═══")
        sb.appendLine("Motion Wake: ${halitePrefs.screensaver.motionWakeMode}")
        if (halitePrefs.screensaver.motionWakeMode == "camera" || halitePrefs.screensaver.motionWakeMode == "face") {
            sb.appendLine("  Threshold: ${halitePrefs.screensaver.cameraWakeThresholdDouble}%")
        }
        sb.appendLine("Hardware Screen Off: ${if (halitePrefs.sleep.hardwareScreenOff) "Yes" else "No"}")
        val motionDiag = motionWakeDiagnosticsProvider?.invoke()
        if (motionDiag != null) {
            sb.appendLine(motionDiag)
        }
        sb.appendLine("Auto-Reload: ${if (halitePrefs.performance.autoReloadStaleMinutes == 0) "Disabled" else "${halitePrefs.performance.autoReloadStaleMinutes} min"}")
        sb.appendLine("WS Keep-Alive: ${if (halitePrefs.performance.websocketPingEnabled) "Enabled" else "Disabled"}")
        sb.appendLine("Touch Priority: ${if (halitePrefs.performance.interactionPriorityEnabled) "Enabled" else "Disabled"}")
        sb.appendLine("Smart Reconnect: ${if (halitePrefs.performance.smartReconnectEnabled) "Enabled" else "Disabled"}")
        if (halitePrefs.performance.smartReconnectEnabled) {
            sb.appendLine("  Local HA URL: ${halitePrefs.performance.localHaUrl.ifBlank { "Not set" }}")
        }
        sb.appendLine("Telemetry: ${if (halitePrefs.performance.dashboardTelemetryEnabled) "Enabled" else "Disabled"}")
        sb.appendLine()

        // Camera streaming
        sb.appendLine("═══ CAMERA STREAMING ═══")
        sb.appendLine("Enabled: ${if (halitePrefs.camera.rtspEnabled) "Yes" else "No"}")
        if (halitePrefs.camera.rtspEnabled) {
            sb.appendLine("Resolution: ${halitePrefs.camera.rtspResolutionDisplay}")
            sb.appendLine("Frame Rate: ${halitePrefs.camera.rtspFps} fps")
            sb.appendLine("Software Enc: ${if (halitePrefs.camera.rtspForceSoftwareEncoding) "Yes" else "No"}")
        }

        // Camera capabilities
        val cameraCaps = MediaCodecCapabilities.getCameraCapabilities(activity, useFrontCamera = true)
        if (cameraCaps != null) {
            sb.appendLine("Camera: ${cameraCaps.cameraId} (${cameraCaps.facing})")
            sb.appendLine("Orientation: ${cameraCaps.sensorOrientation}°")
        }

        // Encoder capabilities
        val encoderCaps = MediaCodecCapabilities.getH264EncoderCapabilities()
        if (encoderCaps != null) {
            sb.appendLine("Encoder: ${encoderCaps.encoderName}")
            sb.appendLine("HW Accel: ${if (encoderCaps.isHardwareAccelerated) "Yes" else "No"}")
        }

        return sb.toString()
    }
}
