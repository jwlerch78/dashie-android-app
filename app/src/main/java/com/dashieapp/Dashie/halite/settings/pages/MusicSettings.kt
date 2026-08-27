package com.dashieapp.Dashie.halite.settings.pages

import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.edition.brandName

internal fun SettingsActivity.createMusicFragment(): com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment {
    val isProd = !packageName.contains("staging") && !packageName.contains("local")

    // Family Music Profiles section is hidden (item 11), so we no longer
    // need to load family members on page open. The previous load path
    // surfaced a "Family service not initialized" toast on kiosk-only
    // installs (no Dashie account → no familyService in JS); skip it.
    if (!isProd) {
        checkMaTokenRole()
    }

    // Register re-login action (for admin profile linking)
    schemaContext.callbackRegistry.register("maReloginAdmin") {
        val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
        val maUrl = prefs.connection.getEffectiveMaApiUrl()
        if (maUrl.isNotEmpty()) {
            prefs.connection.maApiToken = ""
            val intent = com.dashieapp.Dashie.halite.music.MaLoginActivity.createIntent(this, maUrl)
            intent.putExtra("skip_auto_login", true)
            startActivity(intent)
        }
    }

    // Disconnect: confirmation modal, then clears local token and central store
    schemaContext.callbackRegistry.register("maDisconnect") {
        val activity = this
        val dialogView = layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm, null)
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Disconnect Music Assistant"
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text =
            "This will disconnect Music Assistant on this device and remove the shared token. Other devices will need to reconnect.\n\nAre you sure?"

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative).apply {
            text = "Cancel"
            setOnClickListener { dialog.dismiss() }
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive).apply {
            text = "Disconnect"
            setOnClickListener {
                dialog.dismiss()
                val prefs = com.dashieapp.Dashie.halite.HalitePreferences(activity)
                android.util.Log.i("MusicSettings", "maDisconnect: clearing local and central tokens")
                prefs.connection.maApiToken = ""
                maCurrentUserName = null
                maIsAdmin = null
                android.widget.Toast.makeText(activity, "Music Assistant disconnected", android.widget.Toast.LENGTH_SHORT).show()
                // Clear central store in background
                Thread {
                    try {
                        com.dashieapp.Dashie.halite.settings.schema.wiring.SettingsDialogWiring
                            .clearCentralMaToken(prefs)
                    } catch (e: Exception) {
                        android.util.Log.w("MusicSettings", "maDisconnect: failed to clear central token: ${e.message}")
                    }
                }.start()
                // Refresh UI immediately to show disconnected state
                val fragment = supportFragmentManager.findFragmentById(
                    com.dashieapp.Dashie.R.id.settingsFragmentContainer
                )
                if (fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                    fragment.refresh()
                }
            }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    // Reconnect: tries central store first, then falls back to full reset login
    schemaContext.callbackRegistry.register("maReconnect") {
        val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
        val maUrl = prefs.connection.getEffectiveMaApiUrl()
        if (maUrl.isEmpty()) {
            android.widget.Toast.makeText(this, "No Music Assistant URL configured", android.widget.Toast.LENGTH_SHORT).show()
            return@register
        }
        android.util.Log.i("MusicSettings", "maReconnect: clearing local token, trying central store")
        prefs.connection.maApiToken = ""
        android.widget.Toast.makeText(this, "Reconnecting to Music Assistant...", android.widget.Toast.LENGTH_SHORT).show()
        val activity = this
        Thread {
            // Try central store first (quick path — another device already logged in)
            val centralToken = com.dashieapp.Dashie.halite.settings.schema.wiring.SettingsDialogWiring
                .fetchCentralMaToken(prefs)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (centralToken != null) {
                    android.util.Log.i("MusicSettings", "maReconnect: got token from central store")
                    prefs.connection.maApiToken = centralToken.first
                    prefs.connection.maApiUrl = centralToken.second
                    android.widget.Toast.makeText(activity, "Music Assistant reconnected", android.widget.Toast.LENGTH_SHORT).show()
                    val fragment = supportFragmentManager.findFragmentById(
                        com.dashieapp.Dashie.R.id.settingsFragmentContainer
                    )
                    if (fragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                        fragment.refresh()
                    }
                } else {
                    // No central token — full reset: clear central store, wipe WebView, fresh login
                    android.util.Log.i("MusicSettings", "maReconnect: no central token, falling back to full reset login")
                    android.widget.Toast.makeText(activity, "No shared token found — opening login...", android.widget.Toast.LENGTH_SHORT).show()
                    Thread {
                        try {
                            com.dashieapp.Dashie.halite.settings.schema.wiring.SettingsDialogWiring
                                .clearCentralMaToken(prefs)
                        } catch (e: Exception) {
                            android.util.Log.w("MusicSettings", "maReconnect: failed to clear central token: ${e.message}")
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            val intent = com.dashieapp.Dashie.halite.music.MaLoginActivity.createIntent(activity, maUrl)
                            intent.putExtra("clear_all_webview_data", true)
                            intent.putExtra("skip_auto_login", true)
                            com.dashieapp.Dashie.halite.settings.schema.wiring.SettingsCallbackWiring.pendingMusicEnableCallback = {
                                // Check role + show non-admin warning if needed
                                checkMaTokenRole()
                            }
                            startActivity(intent)
                        }
                    }.start()
                }
            }
        }.start()
    }

    return com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            val connPrefs = com.dashieapp.Dashie.halite.HalitePreferences(this@createMusicFragment).connection
            val sendspinStatus = if (connPrefs.musicSpeakerOnly) {
                val svc = com.dashieapp.Dashie.halite.music.sendspin.SendspinPlayerService.activeClient
                if (svc != null) "Connected as speaker" else "Searching for server…"
            } else null
            com.dashieapp.Dashie.halite.settings.schemas.MusicPageSchema.create(
                members = familyMembers,
                maUserNames = maUserNames,
                maCurrentUser = maCurrentUserName,
                maIsAdmin = maIsAdmin,
                isProd = isProd,
                hasToken = connPrefs.hasMaApiToken,
                sendspinStatus = sendspinStatus
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when {
                target == "ext:speaker_picker" -> {
                    val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this@createMusicFragment)
                    val conn = prefs.connection
                    val currentEntityId = prefs.connection.getEffectiveMusicPlayerEntityId()
                    val recentEntityIds = conn.getRecentMusicPlayers()
                    val dialogs = com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs(
                        this@createMusicFragment, prefs
                    )
                    dialogs.webView = SettingsActivity.webViewRef?.get()
                    dialogs.useShellRelay = true

                    val onSelected = { entityId: String ->
                        // This picker sets the DEFAULT player, not the current one: it updates
                        // the persisted default (consumed by effectiveMusicEntityId on the next
                        // player open / voice-play-when-idle) and deliberately does NOT touch
                        // registry.currentMusicEntityId or dispatch a live switch, so a running
                        // player is left alone.
                        prefs.connection.musicPlayerEntityId = entityId
                        conn.addRecentMusicPlayer(entityId)
                        conn.musicPlayerDefaultEntityId = entityId
                        val currentFragment = supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer)
                        if (currentFragment is com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment) {
                            currentFragment.refresh()
                        }
                    }
                    val onSetDefault = { entityId: String ->
                        conn.setDefaultMusicPlayer(entityId)
                    }

                    // Prefer MA REST API for player list (no _2 entities), with HA relay fallback
                    val haBase = (conn.haBaseUrl.takeIf { it.isNotEmpty() } ?: conn.haUrl).trimEnd('/')
                    val haToken = conn.haAccessToken
                    val apiClient = run {
                        val apiUrl = conn.getEffectiveMaApiUrl()
                        if (conn.hasMaApiToken && apiUrl.isNotEmpty()) {
                            com.dashieapp.Dashie.halite.music.MaApiClient(apiUrl, conn.maApiToken)
                        } else null
                    }

                    if (apiClient != null) {
                        Thread {
                            try {
                                val arr = apiClient.getPlayers()
                                if (arr == null) {
                                    runOnUiThread {
                                        android.widget.Toast.makeText(this@createMusicFragment,
                                            "Music Assistant unavailable", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                    return@Thread
                                }
                                val players = (0 until arr.length()).map { i ->
                                    val obj = arr.getJSONObject(i)
                                    val playerId = obj.optString("player_id", "")
                                    val displayName = obj.optString("display_name", "")
                                    val name = obj.optString("name", "")
                                    val model = obj.optJSONObject("device_info")?.optString("model", "") ?: ""
                                    val friendlyName = when {
                                        displayName.isNotEmpty() && displayName != model -> displayName
                                        name.isNotEmpty() && name != model -> name
                                        else -> playerId.removePrefix("media_player.")
                                            .replace("_", " ").replaceFirstChar { it.uppercase() }
                                            .replace(Regex("\\b\\w")) { it.value.uppercase() }
                                    }
                                    val playerType = obj.optString("type", "player")
                                    val isGroup = playerType == "group" || playerType == "sync_group" || playerType == "universal_group"
                                    com.dashieapp.Dashie.halite.sidebar.dialogs.MusicPlayerDialogs.MediaPlayerInfo(
                                        entityId = playerId, friendlyName = friendlyName,
                                        state = obj.optString("state", "idle"),
                                        isGroup = isGroup
                                    )
                                }.filter { it.entityId.isNotEmpty() }
                                val playerMap = players.associateBy { it.entityId }
                                val onSelectedWithName = { entityId: String ->
                                    val friendlyName = playerMap[entityId]?.friendlyName ?: ""
                                    conn.musicPlayerDisplayName = friendlyName
                                    onSelected(entityId)
                                }
                                runOnUiThread {
                                    dialogs.showMediaPlayerPickerDialogDirect(
                                        currentEntityId, players, recentEntityIds, onSelectedWithName, onSetDefault
                                    )
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    android.widget.Toast.makeText(this@createMusicFragment,
                                        "Music Assistant unavailable", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    } else {
                        // No MA API — offer login
                        val maUrl = conn.getEffectiveMaApiUrl()
                        if (maUrl.isNotEmpty()) {
                            startActivity(com.dashieapp.Dashie.halite.music.MaLoginActivity.createIntent(this@createMusicFragment, maUrl))
                        } else {
                            android.widget.Toast.makeText(this@createMusicFragment,
                                "Music Assistant not configured", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                target == "ext:ma_profiles_page" -> {
                    showMaProfilesPage()
                    true
                }
                target.startsWith("ext:ma_user_picker:") -> {
                    val memberId = target.removePrefix("ext:ma_user_picker:")
                    showMaUserPicker(memberId)
                    true
                }
                else -> false
            }
        }
    )
}

/** Track whether we've already shown the non-admin warning this session */
private var nonAdminWarningShown = false

/** Check whether the current MA token belongs to an admin user */
internal fun SettingsActivity.checkMaTokenRole() {
    val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
    val conn = prefs.connection
    val apiUrl = conn.getEffectiveMaApiUrl()
    if (!conn.hasMaApiToken || apiUrl.isEmpty()) return

    Thread {
        try {
            val apiClient = com.dashieapp.Dashie.halite.music.MaApiClient(apiUrl, conn.maApiToken)
            val me = apiClient.getCurrentUser()
            if (me != null) {
                val role = me.optString("role", "user")
                val displayName = me.optString("display_name", "").ifEmpty { me.optString("username", "") }
                maCurrentUserName = displayName
                maIsAdmin = role == "admin"
                android.util.Log.i("SettingsActivity", "\uD83C\uDFB5 MA token user: $displayName, role=$role, isAdmin=$maIsAdmin")
                runOnUiThread {
                    supportFragmentManager.fragments.filterIsInstance<
                        com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { it.refresh() }

                    // Show non-admin warning once per session
                    if (maIsAdmin == false && !nonAdminWarningShown) {
                        nonAdminWarningShown = true
                        showNonAdminWarning(displayName)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SettingsActivity", "\uD83C\uDFB5 Failed to check MA token role: ${e.message}")
        }
    }.start()
}

/** Show a warning dialog when the user is connected as a non-admin MA user */
private fun SettingsActivity.showNonAdminWarning(displayName: String) {
    val dialogView = layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_confirm, null)
    dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text =
        "Non-Admin Account"
    dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogMessage).text =
        "You are logged in as \"$displayName\" which is not an admin account.\n\n" +
        "${brandName()} recommends logging into Music Assistant as an admin to enable features like family music profiles."

    val dialog = android.app.AlertDialog.Builder(this)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        attributes = attributes?.apply { dimAmount = 0.5f }
    }

    dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonNegative).apply {
        text = "Continue as Non-Admin"
        setOnClickListener { dialog.dismiss() }
    }

    dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonPositive).apply {
        text = "Log In as Different User"
        setOnClickListener {
            dialog.dismiss()
            val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this@showNonAdminWarning)
            val maUrl = prefs.connection.getEffectiveMaApiUrl()
            if (maUrl.isNotEmpty()) {
                prefs.connection.maApiToken = ""
                maCurrentUserName = null
                maIsAdmin = null
                nonAdminWarningShown = false
                val intent = com.dashieapp.Dashie.halite.music.MaLoginActivity.createIntent(
                    this@showNonAdminWarning, maUrl
                )
                intent.putExtra("skip_auto_login", true)
                intent.putExtra("clear_all_webview_data", true)
                com.dashieapp.Dashie.halite.settings.schema.wiring.SettingsCallbackWiring.pendingMusicEnableCallback = {
                    checkMaTokenRole()
                }
                startActivity(intent)
            }
        }
    }

    dialog.show()
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
}

/** Push the profiles sub-page showing individual family member MA links */
internal fun SettingsActivity.showMaProfilesPage() {
    // Pre-fetch MA user names if not cached yet
    if (maUserNames.isEmpty()) {
        fetchMaUserNames()
    }

    val profilesFragment = com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment.create(
        schemaProvider = {
            com.dashieapp.Dashie.halite.settings.schemas.MusicPageSchema.createProfilesPage(
                members = familyMembers,
                maUserNames = maUserNames
            )
        },
        valueProvider = schemaContext.valueProvider,
        callbackRegistry = schemaContext.callbackRegistry,
        navigationCallback = { target ->
            when {
                target.startsWith("ext:ma_user_picker:") -> {
                    val memberId = target.removePrefix("ext:ma_user_picker:")
                    showMaUserPicker(memberId)
                    true
                }
                else -> false
            }
        }
    )
    supportFragmentManager.beginTransaction()
        .setCustomAnimations(
            R.anim.settings_slide_in_right, R.anim.settings_slide_out_left,
            R.anim.settings_slide_in_left, R.anim.settings_slide_out_right
        )
        .replace(R.id.settingsFragmentContainer, profilesFragment)
        .addToBackStack("ma_profiles")
        .commit()
}

/** Fetch MA user display names in background and refresh the current fragment */
internal fun SettingsActivity.fetchMaUserNames() {
    val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
    val conn = prefs.connection
    val apiUrl = conn.getEffectiveMaApiUrl()
    if (!conn.hasMaApiToken || apiUrl.isEmpty()) return

    Thread {
        try {
            val apiClient = com.dashieapp.Dashie.halite.music.MaApiClient(apiUrl, conn.maApiToken)
            val users = apiClient.getUsers() ?: return@Thread
            val names = mutableMapOf<String, String>()
            for (i in 0 until users.length()) {
                val u = users.getJSONObject(i)
                val id = u.optString("user_id", "")
                val name = u.optString("display_name", "").ifEmpty { u.optString("username", "") }
                if (id.isNotEmpty()) names[id] = name
            }
            maUserNames = names
            runOnUiThread {
                supportFragmentManager.fragments.filterIsInstance<
                    com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment>().forEach { it.refresh() }
            }
        } catch (_: Exception) { }
    }.start()
}

/**
 * Show a picker dialog to link a family member to an MA user.
 * Fetches MA users from the API, shows a selection dialog.
 */
internal fun SettingsActivity.showMaUserPicker(memberId: String) {
    val prefs = com.dashieapp.Dashie.halite.HalitePreferences(this)
    val conn = prefs.connection
    val apiUrl = conn.getEffectiveMaApiUrl()
    if (!conn.hasMaApiToken || apiUrl.isEmpty()) {
        android.widget.Toast.makeText(this, "Music Assistant not configured", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    val member = familyMembers.find { it.id == memberId } ?: return
    val apiClient = com.dashieapp.Dashie.halite.music.MaApiClient(apiUrl, conn.maApiToken)

    Thread {
        try {
            val users = apiClient.getUsers()
            if (users == null || users.length() == 0) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "No Music Assistant users found", android.widget.Toast.LENGTH_SHORT).show()
                }
                return@Thread
            }

            // Parse MA users
            data class MaUser(val userId: String, val displayName: String, val username: String, val role: String)
            val maUsers = (0 until users.length()).map { i ->
                val obj = users.getJSONObject(i)
                MaUser(
                    userId = obj.optString("user_id", ""),
                    displayName = obj.optString("display_name", "").ifEmpty { obj.optString("username", "") },
                    username = obj.optString("username", ""),
                    role = obj.optString("role", "user")
                )
            }.filter { it.userId.isNotEmpty() && it.role != "guest" }

            // Cache display names for schema refresh
            maUserNames = maUsers.associate { it.userId to it.displayName }

            runOnUiThread {
                // Build picker options: "None" + MA users
                val options = mutableListOf("None (unlink)")
                options.addAll(maUsers.map { "${it.displayName} (${it.role})" })

                val currentMaUserId = member.maUserId
                val currentIndex = if (currentMaUserId == null) 0
                    else (maUsers.indexOfFirst { it.userId == currentMaUserId } + 1).coerceAtLeast(0)

                androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_Dashie_Dialog)
                    .setTitle("Link ${member.shortName} to MA User")
                    .setSingleChoiceItems(options.toTypedArray(), currentIndex) { dialog, which ->
                        dialog.dismiss()
                        val selectedMaUserId = if (which == 0) null else maUsers[which - 1].userId
                        saveMaUserLink(memberId, selectedMaUserId)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                android.widget.Toast.makeText(this, "Failed to load MA users: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

/**
 * Save the MA user link for a family member via JS bridge.
 */
internal fun SettingsActivity.saveMaUserLink(memberId: String, maUserId: String?) {
    val wv = SettingsActivity.webViewRef?.get() ?: return
    val maUserIdJs = if (maUserId != null) "'$maUserId'" else "null"
    wv.post {
        wv.evaluateJavascript("""
            (async () => {
                try {
                    const svc = window.familyService;
                    if (!svc) throw new Error('Family service not initialized');
                    await svc.updateMember('$memberId', { ma_user_id: $maUserIdJs });
                    const members = await svc.listMembers({ forceRefresh: true });
                    window.DashieNative.onFamilyMembersLoaded(JSON.stringify(members));
                } catch(e) {
                    console.error('Failed to save MA user link:', e);
                }
            })();
        """.trimIndent(), null)
    }
}
