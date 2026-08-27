package com.dashieapp.Dashie.halite.videofeed

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import java.util.concurrent.TimeUnit

/**
 * Loads a Frigate event thumbnail into an ImageView off the UI thread.
 * Extracted from FrigatePlaybackOverlay to keep that file within budget.
 */
object FrigateClipThumbnail {
    private const val TAG = "FrigateClipThumb"

    fun load(context: Context, event: FrigateEvent, imageView: ImageView) {
        Thread {
            try {
                val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(context)
                val credentials = com.dashieapp.Dashie.halite.HaTokenExtractor
                    .getValidCredentialsSync(halitePrefs) ?: return@Thread
                val (baseUrl, token) = credentials
                val url = "${baseUrl.trimEnd('/')}${event.thumbnailUrl}"

                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes() ?: return@use
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            Handler(Looper.getMainLooper()).post {
                                imageView.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Thumbnail load error: ${e.message}")
            }
        }.start()
    }
}
