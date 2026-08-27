package com.dashieapp.Dashie.halite.wiring

import android.util.Log
import com.dashieapp.Dashie.halite.HaliteComponentRegistry
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog

/**
 * Routes "the kiosk shell document is unreachable" faults into the dashboard
 * recovery ladder.
 *
 * ## Why this exists
 *
 * `MediaComponentWiring.evalInHaContext` returns `no-ha` **only** when the main
 * frame has neither `evalInHaIframe` (defined by kiosk-shell.js) nor a
 * `home-assistant` element. That is not "HA is idle" — it means the script never
 * ran, because **the shell document itself is broken**. To the user that is a
 * blank dashboard.
 *
 * Before this, sustained `no-ha` only stopped the keepalive ping loop, under the
 * comment *"nothing to keep alive"*. True of the ping; false of the device. A
 * recoverable fault was being treated as a reason to stop looking, so nothing
 * ever reached [com.dashieapp.Dashie.halite.DashboardHealthCoordinator] — which
 * had the reload → reload-page → recreate ladder ready the whole time.
 *
 * ## Field evidence
 *
 * Diagnostics report `e4403639` (samsung SM-X210 `gta9pwifi`, Android 16, v1.0.8,
 * submitted 2026-06-16 08:47): **2488** `no-ha` drops, **169 consecutive**
 * (`Stopped: 169 pings, 169 drops`) in the sleep immediately before the wake the
 * user reported, then `Wake nudge: no-ha` → `Wake nudge retry: no-ha` → nothing.
 * `MEMORY: HA: online` printed throughout — the health summary disagreed with a
 * context that was unreachable 169/169 times, which is why nothing upstream
 * reacted. The user was still reporting the blank screen on 2026-08-07.
 *
 * ## Safety
 *
 * [com.dashieapp.Dashie.halite.DashboardHealthCoordinator.onIframeError] is safe
 * to call repeatedly: it is kill-switch guarded (`smartReconnectEnabled`), only
 * resets backoff on the HEALTHY→error transition, and schedules its own
 * backed-off loop — so this cannot become a reload storm.
 *
 * ⚠️ It owns recovery only while the screen is **asleep**; awake it defers to the
 * JS poll, which in this specific fault is the very thing that cannot run. The
 * asleep path is what actually recovers this case — and is where the 169
 * failures above happened, before the user ever walked up.
 */
object ShellHealthEscalation {

    private const val TAG = "ShellHealthEscalation"

    /**
     * Report an unreachable shell document. [reason] is a short tag identifying the
     * detector (e.g. `keepalive-x5`, `wake-nudge-retry`) and is carried into the
     * coordinator's log line so a field report says which path noticed.
     */
    fun reportShellUnreachable(registry: HaliteComponentRegistry, reason: String) {
        val coordinator = registry.dashboardHealthCoordinatorProvider?.invoke()
        if (coordinator == null) {
            // Loud on purpose: a missing provider means the fault is detected and
            // then dropped, which is precisely the bug this object exists to end.
            Log.w(TAG, "DROP: shell unreachable ($reason) but no health coordinator wired")
            PersistentLog.warn(
                "WS_KEEPALIVE",
                "DROP: shell unreachable ($reason) but no health coordinator wired — cannot recover"
            )
            return
        }
        val url = try { registry.webViewRef.url ?: "unknown" } catch (e: Exception) { "err" }
        registry.activityRef.runOnUiThread {
            // No "url=" here: the coordinator already prefixes this argument with
            // url= in its HEALTH line, so adding one produced a doubled label in the
            // device run ("url=shell-unreachable:keepalive-x10 url=http://…").
            coordinator.onIframeError("shell-unreachable:$reason@${url.take(80)}")
        }
    }
}
