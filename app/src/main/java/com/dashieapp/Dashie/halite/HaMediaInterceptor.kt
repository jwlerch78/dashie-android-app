package com.dashieapp.Dashie.halite

import android.util.Log
import android.webkit.WebResourceResponse
import com.dashieapp.Dashie.halite.screensaver.PhotoCache
import com.dashieapp.Dashie.halite.screensaver.ImageProcessor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/**
 * Intercepts HA API requests from the WebView to bypass CORS and add auth.
 *
 * Two interception modes:
 * 1. **Virtual URL proxy** — JS fetches from `http://dashie-ha-api.local/api/...`,
 *    this class rewrites to the real HA URL and adds the auth token. Works for any
 *    HA API endpoint (media list, media images, entities, services, etc.).
 * 2. **Direct URL proxy** — Intercepts requests to the actual HA media image URL
 *    (used by the screensaver photo display).
 *
 * Also handles:
 * - Serving from PhotoCache if available (for screensaver instant display)
 * - HEIC to JPEG conversion (WebView can't render HEIC natively)
 * - EXIF orientation correction (WebView doesn't reliably support image-orientation CSS)
 *
 * Extracted from DashieWebViewClient.
 */
class HaMediaInterceptor(
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "HaMediaInterceptor"
        /** Virtual host for HA API proxy — JS fetches from this, Kotlin proxies to real HA */
        const val VIRTUAL_HOST = "dashie-ha-api.local"
    }

    // HTTP client for proxying HA media requests (bypasses CORS).
    // Accepts self-signed certs for LAN HA URLs.
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun interceptHaMediaRequest(url: String): WebResourceResponse? {
        // Check PhotoCache first (for screensaver photos)
        val cachedPath = PhotoCache.getCachedPath(url)
        if (cachedPath != null) {
            Log.i(TAG, "📷 Serving from PhotoCache: $url -> $cachedPath")
            return try {
                val file = File(cachedPath)
                if (file.exists() && file.length() > 0) {
                    WebResourceResponse(
                        "image/jpeg",
                        null,
                        FileInputStream(file)
                    )
                } else {
                    Log.w(TAG, "📷 Cached file missing or empty: $cachedPath")
                    null  // Fall through to network fetch
                }
            } catch (e: Exception) {
                Log.e(TAG, "📷 Error serving from cache: ${e.message}")
                null  // Fall through to network fetch
            }
        }

        var token = halitePrefs.connection.haAccessToken
        if (token.isEmpty()) {
            Log.w(TAG, "📷 No HA token for media request: $url")
            return null  // Let WebView try direct (will fail with CORS)
        }

        // Pre-refresh expired token BEFORE making the request to avoid 401 → HA IP ban warning
        if (halitePrefs.connection.isHaTokenExpired) {
            Log.d(TAG, "📷 Token expired, refreshing before media request...")
            val refreshResult = HaTokenExtractor.refreshTokenSync(halitePrefs)
            if (refreshResult.success && refreshResult.accessToken.isNotEmpty()) {
                token = refreshResult.accessToken
                Log.i(TAG, "📷 Token pre-refreshed successfully")
            } else {
                Log.w(TAG, "📷 Token expired and refresh failed, skipping media request")
                return null
            }
        }

        Log.i(TAG, "📷 Intercepting HA media request (network): $url")

        return try {
            var request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            var response = httpClient.newCall(request).execute()

            // Handle 401 by refreshing token and retrying once (fallback if pre-refresh missed it)
            if (response.code == 401) {
                Log.w(TAG, "📷 HA media fetch got 401, attempting token refresh...")
                response.close()

                val refreshResult = HaTokenExtractor.refreshTokenSync(halitePrefs)
                if (refreshResult.success) {
                    Log.i(TAG, "📷 Token refreshed, retrying media request")
                    token = refreshResult.accessToken
                    request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer $token")
                        .build()
                    response = httpClient.newCall(request).execute()
                } else {
                    Log.e(TAG, "📷 Token refresh failed, cannot load media")
                    return null
                }
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "📷 HA media fetch failed: ${response.code}")
                response.close()
                return null
            }

            // Get content type from response (e.g., image/jpeg, image/heic)
            var contentType = response.header("Content-Type") ?: "image/jpeg"
            var body = response.body?.bytes()

            if (body == null) {
                Log.w(TAG, "📷 HA media response empty")
                response.close()
                return null
            }

            // Check if HEIC (needs conversion)
            val isHeic = contentType.contains("heic", ignoreCase = true) ||
                         url.lowercase().endsWith(".heic")

            // Process image using shared ImageProcessor:
            // - Downsize to screen resolution (1920px max) for memory efficiency
            // - Apply EXIF rotation
            // - Convert HEIC to JPEG
            body = ImageProcessor.processForWebView(body, isHeic)
            contentType = "image/jpeg"

            Log.i(TAG, "📷 Proxied HA media: ${body.size} bytes, type=$contentType")

            // Return the image data directly to WebView
            WebResourceResponse(
                contentType,
                null,  // encoding (binary, no charset needed)
                ByteArrayInputStream(body)
            )
        } catch (e: Exception) {
            Log.e(TAG, "📷 HA media proxy error: ${e.message}")
            null
        }
    }

    /**
     * Check if a URL is a virtual HA API proxy request.
     * JS fetches from http://dashie-ha-api.local/api/... to avoid CORS.
     */
    fun isVirtualHaApiRequest(url: String): Boolean {
        return url.contains(VIRTUAL_HOST)
    }

    /**
     * Proxy a virtual HA API request to the real HA server.
     * Rewrites http://dashie-ha-api.local/api/... → {haUrl}/api/...
     * Adds Authorization header with HA access token.
     *
     * Returns JSON or image data depending on the endpoint.
     */
    fun interceptVirtualHaApiRequest(url: String): WebResourceResponse? {
        // Use getHaOrigin() for just the base URL (no dashboard path like /ha-dashie)
        val haUrl = halitePrefs.connection.getHaOrigin()?.trimEnd('/')
        if (haUrl.isNullOrEmpty()) {
            Log.w(TAG, "🌐 No HA URL configured for proxy request: $url")
            return errorResponse(503, "Home Assistant not configured")
        }

        var token = halitePrefs.connection.haAccessToken
        if (token.isEmpty()) {
            Log.w(TAG, "🌐 No HA token for proxy request: $url")
            return errorResponse(401, "No Home Assistant auth token")
        }

        // Rewrite virtual URL to real HA URL
        // http://dashie-ha-api.local/api/dashie/media?... → {haUrl}/api/dashie/media?...
        val path = url.substringAfter(VIRTUAL_HOST).let {
            if (it.startsWith(":")) it.substringAfter("/") else it.trimStart('/')
        }
        val realUrl = "$haUrl/$path"

        Log.i(TAG, "🌐 Proxying HA API: $url → $realUrl")

        return try {
            var request = Request.Builder()
                .url(realUrl)
                .header("Authorization", "Bearer $token")
                .build()

            var response = httpClient.newCall(request).execute()

            // Handle 401 by refreshing token and retrying once
            if (response.code == 401) {
                Log.w(TAG, "🌐 HA API proxy got 401, refreshing token...")
                response.close()
                val refreshResult = HaTokenExtractor.refreshTokenSync(halitePrefs)
                if (refreshResult.success) {
                    token = refreshResult.accessToken
                    request = Request.Builder()
                        .url(realUrl)
                        .header("Authorization", "Bearer $token")
                        .build()
                    response = httpClient.newCall(request).execute()
                } else {
                    return errorResponse(401, "Token refresh failed")
                }
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "🌐 HA API proxy failed: ${response.code}")
                val errorBody = response.body?.string() ?: "HA returned ${response.code}"
                response.close()
                return errorResponse(response.code, errorBody)
            }

            val contentType = response.header("Content-Type") ?: "application/json"
            val body = response.body?.bytes() ?: return errorResponse(502, "Empty response from HA")

            // If it's an image, process it (HEIC conversion, EXIF rotation)
            if (contentType.startsWith("image/")) {
                val isHeic = contentType.contains("heic", ignoreCase = true) ||
                             realUrl.lowercase().endsWith(".heic")
                val processed = ImageProcessor.processForWebView(body, isHeic)
                Log.i(TAG, "🌐 Proxied HA image: ${processed.size} bytes")
                return WebResourceResponse(
                    "image/jpeg", null, ByteArrayInputStream(processed)
                ).apply {
                    responseHeaders = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "private, max-age=300"
                    )
                }
            }

            // JSON or other responses — pass through with CORS headers
            Log.i(TAG, "🌐 Proxied HA API: ${body.size} bytes, type=$contentType")
            WebResourceResponse(
                contentType,
                "UTF-8",
                ByteArrayInputStream(body)
            ).apply {
                responseHeaders = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Headers" to "authorization, content-type",
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "🌐 HA API proxy error: ${e.message}")
            errorResponse(502, "Proxy error: ${e.message}")
        }
    }

    private fun errorResponse(code: Int, message: String): WebResourceResponse {
        val json = """{"error": "$message"}"""
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            ByteArrayInputStream(json.toByteArray())
        ).apply {
            setStatusCodeAndReasonPhrase(code, message.take(50))
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }
}
