package com.dashieapp.Dashie.halite.orientation

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.DisplayPreferences

/**
 * Single source of truth for screen orientation in widgets layout mode.
 *
 * Responsibilities:
 * - Read `display.orientationLock` from [DisplayPreferences] and translate
 *   it to an [ActivityInfo] orientation flag applied to the Activity's
 *   `requestedOrientation`.
 * - Observe [Configuration] changes (rotation events) and notify
 *   listeners with the new effective orientation.
 * - Track the currently effective orientation
 *   (landscape / portrait) so other components — bottom-bar swap, JS
 *   bridge getters — can read it without re-parsing Configuration.
 *
 * Lock honored only when the dashboard is in widgets layout mode. In
 * single_panel / kiosk modes the device follows its physical orientation;
 * [applyLock] passes SCREEN_ORIENTATION_UNSPECIFIED in that case.
 *
 * Wired in `MainActivity` after [DisplayPreferences] is available.
 */
class OrientationController(
    private val activity: Activity,
    private val displayPrefs: DisplayPreferences
) {

    enum class Orientation { LANDSCAPE, PORTRAIT }

    fun interface Listener {
        fun onOrientationChanged(orientation: Orientation)
    }

    private val listeners = mutableListOf<Listener>()
    private var currentOrientation: Orientation = readActivityOrientation()

    /**
     * Apply the lock pref to [Activity.requestedOrientation]. Call once
     * at startup after the activity is constructed, and again whenever
     * the user changes `display.orientationLock` or `display.layoutMode`.
     *
     * Calling this *can* trigger an immediate orientation change — when
     * the device's physical orientation differs from the new lock — which
     * fires [Activity.onConfigurationChanged]. The MainActivity hook
     * routes that back into [handleConfigurationChanged] below, which
     * fires our listeners.
     */
    fun applyLock() {
        val mode = displayPrefs.layoutMode
        val lock = displayPrefs.orientationLock
        val isTv = (activity.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val requested = if (isTv) {
            // TVs output a fixed-landscape HDMI signal and can't truly rotate;
            // a portrait lock only pillar-boxes a portrait window. The
            // orientation setting is hidden on TV, so force landscape here and
            // ignore any stored portrait lock (also un-sticks a device that was
            // put into portrait before this gate existed).
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else if (mode != "widgets") {
            // Single-panel / kiosk ignore the lock — let the device decide.
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else when (lock) {
            DisplayPreferences.ORIENTATION_LOCK_LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            DisplayPreferences.ORIENTATION_LOCK_LANDSCAPE_REVERSE ->
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            DisplayPreferences.ORIENTATION_LOCK_PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            DisplayPreferences.ORIENTATION_LOCK_PORTRAIT_REVERSE ->
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else ->
                // Auto — use FULL_USER (all 4 orientations, respects the
                // OS rotation-lock toggle). UNSPECIFIED leaves the choice
                // to Android, which on some devices favors one portrait
                // over the other and never auto-flips 180° even when the
                // tablet is physically upside-down.
                ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        if (activity.requestedOrientation != requested) {
            Log.i(TAG, "🧭 Applying lock: mode=$mode lock=$lock → requestedOrientation=$requested")
            activity.requestedOrientation = requested
        }
        // Re-read in case the lock matched a physical state already in
        // effect (no Configuration change will fire) — keeps listeners
        // in sync with reality after a settings change.
        updateAndNotify(readActivityOrientation())
    }

    /**
     * Called from `MainActivity.onConfigurationChanged`. Reads the new
     * Configuration's orientation field and notifies listeners.
     */
    fun handleConfigurationChanged(newConfig: Configuration) {
        val next = configToOrientation(newConfig.orientation)
        updateAndNotify(next)
    }

    fun current(): Orientation = currentOrientation

    /** "landscape" | "portrait" — convenience for JS bridge */
    fun currentString(): String =
        if (currentOrientation == Orientation.PORTRAIT) "portrait" else "landscape"

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
        // Fire immediately so the new listener can sync to the current
        // state without waiting for the next rotation.
        listener.onOrientationChanged(currentOrientation)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun updateAndNotify(next: Orientation) {
        if (next == currentOrientation) return
        Log.i(TAG, "🧭 Orientation: $currentOrientation → $next")
        currentOrientation = next
        // Copy to avoid CME if a listener mutates the list.
        listeners.toList().forEach {
            try {
                it.onOrientationChanged(next)
            } catch (e: Exception) {
                Log.w(TAG, "Listener threw", e)
            }
        }
    }

    private fun readActivityOrientation(): Orientation =
        configToOrientation(activity.resources.configuration.orientation)

    private fun configToOrientation(orientation: Int): Orientation =
        if (orientation == Configuration.ORIENTATION_PORTRAIT)
            Orientation.PORTRAIT else Orientation.LANDSCAPE

    companion object {
        private const val TAG = "OrientationController"
    }
}
