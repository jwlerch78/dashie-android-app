package com.dashieapp.Dashie.halite.screensaver

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Singleton photo cache for HTML screensaver.
 *
 * Shared between:
 * - HaliteScreenController: Prefetches photos to cache
 * - DashieWebViewClient: Serves cached photos via shouldInterceptRequest
 *
 * This enables instant photo display without using file:// URLs
 * (which WebView blocks for security).
 *
 * Uses LRU eviction to keep only MAX_CACHED_PHOTOS in memory/disk,
 * preventing unbounded memory growth with large photo libraries.
 */
object PhotoCache {
    private const val TAG = "PhotoCache"
    private const val CACHE_DIR_NAME = "html_screensaver_cache"

    // Maximum number of photos to keep in cache (rotating window)
    private const val MAX_CACHED_PHOTOS = 5

    // URL -> CacheEntry mapping with access tracking for LRU eviction
    private data class CacheEntry(
        val localPath: String,
        var lastAccessTime: Long = System.currentTimeMillis()
    )
    private val cachedPaths = mutableMapOf<String, CacheEntry>()

    // Cache directory (initialized lazily)
    private var cacheDir: File? = null

    // Cache version - increment to invalidate old cached files
    private const val CACHE_VERSION = 2  // v2: HEIC->JPEG conversion on prefetch

    /**
     * Initialize the cache directory.
     * Must be called once during app startup.
     * Clears cache if version changed (e.g., format change).
     */
    fun init(context: Context) {
        cacheDir = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

        // Check cache version and clear if outdated
        val versionFile = File(cacheDir, ".cache_version")
        val currentVersion = if (versionFile.exists()) {
            try { versionFile.readText().trim().toInt() } catch (e: Exception) { 0 }
        } else { 0 }

        if (currentVersion != CACHE_VERSION) {
            Log.i(TAG, "📷 Cache version changed ($currentVersion -> $CACHE_VERSION), clearing old cache")
            clear()
            versionFile.writeText(CACHE_VERSION.toString())
        } else {
            // Clean up any orphaned files beyond MAX_CACHED_PHOTOS
            // This handles the case where we reduced the limit or files accumulated
            cleanupOrphanedFiles()
        }

        Log.i(TAG, "📷 Photo cache initialized: ${cacheDir?.absolutePath} (max $MAX_CACHED_PHOTOS photos)")
    }

    /**
     * Get the cache directory.
     */
    fun getCacheDir(): File? = cacheDir

    /**
     * Check if a URL is cached and return the local file path.
     * Returns null if not cached or file doesn't exist.
     *
     * First checks the in-memory map, then checks if the expected file exists.
     * This handles the case where files exist but the map was cleared (app restart).
     * Updates last access time for LRU tracking.
     */
    fun getCachedPath(url: String): String? {
        // Check in-memory map first
        val entry = cachedPaths[url]
        if (entry != null) {
            val file = File(entry.localPath)
            if (file.exists() && file.length() > 0) {
                // Update access time for LRU
                entry.lastAccessTime = System.currentTimeMillis()
                return entry.localPath
            }
        }

        // Check if the expected file exists on disk (handles app restart)
        // But only load it if we have room in the cache
        val expectedFile = getCacheFile(url)
        if (expectedFile != null && expectedFile.exists() && expectedFile.length() > 0) {
            // Add to map for future lookups (with eviction if needed)
            addToCache(url, expectedFile.absolutePath)
            Log.d(TAG, "📷 Found existing cache file: ${expectedFile.absolutePath}")
            return expectedFile.absolutePath
        }

        return null
    }

    /**
     * Add a URL to the cache mapping.
     * Called after successfully downloading a photo.
     * Evicts oldest entry if cache exceeds MAX_CACHED_PHOTOS.
     */
    fun addToCache(url: String, localPath: String) {
        // If URL already in cache, just update it
        if (cachedPaths.containsKey(url)) {
            cachedPaths[url] = CacheEntry(localPath)
            Log.d(TAG, "📷 Updated cache: $url")
            return
        }

        // Evict oldest entries if at capacity
        while (cachedPaths.size >= MAX_CACHED_PHOTOS) {
            evictOldest()
        }

        cachedPaths[url] = CacheEntry(localPath)
        Log.d(TAG, "📷 Added to cache: $url (${cachedPaths.size}/$MAX_CACHED_PHOTOS)")
    }

    /**
     * Evict the least recently used cache entry.
     * Deletes both the in-memory mapping and the disk file.
     */
    private fun evictOldest() {
        val oldest = cachedPaths.entries.minByOrNull { it.value.lastAccessTime }
        if (oldest != null) {
            // Delete the file from disk
            val file = File(oldest.value.localPath)
            if (file.exists()) {
                file.delete()
                Log.d(TAG, "📷 Evicted from cache: ${oldest.key} (deleted ${file.name})")
            }
            cachedPaths.remove(oldest.key)
        }
    }

    /**
     * Check if a URL is in the cache.
     * Note: This does NOT update last access time (use getCachedPath for that).
     */
    fun isCached(url: String): Boolean {
        // Check in-memory first (quick)
        val entry = cachedPaths[url]
        if (entry != null) {
            val file = File(entry.localPath)
            if (file.exists() && file.length() > 0) {
                return true
            }
        }
        // Check disk (slower but handles app restart)
        val expectedFile = getCacheFile(url)
        return expectedFile != null && expectedFile.exists() && expectedFile.length() > 0
    }

    /**
     * Get a cache file for a given URL.
     * Uses a hash of the URL to create a unique, stable filename.
     */
    fun getCacheFile(url: String): File? {
        val hash = MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)  // Use first 16 chars of MD5 hash
        return cacheDir?.let { File(it, "photo_$hash.jpg") }
    }

    /**
     * @deprecated Use getCacheFile(url: String) instead for stable filenames.
     * This index-based version can cause overwrites when slideshow loops.
     */
    @Deprecated("Use getCacheFile(url) for stable filenames", ReplaceWith("getCacheFile(url)"))
    fun getCacheFile(index: Int): File? {
        Log.w(TAG, "⚠️ Using deprecated index-based getCacheFile($index)")
        return cacheDir?.let { File(it, "photo_$index.jpg") }
    }

    /**
     * Clear all cached photos (both memory and disk).
     */
    fun clear() {
        cacheDir?.listFiles()?.filter { it.name != ".cache_version" }?.forEach { it.delete() }
        cachedPaths.clear()
        Log.i(TAG, "📷 Photo cache cleared")
    }

    /**
     * Clean up orphaned files on disk that exceed MAX_CACHED_PHOTOS.
     * Keeps the most recently modified files.
     */
    private fun cleanupOrphanedFiles() {
        val files = cacheDir?.listFiles()?.filter {
            it.isFile && it.name != ".cache_version"
        } ?: return

        if (files.size <= MAX_CACHED_PHOTOS) return

        // Sort by last modified (oldest first) and delete excess
        val sortedFiles = files.sortedBy { it.lastModified() }
        val toDelete = sortedFiles.take(files.size - MAX_CACHED_PHOTOS)

        toDelete.forEach { file ->
            file.delete()
            Log.d(TAG, "📷 Cleaned up orphaned cache file: ${file.name}")
        }

        Log.i(TAG, "📷 Cleaned up ${toDelete.size} orphaned cache files (keeping $MAX_CACHED_PHOTOS)")
    }

    /**
     * Get cache size in bytes.
     */
    fun getCacheSize(): Long {
        return cacheDir?.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Get number of cached photos.
     */
    fun getCachedCount(): Int = cachedPaths.size

    /**
     * Get a status string for memory diagnostics.
     */
    fun getMemoryStatus(): String {
        val count = cachedPaths.size
        val sizeBytes = getCacheSize()
        val sizeMb = sizeBytes / (1024 * 1024)
        return "files=$count/${MAX_CACHED_PHOTOS} size=${sizeMb}MB"
    }
}
