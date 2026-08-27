package com.dashieapp.Dashie.api

/**
 * RTSP configuration data class for API responses.
 * Used across the api package and its consumers; kept as a top-level type.
 */
data class RtspConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val port: Int,
    val softwareEncoding: Boolean
)
