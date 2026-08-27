package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Date

/**
 * Photo source that fetches photos from a self-hosted Immich server.
 *
 * Uses Immich's REST API:
 * - POST /api/auth/login — authenticate with email/password
 * - GET /api/albums — list available albums
 * - GET /api/albums/{id} — get album assets
 * - POST /api/search/random — get random photos (all-library mode)
 * - GET /api/assets/{id}/thumbnail?size=preview — download ~1440px preview
 *
 * Metadata (date, location, camera) is pulled from the Immich API response
 * rather than extracting EXIF from image bytes, since preview thumbnails
 * have EXIF stripped.
 *
 * Credentials are stored centrally in HA via /api/dashie/immich/token
 * so all devices (including kiosk) share the same login.
 */
class ImmichPhotoSource(
    private val context: Context,
    private val prefs: ScreensaverPreferences
) : PhotoSource {

    companion object {
        private const val TAG = "ImmichPhotoSource"
        private const val CACHE_DIR_NAME = "immich_cache"
        private const val MAX_CACHED_PHOTOS = 8
    }

    // Asset metadata from Immich API (not yet downloaded)
    private var assetMetadata: List<ImmichAsset> = emptyList()

    // Downloaded photos ready for display
    private var cachedPhotos: MutableList<PhotoItem> = mutableListOf()
    private val downloadedIds: MutableMap<String, File> = mutableMapOf()

    // Streaming state
    private var globalPhotoIndex = 0
    private var lastDisplayedPhotoId: String? = null
    private var fallbackCacheIndex = 0
    private var isRandomMode = false  // True when using "all photos" random mode
    private val seenAssetIds = mutableSetOf<String>()  // Track shown assets to avoid repeats

    // API client
    private var apiClient: ImmichApiClient? = null

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
    }

    init {
        loadCachedPhotosFromDisk()
    }

    // ── PhotoSource Interface ───────────────────────────────────────

    override fun getSourceType() = PhotoSourceType.IMMICH

    override fun supportsAutoSync() = true

    override fun getPhotos(): List<PhotoItem> = cachedPhotos.toList()

    override suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val client = apiClient
        if (client == null) {
            return@withContext SyncResult(
                success = false, photosFound = 0, photosNew = 0,
                error = "Immich not configured"
            )
        }

        try {
            // Fetch assets based on album selection
            val selectedAlbums = prefs.immichSelectedAlbums
            isRandomMode = selectedAlbums.isEmpty() || selectedAlbums == "*"
            val assets = if (!isRandomMode) {
                fetchAlbumAssets(client, selectedAlbums)
            } else {
                client.getRandomAssets(50)
            }

            if (assets.isEmpty()) {
                return@withContext SyncResult(
                    success = true, photosFound = 0, photosNew = 0,
                    message = "No photos found in Immich"
                )
            }

            val previousCount = assetMetadata.size
            assetMetadata = assets
            Log.i(TAG, "Found ${assets.size} photos in Immich")

            // Clear cache if list changed significantly
            if (previousCount > 0 && kotlin.math.abs(assets.size - previousCount) > 5) {
                clearCache()
            }

            val prefetched = prefetchInitialPhotos(MAX_CACHED_PHOTOS)

            SyncResult(
                success = true,
                photosFound = assets.size,
                photosNew = prefetched,
                message = "Synced ${assets.size} photos from Immich"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing from Immich", e)
            SyncResult(
                success = false, photosFound = 0, photosNew = 0,
                error = "Sync failed: ${e.message}"
            )
        }
    }

    // ── Credentials ─────────────────────────────────────────────────

    fun setCredentials(serverUrl: String, accessToken: String) {
        val url = serverUrl.trimEnd('/')
        if (apiClient == null) {
            apiClient = ImmichApiClient(url, accessToken)
        } else {
            apiClient?.updateCredentials(url, accessToken)
        }
        Log.d(TAG, "Credentials set: serverUrl=$url")
    }

    fun hasCredentials(): Boolean {
        return prefs.immichServerUrl.isNotEmpty() && prefs.immichAccessToken.isNotEmpty()
    }

    // ── Streaming ───────────────────────────────────────────────────

    fun markPhotoDisplayed(photoId: String) {
        lastDisplayedPhotoId = photoId
    }

    suspend fun getNextPhoto(): PhotoItem? = withContext(Dispatchers.IO) {
        if (assetMetadata.isEmpty()) {
            return@withContext getNextCachedPhotoFallback()
        }

        val client = apiClient ?: return@withContext getNextCachedPhotoFallback()

        globalPhotoIndex++

        // In random mode, fetch a fresh batch when we've exhausted the current one
        if (isRandomMode && globalPhotoIndex >= assetMetadata.size) {
            Log.i(TAG, "Exhausted current batch of ${assetMetadata.size} random photos, fetching fresh batch...")
            try {
                val freshAssets = client.getRandomAssets(50)
                    .filter { it.id !in seenAssetIds }  // Skip already-shown photos
                if (freshAssets.isNotEmpty()) {
                    assetMetadata = freshAssets
                    globalPhotoIndex = 0
                    Log.i(TAG, "Fetched ${freshAssets.size} new random photos (${seenAssetIds.size} seen total)")
                } else {
                    // All photos seen — reset and allow repeats
                    Log.i(TAG, "All photos seen (${seenAssetIds.size}), resetting for fresh cycle")
                    seenAssetIds.clear()
                    val resetAssets = client.getRandomAssets(50)
                    if (resetAssets.isNotEmpty()) {
                        assetMetadata = resetAssets
                        globalPhotoIndex = 0
                    } else {
                        globalPhotoIndex = 0  // Fall back to cycling existing list
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch fresh random batch: ${e.message}")
                globalPhotoIndex = 0  // Cycle existing list as fallback
            }
        } else {
            globalPhotoIndex = globalPhotoIndex % assetMetadata.size
        }

        val asset = assetMetadata[globalPhotoIndex]
        seenAssetIds.add(asset.id)

        // Check if already cached
        val existing = cachedPhotos.find { it.id == asset.id }
        if (existing != null) return@withContext existing

        // Download new photo
        val shouldEvict = cachedPhotos.size >= MAX_CACHED_PHOTOS

        val newPhoto = try {
            downloadPhoto(client, asset)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to download ${asset.originalFileName}: ${e.message}")
            null
        }

        if (newPhoto != null) {
            if (shouldEvict) evictOldestDisplayed()
            cachedPhotos.add(newPhoto)
            downloadedIds[asset.id] = File(cacheDir, "${asset.id}.jpg")
            return@withContext newPhoto
        }

        getNextCachedPhotoFallback()
    }

    // ── Album Fetching ──────────────────────────────────────────────

    /**
     * Fetch albums — exposed for the album picker settings page.
     */
    suspend fun fetchAlbums(): List<ImmichAlbum> {
        return apiClient?.getAlbums() ?: emptyList()
    }

    private suspend fun fetchAlbumAssets(
        client: ImmichApiClient,
        selectedAlbumsJson: String
    ): List<ImmichAsset> {
        val albumIds = try {
            val arr = JSONArray(selectedAlbumsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            Log.w(TAG, "Invalid selected albums JSON: $selectedAlbumsJson")
            return client.getRandomAssets(50)
        }

        val allAssets = mutableListOf<ImmichAsset>()
        for (albumId in albumIds) {
            allAssets.addAll(client.getAlbumAssets(albumId))
        }

        // Deduplicate by asset ID (photos can be in multiple albums)
        return allAssets.distinctBy { it.id }
    }

    // ── Download & Cache ────────────────────────────────────────────

    private suspend fun downloadPhoto(
        client: ImmichApiClient,
        asset: ImmichAsset
    ): PhotoItem? {
        val imageBytes = client.downloadThumbnail(asset.id)
        if (imageBytes == null || imageBytes.isEmpty()) {
            Log.w(TAG, "Empty thumbnail for ${asset.originalFileName}")
            return null
        }

        val cacheFile = File(cacheDir, "${asset.id}.jpg")
        FileOutputStream(cacheFile).use { it.write(imageBytes) }

        // Log downloaded preview dimensions vs original for zoom debugging
        val previewOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(cacheFile.absolutePath, previewOpts)
        val origW = asset.exifInfo?.exifImageWidth
        val origH = asset.exifInfo?.exifImageHeight
        val previewW = previewOpts.outWidth
        val previewH = previewOpts.outHeight
        val origAspect = if (origW != null && origH != null && origH > 0) origW.toFloat() / origH else null
        val previewAspect = if (previewH > 0) previewW.toFloat() / previewH else null
        Log.i(TAG, "PHOTO_DEBUG ${asset.originalFileName}: " +
            "original=${origW}x${origH} (aspect=${origAspect?.let { "%.3f".format(it) }}), " +
            "preview=${previewW}x${previewH} (aspect=${previewAspect?.let { "%.3f".format(it) }}), " +
            "bytes=${imageBytes.size}")
        if (origAspect != null && previewAspect != null && kotlin.math.abs(origAspect - previewAspect) > 0.05f) {
            Log.w(TAG, "PHOTO_DEBUG ⚠️ ASPECT RATIO MISMATCH for ${asset.originalFileName}! " +
                "Original=$origAspect, Preview=$previewAspect — Immich may be returning a smart crop")
        }

        // If exifInfo is missing, fetch full asset details
        val enrichedAsset = if (asset.exifInfo == null) {
            Log.d(TAG, "No exifInfo for ${asset.originalFileName}, fetching asset details...")
            val details = client.getAssetDetails(asset.id)
            Log.d(TAG, "Asset details: exif=${details?.exifInfo != null}, localDateTime=${details?.localDateTime}")
            details ?: asset
        } else {
            Log.d(TAG, "Asset has exifInfo: city=${asset.exifInfo.city}, date=${asset.exifInfo.dateTimeOriginal}")
            asset
        }

        // Map metadata from Immich API (not EXIF extraction)
        val metadata = ImmichMetadataMapper.toMetadataMap(enrichedAsset)
        val photoMetadata = ImmichMetadataMapper.toPhotoMetadata(enrichedAsset)
        Log.d(TAG, "Mapped metadata for ${enrichedAsset.originalFileName}: " +
            "date=${photoMetadata?.dateFormatted}, loc=${photoMetadata?.location}, " +
            "mapKeys=${metadata.keys}")

        // Save metadata sidecar so disk-cached photos retain metadata on reload
        saveMetadataSidecar(asset.id, enrichedAsset.originalFileName, metadata,
            photoMetadata?.dateTaken ?: parseLocalDateTime(enrichedAsset.localDateTime))

        return PhotoItem(
            id = asset.id,
            uri = Uri.fromFile(cacheFile),
            filename = enrichedAsset.originalFileName,
            createdAt = photoMetadata?.dateTaken ?: parseLocalDateTime(enrichedAsset.localDateTime),
            source = PhotoSourceType.IMMICH,
            metadata = metadata
        )
    }

    private suspend fun prefetchInitialPhotos(count: Int): Int = withContext(Dispatchers.IO) {
        val client = apiClient ?: return@withContext 0
        var downloaded = 0

        for (i in 0 until minOf(count, assetMetadata.size)) {
            val asset = assetMetadata[i]
            if (downloadedIds.containsKey(asset.id)) continue
            if (cachedPhotos.size >= MAX_CACHED_PHOTOS) break

            try {
                val photo = downloadPhoto(client, asset)
                if (photo != null) {
                    cachedPhotos.add(photo)
                    downloadedIds[asset.id] = File(cacheDir, "${asset.id}.jpg")
                    downloaded++
                    Log.d(TAG, "Prefetched: ${asset.originalFileName} (${cachedPhotos.size}/$MAX_CACHED_PHOTOS)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to prefetch ${asset.originalFileName}: ${e.message}")
            }
        }
        downloaded
    }

    // ── Cache Management ────────────────────────────────────────────

    private fun loadCachedPhotosFromDisk() {
        val cacheFiles = cacheDir.listFiles() ?: return
        if (cacheFiles.isEmpty()) return

        val imageFiles = cacheFiles
            .filter { it.isFile && it.length() > 0 && it.extension == "jpg" }
            .sortedByDescending { it.lastModified() }
            .take(MAX_CACHED_PHOTOS)

        // Clean up excess image files (and their sidecars)
        val allImages = cacheFiles.filter { it.isFile && it.length() > 0 && it.extension == "jpg" }
        if (allImages.size > MAX_CACHED_PHOTOS) {
            allImages.sortedBy { it.lastModified() }
                .take(allImages.size - MAX_CACHED_PHOTOS)
                .forEach {
                    it.delete()
                    File(cacheDir, "${it.nameWithoutExtension}.json").delete()
                }
        }

        var loaded = 0
        imageFiles.forEach { file ->
            val assetId = file.nameWithoutExtension
            if (!downloadedIds.containsKey(assetId)) {
                val sidecar = loadMetadataSidecar(assetId)
                cachedPhotos.add(PhotoItem(
                    id = assetId,
                    uri = Uri.fromFile(file),
                    filename = sidecar?.first ?: file.name,
                    createdAt = sidecar?.third ?: Date(file.lastModified()),
                    source = PhotoSourceType.IMMICH,
                    metadata = sidecar?.second
                ))
                downloadedIds[assetId] = file
                loaded++
            }
        }

        if (loaded > 0) {
            Log.i(TAG, "Loaded $loaded cached Immich photos from disk (${imageFiles.count { loadMetadataSidecar(it.nameWithoutExtension) != null }} with metadata)")
        }
    }

    private fun evictOldestDisplayed() {
        if (cachedPhotos.isEmpty()) return

        val lastDisplayedIndex = if (lastDisplayedPhotoId != null) {
            cachedPhotos.indexOfFirst { it.id == lastDisplayedPhotoId }
        } else -1

        val indexToEvict = if (lastDisplayedIndex > 0) {
            0
        } else if (lastDisplayedIndex == 0 && cachedPhotos.size > 1) {
            return  // Don't evict the currently displayed photo
        } else {
            0
        }

        val evicted = cachedPhotos.removeAt(indexToEvict)
        val file = downloadedIds.remove(evicted.id)
        file?.delete()
        File(cacheDir, "${evicted.id}.json").delete()
        Log.d(TAG, "Evicted: ${evicted.filename}")
    }

    private fun getNextCachedPhotoFallback(): PhotoItem? {
        if (cachedPhotos.isEmpty()) return null
        val photo = cachedPhotos[fallbackCacheIndex % cachedPhotos.size]
        fallbackCacheIndex = (fallbackCacheIndex + 1) % cachedPhotos.size
        return photo
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
        cachedPhotos.clear()
        downloadedIds.clear()
        seenAssetIds.clear()
        Log.i(TAG, "Cache cleared")
    }

    // ── Metadata Sidecar ────────────────────────────────────────────

    private fun saveMetadataSidecar(
        assetId: String,
        filename: String,
        metadata: Map<String, Any>,
        createdAt: Date?
    ) {
        try {
            val json = JSONObject()
            json.put("filename", filename)
            json.put("createdAt", createdAt?.time ?: 0L)
            val metaObj = JSONObject()
            for ((key, value) in metadata) {
                metaObj.put(key, value)
            }
            json.put("metadata", metaObj)
            File(cacheDir, "$assetId.json").writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save metadata sidecar for $assetId: ${e.message}")
        }
    }

    /**
     * Load metadata sidecar for a cached asset.
     * Returns Triple(filename, metadataMap, createdAt) or null if no sidecar.
     */
    private fun loadMetadataSidecar(assetId: String): Triple<String, Map<String, Any>, Date?>? {
        try {
            val file = File(cacheDir, "$assetId.json")
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            val filename = json.optString("filename", "$assetId.jpg")
            val createdAtMs = json.optLong("createdAt", 0L)
            val createdAt = if (createdAtMs > 0) Date(createdAtMs) else null
            val metaObj = json.optJSONObject("metadata") ?: return null
            val metadata = mutableMapOf<String, Any>()
            for (key in metaObj.keys()) {
                metadata[key] = metaObj.get(key)
            }
            return Triple(filename, metadata, createdAt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load metadata sidecar for $assetId: ${e.message}")
            return null
        }
    }

    // ── Utility ─────────────────────────────────────────────────────

    fun isReady(): Boolean = hasCredentials() && cachedPhotos.isNotEmpty()

    fun getSummary(): String {
        return if (hasCredentials()) {
            if (assetMetadata.isNotEmpty()) {
                "${assetMetadata.size} photos from Immich"
            } else if (cachedPhotos.isNotEmpty()) {
                "${cachedPhotos.size} cached photos"
            } else {
                "Connected — no photos found"
            }
        } else {
            "Not connected to Immich"
        }
    }

    private fun parseLocalDateTime(isoString: String?): Date? {
        if (isoString.isNullOrEmpty()) return null
        return try {
            val cleaned = isoString.substringBefore("+").substringBefore("Z").substringBefore(".")
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(cleaned)
        } catch (_: Exception) {
            null
        }
    }
}
