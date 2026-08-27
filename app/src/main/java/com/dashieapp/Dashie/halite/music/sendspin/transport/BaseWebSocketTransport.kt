package com.dashieapp.Dashie.halite.music.sendspin.transport

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Base class for WebSocket-based SendSpin transports using Ktor.
 *
 * Encapsulates the shared connection lifecycle, channel management, send/receive loops,
 * and close logic. Subclasses provide only the transport-specific setup:
 * - [WebSocketTransport]: direct `ws://host:port/path` for local network
 * - [ProxyWebSocketTransport]: `wss://` URL with optional Bearer auth header for proxies
 *
 * ## Thread Safety
 * This class is thread-safe. All state changes are atomic, and Ktor handles
 * WebSocket coroutines internally.
 *
 * @param tag Log tag for this transport instance
 * @param httpClient Ktor HttpClient configured for WebSocket connections
 */
abstract class BaseWebSocketTransport(
    protected val tag: String,
    protected val httpClient: HttpClient
) : SendSpinTransport {

    companion object {
        /**
         * Default read-idle timeout. A half-open ("zombie") socket — common on
         * cheap WiFi devices with aggressive power management — produces no
         * inbound frames, so the receive loop would otherwise block forever and
         * the connection would never be detected as dead. This bounds that:
         * if nothing is read within the window, Ktor throws a
         * SocketTimeoutException → onFailure(recoverable) → reconnect.
         *
         * Must comfortably exceed 2× the ping interval so that, on a healthy
         * connection, inbound pongs (and SERVER_TIME frames) keep resetting the
         * timer. With the default 30s ping, 75s tolerates two missed pongs.
         */
        const val DEFAULT_READ_TIMEOUT_MS = 75_000L

        /**
         * Create a default Ktor HttpClient configured for WebSocket connections.
         */
        fun createDefaultClient(
            pingIntervalSeconds: Long = 30,
            connectTimeoutMs: Long = 5000,
            readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS
        ): HttpClient = HttpClient {
            install(WebSockets) {
                pingIntervalMillis = pingIntervalSeconds * 1000
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                connectTimeoutMillis = connectTimeoutMs
                // Read-idle timeout: detects half-open sockets. Ktor resets this
                // on every inbound frame (pongs, SERVER_TIME), so it only fires
                // when the pipe is genuinely silent — i.e. the dead state.
                socketTimeoutMillis = readTimeoutMs
            }
        }
    }

    private val _state = AtomicReference(TransportState.Disconnected)
    override val state: TransportState get() = _state.get()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var listener: SendSpinTransport.Listener? = null

    // Channel for outgoing messages (text or binary)
    private var outgoingChannel: Channel<OutgoingMessage>? = null

    private sealed class OutgoingMessage {
        data class Text(val text: String) : OutgoingMessage()
        data class Binary(val bytes: ByteArray) : OutgoingMessage()
    }

    // ------------------------------------------------------------------
    // Abstract hooks for subclasses
    // ------------------------------------------------------------------

    /**
     * Return the WebSocket URL to connect to (e.g., "ws://192.168.1.100:8927/sendspin"
     * or "wss://proxy.example.com/sendspin").
     */
    protected abstract fun buildWebSocketUrl(): String

    /**
     * Optional hook to customise the HTTP upgrade request (e.g., add auth headers).
     * Default implementation does nothing.
     */
    protected open fun configureRequest(builder: HttpRequestBuilder) {
        // No-op by default
    }

    /**
     * Check if an error is likely temporary (network glitch) vs. permanent (config error).
     * Subclasses may override to add transport-specific checks (e.g., auth errors).
     */
    protected open fun isRecoverableError(t: Throwable): Boolean {
        val cause = t.cause ?: t
        val message = t.message?.lowercase() ?: ""
        val causeName = cause::class.simpleName?.lowercase() ?: ""

        return when {
            // Network errors that might resolve themselves
            causeName.contains("socketexception") -> true
            causeName.contains("eofexception") -> true
            causeName.contains("sockettimeoutexception") -> true
            causeName.contains("timeoutexception") -> true
            message.contains("reset") -> true
            message.contains("abort") -> true
            message.contains("broken pipe") -> true
            message.contains("connection closed") -> true
            message.contains("timeout") -> true

            // Configuration errors that won't fix themselves
            causeName.contains("unknownhostexception") -> false
            causeName.contains("sslhandshakeexception") -> false
            causeName.contains("connectexception") -> false
            causeName.contains("noroutetohostexception") -> false
            message.contains("refused") -> false
            message.contains("unknown host") -> false
            message.contains("no route") -> false

            // Default to recoverable (optimistic)
            else -> true
        }
    }

    // ------------------------------------------------------------------
    // SendSpinTransport implementation
    // ------------------------------------------------------------------

    override fun setListener(listener: SendSpinTransport.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (!_state.compareAndSet(TransportState.Disconnected, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Failed, TransportState.Connecting) &&
            !_state.compareAndSet(TransportState.Closed, TransportState.Connecting)) {
            Log.w(tag, "Cannot connect: already ${state}")
            return
        }

        val wsUrl = buildWebSocketUrl()
        Log.d(tag, "Connecting to: $wsUrl")

        val sendChannel = Channel<OutgoingMessage>(Channel.BUFFERED)
        outgoingChannel = sendChannel

        connectionJob = scope.launch {
            try {
                httpClient.webSocket(
                    urlString = wsUrl,
                    request = { configureRequest(this) }
                ) {
                    Log.d(tag, "WebSocket connected")
                    _state.set(TransportState.Connected)
                    listener?.onConnected()

                    // Launch sender coroutine
                    val senderJob = launch {
                        try {
                            for (msg in sendChannel) {
                                when (msg) {
                                    is OutgoingMessage.Text -> send(Frame.Text(msg.text))
                                    is OutgoingMessage.Binary -> send(Frame.Binary(true, msg.bytes))
                                }
                            }
                        } catch (_: ClosedSendChannelException) {
                            // Channel closed, normal shutdown
                        } catch (_: CancellationException) {
                            // Coroutine cancelled
                        }
                    }

                    // Receive loop
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> listener?.onMessage(frame.readText())
                                is Frame.Binary -> {
                                    val bytes = frame.readBytes()
                                    val l = listener
                                    Log.v(tag, "Binary frame: ${bytes.size} bytes, listener=${l != null}")
                                    l?.onMessage(bytes)
                                }
                                is Frame.Close -> {
                                    val reason = closeReason.await()
                                    val code = reason?.code?.toInt() ?: 1000
                                    val msg = reason?.message ?: ""
                                    Log.d(tag, "WebSocket closing: $code $msg")
                                    listener?.onClosing(code, msg)
                                }
                                else -> { /* Ping/Pong handled by Ktor */ }
                            }
                        }
                    } catch (_: CancellationException) {
                        // Normal cancellation during close
                    }

                    senderJob.cancel()

                    // Session ended normally
                    val reason = closeReason.await()
                    val code = reason?.code?.toInt() ?: 1000
                    val msg = reason?.message ?: ""
                    Log.d(tag, "WebSocket closed: $code $msg")
                    _state.set(TransportState.Closed)
                    listener?.onClosed(code, msg)
                }
            } catch (e: CancellationException) {
                // Intentional close via destroy()/close()
                Log.d(tag, "WebSocket cancelled")
            } catch (e: Exception) {
                Log.e(tag, "WebSocket failure: ${e.message}")
                _state.set(TransportState.Failed)
                listener?.onFailure(e, isRecoverableError(e))
            } finally {
                sendChannel.close()
                outgoingChannel = null
            }
        }
    }

    override fun send(text: String): Boolean {
        if (!isConnected) {
            Log.w(tag, "Cannot send text: not connected (state=$state)")
            return false
        }
        val channel = outgoingChannel ?: return false
        return channel.trySend(OutgoingMessage.Text(text)).isSuccess
    }

    override fun send(bytes: ByteArray): Boolean {
        if (!isConnected) {
            Log.w(tag, "Cannot send bytes: not connected (state=$state)")
            return false
        }
        val channel = outgoingChannel ?: return false
        return channel.trySend(OutgoingMessage.Binary(bytes)).isSuccess
    }

    override fun close(code: Int, reason: String) {
        Log.d(tag, "Closing WebSocket: code=$code reason=$reason")
        outgoingChannel?.close()
        connectionJob?.cancel()
        connectionJob = null
    }

    override fun destroy() {
        close(1000, "Transport destroyed")
        _state.set(TransportState.Closed)
        httpClient.close()
    }
}
