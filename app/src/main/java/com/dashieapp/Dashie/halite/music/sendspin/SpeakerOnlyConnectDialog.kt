package com.dashieapp.Dashie.halite.music.sendspin

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.dashieapp.Dashie.R
import com.dashieapp.Dashie.halite.sidebar.dialogs.DialogHelper
import java.net.URI

/**
 * Dialog flow for enabling speaker-only Sendspin connection.
 *
 * Invoked when the user flips the "Use Tablet as Speaker Only" toggle ON from settings.
 * Runs mDNS discovery for a Music Assistant Sendspin server, shows the result (or lets
 * the user enter a URL manually if discovery fails), then persists the chosen URL and
 * reports success/cancel.
 *
 * Pre-fix, flipping the toggle started mDNS discovery silently in the background.
 * On devices where discovery failed (primary cause was a service-type mismatch fixed
 * separately, but multicast / OEM quirks can still interfere) the user was stuck on
 * "Searching for server…" with no way to intervene. This dialog exposes the discovery
 * state and lets the user force a manual URL as an escape hatch.
 *
 * Uses the same XML layout / button styling pattern as dialog_auto_brightness_settings
 * (the reference "correct" Dashie dialog): scrollable body with intro text inside the
 * scroll region so short screens have enough room for controls, custom-styled
 * Cancel/Connect buttons via @drawable/button_border and @drawable/button_primary, and
 * DialogHelper.applyImmersiveModeToDialog for consistency.
 *
 * Scope: only renders UI and runs discovery. Persistence and service start happen via
 * [onResult] — the caller saves the URL to prefs and allows the toggle to flip.
 */
class SpeakerOnlyConnectDialog(
    private val activity: Activity,
    /** Prior URL (if any) to pre-fill the text field when discovery fails. Empty on first run. */
    private val priorUrl: String = "",
    /** Called with (confirmed, url). confirmed=false → user cancelled, url will be null. */
    private val onResult: (confirmed: Boolean, url: String?) -> Unit
) {
    companion object {
        private const val TAG = "SpeakerOnlyDialog"
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
    }

    private enum class UiState { SEARCHING, FOUND, NOT_FOUND }

    private var dialog: AlertDialog? = null
    private var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var urlLabel: TextView? = null
    private var urlField: EditText? = null
    private var urlHint: TextView? = null
    private var retryLink: TextView? = null

    private val discovery = SendspinDiscoveryManager(activity.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var state: UiState = UiState.SEARCHING
    private var resolved = false

    fun show() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_speaker_only_connect, null)

        statusText = dialogView.findViewById(R.id.speakerOnlyStatus)
        progressBar = dialogView.findViewById(R.id.speakerOnlyProgress)
        urlLabel = dialogView.findViewById(R.id.speakerOnlyUrlLabel)
        urlField = dialogView.findViewById(R.id.speakerOnlyUrlField)
        urlHint = dialogView.findViewById(R.id.speakerOnlyUrlHint)
        retryLink = dialogView.findViewById(R.id.speakerOnlyRetry)

        urlField?.setText(priorUrl)
        retryLink?.setOnClickListener { startDiscovery() }

        val d = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(true)
            .setOnCancelListener { cancel() }
            .create()
        // Make window transparent so the XML layout's @drawable/dialog_background
        // shows through (matches auto-brightness dialog pattern).
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Explicitly enable the dim scrim. Normally inherited from the dialog theme, but
        // a few users reported no dim behind this dialog — belt-and-suspenders.
        d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        d.window?.setDimAmount(0.6f)
        dialog = d

        dialogView.findViewById<Button>(R.id.buttonCancel).setOnClickListener { cancel() }
        dialogView.findViewById<Button>(R.id.buttonConnect).setOnClickListener { onConnectClicked() }

        d.show()
        DialogHelper.applyImmersiveModeToDialog(d)
        startDiscovery()
    }

    private fun startDiscovery() {
        resolved = false
        transitionTo(UiState.SEARCHING)

        discovery.onServerDiscovered = { server ->
            handler.post {
                if (resolved) return@post
                resolved = true
                onDiscovered(server)
            }
        }
        discovery.onDiscoveryFailed = { err ->
            handler.post {
                if (resolved) return@post
                resolved = true
                Log.w(TAG, "Discovery error: $err")
                transitionTo(UiState.NOT_FOUND)
            }
        }
        discovery.startDiscovery()

        timeoutRunnable?.let { handler.removeCallbacks(it) }
        val timeout = Runnable {
            if (resolved) return@Runnable
            resolved = true
            Log.i(TAG, "Discovery timed out after ${DISCOVERY_TIMEOUT_MS}ms")
            discovery.stopDiscovery()
            transitionTo(UiState.NOT_FOUND)
        }
        timeoutRunnable = timeout
        handler.postDelayed(timeout, DISCOVERY_TIMEOUT_MS)
    }

    private fun onDiscovered(server: SendspinDiscoveryManager.DiscoveredServer) {
        discovery.stopDiscovery()
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        val url = buildUrl(server.host, server.port, server.path)
        urlField?.setText(url)
        transitionTo(UiState.FOUND)
    }

    private fun transitionTo(newState: UiState) {
        state = newState
        when (newState) {
            UiState.SEARCHING -> {
                statusText?.text = "Looking for Music Assistant server on your network…"
                progressBar?.visibility = View.VISIBLE
                urlLabel?.visibility = View.GONE
                urlField?.visibility = View.GONE
                urlHint?.visibility = View.GONE
                retryLink?.visibility = View.GONE
            }
            UiState.FOUND -> {
                statusText?.text = "Music Assistant found. Review the URL and tap Connect."
                progressBar?.visibility = View.GONE
                urlLabel?.visibility = View.VISIBLE
                urlField?.visibility = View.VISIBLE
                urlHint?.visibility = View.VISIBLE
                retryLink?.visibility = View.VISIBLE
            }
            UiState.NOT_FOUND -> {
                statusText?.text = "Didn't find Music Assistant automatically. Enter the URL below, or tap Search again."
                progressBar?.visibility = View.GONE
                urlLabel?.visibility = View.VISIBLE
                urlField?.visibility = View.VISIBLE
                urlHint?.visibility = View.VISIBLE
                retryLink?.visibility = View.VISIBLE
            }
        }
    }

    private fun onConnectClicked() {
        val raw = urlField?.text?.toString()?.trim().orEmpty()
        val normalized = normalizeUrl(raw)
        if (normalized == null) {
            statusText?.text = "That URL doesn't look right. Try something like http://homeassistant.local:8095"
            return
        }
        cleanup()
        dialog?.dismiss()
        onResult(true, normalized)
    }

    private fun cancel() {
        cleanup()
        dialog?.dismiss()
        onResult(false, null)
    }

    private fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        discovery.stopDiscovery()
    }

    // ---- Helpers ----

    private fun buildUrl(host: String, port: Int, path: String): String {
        val trimmedPath = if (path.startsWith("/")) path else "/$path"
        return "http://$host:$port$trimmedPath"
    }

    /**
     * Normalize user input into a canonical URL string.
     * Accepts "host", "host:port", "http://host:port", "http://host:port/path" etc.
     * Returns null if we can't turn it into a URI at all.
     */
    private fun normalizeUrl(input: String): String? {
        if (input.isEmpty()) return null
        val withScheme = if (input.startsWith("http://") || input.startsWith("https://")) {
            input
        } else {
            "http://$input"
        }
        return try {
            val uri = URI(withScheme)
            if (uri.host.isNullOrBlank()) return null
            withScheme
        } catch (_: Exception) {
            null
        }
    }
}
