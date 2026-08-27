package com.dashieapp.Dashie.halite.music.sendspin.transport

import io.ktor.client.HttpClient

/**
 * WebSocket-based transport for local network connections using Ktor.
 *
 * Uses Ktor's WebSocket client to establish a direct connection to a
 * SendSpin server on the local network.
 *
 * ## Connection URL
 * Format: `ws://host:port/path` (e.g., `ws://192.168.1.100:8927/sendspin`)
 *
 * ## Thread Safety
 * This class is thread-safe. All state changes are atomic, and Ktor
 * handles WebSocket coroutines internally.
 *
 * @param address Server address in "host:port" format
 * @param path WebSocket path (default: "/sendspin")
 * @param pingIntervalSeconds Ping interval in seconds (default: 30, 15 in High Power Mode)
 * @param connectTimeoutMs Connect timeout in milliseconds (default: 5000)
 * @param readTimeoutMs Read-idle timeout in milliseconds; detects half-open sockets (default: 75s)
 * @param httpClient Optional Ktor HttpClient (creates one if not provided)
 */
class WebSocketTransport(
    private val address: String,
    private val path: String = "/sendspin",
    pingIntervalSeconds: Long = 30,
    connectTimeoutMs: Long = 5000,
    readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    httpClient: HttpClient = createDefaultClient(pingIntervalSeconds, connectTimeoutMs, readTimeoutMs)
) : BaseWebSocketTransport(
    tag = TAG,
    httpClient = httpClient
) {

    companion object {
        private const val TAG = "WebSocketTransport"
    }

    override fun buildWebSocketUrl(): String = "ws://$address$path"
}
