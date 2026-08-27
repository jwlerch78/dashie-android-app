package com.dashieapp.Dashie.controlcenter

/**
 * Data model for a Control Center card.
 *
 * Each card represents a feature or device section with a status indicator,
 * label, summary text, and navigation action.
 */
data class ControlCenterCard(
    val id: String,
    val label: String,
    val status: CardStatus,
    val summary: String,
    val action: String,
    /** Feature cards show a status dot; nav cards do not */
    val showDot: Boolean = true
)

enum class CardStatus {
    ON, OFF, WARN, ERROR
}

/**
 * Represents the bottom button row (Account + All Settings).
 */
data class ControlCenterBottomButton(
    val label: String,
    val subtitle: String?,
    val action: String
)

/**
 * Device info footer shown at the bottom of the control center.
 */
data class ControlCenterFooter(
    val ip: String,
    val appVersion: String,
    val deviceId: String
)
