package com.dashieapp.Dashie.halite.settings.fragments

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.settings.items.SettingsItem

/**
 * Searchable entity picker for camera and trigger entities.
 * Shows entity friendly name with entity_id as sublabel.
 * Trigger mode includes "Top Matches" and "All Sensors" sections.
 */
class EntityPickerFragment : BaseSettingsFragment() {

    data class EntityInfo(
        val entityId: String,
        val friendlyName: String,
        val deviceClass: String = ""
    )

    companion object {
        fun create(
            domain: String,
            currentValue: String,
            cameraEntityId: String? = null,
            onSelect: (entityId: String, friendlyName: String) -> Unit
        ): EntityPickerFragment {
            return EntityPickerFragment().apply {
                this.domain = domain
                this.currentValue = currentValue
                this.cameraEntityId = cameraEntityId
                this.onSelect = onSelect
            }
        }

        private val DEVICE_CLASS_LABELS = mapOf(
            "motion" to "Motion", "door" to "Door", "window" to "Window",
            "opening" to "Opening", "occupancy" to "Occupancy", "presence" to "Presence",
            "connectivity" to "Connectivity", "sound" to "Sound", "vibration" to "Vibration",
            "helper" to "Helper"
        )
    }

    private lateinit var domain: String
    private lateinit var currentValue: String
    private var cameraEntityId: String? = null
    private var onSelect: (String, String) -> Unit = { _, _ -> }

    private var allEntities: List<EntityInfo> = emptyList()
    private var searchQuery = ""
    private var isLoading = true
    private var loadError: String? = null

    override val title: String
        get() = if (domain == "camera") "Select Camera" else "Select Trigger"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addSearchBar(view)
        fetchEntities()
    }

    private fun addSearchBar(view: View) {
        val parent = recyclerView.parent as? LinearLayout ?: return
        val rvIndex = parent.indexOfChild(recyclerView)
        val density = resources.displayMetrics.density

        val container = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val hPad = resources.getDimensionPixelSize(R.dimen.settings_section_margin_horizontal)
            setPadding(hPad, (12 * density).toInt(), hPad, (4 * density).toInt())
        }

        val searchInput = EditText(requireContext()).apply {
            hint = if (domain == "camera") "Search cameras..." else "Search sensors..."
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

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString() ?: ""
                refreshItems()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        container.addView(searchInput)
        parent.addView(container, rvIndex)
    }

    override fun getItems(): List<SettingsItem> {
        if (isLoading) {
            return listOf(SettingsItem.Info(id = "loading", text = "Loading entities..."))
        }
        if (loadError != null) {
            return listOf(SettingsItem.Info(id = "error", text = loadError!!))
        }
        if (allEntities.isEmpty()) {
            val type = if (domain == "camera") "cameras" else "sensors"
            return listOf(SettingsItem.Info(id = "empty", text = "No $type found in Home Assistant"))
        }

        val query = searchQuery.lowercase()
        val filtered = if (query.isEmpty()) allEntities
        else allEntities.filter { e ->
            e.friendlyName.lowercase().contains(query) ||
            e.entityId.lowercase().contains(query) ||
            e.deviceClass.lowercase().contains(query)
        }

        if (filtered.isEmpty()) {
            return listOf(SettingsItem.Info(
                id = "no_match", text = "No matches for \"$searchQuery\""
            ))
        }

        val items = mutableListOf<SettingsItem>()

        // Trigger mode: show "Top Matches" and "All Sensors" sections
        if (domain == "trigger" && query.isEmpty() && cameraEntityId?.isNotEmpty() == true) {
            val topMatches = getTopMatches(filtered)
            val topMatchIds = topMatches.map { it.entityId }.toSet()

            if (topMatches.isNotEmpty()) {
                items.add(SettingsItem.SectionHeader(id = "header_top", title = "Top Matches"))
                topMatches.forEach { items.add(entityToCheckmark(it)) }
                items.add(SettingsItem.SectionHeader(id = "header_all", title = "All Sensors"))
            }

            filtered.filter { it.entityId !in topMatchIds }.forEach {
                items.add(entityToCheckmark(it))
            }
        } else {
            filtered.forEach { items.add(entityToCheckmark(it)) }
        }

        return items
    }

    private fun entityToCheckmark(entity: EntityInfo): SettingsItem.Checkmark {
        val badge = if (domain == "trigger" && entity.deviceClass.isNotEmpty()) {
            val label = DEVICE_CLASS_LABELS[entity.deviceClass.lowercase()]
                ?: entity.deviceClass.replaceFirstChar { it.uppercase() }
            " · $label"
        } else ""

        return SettingsItem.Checkmark(
            id = entity.entityId,
            label = "${entity.friendlyName}$badge",
            sublabel = entity.entityId,
            isChecked = entity.entityId == currentValue
        )
    }

    override fun handleItemClick(item: SettingsItem) {
        if (item is SettingsItem.Checkmark) {
            val entityId = item.id
            val friendlyName = allEntities.find { it.entityId == entityId }?.friendlyName ?: entityId
            onSelect(entityId, friendlyName)
            navigateBack()
        }
    }

    // ── Entity Fetching ─────────────────────────────────────────────

    private fun fetchEntities() {
        isLoading = true
        loadError = null
        refreshItems()

        Thread {
            try {
                val halitePrefs = com.dashieapp.Dashie.halite.HalitePreferences(requireContext())
                val entities = fetchEntitiesByDomain(halitePrefs)
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    allEntities = entities.sortedBy { it.friendlyName.lowercase() }
                    isLoading = false
                    refreshItems()
                }
            } catch (e: Exception) {
                if (!isAdded) return@Thread
                requireActivity().runOnUiThread {
                    isLoading = false
                    loadError = "Failed to load entities: ${e.message}"
                    refreshItems()
                }
            }
        }.start()
    }

    private fun fetchEntitiesByDomain(
        halitePrefs: com.dashieapp.Dashie.halite.HalitePreferences
    ): List<EntityInfo> {
        val (baseUrl, token) = com.dashieapp.Dashie.halite.HaTokenExtractor.getValidCredentialsSync(halitePrefs) ?: return emptyList()

        val client = com.dashieapp.Dashie.util.LocalHostsTrustingHttpClient.builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val request = okhttp3.Request.Builder()
            .url("$baseUrl/api/states")
            .addHeader("Authorization", "Bearer $token")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val arr = org.json.JSONArray(body)
            val prefixes = if (domain == "trigger") {
                listOf("binary_sensor.", "input_boolean.")
            } else {
                listOf("$domain.")
            }
            val entities = mutableListOf<EntityInfo>()

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val entityId = obj.getString("entity_id")
                if (prefixes.any { entityId.startsWith(it) }) {
                    val attrs = obj.optJSONObject("attributes") ?: org.json.JSONObject()
                    val friendlyName = attrs.optString("friendly_name", entityId)
                    val deviceClass = attrs.optString("device_class", "")
                    val dc = if (entityId.startsWith("input_boolean.") && deviceClass.isEmpty())
                        "helper" else deviceClass
                    entities.add(EntityInfo(entityId, friendlyName, dc))
                }
            }

            return entities
        }
    }

    // ── Top Matches (Trigger Mode) ──────────────────────────────────

    /**
     * Find top trigger matches based on camera entity name similarity.
     * Extracts prefix tokens from camera entity_id and scores triggers by match.
     * Prefers motion/occupancy device classes.
     */
    private fun getTopMatches(entities: List<EntityInfo>): List<EntityInfo> {
        val camId = cameraEntityId ?: return emptyList()
        val baseName = camId.removePrefix("camera.")
        val parts = baseName.split("_")

        val prefixes = mutableListOf<String>()
        for (len in minOf(3, parts.size - 1) downTo 2) {
            prefixes.add(parts.take(len).joinToString("_"))
        }
        if (parts.isNotEmpty() && parts[0].length > 2) prefixes.add(parts[0])
        if (prefixes.isEmpty()) return emptyList()

        val motionOccupancyClasses = setOf("motion", "occupancy")

        val scored = entities
            .filter { it.entityId != currentValue }
            .filter { it.deviceClass.lowercase() in motionOccupancyClasses }
            .map { entity ->
                val triggerBase = entity.entityId.substringAfter(".")
                var score = 0.0
                for ((i, prefix) in prefixes.withIndex()) {
                    if (triggerBase.startsWith(prefix)) {
                        score = (prefixes.size - i + 1).toDouble()
                        break
                    }
                }
                entity to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }

        val selected = if (currentValue.isNotEmpty()) {
            entities.find { it.entityId == currentValue && it.deviceClass.lowercase() in motionOccupancyClasses }
        } else null

        val results = scored.take(if (selected != null) 2 else 3)
            .map { it.first }.toMutableList()

        if (selected != null) {
            val triggerBase = selected.entityId.substringAfter(".")
            if (prefixes.any { triggerBase.startsWith(it) }) {
                results.add(0, selected)
            }
        }

        return results.take(3)
    }
}
