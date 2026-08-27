package com.dashieapp.Dashie.halite.theming

import android.graphics.Color
import android.util.Log

/**
 * Singleton that holds the active Dashie webapp theme palette so native Kotlin
 * surfaces (sidebar, control center, popouts, focus rings, native widgets) can
 * render in the same colors as the JS dashboard instead of carrying their own
 * hardcoded light/dark literals.
 *
 * **Update path:** JS calls [com.dashieapp.Dashie.webview.DashieJSBridge.setSidebarThemeColors]
 * with a JSON payload of the full palette. That call routes through
 * [com.dashieapp.Dashie.sidebar.NativeSidebarController.setThemeColors] which
 * invokes [update] here. Each surface that needs live updates registers a
 * listener via [addListener]; surfaces that only need to read on-show (popouts,
 * control center cards) just query the getters.
 *
 * **Fallback behavior:** Until JS pushes the first palette (during cold boot,
 * before the webapp has rendered), getters return null. Callers should keep
 * their existing hardcoded defaults as fallbacks until a palette arrives.
 *
 * ⚠️ READ FIRST: dashieapp_staging/.reference/THEMING_ARCHITECTURE.md
 *    Documents the full pipeline, 8 documented pitfalls, and recipes for
 *    adding themes / themed surfaces. Touching this manager or any theming
 *    code without that context will likely re-introduce bugs.
 */
object NativeThemeManager {

    private const val TAG = "NativeThemeManager"

    // ── Palette (all nullable until first JS push) ─────────────────────────

    @Volatile private var bgPrimary: Int? = null
    @Volatile private var bgSecondary: Int? = null
    @Volatile private var bgTertiary: Int? = null
    @Volatile private var gridGap: Int? = null
    @Volatile private var accentPrimary: Int? = null
    @Volatile private var accentSecondary: Int? = null
    @Volatile private var textPrimary: Int? = null
    @Volatile private var textSecondary: Int? = null

    // ── Listeners (live-update surfaces only — sidebar strip, CC overlay) ──

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    // ── Update (called from JS bridge on every theme change) ───────────────

    /**
     * Apply a palette parsed from the JS bridge JSON. Missing fields are left
     * unchanged from the previous palette (partial updates are allowed but the
     * webapp currently always sends the full set).
     */
    fun update(
        bgPrimaryHex: String? = null,
        bgSecondaryHex: String? = null,
        bgTertiaryHex: String? = null,
        gridGapHex: String? = null,
        accentPrimaryHex: String? = null,
        accentSecondaryHex: String? = null,
        textPrimaryHex: String? = null,
        textSecondaryHex: String? = null,
    ) {
        bgPrimaryHex?.let { parse(it)?.let { c -> bgPrimary = c } }
        bgSecondaryHex?.let { parse(it)?.let { c -> bgSecondary = c } }
        bgTertiaryHex?.let { parse(it)?.let { c -> bgTertiary = c } }
        gridGapHex?.let { parse(it)?.let { c -> gridGap = c } }
        accentPrimaryHex?.let { parse(it)?.let { c -> accentPrimary = c } }
        accentSecondaryHex?.let { parse(it)?.let { c -> accentSecondary = c } }
        textPrimaryHex?.let { parse(it)?.let { c -> textPrimary = c } }
        textSecondaryHex?.let { parse(it)?.let { c -> textSecondary = c } }

        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach { runCatching { it() }.onFailure { Log.w(TAG, "listener threw", it) } }
    }

    private fun parse(hex: String): Int? = try {
        // Android's Color.parseColor doesn't handle 3-digit shorthand (#rgb) or
        // 4-digit (#argb), but Dashie's CSS uses these heavily (--text-primary:
        // #fff, --text-secondary: #ccc, default --bg-primary: #000). Expand
        // shorthand to the full form before delegating.
        Color.parseColor(expandShorthandHex(hex))
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Invalid color hex: $hex"); null
    }

    private fun expandShorthandHex(hex: String): String {
        if (!hex.startsWith("#")) return hex
        return when (hex.length) {
            4 -> {
                // #rgb → #rrggbb
                val r = hex[1]; val g = hex[2]; val b = hex[3]
                "#$r$r$g$g$b$b"
            }
            5 -> {
                // #argb → #aarrggbb
                val a = hex[1]; val r = hex[2]; val g = hex[3]; val b = hex[4]
                "#$a$a$r$r$g$g$b$b"
            }
            else -> hex
        }
    }

    // ── Getters (null until first JS push; callers fall back to their own defaults) ─

    fun getBgPrimary(): Int? = bgPrimary
    fun getBgSecondary(): Int? = bgSecondary
    fun getBgTertiary(): Int? = bgTertiary
    fun getGridGap(): Int? = gridGap
    fun getAccentPrimary(): Int? = accentPrimary
    fun getAccentSecondary(): Int? = accentSecondary
    fun getTextPrimary(): Int? = textPrimary
    fun getTextSecondary(): Int? = textSecondary

    /** Convenience for callers that want a default when nothing's been pushed yet. */
    fun getBgPrimaryOr(default: Int): Int = bgPrimary ?: default
    fun getBgSecondaryOr(default: Int): Int = bgSecondary ?: default
    fun getGridGapOr(default: Int): Int = gridGap ?: default
    fun getAccentPrimaryOr(default: Int): Int = accentPrimary ?: default
    fun getAccentSecondaryOr(default: Int): Int = accentSecondary ?: default
    fun getTextPrimaryOr(default: Int): Int = textPrimary ?: default
    fun getTextSecondaryOr(default: Int): Int = textSecondary ?: default
    fun getBgTertiaryOr(default: Int): Int = bgTertiary ?: default
}
