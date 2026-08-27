package com.dashieapp.Dashie.halite.settings.fragments

import android.util.Log
import com.dashieapp.Dashie.halite.HaDiscovery
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.halite.settings.items.SettingsItem

/**
 * Custom fragment for Home Assistant URL configuration.
 * Renders within the settings RecyclerView — reuses HaDiscovery and
 * ConnectionPreferences URL building logic from HaUrlBuilderActivity.
 *
 * The reload prompt is handled by the parent HA settings page when
 * the user exits the HA settings entirely.
 */
class SettingsHaUrlFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "SettingsHaUrl"
    }

    override val title: String = "Dashboard URL"

    private val prefs by lazy { HalitePreferences(requireContext()) }
    private val connPrefs get() = prefs.connection

    // mDNS discovery
    private var haDiscovery: HaDiscovery? = null
    private var discoveryThread: Thread? = null
    @Volatile private var isDiscoveryRunning = false
    private var detectedUrl: String? = null

    override fun onResume() {
        super.onResume()
        startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        stopDiscovery()
    }

    override fun getItems(): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()
        val autoBuild = connPrefs.autoBuildUrl

        // Auto-build toggle
        items.add(SettingsItem.Toggle(
            id = "auto_build",
            label = "Auto-Build URL",
            isChecked = autoBuild
        ))

        // mDNS detection info
        detectedUrl?.let { url ->
            items.add(SettingsItem.Info(
                id = "detected_url",
                text = "Detected Home Assistant at $url"
            ))
        }

        if (autoBuild) {
            // Component-based URL building
            items.add(SettingsItem.SectionHeader(
                id = "header_url_components",
                title = "URL Components"
            ))
            items.add(SettingsItem.TextInput(
                id = "base_url",
                label = "Base URL",
                value = connPrefs.haBaseUrl,
                placeholder = "http://192.168.1.x:8123"
            ))
            items.add(SettingsItem.TextInput(
                id = "dashboard_path",
                label = "Dashboard Path",
                value = connPrefs.dashboardName,
                placeholder = "lovelace/my-dashboard (optional)"
            ))

            // Built URL preview
            val builtUrl = connPrefs.buildFullUrl()
            if (builtUrl.isNotEmpty() && builtUrl != HalitePreferences.DEFAULT_HA_URL) {
                items.add(SettingsItem.ValueDisplay(
                    id = "built_url_preview",
                    label = "URL",
                    value = builtUrl
                ))
            }
        } else {
            // Manual URL entry
            items.add(SettingsItem.SectionHeader(
                id = "header_manual_url",
                title = "Dashboard URL"
            ))
            items.add(SettingsItem.TextInput(
                id = "manual_url",
                label = "URL",
                value = connPrefs.haUrl,
                placeholder = "http://192.168.1.x:8123/lovelace/0"
            ))
        }

        return items
    }

    override fun handleToggleChange(item: SettingsItem.Toggle, newValue: Boolean) {
        when (item.id) {
            "auto_build" -> {
                connPrefs.autoBuildUrl = newValue
                if (newValue) {
                    val currentUrl = connPrefs.haUrl
                    if (currentUrl.isNotEmpty() && currentUrl != HalitePreferences.DEFAULT_HA_URL) {
                        connPrefs.parseUrl(currentUrl)
                    }
                } else {
                    val builtUrl = connPrefs.buildFullUrl()
                    if (builtUrl.isNotEmpty()) {
                        connPrefs.haUrl = builtUrl
                    }
                }
                refreshItems()
            }
        }
    }

    override fun handleItemClick(item: SettingsItem) {
        // No clickable items in this fragment
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun handleTextChange(item: SettingsItem.TextInput, newValue: String) {
        when (item.id) {
            "base_url" -> {
                val oldUrl = connPrefs.haUrl
                connPrefs.haBaseUrl = newValue
                connPrefs.haUrl = connPrefs.buildFullUrl()
                refreshItems()
                if (connPrefs.haUrl != oldUrl) markNeedsReload()
            }
            "dashboard_path" -> {
                val oldUrl = connPrefs.haUrl
                connPrefs.dashboardName = newValue
                connPrefs.haUrl = connPrefs.buildFullUrl()
                refreshItems()
                if (connPrefs.haUrl != oldUrl) markNeedsReload()
            }
            "manual_url" -> {
                val oldUrl = connPrefs.haUrl
                val normalized = normalizeUrl(newValue)
                if (normalized != null) {
                    connPrefs.haUrl = normalized
                    if (connPrefs.haUrl != oldUrl) markNeedsReload()
                }
            }
        }
    }

    private var needsReload = false

    private fun markNeedsReload() {
        needsReload = true
        Log.i(TAG, "🔄 HA URL changed to: ${connPrefs.haUrl} — will reload on exit")
    }

    override fun onDestroyView() {
        if (needsReload) {
            // Cloud-mode (Dashie account linked): the main WebView is on
            // dev.dashieapp.com / dashieapp.com, NOT the HA URL — so a
            // full-app restart is not just unnecessary, it actively breaks
            // the flow. The cold start re-syncs settings from Supabase
            // and the JS settingsStore.home_assistant.url (still the OLD
            // value) overwrites the Kotlin haUrl we just saved → URL
            // change is lost. Skip the restart; the Kotlin pref save is
            // sufficient for the immediate HA-login flow.
            //
            // Kiosk-mode (no Dashie account): the main WebView IS the HA
            // URL, so a restart with the new URL is correct.
            val isCloudMode = prefs.account.isLinked
            if (isCloudMode) {
                Log.i(TAG, "🔄 URL changed (cloud-mode) — skipping app restart, Kotlin pref already saved")
                // Audit 2026-07-04 §2B: the Kotlin pref save alone left the
                // cloud HA blob (and the Console) showing the OLD URL until
                // the next boot upload. Fire the generic HA-config broadcast
                // so MainBroadcastManager dispatches home_assistant_config
                // and JS persists the fresh blob to user_devices now.
                requireContext().sendBroadcast(
                    android.content.Intent("com.dashieapp.Dashie.ACTION_HA_CONFIG_CHANGED").apply {
                        setPackage(requireContext().packageName)
                    }
                )
            } else {
                Log.i(TAG, "🔄 URL changed (kiosk-mode) — reloading dashboard")
                val intent = android.content.Intent(
                    requireContext(), com.dashieapp.Dashie.MainActivity::class.java
                )
                intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                requireActivity().finish()
            }
        }
        super.onDestroyView()
    }

    // ── mDNS Discovery ──────────────────────────────────────────────────

    private fun startDiscovery() {
        if (isDiscoveryRunning) return
        isDiscoveryRunning = true

        haDiscovery = HaDiscovery(requireContext())
        discoveryThread = Thread {
            haDiscovery?.discoverInstances(object : HaDiscovery.DiscoveryCallback {
                override fun onInstanceFound(instance: HaDiscovery.DiscoveredInstance) {
                    if (!isDiscoveryRunning) return
                    activity?.runOnUiThread {
                        detectedUrl = instance.url
                        val hasUrl = connPrefs.haBaseUrl.isNotEmpty() ||
                            (connPrefs.haUrl.isNotEmpty() && connPrefs.haUrl != HalitePreferences.DEFAULT_HA_URL)
                        if (!hasUrl) {
                            connPrefs.parseUrl(instance.url)
                            connPrefs.haUrl = instance.url
                        }
                        refreshItems()
                    }
                }

                override fun onDiscoveryComplete(instances: List<HaDiscovery.DiscoveredInstance>) {
                    if (!isDiscoveryRunning) return
                    activity?.runOnUiThread {
                        if (instances.isEmpty()) {
                            detectedUrl = null
                            refreshItems()
                        }
                    }
                }

                override fun onProgress(scannedCount: Int, totalCount: Int) {}
                override fun onError(message: String) {
                    Log.w(TAG, "Discovery error: $message")
                }
            }, timeoutMs = 15000)
        }
        discoveryThread?.start()
    }

    private fun stopDiscovery() {
        isDiscoveryRunning = false
        discoveryThread?.interrupt()
        discoveryThread = null
        haDiscovery = null
    }

    // ── URL Normalization ───────────────────────────────────────────────

    private fun normalizeUrl(input: String): String? {
        var url = input.trim()
        if (url.isEmpty()) return null

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }

        return try {
            val parsed = java.net.URL(url)
            val queryPart = if (!parsed.query.isNullOrEmpty()) "?${parsed.query}" else ""
            if (parsed.port == -1) {
                "${parsed.protocol}://${parsed.host}${parsed.path}${queryPart}"
            } else {
                "${parsed.protocol}://${parsed.host}:${parsed.port}${parsed.path}${queryPart}"
            }.trimEnd('/')
        } catch (e: Exception) {
            Log.e(TAG, "Invalid URL: $input", e)
            null
        }
    }
}
