package com.dashieapp.Dashie.halite.settings.pages.calendar

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity

internal fun SettingsActivity.showCalendarTypeSelector(calendarId: String) {
    val currentType = calendarAssignmentTypes[calendarId] ?: "family"
    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.createTypeSelector(calendarId, currentType)
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            if (target.startsWith("ext:set_calendar_type:")) {
                val parts = target.removePrefix("ext:set_calendar_type:").split(":")
                val calId = parts.getOrElse(0) { "" }
                val newType = parts.getOrElse(1) { "family" }
                setCalendarAssignmentType(calId, newType)
                true
            } else false
        }
    )
    showFragment(fragment, "calendar_type_selector")
}

internal fun SettingsActivity.showCalendarColorPicker(calendarId: String) {
    // Source color is the calendar's color from the provider (before any override).
    val sourceColor = calendarAccounts.flatMap { it.calendars }.find { it.prefixedId == calendarId }?.backgroundColor
        ?: "#607D8B"
    val hasOverride = calendarColorOverrides.containsKey(calendarId)
    val currentColor = calendarColorOverrides[calendarId] ?: sourceColor

    val fragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.CalendarPageSchema.createColorPickerScreen(
                calendarId = calendarId,
                currentColor = currentColor,
                sourceColor = sourceColor,
                hasOverride = hasOverride
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            if (target.startsWith("ext:set_calendar_color:")) {
                val parts = target.removePrefix("ext:set_calendar_color:").split(":")
                val calId = parts.getOrElse(0) { "" }
                val color = parts.getOrElse(1) { "" }
                if (color == "default") {
                    calendarColorOverrides.remove(calId)
                    saveCalendarColorOverride(calId, "")
                } else {
                    calendarColorOverrides[calId] = color
                    saveCalendarColorOverride(calId, color)
                }
                // Pop back to detail screen which will re-render with new color
                supportFragmentManager.popBackStack()
                true
            } else false
        }
    )
    showFragment(fragment, "calendar_color_picker")
}

internal fun SettingsActivity.saveCalendarDisplayName(calendarId: String, displayName: String?) {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    val nameArg = if (displayName != null) "'$displayName'" else "null"
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    if (cs) await cs.setCalendarDisplayName('$calendarId', $nameArg);
                } catch (e) { console.warn('Failed to save display name', e); }
            })()
        """.trimIndent(), null)
    }
}

internal fun SettingsActivity.saveCalendarColorOverride(calendarId: String, color: String) {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    if (cs) await cs.setCalendarColorOverride('$calendarId', '$color');
                } catch (e) { console.warn('Failed to save color override', e); }
            })()
        """.trimIndent(), null)
    }
}

internal fun SettingsActivity.setCalendarAssignmentType(calendarId: String, newType: String) {
    calendarAssignmentTypes[calendarId] = newType
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    await cs.setCalendarAssignmentType('$calendarId', '$newType');
                } catch (e) {
                    window.DashieNative.onCalendarError(e.message || 'Failed to set type');
                }
            })()
        """.trimIndent(), null)
    }
    // Pop back to detail screen and refresh
    if (supportFragmentManager.backStackEntryCount > 0) {
        supportFragmentManager.popBackStack()
    }
    runOnUiThread {
        supportFragmentManager.fragments.filterIsInstance<
            com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
    }
}

internal fun SettingsActivity.toggleMemberCalendarAssignment(calendarId: String, memberId: String, assign: Boolean) {
    android.util.Log.i("CalAssign", "toggleMemberCalendar: calId=$calendarId, memberId=$memberId, assign=$assign")
    val memberBefore = familyMembers.find { it.id == memberId }
    android.util.Log.i("CalAssign", "  member before: ${memberBefore?.shortName}, calendars=${memberBefore?.assignedCalendars}")
    // Optimistically update local state so the checkmark toggles immediately
    familyMembers = familyMembers.map { member ->
        if (member.id == memberId) {
            val updatedCalendars = if (assign) {
                member.assignedCalendars + calendarId
            } else {
                member.assignedCalendars - calendarId
            }
            member.copy(assignedCalendars = updatedCalendars)
        } else member
    }
    val memberAfter = familyMembers.find { it.id == memberId }
    android.util.Log.i("CalAssign", "  member after: ${memberAfter?.shortName}, calendars=${memberAfter?.assignedCalendars}")
    // Post refresh to next frame so the click handler completes first — only refresh top fragment
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        val frags = supportFragmentManager.fragments.filterIsInstance<
            com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>()
        android.util.Log.i("CalAssign", "  refreshing top fragment after toggle (${frags.size} total)")
        frags.lastOrNull()?.refresh()
    }

    // Persist via JS bridge in background using window.familyService (already initialized)
    val wv = SettingsActivity.webViewRef?.get()
    if (wv == null) {
        android.util.Log.e("CalAssign", "  WebView ref is NULL — cannot persist toggle!")
        return
    }
    val method = if (assign) "addCalendarToMember" else "removeCalendarFromMember"
    android.util.Log.i("CalAssign", "  JS bridge call: svc.$method('$memberId', '$calendarId'), webView=$wv")
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    console.log('[CalAssign] JS: starting $method for $memberId on $calendarId');
                    const svc = window.familyService;
                    console.log('[CalAssign] JS: familyService exists:', !!svc);
                    if (!svc) throw new Error('Family service not initialized');
                    console.log('[CalAssign] JS: calling svc.$method...');
                    const result = await svc.$method('$memberId', '$calendarId');
                    console.log('[CalAssign] JS: $method completed, assigned_calendars:', JSON.stringify(result.assigned_calendars));
                } catch (e) {
                    console.error('[CalAssign] JS: $method FAILED:', e.message, e.stack);
                    window.DashieNative.onCalendarError(e.message || 'Failed to update assignment');
                }
            })()
        """.trimIndent(), null)
    }
}

internal fun SettingsActivity.showAddTagDialog(calendarId: String) {
    val input = android.widget.EditText(this)
    input.hint = "e.g. soccer, music, dance"
    input.setPadding(48, 32, 48, 32)
    input.filters = arrayOf(android.text.InputFilter.LengthFilter(30))

    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
    dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Add Tag"
    val scrollView = dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage)?.parent as? android.view.ViewGroup
    val messageView = dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage)
    if (scrollView != null && messageView != null) {
        val idx = scrollView.indexOfChild(messageView)
        scrollView.removeView(messageView)
        scrollView.addView(input, idx)
    }
    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView).setCancelable(true).create()
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).apply {
        text = "Cancel"; setOnClickListener { dialog.dismiss() }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
        text = "Add"; setOnClickListener {
            dialog.dismiss()
            val tag = input.text.toString().trim().lowercase()
            if (tag.isNotEmpty()) {
                addCalendarTag(calendarId, tag)
            }
        }
    }
    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
}

internal fun SettingsActivity.addCalendarTag(calendarId: String, tag: String) {
    val current = calendarTags[calendarId]?.toMutableList() ?: mutableListOf()
    if (!current.contains(tag)) {
        current.add(tag)
        calendarTags[calendarId] = current
    }
    val wv = SettingsActivity.webViewRef?.get() ?: return
    val tagsJson = org.json.JSONArray(current).toString().replace("'", "\\'")
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    await cs.setCalendarTags('$calendarId', JSON.parse('$tagsJson'));
                } catch (e) {
                    window.DashieNative.onCalendarError(e.message || 'Failed to add tag');
                }
            })()
        """.trimIndent(), null)
    }
    // Register the new remove callback and refresh
    schemaContext.callbackRegistry.register("removeCalendarTag:$calendarId:$tag") {
        removeCalendarTag(calendarId, tag)
    }
    runOnUiThread {
        supportFragmentManager.fragments.filterIsInstance<
            com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
    }
}

internal fun SettingsActivity.removeCalendarTag(calendarId: String, tag: String) {
    val current = calendarTags[calendarId]?.toMutableList() ?: return
    current.remove(tag)
    calendarTags[calendarId] = current
    val wv = SettingsActivity.webViewRef?.get() ?: return
    val tagsJson = org.json.JSONArray(current).toString().replace("'", "\\'")
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    await cs.setCalendarTags('$calendarId', JSON.parse('$tagsJson'));
                } catch (e) {
                    window.DashieNative.onCalendarError(e.message || 'Failed to remove tag');
                }
            })()
        """.trimIndent(), null)
    }
    runOnUiThread {
        supportFragmentManager.fragments.filterIsInstance<
            com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
    }
}

/** "Allow adding events" (design 20260713 §2.7): store the explicit per-calendar
 *  override via the JS service (edge op set_calendar_editable + broadcast). The
 *  fragment refresh re-reads `editable` from the reloaded calendar list. */
internal fun SettingsActivity.setCalendarEditable(prefixedId: String, editable: Boolean) {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    if (!cs?.setCalendarEditable) throw new Error('Calendar service not initialized');
                    await cs.setCalendarEditable('$prefixedId', $editable);
                    window.DashieNative.onCalendarToggled();
                } catch (e) {
                    window.DashieNative.onCalendarError(e.message || 'Failed to set editable flag');
                }
            })()
        """.trimIndent(), null)
    }
}

internal fun SettingsActivity.toggleCalendar(prefixedId: String, enabled: Boolean) {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    // Parse accountType from prefixedId (format: "accountType-calendarId")
    val parts = prefixedId.split("-", limit = 2)
    val accountType = parts.getOrElse(0) { "primary" }
    val calendarId = parts.getOrElse(1) { prefixedId }
    val method = if (enabled) "enableCalendar" else "disableCalendar"

    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const cs = window.calendarService;
                    if (!cs) throw new Error('Calendar service not initialized');
                    await cs.$method('$accountType', '$calendarId');
                    window.DashieNative.onCalendarToggled();
                } catch (e) {
                    window.DashieNative.onCalendarError(e.message || 'Failed to toggle calendar');
                }
            })()
        """.trimIndent(), null)
    }
}

