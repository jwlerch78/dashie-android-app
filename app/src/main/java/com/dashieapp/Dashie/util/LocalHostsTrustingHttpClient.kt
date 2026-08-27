package com.dashieapp.Dashie.util

import android.util.Log
import okhttp3.OkHttpClient
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * OkHttp client factory for code paths that target user-configured local
 * network hosts (Home Assistant). Accepts self-signed certs *only* when the
 * destination host matches `LocalNetworkHostnames.isLocalHost`. For public
 * hostnames the connection is closed before any data is exchanged (the
 * TrustManager still accepts to allow handshake completion, but the
 * HostnameVerifier rejects so no HTTP request is sent).
 *
 * **Do not use this for general-purpose HTTPS** (Supabase, Google APIs, etc.)
 * — use the default `OkHttpClient.Builder()` there so you get full cert
 * chain validation.
 *
 * Usage:
 *   val client = LocalHostsTrustingHttpClient.builder()
 *       .connectTimeout(10, TimeUnit.SECONDS)
 *       .build()
 */
object LocalHostsTrustingHttpClient {
    private const val TAG = "LocalHostsHttpClient"

    /**
     * X509ExtendedTrustManager that delegates to the platform default for
     * non-local hosts and unconditionally accepts for local hosts.
     *
     * Hostname is inferred from the SSLSession's peer host (set by OkHttp
     * before the handshake). For socket-based overloads we can't easily get
     * the destination host, so we fall back to delegating.
     */
    private class LocalLenientTrustManager(
        private val delegate: X509ExtendedTrustManager
    ) : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            delegate.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) =
            delegate.checkClientTrusted(chain, authType, engine)

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            // No host info — delegate strictly. Local-host bypass happens in
            // the engine/socket overloads which OkHttp uses in practice.
            delegate.checkServerTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            try {
                delegate.checkServerTrusted(chain, authType, socket)
            } catch (e: Exception) {
                val sslSocket = socket as? javax.net.ssl.SSLSocket
                // session is the post-handshake session; during the handshake
                // (which is when we're called) only handshakeSession is set.
                // Fall back to the socket's InetAddress so we still get the IP
                // even when the SSL session has no peerHost recorded.
                val host = sslSocket?.handshakeSession?.peerHost
                    ?: sslSocket?.session?.peerHost
                    ?: sslSocket?.inetAddress?.hostAddress
                if (LocalNetworkHostnames.isTrustedHost(host)) {
                    Log.i(TAG, "🔓 Accepting self-signed cert for trusted host: $host")
                } else {
                    Log.w(TAG, "❌ Strict cert validation failed for host: $host — ${e.message}")
                    throw e
                }
            }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: javax.net.ssl.SSLEngine?) {
            try {
                delegate.checkServerTrusted(chain, authType, engine)
            } catch (e: Exception) {
                val host = engine?.handshakeSession?.peerHost
                    ?: engine?.session?.peerHost
                    ?: engine?.peerHost
                if (LocalNetworkHostnames.isTrustedHost(host)) {
                    Log.i(TAG, "🔓 Accepting self-signed cert for trusted host: $host")
                } else {
                    Log.w(TAG, "❌ Strict cert validation failed for host: $host — ${e.message}")
                    throw e
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    private fun platformTrustManager(): X509ExtendedTrustManager {
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(null as java.security.KeyStore?)
        return tmf.trustManagers.first { it is X509ExtendedTrustManager } as X509ExtendedTrustManager
    }

    private fun buildSslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(trustManager), SecureRandom())
        return ctx.socketFactory
    }

    // Capture the platform default at class-init time, BEFORE anything
    // calls installAsDefault() and replaces it with our verifier. Without
    // this snapshot, the public-host branch below would call back into
    // ourselves once installed as default → infinite recursion → crash.
    private val platformHostnameVerifier: HostnameVerifier =
        HttpsURLConnection.getDefaultHostnameVerifier()

    private val hostnameVerifier = HostnameVerifier { hostname, session ->
        // For local hosts, accept any (or no) hostname match. For public
        // hosts, fall back to the platform default verifier so we don't
        // weaken cert→hostname binding on the open internet.
        if (LocalNetworkHostnames.isTrustedHost(hostname)) {
            true
        } else {
            platformHostnameVerifier.verify(hostname, session)
        }
    }

    fun builder(): OkHttpClient.Builder {
        val tm = LocalLenientTrustManager(platformTrustManager())
        return OkHttpClient.Builder()
            .sslSocketFactory(buildSslSocketFactory(tm), tm)
            .hostnameVerifier(hostnameVerifier)
    }

    // Lazily-built singletons reused by every HttpURLConnection caller.
    private val httpsSocketFactory: SSLSocketFactory by lazy {
        buildSslSocketFactory(LocalLenientTrustManager(platformTrustManager()))
    }

    /**
     * Apply the same lenient-for-local-hosts SSL + hostname policy to an
     * `HttpURLConnection`. No-op for plain HTTP connections. Call right
     * after `url.openConnection()` and before any read/write.
     *
     * Usually unnecessary if `installAsDefault()` has been called — that
     * sets the same policy as the process-wide default for every
     * `HttpsURLConnection` in the JVM.
     */
    fun applyTo(connection: java.net.URLConnection?) {
        if (connection is HttpsURLConnection) {
            connection.sslSocketFactory = httpsSocketFactory
            connection.hostnameVerifier = hostnameVerifier
        }
    }

    @Volatile private var defaultInstalled = false

    /**
     * Install the lenient-for-local-hosts policy as the process-wide
     * default for every `HttpsURLConnection`. Idempotent — safe to call
     * multiple times. Should be called once at app startup so legacy code
     * paths (`url.openConnection() as HttpURLConnection`) get the same
     * SSL handling without per-site `applyTo()` edits.
     *
     * Public hosts still get full cert chain validation and hostname
     * matching (the trust manager and hostname verifier both delegate to
     * the platform default for non-local hosts).
     */
    fun installAsDefault() {
        if (defaultInstalled) return
        synchronized(this) {
            if (defaultInstalled) return
            try {
                HttpsURLConnection.setDefaultSSLSocketFactory(httpsSocketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier)
                defaultInstalled = true
                android.util.Log.i(TAG, "🔧 Installed LAN-tolerant SSL policy as HttpsURLConnection default")
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "Failed to install default SSL policy", e)
            }
        }
    }
}
