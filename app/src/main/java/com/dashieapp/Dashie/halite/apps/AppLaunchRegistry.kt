package com.dashieapp.Dashie.halite.apps

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Resolves a spoken app name (e.g. "netflix", "youtube tv", "prime") to an
 * installed Android package, for the voice "open <app>" feature.
 *
 * Two tiers:
 *  1. Curated alias map ([KNOWN_APPS]) — high-confidence resolution for the
 *     common streaming/music apps and their messy spoken variants. Static, so
 *     [matchKnownApp] can run inside the (context-free) intent classifier.
 *  2. Dynamic fallback ([resolve]) — matches the spoken name against the
 *     device's actual installed launchable apps, so "open Plex" / "open Hulu"
 *     work even when not in the curated list, as long as they're installed.
 *
 * The curated list is NOT a technical limit — any installed launchable app can
 * be opened. It exists to guarantee correct resolution + disambiguation
 * (e.g. "youtube tv" vs "youtube") for the popular apps.
 */
object AppLaunchRegistry {
    private const val TAG = "AppLaunchRegistry"

    /** A curated known app: display [label], its [packageName], and spoken [aliases]. */
    data class KnownApp(val label: String, val packageName: String, val aliases: List<String>)

    /** Result of resolving a spoken name. [installed] is false when we know the app but it's absent. */
    data class ResolvedApp(val label: String, val packageName: String, val installed: Boolean)

    // Longest aliases first is enforced in [aliasIndex] so "youtube tv" wins over "youtube".
    val KNOWN_APPS: List<KnownApp> = listOf(
        KnownApp("YouTube TV", "com.google.android.apps.youtube.unplugged",
            listOf("youtube tv", "youtube television", "yt tv")),
        KnownApp("YouTube", "com.google.android.youtube", listOf("youtube")),
        KnownApp("Netflix", "com.netflix.mediaclient", listOf("netflix")),
        KnownApp("Prime Video", "com.amazon.avod.thirdpartyclient",
            listOf("amazon prime video", "prime video", "amazon prime", "prime")),
        KnownApp("Spotify", "com.spotify.music", listOf("spotify")),
        KnownApp("Disney+", "com.disney.disneyplus", listOf("disney plus", "disney+", "disney")),
        KnownApp("Hulu", "com.hulu.plus", listOf("hulu")),
        KnownApp("Max", "com.wbd.stream", listOf("hbo max", "hbo", "max")),
        KnownApp("Peacock", "com.peacocktv.peacockandroid", listOf("peacock")),
        KnownApp("Paramount+", "com.cbs.app", listOf("paramount plus", "paramount+", "paramount")),
        KnownApp("Tubi", "com.tubitv", listOf("tubi")),
        KnownApp("Pluto TV", "tv.pluto.android", listOf("pluto tv", "pluto")),
        KnownApp("Sling", "com.sling", listOf("sling")),
        KnownApp("Twitch", "tv.twitch.android.app", listOf("twitch")),
        KnownApp("Pandora", "com.pandora.android", listOf("pandora")),
        KnownApp("Plex", "com.plexapp.android", listOf("plex")),
        KnownApp("Jellyfin", "org.jellyfin.mobile", listOf("jellyfin")),
        KnownApp("Kodi", "org.xbmc.kodi", listOf("kodi")),
    )

    // Flattened (alias -> app), sorted by alias length desc so multi-word aliases match first.
    private val aliasIndex: List<Pair<String, KnownApp>> = KNOWN_APPS
        .flatMap { app -> app.aliases.map { it to app } }
        .sortedByDescending { it.first.length }

    /** All curated alias tokens — used by the classifier to detect an app-launch phrase. */
    val KNOWN_ALIASES: List<String> = aliasIndex.map { it.first }

    /**
     * Match a (verb-stripped, normalized) spoken target to a curated app.
     * Context-free — safe to call from KotlinIntentPatterns. Returns null if
     * the target doesn't clearly name a known app.
     */
    fun matchKnownApp(target: String): KnownApp? {
        val t = target.trim().lowercase()
        if (t.isEmpty()) return null
        // Prefer an exact alias hit, then a whole-word containment (longest alias first).
        aliasIndex.firstOrNull { it.first == t }?.let { return it.second }
        return aliasIndex.firstOrNull { (alias, _) ->
            t == alias || t.startsWith("$alias ") || t.endsWith(" $alias") || t.contains(" $alias ")
        }?.second
    }

    /** True if [packageName] has a launchable entry on this device. */
    fun isInstalled(context: Context, packageName: String): Boolean =
        packageName.isNotEmpty() && context.packageManager.getLaunchIntentForPackage(packageName) != null

    /**
     * Full resolution for the controller (has a Context). Curated match first
     * (verifying it's actually installed), then a dynamic match against the
     * device's installed launchable apps by label. Returns null when the spoken
     * name doesn't map to anything we can identify.
     */
    fun resolve(context: Context, spokenTarget: String): ResolvedApp? {
        val target = spokenTarget.trim().lowercase()
        if (target.isEmpty()) return null

        matchKnownApp(target)?.let { known ->
            return ResolvedApp(known.label, known.packageName, isInstalled(context, known.packageName))
        }

        // Dynamic: match against installed launchable app labels.
        val installed = queryInstalledApps(context)
        // Exact label match, else label-contains-target, else target-contains-label (>=3 chars).
        val hit = installed.firstOrNull { it.second.equals(target, ignoreCase = true) }
            ?: installed.firstOrNull { it.second.contains(target, ignoreCase = true) }
            ?: installed.firstOrNull { target.length >= 3 && target.contains(it.second, ignoreCase = true) }
            ?: return null
        return ResolvedApp(hit.second, hit.first, true)
    }

    /** (packageName, label) for every launchable app, excluding Dashie itself. */
    private fun queryInstalledApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val out = LinkedHashMap<String, String>()
        listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).forEach { category ->
            val intent = Intent(Intent.ACTION_MAIN, null).addCategory(category)
            pm.queryIntentActivities(intent, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg != context.packageName) out.putIfAbsent(pkg, ri.loadLabel(pm).toString())
            }
        }
        Log.d(TAG, "queryInstalledApps: ${out.size} launchable apps")
        return out.entries.map { it.key to it.value }
    }
}
