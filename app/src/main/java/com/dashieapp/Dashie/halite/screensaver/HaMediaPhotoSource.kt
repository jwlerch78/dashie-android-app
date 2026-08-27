package com.dashieapp.Dashie.halite.screensaver

import com.dashieapp.Dashie.edition.ApiPaths

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Photo source that fetches photos from Home Assistant's media folder.
 *
 * Uses the Dashie HA integration's media API:
 * - GET /api/dashie/media?folder=.&random=true - List photos
 * - GET /api/dashie/media/image/{folder}/{filename} - Get photo
 *
 * Photos are downloaded to a local cache for smooth slideshow transitions.
 * Uses a rotating cache of MAX_CACHED_PHOTOS to prevent unbounded memory growth.
 * The source cycles through photos, downloading ahead of current position
 * and evicting old photos as needed.
 */
class HaMediaPhotoSource(
    private val context: Context,
    private val prefs: ScreensaverPreferences
) : PhotoSource {

    companion object {
        private const val TAG = "HaMediaPhotoSource"
        private const val CACHE_DIR_NAME = "ha_media_cache"
        private const val MAX_CACHED_PHOTOS = 8  // Maximum photos to keep in cache
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val READ_TIMEOUT_MS = 30000L
    }

    // Photo metadata from HA (not downloaded yet)
    private var photoMetadata: List<HaPhotoMetadata> = emptyList()

    // Photos that have been downloaded and are ready to display
    // This is a rolling window - new photos are added at end, old removed from front
    private var cachedPhotos: MutableList<PhotoItem> = mutableListOf()

    // Track which photos we've downloaded by their path -> cache file
    private val downloadedPaths: MutableMap<String, File> = mutableMapOf()

    // Track global position in the full photo metadata list
    // This allows cycling through all photos, not just the cached subset
    private var globalPhotoIndex = 0

    // Track which photo was last successfully displayed
    // We only evict photos BEHIND this position (already shown)
    private var lastDisplayedPhotoId: String? = null

    // Track fallback index for cycling through cached photos when network is unavailable
    // This ensures we show different cached photos instead of always the first one
    private var fallbackCacheIndex = 0

    // HTTP client for HA API calls. Accepts self-signed certs for LAN.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    // Auth token and HA URL (set before sync)
    private var accessToken: String? = null
    private var haBaseUrl: String? = null

    // Token provider reads fresh from halitePrefs each time (avoids stale cached token after proactive refresh)
    var tokenProvider: (() -> String?)? = null

    // Callback to refresh the access token when it expires (returns new token or null)
    var onTokenRefreshNeeded: (suspend () -> String?)? = null

    /** Get the current token — prefers provider (always fresh) over cached field */
    private fun getToken(): String? = tokenProvider?.invoke() ?: accessToken

    // Cache directory
    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
    }

    init {
        // Load any existing cached photos from disk on startup
        loadCachedPhotosFromDisk()
    }

    /**
     * Load cached photos from disk into memory.
     * This allows previously downloaded photos to be used immediately
     * without waiting for a network sync.
     * Only loads up to MAX_CACHED_PHOTOS, keeping the most recent ones.
     */
    private fun loadCachedPhotosFromDisk() {
        val cacheFiles = cacheDir.listFiles() ?: return
        if (cacheFiles.isEmpty()) return

        // Sort by last modified (most recent first) and take only MAX_CACHED_PHOTOS
        val validFiles = cacheFiles
            .filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
            .take(MAX_CACHED_PHOTOS)

        // Delete any files beyond the limit
        val allValidFiles = cacheFiles.filter { it.isFile && it.length() > 0 }
        if (allValidFiles.size > MAX_CACHED_PHOTOS) {
            val toDelete = allValidFiles.sortedBy { it.lastModified() }
                .take(allValidFiles.size - MAX_CACHED_PHOTOS)
            toDelete.forEach { file ->
                file.delete()
                Log.d(TAG, "🖼️ Cleaned up old cache file: ${file.name}")
            }
            Log.i(TAG, "🖼️ Cleaned up ${toDelete.size} old cache files (limit: $MAX_CACHED_PHOTOS)")
        }

        var loadedCount = 0
        validFiles.forEach { file ->
            // Reconstruct path from filename (we used path.replace("/", "_"))
            val path = file.name
            if (!downloadedPaths.containsKey(path)) {
                val photoItem = PhotoItem(
                    id = path,
                    uri = Uri.fromFile(file),
                    filename = file.name,
                    createdAt = Date(file.lastModified()),
                    source = PhotoSourceType.HA_MEDIA
                )
                cachedPhotos.add(photoItem)
                downloadedPaths[path] = file
                loadedCount++
            }
        }

        if (loadedCount > 0) {
            Log.i(TAG, "🖼️ Loaded $loadedCount cached photos from disk (max $MAX_CACHED_PHOTOS)")
        }
    }

    override fun getSourceType() = PhotoSourceType.HA_MEDIA

    override fun supportsAutoSync() = true  // Can poll for new photos

    override fun getPhotos(): List<PhotoItem> {
        // Return all cached photos (downloaded to local storage)
        return cachedPhotos.toList()
    }

    /**
     * Set the authentication token and HA base URL.
     * Must be called before sync().
     */
    fun setCredentials(token: String, baseUrl: String) {
        this.accessToken = token
        this.haBaseUrl = baseUrl.trimEnd('/')
        Log.d(TAG, "Credentials set: baseUrl=$haBaseUrl, token=${token.take(20)}...")
    }

    /**
     * Check if credentials are configured
     */
    fun hasCredentials(): Boolean = !getToken().isNullOrEmpty() && !haBaseUrl.isNullOrEmpty()

    override suspend fun sync(): SyncResult = syncWithRetry(isRetry = false)

    private suspend fun syncWithRetry(isRetry: Boolean): SyncResult = withContext(Dispatchers.IO) {
        val token = getToken() ?: return@withContext SyncResult(
            success = false,
            photosFound = 0,
            photosNew = 0,
            error = "No access token configured"
        )

        val baseUrl = haBaseUrl ?: return@withContext SyncResult(
            success = false,
            photosFound = 0,
            photosNew = 0,
            error = "No HA URL configured"
        )

        try {
            // Get media folder from preferences (default ".")
            val folder = prefs.haMediaFolder.ifEmpty { "." }

            // Fetch photo list from HA
            val url = "$baseUrl${ApiPaths.HA}/media?folder=$folder&random=true"
            Log.e(TAG, "🖼️ Fetching photo list from: $url")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                // Handle 401 - try to refresh token and retry once
                if (response.code == 401 && !isRetry) {
                    Log.w(TAG, "Got 401 on sync, attempting token refresh...")
                    val newToken = onTokenRefreshNeeded?.invoke()
                    if (newToken != null && newToken.isNotEmpty()) {
                        accessToken = newToken  // Update stored token
                        Log.i(TAG, "Token refreshed, retrying sync...")
                        return@withContext syncWithRetry(isRetry = true)
                    } else {
                        Log.w(TAG, "Token refresh failed or not available")
                    }
                }
                val error = "API returned ${response.code}: ${response.message}"
                Log.e(TAG, error)
                return@withContext SyncResult(
                    success = false,
                    photosFound = 0,
                    photosNew = 0,
                    error = error
                )
            }

            val body = response.body?.string() ?: "{}"
            Log.e(TAG, "🖼️ Response body: ${body.take(500)}")
            val json = JSONObject(body)
            val photosArray = json.optJSONArray("photos") ?: return@withContext SyncResult(
                success = false,
                photosFound = 0,
                photosNew = 0,
                error = "No photos array in response"
            )

            // Parse photo metadata
            val newMetadata = mutableListOf<HaPhotoMetadata>()
            for (i in 0 until photosArray.length()) {
                val photo = photosArray.getJSONObject(i)
                newMetadata.add(HaPhotoMetadata(
                    filename = photo.getString("filename"),
                    path = photo.getString("path"),
                    url = photo.getString("url"),
                    size = photo.optLong("size", 0),
                    modified = photo.optDouble("modified", 0.0)
                ))
            }

            val total = json.optInt("total", newMetadata.size)
            Log.i(TAG, "Found $total photos in HA media folder '$folder'")

            // Update metadata
            val previousCount = photoMetadata.size
            photoMetadata = newMetadata

            // Clear cache if photo list changed significantly
            if (previousCount > 0 && kotlin.math.abs(newMetadata.size - previousCount) > 5) {
                Log.d(TAG, "Photo list changed significantly, clearing cache")
                clearCache()
            }

            // Prefetch initial batch of photos
            val prefetched = prefetchInitialPhotos(MAX_CACHED_PHOTOS)

            SyncResult(
                success = true,
                photosFound = total,
                photosNew = prefetched,
                message = "Synced $total photos from HA media folder"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing photos", e)
            SyncResult(
                success = false,
                photosFound = 0,
                photosNew = 0,
                error = "Sync failed: ${e.message}"
            )
        }
    }

    /**
     * Mark a photo as successfully displayed.
     * This allows us to safely evict photos that are "behind" the current display position.
     */
    fun markPhotoDisplayed(photoId: String) {
        lastDisplayedPhotoId = photoId
        Log.d(TAG, "🖼️ Marked photo as displayed: $photoId")
    }

    /**
     * Get the next cached photo in cycling order for fallback when network is unavailable.
     * This ensures we show different photos instead of always the first one.
     */
    private fun getNextCachedPhotoFallback(): PhotoItem? {
        if (cachedPhotos.isEmpty()) return null

        val photo = cachedPhotos[fallbackCacheIndex % cachedPhotos.size]
        fallbackCacheIndex = (fallbackCacheIndex + 1) % cachedPhotos.size
        Log.d(TAG, "🖼️ Fallback: using cached photo ${fallbackCacheIndex}/${cachedPhotos.size}: ${photo.filename}")
        return photo
    }

    /**
     * Get the next photo to display and prepare the cache.
     * This is the main entry point for the slideshow to get the next photo.
     *
     * Logic:
     * 1. Find the next photo in the global metadata list
     * 2. If already cached, return it
     * 3. If not cached, download it (only evict AFTER successful download)
     * 4. Return the photo (or null if download failed)
     *
     * Key safety: We only evict photos AFTER successfully downloading a replacement,
     * so if we go offline we keep showing cached photos.
     */
    suspend fun getNextPhoto(): PhotoItem? = withContext(Dispatchers.IO) {
        if (photoMetadata.isEmpty()) {
            Log.w(TAG, "No photo metadata available, using cached fallback")
            return@withContext getNextCachedPhotoFallback()
        }

        val token = getToken() ?: return@withContext getNextCachedPhotoFallback()
        val baseUrl = haBaseUrl ?: return@withContext getNextCachedPhotoFallback()

        // Advance global index (wrap around)
        globalPhotoIndex = (globalPhotoIndex + 1) % photoMetadata.size
        val meta = photoMetadata[globalPhotoIndex]
        Log.d(TAG, "🖼️ Next photo: ${meta.filename} (index $globalPhotoIndex/${photoMetadata.size})")

        // Check if already cached
        val existingPhoto = cachedPhotos.find { it.id == meta.path }
        if (existingPhoto != null) {
            Log.d(TAG, "🖼️ Photo already cached: ${meta.filename}")
            return@withContext existingPhoto
        }

        // Need to download - first check if we should evict
        val shouldEvict = cachedPhotos.size >= MAX_CACHED_PHOTOS

        // Try to download the new photo
        val newPhoto = try {
            downloadPhoto(meta, baseUrl, token)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download ${meta.filename}: ${e.message}")
            null
        }

        if (newPhoto != null) {
            // Success! Now we can safely evict an old photo if needed
            if (shouldEvict) {
                evictOldestDisplayed()
            }

            // Add new photo to cache
            cachedPhotos.add(newPhoto)
            val cacheFile = File(cacheDir, meta.path.replace("/", "_"))
            downloadedPaths[meta.path] = cacheFile
            Log.d(TAG, "🖼️ Downloaded: ${meta.filename} (${cachedPhotos.size}/$MAX_CACHED_PHOTOS cached)")
            return@withContext newPhoto
        } else {
            // Download failed - return next cached photo in rotation as fallback
            // (don't evict anything since we're offline or having issues)
            Log.w(TAG, "🖼️ Download failed, using cached fallback")
            return@withContext getNextCachedPhotoFallback()
        }
    }

    /**
     * Get the current global position in the full photo list.
     */
    fun getGlobalIndex(): Int = globalPhotoIndex

    /**
     * Reset global index (e.g., when resyncing).
     */
    fun resetGlobalIndex() {
        globalPhotoIndex = 0
        lastDisplayedPhotoId = null
    }

    /**
     * Prefetch the initial batch of photos for slideshow start.
     * Returns number of photos downloaded.
     */
    suspend fun prefetchInitialPhotos(count: Int): Int = withContext(Dispatchers.IO) {
        val token = getToken() ?: return@withContext 0
        val baseUrl = haBaseUrl ?: return@withContext 0

        var downloaded = 0

        for (i in 0 until minOf(count, photoMetadata.size)) {
            val meta = photoMetadata[i]

            // Skip if already downloaded
            if (downloadedPaths.containsKey(meta.path)) continue

            // Stop if at capacity (don't evict during initial prefetch)
            if (cachedPhotos.size >= MAX_CACHED_PHOTOS) break

            try {
                val photoItem = downloadPhoto(meta, baseUrl, token)
                if (photoItem != null) {
                    cachedPhotos.add(photoItem)
                    val cacheFile = File(cacheDir, meta.path.replace("/", "_"))
                    downloadedPaths[meta.path] = cacheFile
                    downloaded++
                    Log.d(TAG, "🖼️ Prefetched: ${meta.filename} (${cachedPhotos.size}/$MAX_CACHED_PHOTOS)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prefetch ${meta.filename}: ${e.message}")
            }
        }

        downloaded
    }

    /**
     * Evict the oldest displayed photo to make room for new ones.
     * Only evicts photos that have already been displayed (not currently showing or pending).
     * Deletes both the in-memory entry and the disk file.
     */
    private fun evictOldestDisplayed() {
        if (cachedPhotos.isEmpty()) return

        // Find the index of the last displayed photo
        val lastDisplayedIndex = if (lastDisplayedPhotoId != null) {
            cachedPhotos.indexOfFirst { it.id == lastDisplayedPhotoId }
        } else {
            -1
        }

        // We can safely evict photos before the last displayed one
        // If nothing has been displayed yet, evict the first one
        val indexToEvict = if (lastDisplayedIndex > 0) {
            0  // Always evict from front, but only if there's something before current
        } else if (lastDisplayedIndex == 0 && cachedPhotos.size > 1) {
            // Current photo is first - don't evict it, skip
            Log.d(TAG, "🖼️ No safe photo to evict yet (current is first)")
            return
        } else {
            0  // Nothing displayed yet, safe to evict first
        }

        val photoToEvict = cachedPhotos.removeAt(indexToEvict)
        val path = photoToEvict.id

        // Find and delete the cache file
        val cacheFile = downloadedPaths.remove(path)
        if (cacheFile != null && cacheFile.exists()) {
            cacheFile.delete()
            Log.d(TAG, "🖼️ Evicted: ${photoToEvict.filename}")
        }
    }

    /**
     * Download a single photo and return a PhotoItem with pre-extracted metadata.
     * Metadata (EXIF date, GPS location) is extracted at download time for instant display.
     * Automatically retries with refreshed token on 401 errors.
     */
    private suspend fun downloadPhoto(
        meta: HaPhotoMetadata,
        baseUrl: String,
        token: String,
        isRetry: Boolean = false
    ): PhotoItem? = withContext(Dispatchers.IO) {
        val url = "$baseUrl${meta.url}"
        Log.d(TAG, "Downloading: $url")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            // Handle 401 - try to refresh token and retry once
            if (response.code == 401 && !isRetry) {
                Log.w(TAG, "Got 401 for ${meta.filename}, attempting token refresh...")
                val newToken = onTokenRefreshNeeded?.invoke()
                if (newToken != null && newToken.isNotEmpty()) {
                    accessToken = newToken  // Update stored token
                    Log.i(TAG, "Token refreshed, retrying download...")
                    return@withContext downloadPhoto(meta, baseUrl, newToken, isRetry = true)
                } else {
                    Log.w(TAG, "Token refresh failed or not available")
                }
            }
            Log.w(TAG, "Failed to download ${meta.filename}: ${response.code}")
            return@withContext null
        }

        // Read image bytes first (for both saving and metadata extraction)
        val imageBytes = response.body?.bytes()
        if (imageBytes == null || imageBytes.isEmpty()) {
            Log.w(TAG, "Empty response for ${meta.filename}")
            return@withContext null
        }

        // Save to cache
        val cacheFile = File(cacheDir, meta.path.replace("/", "_"))
        FileOutputStream(cacheFile).use { output ->
            output.write(imageBytes)
        }

        // Extract metadata from image bytes (date, location)
        val extractedMetadata = try {
            PhotoMetadataExtractor.extractFromBytes(imageBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata from ${meta.filename}: ${e.message}")
            null
        }

        // Build metadata map for PhotoItem
        val metadataMap = mutableMapOf<String, Any>()
        extractedMetadata?.let { m ->
            m.dateFormatted?.let { metadataMap["dateFormatted"] = it }
            m.dateTaken?.let { metadataMap["dateTaken"] = it.time }
            m.location?.let { metadataMap["location"] = it }
            m.latitude?.let { metadataMap["latitude"] = it }
            m.longitude?.let { metadataMap["longitude"] = it }
        }

        if (metadataMap.isNotEmpty()) {
            Log.d(TAG, "🖼️ Metadata extracted: ${meta.filename} -> date=${metadataMap["dateFormatted"]}, loc=${metadataMap["location"]}")
        }

        PhotoItem(
            id = meta.path,
            uri = Uri.fromFile(cacheFile),
            filename = meta.filename,
            createdAt = extractedMetadata?.dateTaken
                ?: if (meta.modified > 0) Date((meta.modified * 1000).toLong()) else null,
            source = PhotoSourceType.HA_MEDIA,
            metadata = if (metadataMap.isNotEmpty()) metadataMap else null
        )
    }

    /**
     * Clear the local cache (both memory and disk).
     */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cachedPhotos.clear()
        downloadedPaths.clear()
        Log.i(TAG, "🖼️ Cache cleared")
    }

    /**
     * Get the total number of photos available (not just downloaded).
     */
    fun getTotalPhotoCount(): Int = photoMetadata.size

    /**
     * Get cache size in bytes.
     */
    fun getCacheSize(): Long {
        return cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get cache size formatted as human-readable string.
     */
    fun getCacheSizeFormatted(): String {
        val bytes = getCacheSize()
        return when {
            bytes >= 1_073_741_824 -> String.format("%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    /**
     * Check if source is ready (has credentials and photos).
     */
    fun isReady(): Boolean = hasCredentials() && cachedPhotos.isNotEmpty()

    /**
     * Get a summary string for display in settings.
     */
    fun getSummary(): String {
        return if (hasCredentials()) {
            if (photoMetadata.isNotEmpty()) {
                "${photoMetadata.size} photos (${getCacheSizeFormatted()} cached)"
            } else {
                "Connected - no photos found"
            }
        } else {
            "Not connected to Home Assistant"
        }
    }

    /**
     * Fetch available folders from HA media API.
     * Returns list of folder info (name, path, photo_count).
     */
    suspend fun fetchFolders(): List<HaMediaFolder> = withContext(Dispatchers.IO) {
        val result = fetchFoldersWithError()
        result.folders
    }

    /**
     * Fetch available folders from HA media API with detailed error information.
     * Use this when you need to know why the fetch failed.
     */
    suspend fun fetchFoldersWithError(): FolderFetchResult = withContext(Dispatchers.IO) {
        val token = getToken()
        val baseUrl = haBaseUrl

        if (token == null || baseUrl == null) {
            Log.w(TAG, "Missing credentials: token=${token != null}, baseUrl=${baseUrl != null}")
            return@withContext FolderFetchResult(
                folders = emptyList(),
                error = FolderFetchError.MISSING_CREDENTIALS
            )
        }

        try {
            val url = "$baseUrl${ApiPaths.HA}/media/folders"
            Log.d(TAG, "Fetching folders from: $url")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch folders: ${response.code}")
                val error = when (response.code) {
                    401 -> FolderFetchError.UNAUTHORIZED
                    404 -> FolderFetchError.API_NOT_FOUND
                    else -> FolderFetchError.API_ERROR
                }
                return@withContext FolderFetchResult(
                    folders = emptyList(),
                    error = error,
                    httpCode = response.code
                )
            }

            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            val foldersArray = json.optJSONArray("folders")

            if (foldersArray == null || foldersArray.length() == 0) {
                return@withContext FolderFetchResult(
                    folders = emptyList(),
                    error = FolderFetchError.NO_FOLDERS
                )
            }

            val folders = mutableListOf<HaMediaFolder>()
            for (i in 0 until foldersArray.length()) {
                val folder = foldersArray.getJSONObject(i)
                folders.add(HaMediaFolder(
                    name = folder.getString("name"),
                    path = folder.getString("path"),
                    photoCount = folder.optInt("photo_count", 0)
                ))
            }

            Log.i(TAG, "Found ${folders.size} folders in HA media")
            FolderFetchResult(folders = folders, error = null)
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "Network error - cannot reach HA", e)
            FolderFetchResult(folders = emptyList(), error = FolderFetchError.NETWORK_ERROR)
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Network timeout", e)
            FolderFetchResult(folders = emptyList(), error = FolderFetchError.NETWORK_ERROR)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching folders", e)
            FolderFetchResult(folders = emptyList(), error = FolderFetchError.UNKNOWN)
        }
    }

    /**
     * Result of folder fetch operation with error details.
     */
    data class FolderFetchResult(
        val folders: List<HaMediaFolder>,
        val error: FolderFetchError?,
        val httpCode: Int? = null
    )

    /**
     * Error types for folder fetch operation.
     */
    enum class FolderFetchError {
        MISSING_CREDENTIALS,    // No token or base URL
        UNAUTHORIZED,           // 401 - bad token
        API_NOT_FOUND,          // 404 - Dashie integration not installed
        API_ERROR,              // Other HTTP error
        NO_FOLDERS,             // API works but no folders with photos
        NETWORK_ERROR,          // Can't reach HA
        UNKNOWN                 // Other exception
    }

    /**
     * Folder info from HA media API.
     */
    data class HaMediaFolder(
        val name: String,
        val path: String,
        val photoCount: Int
    )

    /**
     * Metadata for a photo from HA (before download).
     */
    private data class HaPhotoMetadata(
        val filename: String,
        val path: String,
        val url: String,
        val size: Long,
        val modified: Double
    )
}
