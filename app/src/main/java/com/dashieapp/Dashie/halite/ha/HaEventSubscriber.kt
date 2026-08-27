package com.dashieapp.Dashie.halite.ha

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * One long-lived, authenticated Home Assistant WebSocket that fans HA events out to any number of
 * in-app consumers.
 *
 * ## Why this is general infrastructure and not a lease detail
 *
 * It was built for the capability-lease revocation nudge (`JS_KOTLIN_CONTRACTS #68`), but HA
 * events are classic reuse-me plumbing and the seam rule says share the first copy rather than let
 * the next consumer duplicate it. **One authenticated socket per device is also the
 * resource-correct shape** — a second feature opening its own would be the mirror problem in
 * socket form, and HA would carry two connections doing the same work.
 *
 * Distinct from [com.dashieapp.Dashie.halite.voice.HaAssistClient], which opens a socket per
 * assist-pipeline run and closes it. That one is a request/response conversation; this one is a
 * subscription that outlives any single interaction.
 *
 * ## Reconnect is the normal case, not the error case
 *
 * HA restarts, WiFi drops and token rotations are routine on a wall tablet. The socket
 * re-establishes with capped exponential backoff and **re-subscribes every handler**, because a
 * subscription that silently fails to come back is indistinguishable from an event that never
 * fired — which for the lease nudge would mean sharing-off quietly stopping taking effect
 * promptly, with the TTL masking it.
 *
 * ⚠️ Consumers must treat a missed event as possible. Nothing here is a delivery guarantee, and
 * for the lease that is fine by design: a missed nudge costs time, never correctness, because the
 * lease expiry is the actual guarantee.
 */
class HaEventSubscriber(
    /** Live HA base URL (`http://host:8123`). Read per connect — it can change. */
    private val haUrlProvider: () -> String,
    /** Live HA token. Rotates roughly every 30 minutes, so it is read per connect, never cached. */
    private val haTokenProvider: () -> String,
    private val http: OkHttpClient = defaultClient(),
) {

    private companion object {
        const val TAG = "HaEventSub"
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 60_000L

        /** No read timeout: an idle subscription is healthy, not stalled. HA sends pings. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    /** eventType -> handlers. Guarded by `this`. */
    private val handlers = mutableMapOf<String, MutableList<(JSONObject) -> Unit>>()

    private var socket: WebSocket? = null
    private var started = false
    private var backoffMs = MIN_BACKOFF_MS
    private val msgId = AtomicInteger(1)

    /**
     * Register a handler for an HA event type. Safe to call before [start] and after a
     * reconnect; the subscription is (re)issued whenever the socket authenticates.
     *
     * @param data the event's `data` object, already unwrapped.
     */
    @Synchronized
    fun on(eventType: String, handler: (data: JSONObject) -> Unit) {
        handlers.getOrPut(eventType) { mutableListOf() }.add(handler)
        // Live socket ⇒ subscribe now; otherwise it goes out at the next auth_ok.
        socket?.let { subscribe(it, eventType) }
    }

    /**
     * Open the socket and keep it open. Idempotent.
     *
     * 🔴 Both outcomes are LOUD, including the boring one. T's s26p run found this subscriber
     * producing **zero log lines of any kind** on a device where the box half demonstrably
     * worked — and with no line here, "start() was never called", "start() returned early
     * because it was already started" and "start() ran and the socket died silently" are one
     * indistinguishable silence. That is standing rule 2's exact failure shape, and it cost a
     * whole diagnostic round.
     */
    @Synchronized
    fun start() {
        if (started) {
            Log.i(TAG, "start() ignored — already started (this is normal on a re-init)")
            return
        }
        started = true
        Log.i(TAG, "start() — opening the HA event socket")
        connect()
    }

    /** Close and stop reconnecting. Handlers are retained for a later [start]. */
    @Synchronized
    fun stop() {
        started = false
        socket?.close(1000, "stopped")
        socket = null
    }

    /**
     * One connection attempt.
     *
     * 🔴 **Every exit from this function is now logged, including the throw.** It previously had
     * three ways to end in total silence, and T hit at least one of them: a malformed URL makes
     * OkHttp's `Request.Builder.url()` throw `IllegalArgumentException` (an HA origin stored
     * WITHOUT a scheme survives both `replace` calls unchanged and is unparseable), and a throw
     * here unwinds through [start] leaving no trace at all. `runCatching` + a reconnect keeps a
     * bad origin retrying instead of permanently dead, which is the same "downgrade, never
     * outage" rule the lease itself follows.
     */
    private fun connect() {
        val base = haUrlProvider().trimEnd('/')
        val token = haTokenProvider()
        if (base.isBlank() || token.isBlank()) {
            // Not an error: HA may not be configured yet, or the token not minted. Retry quietly.
            // Still LOUD about which half is missing — "no HA configured" and "token not minted
            // yet" are different operator actions, and the reconnect line alone conflated them.
            Log.i(TAG, "no credentials yet (haUrl=${if (base.isBlank()) "MISSING" else "set"} " +
                "token=${if (token.isBlank()) "MISSING" else "set"}) — will retry")
            scheduleReconnect("no_ha_credentials")
            return
        }
        val wsUrl = base.replace("https://", "wss://").replace("http://", "ws://") + "/api/websocket"
        // Origin only, never the token. The SCHEME is the load-bearing part: an origin that
        // reaches here still starting with http/https means neither replace matched, which is
        // the malformed-URL case above.
        Log.i(TAG, "connecting to $wsUrl")

        val opened = runCatching {
            http.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : WebSocketListener() {
                    /**
                     * 🔴 This override did not exist, and its absence is why "zero lines" was
                     * possible. Between creating the socket and [handleMessage]'s `auth_ok`
                     * there was NO marker — so a socket that opens and is then never spoken to
                     * (an HA behind a proxy that upgrades the connection but never forwards
                     * `auth_required`, say) looked identical to one that was never created.
                     */
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "socket open (HTTP ${response.code}) — awaiting auth_required")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(webSocket, text, token)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "socket failed: ${t.javaClass.simpleName} ${t.message}" +
                            (response?.let { " (HTTP ${it.code})" } ?: ""))
                        scheduleReconnect("failure")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.i(TAG, "socket closed ($code $reason)")
                        if (started) scheduleReconnect("closed:$code")
                    }
                }
            )
        }.onFailure {
            // The silent killer: url() rejecting a scheme-less or malformed origin.
            Log.e(TAG, "could not open a socket to '$wsUrl': " +
                "${it.javaClass.simpleName} ${it.message} — is the HA origin missing its scheme?", it)
            scheduleReconnect("open_threw")
        }.getOrNull()

        socket = opened
    }

    private fun handleMessage(ws: WebSocket, text: String, token: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "auth_required" ->
                ws.send(JSONObject().put("type", "auth").put("access_token", token).toString())

            "auth_ok" -> {
                Log.i(TAG, "authenticated — (re)subscribing ${handlers.size} event type(s)")
                backoffMs = MIN_BACKOFF_MS      // a real connection resets the backoff
                synchronized(this) { handlers.keys.toList() }.forEach { subscribe(ws, it) }
            }

            "auth_invalid" -> {
                // Usually a rotated token. Reconnecting re-reads it, so this self-heals; log it
                // because a PERSISTENT auth_invalid is a real problem that otherwise looks
                // identical to a quiet network.
                Log.w(TAG, "auth_invalid — will retry with a freshly read token")
                scheduleReconnect("auth_invalid")
            }

            /**
             * 🔴 HA's answer to our `subscribe_events`, and it was being DISCARDED.
             *
             * A refused subscription is the failure mode this whole transport exists because of:
             * #68 died precisely because HA rejects `subscribe_events` for a custom event type
             * from a non-admin user — a wall tablet's exact situation. We swapped to
             * `state_changed` on that finding and then still never read the reply, so a second
             * refusal (a permission change, a future HA tightening `state_changed`) would look
             * identical to "the nudge simply never fired", with the TTL quietly masking it.
             *
             * Success is logged at debug volume — one line per subscription, at connect only —
             * because "we are definitely subscribed" is the fact that makes an absence of events
             * mean something.
             */
            "result" -> {
                if (msg.optBoolean("success", true)) {
                    Log.i(TAG, "subscribe ok (id=${msg.optInt("id")})")
                } else {
                    val err = msg.optJSONObject("error")
                    Log.e(TAG, "DROP: HA REFUSED our subscription (id=${msg.optInt("id")}): " +
                        "${err?.optString("code")} ${err?.optString("message")} — no events of " +
                        "that type will ever arrive on this connection.")
                }
            }

            "event" -> {
                val event = msg.optJSONObject("event") ?: return
                val type = event.optString("event_type")
                val data = event.optJSONObject("data") ?: JSONObject()
                val targets = synchronized(this) { handlers[type]?.toList() }.orEmpty()
                if (targets.isEmpty()) {
                    // Subscribed but nobody listening: a handler was removed, or HA is sending a
                    // type we no longer want. Loud because it is otherwise invisible.
                    Log.w(TAG, "DROP: HA event '$type' with no handler")
                    return
                }
                targets.forEach { h -> runCatching { h(data) }
                    .onFailure { Log.e(TAG, "handler for '$type' threw", it) } }
            }
        }
    }

    private fun subscribe(ws: WebSocket, eventType: String) {
        ws.send(
            JSONObject()
                .put("id", msgId.getAndIncrement())
                .put("type", "subscribe_events")
                .put("event_type", eventType)
                .toString()
        )
    }

    private fun scheduleReconnect(cause: String) {
        synchronized(this) {
            socket = null
            if (!started) return
        }
        val delay = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        Log.i(TAG, "reconnecting in ${delay}ms ($cause)")
        Thread {
            Thread.sleep(delay)
            synchronized(this) { if (started && socket == null) connect() }
        }.apply { isDaemon = true }.start()
    }
}
