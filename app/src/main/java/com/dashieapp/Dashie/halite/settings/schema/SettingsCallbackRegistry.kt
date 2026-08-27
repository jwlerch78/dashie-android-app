package com.dashieapp.Dashie.halite.settings.schema

/**
 * Registry for side-effect callbacks triggered by schema item changes.
 *
 * Schema items reference callbacks by name (e.g. "notifyThemeChanged").
 * At initialization time, the host wires actual implementations:
 *
 * ```kotlin
 * registry.register("notifyThemeChanged") { themeApplier.refreshTheme() }
 * registry.register("notifyZoomChanged") { webView.evaluateJavascript(...) }
 * ```
 */
class SettingsCallbackRegistry {

    private val callbacks = mutableMapOf<String, () -> Unit>()

    /**
     * Value-passing callbacks (parallel to [callbacks]). Same name space, but the
     * invocation carries the value the user acted on — e.g. an `onDisabledTap`
     * that must know WHICH grayed option was tapped (Cloud vs Hybrid) so it can
     * remember and later auto-apply it. Kept as a separate map (mirroring the
     * [toggleInterceptors] pattern) so plain callbacks and value callbacks can
     * coexist under one registry without lambda-arity overload ambiguity.
     */
    private val valueCallbacks = mutableMapOf<String, (String) -> Unit>()

    /**
     * Interceptor signature: `(newValue, proceed, cancel) -> Unit`.
     * - `proceed()` — commit the new value and fire onChanged.
     * - `cancel()` — revert the visual toggle state back to the stored pref value
     *   (needed because the adapter optimistically flips the Switch before we decide).
     * The interceptor MUST call exactly one of them (may be async).
     */
    private val toggleInterceptors = mutableMapOf<String, (Boolean, () -> Unit, () -> Unit) -> Unit>()

    /**
     * Register a named callback.
     */
    fun register(name: String, callback: () -> Unit) {
        callbacks[name] = callback
    }

    /**
     * Invoke a named callback. No-op if the name is not registered — but WARN,
     * because a typo'd onChanged/action name in a page schema is otherwise
     * completely silent (the setting saves but its side effect never fires;
     * see the never-invoked mqttConfigChanged bug class).
     */
    fun invoke(name: String) {
        val callback = callbacks[name]
        if (callback == null) {
            android.util.Log.w("SettingsCallbackRegistry", "No callback registered for '$name' — schema typo or missing wiring?")
            return
        }
        callback.invoke()
    }

    /**
     * Register a named value-passing callback (see [valueCallbacks]).
     */
    fun registerValueCallback(name: String, callback: (String) -> Unit) {
        valueCallbacks[name] = callback
    }

    /**
     * Invoke a named callback WITH a value. Prefers a value callback of this name;
     * falls back to a plain no-arg callback of the same name (so a value-passing
     * call site — e.g. the picker's onDisabledTap — still works for handlers that
     * don't care about the value). WARNs if neither is registered (schema typo).
     */
    fun invoke(name: String, value: String) {
        valueCallbacks[name]?.let { it(value); return }
        callbacks[name]?.let { it(); return }
        android.util.Log.w("SettingsCallbackRegistry", "No callback registered for '$name' (value invoke) — schema typo or missing wiring?")
    }

    /**
     * Check if a callback is registered.
     */
    fun has(name: String): Boolean = name in callbacks || name in valueCallbacks

    /**
     * Register an interceptor for a toggle change.
     * The interceptor receives the new value, a `proceed` callback, and a `cancel` callback.
     * It MUST call `proceed()` to apply the change, or `cancel()` to revert the UI toggle
     * back to its stored value. Not calling either leaves the UI in a stale visually-flipped
     * state (the Switch was optimistically flipped by the adapter before the interceptor ran).
     * Async license checks, confirmation dialogs, etc. should wire into one of these paths.
     */
    fun registerToggleInterceptor(settingKey: String, interceptor: (newValue: Boolean, proceed: () -> Unit, cancel: () -> Unit) -> Unit) {
        toggleInterceptors[settingKey] = interceptor
    }

    /**
     * Get interceptor for a toggle setting key, if registered.
     */
    fun getToggleInterceptor(settingKey: String): ((Boolean, () -> Unit, () -> Unit) -> Unit)? {
        return toggleInterceptors[settingKey]
    }
}
