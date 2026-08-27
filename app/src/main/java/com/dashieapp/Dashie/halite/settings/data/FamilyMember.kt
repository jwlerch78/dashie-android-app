package com.dashieapp.Dashie.halite.settings.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data model for a family member, parsed from JS bridge JSON.
 *
 * Maps to the Supabase `family_members` table. Loaded via
 * `familyService.listMembers()` through the WebView bridge.
 */
data class FamilyMember(
    val id: String,
    val fullName: String,
    val nickname: String?,
    val relationship: String,     // parent, child, guest
    val assignedColor: String,    // hex color e.g. #FF6B6B
    val avatarUrl: String?,
    val notes: String?,
    val assignedCalendars: List<String>,  // calendar IDs
    // Device linking
    val deviceLinked: Boolean,
    val deviceName: String?,
    val devicePlatform: String?,
    val inviteCode: String?,
    val invitePending: Boolean,
    val gpsEnabled: Boolean,
    // Google account linking
    val linkedEmail: String?,
    val linkedAuthUserId: String?,
    // HA / MA profile linking
    val haPersonEntityId: String?,   // e.g. "person.john" — linked HA person entity
    val maUserId: String?,           // linked Music Assistant user profile ID
    val haAvatarUrl: String?         // cached avatar URL from HA person entity_picture
) {
    /** Display name: nickname if set, otherwise full name */
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: fullName

    /**
     * Short name for compact list display (e.g. calendar assignment).
     * Uses the explicit display-name (nickname) if set; otherwise returns
     * the first token + a generational suffix (Jr/Sr/II/III/IV) when present,
     * so a son named "Raleigh Smith Jr" shows as "Raleigh Jr" instead of
     * colliding with a parent named "Raleigh".
     */
    val shortName: String get() {
        nickname?.takeIf { it.isNotBlank() }?.let { return it }
        val tokens = fullName.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return fullName
        val first = tokens.first()
        val last = tokens.last()
        val suffixes = setOf("Jr", "Jr.", "Sr", "Sr.", "II", "III", "IV")
        return if (tokens.size >= 2 && last in suffixes) "$first $last" else first
    }

    /** Capitalized role label */
    val roleLabel: String get() = relationship.replaceFirstChar { it.uppercase() }

    /** Whether this member is the account owner (linked to the auth user) */
    val isAccountOwner: Boolean get() = linkedAuthUserId != null && linkedEmail != null

    /** Best available avatar URL: Dashie avatar first, then HA avatar, or null */
    val bestAvatarUrl: String? get() = avatarUrl?.takeIf { it.isNotBlank() }
        ?: haAvatarUrl?.takeIf { it.isNotBlank() }

    companion object {
        /** Parse a single member from JSON */
        fun fromJson(json: JSONObject): FamilyMember {
            // Parse assigned_calendars from JSON array
            val calendars = mutableListOf<String>()
            val calArr = json.optJSONArray("assigned_calendars")
            if (calArr != null) {
                for (i in 0 until calArr.length()) {
                    calArr.optString(i)?.let { calendars.add(it) }
                }
            }

            // Check invite pending state
            val hasInviteCode = json.has("invite_code") && !json.isNull("invite_code")
            val inviteExpires = json.optString("invite_code_expires_at", "")
            val invitePending = hasInviteCode && inviteExpires.isNotEmpty()

            return FamilyMember(
                id = json.optString("id", ""),
                fullName = json.optString("full_name", json.optString("fullName", "")),
                nickname = if (json.isNull("nickname")) null else json.optString("nickname", ""),
                relationship = json.optString("relationship", "parent"),
                assignedColor = json.optString("assigned_color", json.optString("assignedColor", "#FF6B6B")),
                avatarUrl = if (json.isNull("avatar_url") && json.isNull("avatarUrl")) null
                    else json.optString("avatar_url", json.optString("avatarUrl", "")),
                notes = if (json.isNull("notes")) null else json.optString("notes", ""),
                assignedCalendars = calendars,
                deviceLinked = json.has("device_linked_at") && !json.isNull("device_linked_at"),
                deviceName = if (json.isNull("device_name") && json.isNull("deviceName")) null
                    else json.optString("device_name", json.optString("deviceName", "")),
                devicePlatform = if (json.isNull("device_platform")) null
                    else json.optString("device_platform", ""),
                inviteCode = if (json.isNull("invite_code")) null
                    else json.optString("invite_code", ""),
                invitePending = invitePending,
                gpsEnabled = json.optBoolean("gps_sharing_enabled", json.optBoolean("gpsEnabled", false)),
                linkedEmail = if (json.isNull("linked_email")) null
                    else json.optString("linked_email", ""),
                linkedAuthUserId = if (json.isNull("linked_auth_user_id")) null
                    else json.optString("linked_auth_user_id", ""),
                haPersonEntityId = if (json.isNull("ha_person_entity_id")) null
                    else json.optString("ha_person_entity_id", ""),
                maUserId = if (json.isNull("ma_user_id")) null
                    else json.optString("ma_user_id", ""),
                haAvatarUrl = if (json.isNull("ha_avatar_url")) null
                    else json.optString("ha_avatar_url", "")
            )
        }

        /** Parse an array of members from JSON */
        fun fromJsonArray(json: String): List<FamilyMember> {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** 10-color palette matching JS FamilyService.getColorPalette() */
        val COLOR_PALETTE = listOf(
            "#FF6B6B", "#4ECDC4", "#45B7D1", "#FFA07A", "#98D8C8",
            "#F7DC6F", "#BB8FCE", "#85C1E2", "#F8B739", "#52B788"
        )
    }
}
