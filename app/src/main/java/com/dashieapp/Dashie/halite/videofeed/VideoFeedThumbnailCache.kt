package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Disk-backed cache of the most recently known frame for each camera entity,
 * used to paint a placeholder on a VideoFeedCard before the live stream
 * attaches. Kills the "black box for 1-3 seconds" first impression.
 *
 * Flow on a card mount:
 *   1. Synchronously load from disk (instant if we've seen this camera before).
 *   2. Asynchronously fetch a fresh snapshot from the HA Dashie integration's
 *      /api/dashie/stream/snapshot/{entity_id} endpoint, update disk cache.
 *
 * The snapshot endpoint itself has a fast path (~5ms) when a stream is
 * active or recently ran, and a slow path (~500-1500ms) when it has to
 * spawn ffmpeg from cold. Either way, the Android-side disk cache means
 * every visit after the first one is instant.
 *
 * File naming: entity ids contain dots (camera.foo) so we swap them for
 * underscores in the filename. Stored as raw JPEG bytes; we decode to
 * Bitmap at load time.
 */
internal object VideoFeedThumbnailCache {

    private const val TAG = "VideoFeedThumbnail"
    private const val CACHE_DIR = "video_thumbnails"
    // Longest we trust a disk-cached thumbnail as "recent enough" to paint
    // without any network-side refresh. Older entries still render (better
    // than black), but we'll schedule a refresh in the background.
    private const val DISK_MAX_AGE_MS = 24 * 60 * 60 * 1000L  // 24h

    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Single-threaded executor so we don't launch concurrent fetches for the
    // same camera when a card mounts, gets detached, and mounts again quickly.
    private val fetchExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "VideoFeedThumbnailFetch").apply { isDaemon = true }
    }

    private fun cacheDir(context: Context): File {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(context: Context, entityId: String): File {
        val safeName = entityId.replace('.', '_').replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(cacheDir(context), "$safeName.jpg")
    }

    /** Synchronous disk read. Returns a decoded Bitmap or null. */
    fun loadFromDisk(context: Context, entityId: String): Bitmap? {
        if (entityId.isBlank()) return null
        val file = fileFor(context, entityId)
        if (!file.exists() || file.length() == 0L) return null
        val ageMs = System.currentTimeMillis() - file.lastModified()
        val stale = ageMs > DISK_MAX_AGE_MS
        return try {
            val bytes = file.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                Log.d(TAG, "Disk hit for $entityId (${bytes.size}B, ageMs=$ageMs, stale=$stale)")
            }
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load disk thumbnail for $entityId", e)
            null
        }
    }

    /**
     * Fetch a fresh snapshot from HA, save to disk, invoke callback on success.
     * Never throws; failures are logged and the callback is not invoked.
     *
     * @param snapshotUrl Full URL to the snapshot endpoint (caller builds it
     *   from HA base URL + entity id).
     * @param token HA bearer token.
     * @param onBitmap Main-thread callback with the fresh Bitmap. Not called
     *   on failure — caller should keep showing the disk-cached image (if any).
     */
    fun fetchAsync(
        context: Context,
        entityId: String,
        snapshotUrl: String,
        token: String,
        onBitmap: (Bitmap) -> Unit,
    ) {
        if (entityId.isBlank() || snapshotUrl.isBlank() || token.isBlank()) return
        fetchExecutor.execute {
            val t0 = System.currentTimeMillis()
            val fresh = fetchBlocking(snapshotUrl, token)
            val elapsedMs = System.currentTimeMillis() - t0
            if (fresh == null) {
                Log.w(TAG, "📸 Snapshot fetch FAILED for $entityId after ${elapsedMs}ms (url=$snapshotUrl)")
                return@execute
            }
            try {
                fileFor(context, entityId).writeBytes(fresh)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write disk thumbnail for $entityId", e)
            }
            val bmp = BitmapFactory.decodeByteArray(fresh, 0, fresh.size)
            if (bmp != null) {
                Log.i(TAG, "📸 Fetched fresh snapshot for $entityId (${fresh.size}B, ${elapsedMs}ms)")
                onBitmap(bmp)
            } else {
                Log.w(TAG, "📸 Snapshot decode FAILED for $entityId (${fresh.size}B, ${elapsedMs}ms)")
            }
        }
    }

    private fun fetchBlocking(url: String, token: String): ByteArray? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "📸 Snapshot HTTP ${resp.code} from $url")
                    return null
                }
                val src = resp.header("X-Dashie-Snapshot-Source") ?: "?"
                val age = resp.header("X-Dashie-Snapshot-Age-Ms") ?: "?"
                Log.d(TAG, "📸 Snapshot response: source=$src ageMs=$age")
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "📸 Snapshot fetch exception: ${e.message}")
            null
        }
    }
}
