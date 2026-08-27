package com.dashieapp.Dashie.halite.screensaver

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import java.io.File

/**
 * Dialogs for configuring screensaver photo source and slideshow settings.
 */
class ScreensaverDialogs(
    private val activity: Activity,
    private val prefs: ScreensaverPreferences,
    private val onSettingsChanged: () -> Unit
) {
    companion object {
        private const val TAG = "ScreensaverDialogs"
    }

    /**
     * Show the photo source selection dialog.
     */
    fun showPhotoSourceDialog() {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_confirm, null)
        val titleView = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val messageView = dialogView.findViewById<TextView>(R.id.dialogMessage)

        titleView.text = "Photo Source"

        // Build the current status message
        val currentSource = prefs.photoSourceType
        val localSource = LocalPhotoSource(activity, prefs)
        val statusMessage = when (currentSource) {
            ScreensaverPreferences.PHOTO_SOURCE_LOCAL -> {
                "Local Folder: ${localSource.getFolderPath()}\n${localSource.getSummary()}"
            }
            ScreensaverPreferences.PHOTO_SOURCE_IMMICH -> {
                "Immich: ${prefs.immichServerUrl}"
            }
            ScreensaverPreferences.PHOTO_SOURCE_GOOGLE -> {
                "Google Photos: Album configured"
            }
            ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH -> {
                val query = prefs.unsplashQuery.ifEmpty { "nature" }
                "Unsplash: Random photos (query: $query)"
            }
            else -> "No photo source configured"
        }
        messageView.text = statusMessage

        val dialog = AlertDialog.Builder(activity)
            .setView(dialogView)
            .create()

        // Set up buttons
        dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }

        dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
            text = "Configure"
            setOnClickListener {
                dialog.dismiss()
                showPhotoSourceSelectionDialog()
            }
        }

        dialog.show()
        DialogHelper.applyImmersiveModeToDialog(dialog)
    }

    /**
     * Show dialog to select photo source type.
     */
    private fun showPhotoSourceSelectionDialog() {
        val options = arrayOf(
            "Local Folder (Recommended)",
            "Unsplash (Random Photos)",
            "Immich Server",
            "Google Photos"
        )

        val currentSource = prefs.photoSourceType
        val currentIndex = when (currentSource) {
            ScreensaverPreferences.PHOTO_SOURCE_LOCAL -> 0
            ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH -> 1
            ScreensaverPreferences.PHOTO_SOURCE_IMMICH -> 2
            ScreensaverPreferences.PHOTO_SOURCE_GOOGLE -> 3
            else -> -1
        }

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_picker, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Select Photo Source"

        val optionsGroup = dialogView.findViewById<RadioGroup>(R.id.optionsGroup)
        val radioIds = mutableListOf<Int>()

        options.forEachIndexed { index, label ->
            val radioButton = LayoutInflater.from(activity).inflate(
                R.layout.dialog_picker_item, optionsGroup, false
            ) as RadioButton
            radioButton.id = android.view.View.generateViewId()
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

        dialogView.findViewById<android.widget.Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.buttonSave).setOnClickListener {
            val selectedIdx = radioIds.indexOf(optionsGroup.checkedRadioButtonId)
            if (selectedIdx >= 0) {
                dialog.dismiss()
                when (selectedIdx) {
                    0 -> configureLocalFolder()
                    1 -> showUnsplashConfigDialog()
                    2 -> showImmichNotAvailable()
                    3 -> showGooglePhotosNotAvailable()
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
     * Configure local folder photo source.
     */
    private fun configureLocalFolder() {
        val localSource = LocalPhotoSource(activity, prefs)
        val folderPath = localSource.getFolderPath()
        val photoCount = localSource.getPhotoCount()
        val totalSize = localSource.getTotalSizeFormatted()

        // Build message
        val message = buildString {
            append("Photos will be loaded from:\n")
            append(folderPath)
            append("\n\n")
            if (photoCount > 0) {
                append("$photoCount photos ($totalSize)")
            } else {
                append("No photos found yet.\n\n")
                append("Copy photos to this folder using a file manager or USB connection.")
            }
        }

        val options = arrayOf(
            "Use This Folder",
            "Change Folder",
            "Reset to Default"
        )

        AlertDialog.Builder(activity)
            .setTitle("Local Folder")
            .setMessage(message)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Use current folder
                        prefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_LOCAL

                        // Create folder if it doesn't exist
                        val folder = File(folderPath)
                        if (!folder.exists()) {
                            folder.mkdirs()
                            Log.i(TAG, "Created photo folder: ${folder.absolutePath}")
                        }

                        onSettingsChanged()
                    }
                    1 -> {
                        // Change folder
                        showFolderPathInputDialog()
                    }
                    2 -> {
                        // Reset to default
                        prefs.localPhotoFolder = ""  // Empty means use default
                        prefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_LOCAL

                        // Create default folder if it doesn't exist
                        val defaultFolder = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "Dashie"
                        )
                        if (!defaultFolder.exists()) {
                            defaultFolder.mkdirs()
                            Log.i(TAG, "Created default photo folder: ${defaultFolder.absolutePath}")
                        }

                        onSettingsChanged()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show dialog to input custom folder path.
     */
    private fun showFolderPathInputDialog() {
        val currentPath = prefs.localPhotoFolder.ifEmpty {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Dashie"
            ).absolutePath
        }

        // Create an EditText for path input
        val input = EditText(activity).apply {
            setText(currentPath)
            setSelectAllOnFocus(true)
            hint = "/storage/emulated/0/Pictures/Dashie"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(activity)
            .setTitle("Enter Folder Path")
            .setMessage("Enter the full path to your photos folder:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newPath = input.text.toString().trim()
                if (newPath.isNotEmpty()) {
                    val folder = File(newPath)

                    // Validate the path
                    if (folder.exists() && folder.isDirectory) {
                        prefs.localPhotoFolder = newPath
                        prefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_LOCAL
                        Log.i(TAG, "Photo folder changed to: $newPath")
                        onSettingsChanged()
                    } else if (!folder.exists()) {
                        // Try to create it
                        if (folder.mkdirs()) {
                            prefs.localPhotoFolder = newPath
                            prefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_LOCAL
                            Log.i(TAG, "Created and set photo folder: $newPath")
                            onSettingsChanged()
                        } else {
                            showFolderError("Could not create folder at:\n$newPath\n\nCheck that the path is valid and you have write permission.")
                        }
                    } else {
                        showFolderError("Path exists but is not a folder:\n$newPath")
                    }
                }
            }
            .setNeutralButton("Browse...") { _, _ ->
                showCommonFolderPicker()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show common folder locations to pick from.
     */
    private fun showCommonFolderPicker() {
        val basePath = Environment.getExternalStorageDirectory().absolutePath

        val folders = listOf(
            "$basePath/Pictures/Dashie" to "Pictures/Dashie (default)",
            "$basePath/Pictures" to "Pictures",
            "$basePath/DCIM" to "DCIM (Camera)",
            "$basePath/DCIM/Camera" to "DCIM/Camera",
            "$basePath/Download" to "Download",
            "$basePath/Documents" to "Documents"
        ).filter { File(it.first).exists() || it.first.contains("Dashie") }

        val displayNames = folders.map { it.second }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Select Folder")
            .setItems(displayNames) { _, which ->
                val selectedPath = folders[which].first
                val folder = File(selectedPath)

                // Create if needed
                if (!folder.exists()) {
                    folder.mkdirs()
                }

                prefs.localPhotoFolder = selectedPath
                prefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_LOCAL
                Log.i(TAG, "Photo folder selected: $selectedPath")
                onSettingsChanged()
            }
            .setNeutralButton("Custom Path...") { _, _ ->
                showFolderPathInputDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show folder error dialog.
     */
    private fun showFolderError(message: String) {
        AlertDialog.Builder(activity)
            .setTitle("Folder Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                // Go back to folder input dialog
                showFolderPathInputDialog()
            }
            .show()
    }

    /**
     * Open folder in system file manager.
     */
    private fun openFolderInFileManager(path: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Pictures%2FDashie")
                setDataAndType(uri, "resource/folder")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open file manager", e)
            // Try alternative approach
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(Intent.createChooser(this, "Open Folder")))
                }
                activity.startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "Could not open any file manager", e2)
            }
        }
    }

    /**
     * Show Unsplash API configuration dialog.
     */
    private fun showUnsplashConfigDialog() {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val queryLabel = TextView(activity).apply {
            text = "Search Keywords"
            setPadding(0, 0, 0, 8)
        }
        layout.addView(queryLabel)

        val queryInput = EditText(activity).apply {
            setText(prefs.unsplashQuery)
            hint = "nature, landscape, mountains..."
            isSingleLine = true
        }
        layout.addView(queryInput)

        val helpText = TextView(activity).apply {
            text = "Random high-quality photos from Unsplash\nCached locally for offline use"
            setPadding(0, 16, 0, 0)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
        }
        layout.addView(helpText)

        AlertDialog.Builder(activity)
            .setTitle("Unsplash Photo Source")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.unsplashQuery = queryInput.text.toString().trim()
                prefs.photoSourceType = ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH
                Log.i(TAG, "Unsplash configured with query: ${prefs.unsplashQuery.ifEmpty { "nature" }}")
                onSettingsChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show placeholder for Immich (coming in Phase 2).
     */
    private fun showImmichNotAvailable() {
        AlertDialog.Builder(activity)
            .setTitle("Immich Server")
            .setMessage("Immich integration is coming soon!\n\nFor now, use Local Folder to copy photos to your device.")
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Show placeholder for Google Photos (coming in Phase 3).
     */
    private fun showGooglePhotosNotAvailable() {
        AlertDialog.Builder(activity)
            .setTitle("Google Photos")
            .setMessage("Google Photos integration is coming soon!\n\nFor now, use Local Folder to copy photos to your device.")
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Show slideshow settings dialog.
     */
    fun showSlideshowSettingsDialog() {
        val options = arrayOf(
            "5 seconds",
            "10 seconds",
            "15 seconds (default)",
            "30 seconds",
            "60 seconds",
            "2 minutes",
            "5 minutes",
            "10 minutes",
            "15 minutes"
        )
        val values = intArrayOf(5, 10, 15, 30, 60, 120, 300, 600, 900)

        val currentInterval = prefs.slideshowInterval
        val currentIndex = values.indexOfFirst { it == currentInterval }.takeIf { it >= 0 } ?: 2

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_picker, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Slideshow Interval"

        val optionsGroup = dialogView.findViewById<RadioGroup>(R.id.optionsGroup)
        val radioIds = mutableListOf<Int>()

        options.forEachIndexed { index, label ->
            val radioButton = LayoutInflater.from(activity).inflate(
                R.layout.dialog_picker_item, optionsGroup, false
            ) as RadioButton
            radioButton.id = android.view.View.generateViewId()
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

        dialogView.findViewById<android.widget.Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.buttonSave).setOnClickListener {
            val selectedIdx = radioIds.indexOf(optionsGroup.checkedRadioButtonId)
            if (selectedIdx >= 0) {
                prefs.slideshowInterval = values[selectedIdx]
                onSettingsChanged()
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
     * Show transition effect selection dialog.
     */
    fun showTransitionDialog() {
        val options = arrayOf(
            "Fade (default)",
            "Slide",
            "None"
        )
        val values = arrayOf(
            ScreensaverPreferences.TRANSITION_FADE,
            ScreensaverPreferences.TRANSITION_SLIDE,
            ScreensaverPreferences.TRANSITION_NONE
        )

        val currentTransition = prefs.slideshowTransition
        val currentIndex = values.indexOfFirst { it == currentTransition }.takeIf { it >= 0 } ?: 0

        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_picker, null)
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Transition Effect"

        val optionsGroup = dialogView.findViewById<RadioGroup>(R.id.optionsGroup)
        val radioIds = mutableListOf<Int>()

        options.forEachIndexed { index, label ->
            val radioButton = LayoutInflater.from(activity).inflate(
                R.layout.dialog_picker_item, optionsGroup, false
            ) as RadioButton
            radioButton.id = android.view.View.generateViewId()
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

        dialogView.findViewById<android.widget.Button>(R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(R.id.buttonSave).setOnClickListener {
            val selectedIdx = radioIds.indexOf(optionsGroup.checkedRadioButtonId)
            if (selectedIdx >= 0) {
                prefs.slideshowTransition = values[selectedIdx]
                onSettingsChanged()
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
     * Get display text for the current photo source configuration.
     */
    fun getPhotoSourceDisplayText(): String {
        return when (prefs.photoSourceType) {
            ScreensaverPreferences.PHOTO_SOURCE_LOCAL -> {
                val localSource = LocalPhotoSource(activity, prefs)
                val count = localSource.getPhotoCount()
                if (count > 0) "Local ($count photos)" else "Local (empty)"
            }
            ScreensaverPreferences.PHOTO_SOURCE_IMMICH -> {
                if (prefs.hasImmichConfig) "Immich" else "Immich (not configured)"
            }
            ScreensaverPreferences.PHOTO_SOURCE_GOOGLE -> {
                if (prefs.hasGooglePhotosConfig) "Google Photos" else "Google Photos (not configured)"
            }
            ScreensaverPreferences.PHOTO_SOURCE_UNSPLASH -> {
                if (prefs.hasUnsplashConfig) {
                    val query = prefs.unsplashQuery.ifEmpty { "nature" }
                    "Unsplash ($query)"
                } else "Unsplash (not configured)"
            }
            else -> "Not configured"
        }
    }
}
