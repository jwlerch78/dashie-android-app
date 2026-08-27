package com.dashieapp.Dashie.halite.settings.schema.wiring

import com.dashieapp.Dashie.edition.ApiPaths
import com.dashieapp.Dashie.edition.brandName

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.SettingsActivity
import com.dashieapp.Dashie.halite.settings.schema.SchemaSettingsFragment

/**
 * Dialog-related functions extracted from SettingsSchemaWiring:
 * - Music Token Central Storage (fetch/save)
 * - Immich Central Token (fetch/save)
 * - Immich Login Flow
 * - Immich Album Picker
 * - MA Login Dialog
 */
object SettingsDialogWiring {

    // ── Music Token Central Storage ─────────────────────────────────────

    /**
     * Try to fetch a centrally-stored MA token from the Dashie HA integration.
     * Returns (token, maUrl) or null if unavailable.
     * Fails gracefully if the integration isn't installed.
     */
    fun fetchCentralMaToken(prefs: HalitePreferences): Pair<String, String>? {
        return try {
            val halitePrefs = prefs
            val (baseUrl, haToken) = com.dashieapp.Dashie.halite.HaTokenExtractor
                .getValidCredentialsSync(halitePrefs) ?: return null

            val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("$baseUrl${ApiPaths.HA}/music/token")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                val body = response.body?.string() ?: return null
                val json = org.json.JSONObject(body)
                val token = json.optString("token", "")
                val maUrl = json.optString("ma_url", "")
                if (token.isEmpty()) return null
                // Use central URL if provided, otherwise keep the device's existing URL
                val effectiveUrl = maUrl.ifEmpty { prefs.connection.getEffectiveMaApiUrl() }
                if (effectiveUrl.isEmpty()) return null
                Pair(token, effectiveUrl)
            }
        } catch (e: Exception) {
            // Integration not installed or HA unreachable — that's fine
            android.util.Log.d("SettingsDialogWiring", "🎵 Central token fetch failed (expected if no integration): ${e.message}")
            null
        }
    }

    /**
     * Save the MA token to the central HA store so other devices can use it.
     * Fails silently if the integration isn't installed.
     */
    fun saveCentralMaToken(prefs: HalitePreferences) {
        val token = prefs.connection.maApiToken
        val maUrl = prefs.connection.maApiUrl
        if (token.isEmpty() || maUrl.isEmpty()) return

        Thread {
            try {
                val (baseUrl, haToken) = com.dashieapp.Dashie.halite.HaTokenExtractor
                    .getValidCredentialsSync(prefs) ?: return@Thread

                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val body = org.json.JSONObject().apply {
                    put("token", token)
                    put("ma_url", maUrl)
                }.toString()

                val request = okhttp3.Request.Builder()
                    .url("$baseUrl${ApiPaths.HA}/music/token")
                    .addHeader("Authorization", "Bearer $haToken")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    android.util.Log.i("SettingsDialogWiring",
                        "🎵 Saved MA token to central store: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                android.util.Log.d("SettingsDialogWiring",
                    "🎵 Central token save failed (expected if no integration): ${e.message}")
            }
        }.start()
    }

    /**
     * Clear the central MA token from the HA store (DELETE endpoint).
     * Synchronous — call from a background thread.
     */
    fun clearCentralMaToken(prefs: HalitePreferences) {
        val (baseUrl, haToken) = com.dashieapp.Dashie.halite.HaTokenExtractor
            .getValidCredentialsSync(prefs) ?: return

        val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url("$baseUrl${ApiPaths.HA}/music/token")
            .addHeader("Authorization", "Bearer $haToken")
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            android.util.Log.i("SettingsDialogWiring",
                "🎵 Cleared central MA token: HTTP ${response.code}")
        }
    }

    // ── Immich Central Token ───────────────────────────────────────────

    /**
     * Fetch Immich credentials from the central HA store.
     * Returns (token, serverUrl) or null if not stored.
     */
    fun fetchCentralImmichToken(prefs: HalitePreferences): Triple<String, String, String>? {
        return try {
            val (baseUrl, haToken) = com.dashieapp.Dashie.halite.HaTokenExtractor
                .getValidCredentialsSync(prefs) ?: return null

            val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url("$baseUrl${ApiPaths.HA}/immich/token")
                .addHeader("Authorization", "Bearer $haToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                val body = response.body?.string() ?: return null
                val json = org.json.JSONObject(body)
                val token = json.optString("token", "")
                val serverUrl = json.optString("server_url", "")
                val albums = json.optString("selected_albums", "")
                if (token.isNotEmpty() && serverUrl.isNotEmpty()) Triple(token, serverUrl, albums) else null
            }
        } catch (e: Exception) {
            android.util.Log.d("SettingsDialogWiring",
                "📷 Central Immich token fetch failed: ${e.message}")
            null
        }
    }

    /**
     * Save Immich credentials to the central HA store so other devices can use them.
     */
    fun saveCentralImmichToken(prefs: HalitePreferences) {
        val screensaver = prefs.screensaver
        val token = screensaver.immichAccessToken
        val serverUrl = screensaver.immichServerUrl
        if (token.isEmpty() || serverUrl.isEmpty()) return

        Thread {
            try {
                val (baseUrl, haToken) = com.dashieapp.Dashie.halite.HaTokenExtractor
                    .getValidCredentialsSync(prefs) ?: return@Thread

                val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val body = org.json.JSONObject().apply {
                    put("token", token)
                    put("server_url", serverUrl)
                    put("selected_albums", screensaver.immichSelectedAlbums)
                }.toString()

                val request = okhttp3.Request.Builder()
                    .url("$baseUrl${ApiPaths.HA}/immich/token")
                    .addHeader("Authorization", "Bearer $haToken")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { response ->
                    android.util.Log.i("SettingsDialogWiring",
                        "📷 Saved Immich token to central store: HTTP ${response.code}")
                }
            } catch (e: Exception) {
                android.util.Log.d("SettingsDialogWiring",
                    "📷 Central Immich token save failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Clear the central Immich token from the HA store (DELETE endpoint).
     * Synchronous — call from a background thread.
     */
    fun clearCentralImmichToken(prefs: HalitePreferences) {
        val (baseUrl, haToken) = com.dashieapp.Dashie.halite.HaTokenExtractor
            .getValidCredentialsSync(prefs) ?: return

        val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url("$baseUrl${ApiPaths.HA}/immich/token")
            .addHeader("Authorization", "Bearer $haToken")
            .delete()
            .build()

        client.newCall(request).execute().use { response ->
            android.util.Log.i("SettingsDialogWiring",
                "📷 Cleared central Immich token: HTTP ${response.code}")
        }
    }

    /**
     * Sign out of Immich: clear central HA store, local prefs, and notify
     * the screensaver/widget. Runs the network call off the main thread.
     */
    fun signOutImmich(
        activity: com.dashieapp.Dashie.halite.settings.SettingsActivity,
        prefs: HalitePreferences,
        onComplete: () -> Unit
    ) {
        Thread {
            try {
                clearCentralImmichToken(prefs)
            } catch (e: Exception) {
                android.util.Log.w("SettingsDialogWiring",
                    "📷 Central Immich token clear failed: ${e.message}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                prefs.screensaver.immichServerUrl = ""
                prefs.screensaver.immichAccessToken = ""
                prefs.screensaver.immichSelectedAlbums = ""
                immichTokenExpired = false
                activity.sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_PHOTO_SOURCE_CHANGED").apply {
                        setPackage(activity.packageName)
                    }
                )
                onComplete()
            }
        }.start()
    }

    /**
     * In-memory flag: true if the most recent validateToken() ping failed.
     * Read by the Photos page status display to surface "Sign-in expired" so
     * users can re-authenticate instead of seeing a misleading "Connected"
     * label backed by a dead token. Reset on successful login or sign-out.
     */
    @Volatile
    var immichTokenExpired: Boolean = false

    /**
     * Ping the Immich server to confirm the stored token still authenticates.
     * Updates [immichTokenExpired] and refreshes the settings fragment if the
     * status changed. No-op when no credentials are stored.
     */
    fun pingImmichToken(
        activity: com.dashieapp.Dashie.halite.settings.SettingsActivity,
        prefs: HalitePreferences
    ) {
        if (!prefs.screensaver.hasImmichConfig) {
            immichTokenExpired = false
            return
        }
        Thread {
            val client = com.dashieapp.Dashie.halite.screensaver.ImmichApiClient(
                prefs.screensaver.immichServerUrl,
                prefs.screensaver.immichAccessToken
            )
            val valid = kotlinx.coroutines.runBlocking { client.validateToken() }
            val newState = !valid
            if (newState != immichTokenExpired) {
                immichTokenExpired = newState
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val fragment = activity.supportFragmentManager.findFragmentById(
                        com.dashieapp.Dashie.R.id.settingsFragmentContainer
                    )
                    if (fragment is SchemaSettingsFragment) {
                        fragment.refresh()
                    }
                }
            }
        }.start()
    }

    // ── Immich Login Flow ───────────────────────────────────────────────

    /** Pending callback for Immich login — called from SettingsActivity.onResume. */
    @Volatile
    private var pendingImmichLoginCallback: (() -> Unit)? = null

    /**
     * Called from SettingsActivity.onResume to complete the Immich login flow.
     */
    fun checkPendingImmichLogin(prefs: HalitePreferences, activity: com.dashieapp.Dashie.halite.settings.SettingsActivity? = null) {
        val callback = pendingImmichLoginCallback ?: return
        pendingImmichLoginCallback = null
        if (prefs.screensaver.hasImmichConfig) {
            immichTokenExpired = false
            saveCentralImmichToken(prefs)
            // Notify screensaver/widget to restart with Immich credentials
            activity?.sendBroadcast(
                android.content.Intent("com.dashieapp.Dashie.ACTION_PHOTO_SOURCE_CHANGED").apply {
                    setPackage(activity.packageName)
                }
            )
            callback?.invoke() ?: run {
                // Refresh settings UI if no explicit callback
                activity?.let { act ->
                    val fragment = act.supportFragmentManager.findFragmentById(
                        com.dashieapp.Dashie.R.id.settingsFragmentContainer
                    )
                    if (fragment is SchemaSettingsFragment) {
                        fragment.refresh()
                    }
                }
            }
        } else {
            android.util.Log.i("SettingsDialogWiring", "📷 Immich login cancelled — reverting")
            activity?.let { act ->
                val fragment = act.supportFragmentManager.findFragmentById(
                    com.dashieapp.Dashie.R.id.settingsFragmentContainer
                )
                if (fragment is SchemaSettingsFragment) {
                    fragment.refresh()
                }
            }
        }
    }

    /**
     * Launch the Immich login flow. Called when user selects Immich source
     * and has no credentials, or taps the "Immich Account" navigation item.
     */
    fun launchImmichLogin(activity: com.dashieapp.Dashie.halite.settings.SettingsActivity, prefs: HalitePreferences, onComplete: (() -> Unit)? = null) {
        // First try central store
        Thread {
            val central = fetchCentralImmichToken(prefs)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (central != null) {
                    // Got credentials from another device
                    prefs.screensaver.immichServerUrl = central.second
                    prefs.screensaver.immichAccessToken = central.first
                    if (central.third.isNotEmpty()) {
                        prefs.screensaver.immichSelectedAlbums = central.third
                    }
                    android.util.Log.i("SettingsDialogWiring", "📷 Got Immich token from central store")
                    onComplete?.invoke()
                    // Refresh the settings page
                    val fragment = activity.supportFragmentManager.findFragmentById(
                        com.dashieapp.Dashie.R.id.settingsFragmentContainer
                    )
                    if (fragment is SchemaSettingsFragment) {
                        fragment.refresh()
                    }
                } else {
                    // No central token — show login activity
                    pendingImmichLoginCallback = onComplete
                    val haBaseUrl = prefs.connection.haBaseUrl.ifEmpty {
                        prefs.connection.haUrl.substringBefore("?").trimEnd('/')
                    }
                    val intent = com.dashieapp.Dashie.halite.screensaver.ImmichLoginActivity
                        .createIntent(activity, prefs.screensaver.immichServerUrl, haBaseUrl)
                    activity.startActivity(intent)
                }
            }
        }.start()
    }

    // ── Immich Album Picker ────────────────────────────────────────────

    /**
     * Show a multi-select album picker dialog for Immich.
     * Fetches albums from Immich API, shows a list with checkmarks,
     * and saves selected album IDs as JSON array to preferences.
     */
    fun showImmichAlbumPicker(activity: SettingsActivity, prefs: HalitePreferences, onChanged: () -> Unit) {
        if (!prefs.screensaver.hasImmichConfig) {
            android.widget.Toast.makeText(activity, "Sign in to Immich first", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Show loading
        val loadingDialog = android.app.AlertDialog.Builder(activity)
            .setView(android.widget.ProgressBar(activity).apply {
                isIndeterminate = true
                setPadding(48, 48, 48, 48)
            })
            .setCancelable(true)
            .create()
        loadingDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        loadingDialog.show()

        Thread {
            val source = com.dashieapp.Dashie.halite.screensaver.ImmichPhotoSource(activity, prefs.screensaver)
            source.setCredentials(prefs.screensaver.immichServerUrl, prefs.screensaver.immichAccessToken)
            val apiClient = com.dashieapp.Dashie.halite.screensaver.ImmichApiClient(
                prefs.screensaver.immichServerUrl, prefs.screensaver.immichAccessToken
            )
            val albums = kotlinx.coroutines.runBlocking { source.fetchAlbums() }
            val totalImages = kotlinx.coroutines.runBlocking { apiClient.getAssetStatistics() }

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                loadingDialog.dismiss()
                if (albums.isEmpty()) {
                    android.widget.Toast.makeText(activity, "No albums found in Immich", android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                showImmichAlbumList(activity, prefs.screensaver, albums, totalImages, onChanged)
            }
        }.start()
    }

    fun showImmichAlbumList(
        activity: SettingsActivity,
        screensaverPrefs: com.dashieapp.Dashie.halite.screensaver.ScreensaverPreferences,
        albums: List<com.dashieapp.Dashie.halite.screensaver.ImmichAlbum>,
        totalImageCount: Int = -1,
        onChanged: () -> Unit
    ) {
        val dialogView = activity.layoutInflater.inflate(com.dashieapp.Dashie.R.layout.dialog_picker, null)
        dialogView.findViewById<android.widget.TextView>(com.dashieapp.Dashie.R.id.dialogTitle).text = "Select Albums"

        // Replace RadioGroup with LinearLayout for custom rows
        val optionsGroup = dialogView.findViewById<android.widget.RadioGroup>(com.dashieapp.Dashie.R.id.optionsGroup)
        val scrollView = optionsGroup.parent as? android.widget.ScrollView
        scrollView?.removeView(optionsGroup)

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(0, 16, 0, 8)
        }
        scrollView?.addView(container)

        // Parse currently selected albums
        val currentAlbums = screensaverPrefs.immichSelectedAlbums
        val selectedIds = mutableSetOf<String>()
        val isAllSelected = currentAlbums.isEmpty() || currentAlbums == "*"
        if (!isAllSelected) {
            try {
                val arr = org.json.JSONArray(currentAlbums)
                for (i in 0 until arr.length()) selectedIds.add(arr.getString(i))
            } catch (_: Exception) { }
        }

        // Track "All Photos" toggle
        var allPhotosMode = isAllSelected

        fun updateRowVisuals() {
            // Row 0 = "All Photos"
            val allRow = container.getChildAt(0) as? android.widget.LinearLayout
            if (allRow != null && allRow.childCount >= 2) {
                val nameView = allRow.getChildAt(0) as? android.widget.TextView
                val checkView = allRow.getChildAt(allRow.childCount - 1) as? android.widget.TextView
                nameView?.setTypeface(nameView.typeface,
                    if (allPhotosMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                checkView?.visibility = if (allPhotosMode) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }

            // Album rows (1-indexed)
            albums.forEachIndexed { index, album ->
                val row = container.getChildAt(index + 1) as? android.widget.LinearLayout ?: return@forEachIndexed
                val isSelected = if (allPhotosMode) false else selectedIds.contains(album.id)
                val nameView = row.getChildAt(0) as? android.widget.TextView
                val checkView = row.getChildAt(row.childCount - 1) as? android.widget.TextView
                nameView?.setTypeface(nameView.typeface,
                    if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                checkView?.visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
        }

        fun createRow(name: String, subtitle: String?, isSelected: Boolean): android.widget.LinearLayout {
            return android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(48, 20, 48, 20)
                isClickable = true
                isFocusable = true
                background = activity.getDrawable(android.R.drawable.list_selector_background)
                gravity = android.view.Gravity.CENTER_VERTICAL

                addView(android.widget.TextView(activity).apply {
                    text = name
                    textSize = 16f
                    setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
                    if (isSelected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                if (subtitle != null) {
                    addView(android.widget.TextView(activity).apply {
                        text = subtitle
                        textSize = 12f
                        setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
                        setPadding(16, 0, 0, 0)
                    })
                }

                addView(android.widget.TextView(activity).apply {
                    text = "✓"
                    textSize = 16f
                    setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.dashie_orange))
                    setPadding(16, 0, 0, 0)
                    visibility = if (isSelected) android.view.View.VISIBLE else android.view.View.INVISIBLE
                })
            }
        }

        // "All Photos (random)" row — show total library count from Immich stats
        val allPhotosSubtitle = if (totalImageCount > 0) "$totalImageCount photos" else null
        container.addView(createRow("All Photos (random)", allPhotosSubtitle, allPhotosMode))

        // Album rows
        albums.forEach { album ->
            val isSelected = !allPhotosMode && selectedIds.contains(album.id)
            val subtitle = "${album.assetCount} photo${if (album.assetCount != 1) "s" else ""}"
            container.addView(createRow(album.albumName, subtitle, isSelected))
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.apply {
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes?.apply { dimAmount = 0.5f }
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialogView.findViewById<android.widget.Button>(com.dashieapp.Dashie.R.id.buttonSave).setOnClickListener {
            val albumsJson = if (allPhotosMode || selectedIds.isEmpty()) {
                "*"
            } else {
                org.json.JSONArray(selectedIds.toList()).toString()
            }
            screensaverPrefs.immichSelectedAlbums = albumsJson
            android.util.Log.i("SettingsDialogWiring", "📷 Immich albums set to: $albumsJson")

            // Also update central store
            saveCentralImmichToken(com.dashieapp.Dashie.halite.HalitePreferences(activity))

            onChanged()
            dialog.dismiss()
        }

        // "All Photos" row click
        container.getChildAt(0).setOnClickListener {
            allPhotosMode = true
            selectedIds.clear()
            updateRowVisuals()
        }

        // Album row clicks (multi-select toggle)
        albums.forEachIndexed { index, album ->
            container.getChildAt(index + 1).setOnClickListener {
                allPhotosMode = false
                if (selectedIds.contains(album.id)) {
                    selectedIds.remove(album.id)
                    // If nothing selected, revert to all
                    if (selectedIds.isEmpty()) allPhotosMode = true
                } else {
                    selectedIds.add(album.id)
                }
                updateRowVisuals()
            }
        }

        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.setDefaultFocusOnCancel(dialogView)
    }

    // ── MA Login Dialog ─────────────────────────────────────────────────

    /** Pending callback for music enable — called from SettingsActivity.onResume after MA login. */
    @Volatile
    private var pendingMusicEnableCallback: (() -> Unit)? = null

    /**
     * Called from SettingsActivity.onResume to complete the music enable flow
     * after returning from MaLoginActivity.
     */
    fun checkPendingMusicEnable(prefs: HalitePreferences, activity: SettingsActivity? = null) {
        val callback = pendingMusicEnableCallback ?: return
        pendingMusicEnableCallback = null
        if (prefs.connection.hasMaApiToken) {
            // Login succeeded — save token centrally for other devices
            saveCentralMaToken(prefs)
            callback()
        } else {
            // User cancelled login — refresh the settings fragment to revert the toggle
            android.util.Log.i("SettingsDialogWiring", "🎵 MA login cancelled — reverting toggle")
            activity?.let { act ->
                val fragment = act.supportFragmentManager.findFragmentById(
                    com.dashieapp.Dashie.R.id.settingsFragmentContainer
                )
                if (fragment is SchemaSettingsFragment) {
                    fragment.refresh()
                }
            }
        }
    }

    fun showMaLoginDialog(activity: SettingsActivity, maUrl: String, proceed: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.dialog_background)
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // Title
        android.widget.TextView(activity).apply {
            text = "Login to Music Assistant"
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            container.addView(this)
        }

        // Spacer
        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
            container.addView(this)
        }

        // Message
        android.widget.TextView(activity).apply {
            val msgPrefix = "To use ${activity.brandName()} as a music assistant player you need to authorize access. "
            val msgBold = "Sign in with your Home Assistant admin account to enable long term access and user profiles"
            val msgSuffix = " — this only needs to be done once."
            val spannable = android.text.SpannableString(msgPrefix + msgBold + msgSuffix).apply {
                setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    msgPrefix.length,
                    msgPrefix.length + msgBold.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            text = spannable
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            setLineSpacing(dp(4).toFloat(), 1f)
            container.addView(this)
        }

        // Spacer
        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
            container.addView(this)
        }

        // MA URL label
        android.widget.TextView(activity).apply {
            text = "Music Assistant URL"
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 12f
            container.addView(this)
        }

        // Spacer
        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(4))
            container.addView(this)
        }

        // MA URL input
        val urlInput = android.widget.EditText(activity).apply {
            setText(maUrl)
            hint = "http://192.168.1.x:8095"
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_primary))
            setHintTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            container.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        // Spacer
        android.view.View(activity).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(20))
            container.addView(this)
        }

        // Buttons row
        val buttons = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val dialog = android.app.AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(true)
            .create()

        // Cancel button
        android.widget.Button(activity).apply {
            text = "Cancel"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_border)
            setTextColor(activity.getColor(com.dashieapp.Dashie.R.color.text_secondary))
            textSize = 14f
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { dialog.dismiss() }
            buttons.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { marginEnd = dp(12) })
        }

        // Sign In button
        android.widget.Button(activity).apply {
            text = "Sign In"
            setBackgroundResource(com.dashieapp.Dashie.R.drawable.button_primary)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isAllCaps = false
            minimumHeight = dp(40)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                val enteredUrl = urlInput.text.toString().trim().trimEnd('/')
                if (enteredUrl.isEmpty() || !enteredUrl.startsWith("http")) {
                    urlInput.error = "Enter a valid URL (e.g. http://192.168.1.x:8095)"
                    return@setOnClickListener
                }
                dialog.dismiss()
                pendingMusicEnableCallback = proceed
                val intent = com.dashieapp.Dashie.halite.music.MaLoginActivity.createIntent(activity, enteredUrl)
                activity.startActivity(intent)
            }
            buttons.addView(this, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        }

        container.addView(buttons)

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper.applyImmersiveModeToDialog(dialog)
    }
}
