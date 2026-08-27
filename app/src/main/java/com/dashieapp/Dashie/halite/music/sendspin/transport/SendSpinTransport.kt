package com.dashieapp.Dashie.halite.music.sendspin.transport

/**
 * Transport abstraction for SendSpin communication.
 *
 * This interface allows SendSpinClient to work with different transport mechanisms:
 * - **Local**: Direct WebSocket connection to server on local network
 * - **Remote**: WebRTC DataChannel via signaling server for internet access
 *
 * Both transports carry the same SendSpin protocol (JSON text + binary audio),
 * but differ in how the connection is established and maintained.
 *
 * ## Transport Lifecycle
 * ```
 * [Created] -> connect() -> [Connecting] -> [Connected] -> close() -> [Closed]
 *                              |              |
 *                           [Failed]     [Disconnected]
 * ```
 */
interface SendSpinTransport {

    /**
     * Current connection state of the transport.
     */
    val state: TransportState

    /**
     * Whether the transport is currently connected and can send messages.
     */
    val isConnected: Boolean
        get() = state == TransportState.Connected

    /**
     * Initiate connection. This is asynchronous - results delivered via [Listener].
     */
    fun connect()

    /**
     * Send a text message (JSON protocol messages).
     *
     * @param text The message to send
     * @return true if the message was queued for sending, false if transport unavailable
     */
    fun send(text: String): Boolean

    /**
     * Send binary data (audio chunks, artwork).
     *
     * @param bytes The binary data to send
     * @return true if the data was queued for sending, false if transport unavailable
     */
    fun send(bytes: ByteArray): Boolean

    /**
     * Close the transport connection.
     *
     * @param code Close code (1000 = normal, others indicate errors)
     * @param reason Human-readable close reason
     */
    fun close(code: Int = 1000, reason: String = "")

    /**
     * Release all resources associated with this transport.
     * Should be called when the transport is no longer needed.
     */
    fun destroy()

    /**
     * Set the listener for transport events.
     */
    fun setListener(listener: Listener?)

    /**
     * Listener interface for transport events.
     * All callbacks are delivered on the IO dispatcher, not the main thread.
     */
    interface Listener {
        /**
         * Called when the transport connection is established.
         */
        fun onConnected()

        /**
         * Called when the transport receives a text message.
         */
        fun onMessage(text: String)

        /**
         * Called when the transport receives binary data.
         */
        fun onMessage(bytes: ByteArray)

        /**
         * Called when the transport is closing (graceful shutdown).
         *
         * @param code Close code
         * @param reason Close reason
         */
        fun onClosing(code: Int, reason: String)

        /**
         * Called when the transport is fully closed.
         *
         * @param code Close code
         * @param reason Close reason
         */
        fun onClosed(code: Int, reason: String)

        /**
         * Called when the transport encounters an error.
         *
         * @param error The exception that caused the failure
         * @param isRecoverable Whether the error might be temporary (network glitch vs. config error)
         */
        fun onFailure(error: Throwable, isRecoverable: Boolean)
    }
}

/**
 * Connection state for transports.
 */
enum class TransportState {
    /** Initial state, not connected */
    Disconnected,

    /** Connection in progress */
    Connecting,

    /** Connected and ready to send/receive */
    Connected,

    /** Connection failed */
    Failed,

    /** Connection was closed (either by us or remote) */
    Closed
}
