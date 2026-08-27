package com.dashieapp.Dashie.util

/**
 * Classifies hostnames as "local-network" so callers can decide whether to
 * relax HTTPS cert validation. Used by the WebView SSL error handler and by
 * OkHttp clients that talk to user-configured HA URLs.
 *
 * Matches:
 *   - IPv4 loopback (127.0.0.0/8) and RFC 1918 private ranges (10/8, 172.16/12,
 *     192.168/16) plus link-local (169.254/16)
 *   - IPv6 loopback (::1), link-local (fe80::/10), unique-local (fc00::/7)
 *   - `localhost`, `*.local` (mDNS), `*.lan`, `*.home`
 *
 * Rationale: a self-signed cert on a LAN address is the common HA setup. An
 * attacker who can MITM your LAN can do worse (sniff DNS, hit plain HTTP),
 * so strict cert validation there is high friction with little real benefit.
 * Public hosts still get full validation elsewhere.
 */
object LocalNetworkHostnames {
    private val LAN_REGEX = Regex(
        // IPv4 private + loopback + link-local
        "^(?:127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(?:1[6-9]|2[0-9]|3[01])\\.)" +
        // IPv6 loopback + link-local + unique-local
        "|^(?:::1|fe80:|fc[0-9a-f]{2}:|fd[0-9a-f]{2}:)" +
        // mDNS / common LAN TLDs
        "|^localhost$|\\.local$|\\.lan$|\\.home$",
        RegexOption.IGNORE_CASE
    )

    fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        // Strip IPv6 brackets if present (`[::1]` → `::1`)
        val stripped = host.trim().removePrefix("[").removeSuffix("]")
        return LAN_REGEX.containsMatchIn(stripped)
    }

    /**
     * Hosts the user has explicitly configured (their HA URLs). Trusted by
     * definition even when they aren't LAN-pattern hosts — e.g. a custom domain,
     * *.fritz.box, or a VPN/Tailscale address. Wired once at startup to read live
     * from preferences so config changes are picked up. Defaults to none.
     */
    @Volatile
    var configuredHostProvider: () -> Set<String> = { emptySet() }

    /** Lowercased hosts parsed out of URL strings; blanks/unparseable skipped. */
    fun hostsFromUrls(vararg urls: String?): Set<String> =
        urls.asSequence()
            .filterNot { it.isNullOrBlank() }
            .mapNotNull { runCatching { android.net.Uri.parse(it).host?.lowercase() }.getOrNull() }
            .filter { it.isNotBlank() }
            .toSet()

    /**
     * True if `host` is a LAN-pattern host OR a host the user explicitly configured.
     * Use this (not isLocalHost) for relaxing self-signed cert validation, so
     * user-configured non-LAN dashboards (custom domain / VPN) are also trusted.
     */
    fun isTrustedHost(host: String?): Boolean {
        if (isLocalHost(host)) return true
        if (host.isNullOrBlank()) return false
        val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        return h in configuredHostProvider()
    }
}
