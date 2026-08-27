package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log
import android.media.MediaCodecInfo.CodecCapabilities
import android.util.Size
import kotlin.math.abs

/**
 * Utility to query MediaCodec capabilities for video encoding.
 * Helps diagnose what resolutions, profiles, and formats the device actually supports.
 *
 * Usage:
 * ```
 * val caps = MediaCodecCapabilities.getH264EncoderCapabilities()
 * caps?.let {
 *     DiagnosticBuffer.info("CODEC", "Supported resolutions: ${it.supportedResolutions}")
 *     DiagnosticBuffer.info("CODEC", "Supported profiles: ${it.supportedProfiles}")
 * }
 * ```
 */
object MediaCodecCapabilities {
    private const val TAG = "MediaCodecCaps"
    private const val MIME_H264 = "video/avc"

    /**
     * H.264 profile constants for readability
     */
    object H264Profile {
        const val BASELINE = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        const val MAIN = MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        const val HIGH = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        const val CONSTRAINED_BASELINE = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
        const val CONSTRAINED_HIGH = MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedHigh

        fun nameOf(profile: Int): String = when (profile) {
            BASELINE -> "Baseline"
            MAIN -> "Main"
            HIGH -> "High"
            CONSTRAINED_BASELINE -> "Constrained Baseline"
            CONSTRAINED_HIGH -> "Constrained High"
            MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High10"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High422"
            MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High444"
            else -> "Unknown($profile)"
        }
    }

    /**
     * Color format constants for readability.
     * These determine how YUV data is laid out in memory.
     */
    object ColorFormat {
        // Common YUV formats used by hardware encoders
        const val YUV420_PLANAR = CodecCapabilities.COLOR_FormatYUV420Planar  // I420: Y, U, V planes
        const val YUV420_SEMIPLANAR = CodecCapabilities.COLOR_FormatYUV420SemiPlanar  // NV12: Y plane, UV interleaved
        const val YUV420_FLEXIBLE = CodecCapabilities.COLOR_FormatYUV420Flexible  // Generic YUV420

        // Surface-based input (what we use with Camera2)
        const val SURFACE = CodecCapabilities.COLOR_FormatSurface

        fun nameOf(format: Int): String = when (format) {
            YUV420_PLANAR -> "I420 (YUV420 Planar)"
            YUV420_SEMIPLANAR -> "NV12 (YUV420 SemiPlanar)"
            YUV420_FLEXIBLE -> "YUV420 Flexible"
            SURFACE -> "Surface"
            CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> "YUV420 Packed Planar"
            CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> "NV21 (YUV420 Packed SemiPlanar)"
            else -> "Unknown($format)"
        }

        /**
         * Check if a color format is a standard YUV420 format
         */
        fun isYuv420(format: Int): Boolean = when (format) {
            YUV420_PLANAR, YUV420_SEMIPLANAR, YUV420_FLEXIBLE,
            CodecCapabilities.COLOR_FormatYUV420PackedPlanar,
            CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> true
            else -> false
        }
    }

    /**
     * H.264 level constants for readability
     */
    object H264Level {
        fun nameOf(level: Int): String = when (level) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel1 -> "1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel1b -> "1b"
            MediaCodecInfo.CodecProfileLevel.AVCLevel11 -> "1.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel12 -> "1.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel13 -> "1.3"
            MediaCodecInfo.CodecProfileLevel.AVCLevel2 -> "2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel21 -> "2.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel22 -> "2.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 -> "3"
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 -> "3.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel32 -> "3.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 -> "4"
            MediaCodecInfo.CodecProfileLevel.AVCLevel41 -> "4.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 -> "4.2"
            MediaCodecInfo.CodecProfileLevel.AVCLevel5 -> "5"
            MediaCodecInfo.CodecProfileLevel.AVCLevel51 -> "5.1"
            MediaCodecInfo.CodecProfileLevel.AVCLevel52 -> "5.2"
            else -> "Unknown($level)"
        }
    }

    /**
     * Capabilities data class
     */
    data class EncoderCapabilities(
        val encoderName: String,
        val isHardwareAccelerated: Boolean,
        val supportedProfiles: List<String>,
        val supportedProfileLevels: List<Pair<String, String>>,
        val widthRange: IntRange,
        val heightRange: IntRange,
        val supportedFrameRates: IntRange,
        val bitrateRange: IntRange,
        val supportedResolutions: List<String>,  // Common resolutions that are supported
        val supportedColorFormats: List<String>,  // Color formats the encoder accepts
        val supportsSurfaceInput: Boolean,  // Whether Surface-based input is supported (what we use)
        val widthAlignment: Int,  // Required width alignment (typically 2 or 16)
        val heightAlignment: Int  // Required height alignment (typically 2 or 16)
    ) {
        /**
         * Check if a specific resolution is supported
         */
        fun isResolutionSupported(width: Int, height: Int): Boolean {
            return width in widthRange && height in heightRange
        }

        /**
         * Check if a specific profile is supported
         */
        fun isProfileSupported(profileName: String): Boolean {
            return supportedProfiles.any { it.equals(profileName, ignoreCase = true) }
        }

        /**
         * Check if a resolution respects the encoder's alignment requirements
         */
        fun isResolutionAligned(width: Int, height: Int): Boolean {
            return (widthAlignment == 0 || width % widthAlignment == 0) &&
                   (heightAlignment == 0 || height % heightAlignment == 0)
        }

        /**
         * Get alignment warnings for a resolution
         */
        fun getAlignmentWarnings(width: Int, height: Int): List<String> {
            val warnings = mutableListOf<String>()
            if (widthAlignment > 0 && width % widthAlignment != 0) {
                warnings.add("Width $width not aligned to $widthAlignment (requires ${(width / widthAlignment) * widthAlignment} or ${((width / widthAlignment) + 1) * widthAlignment})")
            }
            if (heightAlignment > 0 && height % heightAlignment != 0) {
                warnings.add("Height $height not aligned to $heightAlignment (requires ${(height / heightAlignment) * heightAlignment} or ${((height / heightAlignment) + 1) * heightAlignment})")
            }
            return warnings
        }

        /**
         * Format as diagnostic text
         */
        fun toDiagnosticText(): String {
            val sb = StringBuilder()
            sb.appendLine("Encoder: $encoderName")
            sb.appendLine("Hardware: $isHardwareAccelerated")
            sb.appendLine("Resolution: ${widthRange.first}-${widthRange.last} x ${heightRange.first}-${heightRange.last}")
            sb.appendLine("Alignment: ${widthAlignment}x${heightAlignment}")
            sb.appendLine("Profiles: ${supportedProfiles.joinToString(", ")}")
            sb.appendLine("FPS range: ${supportedFrameRates.first}-${supportedFrameRates.last}")
            sb.appendLine("Bitrate: ${bitrateRange.first / 1000}k - ${bitrateRange.last / 1_000_000}M")
            sb.appendLine("Color formats: ${supportedColorFormats.joinToString(", ")}")
            sb.appendLine("Surface input: $supportsSurfaceInput")
            sb.appendLine("Common resolutions: ${supportedResolutions.joinToString(", ")}")
            return sb.toString()
        }
    }

    /**
     * Get H.264 encoder capabilities for this device
     */
    fun getH264EncoderCapabilities(): EncoderCapabilities? {
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

            // Find H.264 encoder
            val encoderInfo = codecList.codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.contains(MIME_H264)
            } ?: run {
                Log.w(TAG, "No H.264 encoder found")
                return null
            }

            val capabilities = encoderInfo.getCapabilitiesForType(MIME_H264)
            val videoCapabilities = capabilities.videoCapabilities

            // Get supported profiles
            val profileLevels = capabilities.profileLevels
            val profiles = profileLevels.map { H264Profile.nameOf(it.profile) }.distinct()
            val profileLevelPairs = profileLevels.map {
                H264Profile.nameOf(it.profile) to H264Level.nameOf(it.level)
            }

            // Get supported ranges
            val widthRange = videoCapabilities.supportedWidths
            val heightRange = videoCapabilities.supportedHeights
            val framerateRange = videoCapabilities.supportedFrameRates
            val bitrateRange = videoCapabilities.bitrateRange

            // Check common resolutions
            val commonResolutions = listOf(
                Size(640, 480) to "480p",
                Size(1280, 720) to "720p",
                Size(1920, 1080) to "1080p",
                Size(854, 480) to "854x480",
                Size(960, 540) to "540p",
                Size(1024, 768) to "XGA",
                Size(800, 600) to "SVGA"
            )

            val supported = commonResolutions.filter { (size, _) ->
                videoCapabilities.isSizeSupported(size.width, size.height)
            }.map { it.second }

            val isHardware = try {
                // API 29+ has isHardwareAccelerated
                encoderInfo.isHardwareAccelerated
            } catch (e: NoSuchMethodError) {
                // Fallback: assume hardware if name contains "OMX" or doesn't contain "sw"
                !encoderInfo.name.lowercase().contains("sw") &&
                (encoderInfo.name.contains("OMX") || encoderInfo.name.contains("c2."))
            }

            // Get supported color formats
            val colorFormats = capabilities.colorFormats
                .map { ColorFormat.nameOf(it) }
                .distinct()
            val supportsSurface = colorFormats.any { it.contains("Surface") }

            // Get alignment requirements (if available, API 21+)
            val widthAlignment = try {
                videoCapabilities.widthAlignment
            } catch (e: Exception) {
                2  // Default minimum
            }
            val heightAlignment = try {
                videoCapabilities.heightAlignment
            } catch (e: Exception) {
                2  // Default minimum
            }

            return EncoderCapabilities(
                encoderName = encoderInfo.name,
                isHardwareAccelerated = isHardware,
                supportedProfiles = profiles,
                supportedProfileLevels = profileLevelPairs,
                widthRange = widthRange.lower..widthRange.upper,
                heightRange = heightRange.lower..heightRange.upper,
                supportedFrameRates = framerateRange.lower.toInt()..framerateRange.upper.toInt(),
                bitrateRange = bitrateRange.lower..bitrateRange.upper,
                supportedResolutions = supported,
                supportedColorFormats = colorFormats,
                supportsSurfaceInput = supportsSurface,
                widthAlignment = widthAlignment,
                heightAlignment = heightAlignment
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get encoder capabilities: ${e.message}", e)
            return null
        }
    }

    /**
     * Log encoder capabilities to DiagnosticBuffer
     */
    fun logCapabilitiesToDiagnostics() {
        val caps = getH264EncoderCapabilities()
        if (caps != null) {
            DiagnosticBuffer.info("CODEC", "H.264 encoder: ${caps.encoderName} (HW: ${caps.isHardwareAccelerated})")
            DiagnosticBuffer.info("CODEC", "Profiles: ${caps.supportedProfiles.joinToString(", ")}")
            DiagnosticBuffer.info("CODEC", "Resolution: ${caps.widthRange.first}-${caps.widthRange.last} x ${caps.heightRange.first}-${caps.heightRange.last}")
            DiagnosticBuffer.info("CODEC", "Alignment: ${caps.widthAlignment}x${caps.heightAlignment}")
            DiagnosticBuffer.info("CODEC", "FPS: ${caps.supportedFrameRates.first}-${caps.supportedFrameRates.last}, Bitrate: ${caps.bitrateRange.first/1000}k-${caps.bitrateRange.last/1_000_000}M")
            DiagnosticBuffer.info("CODEC", "Color formats: ${caps.supportedColorFormats.take(3).joinToString(", ")}${if (caps.supportedColorFormats.size > 3) "..." else ""}")
            DiagnosticBuffer.info("CODEC", "Surface input: ${caps.supportsSurfaceInput}")
        } else {
            DiagnosticBuffer.error("CODEC", "Failed to query H.264 encoder capabilities")
        }
    }

    /**
     * Comprehensive diagnostic check for potential "scrambled video" issues.
     * Returns a list of potential problems and recommendations.
     */
    data class VideoConfigDiagnostic(
        val isLikelyOk: Boolean,
        val issues: List<String>,
        val warnings: List<String>,
        val recommendations: List<String>
    ) {
        fun toDiagnosticText(): String {
            val sb = StringBuilder()
            if (isLikelyOk) {
                sb.appendLine("Configuration appears OK")
            } else {
                sb.appendLine("Potential issues detected:")
            }
            issues.forEach { sb.appendLine("  ERROR: $it") }
            warnings.forEach { sb.appendLine("  WARN: $it") }
            if (recommendations.isNotEmpty()) {
                sb.appendLine("Recommendations:")
                recommendations.forEach { sb.appendLine("  - $it") }
            }
            return sb.toString()
        }
    }

    /**
     * Diagnose a video configuration for potential issues that could cause scrambled video.
     */
    fun diagnoseVideoConfig(
        context: Context,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        useFrontCamera: Boolean = true
    ): VideoConfigDiagnostic {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        val encoderCaps = getH264EncoderCapabilities()
        val cameraCaps = getCameraCapabilities(context, useFrontCamera)
        val resValidation = validateResolution(width, height)

        // 1. Check encoder support
        if (encoderCaps == null) {
            issues.add("Could not query encoder capabilities")
        } else {
            // Resolution support
            if (!encoderCaps.isResolutionSupported(width, height)) {
                issues.add("Resolution ${width}x${height} outside encoder range (${encoderCaps.widthRange.first}-${encoderCaps.widthRange.last} x ${encoderCaps.heightRange.first}-${encoderCaps.heightRange.last})")
            }

            // Alignment check
            val alignWarnings = encoderCaps.getAlignmentWarnings(width, height)
            if (alignWarnings.isNotEmpty()) {
                warnings.addAll(alignWarnings)
                recommendations.add("Use dimensions divisible by ${encoderCaps.widthAlignment} for best compatibility")
            }

            // FPS support
            if (fps !in encoderCaps.supportedFrameRates) {
                warnings.add("FPS $fps outside supported range (${encoderCaps.supportedFrameRates.first}-${encoderCaps.supportedFrameRates.last})")
            }

            // Bitrate check
            if (bitrate < encoderCaps.bitrateRange.first) {
                warnings.add("Bitrate ${bitrate/1000}kbps below minimum (${encoderCaps.bitrateRange.first/1000}kbps)")
                recommendations.add("Increase bitrate to at least ${encoderCaps.bitrateRange.first/1000}kbps")
            } else if (bitrate > encoderCaps.bitrateRange.last) {
                warnings.add("Bitrate ${bitrate/1000}kbps above maximum (${encoderCaps.bitrateRange.last/1000}kbps)")
            }

            // Surface input check (critical for our Camera2 implementation)
            if (!encoderCaps.supportsSurfaceInput) {
                issues.add("Encoder does not support Surface input - this is unusual and may cause issues")
            }
        }

        // 2. Check resolution validation (divisibility, aspect ratio)
        if (!resValidation.isDivisibleBy2) {
            issues.add("Odd dimensions will likely fail encoding")
        } else if (!resValidation.isDivisibleBy16) {
            warnings.add("Dimensions not divisible by 16 - may cause padding/stride issues on some devices")
            recommendations.add("Use standard resolutions like 640x480, 1280x720, or 1920x1080")
        }

        if (!resValidation.isStandardRatio) {
            warnings.add("Non-standard aspect ratio (${resValidation.aspectRatioName}) - may require scaling")
        }

        // 3. Check camera capabilities
        if (cameraCaps != null) {
            if (!cameraCaps.supportsAspectRatio(width, height)) {
                warnings.add("Camera does not natively support ${resValidation.aspectRatioName} aspect ratio")
                recommendations.add("Consider using a resolution that matches camera native ratios: ${cameraCaps.nativeAspectRatios.joinToString(", ")}")
            }

            // Check if exact resolution is supported
            if (!cameraCaps.supportsExact(width, height)) {
                val best = cameraCaps.findBestMatch(width, height)
                if (best != null && (best.width != width || best.height != height)) {
                    warnings.add("Camera will scale from ${best.width}x${best.height} to ${width}x${height}")
                }
            }
        }

        // 4. Specific known issues
        // Samsung devices sometimes have issues with non-standard resolutions
        val deviceModel = android.os.Build.MODEL.lowercase()
        val isSamsung = android.os.Build.MANUFACTURER.lowercase().contains("samsung")
        if (isSamsung && !resValidation.isDivisibleBy16) {
            warnings.add("Samsung devices may have encoder issues with non-16-aligned resolutions")
            recommendations.add("Try 640x480 (4:3) or 1280x720 (16:9) for best Samsung compatibility")
        }

        // 5. Calculate recommended resolutions based on camera
        if (cameraCaps != null && issues.isNotEmpty()) {
            val safeResolutions = cameraCaps.supportedSizes
                .filter { it.width % 16 == 0 && it.height % 16 == 0 }
                .filter { encoderCaps?.isResolutionSupported(it.width, it.height) != false }
                .take(3)
                .joinToString(", ") { "${it.width}x${it.height}" }
            if (safeResolutions.isNotEmpty()) {
                recommendations.add("Safe resolutions for this device: $safeResolutions")
            }
        }

        val isLikelyOk = issues.isEmpty() && warnings.size <= 1

        return VideoConfigDiagnostic(isLikelyOk, issues, warnings, recommendations)
    }

    /**
     * Check if a specific configuration is supported
     */
    fun isConfigSupported(width: Int, height: Int, fps: Int): Boolean {
        val caps = getH264EncoderCapabilities() ?: return false
        return caps.isResolutionSupported(width, height) && fps in caps.supportedFrameRates
    }

    /**
     * Get a summary suitable for display in settings
     */
    fun getSupportedResolutionsSummary(): String {
        val caps = getH264EncoderCapabilities()
        return caps?.supportedResolutions?.joinToString(", ") ?: "Unknown"
    }

    // ========== Resolution Validation ==========

    /**
     * Common aspect ratios that cameras typically support well
     */
    object AspectRatio {
        const val RATIO_16_9 = 16.0 / 9.0   // 1.778 - HD/FHD standard
        const val RATIO_4_3 = 4.0 / 3.0     // 1.333 - VGA/classic
        const val RATIO_3_2 = 3.0 / 2.0     // 1.5 - Some sensors
        const val RATIO_1_1 = 1.0           // Square

        fun nameOf(ratio: Double): String = when {
            abs(ratio - RATIO_16_9) < 0.01 -> "16:9"
            abs(ratio - RATIO_4_3) < 0.01 -> "4:3"
            abs(ratio - RATIO_3_2) < 0.01 -> "3:2"
            abs(ratio - RATIO_1_1) < 0.01 -> "1:1"
            else -> "%.2f:1".format(ratio)
        }

        fun isStandard(ratio: Double): Boolean {
            return abs(ratio - RATIO_16_9) < 0.01 ||
                   abs(ratio - RATIO_4_3) < 0.01 ||
                   abs(ratio - RATIO_3_2) < 0.01 ||
                   abs(ratio - RATIO_1_1) < 0.01
        }
    }

    /**
     * Resolution validation result
     */
    data class ResolutionValidation(
        val width: Int,
        val height: Int,
        val aspectRatio: Double,
        val aspectRatioName: String,
        val isStandardRatio: Boolean,
        val isDivisibleBy16: Boolean,
        val isDivisibleBy2: Boolean,
        val warnings: List<String>
    ) {
        val isValid: Boolean get() = warnings.isEmpty()

        fun toSummary(): String {
            return if (warnings.isEmpty()) {
                "${width}x${height} ($aspectRatioName) - OK"
            } else {
                "${width}x${height} ($aspectRatioName) - ${warnings.joinToString("; ")}"
            }
        }
    }

    /**
     * Validate a resolution for potential encoding issues
     */
    fun validateResolution(width: Int, height: Int): ResolutionValidation {
        val ratio = width.toDouble() / height
        val warnings = mutableListOf<String>()

        // Check divisibility by 16 (H.264 macroblock size)
        val divBy16 = width % 16 == 0 && height % 16 == 0
        if (!divBy16) {
            if (width % 16 != 0 && height % 16 != 0) {
                warnings.add("Neither dimension divisible by 16 (may cause encoding issues)")
            } else if (width % 16 != 0) {
                warnings.add("Width not divisible by 16")
            } else {
                warnings.add("Height not divisible by 16")
            }
        }

        // Check divisibility by 2 (minimum requirement)
        val divBy2 = width % 2 == 0 && height % 2 == 0
        if (!divBy2) {
            warnings.add("Odd dimension will likely fail")
        }

        // Check for standard aspect ratio
        val isStandard = AspectRatio.isStandard(ratio)
        if (!isStandard) {
            warnings.add("Non-standard aspect ratio")
        }

        return ResolutionValidation(
            width = width,
            height = height,
            aspectRatio = ratio,
            aspectRatioName = AspectRatio.nameOf(ratio),
            isStandardRatio = isStandard,
            isDivisibleBy16 = divBy16,
            isDivisibleBy2 = divBy2,
            warnings = warnings
        )
    }

    // ========== Camera Capabilities ==========

    /**
     * Camera capabilities data class
     */
    data class CameraCapabilities(
        val cameraId: String,
        val facing: String,  // "front" or "back"
        val sensorOrientation: Int,
        val supportedSizes: List<Size>,
        val nativeAspectRatios: List<String>
    ) {
        /**
         * Find the best matching size for a target resolution
         */
        fun findBestMatch(targetWidth: Int, targetHeight: Int): Size? {
            // First try exact match
            supportedSizes.find { it.width == targetWidth && it.height == targetHeight }?.let { return it }

            // Then try same aspect ratio
            val targetRatio = targetWidth.toDouble() / targetHeight
            val sameRatio = supportedSizes.filter {
                abs(it.width.toDouble() / it.height - targetRatio) < 0.01
            }
            if (sameRatio.isNotEmpty()) {
                // Return closest size with same ratio
                return sameRatio.minByOrNull { abs(it.width - targetWidth) + abs(it.height - targetHeight) }
            }

            // Return closest size overall
            return supportedSizes.minByOrNull { abs(it.width - targetWidth) + abs(it.height - targetHeight) }
        }

        /**
         * Check if camera natively supports a resolution (exact match)
         */
        fun supportsExact(width: Int, height: Int): Boolean {
            return supportedSizes.any { it.width == width && it.height == height }
        }

        /**
         * Check if camera supports the aspect ratio
         */
        fun supportsAspectRatio(width: Int, height: Int): Boolean {
            val targetRatio = width.toDouble() / height
            return supportedSizes.any { abs(it.width.toDouble() / it.height - targetRatio) < 0.01 }
        }

        fun toDiagnosticText(): String {
            val sb = StringBuilder()
            sb.appendLine("Camera: $cameraId ($facing)")
            sb.appendLine("Sensor orientation: $sensorOrientation°")
            sb.appendLine("Native aspect ratios: ${nativeAspectRatios.joinToString(", ")}")
            sb.appendLine("Supported sizes (${supportedSizes.size}):")
            // Group by aspect ratio for readability
            supportedSizes.groupBy { AspectRatio.nameOf(it.width.toDouble() / it.height) }
                .forEach { (ratio, sizes) ->
                    sb.appendLine("  $ratio: ${sizes.joinToString(", ") { "${it.width}x${it.height}" }}")
                }
            return sb.toString()
        }
    }

    /**
     * Get camera capabilities for the front or back camera
     */
    fun getCameraCapabilities(context: Context, useFrontCamera: Boolean = true): CameraCapabilities? {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val targetFacing = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT
                               else CameraCharacteristics.LENS_FACING_BACK

            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                if (facing == targetFacing) {
                    val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    val streamConfigMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

                    // Get supported output sizes for SurfaceTexture (what we use for encoding)
                    val sizes = streamConfigMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
                        ?.toList()
                        ?.sortedByDescending { it.width * it.height }
                        ?: emptyList()

                    // Get unique aspect ratios
                    val ratios = sizes.map { AspectRatio.nameOf(it.width.toDouble() / it.height) }
                        .distinct()

                    return CameraCapabilities(
                        cameraId = id,
                        facing = if (useFrontCamera) "front" else "back",
                        sensorOrientation = sensorOrientation,
                        supportedSizes = sizes,
                        nativeAspectRatios = ratios
                    )
                }
            }

            Log.w(TAG, "No ${if (useFrontCamera) "front" else "back"} camera found")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera capabilities: ${e.message}", e)
            return null
        }
    }

    /**
     * Log camera capabilities to DiagnosticBuffer
     */
    fun logCameraCapabilitiesToDiagnostics(context: Context, useFrontCamera: Boolean = true) {
        val caps = getCameraCapabilities(context, useFrontCamera)
        if (caps != null) {
            DiagnosticBuffer.info("CAMERA", "Camera: ${caps.cameraId} (${caps.facing}), orientation: ${caps.sensorOrientation}°")
            DiagnosticBuffer.info("CAMERA", "Native ratios: ${caps.nativeAspectRatios.joinToString(", ")}")
            DiagnosticBuffer.info("CAMERA", "Sizes: ${caps.supportedSizes.take(5).joinToString(", ") { "${it.width}x${it.height}" }}...")
        } else {
            DiagnosticBuffer.error("CAMERA", "Failed to query camera capabilities")
        }
    }
}
