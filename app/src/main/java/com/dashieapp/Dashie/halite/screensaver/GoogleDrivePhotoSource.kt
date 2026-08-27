package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Photo source that fetches photos from Google Drive via the google-drive-photos
 * edge function. Auth is handled server-side — Kotlin sends Supabase JWT + anon key.
 *
 * Flow:
 * 1. sync() calls edge function "sync" op → diffs Drive folder vs user_photos table
 * 2. getNextPhoto() downloads images on demand via edge function "get_url" op
 * 3. Photos cached to disk in a rolling window (same pattern as UnsplashPhotoSource)
 *
 * Requires: Supabase JWT cached in ConnectionPreferences (extracted by SupabaseTokenExtractor)
 */
class GoogleDrivePhotoSource(
    private val context: Context,
    private val connectionPrefs: ConnectionPreferences,
    private val screensaverPrefs: ScreensaverPreferences
) : PhotoSource {

    companion object {
        private const val TAG = "GoogleDrivePhotoSource"
        private const val CACHE_DIR_NAME = "gdrive_photo_cache"
        private const val MAX_CACHED_PHOTOS = 8
        private const val CONNECT_TIMEOUT_MS = 15000L
        private const val READ_TIMEOUT_MS = 60000L

        private val EDGE_FUNCTION_URL = BuildConfig.SUPABASE_URL + "/functions/v1/google-drive-photos"
    }

    // Photo index from user_photos table (lightweight metadata)
    private var photoIndex: MutableList<DrivePhotoMeta> = mutableListOf()

    // Downloaded photos ready to display
    private var cachedPhotos: MutableList<PhotoItem> = mutableListOf()

    // Track downloads by external_id -> cache file
    private val downloadedIds: MutableMap<String, File> = mutableMapOf()

    // Current position in index
    private var currentIndex = 0

    // Folder ID (set after first sync)
    private var folderId: String? = null

    // Fallback index for offline cycling
    private var fallbackCacheIndex = 0

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

    override fun getSourceType() = PhotoSourceType.GOOGLE_DRIVE
    override fun supportsAutoSync() = true
    override fun getPhotos(): List<PhotoItem> = cachedPhotos.toList()

    // ==================== SYNC ====================

    override suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val jwt = connectionPrefs.supabaseJwt
        if (jwt.isEmpty()) {
            return@withContext SyncResult(false, 0, 0, error = "No Supabase JWT — user not logged in")
        }

        try {
            val response = callEdgeFunction(jwt, JSONObject().apply {
                put("operation", "sync")
            })

            if (response == null) {
                return@withContext SyncResult(false, 0, 0, error = "Edge function call failed")
            }

            folderId = response.optString("folder_id", null)
            val total = response.optInt("total", 0)
            val newCount = response.optInt("new_count", 0)
            val removed = response.optInt("removed", 0)

            // Parse photo index from response
            val photosArray = response.optJSONArray("photos")
            if (photosArray != null) {
                photoIndex.clear()
                for (i in 0 until photosArray.length()) {
                    val p = photosArray.getJSONObject(i)
                    val locationObj = p.optJSONObject("location")
                    photoIndex.add(DrivePhotoMeta(
                        id = p.getString("id"),
                        externalId = p.getString("external_id"),
                        filename = p.optString("filename", "photo.jpg"),
                        thumbnailUrl = p.optString("external_thumbnail_url", null),
                        createdAt = p.optString("created_at", null),
                        locationCity = locationObj?.optString("city", null),
                        locationState = locationObj?.optString("state", null)
                    ))
                }
            }

            // Enrich any previously-cached photos with metadata from the index
            enrichCachedPhotosWithMetadata()

            // Prefetch first batch
            val prefetched = prefetchPhotos(MAX_CACHED_PHOTOS)

            Log.i(TAG, "Sync complete: $total in Drive, $newCount new, $removed removed, $prefetched prefetched")
            SyncResult(
                success = true,
                photosFound = total,
                photosNew = newCount,
                message = "$total photos indexed, $prefetched cached"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            SyncResult(false, 0, 0, error = "Sync failed: ${e.message}")
        }
    }

    // ==================== STREAMING NEXT PHOTO ====================

    /**
     * Get the next photo for the slideshow.
     * Downloads on demand from Drive via edge function.
     */
    suspend fun getNextPhoto(): PhotoItem? = withContext(Dispatchers.IO) {
        if (photoIndex.isEmpty()) {
            return@withContext getNextCachedFallback()
        }

        // Advance index
        currentIndex = (currentIndex + 1) % photoIndex.size
        val meta = photoIndex[currentIndex]

        // Already cached?
        val existing = cachedPhotos.find { it.id == meta.externalId }
        if (existing != null) return@withContext existing

        // Download
        val newPhoto = try {
            downloadPhoto(meta)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download ${meta.externalId}: ${e.message}")
            null
        }

        if (newPhoto != null) {
            if (cachedPhotos.size >= MAX_CACHED_PHOTOS) {
                evictOldest()
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

    // ==================== DOWNLOAD ====================

    private suspend fun prefetchPhotos(count: Int): Int = withContext(Dispatchers.IO) {
        var downloaded = 0
        for (i in 0 until minOf(count, photoIndex.size)) {
            val meta = photoIndex[i]
            if (downloadedIds.containsKey(meta.externalId)) continue
            if (cachedPhotos.size >= MAX_CACHED_PHOTOS) break

            val photo = downloadPhoto(meta)
            if (photo != null) {
                cachedPhotos.add(photo)
                downloaded++
            }
        }
        downloaded
    }

    private suspend fun downloadPhoto(meta: DrivePhotoMeta): PhotoItem? = withContext(Dispatchers.IO) {
        val jwt = connectionPrefs.supabaseJwt
        if (jwt.isEmpty()) return@withContext null

        // Get download URL + access token from edge function
        val urlResponse = callEdgeFunction(jwt, JSONObject().apply {
            put("operation", "get_url")
            put("file_id", meta.externalId)
        }) ?: return@withContext null

        val downloadUrl = urlResponse.optString("url", "")
        val accessToken = urlResponse.optString("access_token", "")
        if (downloadUrl.isEmpty() || accessToken.isEmpty()) return@withContext null

        // Download the image bytes
        val request = Request.Builder()
            .url(downloadUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Download failed for ${meta.externalId}: ${response.code}")
            return@withContext null
        }

        val imageBytes = response.body?.bytes()
        if (imageBytes == null || imageBytes.isEmpty()) return@withContext null

        // Cache to disk
        val extension = when {
            meta.filename.endsWith(".png", ignoreCase = true) -> "png"
            meta.filename.endsWith(".webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }
        val cacheFile = File(cacheDir, "${meta.externalId}.$extension")
        FileOutputStream(cacheFile).use { it.write(imageBytes) }
        downloadedIds[meta.externalId] = cacheFile

        Log.d(TAG, "Downloaded ${meta.filename} (${imageBytes.size / 1024}KB)")

        PhotoItem(
            id = meta.externalId,
            uri = Uri.fromFile(cacheFile),
            filename = meta.filename,
            createdAt = meta.createdAt?.let { parseDate(it) },
            source = PhotoSourceType.GOOGLE_DRIVE,
            metadata = buildMetadataMap(meta)
        )
    }

    // ==================== EDGE FUNCTION ====================

    private fun callEdgeFunction(jwt: String, body: JSONObject): JSONObject? {
        val request = Request.Builder()
            .url(EDGE_FUNCTION_URL)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $jwt")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e(TAG, "Edge function error ${response.code}: $errorBody")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        return JSONObject(responseBody)
    }

    // ==================== CACHE MANAGEMENT ====================

    private fun loadCachedPhotosFromDisk() {
        val cacheFiles = cacheDir.listFiles() ?: return
        val imageFiles = cacheFiles
            .filter { it.isFile && it.length() > 0 && it.extension in listOf("jpg", "png", "webp") }
            .sortedByDescending { it.lastModified() }
            .take(MAX_CACHED_PHOTOS)

        imageFiles.forEach { file ->
            val id = file.nameWithoutExtension
            if (!downloadedIds.containsKey(id)) {
                cachedPhotos.add(PhotoItem(
                    id = id,
                    uri = Uri.fromFile(file),
                    filename = file.name,
                    createdAt = Date(file.lastModified()),
                    source = PhotoSourceType.GOOGLE_DRIVE
                ))
                downloadedIds[id] = file
            }
        }

        if (cachedPhotos.isNotEmpty()) {
            Log.i(TAG, "Loaded ${cachedPhotos.size} cached Drive photos from disk")
        }
    }

    private fun evictOldest() {
        if (cachedPhotos.isEmpty()) return
        val evicted = cachedPhotos.removeAt(0)
        val file = downloadedIds.remove(evicted.id)
        file?.delete()
        Log.d(TAG, "Evicted: ${evicted.filename}")
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cachedPhotos.clear()
        downloadedIds.clear()
        photoIndex.clear()
        currentIndex = 0
        folderId = null
        Log.i(TAG, "Cache cleared")
    }

    fun isReady(): Boolean = cachedPhotos.isNotEmpty()

    fun getSummary(): String {
        return if (photoIndex.isNotEmpty()) {
            "${photoIndex.size} photos · ${cachedPhotos.size} cached"
        } else if (cachedPhotos.isNotEmpty()) {
            "${cachedPhotos.size} cached (offline)"
        } else {
            "Not synced"
        }
    }

    // ==================== HELPERS ====================

    /**
     * Build a metadata map from DrivePhotoMeta.
     * Only includes location (city/state) — date is handled by EXIF extraction.
     */
    private fun buildMetadataMap(meta: DrivePhotoMeta): Map<String, Any>? {
        val parts = mutableListOf<String>()
        meta.locationCity?.let { if (it.isNotBlank()) parts.add(it) }
        meta.locationState?.let { if (it.isNotBlank()) parts.add(it) }
        if (parts.isEmpty()) return null

        val locationStr = parts.joinToString(", ")
        return mapOf("location" to locationStr)
    }

    /**
     * Enrich previously-cached photos (loaded from disk) with metadata from the photo index.
     * Called after sync populates photoIndex so that cached photos get location data.
     */
    private fun enrichCachedPhotosWithMetadata() {
        if (photoIndex.isEmpty() || cachedPhotos.isEmpty()) return

        val indexById = photoIndex.associateBy { it.externalId }
        var enriched = 0

        cachedPhotos = cachedPhotos.map { photo ->
            val meta = indexById[photo.id]
            if (meta != null) {
                val metadata = buildMetadataMap(meta)
                if (metadata != null && photo.metadata == null) {
                    enriched++
                    photo.copy(metadata = metadata)
                } else {
                    photo
                }
            } else {
                photo
            }
        }.toMutableList()

        if (enriched > 0) {
            Log.i(TAG, "Enriched $enriched cached photos with metadata from index")
        }
    }

    private fun parseDate(isoString: String): Date? {
        // Try multiple ISO 8601 formats
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (format in formats) {
            try {
                return java.text.SimpleDateFormat(format, java.util.Locale.US).parse(isoString)
            } catch (_: Exception) { }
        }
        Log.w(TAG, "Could not parse date: $isoString")
        return null
    }

    private data class DrivePhotoMeta(
        val id: String,           // user_photos row ID
        val externalId: String,   // Google Drive file ID
        val filename: String,
        val thumbnailUrl: String?,
        val createdAt: String?,
        val locationCity: String?,
        val locationState: String?
    )
}
