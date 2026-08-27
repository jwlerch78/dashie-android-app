package com.dashieapp.Dashie.wifi

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.dashieapp.Dashie.MainActivity
import com.dashieapp.Dashie.R
import kotlinx.coroutines.*
import com.dashieapp.Dashie.edition.brandName

/**
 * WiFi Setup Activity - Simple branded screen that opens the system Internet Panel
 *
 * Strategy:
 * - Show Dashie branding with a simple "Connect to WiFi" button
 * - Opens the system Internet Connectivity Panel (Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
 * - This panel is a contained overlay - doesn't expose full settings navigation
 * - Works for both new networks and reconnecting to saved networks
 */
class WifiSetupActivity : ComponentActivity() {

    companion object {
        /** When set true, the activity will display even if the device is
         *  already connected to the internet. Used by the "Change WiFi
         *  Network" Settings entry so the user can switch networks through
         *  the same branded UI rather than being bounced straight to
         *  MainActivity by the auto-redirect at onCreate / onResume. */
        const val EXTRA_FORCE_SHOW = "force_show"

        /** How long a previously-connected device waits for a saved network to
         *  re-associate on a no-internet boot before surfacing the WiFi prompt.
         *  Covers the gap between the app booting (as launcher) and WiFi
         *  finishing association — tune up if some networks reconnect slower. */
        private const val GRACE_PERIOD_MS = 15000L
    }

    private val TAG = "WifiSetup"
    private lateinit var wifiHelper: WifiHelper
    private lateinit var internetPanelLauncher: ActivityResultLauncher<Intent>

    // UI Elements
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var connectButton: Button
    private lateinit var useOfflineButton: Button
    private lateinit var reconnectingSpinner: ProgressBar

    // Coroutine scope for connectivity polling
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var connectivityCheckJob: Job? = null
    private var graceJob: Job? = null

    // Guards against launching MainActivity more than once. The poller, the
    // grace timer, onResume, and the Internet-panel callback can all race to
    // call launchMainActivity() the moment connectivity arrives. A second
    // launch kicks off a redundant MainActivity cold start (CLEAR_TASK) that
    // doubles the main-thread block during handoff — long enough that the
    // still-foreground WifiSetupActivity misses Android's 5s focus-dispatch
    // window and gets ANR-killed, restarting the app mid-load.
    @Volatile private var launchingMain = false

    // True when launched from Settings ("Change WiFi Network") rather than
    // from the boot-time no-internet redirect. Suppresses all auto-bounces
    // to MainActivity so the user stays on this screen until they press BACK.
    private var forceShow: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        forceShow = intent.getBooleanExtra(EXTRA_FORCE_SHOW, false)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_wifi_setup_simple)

        // Enable immersive fullscreen mode (hide status bar and navigation)
        enableImmersiveMode()

        wifiHelper = WifiHelper(this)

        // Initialize launcher for Internet Connectivity Panel
        internetPanelLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            Log.d(TAG, "Internet panel closed")
            enableImmersiveMode()

            if (forceShow) {
                // Settings entry path: user came here to switch networks.
                // Don't auto-launch MainActivity (they came from Settings,
                // not from a boot-time no-internet redirect). Refresh the
                // UI; user presses BACK to return to Settings.
                if (wifiHelper.isConnectedToInternet()) {
                    applyUpdateModeCopy()
                } else {
                    subtitleText.text = "Choose a network to continue"
                }
                connectButton.visibility = View.VISIBLE
                return@registerForActivityResult
            }

            // Check if connected after panel closes
            scope.launch {
                subtitleText.text = "Checking connection..."
                for (attempt in 1..5) {
                    delay(1000)
                    if (wifiHelper.isConnectedToInternet()) {
                        Log.i(TAG, "Connected after Internet panel")
                        launchMainActivity()
                        return@launch
                    }
                }
                // Not connected - show prompt again
                subtitleText.text = "Internet connection required to continue"
                connectButton.visibility = View.VISIBLE
            }
        }

        // Find views
        titleText = findViewById(R.id.titleText)
        subtitleText = findViewById(R.id.subtitleText)
        connectButton = findViewById(R.id.connectButton)
        useOfflineButton = findViewById(R.id.useOfflineButton)
        reconnectingSpinner = findViewById(R.id.reconnectingSpinner)

        // Setup connect button
        connectButton.setOnClickListener {
            openInternetPanel()
        }

        // The secondary button is TWO different actions depending on how we got here.
        //
        // Boot-time redirect: "Use <brand> without Internet" — escape hatch for networks that
        // reach the app but fail Android's captive-portal validation (so the validated-internet
        // check never passes). Persist the choice so the user isn't trapped on this screen every
        // reboot, then proceed.
        //
        // forceShow (Settings → Advanced → Change WiFi Network): the app is already running and
        // the user only wants to switch networks, so flipping allowOfflineMode would be a side
        // effect they never asked for. It becomes CANCEL instead — finish() and nothing else.
        //
        // 🔴 It used to be `visibility = GONE` here, which left the screen with NO visible exit:
        // the code's own comment said "user presses BACK to return to Settings", but this
        // activity runs in immersive fullscreen with the navigation bar hidden, so on a tablet
        // there is no back affordance on screen at all. The documented way out was invisible.
        // Reported from the field 2026-08-01. Reusing the existing button costs no layout change.
        if (forceShow) {
            useOfflineButton.text = getString(R.string.wifi_cancel)
            useOfflineButton.setOnClickListener {
                Log.i(TAG, "Settings entry: user cancelled the WiFi screen — no offline-mode change")
                finish()
            }
        } else {
            useOfflineButton.text =
                getString(R.string.wifi_use_without_internet, getString(R.string.brand_name))
            useOfflineButton.setOnClickListener {
                Log.i(TAG, "User chose 'without Internet' — enabling offline mode")
                com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(this)
                    .allowOfflineMode = true
                launchMainActivity()
            }
        }

        // TV / D-pad: ensure the primary control receives focus on launch
        // so the orange focus ring is visible immediately without a stray D-pad press.
        connectButton.requestFocus()

        // Check if already connected. In forceShow mode we stay on this
        // screen anyway — just adjust the copy to the "Update" variant.
        if (wifiHelper.isConnectedToInternet()) {
            if (forceShow) {
                applyUpdateModeCopy()
            } else {
                Log.i(TAG, "Already connected to internet - proceeding to main activity")
                launchMainActivity()
                return
            }
        } else if (!wifiHelper.isWifiEnabled()) {
            subtitleText.text = "WiFi is disabled.\nPlease enable WiFi to continue."
        } else if (!forceShow && shouldOfferReconnectGrace()) {
            // Device has reached the internet before and WiFi is on — it's almost
            // certainly mid-reconnect to a saved network (the app booted faster
            // than WiFi could associate). Show a quiet "Reconnecting…" state and
            // give the saved network a chance before surfacing the Connect /
            // Offline prompt. startConnectivityPolling() below proceeds
            // automatically the moment connectivity arrives.
            enterReconnectingState()
        }

        // Start connectivity polling — only for the boot-time no-internet
        // path. In forceShow mode the user controls when to leave (BACK).
        if (!forceShow) {
            startConnectivityPolling()
        }

        Log.i(TAG, "WiFi setup activity started (forceShow=$forceShow)")
    }

    /**
     * True when a no-internet boot should wait silently for a known network to
     * reconnect rather than immediately prompting. Requires (a) the device has
     * reached the internet before — so a saved network exists — and (b) the WiFi
     * radio is on, so a reconnect is actually possible.
     */
    private fun shouldOfferReconnectGrace(): Boolean {
        val connectedBefore = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(this)
            .hasConnectedBefore
        return connectedBefore && wifiHelper.isWifiEnabled()
    }

    /**
     * Show the quiet "Reconnecting…" state and arm a timer that reveals the
     * Connect / Offline prompt only if connectivity hasn't arrived by the time the
     * grace period elapses. Connectivity polling (started separately) proceeds to
     * MainActivity the instant WiFi reconnects, so the prompt is never seen on a
     * normal reboot.
     */
    private fun enterReconnectingState() {
        Log.i(TAG, "Known network — showing reconnect spinner for up to ${GRACE_PERIOD_MS}ms (buttons stay available)")
        titleText.text = "Reconnecting"
        subtitleText.text = "Reconnecting to your Wi-Fi network…"
        reconnectingSpinner.visibility = View.VISIBLE
        // Buttons stay visible during the grace period so the user can switch to a
        // different network or continue offline immediately instead of waiting for
        // the saved network to reconnect. Polling still auto-proceeds the moment
        // connectivity arrives. The primary button reads "Configure WiFi" here —
        // the device is already trying to connect, so this is for picking a
        // different network, not initiating a first connection.
        connectButton.text = "Configure WiFi"
        connectButton.visibility = View.VISIBLE
        useOfflineButton.visibility = View.VISIBLE

        graceJob = scope.launch {
            delay(GRACE_PERIOD_MS)
            // Still on this screen → polling hasn't reconnected within the window.
            // Drop the spinner and switch to the standard "needs internet" copy;
            // the buttons were already there.
            if (!wifiHelper.isConnectedToInternet()) {
                Log.i(TAG, "Reconnect grace elapsed without connectivity — switching to standard prompt copy")
                revealConnectPrompt()
            }
        }
    }

    /** Drop the reconnect spinner and restore the standard Connect-to-WiFi copy
     *  after the grace period elapses. Buttons are already visible. */
    private fun revealConnectPrompt() {
        reconnectingSpinner.visibility = View.GONE
        titleText.text = "Connect to WiFi"
        subtitleText.text = "Internet connection required to continue"
        // No longer mid-reconnect — revert the button to the first-connection copy.
        connectButton.text = "Connect to WiFi"
        connectButton.visibility = View.VISIBLE
        useOfflineButton.visibility = View.VISIBLE
    }

    private fun openInternetPanel() {
        Log.d(TAG, "Opening Internet Connectivity Panel")
        // User took manual control — stop the reconnect grace UI so it doesn't
        // fight the panel flow.
        graceJob?.cancel()
        reconnectingSpinner.visibility = View.GONE
        connectButton.visibility = View.INVISIBLE
        subtitleText.text = "Select a network to connect..."

        try {
            val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            internetPanelLauncher.launch(panelIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Internet panel, falling back to WiFi settings", e)
            // Fall back to WiFi settings if panel not available
            wifiHelper.openWifiSettings()
            connectButton.visibility = View.VISIBLE
            subtitleText.text = "Internet connection required to continue"
        }
    }

    private fun startConnectivityPolling() {
        connectivityCheckJob = scope.launch {
            while (isActive) {
                delay(2000)
                if (wifiHelper.isConnectedToInternet()) {
                    Log.i(TAG, "Connectivity detected via polling")
                    launchMainActivity()
                    break
                }
            }
        }
    }

    /** Swap copy to the "Update" variant. Used when forceShow is true and
     *  the device is already online — the user is here to switch networks,
     *  not to recover from no-internet. */
    private fun applyUpdateModeCopy() {
        titleText.text = "Update Wifi Network"
        subtitleText.text = "Update your network or press back to return to ${brandName()}"
        connectButton.text = "Update Wifi"
    }

    private fun launchMainActivity() {
        if (launchingMain) {
            Log.d(TAG, "launchMainActivity ignored — already launching")
            return
        }
        launchingMain = true
        connectivityCheckJob?.cancel()
        graceJob?.cancel()
        Log.i(TAG, "Launching MainActivity")
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Re-enable immersive mode when returning from settings
        enableImmersiveMode()

        // Check if we got connected while away. forceShow disables this
        // bounce — user came from Settings, not from a no-internet redirect.
        if (!forceShow && wifiHelper.isConnectedToInternet()) {
            launchMainActivity()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
        Log.d(TAG, "Immersive mode enabled")
    }
}
