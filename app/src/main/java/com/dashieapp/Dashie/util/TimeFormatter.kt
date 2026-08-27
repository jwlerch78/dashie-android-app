package com.dashieapp.Dashie.util

/**
 * Shared utility for formatting time durations and values.
 * Consolidates duplicate time formatting logic across the codebase.
 */
object TimeFormatter {

    /**
     * Format a timeout value in seconds for display.
     * Examples: 0 -> "Disabled", 30 -> "30 seconds", 60 -> "1 minute", 300 -> "5 minutes"
     */
    fun formatTimeout(seconds: Int): String {
        return when {
            seconds <= 0 -> "Disabled"
            seconds < 60 -> "$seconds seconds"
            seconds == 60 -> "1 minute"
            seconds < 3600 -> "${seconds / 60} minutes"
            else -> "${seconds / 3600} hour${if (seconds >= 7200) "s" else ""}"
        }
    }

    /**
     * Format a duration in milliseconds for display.
     * Examples: 30000 -> "30 seconds", 120000 -> "2 minutes"
     */
    fun formatDuration(milliseconds: Long): String {
        val seconds = (milliseconds / 1000).toInt()
        return formatTimeout(seconds)
    }

    /**
     * Format trial remaining time from expiration timestamp.
     * @param expiresAtMillis The expiration timestamp in milliseconds
     * @return Human-readable remaining time, or "Expired" if past
     */
    fun formatTrialRemaining(expiresAtMillis: Long): String {
        val now = System.currentTimeMillis()
        val remainingMs = expiresAtMillis - now

        if (remainingMs <= 0) {
            return "Expired"
        }

        val days = remainingMs / (24 * 60 * 60 * 1000)
        val hours = (remainingMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)

        return when {
            days > 0 -> "$days day${if (days > 1) "s" else ""}"
            hours > 0 -> "$hours hour${if (hours > 1) "s" else ""}"
            else -> "Less than an hour"
        }
    }

    /**
     * Format a relative time from a timestamp.
     * Examples: "Just now", "5 minutes ago", "2 hours ago", "Yesterday"
     */
    fun formatRelativeTime(timestampMillis: Long): String {
        val now = System.currentTimeMillis()
        val diffMs = now - timestampMillis

        if (diffMs < 0) {
            return "In the future"
        }

        val seconds = diffMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> "Just now"
            minutes < 60 -> "$minutes minute${if (minutes > 1) "s" else ""} ago"
            hours < 24 -> "$hours hour${if (hours > 1) "s" else ""} ago"
            days == 1L -> "Yesterday"
            days < 7 -> "$days days ago"
            else -> "${days / 7} week${if (days >= 14) "s" else ""} ago"
        }
    }
}
