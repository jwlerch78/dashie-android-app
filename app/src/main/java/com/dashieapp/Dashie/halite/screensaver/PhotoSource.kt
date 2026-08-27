package com.dashieapp.Dashie.halite.screensaver

import android.net.Uri
import java.util.Date

/**
 * Common interface for all photo sources (Local Folder, Immich, Google Photos).
 * Allows the screensaver to work with any photo source without knowing the implementation details.
 */
interface PhotoSource {
    /**
     * Get all available photos from this source.
     * For local sources, this scans the folder.
     * For remote sources, this returns cached photos.
     */
    fun getPhotos(): List<PhotoItem>

    /**
     * Whether this source supports automatic synchronization.
     * - Local Folder: true (FileObserver)
     * - Immich: true (API polling)
     * - Google Photos: false (requires manual re-import)
     */
    fun supportsAutoSync(): Boolean

    /**
     * Synchronize photos from the source.
     * For local sources, this re-scans the folder.
     * For remote sources, this fetches new photos and caches them.
     */
    suspend fun sync(): SyncResult

    /**
     * Get the type of this photo source.
     */
    fun getSourceType(): PhotoSourceType
}

/**
 * Types of photo sources supported by Dashie.
 */
enum class PhotoSourceType {
    LOCAL_FOLDER,    // /Pictures/Dashie/ on device
    IMMICH,          // Self-hosted Immich server
    GOOGLE_PHOTOS,   // Google Photos via Picker API
    GOOGLE_DRIVE,    // Google Drive folder via edge function proxy
    HA_MEDIA,        // Home Assistant /config/media folder
    UNSPLASH,        // Unsplash random photos via API
    SUPABASE         // Dashie Cloud (Supabase Storage) via database-operations edge function
}

/**
 * Represents a single photo from any source.
 */
data class PhotoItem(
    val id: String,                      // Unique identifier (path for local, ID for remote)
    val uri: Uri,                        // Local file URI or cached file URI
    val filename: String,
    val createdAt: Date?,
    val source: PhotoSourceType,
    val width: Int? = null,              // Optional dimensions
    val height: Int? = null,
    val metadata: Map<String, Any>? = null  // Optional extra metadata
)

/**
 * Result of a sync operation.
 */
data class SyncResult(
    val success: Boolean,
    val photosFound: Int,
    val photosNew: Int,
    val photosFailed: Int = 0,
    val message: String? = null,
    val error: String? = null
)
