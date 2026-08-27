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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Photo source that fetches photos from Dashie Cloud (Supabase Storage) via the
 * shared database-operations edge function. Mirrors the JS web/mobile path —
 * same edge function, same operations (list_photos, get_signed_urls).
 *
 * Flow:
 * 1. sync() → list_photos op returns metadata; then get_signed_urls op returns
 *    1-hour signed URLs for full-size + thumbnails. Index is built and the
 *    first batch is downloaded to disk.
 * 2. getNextPhoto() downloads on demand using cached signed URLs; if a 403
 *    surfaces (URL expired), a re-sync is triggered to refresh URLs.
 *
 * Auth: dashboard-owner-only for v1. Supabase JWT cached in
 * ConnectionPreferences (via SupabaseTokenExtractor) is sent both as the
 * Authorization Bearer header AND as `jwtToken` in the body — the
 * database-operations edge function expects both.
 */
class SupabasePhotoSource(
    private val context: Context,
    private val connectionPrefs: ConnectionPreferences,
    private val screensaverPrefs: ScreensaverPreferences,
    // Overridable for tests; production always uses the real edge function
    // URL and the standard timeouts.
    private val edgeFunctionUrl: String = EDGE_FUNCTION_URL,
    readTimeoutMs: Long = READ_TIMEOUT_MS
) : PhotoSource {

    companion object {
        private const val TAG = "SupabasePhotoSource"
        private const val CACHE_DIR_NAME = "supabase_photo_cache"
        private const val MAX_CACHED_PHOTOS = 8
        private const val SIGN_BATCH_SIZE = 8
        private const val CONNECT_TIMEOUT_MS = 15000L
        private const val READ_TIMEOUT_MS = 60000L
        private const val LIST_LIMIT = 1000
        // 1-hour expiry matches what the JS path requests; we'll re-sign on
        // 403 or explicit refresh rather than running a background timer.
        private const val URL_EXPIRY_SECONDS = 3600

        private val EDGE_FUNCTION_URL = BuildConfig.SUPABASE_URL + "/functions/v1/database-operations"
    }

    private var photoIndex: MutableList<SupabasePhotoMeta> = mutableListOf()
    private var cachedPhotos: MutableList<PhotoItem> = mutableListOf()
    private val downloadedIds: MutableMap<String, File> = mutableMapOf()
    private var currentIndex = 0
    private var fallbackCacheIndex = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
    }

    init {
        loadCachedPhotosFromDisk()
    }

    override fun getSourceType() = PhotoSourceType.SUPABASE
    override fun supportsAutoSync() = true
    override fun getPhotos(): List<PhotoItem> = cachedPhotos.toList()

    // ==================== SYNC ====================

    override suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val jwt = connectionPrefs.supabaseJwt
        if (jwt.isEmpty()) {
            return@withContext SyncResult(false, 0, 0, error = "No Supabase JWT — user not logged in")
        }

        try {
            // 1. list_photos — metadata only
            val listResponse = callEdgeFunction(jwt, "list_photos", JSONObject().apply {
                put("folder", JSONObject.NULL)
                put("limit", LIST_LIMIT)
            }) ?: return@withContext SyncResult(false, 0, 0, error = "list_photos failed")

            val photosArray = listResponse.optJSONArray("photos")
                ?: return@withContext SyncResult(false, 0, 0, error = "list_photos response missing photos array")

            val total = photosArray.length()
            if (total == 0) {
                photoIndex.clear()
                return@withContext SyncResult(true, 0, 0, message = "No photos in Dashie Cloud")
            }

            // Parse all metadata up front; sign URLs lazily.
            val parsed = mutableListOf<SupabasePhotoMeta>()
            for (i in 0 until total) {
                val p = photosArray.getJSONObject(i)
                val storagePath = p.optString("storage_path", "")
                if (storagePath.isEmpty()) continue
                val thumbnailPath = p.optString("thumbnail_path", "").takeIf { it.isNotEmpty() }
                parsed.add(SupabasePhotoMeta(
                    id = p.getString("id"),
                    filename = p.optString("filename", "photo.jpg"),
                    storagePath = storagePath,
                    thumbnailPath = thumbnailPath,
                    uploadedAt = p.optString("uploaded_at", null),
                    createdAt = p.optString("created_at", null),
                    location = extractLocation(p)
                ))
            }

            val previousIds = photoIndex.map { it.id }.toSet()
            photoIndex.clear()
            photoIndex.addAll(parsed)
            val newCount = photoIndex.count { it.id !in previousIds }

            // 2. Sign just the first batch — server-side signing of all 150+
            //    paths is the slow part, so we defer the rest. The remaining
            //    photos get signed on-demand inside getNextPhoto().
            signNextBatch(jwt, fromIndex = 0, count = SIGN_BATCH_SIZE)

            enrichCachedPhotosWithMetadata()

            // 3. Download just the first photo so the slideshow can start.
            //    Subsequent prefetch happens in startBackgroundPrefetch(),
            //    which the factory schedules right after slideshow.start().
            val firstReady = photoIndex.firstOrNull {
                it.fullUrl != null && !downloadedIds.containsKey(it.id)
            }
            if (firstReady != null && cachedPhotos.size < MAX_CACHED_PHOTOS) {
                val photo = downloadPhoto(firstReady)
                if (photo != null) cachedPhotos.add(photo)
            }

            Log.i(TAG, "Sync first-photo ready: $total in cloud, $newCount new, ${cachedPhotos.size} cached")
            SyncResult(
                success = true,
                photosFound = total,
                photosNew = newCount,
                message = "$total photos indexed"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            SyncResult(false, 0, 0, error = "Sync failed: ${e.message}")
        }
    }

    /**
     * Continue prefetching the next [count] photos in the background after
     * sync() returns. Slideshow starts on photo 1; this fills up the rest of
     * the cache window before the slideshow advances to them.
     */
    suspend fun startBackgroundPrefetch(count: Int = MAX_CACHED_PHOTOS) = withContext(Dispatchers.IO) {
        val jwt = connectionPrefs.supabaseJwt
        if (jwt.isEmpty()) return@withContext
        // Make sure URLs exist for the photos we're about to download.
        signNextBatch(jwt, fromIndex = 0, count = count)
        for (i in 0 until minOf(count, photoIndex.size)) {
            val meta = photoIndex[i]
            if (downloadedIds.containsKey(meta.id)) continue
            if (cachedPhotos.size >= MAX_CACHED_PHOTOS) break
            val photo = downloadPhoto(meta) ?: continue
            cachedPhotos.add(photo)
        }
    }

    /**
     * Sign up to [count] photos starting at [fromIndex] that don't yet have a
     * fullUrl. Mutates photoIndex in place with the signed URLs.
     */
    private fun signNextBatch(jwt: String, fromIndex: Int, count: Int) {
        if (photoIndex.isEmpty() || count <= 0) return
        val toSign = mutableListOf<Int>()      // indices in photoIndex
        val storagePaths = mutableListOf<String>()
        var i = fromIndex
        while (i < photoIndex.size && toSign.size < count) {
            val m = photoIndex[i]
            if (m.fullUrl == null) {
                toSign.add(i)
                storagePaths.add(m.storagePath)
                m.thumbnailPath?.let { storagePaths.add(it) }
            }
            i++
        }
        if (storagePaths.isEmpty()) return

        val response = callEdgeFunction(jwt, "get_signed_urls", JSONObject().apply {
            put("storage_paths", JSONArray(storagePaths))
            put("expiry_seconds", URL_EXPIRY_SECONDS)
        }) ?: return
        val urlMap = response.optJSONObject("signed_urls") ?: return

        for (idx in toSign) {
            val m = photoIndex[idx]
            val fullUrl = urlMap.optString(m.storagePath, "").takeIf { it.isNotEmpty() } ?: continue
            val thumbUrl = m.thumbnailPath?.let { urlMap.optString(it, "") }?.takeIf { it.isNotEmpty() }
            photoIndex[idx] = m.copy(fullUrl = fullUrl, thumbUrl = thumbUrl)
        }
    }

    // ==================== STREAMING NEXT PHOTO ====================

    suspend fun getNextPhoto(): PhotoItem? = withContext(Dispatchers.IO) {
        if (photoIndex.isEmpty()) {
            return@withContext getNextCachedFallback()
        }

        currentIndex = (currentIndex + 1) % photoIndex.size
        var meta = photoIndex[currentIndex]

        val existing = cachedPhotos.find { it.id == meta.id }
        if (existing != null) return@withContext existing

        // Lazy-sign the next chunk if this photo's URL hasn't been generated
        // yet — happens once we exhaust the initial 8 signed in sync().
        if (meta.fullUrl == null) {
            val jwt = connectionPrefs.supabaseJwt
            if (jwt.isNotEmpty()) {
                signNextBatch(jwt, fromIndex = currentIndex, count = SIGN_BATCH_SIZE)
                meta = photoIndex[currentIndex]
            }
            if (meta.fullUrl == null) return@withContext getNextCachedFallback()
        }

        val newPhoto = try {
            downloadPhoto(meta)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download ${meta.filename}: ${e.message}")
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

    /**
     * Download photo bytes from the signed URL. Signed URLs have auth baked
     * in, so no Authorization header is needed. On 403/expired we let the
     * caller surface and trigger a re-sync at the slideshow level.
     */
    private fun downloadPhoto(meta: SupabasePhotoMeta): PhotoItem? {
        val url = meta.fullUrl ?: return null
        val request = Request.Builder().url(url).build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            Log.w(TAG, "Download failed for ${meta.filename}: ${e.message}")
            return null
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "Download failed for ${meta.filename}: ${response.code}")
            return null
        }
        val imageBytes = response.body?.bytes() ?: return null
        if (imageBytes.isEmpty()) return null

        val extension = when {
            meta.filename.endsWith(".png", ignoreCase = true) -> "png"
            meta.filename.endsWith(".webp", ignoreCase = true) -> "webp"
            meta.filename.endsWith(".heic", ignoreCase = true) -> "heic"
            else -> "jpg"
        }
        val cacheFile = File(cacheDir, "${meta.id}.$extension")
        FileOutputStream(cacheFile).use { it.write(imageBytes) }
        downloadedIds[meta.id] = cacheFile

        Log.d(TAG, "Downloaded ${meta.filename} (${imageBytes.size / 1024}KB)")

        return PhotoItem(
            id = meta.id,
            uri = Uri.fromFile(cacheFile),
            filename = meta.filename,
            createdAt = meta.uploadedAt?.let { parseDate(it) },
            source = PhotoSourceType.SUPABASE,
            metadata = buildMetadataMap(meta)
        )
    }

    // ==================== EDGE FUNCTION ====================

    /**
     * Call database-operations. JS sends `{operation, data, jwtToken}` for
     * dashboard-owner auth, with JWT also in the Authorization header.
     */
    private fun callEdgeFunction(jwt: String, operation: String, data: JSONObject): JSONObject? {
        val body = JSONObject().apply {
            put("operation", operation)
            put("data", data)
            put("jwtToken", jwt)
        }
        val request = Request.Builder()
            .url(edgeFunctionUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $jwt")
            .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            // Network failure (timeout, connection reset, etc.). Callers treat
            // null as "no result" and degrade gracefully (cached fallback /
            // re-sign later), so swallow it here rather than letting it crash
            // the process from an unprotected call site like getNextPhoto().
            Log.w(TAG, "Edge function call failed ($operation): ${e.message}")
            return null
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e(TAG, "Edge function error ${response.code} ($operation): $errorBody")
            return null
        }
        val responseBody = response.body?.string() ?: return null
        return JSONObject(responseBody)
    }

    // ==================== CACHE MANAGEMENT ====================

    private fun loadCachedPhotosFromDisk() {
        val cacheFiles = cacheDir.listFiles() ?: return
        val imageFiles = cacheFiles
            .filter { it.isFile && it.length() > 0 && it.extension in listOf("jpg", "png", "webp", "heic") }
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
                    source = PhotoSourceType.SUPABASE
                ))
                downloadedIds[id] = file
            }
        }

        if (cachedPhotos.isNotEmpty()) {
            Log.i(TAG, "Loaded ${cachedPhotos.size} cached Dashie Cloud photos from disk")
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

    private fun buildMetadataMap(meta: SupabasePhotoMeta): Map<String, Any>? {
        val map = mutableMapOf<String, Any>()
        val dateString = meta.createdAt?.takeIf { it.isNotBlank() } ?: meta.uploadedAt
        val date = dateString?.let { parseDate(it) }
        if (date != null) {
            map["dateTaken"] = date.time
            map["dateFormatted"] = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(date)
        }
        meta.location?.takeIf { it.isNotBlank() && it != "null" }?.let { map["location"] = it }
        return if (map.isEmpty()) null else map
    }

    /**
     * Re-attach metadata (location, filename) to photos already on disk —
     * called after sync repopulates photoIndex so cached photos pick up any
     * new location strings.
     */
    private fun enrichCachedPhotosWithMetadata() {
        if (photoIndex.isEmpty() || cachedPhotos.isEmpty()) return
        val indexById = photoIndex.associateBy { it.id }
        var enriched = 0
        cachedPhotos = cachedPhotos.map { photo ->
            val meta = indexById[photo.id]
            if (meta != null) {
                val metadata = buildMetadataMap(meta)
                if (metadata != null && photo.metadata == null) {
                    enriched++
                    photo.copy(metadata = metadata, filename = meta.filename)
                } else {
                    photo
                }
            } else photo
        }.toMutableList()
        if (enriched > 0) {
            Log.i(TAG, "Enriched $enriched cached photos with metadata from index")
        }
    }

    /**
     * Pull "city, state" out of the photo record. The JS widget reads
     * `photo.location.city` / `.state` — `location` is a JSONObject. Older
     * rows may have it nested under `image_metadata.location` instead.
     */
    private fun extractLocation(p: JSONObject): String? {
        // 1. top-level `location` object (preferred — matches JS widget path)
        p.optJSONObject("location")?.let { obj ->
            formatCityState(obj)?.let { return it }
        }
        // 2. nested `image_metadata.location` object (legacy rows)
        p.optJSONObject("image_metadata")?.optJSONObject("location")?.let { obj ->
            formatCityState(obj)?.let { return it }
        }
        // 3. top-level `location` plain string (rare; older format).
        // optString returns the literal "null" when the JSON value is JSON-null
        // (Android JSON quirk), so explicitly reject that — otherwise the
        // slideshow renders "null" as the location text.
        val asString = p.optString("location", "").trim()
        if (asString.isNotBlank() && asString != "null" && !asString.startsWith("{")) return asString
        return null
    }

    private fun formatCityState(locObj: JSONObject): String? {
        val parts = mutableListOf<String>()
        locObj.optString("city", "").takeIf { it.isNotBlank() && it != "null" }?.let { parts.add(it) }
        locObj.optString("state", "").takeIf { it.isNotBlank() && it != "null" }?.let { parts.add(it) }
        if (parts.isEmpty()) {
            locObj.optString("country", "").takeIf { it.isNotBlank() && it != "null" }?.let { parts.add(it) }
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    private fun parseDate(isoString: String): Date? {
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

    private data class SupabasePhotoMeta(
        val id: String,
        val filename: String,
        val storagePath: String,
        val thumbnailPath: String?,
        val uploadedAt: String?,
        val createdAt: String?,
        val location: String?,
        val fullUrl: String? = null,
        val thumbUrl: String? = null
    )
}
