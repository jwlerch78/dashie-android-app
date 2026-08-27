package com.dashieapp.Dashie.halite.install

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Sends a monthly check-in heartbeat to track active devices.
 * Runs on every app startup but only sends if 30+ days have elapsed.
 *
 * ⚠️ **This class used to claim it "receives account entitlements for linked devices."** It does
 * receive them; nothing consumes them. That sentence is what made `EditionTelemetry`'s 2a plan
 * list this as an `EntitlementSync` seam — a doc comment describing an intention the code never
 * carried out, believed over the code. The name of this class is the honest scope: **telemetry**.
 *
 * 🔴 Fixed 2026-08-04: three fields of this payload were reading SharedPreferences files that
 * **no writer has ever created** (`supabase_auth`, `dashie_subscription`), so `isLoggedIn` was
 * unconditionally `false` and `subscriptionStatus` unconditionally `null` — confidently wrong
 * rather than absent, which is worse for analytics because the rows look answered.
 *
 * Product decision 2026-08-21: sends ONLY when the "Share Performance Data" permission
 * (dashboard_telemetry_enabled && dashboard_telemetry_consent, both default false) allows it.
 * The suppressed path logs a DROP: marker so "gated off" is distinguishable from "broken".
 *
 * Call [checkinIfDue] from MainActivity.onCreate().
 */
class MonthlyCheckin(private val context: Context) {
    companion object {
        private const val TAG = "MonthlyCheckin"
        private const val PREFS_NAME = "dashie_install"
        private const val KEY_LAST_CHECKIN = "last_monthly_checkin"
        private const val CHECKIN_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Send a check-in if 30+ days have elapsed since the last one.
     * Safe to call on every app launch — returns immediately if not due.
     */
    fun checkinIfDue() {
        val lastCheckin = prefs.getLong(KEY_LAST_CHECKIN, 0)
        val now = System.currentTimeMillis()

        if (now - lastCheckin < CHECKIN_INTERVAL_MS) return

        // Product decision 2026-08-21: the check-in is gated on the "Share Performance Data"
        // permission (Settings → Advanced → Anonymous Data Sharing). It reads the SAME
        // enabled && consent conjunction as every other telemetry gate in the tree
        // (TelemetryDelegate, DashieWebViewClient, KioskCssInjector, WsProxyDelegate) —
        // a second predicate here is exactly how the gates would drift. Both keys default
        // FALSE, so the 30-day ping goes dark on any install that never opts in; that
        // consequence was stated with, and accepted by, the ruling. `last_monthly_checkin`
        // is deliberately NOT advanced on the suppressed path, so opting in later sends
        // the (already-due) ping on the next launch.
        val perf = com.dashieapp.Dashie.halite.preferences.PerformancePreferences(context)
        if (!perf.dashboardTelemetryEnabled || !perf.dashboardTelemetryConsent) {
            Log.i(TAG, "DROP: monthly check-in due but suppressed — Share Performance Data " +
                "is off (dashboard_telemetry_enabled=${perf.dashboardTelemetryEnabled}, " +
                "dashboard_telemetry_consent=${perf.dashboardTelemetryConsent}). " +
                "Gated, not broken; nothing was sent.")
            return
        }

        val installToken = prefs.getString(InstallTracker.KEY_INSTALL_TOKEN, null)
        if (installToken == null) {
            Log.d(TAG, "No install token yet — skipping check-in")
            return
        }

        Log.i(TAG, "📡 Monthly check-in due — sending")

        val networkId = NetworkFingerprint.generate(context)

        val data = CheckinData(
            installToken = installToken,
            appVersion = BuildConfig.VERSION_NAME,
            isLoggedIn = isUserLoggedIn(),
            subscriptionStatus = getSubscriptionStatus(),
            networkId = networkId,
            uptimeHours = null, // TODO: approximate uptime tracking
            // Sent every check-in so devices registered before the
            // install_source column existed get backfilled on next ping.
            installSource = InstallTracker.detectInstallSource(context)
        )

        // Intentionally unscoped: one-shot telemetry ping that should complete
        // regardless of UI lifecycle; holds no UI references.
        CoroutineScope(Dispatchers.IO).launch {
            val result = CheckinClient.checkin(data)
            if (result.success) {
                prefs.edit().putLong(KEY_LAST_CHECKIN, now).apply()
                Log.i(TAG, "✅ Check-in recorded")

                if (result.voiceEntitled || result.subscriptionStatus != null) {
                    recordEntitlements(result)
                }
            } else {
                Log.w(TAG, "⚠️ Check-in failed — will retry on next launch")
            }
        }
    }

    /**
     * 🔴 **Was reading SharedPreferences file `supabase_auth`, which does not exist.**
     *
     * That file name appears **nowhere else in the codebase** — nothing has ever written it — so
     * this returned `false` unconditionally and **every check-in has reported every device as
     * logged out**, including devices with an active paid account. The defect is not a missing
     * field; it is a field that has been confidently wrong.
     *
     * Now asks the app's own canonical question rather than inventing a second definition of
     * "logged in": `AccountPreferences.isLinked` is what `showsDashboard` and the rest of the app
     * key on. A second predicate here is exactly how the two would drift.
     */
    private fun isUserLoggedIn(): Boolean =
        com.dashieapp.Dashie.halite.preferences.AccountPreferences(context).isLinked

    /**
     * 🔴 **Was reading file `dashie_subscription`; the real one is `dashie_subscription_prefs`.**
     *
     * One suffix apart, and nothing has ever written the shorter name — so `subscriptionStatus`
     * in the check-in payload has always been `null`. `EditionTelemetry`'s KDoc had already
     * diagnosed this half and deliberately left it for a Dashie-side thread; this is that fix.
     *
     * Reads through [SubscriptionPreferences] rather than re-opening the file by name, so the
     * pref name cannot drift out from under this call site a second time. Empty maps to `null`
     * because the wire field is optional and "" is not a status.
     */
    private fun getSubscriptionStatus(): String? =
        com.dashieapp.Dashie.halite.preferences.SubscriptionPreferences(context)
            .subscriptionStatus.takeIf { it.isNotEmpty() }

    /**
     * The server's entitlement answer, **announced rather than cached into a hole**.
     *
     * ⚠️ This used to write `household_voice_entitled` into the orphan `dashie_subscription`
     * file. **Nothing has ever read that key** — not the orphan copy, and there is no
     * voice-entitlement field on [SubscriptionPreferences] either. So the honest fix is NOT to
     * relocate the write to a "real" prefs file: that would move an authored-but-unreached value
     * rather than remove one, and the next reader would reasonably assume it feeds something.
     *
     * The server genuinely sends `voice_entitled`, so the fact is logged where a `DROP:`-style
     * grep will find it. If a consumer is ever wanted, it gets wired deliberately — with a
     * reader — instead of inheriting a value that silently accumulated.
     */
    private fun recordEntitlements(result: CheckinClient.CheckinResult) {
        Log.i(TAG, "DROP: [expected] check-in returned entitlements with no on-device consumer — " +
            "voice_entitled=${result.voiceEntitled}, subscription_status=${result.subscriptionStatus}. " +
            "Reported for telemetry only; nothing reads these on the device.")
    }
}
