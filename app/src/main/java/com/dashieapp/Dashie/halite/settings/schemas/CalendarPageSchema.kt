package com.dashieapp.Dashie.halite.settings.schemas

import com.dashieapp.Dashie.edition.EditionSeams
import com.dashieapp.Dashie.halite.settings.data.CalendarAccount
import com.dashieapp.Dashie.halite.settings.data.CalendarInfo
import com.dashieapp.Dashie.halite.settings.data.FamilyMember
import com.dashieapp.Dashie.halite.settings.schema.Condition
import com.dashieapp.Dashie.halite.settings.schema.SchemaItem
import com.dashieapp.Dashie.halite.settings.schema.SchemaPickerOption
import com.dashieapp.Dashie.halite.settings.schema.SettingsPageSchema
import com.dashieapp.Dashie.halite.settings.schema.SettingsSection
import com.dashieapp.Dashie.halite.settings.schema.SubScreenSchema

/**
 * Schema definition for the Calendar settings page.
 *
 * Main page is a navigation hub matching the JS layout:
 * 1. Enable — master toggle
 * 2. Calendars & Accounts — Select Calendars, Categorize & Assign, Add/Remove accounts
 * 3. Calendar Options — Start Week On, Start Time to Scroll To, Calendar Editing
 *
 * "Select Calendars" is a separate pushed fragment with per-account sections
 * showing checkmark items with color dots.
 */
object CalendarPageSchema {

    private val calendarEnabled = Condition.IsTrue("calendar.enabled")

    /**
     * Main calendar settings page — navigation hub.
     */
    fun create(
        accounts: List<CalendarAccount> = emptyList(),
        isLoading: Boolean = false
    ): SettingsPageSchema {
        val activeCount = accounts.sumOf { a -> a.calendars.count { it.isActive } }
        val totalCount = accounts.sumOf { it.calendars.size }
        val calendarSummary = if (isLoading) "Loading..." else "$activeCount of $totalCount active"

        return SettingsPageSchema(
            id = "calendar",
            title = "Calendar",
            sections = listOfNotNull(
                enableSection(),
                signInRequiredSection(accounts),
                calendarsAndAccountsSection(calendarSummary, accounts),
                calendarOptionsSection()
            ),
            subScreens = mapOf(
                "calendar-start-week" to startWeekSubScreen(),
                "calendar-scroll-time" to scrollTimeSubScreen(),
                "calendar-hours-to-show" to hoursToShowSubScreen(),
                // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
                "calendar-write-access" to writeAccessSubScreen()
            )
        )
    }

    /**
     * Sign-in Required section — renders only when one or more connected
     * accounts has had its refresh token terminally revoked. Each row is a
     * tap-to-reauth action that pushes the user through the OAuth flow in
     * the WebView (mirrors the JS calendar settings page on
     * dev.dashieapp.com). When all accounts are healthy this returns null
     * and the section disappears entirely.
     */
    private fun signInRequiredSection(accounts: List<CalendarAccount>): SettingsSection? {
        val invalid = accounts.filter { it.authInvalid }
        if (invalid.isEmpty()) return null

        return SettingsSection(
            header = "Sign-in Required",
            footer = "These accounts can't load calendars until you re-sign in.",
            visibleWhen = calendarEnabled,
            items = invalid.map { account ->
                val providerLabel = when (account.provider) {
                    "microsoft" -> "Outlook"
                    "google" -> "Google"
                    else -> account.provider.replaceFirstChar { it.uppercase() }
                }
                val label = if (account.email.isNotEmpty()) {
                    "Re-sign in: ${account.email}"
                } else {
                    "Re-sign in: $providerLabel"
                }
                SchemaItem.Action(
                    id = "reauth_${account.accountType}_${account.provider}",
                    label = label,
                    // accountType already carries the microsoft- prefix when
                    // applicable (see loadCalendarDataFromJs); strip it here
                    // so the action string holds the bare slot id.
                    action = "reAuthCalendarAccount:${account.provider}:${account.accountType.removePrefix("microsoft-")}:${account.email}"
                )
            }
        )
    }

    /**
     * Select Calendars sub-page — pushed as a separate fragment.
     * Shows per-account sections with checkmark items and color dots.
     */
    fun createSelectCalendarsScreen(
        accounts: List<CalendarAccount> = emptyList(),
        isLoading: Boolean = false
    ) = SettingsPageSchema(
        id = "calendar_select",
        title = "Select Calendars",
        sections = if (isLoading) {
            listOf(SettingsSection(
                items = listOf(SchemaItem.Info(id = "loading", label = "Loading calendars..."))
            ))
        } else if (accounts.isEmpty()) {
            listOf(SettingsSection(
                items = listOf(SchemaItem.Info(id = "empty", label = "No calendar accounts connected."))
            ))
        } else {
            accounts.map { account -> accountCalendarSection(account) }
        }
    )

    // ── Main page sections ──────────────────────────────────────────

    private fun enableSection() = SettingsSection(
        items = listOf(
            SchemaItem.Toggle(
                id = "calendar_enabled",
                label = "Enable Calendar",
                settingKey = "calendar.enabled",
                sublabel = "Show calendar events on the dashboard",
                onChanged = "notifyCalendarConfigChanged"
            )
        )
    )

    private fun calendarsAndAccountsSection(calendarSummary: String, accounts: List<CalendarAccount>) = SettingsSection(
        header = "Calendars & Accounts",
        visibleWhen = calendarEnabled,
        items = listOfNotNull(
            SchemaItem.Navigation(
                id = "select_calendars",
                label = "Select Calendars",
                navigateTo = "ext:select_calendars",
                displayValue = calendarSummary
            ),
            SchemaItem.Navigation(
                id = "categorize_assign",
                label = "Categorize & Assign Calendars",
                navigateTo = "ext:categorize_assign"
            ),
            SchemaItem.Navigation(
                id = "add_account",
                label = "Add Calendar Account",
                navigateTo = "ext:add_calendar_account"
            ),
            if (accounts.size > 1) SchemaItem.Navigation(
                id = "remove_account",
                label = "Remove Calendar Account",
                navigateTo = "ext:remove_calendar_account"
            ) else null
        )
    )

    /**
     * Remove Calendar Account sub-page — lists connected accounts with remove actions.
     */
    fun createRemoveAccountScreen(accounts: List<CalendarAccount>) = SettingsPageSchema(
        id = "calendar_remove_account",
        title = "Remove Calendar Account",
        sections = listOf(
            SettingsSection(
                header = "Connected Accounts",
                footer = "Removing an account will also remove all its calendars from the dashboard.",
                items = if (accounts.isEmpty()) {
                    listOf(SchemaItem.Info(id = "no_accounts", label = "No accounts connected"))
                } else {
                    accounts.map { account ->
                        val providerLabel = when (account.provider) {
                            "microsoft" -> "Outlook"
                            "caldav", "icloud" -> "Apple iCloud"
                            "ha" -> "Home Assistant"
                            "google" -> "Google"
                            else -> account.provider.replaceFirstChar { it.uppercase() }
                        }
                        // Always show provider + email so users can distinguish same-email accounts
                        // (e.g., the same Gmail address as both Google and Apple iCloud).
                        val label = if (account.email.isNotEmpty()) {
                            "$providerLabel · ${account.email}"
                        } else {
                            "$providerLabel Calendar"
                        }

                        // Primary Google account can't be removed
                        val isPrimary = account.accountType == "primary" && account.provider == "google"
                        if (isPrimary) {
                            SchemaItem.Info(
                                id = "account_${account.accountType}_${account.provider}",
                                label = "$label (primary — cannot remove)"
                            )
                        } else {
                            SchemaItem.Action(
                                id = "remove_${account.accountType}_${account.provider}",
                                label = label,
                                action = "removeCalendarAccount:${account.provider}:${account.accountType}",
                                destructive = true
                            )
                        }
                    }
                }
            )
        )
    )

    /**
     * Add Calendar Account sub-page — shows provider options.
     */
    fun createAddAccountScreen() = SettingsPageSchema(
        id = "calendar_add_account",
        title = "Add Calendar Account",
        sections = listOf(
            SettingsSection(
                header = "Select Calendar Provider",
                // 🔴 The three CLOUD providers are built conditionally, not hidden by a
                // Condition: in an account-free edition the option must not EXIST (John's call,
                // 2026-08-02) — same shape as the first-run screen that no longer offers
                // "Create a Dashie Account". A `visibleWhen` would leave the rows in the schema
                // and the flows one state-change away from being reachable; this leaves nothing
                // to reach. Home Assistant is deliberately outside the gate: it is the
                // household's own entities and it is Chickadee's calendar source.
                items = buildList {
                    if (EditionSeams.hasCloudCalendarAccounts) {
                        add(
                            SchemaItem.Action(
                                id = "add_google",
                                label = "Google Calendar",
                                action = "addGoogleCalendarAccount"
                            )
                        )
                        add(
                            SchemaItem.Action(
                                id = "add_microsoft",
                                label = "Outlook Calendar",
                                action = "addMicrosoftCalendarAccount"
                            )
                        )
                    }
                    add(
                        SchemaItem.Action(
                            id = "add_home_assistant",
                            label = "Home Assistant",
                            action = "addHomeAssistantCalendars",
                            visibleWhen = Condition.IsTrue("ha.connected")
                        )
                    )
                    if (EditionSeams.hasCloudCalendarAccounts) {
                        add(
                            SchemaItem.Action(
                                id = "add_apple",
                                label = "Apple Calendar (iCloud)",
                                action = "addAppleCalendarAccount"
                            )
                        )
                    }
                }
            )
        )
    )

    private fun calendarOptionsSection() = SettingsSection(
        header = "Calendar Options",
        visibleWhen = calendarEnabled,
        items = listOf(
            SchemaItem.Navigation(
                id = "start_week_on",
                label = "Start Week On",
                navigateTo = "calendar-start-week",
                displayValueKey = "calendar.startWeekDisplay",
                directPicker = true
            ),
            SchemaItem.Navigation(
                id = "scroll_time",
                label = "Start Time to Scroll To",
                navigateTo = "calendar-scroll-time",
                displayValueKey = "calendar.scrollTimeDisplay",
                directPicker = true
            ),
            SchemaItem.Navigation(
                id = "hours_to_show",
                label = "Hours to Show",
                navigateTo = "calendar-hours-to-show",
                displayValueKey = "calendar.hoursToShowDisplay",
                directPicker = true
            ),
            // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
            // Mirrors the start_week_on / scroll_time navigation rows: a direct
            // picker into the write-access sub-screen, showing the current label.
            SchemaItem.Navigation(
                id = "write_access",
                label = "Calendar Editing",
                navigateTo = "calendar-write-access",
                displayValueKey = "calendar.writeAccessDisplay",
                directPicker = true
            )
        )
    )

    // ── Select Calendars — per-account section with checkmarks ──────

    private fun accountCalendarSection(account: CalendarAccount): SettingsSection {
        val providerLabel = when (account.provider) {
            "microsoft" -> "Outlook"
            "caldav", "icloud" -> "Apple iCloud"
            "ha" -> "Home Assistant"
            "google" -> "Google"
            else -> account.provider.replaceFirstChar { it.uppercase() }
        }
        val headerLabel = if (account.email.isNotEmpty()) {
            "$providerLabel — ${account.email}"
        } else {
            "$providerLabel Calendar"
        }

        val items = if (account.calendars.isEmpty()) {
            listOf(SchemaItem.Info(id = "no_cals_${account.accountType}", label = "No calendars found"))
        } else {
            // Sort: default calendar first (matches account email), then active, then inactive
            val sorted = account.calendars.sortedWith(compareByDescending<com.dashieapp.Dashie.halite.settings.data.CalendarInfo> {
                it.rawId == account.email // default calendar first
            }.thenByDescending { it.isActive })
            sorted.map { cal ->
                SchemaItem.Navigation(
                    id = "cal_${cal.prefixedId}",
                    label = cal.summary,
                    colorDot = if (cal.isActive) cal.backgroundColor else "#808080",
                    checked = cal.isActive,
                    navigateTo = "ext:toggle_calendar:${cal.prefixedId}"
                )
            }
        }

        return SettingsSection(header = headerLabel, items = items)
    }

    // ── Sub-screens ─────────────────────────────────────────────────

    private fun startWeekSubScreen() = SubScreenSchema(
        title = "Start Week On",
        parent = "calendar",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "start_week_picker",
                        label = "Start Week On",
                        settingKey = "calendar.startWeekOn",
                        options = listOf(
                            SchemaPickerOption("sunday", "Sunday"),
                            SchemaPickerOption("monday", "Monday"),
                            SchemaPickerOption("saturday", "Saturday")
                        ),
                        onChanged = "notifyCalendarConfigChanged"
                    )
                )
            )
        )
    )

    // NEEDS-DEVICE-VERIFICATION (calendar.writeAccess native UI, 2026-07-15) — built while owner away from devices
    // Mirrors startWeekSubScreen: a 4-option picker bound to the ACCOUNT-level
    // calendar.writeAccess key, firing the shared notifyCalendarConfigChanged
    // callback so the change broadcasts + auto-syncs to the cloud.
    private fun writeAccessSubScreen() = SubScreenSchema(
        title = "Calendar Editing",
        parent = "calendar",
        sections = listOf(
            SettingsSection(
                items = listOf(
                    SchemaItem.Picker(
                        id = "write_access_picker",
                        label = "Calendar Editing",
                        settingKey = "calendar.writeAccess",
                        options = listOf(
                            SchemaPickerOption("none", "None"),
                            SchemaPickerOption("touch", "Touch only"),
                            SchemaPickerOption("voice", "Voice only"),
                            SchemaPickerOption("both", "Both")
                        ),
                        onChanged = "notifyCalendarConfigChanged"
                    )
                )
            )
        )
    )

    // ── Categorize & Assign — calendar list with type indicators ───

    /**
     * Categorize & Assign sub-page — lists all active calendars with their
     * assignment type indicator (👥 Family, 👤 Assigned, ℹ️ Informational).
     * Tapping a calendar opens its detail screen.
     */
    fun createCategorizeAssignScreen(
        accounts: List<CalendarAccount>,
        assignmentTypes: Map<String, String>,
        members: List<FamilyMember>,
        displayNames: Map<String, String> = emptyMap(),
        colorOverrides: Map<String, String> = emptyMap()
    ) = SettingsPageSchema(
        id = "calendar_categorize",
        title = "Categorize & Assign",
        sections = if (accounts.isEmpty()) {
            listOf(SettingsSection(
                items = listOf(SchemaItem.Info(id = "empty", label = "No calendars to categorize."))
            ))
        } else {
            accounts.map { account ->
                val providerLabel = when (account.provider) {
                    "microsoft" -> "Outlook"
                    "ha" -> "Home Assistant"
                    "caldav", "icloud" -> "Apple iCloud"
                    "google" -> "Google"
                    else -> account.provider.replaceFirstChar { it.uppercase() }
                }
                val headerLabel = if (account.email.isNotEmpty()) "$providerLabel — ${account.email}" else "$providerLabel Calendar"

                SettingsSection(
                    header = headerLabel,
                    items = account.calendars.filter { it.isActive }.map { cal ->
                        val type = assignmentTypes[cal.prefixedId] ?: "family"
                        val typeIcon = when (type) {
                            "member" -> "👤"
                            "informational" -> "ℹ️"
                            else -> "👥"
                        }
                        val assignedInfo = if (type == "member") {
                            val assignedMembers = members.filter { m ->
                                m.assignedCalendars.contains(cal.prefixedId)
                            }
                            if (assignedMembers.isNotEmpty()) {
                                assignedMembers.joinToString(", ") { m -> m.shortName }
                            } else null
                        } else null

                        SchemaItem.Navigation(
                            id = "assign_${cal.prefixedId}",
                            label = displayNames[cal.prefixedId] ?: cal.summary,
                            sublabel = assignedInfo,
                            displayValue = typeIcon,
                            colorDot = colorOverrides[cal.prefixedId] ?: cal.backgroundColor,
                            navigateTo = "ext:calendar_detail:${cal.prefixedId}"
                        )
                    }.ifEmpty {
                        listOf(SchemaItem.Info(id = "no_active_${account.accountType}", label = "No active calendars"))
                    }
                )
            }
        }
    )

    /**
     * Per-calendar detail screen — type picker + member assignment + tags.
     */
    // Color palette for calendar color picker
    private val COLOR_PALETTE = listOf(
        "#4285F4" to "Blue",
        "#EA4335" to "Red",
        "#34A853" to "Green",
        "#FBBC05" to "Yellow",
        "#FF6D01" to "Orange",
        "#46BDC6" to "Teal",
        "#7B61FF" to "Purple",
        "#E91E63" to "Pink",
        "#795548" to "Brown",
        "#607D8B" to "Gray"
    )

    fun createCalendarDetailScreen(
        calendar: CalendarInfo,
        currentType: String,
        members: List<FamilyMember>,
        tags: List<String>,
        displayNameOverride: String? = null,
        colorOverride: String? = null
    ): SettingsPageSchema {
        val isMemberType = currentType == "member"

        val sections = mutableListOf<SettingsSection>()

        // Calendar Name
        val currentDisplayName = displayNameOverride ?: calendar.summary
        sections.add(SettingsSection(
            header = "Calendar Name",
            footer = if (displayNameOverride != null) "Source: ${calendar.summary}" else null,
            items = listOf(
                SchemaItem.TextInput(
                    id = "calendar_display_name",
                    label = "Display Name",
                    settingKey = "calendar.displayName.${calendar.prefixedId}",
                    placeholder = calendar.summary
                )
            )
        ))

        // Color
        val currentColor = colorOverride ?: calendar.backgroundColor
        val displayValueForColor = when {
            colorOverride == null -> "Default"
            else -> COLOR_PALETTE.find { it.first.equals(currentColor, ignoreCase = true) }?.second ?: "Custom"
        }
        sections.add(SettingsSection(
            header = "Color",
            items = listOf(
                SchemaItem.Navigation(
                    id = "calendar_color",
                    label = "Calendar Color",
                    navigateTo = "ext:calendar_color_picker:${calendar.prefixedId}",
                    colorDot = currentColor,
                    displayValue = displayValueForColor
                )
            )
        ))

        // Calendar Type
        sections.add(SettingsSection(
            header = "Calendar Type",
            items = listOf(
                SchemaItem.Navigation(
                    id = "type_selector",
                    label = "Type",
                    navigateTo = "ext:calendar_type_selector:${calendar.prefixedId}",
                    displayValue = when (currentType) {
                        "member" -> "👤 Assigned"
                        "informational" -> "ℹ️ Informational"
                        else -> "👥 Family"
                    }
                )
            )
        ))

        // Adding Events — "may Dashie (incl. voice) create events here?" User override
        // else provider access-role default; provider read-only calendars render the
        // row disabled (design 20260713_VOICE_CALENDAR_CRUD_DESIGN.md §2.7).
        sections.add(SettingsSection(
            header = "Adding Events",
            footer = if (calendar.providerReadOnly)
                "This calendar is read-only at the provider — events can't be added"
            // BRAND-TRIAGED (A, 2026-08-05): brand-NEUTRAL rather than brand-resolved. This is a
            // Context-free schema object invoked as a `::create` method reference, so threading a
            // Context in would change its signature and its test — same call made in pass 1 for
            // AdvancedPageSchema / VoiceAiPageSchema.
            else "This app (including voice) may create events on this calendar",
            items = listOf(
                if (calendar.providerReadOnly) {
                    SchemaItem.Info(id = "calendar_editable", label = "✏️ Allow adding events")
                } else {
                    SchemaItem.Navigation(
                        id = "calendar_editable",
                        label = "✏️ Allow adding events",
                        checked = calendar.editable,
                        navigateTo = "ext:toggle_calendar_editable:${calendar.prefixedId}"
                    )
                }
            )
        ))

        // Member Assignment (only if type = member)
        if (isMemberType) {
            sections.add(SettingsSection(
                header = "Assigned Members",
                items = if (members.isEmpty()) {
                    listOf(SchemaItem.Info(id = "no_members", label = "No family members"))
                } else {
                    members.map { member ->
                        val isAssigned = member.assignedCalendars.contains(calendar.prefixedId)
                        SchemaItem.Navigation(
                            id = "member_${member.id}",
                            label = member.shortName,
                            colorDot = member.assignedColor,
                            checked = isAssigned,
                            navigateTo = "ext:toggle_member_calendar:${calendar.prefixedId}:${member.id}"
                        )
                    }
                }
            ))
        }

        // Tags
        sections.add(SettingsSection(
            header = "Calendar Tags",
            footer = "Tags help the AI understand what kind of events are in this calendar",
            items = if (tags.isEmpty()) {
                listOf(
                    SchemaItem.Info(id = "no_tags", label = "No tags added"),
                    SchemaItem.Action(id = "add_tag", label = "+ Add Tag", action = "addCalendarTag:${calendar.prefixedId}")
                )
            } else {
                tags.map { tag ->
                    SchemaItem.Action(
                        id = "tag_$tag",
                        label = tag,
                        action = "removeCalendarTag:${calendar.prefixedId}:$tag"
                    )
                } + SchemaItem.Action(id = "add_tag", label = "+ Add Tag", action = "addCalendarTag:${calendar.prefixedId}")
            }
        ))

        return SettingsPageSchema(
            id = "calendar_detail",
            title = displayNameOverride ?: calendar.summary,
            sections = sections
        )
    }

    /**
     * Color picker screen — shows "Default" (source color from calendar provider)
     * at the top, followed by preset color swatches. Selecting "Default" clears
     * any user override and reverts to the provider's color.
     *
     * @param calendarId prefixedId of the calendar
     * @param currentColor the currently displayed color (override or source)
     * @param sourceColor the calendar's source/default color from the provider
     * @param hasOverride true if a user override is currently applied
     */
    fun createColorPickerScreen(
        calendarId: String,
        currentColor: String,
        sourceColor: String,
        hasOverride: Boolean
    ) = SettingsPageSchema(
        id = "calendar_color_picker",
        title = "Calendar Color",
        sections = listOf(
            SettingsSection(
                items = buildList {
                    add(SchemaItem.Navigation(
                        id = "color_default",
                        label = "Default",
                        colorDot = sourceColor,
                        checked = !hasOverride,
                        // Use a sentinel value; Kotlin handler clears the override.
                        navigateTo = "ext:set_calendar_color:$calendarId:default"
                    ))
                    addAll(COLOR_PALETTE.map { (color, label) ->
                        SchemaItem.Navigation(
                            id = "color_${color.removePrefix("#")}",
                            label = label,
                            colorDot = color,
                            checked = hasOverride && color.equals(currentColor, ignoreCase = true),
                            navigateTo = "ext:set_calendar_color:$calendarId:$color"
                        )
                    })
                }
            )
        )
    )

    /**
     * Calendar type selector — family/assigned/informational picker.
     */
    fun createTypeSelector(calendarId: String, currentType: String) = SettingsPageSchema(
        id = "calendar_type_selector",
        title = "Calendar Type",
        sections = listOf(
            SettingsSection(
                footer = "Choose how this calendar's events are treated on the dashboard and in voice responses.",
                items = listOf(
                    SchemaItem.Navigation(
                        id = "type_family",
                        label = "👥 Family",
                        sublabel = "Contains events for the entire family",
                        navigateTo = "ext:set_calendar_type:$calendarId:family"
                    ),
                    SchemaItem.Navigation(
                        id = "type_member",
                        label = "👤 Assigned",
                        sublabel = "Contains events for specific family member(s)",
                        navigateTo = "ext:set_calendar_type:$calendarId:member"
                    ),
                    SchemaItem.Navigation(
                        id = "type_informational",
                        label = "ℹ️ Informational",
                        sublabel = "Contains informational events (holidays, sports schedules)",
                        navigateTo = "ext:set_calendar_type:$calendarId:informational"
                    )
                )
            )
        )
    )

    private fun scrollTimeSubScreen() = SubScreenSchema(
        title = "Start Time to Scroll To",
        parent = "calendar",
        sections = listOf(
            SettingsSection(
                footer = "Calendar view will scroll to this time when opened",
                items = listOf(
                    SchemaItem.Picker(
                        id = "scroll_time_picker",
                        label = "Start Time",
                        settingKey = "calendar.scrollTime",
                        options = (0..23).map { hour ->
                            val label = when {
                                hour == 0 -> "12:00 AM"
                                hour < 12 -> "$hour:00 AM"
                                hour == 12 -> "12:00 PM"
                                else -> "${hour - 12}:00 PM"
                            }
                            SchemaPickerOption(hour.toString(), label)
                        },
                        onChanged = "notifyCalendarConfigChanged"
                    )
                )
            )
        )
    )

    private fun hoursToShowSubScreen() = SubScreenSchema(
        title = "Hours to Show",
        parent = "calendar",
        sections = listOf(
            SettingsSection(
                footer = "How many hours fill the calendar's height. Auto keeps the default density; a number makes exactly that many hours fit (the full day stays scrollable).",
                items = listOf(
                    SchemaItem.Picker(
                        id = "hours_to_show_picker",
                        label = "Hours to Show",
                        settingKey = "calendar.hoursToShow",
                        options = listOf(
                            SchemaPickerOption("auto", "Auto (fit to screen)"),
                            SchemaPickerOption("6", "6 hours"),
                            SchemaPickerOption("8", "8 hours"),
                            SchemaPickerOption("10", "10 hours"),
                            SchemaPickerOption("12", "12 hours"),
                            SchemaPickerOption("16", "16 hours"),
                            SchemaPickerOption("24", "24 hours")
                        ),
                        onChanged = "notifyCalendarConfigChanged"
                    )
                )
            )
        )
    )
}
