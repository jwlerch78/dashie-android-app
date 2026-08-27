package com.dashieapp.Dashie.halite.screensaver

import android.util.Log
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Shared utility for extracting metadata (date taken, location) from photos.
 * Used by both native PhotoSlideshowView and HTML screensaver.
 *
 * Features:
 * - EXIF date extraction
 * - GPS coordinate extraction (coordinates only — no off-device place-name lookup)
 * - Supports both file paths and URLs with authentication
 * - US state/Canadian province abbreviation
 */
object PhotoMetadataExtractor {
    private const val TAG = "PhotoMetadataExtractor"

    // HTTP client for fetching remote photos. Photos may be served from the HA Media API on a
    // LAN host with a self-signed cert. (No longer used for geocoding — that was removed.)
    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Extracted photo metadata
     */
    data class PhotoMetadata(
        val dateTaken: Date?,
        val dateFormatted: String?,
        val location: String?,
        val latitude: Double?,
        val longitude: Double?
    )

    /**
     * Extract metadata from a remote photo URL.
     * Downloads the image, extracts EXIF, and reverse geocodes location.
     *
     * @param url Photo URL
     * @param authToken Optional Bearer token for authentication
     * @return PhotoMetadata or null if extraction fails
     */
    suspend fun extractFromUrl(url: String, authToken: String?): PhotoMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                // Fetch image
                val requestBuilder = Request.Builder().url(url)
                if (!authToken.isNullOrEmpty()) {
                    requestBuilder.header("Authorization", "Bearer $authToken")
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to fetch image: ${response.code}")
                    return@withContext null
                }

                val imageBytes = response.body?.bytes()
                if (imageBytes == null || imageBytes.isEmpty()) {
                    Log.w(TAG, "Empty image response")
                    return@withContext null
                }

                extractFromBytes(imageBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract metadata from URL: ${e.message}")
                null
            }
        }
    }

    /**
     * Extract metadata from image bytes. Coordinates are parsed locally and never resolved
     * off-device — see the REVERSE GEOCODING REMOVED note below.
     */
    fun extractFromBytes(imageBytes: ByteArray): PhotoMetadata? {
        return try {
            val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(imageBytes))

            // Extract date
            val exifDir = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val dateTaken = exifDir?.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            val dateFormatted = dateTaken?.let {
                SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it)
            }

            // Extract GPS
            val gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
            val geoLocation = gpsDir?.geoLocation
            val lat = if (geoLocation != null && !geoLocation.isZero) geoLocation.latitude else null
            val lon = if (geoLocation != null && !geoLocation.isZero) geoLocation.longitude else null

            // No location resolution: coordinates are parsed and returned, never resolved to a
            // place name. See the REVERSE GEOCODING REMOVED note below before changing this.
            val locationStr: String? = null

            PhotoMetadata(
                dateTaken = dateTaken,
                dateFormatted = dateFormatted,
                location = locationStr,
                latitude = lat,
                longitude = lon
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF: ${e.message}")
            null
        }
    }

    /* ── REVERSE GEOCODING REMOVED, product decision 2026-08-21 ───────────────────────────────
     * This class used to POST the photo's EXIF lat/lon to nominatim.openstreetmap.org to turn
     * them into a town name for the caption. That is the entire OpenStreetMap egress on this
     * path, and the decision was to remove it rather than gate it: "we can just remove geocoding
     * from that source."
     *
     * 🔴 DO NOT RE-ADD. Location captions now come only from metadata a photo source already
     * supplies (Immich/Drive/cloud resolve location server-side). If a caption is blank, the
     * source did not provide a location — that is the intended behaviour, not a bug to fix by
     * reinstating a lookup. The disclosure position now reads "location captions come from your
     * photo library's own metadata; nothing is sent anywhere", and re-adding a geocoder would
     * silently make that false.
     *
     * The EXIF read is deliberately KEPT: lat/lon are still parsed and returned on PhotoMetadata
     * so callers can use them locally; they simply never leave the device.
     * Removed with it: the geocodeCache and the Android/Nominatim provider pair.
     */

    /**
     * Abbreviate state/province name to standard two-letter code.
     */
    fun abbreviateState(state: String): String {
        return US_STATE_ABBREVIATIONS[state]
            ?: CA_PROVINCE_ABBREVIATIONS[state]
            ?: state
    }

    // US state abbreviations
    private val US_STATE_ABBREVIATIONS = mapOf(
        "Alabama" to "AL", "Alaska" to "AK", "Arizona" to "AZ", "Arkansas" to "AR",
        "California" to "CA", "Colorado" to "CO", "Connecticut" to "CT", "Delaware" to "DE",
        "Florida" to "FL", "Georgia" to "GA", "Hawaii" to "HI", "Idaho" to "ID",
        "Illinois" to "IL", "Indiana" to "IN", "Iowa" to "IA", "Kansas" to "KS",
        "Kentucky" to "KY", "Louisiana" to "LA", "Maine" to "ME", "Maryland" to "MD",
        "Massachusetts" to "MA", "Michigan" to "MI", "Minnesota" to "MN", "Mississippi" to "MS",
        "Missouri" to "MO", "Montana" to "MT", "Nebraska" to "NE", "Nevada" to "NV",
        "New Hampshire" to "NH", "New Jersey" to "NJ", "New Mexico" to "NM", "New York" to "NY",
        "North Carolina" to "NC", "North Dakota" to "ND", "Ohio" to "OH", "Oklahoma" to "OK",
        "Oregon" to "OR", "Pennsylvania" to "PA", "Rhode Island" to "RI", "South Carolina" to "SC",
        "South Dakota" to "SD", "Tennessee" to "TN", "Texas" to "TX", "Utah" to "UT",
        "Vermont" to "VT", "Virginia" to "VA", "Washington" to "WA", "West Virginia" to "WV",
        "Wisconsin" to "WI", "Wyoming" to "WY", "District of Columbia" to "DC"
    )

    // Canadian province abbreviations
    private val CA_PROVINCE_ABBREVIATIONS = mapOf(
        "Alberta" to "AB", "British Columbia" to "BC", "Manitoba" to "MB",
        "New Brunswick" to "NB", "Newfoundland and Labrador" to "NL",
        "Northwest Territories" to "NT", "Nova Scotia" to "NS", "Nunavut" to "NU",
        "Ontario" to "ON", "Prince Edward Island" to "PE", "Quebec" to "QC",
        "Saskatchewan" to "SK", "Yukon" to "YT"
    )
}
