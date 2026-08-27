package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.widgets.WidgetIconRegistry
import com.dashieapp.Dashie.halite.widgets.WidgetMenuOverlay
import org.json.JSONArray

/**
 * Wires the generic widget overlay JS bridge callbacks (showWidgetHint /
 * showWidgetMenu / showWidgetSettings + their setters and hide methods)
 * to the shared overlay instances on HaliteComponentRegistry.
 *
 * One instance of each shared overlay handles every widget — only one
 * menu/settings/hint is open at a time. Each show() carries the widgetId
 * + jsCoordinator so touch row taps round-trip to the correct JS object.
 *
 * JS coordinators (rotator-menu.js, photos-menu.js, …) call into this
 * via the `showWidget*` @JavascriptInterface methods on DashieJSBridge
 * (which forward to JsBridgeLayoutDelegate).
 */
object WidgetOverlayWiring {
    private const val TAG = "WidgetOverlayWiring"

    fun wireWidgetOverlayCallbacks(registry: HaliteComponentRegistry) {
        val jsBridge = registry.jsBridge ?: run {
            Log.w(TAG, "JS bridge not ready; widget overlay callbacks NOT wired")
            return
        }
        val delegate = jsBridge.layoutDelegate
        val activity = registry.activityRef

        // Hint
        delegate.onWidgetHintShow = { _, x, y, w, h, text ->
            registry.widgetHintOverlay.show(x, y, w, h, text)
        }
        delegate.onWidgetHintHide = { _ ->
            registry.widgetHintOverlay.hide()
        }

        // Bar menu
        delegate.onWidgetMenuShow = { widgetId, jsCoord, x, y, w, h, itemsJson, selectedIndex ->
            val items = parseMenuItems(itemsJson)
            registry.widgetMenuOverlay.show(widgetId, jsCoord, x, y, w, h, items, selectedIndex)
        }
        delegate.onWidgetMenuSelection = { _, index ->
            registry.widgetMenuOverlay.setSelectedIndex(index)
        }
        delegate.onWidgetMenuItemUpdate = { _, index, label, iconKey ->
            registry.widgetMenuOverlay.setItem(index, label, WidgetIconRegistry.resolve(iconKey))
        }
        delegate.onWidgetMenuHide = { _ ->
            registry.widgetMenuOverlay.hide()
        }

        // Settings
        delegate.onWidgetSettingsShow = { widgetId, jsCoord, title, x, y, w, h, itemsJson, selectedIndex ->
            registry.widgetSettingsOverlay.show(widgetId, jsCoord, title, x, y, w, h, itemsJson, selectedIndex)
        }
        delegate.onWidgetSettingsSelection = { _, index ->
            registry.widgetSettingsOverlay.setSelection(index)
        }
        delegate.onWidgetSettingsItemChecked = { _, index, checked ->
            registry.widgetSettingsOverlay.setItemChecked(index, checked)
        }
        delegate.onWidgetSettingsHide = { _ ->
            registry.widgetSettingsOverlay.hide()
        }

        Log.i(TAG, "Widget overlay callbacks wired")
    }

    /** Parse the JS items JSON array into MenuItem(id, label, iconRes).
     *  JSON item shape: { id, label, icon }, where icon is a string key
     *  resolved by WidgetIconRegistry. */
    private fun parseMenuItems(json: String): List<WidgetMenuOverlay.MenuItem> {
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<WidgetMenuOverlay.MenuItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val label = obj.optString("label", "")
            val iconKey = obj.optString("icon", "")
            val iconRes = WidgetIconRegistry.resolve(iconKey)
            out.add(WidgetMenuOverlay.MenuItem(id, label, iconRes))
        }
        return out
    }
}
