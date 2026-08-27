package com.dashieapp.Dashie.halite.screensaver

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Immich API.
 * Handles authentication, login, album listing, asset fetching, and thumbnail downloads.
 */
class ImmichApiClient(
    private var serverUrl: String,
    private var accessToken: String
) {
    companion object {
        private const val TAG = "ImmichApiClient"
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val READ_TIMEOUT_MS = 30000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }

    // Immich is typically self-hosted (often on the LAN with a self-signed
    // cert), so accept LAN certs. Public Immich deployments fall back to
    // strict validation automatically.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    fun updateCredentials(serverUrl: String, accessToken: String) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.accessToken = accessToken
    }

    /**
     * Login with email/password and return the access token.
     * POST /api/auth/login
     */
    suspend fun login(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("email", email)
                put("password", password)
            }
            val request = Request.Builder()
                .url("$serverUrl/api/auth/login")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val error = try {
                    JSONObject(responseBody).optString("message", "Login failed (${response.code})")
                } catch (_: Exception) {
                    "Login failed (${response.code})"
                }
                Log.w(TAG, "Login failed: $error")
                return@withContext LoginResult(success = false, error = error)
            }

            val json = JSONObject(responseBody)
            val token = json.optString("accessToken", "")
            if (token.isEmpty()) {
                return@withContext LoginResult(success = false, error = "No access token in response")
            }

            accessToken = token
            Log.i(TAG, "Immich login successful")
            LoginResult(success = true, accessToken = token)
        } catch (e: Exception) {
            Log.e(TAG, "Login error", e)
            LoginResult(success = false, error = "Connection failed: ${e.message}")
        }
    }

    /**
     * Validate the current access token.
     * POST /api/auth/validateToken
     */
    suspend fun validateToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthRequest("$serverUrl/api/auth/validateToken")
                .post("".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "Token validation failed: ${e.message}")
            false
        }
    }

    /**
     * Get all albums accessible to the authenticated user.
     * Fetches both owned and shared albums separately because the Immich API
     * only returns owned albums when the shared parameter is omitted.
     * See: home-assistant/core#159653
     */
    suspend fun getAlbums(): List<ImmichAlbum> = withContext(Dispatchers.IO) {
        try {
            val albumsById = mutableMapOf<String, ImmichAlbum>()

            // Fetch owned albums (shared=false) and shared albums (shared=true) separately
            for (shared in listOf(false, true)) {
                val request = buildAuthRequest("$serverUrl/api/albums?shared=$shared").build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch albums (shared=$shared): ${response.code}")
                    continue
                }

                val body = response.body?.string() ?: "[]"
                val array = JSONArray(body)

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val id = obj.getString("id")
                    if (id !in albumsById) {
                        albumsById[id] = ImmichAlbum(
                            id = id,
                            albumName = obj.getString("albumName"),
                            assetCount = obj.optInt("assetCount", 0),
                            thumbnailAssetId = obj.optString("albumThumbnailAssetId", "")
                                .ifEmpty { null }
                        )
                    }
                }
            }

            val albums = albumsById.values.toList()
            Log.i(TAG, "Fetched ${albums.size} albums from Immich (owned + shared)")
            albums
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching albums", e)
            emptyList()
        }
    }

    /**
     * Get assets for a specific album.
     * GET /api/albums/{id}
     */
    suspend fun getAlbumAssets(albumId: String): List<ImmichAsset> = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthRequest("$serverUrl/api/albums/$albumId").build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch album assets: ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            val assetsArray = json.optJSONArray("assets") ?: return@withContext emptyList()

            parseAssets(assetsArray)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching album assets", e)
            emptyList()
        }
    }

    /**
     * Get random assets (for "all photos" mode).
     * POST /api/search/random
     */
    suspend fun getRandomAssets(count: Int = 50): List<ImmichAsset> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("size", count)
                put("type", "IMAGE")
            }
            val request = buildAuthRequest("$serverUrl/api/search/random")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch random assets: ${response.code}")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: "[]"
            val array = JSONArray(responseBody)
            parseAssets(array)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching random assets", e)
            emptyList()
        }
    }

    /**
     * Get full metadata for a single asset.
     * GET /api/assets/{id}
     * Used when the list endpoint doesn't include exifInfo.
     */
    suspend fun getAssetDetails(assetId: String): ImmichAsset? = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthRequest("$serverUrl/api/assets/$assetId").build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch asset details for $assetId: ${response.code}")
                return@withContext null
            }
            val body = response.body?.string() ?: "{}"
            val obj = JSONObject(body)
            val array = JSONArray().put(obj)
            parseAssets(array).firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching asset details for $assetId", e)
            null
        }
    }

    /**
     * Download a preview-size thumbnail for an asset.
     * GET /api/assets/{id}/thumbnail?size=preview
     */
    suspend fun downloadThumbnail(assetId: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthRequest(
                "$serverUrl/api/assets/$assetId/thumbnail?size=preview"
            ).build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to download thumbnail for $assetId: ${response.code}")
                return@withContext null
            }

            response.body?.bytes()
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading thumbnail for $assetId", e)
            null
        }
    }

    /**
     * Get total image count from asset statistics.
     * GET /api/assets/statistics
     */
    suspend fun getAssetStatistics(): Int = withContext(Dispatchers.IO) {
        try {
            val request = buildAuthRequest("$serverUrl/api/assets/statistics").build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Failed to fetch asset statistics: ${response.code}")
                return@withContext -1
            }
            val body = response.body?.string() ?: "{}"
            val json = JSONObject(body)
            val images = json.optInt("images", -1)
            Log.d(TAG, "Asset statistics: images=$images")
            images
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching asset statistics: ${e.message}")
            -1
        }
    }

    private fun buildAuthRequest(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
    }

    private fun parseAssets(array: JSONArray): List<ImmichAsset> {
        val assets = mutableListOf<ImmichAsset>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val type = obj.optString("type", "IMAGE")
            if (type != "IMAGE") continue

            val exifObj = obj.optJSONObject("exifInfo")
            val exifInfo = exifObj?.let {
                ImmichExifInfo(
                    dateTimeOriginal = it.optString("dateTimeOriginal", "").takeIf { v -> v.isNotEmpty() && v != "null" },
                    latitude = if (it.has("latitude") && !it.isNull("latitude")) it.optDouble("latitude") else null,
                    longitude = if (it.has("longitude") && !it.isNull("longitude")) it.optDouble("longitude") else null,
                    city = it.optString("city", "").takeIf { v -> v.isNotEmpty() && v != "null" },
                    state = it.optString("state", "").takeIf { v -> v.isNotEmpty() && v != "null" },
                    country = it.optString("country", "").takeIf { v -> v.isNotEmpty() && v != "null" },
                    make = it.optString("make", "").takeIf { v -> v.isNotEmpty() && v != "null" },
                    model = it.optString("model", "").takeIf { v -> v.isNotEmpty() && v != "null" },
                    exifImageWidth = if (it.has("exifImageWidth") && !it.isNull("exifImageWidth")) it.optInt("exifImageWidth") else null,
                    exifImageHeight = if (it.has("exifImageHeight") && !it.isNull("exifImageHeight")) it.optInt("exifImageHeight") else null
                )
            }

            assets.add(ImmichAsset(
                id = obj.getString("id"),
                originalFileName = obj.optString("originalFileName", "unknown.jpg"),
                type = type,
                thumbhash = obj.optString("thumbhash", "").ifEmpty { null },
                originalMimeType = obj.optString("originalMimeType", "image/jpeg"),
                exifInfo = exifInfo,
                localDateTime = obj.optString("localDateTime", "").ifEmpty { null }
            ))
        }
        Log.d(TAG, "Parsed ${assets.size} image assets")
        return assets
    }

    // ── Data Classes ────────────────────────────────────────────────

    data class LoginResult(
        val success: Boolean,
        val accessToken: String? = null,
        val error: String? = null
    )
}

data class ImmichAlbum(
    val id: String,
    val albumName: String,
    val assetCount: Int,
    val thumbnailAssetId: String?
)

data class ImmichAsset(
    val id: String,
    val originalFileName: String,
    val type: String,
    val thumbhash: String?,
    val originalMimeType: String,
    val exifInfo: ImmichExifInfo?,
    val localDateTime: String?
)

data class ImmichExifInfo(
    val dateTimeOriginal: String?,
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val state: String?,
    val country: String?,
    val make: String?,
    val model: String?,
    val exifImageWidth: Int? = null,
    val exifImageHeight: Int? = null
)
