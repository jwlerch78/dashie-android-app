package com.dashieapp.Dashie.api

import android.content.Context
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import com.dashieapp.Dashie.halite.preferences.ConnectionPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.util.Collections

/**
 * Observe-only telemetry for the planned :2323 API IP-allowlist — NO enforcement.
 *
 * The Fully Kiosk-compatible API on :2323 currently ships with an empty default
 * password (open to any device that can reach it). Before adding an IP-allowlist
 * gate — which could break "funky" HA network setups (VLANs, Docker bridge,
 * Tailscale/mesh, HA-in-a-VM, a second controller) — we first measure what source
 * IPs actually hit the API and how a future gate WOULD classify them.
 *
 * Blocks nothing. Two outputs:
 *  - one PersistentLog line per distinct source IP (best-effort; scrolls out of the
 *    300-line crash tail on long-running devices), and
 *  - a persisted rollup (SharedPreferences) surfaced via [reportSection], which is
 *    injected into crash + diagnostics reports so it rides EVERY report regardless of
 *    timing — the reliable path, since crash reports are our highest-volume channel.
 *
 * See .reference/SECURITY_AUDIT_2026-06-29.md / _TECHNICAL_DEBT.md.
 */
object ApiAccessObserver {
    private const val TAG = "ApiAccessObserve"
    private const val PREFS = "dashie_api_access_observe"
    private const val KEY_SUMMARY = "summary_json"
    private const val MAX_IPS = 50  // cap the persisted distinct-IP set

    // Per-process dedup so we don't touch prefs on every request (HA polls constantly).
    private val seenThisProcess = Collections.synchronizedSet(HashSet<String>())

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    /** Call at the top of serve(). Never throws, never blocks, never affects routing. */
    fun observe(context: Context, remoteIp: String?) {
        try {
            val ip = remoteIp?.trim().orEmpty()
            if (ip.isEmpty() || !seenThisProcess.add(ip)) return
            recordIfNew(context, ip)
        } catch (_: Throwable) {
            // observe-only: must never affect request handling
        }
    }

    /**
     * Compact summary for crash / diagnostics reports. Backed by SharedPreferences so it
     * survives across the early-observation → later-crash gap. Returns "" if the API has
     * never been hit. `wouldBlockAny=true` is the at-a-glance "this config would break
     * under enforcement" flag.
     */
    fun reportSection(context: Context): String = try {
        val json = load(context)
        val verdicts = json.optJSONObject("verdicts")
        if (verdicts == null || verdicts.length() == 0) {
            ""
        } else {
            val wouldBlockAny = verdicts.keys().asSequence()
                .any { it.startsWith("WOULD_BLOCK") || it == "DERIVED_UNAVAILABLE" }
            val sb = StringBuilder()
            sb.appendLine("─── API ACCESS (:2323 observe — no enforcement) ───")
            sb.appendLine(
                "haHost=${json.optString("haHost").ifBlank { "?" }} " +
                    "deviceIp=${json.optString("deviceIp").ifBlank { "?" }} " +
                    "wouldBlockAny=$wouldBlockAny"
            )
            for (key in verdicts.keys()) {
                val e = verdicts.getJSONObject(key)
                sb.appendLine("$key: ${e.optInt("count")} (e.g. ${e.optString("example")})")
            }
            sb.appendLine()
            sb.toString()
        }
    } catch (_: Throwable) {
        ""
    }

    @Synchronized
    private fun recordIfNew(context: Context, ip: String) {
        val json = load(context)
        val ips = json.optJSONArray("ips") ?: JSONArray()
        for (i in 0 until ips.length()) if (ips.getString(i) == ip) return  // seen in a prior process

        val haUrl = ConnectionPreferences(context).haUrl
        val haHost = try { URI(haUrl).host } catch (_: Throwable) { null }
        val derivedHaIp = haHost?.takeIf { isIpv4(it) }
        val deviceIp = primarySiteLocalIp()
        val verdict = classify(ip, derivedHaIp, deviceIp)

        val verdicts = json.optJSONObject("verdicts") ?: JSONObject()
        val entry = verdicts.optJSONObject(verdict) ?: JSONObject().put("example", ip).put("count", 0)
        entry.put("count", entry.optInt("count") + 1)
        verdicts.put(verdict, entry)

        if (ips.length() < MAX_IPS) ips.put(ip)
        json.put("ips", ips)
        json.put("verdicts", verdicts)
        json.put("haHost", haHost ?: "")
        json.put("deviceIp", deviceIp ?: "")
        save(context, json)

        PersistentLog.info(
            TAG,
            "api-access source=$ip verdict=$verdict derivedHaIp=${derivedHaIp ?: "none"} " +
                "deviceIp=${deviceIp ?: "?"} haHost=${haHost ?: "?"}"
        )
    }

    private fun load(context: Context): JSONObject = try {
        JSONObject(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SUMMARY, "{}") ?: "{}"
        )
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun save(context: Context, json: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUMMARY, json.toString()).apply()
    }

    private fun classify(ip: String, derivedHaIp: String?, deviceIp: String?): String = when {
        isLoopback(ip) -> "WOULD_ALLOW_loopback"
        // haUrl is a hostname / proxy / the unset default -> a gate can't derive an IP
        // from it, so it would block everything. The most important signal.
        derivedHaIp == null -> "DERIVED_UNAVAILABLE"
        ip == derivedHaIp -> "WOULD_ALLOW_match"
        isCgnat(ip) -> "WOULD_BLOCK_cgnat"                  // 100.64.0.0/10 (Tailscale / CGNAT mesh)
        isPrivate(ip) ->
            if (sameSubnet24(ip, deviceIp)) "WOULD_BLOCK_lanSameSubnet"
            else "WOULD_BLOCK_lanOtherSubnet"               // VLAN / routed / Docker bridge
        else -> "WOULD_BLOCK_public"                        // off-network / port-forward
    }

    private fun isLoopback(ip: String) =
        ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1"

    private fun isIpv4(host: String): Boolean =
        IPV4.matches(host) && host.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }

    private fun octets(ip: String): List<Int>? =
        ip.split(".").mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }

    private fun isPrivate(ip: String): Boolean {
        val o = octets(ip) ?: return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)   // link-local
    }

    private fun isCgnat(ip: String): Boolean {
        val o = octets(ip) ?: return false
        return o[0] == 100 && o[1] in 64..127
    }

    private fun sameSubnet24(a: String, b: String?): Boolean {
        if (b == null) return false
        val pa = a.substringBeforeLast('.', "")
        return pa.isNotEmpty() && pa == b.substringBeforeLast('.', "")
    }

    /** First non-loopback site-local IPv4 across interfaces (WiFi or Ethernet). */
    private fun primarySiteLocalIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }
}
