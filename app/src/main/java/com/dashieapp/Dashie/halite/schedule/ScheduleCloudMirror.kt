package com.dashieapp.Dashie.halite.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.util.StableDeviceId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * NATIVE side of the scheduled-actions cloud mirror — the kiosk twin of
 * `js/core/reminders/reminder-sync.js`.
 *
 * The full webapp mirrors device actions to Supabase in JS (reminder-sync.js, fed by
 * `window.dashieReminderEvent`). A KIOSK WebView shows the HA dashboard + the stripped kiosk
 * bundle, which does NOT include reminder-sync — so before this class a kiosk-created reminder
 * never reached the `scheduled_actions` table: the delegate retried 6× then dropped the emit,
 * and the console couldn't list (or edit) the action.
 *
 * Ownership rule (no double-writes): [ownsMirror] is true only when the device does NOT show
 * the Dashie dashboard (kiosk display / not-linked). Then lifecycle events route HERE and the
 * JS emit is skipped. Full mode keeps the JS path (which also owns the realtime broadcast);
 * this class additionally catches full mode's drop path (JS handler never registered — see
 * JsBridgeScheduleDelegate.emitReminderEvent onDropped).
 *
 * Payload + reconcile semantics deliberately MIRROR reminder-sync.js (`_upsert`, `_reconcile`,
 * `_repairMirror`): same edge ops, same field names, same two-phase order — cloud→device FIRST
 * so a console cancel made while the tablet was off can't be resurrected by the device's repair
 * push. Contract row #31 in .reference/JS_KOTLIN_CONTRACTS.md — change both together.
 *
 * v1 gap (deliberate): no native realtime Broadcast subscription — console edits reach a kiosk
 * at the next reconcile (startup, after any failed write, periodic safety net), not instantly.
 * An anonymous kiosk (no account JWT) skips all writes — same as today, nothing to mirror into.
 */
object ScheduleCloudMirror {

    @Volatile private var appContext: Context? = null
    @Volatile private var prefs: HalitePreferences? = null
    @Volatile private var manager: ScheduledActionManager? = null

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "schedule-cloud-mirror").apply { isDaemon = true }
    }
    @Volatile private var repairPending = false
    @Volatile private var repairDelayMs = REPAIR_BASE_MS
    @Volatile private var started = false

    fun wire(context: Context, prefs: HalitePreferences, manager: ScheduledActionManager?) {
        this.appContext = context.applicationContext
        this.prefs = prefs
        this.manager = manager
    }

    /** Kiosk display mode (or not linked) → the full-webapp JS mirror can never run, so native
     *  owns the mirror. A linked dashboard device keeps the JS path (it also broadcasts). */
    fun ownsMirror(): Boolean = prefs?.account?.showsDashboard != true

    /** Kick the startup reconcile + periodic safety net. Delayed so kiosk self-provisioning
     *  (KioskSessionProvisioner) has minted the account JWT before the first attempt. */
    fun start() {
        if (started) return
        started = true
        exec.schedule({ if (ownsMirror()) runReconcile("startup") }, START_DELAY_MS, TimeUnit.MILLISECONDS)
        exec.scheduleWithFixedDelay(
            { if (ownsMirror()) runReconcile("safety-net") },
            SAFETY_RECONCILE_MS, SAFETY_RECONCILE_MS, TimeUnit.MILLISECONDS)
    }

    /** Mirror ONE lifecycle event (created/fired/cancelled/updated). Fire-and-forget with the
     *  same healing as JS: a failed upsert schedules a full reconcile with capped backoff. */
    fun mirror(event: String, action: ScheduledAction) {
        if (accountJwt() == null) {
            Log.d(TAG, "mirror $event skipped — no account JWT (anonymous kiosk)")
            return
        }
        exec.execute {
            try {
                upsert(action)
                Log.i(TAG, "mirror $event ok (${action.id})")
            } catch (e: Exception) {
                Log.w(TAG, "mirror $event failed: ${e.message}")
                scheduleRepair()
            }
        }
    }

    // ---- device → cloud ----

    /** Same payload as reminder-sync.js `_upsert` (contract #31). */
    private fun upsert(action: ScheduledAction) {
        val data = JSONObject()
            .put("local_id", action.id)
            .put("device_id", deviceId())
            .put("notify_text", action.notifyText)
            .put("fire_at", isoUtc(action.fireAtEpochMs))
            .put("vernacular", action.vernacular.ifEmpty { ScheduledAction.VERNACULAR_REMINDER })
            .put("status", action.status.name.lowercase())
            .put("action_type", action.actionType.ifEmpty { ScheduledAction.TYPE_NOTIFY })
            .put("prompt", action.prompt)
            .put("recurrence", action.recurrence ?: JSONObject.NULL)
        call("upsert_scheduled_action", data)
    }

    // ---- reconcile (two-phase; order is load-bearing — see reminder-sync.js `_reconcile`) ----

    private fun runReconcile(reason: String): Boolean {
        val jwt = accountJwt() ?: return true   // nothing to reconcile against, don't spin
        return try {
            val res = call("list_scheduled_actions", JSONObject().put("include_cancelled", true))
            val mine = ArrayList<JSONObject>()
            val all = res.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until all.length()) {
                val row = all.optJSONObject(i) ?: continue
                if (row.optString("device_id") == deviceId()) mine.add(row)
            }
            mine.forEach { applyRemote(it) }        // phase 1 — console wins for edits/cancels
            val ok = repairMirror(mine)             // phase 2 — device wins for what actually ran
            Log.i(TAG, "reconcile ($reason): ${mine.size} cloud row(s), repairOk=$ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "reconcile ($reason) failed: ${e.message}")
            false
        }
    }

    /** Apply a console edit/cancel to the device — only when it actually differs, so the
     *  resulting lifecycle event (which mirrors back) can't ping-pong. */
    private fun applyRemote(row: JSONObject) {
        val mgr = manager ?: return
        val localId = row.optString("local_id").ifEmpty { return }
        val local = mgr.all().find { it.id == localId } ?: return
        when (val status = row.optString("status")) {
            "cancelled" -> if (local.status != ScheduledAction.Status.CANCELLED) {
                main.post { mgr.cancel(localId) }
            }
            "scheduled" -> {
                val fireAt = parseIsoUtc(row.optString("fire_at")) ?: return
                val text = row.optString("notify_text")
                if (local.status == ScheduledAction.Status.SCHEDULED &&
                    (local.fireAtEpochMs != fireAt || local.notifyText != text)) {
                    main.post { mgr.update(localId, text, fireAt, row.optString("vernacular").ifEmpty { local.vernacular }) }
                }
            }
            // 'fired' → nothing to do on the device (it already fired).
            "fired" -> {}
            else -> Log.w(TAG, "DROP: unknown cloud status '$status' for $localId — not reconciled (new server status?)")
        }
    }

    /** Push the DEVICE's state for anything the cloud has wrong (current state, never an event
     *  replay — a stale queued 'fired' could clobber a newer cancel; a state push can't). */
    private fun repairMirror(cloudRows: List<JSONObject>): Boolean {
        val mgr = manager ?: return true
        val byId = cloudRows.associateBy { it.optString("local_id") }
        val stale = mgr.all().filter { !mirrorMatches(byId[it.id], it) }
        if (stale.isEmpty()) return true
        Log.i(TAG, "repair: ${stale.size} action(s) out of sync — pushing device state")
        var ok = true
        for (action in stale) {
            try { upsert(action) } catch (e: Exception) {
                Log.w(TAG, "repair upsert ${action.id} failed: ${e.message}"); ok = false
            }
        }
        return ok
    }

    /** Same agreement test as reminder-sync.js `_mirrorMatches`. */
    private fun mirrorMatches(row: JSONObject?, action: ScheduledAction): Boolean {
        if (row == null) return false
        return row.optString("status") == action.status.name.lowercase() &&
            parseIsoUtc(row.optString("fire_at")) == action.fireAtEpochMs &&
            (row.optString("recurrence").ifEmpty { null }) == action.recurrence &&
            row.optString("action_type").ifEmpty { ScheduledAction.TYPE_NOTIFY } ==
                action.actionType.ifEmpty { ScheduledAction.TYPE_NOTIFY } &&
            row.optString("prompt") == action.prompt
    }

    private fun scheduleRepair() {
        if (repairPending) return
        repairPending = true
        val delay = repairDelayMs
        Log.i(TAG, "mirror repair scheduled in ${delay / 1000}s")
        exec.schedule({
            repairPending = false
            val ok = runReconcile("repair")
            repairDelayMs = if (ok) REPAIR_BASE_MS else (delay * 2).coerceAtMost(REPAIR_MAX_MS)
            if (!ok) scheduleRepair()
        }, delay, TimeUnit.MILLISECONDS)
    }

    // ---- edge-function call (account JWT; same header shape as BrainConverseClient's
    //      reportDeviceTurnResponse — the proven native database-operations caller) ----

    private fun call(operation: String, data: JSONObject): JSONObject {
        val jwt = accountJwt() ?: throw IOException("no account JWT")
        if (BuildConfig.SUPABASE_URL.isBlank()) throw IOException("no SUPABASE_URL in this flavor")
        val body = JSONObject().put("operation", operation).put("data", data)
            .toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url(BuildConfig.SUPABASE_URL.trimEnd('/') + "/functions/v1/database-operations")
            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $jwt")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) throw IOException("$operation HTTP ${resp.code}: ${text.take(200)}")
            val parsed = JSONObject(text)
            if (!parsed.optBoolean("success")) throw IOException(parsed.optString("error", "$operation failed"))
            return parsed
        }
    }

    private fun accountJwt(): String? {
        val conn = prefs?.connection ?: return null
        return if (conn.hasSupabaseJwt && !conn.isSupabaseJwtExpired) conn.supabaseJwt else null
    }

    private fun deviceId(): String = appContext?.let { StableDeviceId.read(it) } ?: ""

    private fun isoUtc(ms: Long): String = isoFormat().format(Date(ms))

    private fun parseIsoUtc(iso: String): Long? {
        if (iso.isBlank()) return null
        // The edge fn stores what we send (millis precision) but be tolerant of a
        // second-precision or offset-suffixed timestamp coming back from Postgres.
        val candidates = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (pattern in candidates) {
            try {
                return SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(iso)?.time ?: continue
            } catch (_: Exception) { /* next pattern */ }
        }
        return null
    }

    private fun isoFormat() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val TAG = "ScheduleCloudMirror"
    private const val REPAIR_BASE_MS = 15_000L
    private const val REPAIR_MAX_MS = 10 * 60_000L
    private const val SAFETY_RECONCILE_MS = 6 * 60 * 60_000L
    private const val START_DELAY_MS = 20_000L
}
