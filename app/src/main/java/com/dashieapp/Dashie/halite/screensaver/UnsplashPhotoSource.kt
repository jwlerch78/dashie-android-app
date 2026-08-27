package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Photo source that fetches random photos from Unsplash via Supabase Edge Function proxy.
 *
 * Uses server-side proxy to keep the Unsplash API key confidential (required by Unsplash guidelines).
 * Triggers download tracking when photos are displayed (required by Unsplash guidelines).
 * Photos are cached to disk and displayed via native ImageView to avoid WebView GPU memory leaks.
 *
 * Edge Functions used:
 * - unsplash-random: Fetches random photo metadata (proxies /photos/random)
 * - unsplash-download-track: Triggers download event (proxies /photos/:id/download)
 */
class UnsplashPhotoSource(
    private val context: Context,
    private val prefs: ScreensaverPreferences
) : PhotoSource {

    companion object {
        private const val TAG = "UnsplashPhotoSource"
        private const val CACHE_DIR_NAME = "unsplash_cache"
        private const val MAX_CACHED_PHOTOS = 8
        private const val BATCH_SIZE = 10
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val READ_TIMEOUT_MS = 30000L
        private const val PREFETCH_THRESHOLD = 0.8

        private val EDGE_FUNCTION_BASE = BuildConfig.SUPABASE_URL + "/functions/v1"
        private val RANDOM_ENDPOINT = "$EDGE_FUNCTION_BASE/unsplash-random"
        private val DOWNLOAD_TRACK_ENDPOINT = "$EDGE_FUNCTION_BASE/unsplash-download-track"
    }

    // Metadata from API responses (not yet downloaded)
    private var photoMetadata: MutableList<UnsplashPhotoMeta> = mutableListOf()

    // Downloaded photos ready to display
    private var cachedPhotos: MutableList<PhotoItem> = mutableListOf()

    // Track downloads by Unsplash photo ID -> cache file
    private val downloadedIds: MutableMap<String, File> = mutableMapOf()

    // Track which photos have had download events triggered (avoid duplicate tracking)
    private val trackedDownloadIds: MutableSet<String> = mutableSetOf()

    // Position in metadata list
    private var globalPhotoIndex = 0

    // Last displayed photo for safe eviction
    private var lastDisplayedPhotoId: String? = null

    // Fallback index for offline cycling
    private var fallbackCacheIndex = 0

    // Track last query to detect changes
    private var lastQuery: String = prefs.unsplashQuery.ifEmpty { "nature" }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
    }

    init {
        loadCachedPhotosFromDisk()
    }

    private fun loadCachedPhotosFromDisk() {
        val cacheFiles = cacheDir.listFiles() ?: return
        if (cacheFiles.isEmpty()) return

        val validFiles = cacheFiles
            .filter { it.isFile && it.length() > 0 && it.extension == "jpg" }
            .sortedByDescending { it.lastModified() }
            .take(MAX_CACHED_PHOTOS)

        // Clean up excess files
        val allValid = cacheFiles.filter { it.isFile && it.length() > 0 && it.extension == "jpg" }
        if (allValid.size > MAX_CACHED_PHOTOS) {
            allValid.sortedBy { it.lastModified() }
                .take(allValid.size - MAX_CACHED_PHOTOS)
                .forEach { it.delete() }
        }

        var loaded = 0
        validFiles.forEach { file ->
            val id = file.nameWithoutExtension
            if (!downloadedIds.containsKey(id)) {
                // Load attribution metadata from sidecar JSON if available
                val metadataMap = loadSidecarMetadata(id)
                cachedPhotos.add(PhotoItem(
                    id = id,
                    uri = Uri.fromFile(file),
                    filename = file.name,
                    createdAt = Date(file.lastModified()),
                    source = PhotoSourceType.UNSPLASH,
                    metadata = metadataMap
                ))
                downloadedIds[id] = file
                loaded++
            }
        }

        if (loaded > 0) {
            Log.i(TAG, "Loaded $loaded cached Unsplash photos from disk")
        }
    }

    override fun getSourceType() = PhotoSourceType.UNSPLASH
    override fun supportsAutoSync() = true
    override fun getPhotos(): List<PhotoItem> = cachedPhotos.toList()

    override suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        checkQueryChanged()

        try {
            val fetched = fetchBatch()
            if (fetched == 0) {
                return@withContext SyncResult(false, 0, 0, error = "No photos returned from Unsplash")
            }

            val prefetched = prefetchPhotos(MAX_CACHED_PHOTOS)
            SyncResult(
                success = true,
                photosFound = photoMetadata.size,
                photosNew = prefetched,
                message = "Fetched $fetched photo URLs, downloaded $prefetched"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            SyncResult(false, 0, 0, error = "Sync failed: ${e.message}")
        }
    }

    /**
     * Fetch a batch of random photo metadata via the Edge Function proxy.
     */
    private fun fetchBatch(): Int {
        val query = prefs.unsplashQuery.ifEmpty { "nature" }

        val requestBody = JSONObject().apply {
            put("query", query)
            put("orientation", "landscape")
            put("count", BATCH_SIZE)
        }

        val request = Request.Builder()
            .url(RANDOM_ENDPOINT)
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.e(TAG, "Edge Function error: ${response.code} ${response.message}")
            return 0
        }

        val body = response.body?.string() ?: return 0
        val json = JSONObject(body)
        val photosArray = json.getJSONArray("photos")
        var added = 0

        for (i in 0 until photosArray.length()) {
            val photo = photosArray.getJSONObject(i)
            val id = photo.getString("id")

            photoMetadata.add(UnsplashPhotoMeta(
                id = id,
                downloadUrl = photo.getString("url"),
                width = photo.optInt("width", 0),
                height = photo.optInt("height", 0),
                description = photo.optString("description", null as String?),
                photographer = photo.optString("photographer", null as String?),
                photographerUrl = photo.optString("photographer_url", null as String?),
                unsplashUrl = photo.optString("unsplash_url", null as String?),
                downloadLocation = photo.optString("download_location", null as String?)
            ))
            added++
        }

        Log.i(TAG, "Fetched $added photo URLs via proxy (total metadata: ${photoMetadata.size})")
        return added
    }

    /**
     * Pre-download photos up to the given count.
     */
    private suspend fun prefetchPhotos(count: Int): Int = withContext(Dispatchers.IO) {
        var downloaded = 0
        for (i in 0 until minOf(count, photoMetadata.size)) {
            val meta = photoMetadata[i]
            if (downloadedIds.containsKey(meta.id)) continue
            if (cachedPhotos.size >= MAX_CACHED_PHOTOS) break

            val photo = downloadPhoto(meta)
            if (photo != null) {
                cachedPhotos.add(photo)
                downloaded++
            }
        }
        downloaded
    }

    /**
     * Mark a photo as displayed and trigger Unsplash download tracking.
     */
    fun markPhotoDisplayed(photoId: String) {
        lastDisplayedPhotoId = photoId
        triggerDownloadTracking(photoId)
    }

    /**
     * Trigger the Unsplash download_location endpoint for a displayed photo.
     * Required by Unsplash API guidelines when a photo is "used" by the application.
     * Each photo ID is only tracked once per session to avoid duplicate events.
     */
    private fun triggerDownloadTracking(photoId: String) {
        if (trackedDownloadIds.contains(photoId)) return

        val meta = photoMetadata.find { it.id == photoId } ?: return
        val downloadLocation = meta.downloadLocation ?: return

        trackedDownloadIds.add(photoId)

        // Fire-and-forget on IO thread
        Thread {
            try {
                val requestBody = JSONObject().apply {
                    put("download_location", downloadLocation)
                }

                val request = Request.Builder()
                    .url(DOWNLOAD_TRACK_ENDPOINT)
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Download tracked for $photoId")
                } else {
                    Log.w(TAG, "Download track failed: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                Log.w(TAG, "Download track error: ${e.message}")
            }
        }.start()
    }

    /**
     * Get the next photo for the slideshow.
     * Downloads on demand and auto-replenishes the metadata batch when running low.
     */
    suspend fun getNextPhoto(): PhotoItem? = withContext(Dispatchers.IO) {
        checkQueryChanged()

        if (photoMetadata.isEmpty()) {
            fetchBatch()
            if (photoMetadata.isEmpty()) return@withContext getNextCachedFallback()
        }

        // Auto-replenish when near end of current batch
        if (globalPhotoIndex >= (photoMetadata.size * PREFETCH_THRESHOLD).toInt()) {
            try {
                fetchBatch()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch new batch: ${e.message}")
            }
        }

        // Advance index
        globalPhotoIndex = (globalPhotoIndex + 1) % photoMetadata.size
        val meta = photoMetadata[globalPhotoIndex]

        // Already cached?
        val existing = cachedPhotos.find { it.id == meta.id }
        if (existing != null) return@withContext existing

        // Download new photo
        val newPhoto = try {
            downloadPhoto(meta)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download ${meta.id}: ${e.message}")
            null
        }

        if (newPhoto != null) {
            if (cachedPhotos.size >= MAX_CACHED_PHOTOS) {
                evictOldestDisplayed()
            }
            cachedPhotos.add(newPhoto)
            return@withContext newPhoto
        }

        getNextCachedFallback()
    }

    private fun getNextCachedFallback(): PhotoItem? {
        if (cachedPhotos.isEmpty()) return null
        val photo = cachedPhotos[fallbackCacheIndex % cachedPhotos.size]
        fallbackCacheIndex = (fallbackCacheIndex + 1) % cachedPhotos.size
        return photo
    }

    private suspend fun downloadPhoto(meta: UnsplashPhotoMeta): PhotoItem? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(meta.downloadUrl).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            Log.w(TAG, "Failed to download ${meta.id}: ${response.code}")
            return@withContext null
        }

        val imageBytes = response.body?.bytes()
        if (imageBytes == null || imageBytes.isEmpty()) return@withContext null

        val cacheFile = File(cacheDir, "${meta.id}.jpg")
        FileOutputStream(cacheFile).use { it.write(imageBytes) }
        downloadedIds[meta.id] = cacheFile

        // Build metadata for display (photographer attribution, description)
        val metadataMap = mutableMapOf<String, Any>()
        meta.photographer?.let { metadataMap["photographer"] = it }
        meta.photographerUrl?.let { metadataMap["photographer_url"] = it }
        meta.unsplashUrl?.let { metadataMap["unsplash_url"] = it }
        meta.description?.let { metadataMap["description"] = it }

        // Save sidecar JSON so attribution survives app restarts
        saveSidecarMetadata(meta.id, metadataMap)

        PhotoItem(
            id = meta.id,
            uri = Uri.fromFile(cacheFile),
            filename = "${meta.id}.jpg",
            createdAt = Date(),
            source = PhotoSourceType.UNSPLASH,
            width = meta.width,
            height = meta.height,
            metadata = if (metadataMap.isNotEmpty()) metadataMap else null
        )
    }

    private fun evictOldestDisplayed() {
        if (cachedPhotos.isEmpty()) return

        val lastIdx = if (lastDisplayedPhotoId != null) {
            cachedPhotos.indexOfFirst { it.id == lastDisplayedPhotoId }
        } else -1

        val evictIdx = if (lastIdx > 0) 0
        else if (lastIdx == 0 && cachedPhotos.size > 1) return
        else 0

        val evicted = cachedPhotos.removeAt(evictIdx)
        val file = downloadedIds.remove(evicted.id)
        file?.delete()
        File(cacheDir, "${evicted.id}.json").delete() // Remove sidecar too
        Log.d(TAG, "Evicted: ${evicted.id}")
    }

    /**
     * Check if the search query has changed and clear cache if so.
     */
    private fun checkQueryChanged() {
        val currentQuery = prefs.unsplashQuery.ifEmpty { "nature" }
        if (currentQuery != lastQuery) {
            Log.i(TAG, "Query changed from '$lastQuery' to '$currentQuery' - clearing cache")
            lastQuery = currentQuery
            clearCache()
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cachedPhotos.clear()
        downloadedIds.clear()
        photoMetadata.clear()
        trackedDownloadIds.clear()
        globalPhotoIndex = 0
        Log.i(TAG, "Cache cleared")
    }

    fun isReady(): Boolean = cachedPhotos.isNotEmpty()

    fun getSummary(): String {
        return if (cachedPhotos.isNotEmpty()) {
            val query = prefs.unsplashQuery.ifEmpty { "nature" }
            "${cachedPhotos.size} photos cached (query: $query)"
        } else {
            "Configured - syncing..."
        }
    }

    fun getCacheSizeFormatted(): String {
        val bytes = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
        return when {
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    /**
     * Save attribution metadata as a JSON sidecar file alongside the cached photo.
     */
    private fun saveSidecarMetadata(photoId: String, metadata: Map<String, Any>) {
        try {
            val sidecar = File(cacheDir, "$photoId.json")
            val json = JSONObject(metadata).toString()
            sidecar.writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save sidecar for $photoId: ${e.message}")
        }
    }

    /**
     * Load attribution metadata from a JSON sidecar file.
     * Returns null if no sidecar exists.
     */
    private fun loadSidecarMetadata(photoId: String): Map<String, Any>? {
        try {
            val sidecar = File(cacheDir, "$photoId.json")
            if (!sidecar.exists()) return null
            val json = JSONObject(sidecar.readText())
            val map = mutableMapOf<String, Any>()
            json.keys().forEach { key ->
                json.opt(key)?.let { map[key] = it }
            }
            return if (map.isNotEmpty()) map else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load sidecar for $photoId: ${e.message}")
            return null
        }
    }

    private data class UnsplashPhotoMeta(
        val id: String,
        val downloadUrl: String,
        val width: Int,
        val height: Int,
        val description: String?,
        val photographer: String?,
        val photographerUrl: String?,
        val unsplashUrl: String?,
        val downloadLocation: String?
    )
}
