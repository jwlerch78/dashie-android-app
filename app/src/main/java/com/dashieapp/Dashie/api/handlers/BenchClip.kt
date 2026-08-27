package com.dashieapp.Dashie.api.handlers

import android.content.Context
import java.io.File

/**
 * The ONE place a bench corpus clip is named and resolved.
 *
 * Both bench endpoints (`?cmd=sttBench`, `?cmd=haAssistBench`) take a bare FILENAME and resolve
 * it here. That is deliberate on two counts:
 *
 *  • **Scoped storage.** Clips live in the app's OWN external files dir because Android 10+
 *    denies reads of an arbitrary /sdcard path — the first cut used /sdcard/stt-bench and got
 *    EACCES on every clip.
 *  • **No cross-boundary path contract.** The harness sends a basename and this resolves it, so
 *    there is no device path for the two sides to disagree about, and a traversal attempt can't
 *    escape a directory the client never names.
 *
 * It exists as a shared object rather than a second copy because the second bench handler would
 * otherwise have hand-mirrored the first (`lint:discovery` duly flagged it, 2026-07-30). The
 * standing seam rule says share the first before documenting a mirror, and eliminating the
 * duplicate is the top tier of that rule — a resolver that drifted between the two endpoints
 * would send one of them looking in a directory the harness never pushes to.
 */
internal object BenchClip {

    private const val BENCH_SUBDIR = "stt-bench"

    /**
     * The corpus directory — the one place it is named.
     *
     * 🔴 **Two candidates, and Fire OS is why.** `getExternalFilesDir` lands under
     * `/sdcard/Android/data/<pkg>/`, which **Fire OS blocks for `adb push`** — so on a Fire the
     * harness cannot put a clip where this would look, and every bench request answered
     * "clip not found" for a corpus that had been pushed successfully to the device (, N6).
     * `getExternalMediaDirs()` lands under `/sdcard/Android/media/<pkg>/`, which S measured
     * writable there.
     *
     * Resolution order is **whichever already holds clips**, then the media dir if it exists,
     * then the files dir. Preferring the populated directory means a device that has been pushed
     * to either way just works, and nobody has to know which OEM they are on.
     *
     * ⚠️ Both are app-scoped. This is not a return to `/sdcard/stt-bench`, which got EACCES on
     * Android 10+ and is the reason the files dir was chosen in the first place.
     */
    fun dir(context: Context): File {
        val candidates = candidates(context)
        // A directory with clips in it beats an empty one, whatever the order.
        candidates.firstOrNull { (it.listFiles()?.isNotEmpty() == true) }?.let { return it }
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    /** Every place a clip may legitimately live, most-likely-writable first. */
    private fun candidates(context: Context): List<File> = buildList {
        // Media dir first: it is the one that works on BOTH Fire OS and ordinary Android.
        context.externalMediaDirs?.filterNotNull()?.firstOrNull()
            ?.let { add(File(it, BENCH_SUBDIR)) }
        context.getExternalFilesDir(null)?.let { add(File(it, BENCH_SUBDIR)) }
    }.ifEmpty { listOf(File(context.filesDir, BENCH_SUBDIR)) }

    sealed class Result {
        /** [canonicalPath] is safe to hand to a runner. */
        data class Ok(val canonicalPath: String) : Result()
        /** [message] is caller-facing; [logIt] is true when it deserves a DROP: line. */
        data class Err(val message: String, val logIt: Boolean = false) : Result()
    }

    /**
     * Resolve [requested] (a basename; any directory part is discarded) against [dir].
     * Never throws — a bad path is an [Result.Err], not an exception at the HTTP layer.
     */
    fun resolve(context: Context, requested: String): Result {
        val name = File(requested).name          // basename only — no traversal
        // Look in EVERY candidate, not just the preferred one: a clip pushed to the files dir on
        // a device where the media dir happens to exist must still be found.
        for (d in candidates(context)) {
            val target = File(d, name)
            if (!target.exists()) continue
            return try {
                Result.Ok(target.canonicalPath)
            } catch (e: Exception) {
                Result.Err("Bad path: ${e.message}")
            }
        }
        // 🔴 Name EVERY place that was searched. The old message named one directory, and on Fire
        // OS that was the one the harness is not allowed to write to — so the error sent people
        // to push again into a path that could never work (, N6).
        val searched = candidates(context).joinToString(" or ") { it.absolutePath }
        return Result.Err("clip not found: $name (push to $searched)", logIt = true)
    }
}
