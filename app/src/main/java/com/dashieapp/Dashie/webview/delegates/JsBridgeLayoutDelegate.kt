package com.dashieapp.Dashie.webview.delegates

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * JS bridge delegate for layout system interactions.
 *
 * Handles communication between the JS layout canvas and Kotlin native
 * widgets. When JS discovers a photo slot, it calls these methods to tell
 * Kotlin where to position native widget views.
 */
class JsBridgeLayoutDelegate {

    companion object {
        private const val TAG = "JsBridgeLayout"
    }

    var onKotlinWidgetBounds: ((String, Int, Int, Int, Int, Int, Int) -> Unit)? = null
    var onKotlinWidgetRemoved: ((String) -> Unit)? = null
    var onKotlinWidgetHide: ((String) -> Unit)? = null
    var onKotlinWidgetShow: ((String) -> Unit)? = null
    var onResetNativeWidgetGateCallback: (() -> Unit)? = null
    var onRotatorBarBounds: ((String, Int, Int, Int, Int) -> Unit)? = null
    var onRotatorBarRemoved: ((String) -> Unit)? = null
    var onRotatorBarTouchSignal: ((String) -> Unit)? = null
    var onRotatorBarPausedChanged: ((String, Boolean) -> Unit)? = null
    /** Replace the strip's view list. viewsJson = [{type, label}, ...]. */
    var onRotatorViews: ((String, String, String?) -> Unit)? = null
    /** Update only the active widget. */
    var onRotatorActiveView: ((String, String?) -> Unit)? = null
    /** Update only the frozen widget. null = unfrozen. */
    var onRotatorFrozenView: ((String, String?) -> Unit)? = null
    var onSwapKotlinWidgets: ((String, String) -> Unit)? = null
    var onRotatorPauseIndicatorShow: ((String, Int, Int, Int, Int) -> Unit)? = null
    var onRotatorPauseIndicatorHide: ((String) -> Unit)? = null

    // Photos widget — domain-specific actions (full screen, upload, source
    // type). Menu rendering goes through the generic widget overlay
    // callbacks below.
    var onPhotosExplorerOpen: ((String) -> Unit)? = null
    var onPhotosUploadOpen: ((String) -> Unit)? = null
    /** Manual cycle from the JS photos-menu 'activated' state — left/right
     *  on the d-pad. widgetId is unused (only one photo widget) but kept
     *  for symmetry with the other photo bridge methods. */
    var onPhotoNext: ((String) -> Unit)? = null
    var onPhotoPrevious: ((String) -> Unit)? = null
    /** Sync getter — must return immediately on the calling thread. */
    var onPhotosSourceTypeQuery: (() -> String)? = null
    var onPhotosSourceTypeSet: ((String) -> Unit)? = null
    /** True only when the photo widget is currently rendering the Dashie
     *  Cloud "Click to add photos" empty state. */
    var onPhotosShowingDashieCloudEmptyQuery: (() -> Boolean)? = null

    // ============================================================================
    // Generic widget overlay callbacks (go-forward pattern). Each widget that
    // wants a TV menu has a JS coordinator (e.g. window.rotatorMenu, window.
    // photosMenu); the coordinator passes its own name through these calls so
    // touch row taps can be round-tripped back to the right JS object.
    // ============================================================================

    var onWidgetHintShow: ((widgetId: String, x: Int, y: Int, w: Int, h: Int, text: String) -> Unit)? = null
    var onWidgetHintHide: ((widgetId: String) -> Unit)? = null
    var onWidgetMenuShow: ((widgetId: String, jsCoordinator: String, x: Int, y: Int, w: Int, h: Int, itemsJson: String, selectedIndex: Int) -> Unit)? = null
    var onWidgetMenuSelection: ((widgetId: String, index: Int) -> Unit)? = null
    var onWidgetMenuItemUpdate: ((widgetId: String, index: Int, label: String, iconKey: String) -> Unit)? = null
    var onWidgetMenuHide: ((widgetId: String) -> Unit)? = null
    var onWidgetSettingsShow: ((widgetId: String, jsCoordinator: String, title: String, x: Int, y: Int, w: Int, h: Int, itemsJson: String, selectedIndex: Int) -> Unit)? = null
    var onWidgetSettingsSelection: ((widgetId: String, index: Int) -> Unit)? = null
    var onWidgetSettingsItemChecked: ((widgetId: String, index: Int, checked: Boolean) -> Unit)? = null
    var onWidgetSettingsHide: ((widgetId: String) -> Unit)? = null

    // Multiple subscribers — widget visibility gate, backdrop restore, etc.
    // Callbacks fire in registration order on the UI thread (whichever
    // thread DashieNative.onDashboardReady was invoked from).
    private val onDashboardReadyCallbacks = mutableListOf<() -> Unit>()

    /** Subscribe to the JS-driven "dashboard is visible" signal. */
    fun addOnDashboardReadyCallback(cb: () -> Unit) {
        onDashboardReadyCallbacks.add(cb)
    }

    /** Legacy single-callback setter — replaces the entire subscriber list. */
    @Deprecated("Use addOnDashboardReadyCallback to support multiple subscribers")
    var onDashboardReadyCallback: (() -> Unit)?
        get() = onDashboardReadyCallbacks.firstOrNull()
        set(value) {
            onDashboardReadyCallbacks.clear()
            if (value != null) onDashboardReadyCallbacks.add(value)
        }

    @JavascriptInterface
    fun setKotlinWidgetBounds(
        type: String, x: Int, y: Int, w: Int, h: Int,
        gridColumns: Int, gridRows: Int
    ) {
        Log.i(TAG, "setKotlinWidgetBounds: type=$type x=$x y=$y w=$w h=$h grid=${gridColumns}x$gridRows")
        onKotlinWidgetBounds?.invoke(type, x, y, w, h, gridColumns, gridRows)
    }

    @JavascriptInterface
    fun removeKotlinWidget(type: String) {
        Log.i(TAG, "removeKotlinWidget: type=$type")
        onKotlinWidgetRemoved?.invoke(type)
    }

    @JavascriptInterface
    fun hideKotlinWidget(type: String) {
        Log.i(TAG, "hideKotlinWidget: type=$type")
        onKotlinWidgetHide?.invoke(type)
    }

    @JavascriptInterface
    fun showKotlinWidget(type: String) {
        Log.i(TAG, "showKotlinWidget: type=$type")
        onKotlinWidgetShow?.invoke(type)
    }

    @JavascriptInterface
    fun onDashboardReady() {
        Log.i(TAG, "onDashboardReady: JS dashboard framework is visible (${onDashboardReadyCallbacks.size} subscriber(s))")
        // Snapshot to tolerate concurrent add during invoke.
        onDashboardReadyCallbacks.toList().forEach { it.invoke() }
    }

    @JavascriptInterface
    fun resetNativeWidgetGate() {
        Log.i(TAG, "resetNativeWidgetGate: hiding native widgets until next onDashboardReady")
        onResetNativeWidgetGateCallback?.invoke()
    }

    @JavascriptInterface
    fun setRotatorBarBounds(slotId: String, x: Int, y: Int, w: Int, h: Int) {
        Log.i(TAG, "setRotatorBarBounds: slotId=$slotId x=$x y=$y w=$w h=$h")
        onRotatorBarBounds?.invoke(slotId, x, y, w, h)
    }

    @JavascriptInterface
    fun removeRotatorBar(slotId: String) {
        Log.i(TAG, "removeRotatorBar: slotId=$slotId")
        onRotatorBarRemoved?.invoke(slotId)
    }

    @JavascriptInterface
    fun signalRotatorTouch(slotId: String) {
        // Higher-frequency, log at debug only.
        Log.d(TAG, "signalRotatorTouch: slotId=$slotId")
        onRotatorBarTouchSignal?.invoke(slotId)
    }

    @JavascriptInterface
    fun setRotatorPaused(slotId: String, paused: Boolean) {
        Log.i(TAG, "setRotatorPaused: slotId=$slotId paused=$paused")
        onRotatorBarPausedChanged?.invoke(slotId, paused)
    }

    @JavascriptInterface
    fun setRotatorViews(slotId: String, viewsJson: String, activeWidgetType: String?) {
        Log.i(TAG, "setRotatorViews: slotId=$slotId active=$activeWidgetType views=$viewsJson")
        onRotatorViews?.invoke(slotId, viewsJson, activeWidgetType)
    }

    @JavascriptInterface
    fun setRotatorActiveView(slotId: String, activeWidgetType: String?) {
        Log.d(TAG, "setRotatorActiveView: slotId=$slotId active=$activeWidgetType")
        onRotatorActiveView?.invoke(slotId, activeWidgetType)
    }

    @JavascriptInterface
    fun setRotatorFrozenView(slotId: String, frozenWidgetType: String?) {
        Log.d(TAG, "setRotatorFrozenView: slotId=$slotId frozen=$frozenWidgetType")
        onRotatorFrozenView?.invoke(slotId, frozenWidgetType)
    }

    @JavascriptInterface
    fun swapKotlinWidgets(showType: String, hideType: String) {
        Log.i(TAG, "swapKotlinWidgets: show=$showType hide=$hideType")
        onSwapKotlinWidgets?.invoke(showType, hideType)
    }

    @JavascriptInterface
    fun showRotatorPauseIndicator(slotId: String, x: Int, y: Int, w: Int, h: Int) {
        Log.d(TAG, "showRotatorPauseIndicator: slotId=$slotId bounds=[$x,$y,$w,$h]")
        onRotatorPauseIndicatorShow?.invoke(slotId, x, y, w, h)
    }

    @JavascriptInterface
    fun hideRotatorPauseIndicator(slotId: String) {
        Log.d(TAG, "hideRotatorPauseIndicator: slotId=$slotId")
        onRotatorPauseIndicatorHide?.invoke(slotId)
    }

    // ===========================================================================
    // Photos widget — domain-specific actions (full screen, upload, source
    // type). Menu rendering goes through the generic widget overlay bridge.
    // ===========================================================================

    @JavascriptInterface
    fun openPhotosExplorer(widgetId: String) {
        Log.i(TAG, "openPhotosExplorer: widgetId=$widgetId")
        onPhotosExplorerOpen?.invoke(widgetId)
    }

    @JavascriptInterface
    fun openPhotosUpload(widgetId: String) {
        Log.i(TAG, "openPhotosUpload: widgetId=$widgetId")
        onPhotosUploadOpen?.invoke(widgetId)
    }

    @JavascriptInterface
    fun nextPhoto(widgetId: String) {
        Log.d(TAG, "nextPhoto: widgetId=$widgetId")
        onPhotoNext?.invoke(widgetId)
    }

    @JavascriptInterface
    fun previousPhoto(widgetId: String) {
        Log.d(TAG, "previousPhoto: widgetId=$widgetId")
        onPhotoPrevious?.invoke(widgetId)
    }

    /** Sync — JS uses the return value to populate the Source label. */
    @JavascriptInterface
    fun getPhotosSourceType(): String {
        val v = onPhotosSourceTypeQuery?.invoke() ?: ""
        Log.d(TAG, "getPhotosSourceType -> $v")
        return v
    }

    @JavascriptInterface
    fun setPhotosSourceType(sourceType: String) {
        Log.i(TAG, "setPhotosSourceType: $sourceType")
        onPhotosSourceTypeSet?.invoke(sourceType)
    }

    /** Sync — JS uses this when the user d-pads onto the photo widget so
     *  the photos menu can skip the activated/hint state and open the
     *  Upload bar directly. Returns true ONLY when the slideshow is
     *  currently showing the Dashie Cloud "Click to add photos" state. */
    @JavascriptInterface
    fun isPhotosShowingDashieCloudEmpty(): Boolean {
        val v = onPhotosShowingDashieCloudEmptyQuery?.invoke() ?: false
        Log.d(TAG, "isPhotosShowingDashieCloudEmpty -> $v")
        return v
    }

    // ============================================================================
    // Generic widget overlay bridge (go-forward pattern, per-widget keyed by id)
    // ============================================================================

    @JavascriptInterface
    fun showWidgetHint(widgetId: String, x: Int, y: Int, w: Int, h: Int, text: String) {
        Log.d(TAG, "showWidgetHint: widgetId=$widgetId bounds=[$x,$y,$w,$h]")
        onWidgetHintShow?.invoke(widgetId, x, y, w, h, text)
    }

    @JavascriptInterface
    fun hideWidgetHint(widgetId: String) {
        Log.d(TAG, "hideWidgetHint: widgetId=$widgetId")
        onWidgetHintHide?.invoke(widgetId)
    }

    /** itemsJson = JSON array of {id, label, icon} (icon is a string key
     *  resolved by WidgetIconRegistry). */
    @JavascriptInterface
    fun showWidgetMenu(
        widgetId: String, jsCoordinator: String,
        x: Int, y: Int, w: Int, h: Int,
        itemsJson: String, selectedIndex: Int
    ) {
        Log.i(TAG, "showWidgetMenu: widgetId=$widgetId coord=$jsCoordinator bounds=[$x,$y,$w,$h] selectedIndex=$selectedIndex")
        onWidgetMenuShow?.invoke(widgetId, jsCoordinator, x, y, w, h, itemsJson, selectedIndex)
    }

    @JavascriptInterface
    fun setWidgetMenuSelection(widgetId: String, index: Int) {
        Log.d(TAG, "setWidgetMenuSelection: widgetId=$widgetId index=$index")
        onWidgetMenuSelection?.invoke(widgetId, index)
    }

    /** Update a single tab's label + icon (e.g. rotator's Pause↔Play swap). */
    @JavascriptInterface
    fun setWidgetMenuItem(widgetId: String, index: Int, label: String, iconKey: String) {
        Log.d(TAG, "setWidgetMenuItem: widgetId=$widgetId index=$index label='$label' icon='$iconKey'")
        onWidgetMenuItemUpdate?.invoke(widgetId, index, label, iconKey)
    }

    @JavascriptInterface
    fun hideWidgetMenu(widgetId: String) {
        Log.i(TAG, "hideWidgetMenu: widgetId=$widgetId")
        onWidgetMenuHide?.invoke(widgetId)
    }

    @JavascriptInterface
    fun showWidgetSettings(
        widgetId: String, jsCoordinator: String, title: String,
        x: Int, y: Int, w: Int, h: Int,
        itemsJson: String, selectedIndex: Int
    ) {
        Log.i(TAG, "showWidgetSettings: widgetId=$widgetId coord=$jsCoordinator title='$title' bounds=[$x,$y,$w,$h] selectedIndex=$selectedIndex")
        onWidgetSettingsShow?.invoke(widgetId, jsCoordinator, title, x, y, w, h, itemsJson, selectedIndex)
    }

    @JavascriptInterface
    fun setWidgetSettingsSelection(widgetId: String, index: Int) {
        Log.d(TAG, "setWidgetSettingsSelection: widgetId=$widgetId index=$index")
        onWidgetSettingsSelection?.invoke(widgetId, index)
    }

    @JavascriptInterface
    fun setWidgetSettingsItemChecked(widgetId: String, index: Int, checked: Boolean) {
        Log.d(TAG, "setWidgetSettingsItemChecked: widgetId=$widgetId index=$index checked=$checked")
        onWidgetSettingsItemChecked?.invoke(widgetId, index, checked)
    }

    @JavascriptInterface
    fun hideWidgetSettings(widgetId: String) {
        Log.i(TAG, "hideWidgetSettings: widgetId=$widgetId")
        onWidgetSettingsHide?.invoke(widgetId)
    }
}
