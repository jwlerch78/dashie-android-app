package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.halite.settings.data.FamilyMember
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Family settings page.
 *
 * Dynamic schema — the member list is loaded from Supabase via the JS bridge.
 * Each member appears as a Navigation item. The schema is rebuilt on each
 * data refresh via the schemaProvider lambda pattern (same as VideoFeedsPageSchema).
 *
 * Sections:
 * 1. Family Info — family name, zip code (SharedPreferences-backed)
 * 2. Family Members — dynamic member list + Add Member action (JS bridge-backed)
 */
object FamilyPageSchema {

    /**
     * @param members Family members loaded from Supabase via JS bridge
     * @param isLoading Whether members are still being loaded
     * @param usedColors Colors already assigned to members (for color picker exclusion)
     */
    fun create(
        members: List<FamilyMember> = emptyList(),
        isLoading: Boolean = false
    ) = SettingsPageSchema(
        id = "family",
        title = "Family",
        sections = listOf(
            familyInfoSection(),
            membersSection(members, isLoading)
        ),
        subScreens = mapOf(
            "family-add-role" to rolePickerSubScreen("family"),
            "family-edit-role" to rolePickerSubScreen("family"),
            "family-add-color" to colorPickerSubScreen(members, null),
            "family-edit-color" to colorPickerSubScreen(members, null)
        )
    )

    // ── Section 1: Family Info ──────────────────────────────────────

    private fun familyInfoSection() = SettingsSection(
        header = "Family Settings",
        items = listOf(
            SchemaItem.Navigation(
                id = "family_name",
                label = "Family Name",
                navigateTo = "ext:edit_family_name",
                displayValueKey = "family.familyNameDisplay"
            ),
            SchemaItem.Navigation(
                id = "family_zip",
                label = "Zip Code",
                navigateTo = "ext:edit_family_zip",
                displayValueKey = "family.zipCodeDisplay"
            )
        )
    )

    // ── Section 2: Family Members ───────────────────────────────────

    private fun membersSection(members: List<FamilyMember>, isLoading: Boolean): SettingsSection {
        val memberItems: List<SchemaItem> = when {
            isLoading -> listOf(
                SchemaItem.Info(
                    id = "members_loading",
                    label = "Loading family members..."
                )
            )
            members.isEmpty() -> listOf(
                SchemaItem.Info(
                    id = "members_empty",
                    label = "No family members yet. Add your first member below."
                )
            )
            else -> members.map { member ->
                SchemaItem.Navigation(
                    id = "member_${member.id}",
                    label = member.shortName,
                    sublabel = member.roleLabel,
                    navigateTo = "ext:edit_member:${member.id}",
                    colorDot = member.assignedColor,
                    avatarUrl = member.bestAvatarUrl
                )
            }
        }

        val addItem = SchemaItem.Action(
            id = "add_member",
            label = "+ Add Family Member",
            action = "addFamilyMember"
        )

        return SettingsSection(
            header = "Family Members",
            items = memberItems + addItem
        )
    }

    // ── Sub-screens ─────────────────────────────────────────────────

    fun rolePickerSubScreen(parent: String) = SubScreenSchema(
        title = "Role",
        parent = parent,
        sections = listOf(
            SettingsSection(
                footer = "Parents can manage settings and other family members. Children and guests have limited access.",
                items = listOf(
                    SchemaItem.Picker(
                        id = "role_picker",
                        label = "Role",
                        settingKey = "family.editMemberRole",
                        options = listOf(
                            SchemaPickerOption("parent", "Parent"),
                            SchemaPickerOption("child", "Child"),
                            SchemaPickerOption("guest", "Guest")
                        )
                    )
                )
            )
        )
    )

    fun colorPickerSubScreen(members: List<FamilyMember>, editingMemberId: String?) = SubScreenSchema(
        title = "Color",
        parent = "family",
        sections = listOf(
            SettingsSection(
                footer = "Each family member gets a unique color for calendar events and UI elements.",
                items = listOf(
                    SchemaItem.Picker(
                        id = "color_picker",
                        label = "Color",
                        settingKey = "family.editMemberColor",
                        options = FamilyMember.COLOR_PALETTE.map { color ->
                            val usedByOther = members.any { it.assignedColor == color && it.id != editingMemberId }
                            SchemaPickerOption(
                                color,
                                colorDisplayName(color),
                                enabled = !usedByOther,
                                colorSwatch = color
                            )
                        }
                    )
                )
            )
        )
    )

    // ── Edit Member Page ──────────────────────────────────────────

    /**
     * Create a full settings page schema for editing a family member.
     * Pushed as a separate fragment on top of the family list.
     */
    fun createEditScreen(
        member: FamilyMember?,
        allMembers: List<FamilyMember>,
        isNewMember: Boolean = false
    ) = SettingsPageSchema(
        id = "family_edit",
        title = if (isNewMember) "Add Member" else "Edit Member",
        sections = listOfNotNull(
            if (!isNewMember && member?.bestAvatarUrl != null) editAvatarSection(member) else null,
            editProfileSection(),
            editRoleColorSection(allMembers, member?.id),
            if (!isNewMember && member != null) editMobileAppSection(member) else null,
            if (!isNewMember && member != null) editGoogleAccountSection(member) else null, // returns null if no linked account
            if (!isNewMember && member != null) editMusicProfileSection(member) else null,
            if (!isNewMember && member != null) editCalendarsSection(member) else null,
            if (!isNewMember && member != null) editReminderAlertsSection(member) else null,
            editNotesSection(),
            editActionsSection(isNewMember)
        ),
        subScreens = mapOf(
            "family-edit-role-picker" to rolePickerSubScreen("family_edit"),
            "family-edit-color-picker" to colorPickerSubScreen(allMembers, member?.id)
        ),
        // Data-entry form: hide the nav-bar "Done" (closes all of Settings,
        // discards the form). The screen's own "Add Member"/"Save Changes"
        // and "Cancel" actions are the real exits — D.30.
        hideDoneButton = true
    )

    private fun editAvatarSection(member: FamilyMember) = SettingsSection(
        header = "Photo",
        footer = "Update photo in Home Assistant or the Dashie mobile app",
        items = listOf(
            SchemaItem.Navigation(
                id = "edit_avatar_display",
                label = member.displayName,
                navigateTo = "",  // non-navigable, display only
                avatarUrl = member.bestAvatarUrl,
                colorDot = member.assignedColor
            )
        )
    )

    /**
     * "Timer & reminder alerts" toggle — whether this member's phone gets a push
     * when a timer/reminder fires. Immediate-apply via the applyReminderAlertPref
     * callback (writes member_notification_preferences.notify_on_reminder). Shown
     * for every saved member; the footer tells unlinked members it needs the app.
     */
    private fun editReminderAlertsSection(member: FamilyMember) = SettingsSection(
        header = "Alerts",
        footer = if (member.deviceLinked)
            "Send a notification to their phone when a timer or reminder goes off."
        else
            "Notifies their phone when a timer or reminder goes off — once they've installed and linked the Dashie mobile app.",
        items = listOf(
            SchemaItem.Toggle(
                id = "edit_notify_on_reminder",
                label = "Timer & reminder alerts",
                settingKey = "family.editNotifyOnReminder",
                defaultValue = true,
                onChanged = "applyReminderAlertPref"
            )
        )
    )

    private fun editProfileSection() = SettingsSection(
        header = "Profile",
        items = listOf(
            SchemaItem.TextInput(
                id = "edit_name",
                label = "Name",
                settingKey = "family.editName",
                placeholder = "Required"
            ),
            SchemaItem.TextInput(
                id = "edit_nickname",
                label = "Display Name",
                settingKey = "family.editNickname",
                placeholder = "Optional — shown in lists"
            )
        )
    )

    private fun editRoleColorSection(allMembers: List<FamilyMember>, editingId: String?) = SettingsSection(
        header = "Appearance",
        items = listOf(
            SchemaItem.Navigation(
                id = "edit_role",
                label = "Role",
                navigateTo = "family-edit-role-picker",
                displayValueKey = "family.editMemberRole",
                directPicker = true
            ),
            SchemaItem.Navigation(
                id = "edit_color",
                label = "Color",
                navigateTo = "family-edit-color-picker",
                colorDot = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.color,
                directPicker = true
            )
        )
    )

    private fun editMobileAppSection(member: FamilyMember): SettingsSection {
        val items = mutableListOf<SchemaItem>()

        when {
            // Device is linked and active
            member.deviceLinked -> {
                val deviceInfo = listOfNotNull(
                    member.deviceName,
                    member.devicePlatform?.replaceFirstChar { it.uppercase() }
                ).joinToString(" · ").ifEmpty { "Linked" }
                items.add(SchemaItem.Info(
                    id = "device_status",
                    label = "Status: Active — $deviceInfo"
                ))
                items.add(SchemaItem.Action(
                    id = "edit_device_name",
                    label = "Edit Device Name",
                    action = "editDeviceName"
                ))
                items.add(SchemaItem.Action(
                    id = "unlink_device",
                    label = "Remove Device",
                    action = "unlinkDevice",
                    destructive = true
                ))
            }
            // Invite is pending
            member.invitePending -> {
                items.add(SchemaItem.Info(
                    id = "device_status",
                    label = "Status: Pending — invite sent"
                ))
                items.add(SchemaItem.Action(
                    id = "resend_invite",
                    label = "Resend Invite",
                    action = "resendDeviceInvite"
                ))
                items.add(SchemaItem.Action(
                    id = "cancel_invite",
                    label = "Cancel Invite",
                    action = "cancelDeviceInvite"
                ))
            }
            // Account owner — sign in directly, no invite needed
            member.isAccountOwner -> {
                items.add(SchemaItem.Info(
                    id = "device_status",
                    label = "Status: Not Active"
                ))
                items.add(SchemaItem.Info(
                    id = "device_hint",
                    label = "Sign in on the Dashie mobile app with your Google account to link this device."
                ))
            }
            // No device, no invite — can send invite
            else -> {
                items.add(SchemaItem.Info(
                    id = "device_status",
                    label = "Status: Not Active"
                ))
                items.add(SchemaItem.Action(
                    id = "send_invite",
                    label = "Link Mobile Device",
                    action = "sendDeviceInvite"
                ))
            }
        }

        return SettingsSection(
            header = "Mobile App",
            items = items
        )
    }

    private fun editGoogleAccountSection(member: FamilyMember): SettingsSection? {
        // Only show if member has a linked Google account
        if (member.linkedEmail.isNullOrBlank()) return null

        return SettingsSection(
            header = "Linked Google Account",
            items = listOf(
                SchemaItem.Info(
                    id = "google_email",
                    label = member.linkedEmail
                ),
                SchemaItem.Action(
                    id = "unlink_google",
                    label = "Unlink Account",
                    action = "unlinkGoogleAccount",
                    destructive = true
                )
            )
        )
    }

    private fun editMusicProfileSection(member: FamilyMember): SettingsSection? {
        val maUserId = member.maUserId
        if (maUserId.isNullOrBlank()) return null

        return SettingsSection(
            header = "Music Profile",
            footer = "Linked in Music Assistant settings",
            items = listOf(
                SchemaItem.Info(
                    id = "ma_user_status",
                    label = "Linked to MA user: $maUserId"
                )
            )
        )
    }

    private fun editCalendarsSection(member: FamilyMember) = SettingsSection(
        header = "Assigned Calendars",
        items = if (member.assignedCalendars.isEmpty()) {
            listOf(SchemaItem.Info(
                id = "no_calendars",
                label = "None — assign calendars from Calendar settings"
            ))
        } else {
            member.assignedCalendars.map { calId ->
                SchemaItem.Info(
                    id = "cal_$calId",
                    label = calId.substringAfter("-").ifEmpty { calId }
                )
            }
        }
    )

    private fun editNotesSection() = SettingsSection(
        header = "Notes",
        footer = "Dashie can use this information to provide AI-powered assistance",
        items = listOf(
            SchemaItem.TextInput(
                id = "edit_notes",
                label = "Notes",
                settingKey = "family.editNotes",
                placeholder = "Optional notes about this family member",
                inputType = "multiline"
            )
        )
    )

    private fun editActionsSection(isNewMember: Boolean) = SettingsSection(
        items = listOfNotNull(
            SchemaItem.Action(
                id = "save_member",
                label = if (isNewMember) "Add Member" else "Save Changes",
                action = "saveFamilyMember"
            ),
            SchemaItem.Action(
                id = "cancel_edit",
                label = "Cancel",
                action = "cancelFamilyEdit"
            ),
            if (!isNewMember) SchemaItem.Action(
                id = "delete_member",
                label = "Delete Member",
                action = "deleteFamilyMember",
                destructive = true
            ) else null
        )
    )

    private fun colorDisplayName(hex: String): String {
        return when (hex) {
            "#FF6B6B" -> "Red"
            "#4ECDC4" -> "Teal"
            "#45B7D1" -> "Blue"
            "#FFA07A" -> "Salmon"
            "#98D8C8" -> "Mint"
            "#F7DC6F" -> "Yellow"
            "#BB8FCE" -> "Purple"
            "#85C1E2" -> "Sky Blue"
            "#F8B739" -> "Orange"
            "#52B788" -> "Green"
            else -> hex
        }
    }
}
