package com.dashieapp.Dashie.halite.settings.pages.calendar

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity

internal fun SettingsActivity.createCalendarFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    calendarLoading = true
    calendarAccounts = emptyList()
    loadCalendarDataFromJs()
    wireCalendarBridgeCallbacks()

    // Register HA connection status for conditional visibility
    val connPrefs = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(this)
    schemaContext.valueProvider.registerBoolean("ha.connected",
        getter = { connPrefs.haUrl.isNotEmpty() },
        setter = { /* read-only */ }
    )

    // Register action callbacks.
    //
    // The three CLOUD providers are registered only where they exist as a capability
    // (JS_KOTLIN_CONTRACTS-style capability gate — see EditionSeams.hasCloudCalendarAccounts).
    // The schema already omits their rows in an account-free edition, so this is belt AND
    // braces on purpose: an unregistered action is a loud registry miss rather than a working
    // path to an account flow the edition does not have. Home Assistant is ungated — it is the
    // household's own entities and Chickadee's calendar source.
    // ⚠️ Through the seam, not a runtime `if`, and the difference is the whole point: the gate
    // below used to be `if (hasCloudCalendarAccounts) { … }`, which was correct at runtime and
    // still COMPILED the three flows into the Chickadee artifact. The implementations now live
    // in `src/dashie/java` and the seam returns null here, so they are absent rather than
    // unreachable. Registering nothing means an unregistered action is a loud registry miss.
    com.dashieapp.Dashie.edition.EditionSeams.cloudCalendarFlows
        ?.registerAddAccountCallbacks(this)
    schemaContext.callbackRegistry.register("addHomeAssistantCalendars") { importHomeAssistantCalendars() }

    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.create(
                accounts = calendarAccounts,
                isLoading = calendarLoading
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when {
                target == "ext:select_calendars" -> {
                    showSelectCalendarsScreen()
                    true
                }
                target == "ext:categorize_assign" -> {
                    showCategorizeAssignScreen()
                    true
                }
                target == "ext:add_calendar_account" -> {
                    showAddCalendarAccountScreen()
                    true
                }
                target == "ext:remove_calendar_account" -> {
                    showRemoveCalendarAccountScreen()
                    true
                }
                else -> false
            }
        }
    )
}

internal fun SettingsActivity.showSelectCalendarsScreen() {
    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.createSelectCalendarsScreen(
                accounts = calendarAccounts,
                isLoading = calendarLoading
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            if (target.startsWith("ext:toggle_calendar:")) {
                // Format: ext:toggle_calendar:<prefixedId>
                val prefixedId = target.removePrefix("ext:toggle_calendar:")
                // Look up current state from loaded accounts
                val currentlyOn = calendarAccounts.flatMap { it.calendars }
                    .find { it.prefixedId == prefixedId }?.isActive ?: false
                toggleCalendar(prefixedId, !currentlyOn)
                // Update local state and refresh
                calendarAccounts = calendarAccounts.map { account ->
                    account.copy(calendars = account.calendars.map { cal ->
                        if (cal.prefixedId == prefixedId) cal.copy(isActive = !currentlyOn) else cal
                    })
                }
                supportFragmentManager.fragments.filterIsInstance<
                    com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().lastOrNull()?.refresh()
                true
            } else false
        }
    )
    showFragment(fragment, "calendar_select")
}

internal fun SettingsActivity.showRemoveCalendarAccountScreen() {
    // Register remove action callbacks for each account
    calendarAccounts.forEach { account ->
        val actionName = "removeCalendarAccount:${account.provider}:${account.accountType}"
        schemaContext.callbackRegistry.register(actionName) {
            removeCalendarAccount(account.provider, account.accountType, account.email)
        }
    }

    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.createRemoveAccountScreen(calendarAccounts)
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry
    )
    showFragment(fragment, "calendar_remove_account")
}

internal fun SettingsActivity.showAddCalendarAccountScreen() {
    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = { com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.createAddAccountScreen() },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry
    )
    showFragment(fragment, "calendar_add_account")
}
internal fun SettingsActivity.showCategorizeAssignScreen() {
    // Push the fragment immediately with whatever data we have, then refresh async
    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.createCategorizeAssignScreen(
                accounts = calendarAccounts,
                assignmentTypes = calendarAssignmentTypes,
                members = familyMembers,
                displayNames = calendarDisplayNames,
                colorOverrides = calendarColorOverrides
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            android.util.Log.i("CalAssign", "Categorize nav callback: target=$target")
            when {
                target.startsWith("ext:calendar_detail:") -> {
                    val calId = target.removePrefix("ext:calendar_detail:")
                    android.util.Log.i("CalAssign", "Opening calendar detail: calId=$calId")
                    showCalendarDetailScreen(calId)
                    true
                }
                else -> false
            }
        }
    )
    showFragment(fragment, "calendar_categorize")

    // Load fresh data in background, then refresh the fragment
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    if (!cs) throw new Error('Calendar service not initialized');
                    const assignments = await cs.getCalendarAssignments();
                    const tags = await cs.getAllCalendarTags();

                    const fSvc = window.familyService;
                    const members = fSvc ? await fSvc.listMembers({ forceRefresh: true }) : [];

                    const result = {
                        assignments: Object.fromEntries(assignments || new Map()),
                        tags: Object.fromEntries(tags || new Map()),
                        display_names: cs.calendarDisplayNames || {},
                        color_overrides: cs.calendarColorOverrides || {},
                        members: members
                    };
                    window.DashieNative.onCalendarAssignmentDataLoaded(JSON.stringify(result));
                } catch (e) {
                    window.DashieNative.onCalendarError(e.message || 'Failed to load assignments');
                }
            })()
        """.trimIndent(), null)
    }
}

internal fun SettingsActivity.showCalendarDetailScreen(calendarId: String) {
    val calendar = calendarAccounts.flatMap { it.calendars }.find { it.prefixedId == calendarId } ?: return
    val currentType = calendarAssignmentTypes[calendarId] ?: "family"
    val tags = calendarTags[calendarId] ?: emptyList()

    // Register tag action callbacks
    schemaContext.callbackRegistry.register("addCalendarTag:$calendarId") {
        showAddTagDialog(calendarId)
    }
    tags.forEach { tag ->
        schemaContext.callbackRegistry.register("removeCalendarTag:$calendarId:$tag") {
            removeCalendarTag(calendarId, tag)
        }
    }

    // Register display name value provider (reads from/writes to calendarDisplayNames)
    val displayNameKey = "calendar.displayName.${calendar.prefixedId}"
    schemaContext.valueProvider.registerString(displayNameKey,
        getter = { calendarDisplayNames[calendarId] ?: calendar.summary },
        setter = { newName ->
            val trimmed = newName.trim()
            if (trimmed.isEmpty() || trimmed == calendar.summary) {
                calendarDisplayNames.remove(calendarId)
            } else {
                calendarDisplayNames[calendarId] = trimmed
            }
            // Persist via JS bridge
            saveCalendarDisplayName(calendarId, if (trimmed == calendar.summary) null else trimmed)
        }
    )

    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.createCalendarDetailScreen(
                calendar = calendar,
                currentType = calendarAssignmentTypes[calendarId] ?: "family",
                members = familyMembers,
                tags = calendarTags[calendarId] ?: emptyList(),
                displayNameOverride = calendarDisplayNames[calendarId],
                colorOverride = calendarColorOverrides[calendarId]
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when {
                target.startsWith("ext:calendar_type_selector:") -> {
                    val calId = target.removePrefix("ext:calendar_type_selector:")
                    showCalendarTypeSelector(calId)
                    true
                }
                target.startsWith("ext:calendar_color_picker:") -> {
                    val calId = target.removePrefix("ext:calendar_color_picker:")
                    showCalendarColorPicker(calId)
                    true
                }
                target.startsWith("ext:toggle_member_calendar:") -> {
                    val parts = target.removePrefix("ext:toggle_member_calendar:").split(":")
                    val calId = parts.getOrElse(0) { "" }
                    val memberId = parts.getOrElse(1) { "" }
                    val member = familyMembers.find { it.id == memberId }
                    val currentlyAssigned = member?.assignedCalendars?.contains(calId) == true
                    toggleMemberCalendarAssignment(calId, memberId, !currentlyAssigned)
                    true
                }
                target.startsWith("ext:toggle_calendar_editable:") -> {
                    // "Allow adding events" (design 20260713 §2.7) — flip the explicit
                    // override; the JS write broadcasts + the reload re-derives `editable`.
                    val calId = target.removePrefix("ext:toggle_calendar_editable:")
                    val current = calendarAccounts.flatMap { it.calendars }
                        .find { it.prefixedId == calId }?.editable ?: true
                    setCalendarEditable(calId, !current)
                    true
                }
                else -> false
            }
        }
    )
    showFragment(fragment, "calendar_detail")
}
