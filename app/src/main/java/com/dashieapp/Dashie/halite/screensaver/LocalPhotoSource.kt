package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.util.Log
import java.io.File
import java.util.Date

/**
 * Photo source that reads photos from a local folder on the device.
 * Default folder: /Pictures/Dashie/
 *
 * This is the simplest photo source - users just copy photos to the folder
 * and they automatically appear in the screensaver.
 */
class LocalPhotoSource(
    private val context: Context,
    private val prefs: ScreensaverPreferences
) : PhotoSource {

    companion object {
        private const val TAG = "LocalPhotoSource"
        private const val DEFAULT_FOLDER_NAME = "Dashie"
        private val SUPPORTED_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "gif")
    }

    private val defaultFolder = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        DEFAULT_FOLDER_NAME
    )

    private var fileObserver: FileObserver? = null
    private var onPhotosChangedCallback: (() -> Unit)? = null

    override fun getSourceType() = PhotoSourceType.LOCAL_FOLDER

    override fun supportsAutoSync() = true  // FileObserver provides real-time sync

    override fun getPhotos(): List<PhotoItem> {
        val folder = getFolder()

        if (!folder.exists()) {
            // Create folder if it doesn't exist (helpful for first-time users)
            val created = folder.mkdirs()
            if (created) {
                Log.i(TAG, "Created photo folder: ${folder.absolutePath}")
            }
            return emptyList()
        }

        return folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS
        }?.map { file ->
            PhotoItem(
                id = file.absolutePath,
                uri = Uri.fromFile(file),
                filename = file.name,
                createdAt = Date(file.lastModified()),
                source = PhotoSourceType.LOCAL_FOLDER
            )
        }?.sortedByDescending { it.createdAt } ?: emptyList()
    }

    override suspend fun sync(): SyncResult {
        // For local folder, sync is instant - just re-scan
        val photos = getPhotos()
        return SyncResult(
            success = true,
            photosFound = photos.size,
            photosNew = 0,  // We don't track this for local
            message = "Found ${photos.size} photos in ${getFolder().absolutePath}"
        )
    }

    /**
     * Start watching folder for changes (add/delete photos).
     * Call this when the screensaver starts.
     */
    @Suppress("DEPRECATION")
    fun startWatching(onPhotosChanged: () -> Unit) {
        stopWatching()  // Clean up any existing observer

        val folder = getFolder()
        if (!folder.exists()) {
            folder.mkdirs()
        }

        onPhotosChangedCallback = onPhotosChanged

        fileObserver = object : FileObserver(
            folder.absolutePath,
            CREATE or DELETE or MOVED_FROM or MOVED_TO
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return

                // Only react to supported file types
                val extension = path.substringAfterLast('.', "").lowercase()
                if (extension in SUPPORTED_EXTENSIONS) {
                    val eventName = when (event) {
                        CREATE -> "CREATE"
                        DELETE -> "DELETE"
                        MOVED_FROM -> "MOVED_FROM"
                        MOVED_TO -> "MOVED_TO"
                        else -> "EVENT_$event"
                    }
                    Log.d(TAG, "Photo change detected: $path ($eventName)")
                    onPhotosChangedCallback?.invoke()
                }
            }
        }
        fileObserver?.startWatching()
        Log.i(TAG, "Started watching folder: ${folder.absolutePath}")
    }

    /**
     * Stop watching folder for changes.
     * Call this when the screensaver stops.
     */
    fun stopWatching() {
        fileObserver?.stopWatching()
        fileObserver = null
        onPhotosChangedCallback = null
    }

    /**
     * Get the configured photo folder (or default).
     */
    fun getFolder(): File {
        val customPath = prefs.localPhotoFolder
        return if (customPath.isNotEmpty()) {
            File(customPath)
        } else {
            defaultFolder
        }
    }

    /**
     * Get the folder path as a string.
     */
    fun getFolderPath(): String = getFolder().absolutePath

    /**
     * Get the number of photos in the folder.
     */
    fun getPhotoCount(): Int = getPhotos().size

    /**
     * Get total size of photos in folder.
     */
    fun getTotalSize(): Long {
        val folder = getFolder()
        if (!folder.exists()) return 0L

        return folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in SUPPORTED_EXTENSIONS
        }?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get total size formatted as human-readable string.
     */
    fun getTotalSizeFormatted(): String {
        val bytes = getTotalSize()
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    /**
     * Check if the folder exists and has photos.
     */
    fun isReady(): Boolean {
        val folder = getFolder()
        return folder.exists() && getPhotoCount() > 0
    }

    /**
     * Get a summary string for display in settings.
     */
    fun getSummary(): String {
        val count = getPhotoCount()
        return if (count > 0) {
            "$count photos (${getTotalSizeFormatted()})"
        } else {
            "No photos yet"
        }
    }
}
