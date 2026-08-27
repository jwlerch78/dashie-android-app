package com.dashieapp.Dashie.halite

import android.app.Activity
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.dashieapp.Dashie.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Controls tab navigation mode for Home Assistant dashboards.
 *
 * Responsibilities:
 * - Fetch dashboard views from HA API
 * - Manage tab navigation state (active/inactive)
 * - Display toast overlay showing current tab
 * - Handle left/right navigation between tabs
 * - Navigate WebView to selected tab
 *
 * Flow:
 * 1. User long-presses right edge (2s) while sidebar is open
 * 2. Sidebar closes, tab nav mode activates
 * 3. Fetch tabs from HA API (cache for session)
 * 4. Show toast: "Tab Navigation: Kitchen (1/5)"
 * 5. Left/Right arrows navigate tabs (with wrap-around)
 * 6. Escape exits tab nav mode, re-opens sidebar
 */
class TabNavigationController(
    private val activity: Activity,
    private val halitePrefs: HalitePreferences,
    private val getDashboardViews: () -> String?,  // Callback to bridge method
    private val navigateToView: (String) -> Unit,  // Callback to bridge method
    private val onExitTabNavMode: () -> Unit       // Callback to re-open sidebar
) {
    companion object {
        private const val TAG = "TabNavigation"
    }

    // Tab navigation state
    private var isTabNavModeActive = false
    private var currentTabs: List<DashboardView> = emptyList()
    private var currentTabIndex: Int = 0

    // Toast overlay for showing current tab
    private var tabToast: Toast? = null
    private var toastTextView: TextView? = null

    /**
     * Data class representing a dashboard view/tab.
     */
    data class DashboardView(
        val path: String,
        val title: String,
        val icon: String?
    )

    /**
     * Check if tab navigation mode is currently active.
     */
    fun isActive(): Boolean = isTabNavModeActive

    /**
     * Enter tab navigation mode.
     * Fetches tabs from HA API and shows overlay.
     *
     * @param currentPath The current dashboard path (e.g., "/lovelace/kitchen")
     */
    fun enterTabNavMode(currentPath: String) {
        if (isTabNavModeActive) {
            Log.w(TAG, "Already in tab nav mode")
            return
        }

        Log.i(TAG, "Entering tab navigation mode - current path: $currentPath")

        // Fetch tabs in background
        thread {
            try {
                val views = fetchDashboardViews()
                if (views.isEmpty()) {
                    activity.runOnUiThread {
                        showError("No tabs found")
                    }
                    return@thread
                }

                // Find current tab index
                val currentIndex = views.indexOfFirst { view ->
                    currentPath.endsWith(view.path) || currentPath.endsWith("/${view.path}")
                }

                activity.runOnUiThread {
                    currentTabs = views
                    currentTabIndex = if (currentIndex >= 0) currentIndex else 0
                    isTabNavModeActive = true

                    showTabToast()
                    Log.i(TAG, "Tab nav mode active - ${views.size} tabs, current index: $currentTabIndex")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch dashboard views", e)
                activity.runOnUiThread {
                    showError("Failed to load tabs")
                }
            }
        }
    }

    /**
     * Exit tab navigation mode.
     * Hides toast and calls callback to re-open sidebar.
     */
    fun exitTabNavMode() {
        if (!isTabNavModeActive) return

        Log.i(TAG, "Exiting tab navigation mode")
        isTabNavModeActive = false
        hideTabToast()
        currentTabs = emptyList()

        // Callback to re-open sidebar
        onExitTabNavMode()
    }

    /**
     * Navigate to the previous tab (with wrap-around).
     */
    fun navigatePrevious() {
        if (!isTabNavModeActive || currentTabs.isEmpty()) return

        currentTabIndex = if (currentTabIndex > 0) {
            currentTabIndex - 1
        } else {
            currentTabs.size - 1  // Wrap to last
        }

        navigateToCurrentTab()
    }

    /**
     * Navigate to the next tab (with wrap-around).
     */
    fun navigateNext() {
        if (!isTabNavModeActive || currentTabs.isEmpty()) return

        currentTabIndex = if (currentTabIndex < currentTabs.size - 1) {
            currentTabIndex + 1
        } else {
            0  // Wrap to first
        }

        navigateToCurrentTab()
    }

    /**
     * Navigate WebView to the currently selected tab and update toast.
     */
    private fun navigateToCurrentTab() {
        val view = currentTabs[currentTabIndex]
        Log.i(TAG, "Navigating to tab: ${view.title} (${currentTabIndex + 1}/${currentTabs.size})")

        // Navigate WebView
        navigateToView(view.path)

        // Update toast
        updateTabToast()
    }

    /**
     * Fetch dashboard views from HA API via bridge.
     * @return List of dashboard views
     */
    private fun fetchDashboardViews(): List<DashboardView> {
        val json = getDashboardViews() ?: throw Exception("Failed to fetch dashboard views")
        val viewsArray = JSONArray(json)
        val views = mutableListOf<DashboardView>()

        for (i in 0 until viewsArray.length()) {
            val viewObj = viewsArray.getJSONObject(i)
            // Use explicit path if present, otherwise use index
            val path = viewObj.optString("path", i.toString())
            views.add(
                DashboardView(
                    path = path,
                    title = viewObj.optString("title", "Tab ${i + 1}"),
                    icon = viewObj.optString("icon", null)
                )
            )
        }

        Log.i(TAG, "Fetched ${views.size} dashboard views")
        return views
    }

    /**
     * Show toast overlay with current tab info.
     * Format: "Tab Navigation: Kitchen (1/5)"
     */
    private fun showTabToast() {
        hideTabToast()  // Clean up any existing toast

        val view = currentTabs[currentTabIndex]
        val message = "Tab Navigation: ${view.title} (${currentTabIndex + 1}/${currentTabs.size})"

        // Create custom toast view
        val inflater = activity.layoutInflater
        val layout = inflater.inflate(
            android.R.layout.simple_list_item_1,
            null
        ) as TextView

        layout.apply {
            text = message
            textSize = 16f
            setPadding(40, 20, 40, 20)
            setBackgroundColor(0xDD000000.toInt())  // Semi-transparent black
            setTextColor(0xFFFFFFFF.toInt())        // White text
        }

        tabToast = Toast(activity).apply {
            duration = Toast.LENGTH_LONG  // Will be shown continuously
            setView(layout)
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 100)
            show()
        }

        toastTextView = layout
    }

    /**
     * Update toast text with current tab info.
     */
    private fun updateTabToast() {
        val view = currentTabs[currentTabIndex]
        val message = "Tab Navigation: ${view.title} (${currentTabIndex + 1}/${currentTabs.size})"

        toastTextView?.text = message

        // Keep toast visible by re-showing
        tabToast?.show()
    }

    /**
     * Hide tab navigation toast.
     */
    private fun hideTabToast() {
        tabToast?.cancel()
        tabToast = null
        toastTextView = null
    }

    /**
     * Show error toast briefly.
     */
    private fun showError(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Clean up resources.
     */
    fun destroy() {
        hideTabToast()
        currentTabs = emptyList()
        isTabNavModeActive = false
    }
}
