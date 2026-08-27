package com.dashieapp.Dashie.halite.mqtt

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.MqttPreferences
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core MQTT client service for Dashie.
 *
 * Wraps Eclipse Paho MqttAsyncClient with:
 * - Auto-reconnect with exponential backoff
 * - Last Will and Testament (LWT) for availability
 * - Thread-safe publish/subscribe
 * - Lifecycle management (connect/disconnect/destroy)
 *
 * This service is the single MQTT connection point. Other components
 * (MqttSensorPublisher, MqttCommandHandler, MqttDiscoveryPublisher)
 * use this service to publish and subscribe.
 */
class DashieMqttService(
    private val context: Context,
    private val prefs: MqttPreferences
) {
    companion object {
        private const val TAG = "DashieMqttService"
        private const val RECONNECT_BASE_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val KEEP_ALIVE_SEC = 60
        private const val CONNECTION_TIMEOUT_SEC = 10
        // Paho's default max in-flight window is 10. HA discovery refresh
        // (removeDiscovery + publishDiscovery) fires ~30 retained QoS-1 publishes
        // back-to-back; at the default, the overflow is rejected with "Too many
        // publishes in progress" and dropped — so discovery OFF fails to remove HA
        // entities and refresh fails to (re)register them. Widen the window well
        // past the burst size (plus headroom for concurrent sensor-state publishes).
        private const val MAX_INFLIGHT = 100
    }

    // Connection state
    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    @Volatile var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    @Volatile var lastError: String? = null
        private set

    private var client: MqttAsyncClient? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyed = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null

    /**
     * Serializes all blocking lifecycle ops (connect/disconnect) onto a single
     * background thread. These do network I/O and Paho waitForCompletion() calls
     * that must never run on the main thread — doing so caused an ANR when a
     * BroadcastReceiver triggered reconnect() while the broker was unreachable.
     */
    private val lifecycleExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DashieMqtt-Lifecycle").apply { isDaemon = true }
    }

    /** Resolved base topic (e.g., "dashie/kitchen-tablet") */
    @Volatile var baseTopic: String = ""
        private set

    // Listeners
    var onConnectionStateChanged: ((ConnectionState) -> Unit)? = null
    var onMessageReceived: ((topic: String, payload: String) -> Unit)? = null

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Connect to the MQTT broker using current preferences.
     * Dispatched to the lifecycle thread — safe to call from the main thread
     * or a BroadcastReceiver. No-op if already connected or preferences incomplete.
     */
    fun connect() = submitLifecycle { connectInternal() }

    private fun connectInternal() {
        if (destroyed.get()) return
        if (!prefs.enabled || !prefs.hasConnectionConfig) {
            Log.d(TAG, "MQTT disabled or no config, skipping connect")
            return
        }
        if (connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already ${connectionState.name}, skipping connect")
            return
        }

        val deviceName = getDeviceName()
        baseTopic = prefs.resolvedBaseTopic(deviceName)

        val scheme = if (prefs.useTls) "ssl" else "tcp"
        val brokerUri = "$scheme://${prefs.brokerHost}:${prefs.brokerPort}"
        val clientId = "dashie_${com.dashieapp.Dashie.util.StableDeviceId.read(context).ifEmpty { "unknown" }}"

        val hasUser = prefs.username.isNotEmpty()
        val hasPass = prefs.password.isNotEmpty()
        Log.i(TAG, "Connecting to $brokerUri as $clientId, baseTopic=$baseTopic, " +
            "hasUsername=$hasUser, hasPassword=$hasPass, useTls=${prefs.useTls}")
        setState(ConnectionState.CONNECTING)

        try {
            // disconnectForcibly(_, disconnectTimeout=0) waits FOREVER on the disconnect
            // token if the broker is unreachable. Bound it so stale-client cleanup can
            // never hang the lifecycle thread.
            try { client?.disconnectForcibly(0, 1_000) } catch (e: Exception) {
                Log.w(TAG, "Stale client cleanup failed: ${e.message}")
            }
            client = MqttAsyncClient(brokerUri, clientId, MemoryPersistence())

            client?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.i(TAG, "Connected to $serverURI (reconnect=$reconnect)")
                    reconnectAttempt = 0
                    setState(ConnectionState.CONNECTED)
                    lastError = null

                    // Publish online status (retained)
                    publishRetained("$baseTopic/connection", "online")

                    // Re-subscribe to command topic
                    subscribeToCommands()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost: ${cause?.message}")
                    setState(ConnectionState.DISCONNECTED)
                    lastError = cause?.message ?: "Connection lost"
                    scheduleReconnect()
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return
                    val payload = String(message.payload)
                    Log.d(TAG, "Message on $topic: ${payload.take(200)}")
                    onMessageReceived?.invoke(topic, payload)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            val options = MqttConnectOptions().apply {
                isCleanSession = true
                keepAliveInterval = KEEP_ALIVE_SEC
                connectionTimeout = CONNECTION_TIMEOUT_SEC
                isAutomaticReconnect = false // We handle reconnect ourselves for better control
                maxInflight = MAX_INFLIGHT // Fit the HA-discovery publish burst (see MAX_INFLIGHT)

                // Credentials
                if (prefs.username.isNotEmpty()) {
                    userName = prefs.username
                    password = prefs.password.toCharArray()
                }

                // Last Will and Testament — broker publishes "offline" if we disconnect unexpectedly
                setWill("$baseTopic/connection", "offline".toByteArray(), 1, true)
            }

            client?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    // Handled in connectComplete callback
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Connect failed: ${exception?.message}", exception)
                    Log.e(TAG, "  broker=$brokerUri, hasUser=$hasUser, hasPass=$hasPass")
                    lastError = exception?.message ?: "Connection failed"
                    setState(ConnectionState.ERROR)
                    // Don't retry on auth errors — user needs to fix credentials
                    if (!isAuthError(lastError)) {
                        scheduleReconnect()
                    } else {
                        Log.w(TAG, "Auth error — not retrying. User must update credentials.")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MQTT client: ${e.message}")
            setState(ConnectionState.ERROR)
            lastError = e.message
            scheduleReconnect()
        }
    }

    /**
     * Disconnect cleanly from the broker.
     * Dispatched to the lifecycle thread — safe to call from the main thread.
     * Publishes offline status before disconnecting.
     */
    fun disconnect() = submitLifecycle { disconnectInternal() }

    private fun disconnectInternal() {
        cancelReconnect()

        try {
            if (client?.isConnected == true) {
                // Publish offline before disconnect (synchronous, short timeout)
                try {
                    client?.publish(
                        "$baseTopic/connection",
                        MqttMessage("offline".toByteArray()).apply {
                            qos = 1
                            isRetained = true
                        }
                    )?.waitForCompletion(2000)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to publish offline status: ${e.message}")
                }

                client?.disconnect()?.waitForCompletion(3000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error: ${e.message}")
            // Bounded — a 0 disconnect timeout waits forever and would wedge the
            // single-thread lifecycle executor if the broker never acks.
            try { client?.disconnectForcibly(0, 1_000) } catch (_: Exception) {}
        }

        setState(ConnectionState.DISCONNECTED)
        lastError = null
        Log.i(TAG, "Disconnected")
    }

    /**
     * Destroy the service. Call on app shutdown.
     */
    fun destroy() {
        if (destroyed.getAndSet(true)) return
        // Clear listeners up front so no callbacks fire during async teardown.
        onConnectionStateChanged = null
        onMessageReceived = null
        // Run final disconnect + close on the lifecycle thread, then stop accepting work.
        try {
            lifecycleExecutor.execute {
                disconnectInternal()
                try { client?.close() } catch (e: Exception) { Log.w(TAG, "close failed: ${e.message}") }
                client = null
                Log.i(TAG, "Destroyed")
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Destroy cleanup rejected: ${e.message}")
        }
        lifecycleExecutor.shutdown()
    }

    /**
     * Reconnect with new settings (e.g., after user changes broker config).
     * Disconnect + connect run sequentially on the lifecycle thread.
     */
    fun reconnect() = submitLifecycle {
        disconnectInternal()
        connectInternal()
    }

    /** Submit a blocking lifecycle task to the background thread. No-op once destroyed. */
    private fun submitLifecycle(task: () -> Unit) {
        if (destroyed.get()) return
        try {
            lifecycleExecutor.execute(task)
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Lifecycle task rejected (executor shut down): ${e.message}")
        }
    }

    /** Whether we're currently connected and can publish */
    val isConnected: Boolean
        get() = client?.isConnected == true && connectionState == ConnectionState.CONNECTED

    // ── Publishing ──────────────────────────────────────────────────

    /**
     * Publish a message to a topic. Fire-and-forget (QoS 0).
     * No-op if not connected.
     */
    fun publish(topic: String, payload: String, qos: Int = 0, retained: Boolean = false) {
        if (!isConnected) return
        try {
            val message = MqttMessage(payload.toByteArray()).apply {
                this.qos = qos
                this.isRetained = retained
            }
            client?.publish(topic, message)
        } catch (e: Exception) {
            Log.w(TAG, "Publish failed ($topic): ${e.message}")
        }
    }

    /** Convenience: publish with retain flag set */
    fun publishRetained(topic: String, payload: String, qos: Int = 1) {
        publish(topic, payload, qos, retained = true)
    }

    // ── Subscribing ────────────────────────────────────────────────��

    fun subscribe(topic: String, qos: Int = 1) {
        if (!isConnected) return
        try {
            client?.subscribe(topic, qos)
            Log.d(TAG, "Subscribed to $topic (QoS $qos)")
        } catch (e: Exception) {
            Log.w(TAG, "Subscribe failed ($topic): ${e.message}")
        }
    }

    // ── Internal ────────────────────────────────────────────────────

    private fun subscribeToCommands() {
        subscribe("$baseTopic/command", qos = 1)
    }

    private fun setState(state: ConnectionState) {
        connectionState = state
        // Persist status so settings UI can read it across processes
        prefs.lastStatus = when (state) {
            ConnectionState.CONNECTED -> com.dashieapp.Dashie.halite.preferences.MqttPreferences.STATUS_CONNECTED
            ConnectionState.CONNECTING -> com.dashieapp.Dashie.halite.preferences.MqttPreferences.STATUS_CONNECTING
            ConnectionState.ERROR -> {
                if (isAuthError(lastError)) com.dashieapp.Dashie.halite.preferences.MqttPreferences.STATUS_AUTH_ERROR
                else com.dashieapp.Dashie.halite.preferences.MqttPreferences.STATUS_ERROR
            }
            ConnectionState.DISCONNECTED -> com.dashieapp.Dashie.halite.preferences.MqttPreferences.STATUS_DISCONNECTED
        }
        prefs.lastError = lastError ?: ""
        mainHandler.post { onConnectionStateChanged?.invoke(state) }
    }

    private fun isAuthError(error: String?): Boolean {
        if (error == null) return false
        val lower = error.lowercase()
        return lower.contains("not authorized") || lower.contains("bad user") ||
               lower.contains("not_authorized") || lower.contains("authentication") ||
               lower.contains("bad username or password") || lower.contains("connack return code: 5")
    }

    private fun scheduleReconnect() {
        if (destroyed.get() || !prefs.enabled) return

        cancelReconnect()
        reconnectAttempt++
        val delayMs = (RECONNECT_BASE_MS * (1L shl (reconnectAttempt - 1).coerceAtMost(5)))
            .coerceAtMost(RECONNECT_MAX_MS)

        Log.d(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")
        val runnable = Runnable {
            if (!destroyed.get() && prefs.enabled) {
                setState(ConnectionState.DISCONNECTED) // Reset state before retry
                connect()
            }
        }
        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun getDeviceName(): String {
        val connPrefs = com.dashieapp.Dashie.halite.preferences.ConnectionPreferences(context)
        val name = connPrefs.deviceFriendlyName
        return name.ifEmpty { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }
    }
}
