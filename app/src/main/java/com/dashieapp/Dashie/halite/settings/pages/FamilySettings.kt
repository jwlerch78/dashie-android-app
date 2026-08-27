package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.showStyledConfirmDialog

internal fun SettingsActivity.createFamilyFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    // Reset state and trigger load
    familyLoading = true
    familyMembers = emptyList()
    loadFamilyMembersFromJs()
    loadFamilyNameFromJs()

    // Wire JS bridge callbacks to update data and refresh fragment
    wireFamilyBridgeCallbacks()

    // Register action callbacks for family operations
    schemaContext.callbackRegistry.register("addFamilyMember") { showAddMemberForm() }
    schemaContext.callbackRegistry.register("saveFamilyMember") { saveFamilyMember() }
    schemaContext.callbackRegistry.register("deleteFamilyMember") { deleteFamilyMember() }
    schemaContext.callbackRegistry.register("cancelFamilyEdit") {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        }
    }
    // Device linking actions
    schemaContext.callbackRegistry.register("sendDeviceInvite") { sendDeviceInvite() }
    schemaContext.callbackRegistry.register("resendDeviceInvite") { sendDeviceInvite() }
    schemaContext.callbackRegistry.register("cancelDeviceInvite") { cancelDeviceInvite() }
    schemaContext.callbackRegistry.register("unlinkDevice") { unlinkDevice() }
    schemaContext.callbackRegistry.register("editDeviceName") { editDeviceName() }
    schemaContext.callbackRegistry.register("unlinkGoogleAccount") { unlinkGoogleAccount() }
    // Timer/reminder push toggle — immediate-apply on change (writes
    // member_notification_preferences.notify_on_reminder for the edited member).
    schemaContext.callbackRegistry.register("applyReminderAlertPref") {
        val id = editingMemberId
        if (id != null) {
            com.dashieapp.Dashie.halite.notify.NotificationPrefsClient.setReminderPref(
                id,
                com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.notifyOnReminder
            )
        }
    }

    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.FamilyPageSchema.create(
                members = familyMembers,
                isLoading = familyLoading
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when {
                target.startsWith("ext:edit_member:") -> {
                    val memberId = target.removePrefix("ext:edit_member:")
                    showFamilyMemberEditor(memberId)
                    true
                }
                target == "ext:edit_family_name" -> {
                    showEditFamilyNameDialog()
                    true
                }
                target == "ext:edit_family_zip" -> {
                    showEditFamilyZipDialog()
                    true
                }
                else -> false
            }
        }
    )
}

/** Load family members from Supabase via JS bridge */
internal fun SettingsActivity.loadFamilyMembersFromJs() {
    val wv = SettingsActivity.webViewRef?.get() ?: run {
        familyLoading = false
        return
    }
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const svc = window.familyService;
                    if (!svc) throw new Error('Family service not initialized');
                    const members = await svc.listMembers({ forceRefresh: true });
                    window.DashieNative.onFamilyMembersLoaded(JSON.stringify(members));
                } catch (e) {
                    window.DashieNative.onFamilyError(e.message || 'Failed to load members');
                }
            })()
        """.trimIndent(), null)
    }
}

/** Wire the JS bridge data delegate callbacks for family operations */
internal fun SettingsActivity.wireFamilyBridgeCallbacks() {
    // Access the delegate via the static jsBridge reference
    val delegate = SettingsActivity.jsBridgeRef?.settingsDataDelegate ?: return

    delegate.onFamilyMembersLoaded = { members ->
        familyMembers = members
        familyLoading = false
        // Also update edit state if we're editing a member that was reloaded
        val editId = editingMemberId
        if (editId != null) {
            val updated = members.find { m -> m.id == editId }
            if (updated != null) {
                com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.loadFrom(updated)
            }
        }
        runOnUiThread {
            // Refresh all schema fragments (list + edit if on stack)
            supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
        }
    }

    delegate.onFamilyMemberSaved = { member ->
        // Update or add the member in our local list
        val updated = familyMembers.toMutableList()
        val idx = updated.indexOfFirst { it.id == member.id }
        if (idx >= 0) updated[idx] = member else updated.add(member)
        familyMembers = updated
        runOnUiThread {
            val frag = supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().firstOrNull()
            frag?.refresh()
        }
    }

    delegate.onFamilyMemberDeleted = { memberId ->
        familyMembers = familyMembers.filter { it.id != memberId }
        runOnUiThread {
            // Pop back to member list if we're on the edit screen
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
            val frag = supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().firstOrNull()
            frag?.refresh()
        }
    }

    delegate.onFamilyError = { message ->
        familyLoading = false
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            val frag = supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().firstOrNull()
            frag?.refresh()
        }
    }

    delegate.onFamilyNameLoaded = { name ->
        com.dashieapp.Dashie.halite.settings.schema.wiring
            .ConnectionSettingsWiring.FamilyInfoState.familyName = name
        runOnUiThread {
            supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
        }
    }
}

/** Load the family name from the JS settings store (family.familyName) */
internal fun SettingsActivity.loadFamilyNameFromJs() {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (() => {
                try {
                    const n = (window.settingsStore && window.settingsStore.get('family.familyName')) || '';
                    window.DashieNative.onFamilyNameLoaded(String(n));
                } catch (e) {
                    window.DashieNative.onFamilyNameLoaded('');
                }
            })()
        """.trimIndent(), null)
    }
}

/**
 * Text-input dialog to edit the family name. Writes to the JS settingsStore
 * key family.familyName, which persists to Supabase and updates the dashboard
 * header. Mirrors the editDeviceName() dialog pattern.
 */
internal fun SettingsActivity.showEditFamilyNameDialog() {
    val current = com.dashieapp.Dashie.halite.settings.schema.wiring
        .ConnectionSettingsWiring.FamilyInfoState.familyName
    val input = android.widget.EditText(this)
    input.setText(current)
    input.hint = "Family name"
    input.setPadding(48, 32, 48, 32)

    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
    dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Edit Family Name"
    // dialogMessage is a (Material)TextView; the ScrollView wrapping it has no
    // id. Fetch the TextView by its real type, then reach the ScrollView via
    // .parent — findViewById<ScrollView>(R.id.dialogMessage) would throw
    // ClassCastException (the view is a TextView, not a ScrollView).
    val messageView = dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage)
    val scrollView = messageView?.parent as? android.view.ViewGroup
    if (scrollView != null && messageView != null) {
        val idx = scrollView.indexOfChild(messageView)
        scrollView.removeView(messageView)
        scrollView.addView(input, idx)
    }

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
        text = "Save"
        setOnClickListener {
            dialog.dismiss()
            val newName = input.text.toString().trim()
            // Optimistically update the cached value so the row refreshes now
            com.dashieapp.Dashie.halite.settings.schema.wiring
                .ConnectionSettingsWiring.FamilyInfoState.familyName = newName
            val escaped = newName.replace("\\", "\\\\").replace("'", "\\'")
            SettingsActivity.webViewRef?.get()?.let { wv ->
                wv.post {
                    // settingsStore.set() only updates memory — must also call
                    // save() to persist (survives reload) and
                    // syncFamilyNameToLocalStorage() to update the
                    // 'dashie-family-name' key the header widget reads.
                    wv.evaluateJavascript("""
                        (async () => {
                            try {
                                if (!window.settingsStore) return;
                                window.settingsStore.set('family.familyName', '$escaped');
                                if (window.settingsStore.syncFamilyNameToLocalStorage) {
                                    window.settingsStore.syncFamilyNameToLocalStorage();
                                }
                                await window.settingsStore.save();
                            } catch (e) {
                                console.error('[Dashie] Failed to save family name', e);
                            }
                        })()
                    """.trimIndent(), null)
                }
            }
            supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { frag -> frag.refresh() }
        }
    }
    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
}

/**
 * Text-input dialog to edit the family location (zip code or city). Writes to
 * BOTH sides, mirroring what the JS Preferences page does (settingKey +
 * onZipCodeChanged): the native DashieNative.setZipCode updates the
 * SharedPreferences-backed generalPrefs.zipCode (the family.zipCodeDisplay
 * source) and triggers a weather re-fetch; settingsStore.set + save() persists
 * family.zipCode for Supabase sync / reload survival.
 */
internal fun SettingsActivity.showEditFamilyZipDialog() {
    val current = com.dashieapp.Dashie.halite.preferences.GeneralPreferences(this).zipCode
    val input = android.widget.EditText(this)
    input.setText(current)
    input.hint = "90210 or Berlin, Germany"
    input.setPadding(48, 32, 48, 32)

    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
    dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Edit Location"
    val messageView = dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage)
    val scrollView = messageView?.parent as? android.view.ViewGroup
    if (scrollView != null && messageView != null) {
        val idx = scrollView.indexOfChild(messageView)
        scrollView.removeView(messageView)
        scrollView.addView(input, idx)
    }

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
        text = "Save"
        setOnClickListener {
            dialog.dismiss()
            val newZip = input.text.toString().trim()
            val escaped = newZip.replace("\\", "\\\\").replace("'", "\\'")
            SettingsActivity.webViewRef?.get()?.let { wv ->
                wv.post {
                    wv.evaluateJavascript("""
                        (async () => {
                            try {
                                if (window.DashieNative && window.DashieNative.setZipCode) {
                                    window.DashieNative.setZipCode('$escaped');
                                }
                                if (window.settingsStore) {
                                    window.settingsStore.set('family.zipCode', '$escaped');
                                    await window.settingsStore.save();
                                }
                            } catch (e) {
                                console.error('[Dashie] Failed to save location', e);
                            }
                        })()
                    """.trimIndent()) {
                        // DashieNative.setZipCode ran synchronously above, so the
                        // SharedPreferences-backed generalPrefs.zipCode is updated
                        // by the time this callback fires — refresh the row.
                        runOnUiThread {
                            supportFragmentManager.fragments.filterIsInstance<
                                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>()
                                .forEach { frag -> frag.refresh() }
                        }
                    }
                }
            }
        }
    }
    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
}

/** Show member editor as a pushed fragment */
internal fun SettingsActivity.showFamilyMemberEditor(memberId: String) {
    val member = familyMembers.find { it.id == memberId } ?: return
    editingMemberId = memberId

    // Load member data into edit state
    com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.loadFrom(member)
    // D.41 — snapshot the loaded state so Back can detect unsaved edits.
    val snapshot = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.snapshot()

    // The timer/reminder push pref lives in member_notification_preferences (a
    // different table than the member), so default it optimistically then fetch
    // async and refresh the toggle when it lands. Deliberately excluded from the
    // dirty snapshot above — it's immediate-apply, not part of Save.
    com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.notifyOnReminder = true
    com.dashieapp.Dashie.halite.notify.NotificationPrefsClient.getReminderPref(memberId) { enabled ->
        com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.notifyOnReminder = enabled
        runOnUiThread {
            supportFragmentManager.fragments.filterIsInstance<
                com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { it.refresh() }
        }
    }

    val editFragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.FamilyPageSchema.createEditScreen(
                member = familyMembers.find { it.id == editingMemberId },
                allMembers = familyMembers,
                isNewMember = false
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        backNavigationInterceptor = { handleFamilyEditBack(snapshot) }
    )
    showFragment(editFragment, "family_edit")
}

/** Show add member form */
internal fun SettingsActivity.showAddMemberForm() {
    editingMemberId = null
    // D.37 — seed the new member's default color to the first unused one.
    com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState
        .resetForNew(familyMembers.map { it.assignedColor })
    // D.41 — snapshot the reset state so Back can detect unsaved edits.
    val snapshot = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState.snapshot()

    val editFragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.FamilyPageSchema.createEditScreen(
                member = null,
                allMembers = familyMembers,
                isNewMember = true
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        backNavigationInterceptor = { handleFamilyEditBack(snapshot) }
    )
    showFragment(editFragment, "family_add")
}

/**
 * Back-press interceptor for the Add/Edit Family Member form (D.41). If the
 * current FamilyEditState matches the snapshot taken when the form opened,
 * Back proceeds normally. If the user has unsaved edits, show a 3-button
 * dialog (Save / Discard / Cancel) and consume the back press — the dialog
 * decides what happens next.
 */
private fun SettingsActivity.handleFamilyEditBack(
    snapshot: com.dashieapp.Dashie.halite.settings.schema.wiring.ConnectionSettingsWiring.FamilyEditState.Snapshot
): Boolean {
    val state = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState
    if (!state.isDirtyFrom(snapshot)) return false

    // Match the app's dialog look (R.layout.dialog_confirm — see
    // RestartPromptHelper) rather than a stock AlertDialog. dialog_confirm_3
    // is the 3-button variant for Save / Discard / Cancel.
    val dialogView = layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm_3, null)
    dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Save Changes?"
    dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text =
        "You have unsaved changes. Save before exiting?"

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }
    dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNeutral).apply {
        text = "Discard"
        setOnClickListener {
            dialog.dismiss()
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStack()
            }
        }
    }
    dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive).apply {
        text = "Save"
        setOnClickListener {
            dialog.dismiss()
            saveFamilyMember()
        }
    }

    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
    return true
}

/** Save the current member edit form via JS bridge */
internal fun SettingsActivity.saveFamilyMember() {
    val state = com.dashieapp.Dashie.halite.settings.schema.SettingsSchemaWiring.FamilyEditState
    if (state.name.isBlank()) {
        android.widget.Toast.makeText(this, "Name is required", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    val wv = SettingsActivity.webViewRef?.get() ?: return
    val memberId = editingMemberId
    val dataJson = org.json.JSONObject().apply {
        put("full_name", state.fullName)
        put("nickname", if (state.nickname.isBlank()) org.json.JSONObject.NULL else state.nickname)
        put("relationship", state.role)
        put("assigned_color", state.color)
        put("notes", if (state.notes.isBlank()) org.json.JSONObject.NULL else state.notes)
        put("gps_sharing_enabled", state.gpsEnabled)
    }.toString().replace("'", "\\'")

    wv.post {
        val method = if (memberId != null) "updateMember('$memberId', JSON.parse('$dataJson'))" else "createMember(JSON.parse('$dataJson'))"
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const svc = window.familyService;
                    if (!svc) throw new Error('Family service not initialized');
                    const result = await svc.$method;
                    window.DashieNative.onFamilyMemberSaved(JSON.stringify(result));
                } catch (e) {
                    window.DashieNative.onFamilyError(e.message || 'Failed to save member');
                }
            })()
        """.trimIndent(), null)
    }

    // Pop back to member list (callback will refresh)
    if (supportFragmentManager.backStackEntryCount > 0) {
        supportFragmentManager.popBackStack()
    }
}

// ── Device linking actions ───────────────────────────────────

internal fun SettingsActivity.sendDeviceInvite() {
    val memberId = editingMemberId ?: return
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const resp = await fetch('/api/gps-generate-device-invite', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json',
                                   'Authorization': 'Bearer ' + localStorage.getItem('sb-access-token') },
                        body: JSON.stringify({ member_id: '$memberId' })
                    });
                    const data = await resp.json();
                    if (data.invite_code) {
                        window.DashieNative.onFamilyError('Invite sent: ' + data.invite_code);
                    }
                    // Reload member to get updated invite status
                    const svc = window.familyService;
                    if (svc) {
                        const members = await svc.listMembers({ forceRefresh: true });
                        window.DashieNative.onFamilyMembersLoaded(JSON.stringify(members));
                    }
                } catch (e) {
                    window.DashieNative.onFamilyError(e.message || 'Failed to send invite');
                }
            })()
        """.trimIndent(), null)
    }
}

internal fun SettingsActivity.cancelDeviceInvite() {
    val memberId = editingMemberId ?: return
    showStyledConfirmDialog(
        title = "Cancel Invite",
        message = "Cancel the pending device invite?",
        confirmLabel = "Cancel Invite",
        isDestructive = true
    ) {
        familyJsBridgeCall("svc.updateMember('$memberId', { invite_code: null, invite_code_generated_at: null, invite_code_expires_at: null })")
    }
}

internal fun SettingsActivity.unlinkDevice() {
    val memberId = editingMemberId ?: return
    showStyledConfirmDialog(
        title = "Remove Device",
        message = "Remove the linked mobile device? The user will need a new invite to re-link.",
        confirmLabel = "Remove",
        isDestructive = true
    ) {
        familyJsBridgeCall("""
            svc.updateMember('$memberId', {
                device_token: null, device_id: null, device_name: null,
                device_platform: null, device_model: null, device_os_version: null,
                device_linked_at: null, device_last_seen_at: null,
                invite_code: null, invite_code_generated_at: null, invite_code_expires_at: null
            })
        """.trimIndent())
    }
}

internal fun SettingsActivity.editDeviceName() {
    val memberId = editingMemberId ?: return
    val member = familyMembers.find { it.id == memberId } ?: return
    val input = android.widget.EditText(this)
    input.setText(member.deviceName ?: "")
    input.hint = "Device name"
    input.setPadding(48, 32, 48, 32)

    // Edit device name uses a different dialog pattern (has text input)
    val dialogView = layoutInflater.inflate(R.layout.dialog_confirm, null)
    dialogView.findViewById<android.widget.TextView>(R.id.dialogTitle).text = "Edit Device Name"
    // Replace the message TextView with the EditText. Fetch the TextView by
    // its real type, then reach the (id-less) ScrollView via .parent —
    // findViewById<ScrollView>(R.id.dialogMessage) would ClassCastException.
    val messageView = dialogView.findViewById<android.widget.TextView>(R.id.dialogMessage)
    val scrollView = messageView?.parent as? android.view.ViewGroup
    if (scrollView != null && messageView != null) {
        val idx = scrollView.indexOfChild(messageView)
        scrollView.removeView(messageView)
        scrollView.addView(input, idx)
    }

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonNegative).apply {
        text = "Cancel"
        setOnClickListener { dialog.dismiss() }
    }
    dialogView.findViewById<android.widget.Button>(R.id.buttonPositive).apply {
        text = "Save"
        setOnClickListener {
            dialog.dismiss()
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty()) {
                val escaped = newName.replace("'", "\\'")
                familyJsBridgeCall("svc.updateMember('$memberId', { device_name: '$escaped' })")
            }
        }
    }
    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
}

internal fun SettingsActivity.unlinkGoogleAccount() {
    val memberId = editingMemberId ?: return
    showStyledConfirmDialog(
        title = "Unlink Google Account",
        message = "This will remove the linked Google account from this family member.",
        confirmLabel = "Unlink",
        isDestructive = true
    ) {
        // Use the dedicated unlink method, which routes to the
        // unlink_google_account edge function. That handler clears
        // auth_user_id + device token + linked_email atomically. The old
        // updateMember call here only touched deprecated fields and
        // tripped a 500 because update_family_member's ownership check
        // couldn't match linked members anyway.
        familyJsBridgeCall("svc.unlinkGoogleAccount('$memberId')")
    }
}

/** Delete the current member via JS bridge */
internal fun SettingsActivity.deleteFamilyMember() {
    val memberId = editingMemberId ?: return
    showStyledConfirmDialog(
        title = "Delete Family Member",
        message = "Are you sure you want to remove this family member?",
        confirmLabel = "Delete",
        isDestructive = true
    ) {
        val wv = SettingsActivity.webViewRef?.get() ?: return@showStyledConfirmDialog
        wv.post {
            wv.evaluateJavascript("""
                (async () => {
                    try {
                        const svc = window.familyService;
                        if (!svc) throw new Error('Family service not initialized');
                        await svc.deleteMember('$memberId');
                        window.DashieNative.onFamilyMemberDeleted('$memberId');
                    } catch (e) {
                        window.DashieNative.onFamilyError(e.message || 'Failed to delete member');
                    }
                })()
            """.trimIndent(), null)
        }
    }
}

/** Helper: run a family service operation via JS bridge using window.familyService, then reload member list */
internal fun SettingsActivity.familyJsBridgeCall(operation: String) {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const svc = window.familyService;
                    if (!svc) throw new Error('Family service not initialized');
                    await $operation;
                    const members = await svc.listMembers({ forceRefresh: true });
                    window.DashieNative.onFamilyMembersLoaded(JSON.stringify(members));
                } catch (e) {
                    window.DashieNative.onFamilyError(e.message || 'Operation failed');
                }
            })()
        """.trimIndent(), null)
    }
}
