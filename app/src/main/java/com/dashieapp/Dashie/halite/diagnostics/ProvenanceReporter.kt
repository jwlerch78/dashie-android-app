package com.dashieapp.Dashie.halite.diagnostics

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.halite.HalitePreferences
import com.dashieapp.Dashie.util.StableDeviceId
import org.json.JSONObject

/**
 * Runtime provenance — the ground truth of "which lanes is THIS device actually running?",
 * assembled in ONE place (postmortem 20260718 P0: incident D burned hours because every
 * verification targeted an assumption instead of the runtime).
 *
 * Two consumers render [report] verbatim (so they can never disagree):
 *   • `:2323/?cmd=provenance` (DashieApiServer) — `curl http://<ip>:2323/?cmd=provenance`
 *   • the `PROVENANCE: {...}` logcat line stamped at boot and after WebView recreation
 *
 * Deliberately synchronous: pref reads, BuildConfig constants, a cached URL, and one
 * asset-file header read. No network, no WebView eval — safe from the boot path.
 */
object ProvenanceReporter {

    private const val TAG = "Provenance"

    /** Last URL the MAIN WebView started loading — set by DashieWebViewClient.onPageStarted
     *  (main frame). The single fact that would have collapsed incident D. */
    @Volatile var lastMainUrl: String? = null

    /** Per-bundle build stamps, read once from the APK asset headers (see build.js banner). */
    @Volatile private var cachedBundleStamps: Map<String, String>? = null

    fun report(context: Context): JSONObject {
        val prefs = HalitePreferences(context)
        val url = lastMainUrl ?: ""
        return JSONObject().apply {
            // ── JS delivery ──
            put("jsSource", classifyJsSource(url))
            put("mainUrl", url)
            val stamps = bundleStamps(context)
            put("kioskBundleBuild", newestBundleStamp(stamps))
            put("kioskBundleBuilds", JSONObject(stamps as Map<*, *>))
            // ── voice lanes ──
            val v = prefs.voice
            put("voicePipelineMode", v.voicePipelineMode)          // "ai" (Dashie cloud) | "ha"
            put("voiceControlMethod", v.voiceControlMethod)
            put("agentMode", v.conversationEngineMode)             // live | dialog | single (effective)
            put("agentModeRaw", v.agentMode)                       // "" = derived from legacy keys
            put("brainRoute", v.brainRoute)                        // cloud | byok | hermes | local | ""
            put("aiModel", v.aiModel)
            put("personality", com.dashieapp.Dashie.halite.preferences.AiPreferences(context).personalityId)
            put("voiceKey", v.voiceKey)
            // ── identity / mode ──
            put("mode", if (prefs.account.showsDashboard) "full" else "kiosk")
            put("haConfigured", prefs.connection.haUrl.isNotEmpty())
            put("flavor", BuildConfig.FLAVOR)
            put("versionName", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("deviceId", StableDeviceId.read(context).take(8))
        }
    }

    /** Stamp the provenance line into logcat — call at boot and after WebView recreation. */
    fun stamp(context: Context, where: String) {
        try {
            Log.i(TAG, "PROVENANCE[$where]: ${report(context)}")
        } catch (e: Exception) {
            Log.w(TAG, "PROVENANCE stamp failed: ${e.message}")
        }
    }

    private fun classifyJsSource(url: String): String = when {
        url.isEmpty() -> "unknown (no page loaded yet)"
        // Kiosk overlay pages are APK-served on the HA origin (KioskCssInjector intercept) —
        // the shell page names, the /_dashie/ asset path, and the legacy virtual host all mean
        // "this device runs the APK kiosk bundle", the incident-D lane.
        url.contains("dashie-kiosk-overlay.local") || url.contains("/_dashie/") ||
            url.contains("kiosk-shell.html") || url.contains("kiosk-services.html") ||
            url.contains("dash-menu.html") -> "kiosk-overlay"
        url.contains("dev.dashieapp.com") -> "vercel-staging"
        url.contains("app.dashieapp.com") -> "vercel-prod"
        url.contains("local.dashieapp.com") -> "local-tunnel"
        else -> "other"   // HA dashboard origin (kiosk mode pre-overlay), login pages, …
    }

    /**
     * The `// DASHIE-BUNDLE-BUILD <sha> <time>` banner of EVERY bundle in the APK, keyed by file.
     *
     * ## 🔴 Why this is per-bundle and enumerated (fixed 2026-08-22)
     *
     * This used to read ONE hard-coded file — `kiosk-services.bundle.js` — and report it as
     * `kioskBundleBuild`, as if there were a single kiosk bundle build. There is not, and since
     * `build.js` went content-stable (2026-07-31) there cannot be: a bundle is rewritten only when
     * its own CODE changes, so **each bundle carries the stamp of the last build that changed
     * IT**. Two bundles legitimately differ by weeks.
     *
     * That is exactly what happened on the Fire: services was untouched since Aug-2 and reported
     * its true Aug-2 stamp, while `kiosk-shell` — the bundle that had just been rebuilt — carried
     * Aug-22 and was never read. Provenance therefore told a field diagnostician the device ran a
     * 20-day-old bundle. The content-stable design was working; the instrument was reading the
     * wrong file, and an instrument that lies is worse than none.
     *
     * 📌 **Enumerated, not listed.** A hard-coded second filename would be the same defect waiting
     * on the next bundle (`dash-menu`, the chickadee shell override, whatever comes after). The
     * asset directory is the source of truth for what shipped, so a bundle that exists cannot be
     * silently omitted from the report.
     */
    private fun bundleStamps(context: Context): Map<String, String> {
        cachedBundleStamps?.let { return it }
        val stamps = try {
            (context.assets.list("webapp/dist") ?: emptyArray())
                .filter { it.endsWith(".bundle.js") }
                .sorted()
                .associateWith { name -> readBundleStamp(context, "webapp/dist/$name") }
                .ifEmpty { mapOf("(none)" to "no bundles in webapp/dist") }
        } catch (e: Exception) {
            mapOf("(error)" to "unreadable (${e.message})")
        }
        cachedBundleStamps = stamps
        return stamps
    }

    private fun readBundleStamp(context: Context, path: String): String = try {
        context.assets.open(path).bufferedReader().use { r ->
            val head = CharArray(200).let { buf -> String(buf, 0, maxOf(r.read(buf), 0)) }
            Regex("DASHIE-BUNDLE-BUILD\\s+(\\S+\\s+\\S+)").find(head)?.groupValues?.get(1)
                ?: "unstamped (bundle predates build.js banner)"
        }
    } catch (e: Exception) {
        "unreadable (${e.message})"
    }

    /**
     * The scalar `kioskBundleBuild`, kept because `?cmd=provenance` readers and runbooks expect a
     * string — but it is now the NEWEST stamp across the bundles, WITH the bundle it came from
     * named. The question the field asks is *"is the bundle on this device the one I just
     * built?"*, and after a content-stable build the honest scalar answer is the most recent
     * change to ANY bundle. Naming the file is what keeps it from being misread as "all of them".
     * Read `kioskBundleBuilds` for the per-bundle truth.
     *
     * Ordering is lexical on the `<sha> <ISO-8601 time>` stamp — the timestamp is fixed-width UTC
     * ISO, so a plain string compare on the time half is chronological. Entries whose stamp is not
     * a real one ("unstamped …", "unreadable …") sort out via the regex rather than by luck.
     */
    internal fun newestBundleStamp(stamps: Map<String, String>): String {
        val real = stamps.entries.filter { Regex("^\\S+\\s+\\d{4}-").containsMatchIn(it.value) }
        if (real.isEmpty()) return stamps.entries.firstOrNull()?.value ?: "no bundles"
        val newest = real.maxByOrNull { it.value.substringAfter(' ') }!!
        return "${newest.value} (${newest.key}; newest of ${stamps.size})"
    }
}
