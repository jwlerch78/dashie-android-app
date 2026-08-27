package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.data.FamilyMember
import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection

/**
 * Schema definition for the Music Assistant settings page.
 *
 * Layout:
 * 1. Enable Music Player toggle with connection status sublabel
 * 2. Reconnect action (tries central store, then full reset login)
 * 3. Music Player Display — default speaker, fullscreen on play, show with screensaver
 * 4. Family Music Profiles — link family members to MA users (visible when enabled + members exist)
 */
object MusicPageSchema {

    private val musicEnabled = Condition.IsTrue("music.enabled")
    private val notSpeakerOnly = Condition.IsFalse("music.speakerOnly")
    private val musicEnabledFullMode = Condition.And(listOf(musicEnabled, notSpeakerOnly))

    /**
     * @param members Family members loaded from Supabase (for music profile linking)
     * @param maUserNames Map of ma_user_id → display name (for showing link status)
     * @param maCurrentUser Display name of current MA token user (null if not checked yet)
     * @param maIsAdmin Whether the current MA token has admin role (null = still checking)
     */
    /**
     * @param members Family members loaded from Supabase (for music profile linking)
     * @param maUserNames Map of ma_user_id → display name (for showing link status)
     * @param maCurrentUser Display name of current MA token user (null if not connected)
     * @param maIsAdmin Whether the current MA token has admin role (null = still checking)
     * @param hasToken Whether a local MA token exists (may differ from maCurrentUser during async check)
     */
    fun create(
        members: List<FamilyMember> = emptyList(),
        maUserNames: Map<String, String> = emptyMap(),
        maCurrentUser: String? = null,
        maIsAdmin: Boolean? = null,
        isProd: Boolean = false,
        hasToken: Boolean = false,
        sendspinStatus: String? = null
    ) = SettingsPageSchema(
        id = "music",
        title = "Music Assistant",
        sections = listOfNotNull(
            enableSection(maCurrentUser, maIsAdmin, hasToken),
            speakerOnlySection(sendspinStatus),
            connectionSection(maCurrentUser != null || hasToken),
            displaySection()
            // Family Music Profiles section intentionally hidden — not in
            // active use yet. profilesSection() retained below for when
            // we re-enable per-member MA linkage. Re-add to this list to
            // restore (was previously gated `if (!isProd)`).
        )
    )

    // ── Section 1: Enable ─────────────────────────────────────────

    private fun enableSection(maCurrentUser: String?, maIsAdmin: Boolean?, hasToken: Boolean) = SettingsSection(
        header = null,
        items = listOf(
            SchemaItem.Toggle(
                id = "music_enabled",
                label = "Enable Music Player",
                sublabel = when {
                    maCurrentUser != null && maIsAdmin == false -> "Connected as: $maCurrentUser (not admin)"
                    maCurrentUser != null -> "Connected as: $maCurrentUser"
                    hasToken -> "Connected"
                    else -> "Not connected"
                },
                settingKey = "music.enabled",
                onChanged = "notifyMusicEnabledChanged"
            )
        )
    )

    // ── Section 1b: Speaker Only ────────────────────────────────────

    private fun speakerOnlySection(sendspinStatus: String?) = SettingsSection(
        header = null,
        visibleWhen = musicEnabled,
        items = listOfNotNull(
            SchemaItem.Toggle(
                id = "music_speaker_only",
                label = "Use Tablet as Speaker Only",
                sublabel = "Auto-discover and connect to Music Assistant as a speaker — no sign-in required",
                settingKey = "music.speakerOnly",
                onChanged = "notifySpeakerOnlyChanged"
            ),
            if (sendspinStatus != null) SchemaItem.Info(
                id = "sendspin_status",
                label = sendspinStatus
            ) else null
        )
    )

    // ── Section 2: Connection ─────────────────────────────────────

    private fun connectionSection(isConnected: Boolean) = SettingsSection(
        header = null,
        visibleWhen = musicEnabledFullMode,
        items = listOf(
            if (isConnected) {
                SchemaItem.Action(
                    id = "ma_disconnect",
                    label = "Disconnect Music Assistant",
                    action = "maDisconnect",
                    destructive = true
                )
            } else {
                SchemaItem.Action(
                    id = "ma_connect",
                    label = "Connect to Music Assistant",
                    action = "maReconnect"
                )
            }
        )
    )

    // ── Section 3: Display ────────────────────────────────────────

    private fun displaySection() = SettingsSection(
        header = "Music Player Display",
        visibleWhen = musicEnabledFullMode,
        items = listOf(
            SchemaItem.Navigation(
                id = "media_player",
                label = "Default Media Player",
                navigateTo = "ext:speaker_picker",
                displayValueKey = "music.entityDisplay"
            ),
            SchemaItem.Toggle(
                id = "music_fullscreen_on_play",
                label = "Full Screen on Play",
                settingKey = "music.fullScreenOnPlay"
            ),
            SchemaItem.Toggle(
                id = "music_show_with_screensaver",
                label = "Show with Screensaver",
                settingKey = "music.showWithScreensaver"
            )
        )
    )

    // ── Section 4: Family Music Profiles ─────────────────────────

    private fun profilesSection(
        members: List<FamilyMember>,
        maUserNames: Map<String, String>,
        maCurrentUser: String?,
        maIsAdmin: Boolean?
    ): SettingsSection {
        // Still checking MA role — show loading
        if (maIsAdmin == null) {
            return SettingsSection(
                header = "Family Music Profiles",
                visibleWhen = musicEnabledFullMode,
                items = listOf(
                    SchemaItem.Info(
                        id = "ma_checking",
                        label = "Checking Music Assistant account..."
                    )
                )
            )
        }

        // Not admin — show message with re-login option
        if (!maIsAdmin) {
            val userLabel = if (maCurrentUser != null) "Signed in as: $maCurrentUser (not admin)" else "Not signed in as admin"
            return SettingsSection(
                header = "Family Music Profiles",
                footer = "An admin Music Assistant account is required to manage family music profiles",
                visibleWhen = musicEnabledFullMode,
                items = listOf(
                    SchemaItem.Info(
                        id = "ma_not_admin",
                        label = userLabel
                    ),
                    SchemaItem.Action(
                        id = "ma_relogin",
                        label = "Sign In as Admin",
                        action = "maReloginAdmin"
                    )
                )
            )
        }

        // Admin: show summary navigation to profiles sub-page
        val linkedNames = members.filter { it.maUserId != null }.map { it.shortName }
        val summaryLabel = if (linkedNames.isNotEmpty()) {
            "Linked: ${linkedNames.joinToString(", ")}"
        } else {
            "No members linked yet"
        }

        return SettingsSection(
            header = "Family Music Profiles",
            visibleWhen = musicEnabledFullMode,
            items = listOf(
                SchemaItem.Navigation(
                    id = "ma_profiles_list",
                    label = "Music Assistant Profile Assignments",
                    sublabel = summaryLabel,
                    navigateTo = "ext:ma_profiles_page"
                )
            )
        )
    }

    // ── Profiles Sub-page ─────────────────────────────────────────

    /**
     * Create the profiles list page (pushed when tapping the summary row).
     * Shows each family member with their MA link status, tappable to pick an MA user.
     */
    fun createProfilesPage(
        members: List<FamilyMember>,
        maUserNames: Map<String, String>
    ) = SettingsPageSchema(
        id = "ma_profiles",
        title = "Family Music Profiles",
        sections = listOf(
            SettingsSection(
                footer = "Tap a member to link or change their Music Assistant account",
                items = members.map { member ->
                    val maUserId = member.maUserId
                    val linkStatus = if (maUserId != null) {
                        maUserNames[maUserId] ?: maUserId
                    } else {
                        "Not linked"
                    }
                    SchemaItem.Navigation(
                        id = "ma_profile_${member.id}",
                        label = member.shortName,
                        sublabel = linkStatus,
                        navigateTo = "ext:ma_user_picker:${member.id}",
                        colorDot = member.assignedColor,
                        avatarUrl = member.bestAvatarUrl
                    )
                }
            )
        )
    )
}
