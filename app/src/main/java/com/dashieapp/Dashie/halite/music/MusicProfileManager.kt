package com.dashieapp.Dashie.halite.music

import android.util.Log
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import com.dashieapp.Dashie.halite.settings.data.FamilyMember
import org.json.JSONArray

/**
 * Manages the active music profile — resolves family member + MA user data
 * into a MusicProfile for display in the browser sidebar.
 *
 * Holds cached MA user data (fetched once per session) and the family member
 * list (provided externally, same data as settings pages use).
 *
 * Usage:
 *   val mgr = MusicProfileManager(connectionPrefs)
 *   mgr.setFamilyMembers(members)
 *   mgr.fetchMaUsers()  // call on background thread
 *   val profile = mgr.getActiveProfile()  // returns MusicProfile or null
 */
class MusicProfileManager(private val connectionPrefs: ConnectionPreferences) {

    companion object {
        private const val TAG = "MusicProfileMgr"
    }

    /** Cached family members (set externally from bridge callback) */
    private var familyMembers: List<FamilyMember> = emptyList()

    /** Cached MA user data: user_id → MaUserInfo */
    private var maUsers: Map<String, MaUserInfo> = emptyMap()
    /** The current MA token's user info (from auth/me) */
    private var currentMaUser: MaUserInfo? = null

    data class MaUserInfo(
        val userId: String,
        val displayName: String,
        val avatarUrl: String?,            // MA user avatar (often from HA imageproxy)
        val providerFilter: List<String>   // e.g. ["spotify--nmGvvB9h"]
    )

    fun setFamilyMembers(members: List<FamilyMember>) {
        familyMembers = members
    }

    /**
     * Fetch MA users from the API and cache them. Call on a background thread.
     * Returns true if fetch succeeded.
     */
    fun fetchMaUsers(): Boolean {
        val apiUrl = connectionPrefs.getEffectiveMaApiUrl()
        if (!connectionPrefs.hasMaApiToken || apiUrl.isEmpty()) return false

        return try {
            val client = MaApiClient(apiUrl, connectionPrefs.maApiToken)

            // Fetch current user (always works, even non-admin)
            val me = client.getCurrentUser()
            if (me != null) {
                val meProviders = mutableListOf<String>()
                me.optJSONArray("provider_filter")?.let { arr ->
                    for (j in 0 until arr.length()) { arr.optString(j)?.let { meProviders.add(it) } }
                }
                currentMaUser = MaUserInfo(
                    userId = me.optString("user_id", ""),
                    displayName = me.optString("display_name", "").ifEmpty { me.optString("username", "") },
                    avatarUrl = me.optString("avatar_url", "").takeIf { it.isNotEmpty() && it != "null" },
                    providerFilter = meProviders
                )
                Log.i(TAG, "Current MA user: ${currentMaUser?.displayName} (${me.optString("role")})")
            }

            // Fetch all users (admin only — fails gracefully for non-admin)
            val users = client.getUsers()
            if (users != null) {
                val map = mutableMapOf<String, MaUserInfo>()
                for (i in 0 until users.length()) {
                    val u = users.getJSONObject(i)
                    val id = u.optString("user_id", "")
                    if (id.isEmpty()) continue
                    val providers = mutableListOf<String>()
                    u.optJSONArray("provider_filter")?.let { arr ->
                        for (j in 0 until arr.length()) { arr.optString(j)?.let { providers.add(it) } }
                    }
                    map[id] = MaUserInfo(
                        userId = id,
                        displayName = u.optString("display_name", "").ifEmpty { u.optString("username", "") },
                        avatarUrl = u.optString("avatar_url", "").takeIf { it.isNotEmpty() && it != "null" },
                        providerFilter = providers
                    )
                }
                maUsers = map
                Log.i(TAG, "Cached ${map.size} MA users")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch MA users: ${e.message}")
            false
        }
    }

    /** Ensure data is loaded; call on background thread. Returns true if data is ready. */
    fun ensureDataLoaded(): Boolean {
        if (currentMaUser != null) return true
        return fetchMaUsers()
    }

    /** Get the MA user display name for a given user ID */
    fun getMaUserName(maUserId: String): String? = maUsers[maUserId]?.displayName

    /** Get all cached MA user display names (for settings UI) */
    fun getMaUserNames(): Map<String, String> = maUsers.mapValues { it.value.displayName }

    /**
     * Get the active music profile. Always returns a profile if MA data is loaded:
     * 1. Explicitly selected family member (activeMusicProfileId set)
     * 2. Fallback: the current MA token user (whoever is logged in)
     */
    fun getActiveProfile(): MusicProfile? {
        val profileId = connectionPrefs.activeMusicProfileId

        if (profileId.isNotEmpty()) {
            // Try as family member ID first (best quality — has avatar, color)
            val member = familyMembers.find { it.id == profileId }
            if (member != null) {
                val maUserId = member.maUserId
                val maUser = maUserId?.let { maUsers[it] }
                val providerLabel = maUser?.let { resolveProviderLabel(it.providerFilter) }
                return MusicProfile(
                    memberName = member.displayName,
                    avatarUrl = member.bestAvatarUrl,
                    assignedColor = member.assignedColor,
                    providerLabel = providerLabel
                )
            }

            // Try as MA user ID (from fallback switcher when no family members linked)
            val maUser = maUsers[profileId]
            if (maUser != null) {
                val linkedMember = familyMembers.find { it.maUserId == profileId }
                return MusicProfile(
                    memberName = linkedMember?.displayName ?: maUser.displayName,
                    avatarUrl = linkedMember?.bestAvatarUrl ?: maUser.avatarUrl,
                    assignedColor = linkedMember?.assignedColor ?: "#FF9500",
                    providerLabel = resolveProviderLabel(maUser.providerFilter),
                    profileId = profileId
                )
            }
        }

        // Fallback: current MA token user (always available after ensureDataLoaded)
        val me = currentMaUser ?: return null
        return MusicProfile(
            memberName = me.displayName,
            avatarUrl = me.avatarUrl,
            assignedColor = "#FF9500",
            providerLabel = resolveProviderLabel(me.providerFilter),
            profileId = me.userId
        )
    }

    /** Set the active music profile by family member ID or MA user ID. Persists to prefs. */
    fun setActiveProfileId(memberId: String?) {
        connectionPrefs.activeMusicProfileId = memberId ?: ""
        Log.i(TAG, "Active music profile: ${memberId ?: "(none)"}")
    }

    /**
     * Resolve the MA user ID for the active profile.
     * Handles both family member IDs (looks up maUserId) and direct MA user IDs.
     */
    fun getActiveMaUserId(): String? {
        val profileId = connectionPrefs.activeMusicProfileId
        if (profileId.isEmpty()) return currentMaUser?.userId

        // Try as family member → get their maUserId
        val member = familyMembers.find { it.id == profileId }
        if (member?.maUserId != null) return member.maUserId

        // Try as direct MA user ID
        if (maUsers.containsKey(profileId)) return profileId

        return currentMaUser?.userId
    }

    /**
     * Get a user-scoped MaApiClient for the active profile. Call on background thread.
     * Lazily creates a long-lived token for the user if not cached.
     * Returns null if no user is active or token creation fails.
     */
    fun getUserScopedClient(): MaApiClient? {
        val maUserId = getActiveMaUserId()
        if (maUserId == null) {
            Log.d(TAG, "getUserScopedClient: no active MA user ID")
            return null
        }
        val apiUrl = connectionPrefs.getEffectiveMaApiUrl()
        if (apiUrl.isEmpty()) return null

        // If it's the admin user (current token), use the existing token
        if (maUserId == currentMaUser?.userId) {
            Log.d(TAG, "getUserScopedClient: using admin token (user is admin: ${currentMaUser?.displayName})")
            return MaApiClient(apiUrl, connectionPrefs.maApiToken)
        }

        // Check for cached per-user token
        var userToken = connectionPrefs.getMaUserToken(maUserId)
        if (userToken.isNotEmpty()) {
            val maUser = maUsers[maUserId]
            Log.d(TAG, "getUserScopedClient: using cached token for ${maUser?.displayName ?: maUserId}")
            return MaApiClient(apiUrl, userToken)
        }

        // Create a long-lived token for this user (admin operation)
        Log.i(TAG, "Creating long-lived token for MA user $maUserId")
        val adminClient = MaApiClient(apiUrl, connectionPrefs.maApiToken)
        val maUser = maUsers[maUserId]
        val tokenName = "Dashie - ${maUser?.displayName ?: maUserId}"
        val newToken = adminClient.createTokenForUser(maUserId, tokenName)
        if (newToken == null) {
            Log.e(TAG, "getUserScopedClient: FAILED to create token for ${maUser?.displayName ?: maUserId}")
            return null
        }

        // Cache it
        connectionPrefs.setMaUserToken(maUserId, newToken)
        Log.i(TAG, "Cached long-lived token for ${maUser?.displayName} (${newToken.length} chars)")
        return MaApiClient(apiUrl, newToken)
    }

    /** Get family members that have MA user links (for profile switcher) */
    fun getLinkedMembers(): List<FamilyMember> {
        return familyMembers.filter { it.maUserId != null }
    }

    /** Get all switchable profiles for the grid view. Uses linked family members if available, otherwise MA users. */
    fun getAllSwitchableProfiles(): List<MusicProfile> {
        val linked = getLinkedMembers()
        if (linked.isNotEmpty()) {
            return linked.map { member ->
                val maUser = member.maUserId?.let { maUsers[it] }
                MusicProfile(
                    memberName = member.displayName,
                    avatarUrl = member.bestAvatarUrl ?: maUser?.avatarUrl,
                    assignedColor = member.assignedColor,
                    providerLabel = maUser?.let { resolveProviderLabel(it.providerFilter) },
                    profileId = member.id
                )
            }
        }
        // Fallback: all MA users (including current user)
        return maUsers.values.map { user ->
                val linkedMember = familyMembers.find { it.maUserId == user.userId }
                MusicProfile(
                    memberName = linkedMember?.displayName ?: user.displayName,
                    avatarUrl = linkedMember?.bestAvatarUrl ?: user.avatarUrl,
                    assignedColor = linkedMember?.assignedColor ?: "#FF9500",
                    providerLabel = resolveProviderLabel(user.providerFilter),
                    profileId = user.userId
                )
            }
    }

    /** Resolve a human-readable provider label from MA provider_filter entries */
    private fun resolveProviderLabel(providers: List<String>): String? {
        if (providers.isEmpty()) return null
        // Provider IDs look like "spotify--nmGvvB9h", "ytmusic--abc123", etc.
        for (provider in providers) {
            val lower = provider.lowercase()
            when {
                lower.startsWith("spotify") -> return "Spotify"
                lower.startsWith("ytmusic") || lower.startsWith("youtube") -> return "YouTube Music"
                lower.startsWith("apple") -> return "Apple Music"
                lower.startsWith("tidal") -> return "Tidal"
                lower.startsWith("soundcloud") -> return "SoundCloud"
                lower.startsWith("deezer") -> return "Deezer"
                lower.startsWith("plex") -> return "Plex"
                lower.startsWith("jellyfin") -> return "Jellyfin"
            }
        }
        // Unknown provider — return the first one cleaned up
        return providers.first().substringBefore("--").replaceFirstChar { it.uppercase() }
    }
}
