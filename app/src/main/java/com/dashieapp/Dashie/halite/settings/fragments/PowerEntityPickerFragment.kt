package com.dashieapp.Dashie.halite.settings.fragments

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.HaTokenExtractor
import com.dashieapp.Dashie.halite.preferences.PowerPreferences
import com.dashieapp.Dashie.halite.settings.items.SettingsItem
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Custom fragment for selecting a Home Assistant switch entity
 * for battery charging control.
 *
 * Loads switch entities via the HA REST API (no WebView dependency),
 * using the same auth token the [PowerManagementWatchdog] uses.
 *
 * Shows a search input and a checkmark list of switch entities.
 */
class PowerEntityPickerFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "PowerEntityPicker"

        fun create(
            powerPrefs: PowerPreferences,
            halitePrefs: HalitePreferences,
            onDismiss: (() -> Unit)? = null
        ): PowerEntityPickerFragment {
            return PowerEntityPickerFragment().apply {
                this.powerPrefs = powerPrefs
                this.halitePrefs = halitePrefs
                this.onDismiss = onDismiss
            }
        }
    }

    private lateinit var powerPrefs: PowerPreferences
    private lateinit var halitePrefs: HalitePreferences
    private var onDismiss: (() -> Unit)? = null

    private var switchEntities: List<SwitchEntity> = emptyList()
    private var isLoading = true
    private var loadError: String? = null
    private var searchQuery = ""

    override val title: String = "Smart Switch Entity"

    private val httpClient = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class SwitchEntity(
        val entityId: String,
        val friendlyName: String,
        val state: String
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadEntities()
    }

    override fun getItems(): List<SettingsItem> {
        if (isLoading) {
            return listOf(
                SettingsItem.Info(id = "loading", text = "Loading switch entities...")
            )
        }

        if (switchEntities.isEmpty()) {
            val reason = loadError ?: "No switch entities found."
            return listOf(
                SettingsItem.Info(id = "empty", text = reason)
            )
        }

        val currentEntityId = powerPrefs.entityId
        val query = searchQuery.lowercase()

        val filtered = switchEntities.filter { entity ->
            if (query.isEmpty()) true
            else entity.friendlyName.lowercase().contains(query) ||
                 entity.entityId.lowercase().contains(query)
        }.sortedWith(compareBy(
            { it.entityId != currentEntityId }, // selected first
            { it.friendlyName.lowercase() }
        ))

        if (filtered.isEmpty()) {
            return listOf(
                SettingsItem.Info(id = "no_matches", text = "No matches for \"$searchQuery\"")
            )
        }

        return filtered.map { entity ->
            SettingsItem.Checkmark(
                id = "entity_${entity.entityId}",
                label = entity.friendlyName,
                isChecked = entity.entityId == currentEntityId,
                sublabel = entity.entityId
            )
        }
    }

    override fun handleItemClick(item: SettingsItem) {
        if (item is SettingsItem.Checkmark) {
            val entityId = item.id.removePrefix("entity_")
            powerPrefs.entityId = entityId
            refreshItems()
        }
    }

    override fun onDestroyView() {
        onDismiss?.invoke()
        super.onDestroyView()
    }

    // ── Search ───────────────────────────────────────────────────────────

    /**
     * Add a search EditText above the RecyclerView.
     * Matches the EntityPickerFragment search bar style.
     */
    private fun addSearchInput() {
        val parent = recyclerView.parent as? LinearLayout ?: return
        val density = resources.displayMetrics.density
        val rvIndex = parent.indexOfChild(recyclerView)

        val container = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val hPad = resources.getDimensionPixelSize(R.dimen.settings_section_margin_horizontal)
            setPadding(hPad, (12 * density).toInt(), hPad, (4 * density).toInt())
        }

        val searchInput = EditText(requireContext()).apply {
            hint = "Search switches..."
            setHintTextColor(0xFF999999.toInt())
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = 10f * density
            }
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    v.clearFocus()
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else false
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                refreshItems()
            }
        })

        container.addView(searchInput)
        parent.addView(container, rvIndex)
    }

    // ── HA REST API entity loading ───────────────────────────────────────

    private fun loadEntities() {
        isLoading = true
        loadError = null
        refreshItems()

        // Add search input after first layout
        addSearchInput()

        Thread {
            try {
                val entities = fetchSwitchEntities()
                activity?.runOnUiThread {
                    switchEntities = entities
                    isLoading = false
                    if (entities.isEmpty() && loadError == null) {
                        loadError = "No switch entities found in Home Assistant"
                    }
                    refreshItems()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load entities", e)
                activity?.runOnUiThread {
                    isLoading = false
                    loadError = "Error: ${e.message}"
                    refreshItems()
                }
            }
        }.start()
    }

    private fun fetchSwitchEntities(): List<SwitchEntity> {
        val conn = halitePrefs.connection
        val baseUrl = conn.haBaseUrl.ifEmpty {
            conn.haUrl.substringBefore("?").trimEnd('/')
        }
        if (baseUrl.isEmpty()) {
            loadError = "No Home Assistant URL configured"
            return emptyList()
        }

        // Get valid token, refreshing if needed
        val credentials = HaTokenExtractor.getValidCredentialsSync(halitePrefs)
        if (credentials == null) {
            loadError = "Home Assistant token expired and refresh failed"
            return emptyList()
        }
        val (_, token) = credentials

        val request = Request.Builder()
            .url("$baseUrl/api/states")
            .addHeader("Authorization", "Bearer $token")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                loadError = "HA API error (HTTP ${response.code})"
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val arr = JSONArray(body)
            val entities = mutableListOf<SwitchEntity>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val entityId = obj.getString("entity_id")
                if (entityId.startsWith("switch.") && !entityId.contains("dashie")) {
                    val attrs = obj.optJSONObject("attributes") ?: JSONObject()
                    entities.add(SwitchEntity(
                        entityId = entityId,
                        friendlyName = attrs.optString("friendly_name", entityId),
                        state = obj.optString("state", "unknown")
                    ))
                }
            }

            entities.sortBy { it.friendlyName.lowercase() }
            Log.i(TAG, "Loaded ${entities.size} switch entities")
            return entities
        }
    }
}
