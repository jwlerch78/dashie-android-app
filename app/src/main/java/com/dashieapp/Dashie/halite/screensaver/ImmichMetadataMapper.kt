package com.dashieapp.Dashie.halite.screensaver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Maps Immich API asset metadata to Dashie's PhotoMetadata and PhotoItem.metadata map.
 * Avoids downloading full-res originals just for EXIF — Immich's API already provides
 * date, GPS, camera info, and reverse-geocoded location.
 */
object ImmichMetadataMapper {

    private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val displayFormatter = SimpleDateFormat("MMM d, yyyy", Locale.US)

    /**
     * Convert Immich asset to PhotoMetadata (used by slideshow metadata ribbon).
     */
    fun toPhotoMetadata(asset: ImmichAsset): PhotoMetadataExtractor.PhotoMetadata? {
        val exif = asset.exifInfo

        val dateTaken = parseDate(exif?.dateTimeOriginal ?: asset.localDateTime)
        val dateFormatted = dateTaken?.let { displayFormatter.format(it) }
        val location = exif?.let { buildLocationString(it.city, it.state, it.country) }

        // Return null only if we have no usable metadata at all
        if (dateTaken == null && location == null) return null

        return PhotoMetadataExtractor.PhotoMetadata(
            dateTaken = dateTaken,
            dateFormatted = dateFormatted,
            location = location,
            latitude = exif?.latitude,
            longitude = exif?.longitude
        )
    }

    /**
     * Convert Immich asset to metadata map (stored in PhotoItem.metadata).
     */
    fun toMetadataMap(asset: ImmichAsset): Map<String, Any> {
        val map = mutableMapOf<String, Any>()

        // Always store the Immich asset ID for future search/AI integration
        map["immichAssetId"] = asset.id

        val exif = asset.exifInfo
        if (exif != null) {
            val dateTaken = parseDate(exif.dateTimeOriginal ?: asset.localDateTime)
            dateTaken?.let {
                map["dateTaken"] = it.time
                map["dateFormatted"] = displayFormatter.format(it)
            }

            val location = buildLocationString(exif.city, exif.state, exif.country)
            location?.let { map["location"] = it }

            exif.latitude?.let { map["latitude"] = it }
            exif.longitude?.let { map["longitude"] = it }
            exif.make?.let { map["cameraMake"] = it }
            exif.model?.let { map["cameraModel"] = it }
        } else {
            // Fall back to localDateTime if no EXIF
            val dateTaken = parseDate(asset.localDateTime)
            dateTaken?.let {
                map["dateTaken"] = it.time
                map["dateFormatted"] = displayFormatter.format(it)
            }
        }

        return map
    }

    private fun parseDate(isoString: String?): Date? {
        if (isoString.isNullOrEmpty()) return null
        return try {
            // Strip fractional seconds and timezone suffix for simple parsing
            val cleaned = isoString
                .substringBefore("+")
                .substringBefore("Z")
                .substringBefore(".")
            isoParser.parse(cleaned)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildLocationString(city: String?, state: String?, country: String?): String? {
        val parts = listOfNotNull(city, state).ifEmpty {
            listOfNotNull(country)
        }
        return parts.joinToString(", ").ifEmpty { null }
    }
}
