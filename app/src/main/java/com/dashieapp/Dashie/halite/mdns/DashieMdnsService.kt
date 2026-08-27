package com.dashieapp.Dashie.halite.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences

/**
 * mDNS/Zeroconf service for Dashie Kiosk.
 *
 * Broadcasts device presence using mDNS/Bonjour for real-time discovery by Home Assistant.
 * Unlike SSDP (which only discovers at HA startup), mDNS triggers discovery immediately
 * when the service comes online.
 *
 * Service type: _dashie-kiosk._tcp
 * Port: BuildConfig.API_PORT (2323)
 * TXT records: device metadata (name, UUID, HA URL, etc.)
 */
class DashieMdnsService(
    private val context: Context,
    private val halitePrefs: HalitePreferences
) {
    companion object {
        private const val TAG = "DashieMdns"

        // mDNS service type — HA matches this against its integration manifest's `zeroconf`
        // list. ONE TYPE PER BRAND, no cross-listing (JS_KOTLIN_CONTRACTS #62): a co-installed
        // Dashie + Chickadee pair would otherwise cross-discover on one network.
        //
        // 🔴 The failure mode is TOTALLY SILENT on both ends. Advertise a type HA is not
        // listening for and HA simply never sees the device — no error, no DROP, nothing to
        // grep. That is why both halves move in the same change and why `lint:wire-values`
        // gates it.
        //
        // Both literals are declared (not just the active one) so the lint can read the APK's
        // zeroconf VOCABULARY and assert it covers every type the integrations declare —
        // the same recognised-⊇-emitted shape as the `hub` check.
        internal const val DASHIE_SERVICE_TYPE = "_dashie-kiosk._tcp"
        internal const val CHICKADEE_SERVICE_TYPE = "_chickadee-kiosk._tcp"

        /**
         * Pure resolver, `internal` so [ZeroconfServiceTypeTest] can pin all three arms.
         *
         * Deliberately a testable function rather than an inline `when`: #62's failure is
         * silent on both ends, so "the constant exists" is not evidence — the same lesson as
         * `isPublishedHubValue`, where a declared-but-never-compared constant read as covered
         * and wasn't.
         */
        internal fun serviceTypeFor(edition: String): String = when (edition) {
            "dashie" -> DASHIE_SERVICE_TYPE
            "chickadee" -> CHICKADEE_SERVICE_TYPE
            else -> {
                Log.w(TAG, "DROP: unrecognised BuildConfig.EDITION '$edition' — no zeroconf " +
                    "service type is defined for it. Falling back to '$DASHIE_SERVICE_TYPE', " +
                    "which is WRONG for any non-Dashie edition: HA will not be listening for it " +
                    "and discovery will silently never fire. Add the edition here and to " +
                    "JS_KOTLIN_CONTRACTS #62.")
                DASHIE_SERVICE_TYPE
            }
        }

        /** The type THIS build advertises. One brand axis, same as
         *  [com.dashieapp.Dashie.edition.ApiPaths]. */
        private val SERVICE_TYPE: String = serviceTypeFor(BuildConfig.EDITION)
    }

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    /**
     * Start mDNS service registration.
     */
    fun start() {
        if (isRegistered) {
            Log.w(TAG, "mDNS service already registered")
            return
        }

        if (!halitePrefs.connection.apiEnabled) {
            Log.i(TAG, "API not enabled, skipping mDNS")
            return
        }

        Log.i(TAG, "Starting mDNS service registration")

        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            if (nsdManager == null) {
                Log.e(TAG, "NsdManager not available")
                return
            }

            // Use user-configured port (falls back to BuildConfig default)
            val effectivePort = halitePrefs.connection.apiPort

            // Build service info with TXT records containing device metadata
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = halitePrefs.connection.deviceName // e.g. "SM-X200"
                serviceType = SERVICE_TYPE
                port = effectivePort

                // Add TXT records with device info for HA integration
                setAttribute("uuid", halitePrefs.connection.deviceUuid)
                setAttribute("name", halitePrefs.connection.deviceName)
                setAttribute("version", BuildConfig.VERSION_NAME)
                setAttribute("api_port", effectivePort.toString())

                // Include HA URL if configured (helps HA filter its own devices)
                val haUrl = halitePrefs.connection.haUrl.takeIf {
                    it.isNotBlank() && it != "http://192.168.1.x:8123"
                }
                if (haUrl != null) {
                    setAttribute("ha_url", haUrl)
                }
            }

            // Create registration listener
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "✅ mDNS service registered: ${serviceInfo.serviceName}")
                    isRegistered = true
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "❌ mDNS registration failed: errorCode=$errorCode")
                    isRegistered = false
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.i(TAG, "mDNS service unregistered: ${serviceInfo.serviceName}")
                    isRegistered = false
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS unregistration failed: errorCode=$errorCode")
                }
            }

            // Register the service
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting mDNS service", e)
            isRegistered = false
        }
    }

    /**
     * Stop mDNS service registration.
     */
    fun stop() {
        if (!isRegistered) {
            Log.d(TAG, "mDNS service not registered, nothing to stop")
            return
        }

        Log.i(TAG, "Stopping mDNS service")

        try {
            registrationListener?.let { listener ->
                nsdManager?.unregisterService(listener)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mDNS service", e)
        } finally {
            isRegistered = false
            registrationListener = null
            nsdManager = null
        }
    }

    /**
     * Check if service is currently registered.
     */
    fun isRunning(): Boolean = isRegistered
}
