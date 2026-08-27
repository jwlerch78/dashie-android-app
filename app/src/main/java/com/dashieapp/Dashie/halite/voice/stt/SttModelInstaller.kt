package com.dashieapp.Dashie.halite.voice.stt

import android.content.Context
import android.util.Log
import com.dashieapp.Dashie.download.ModelInstaller
import com.dashieapp.Dashie.halite.diagnostics.PersistentLog
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/**
 * Installs an on-device STT model family: download → verify → extract → publish.
 *
 * ## The atomicity invariant, one level up
 *
 * [ModelInstaller] guarantees a verified *file*. A model family is a *directory*, and
 * [SttModelRegistry.isInstalled] — which the STT lane gates on — asks about the directory. So the
 * same rule is applied at directory granularity: extract into a scratch dir, and only **rename**
 * it into place once every member is present. A half-extracted family therefore never occupies
 * the real path, exactly as a half-downloaded file never occupies its own.
 *
 * ⚠️ This is why extraction does NOT stream straight into the destination. It would be simpler and
 * it would be wrong: an interrupted extract would leave a directory that passes a presence check
 * and fails at load — John's present-but-broken state, which is the whole thing this feature must
 * not reintroduce.
 *
 * ## Why bzip2 is here at all
 *
 * Upstream publishes these models only as `.tar.bz2` (verified: `.tar.gz` and `.zip` 404), and
 * Java has no built-in bzip2. Decompressing what upstream ships is what keeps the download path
 * free of any Dashie-controlled host — John's edition-independence constraint. See the
 * `commons-compress` note in `build.gradle.kts`.
 */
object SttModelInstaller {

    private const val TAG = "SttModelInstaller"

    sealed class Result {
        object Installed : Result()
        /**
         * Another install of this same family is already in flight; this call did nothing.
         *
         * Distinct from [Failed] because nothing is wrong — the caller should say "already
         * downloading", not "download failed", and must NOT treat it as a reason to retry.
         */
        object AlreadyRunning : Result()
        /**
         * The user cancelled via [SttInstallProgress.requestCancel]. Nothing is installed and all
         * working files are cleaned up. Distinct from [Failed] because nothing went WRONG — the
         * caller must not surface it as an error.
         */
        object Cancelled : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Families with an install in flight, so a second one cannot start.
     *
     * 🔴 Not defensive padding — two concurrent installs of one family would write the SAME
     * `<id>.tar.bz2` and `<id>.incoming` paths, so each would corrupt the other's archive and the
     * `finally` of whichever finished first would delete the other's working files mid-extract.
     * The realistic trigger is mundane: a user taps the row twice while a 135 MB fetch is running.
     * Guarding here rather than in the UI keeps it true for every caller, which is the seam rule.
     */
    private val inFlight = mutableSetOf<String>()

    /**
     * @param onProgress `(bytesSoFar, totalBytes)` during the DOWNLOAD only. Kept for callers
     *   that want a direct callback; UI surfaces should read [SttInstallProgress] instead, which
     *   covers BOTH phases (T measured download ~15 s vs extraction ~132 s on the Fire — the
     *   extraction is the phase a progress surface exists for).
     *
     * Cancellation: [SttInstallProgress.requestCancel] is polled once per chunk in both phases;
     * a cancel unwinds through the normal cleanup, returns [Result.Cancelled], and leaves no
     * working files.
     */
    fun install(
        context: Context,
        family: SttModelRegistry.Family,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Result {
        val dest = SttModelRegistry.dir(context, family)
        if (SttModelRegistry.isInstalled(context, family)) return Result.Installed

        synchronized(inFlight) {
            if (!inFlight.add(family.id)) {
                Log.i(TAG, "${family.id} install already in flight — this call does nothing")
                return Result.AlreadyRunning
            }
        }

        val work = File(dest.parentFile, "${family.id}.incoming")
        work.deleteRecursively()
        val archive = File(dest.parentFile, "${family.id}.tar.bz2")
        // 🔴 Delete a LEFTOVER archive before starting, not only in the `finally` (2026-08-04,
        // T cont.47). A process kill does not unwind `finally`, so a previous attempt can leave a
        // complete 111 MB archive here. It buys nothing on the retry — [ModelInstaller] always
        // downloads afresh into `<dest>.part` and never reuses an existing destination file — so
        // keeping it only means the retry holds the stale archive AND the new `.part` at the same
        // time: a ~222 MB peak on a wall tablet, for bytes that are about to be overwritten.
        archive.delete()

        val startedAt = System.currentTimeMillis()
        // The install lane was previously INVISIBLE in field diagnostics — a 106 MB download and
        // a 2-minute extract left zero PersistentLog lines (2026-08-18, live on the Fire).
        // These markers are the fix; keep them in lockstep with the phases.
        PersistentLog.info("STT", "install ${family.id} started (~${family.approxMb} MB download)")

        try {
            // 1. Download + verify the ARCHIVE. The digest is upstream's published artifact hash,
            //    the same one the build script pins — so a substituted archive fails here, before
            //    a single byte is extracted.
            var lastPublished = -1L
            val dl = ModelInstaller.install(
                url = family.url,
                destination = archive,
                expectedSha256 = family.archiveSha256,
                tag = "stt/${family.id}",
                onProgress = { soFar, total ->
                    // Publish at MB granularity: the raw callback fires per 64 KB chunk (~1,700×
                    // for the base model) and every publish posts to the main looper.
                    if (soFar / 1_000_000 != lastPublished) {
                        lastPublished = soFar / 1_000_000
                        SttInstallProgress.publish(SttInstallProgress.Snapshot(
                            family.id, family.label, SttInstallProgress.Phase.DOWNLOADING,
                            soFar, total, 0, family.members.size))
                    }
                    onProgress?.invoke(soFar, total)
                },
                shouldAbort = { SttInstallProgress.isCancelRequested(family.id) },
            )
            if (dl !is ModelInstaller.Result.Installed) {
                if (SttInstallProgress.isCancelRequested(family.id)) return cancelled(family)
                return fail("download failed for ${family.id}: ${(dl as ModelInstaller.Result.Failed).reason}")
            }
            val downloadMs = System.currentTimeMillis() - startedAt
            PersistentLog.info("STT", "install ${family.id}: download done " +
                "(${archive.length() / 1_000_000} MB in ${downloadMs / 1000}s) — extracting")

            // 2. Extract into a scratch dir — never into `dest`.
            work.mkdirs()
            if (!extractMembers(archive, family, work)) return cancelled(family)

            // 3. Every member must be present before this counts as a family at all.
            val missing = family.members.filter { File(work, it).length() <= 0 }
            if (missing.isNotEmpty()) {
                return fail("${family.id} archive did not contain: ${missing.joinToString()}")
            }

            // 4. Publish. The rename is what makes it installed.
            dest.deleteRecursively()
            if (!work.renameTo(dest)) return fail("could not publish ${family.id} into place")

            val totalS = (System.currentTimeMillis() - startedAt) / 1000
            Log.i(TAG, "installed ${family.id} (${family.members.size} files) at ${dest.path}")
            PersistentLog.info("STT", "install ${family.id}: PUBLISHED " +
                "(${family.members.size} files, ${totalS}s total)")
            return Result.Installed

        } catch (e: Exception) {
            return fail("${family.id} install failed: ${e.message}")
        } finally {
            // The archive is scratch — tens of megabytes we must not leave on a wall tablet.
            archive.delete()
            work.deleteRecursively()
            synchronized(inFlight) { inFlight.remove(family.id) }
            SttInstallProgress.clear(family.id)
        }
    }

    private fun cancelled(family: SttModelRegistry.Family): Result.Cancelled {
        // Info, not a DROP — the user asked for this; nothing went wrong.
        Log.i(TAG, "${family.id} install cancelled by user")
        PersistentLog.info("STT", "install ${family.id}: CANCELLED by user — working files removed")
        return Result.Cancelled
    }

    /**
     * Pull just [SttModelRegistry.Family.members] out of the tarball, flattened.
     *
     * Upstream nests everything under a versioned directory, and the recognizer wants the files
     * directly in the family dir — so entries are matched by BASENAME against the member list.
     * ⚠️ That also means a tar entry with a traversal path (`../…`) can never escape: the basename
     * is all that is used, and anything not on the member list is skipped entirely.
     *
     * Publishes INSTALLING progress per ~MB written (bytesTotal = -1: the extracted size is not
     * known until it exists, so surfaces render bytes + files, not a percent), and polls the
     * cancel flag per chunk — this phase is ~7× longer than the download on a wall tablet, so it
     * is where a cancel actually lands.
     *
     * @return false if cancelled (partial output stays in [into]; the caller's cleanup removes it).
     */
    private fun extractMembers(archive: File, family: SttModelRegistry.Family, into: File): Boolean {
        var written = 0L
        var lastPublished = -1L
        var filesDone = 0
        archive.inputStream().buffered().use { raw ->
            BZip2CompressorInputStream(raw).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = File(entry.name).name
                        if (name !in family.members) continue
                        File(into, name).outputStream().use { out ->
                            while (true) {
                                if (SttInstallProgress.isCancelRequested(family.id)) return false
                                val n = tar.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                                if (written / 1_000_000 != lastPublished) {
                                    lastPublished = written / 1_000_000
                                    SttInstallProgress.publish(SttInstallProgress.Snapshot(
                                        family.id, family.label,
                                        SttInstallProgress.Phase.INSTALLING,
                                        written, family.extractedBytes,
                                        filesDone, family.members.size))
                                }
                            }
                        }
                        filesDone++
                    }
                }
            }
        }
        return true
    }

    /**
     * Delete working files left behind by an install that DIED — archives and `.incoming` dirs.
     *
     * 🔴 Why a sweep and not just the `finally` (2026-08-04, T cont.47 on the Fire): cleanup lives
     * in a `finally`, and a KILL does not unwind. Force-stopping inside the extraction window left
     * `moonshine-base.tar.bz2` (111,266,225 bytes) plus `moonshine-base.incoming/` orphaned — and
     * that window is not small: T measured ~15 s of download against ~132 s of extraction, so a
     * low-memory kill on a wall tablet lands there far more often than in the transfer.
     *
     * ⚠️ The user this exists for is the one who does NOT retry. A retry already recovers with
     * zero residue (the next install deletes both paths first), so the residue is invisible
     * precisely when nothing ever comes back to clear it: ~106 MB of app-private storage, held
     * forever, with no surface anywhere that mentions it.
     *
     * Nothing here can disturb a live install: a family with an install in flight is skipped by
     * name. That matters because the caller is provider registration, which re-runs on a voice
     * config change and can therefore fire while a download is genuinely running.
     *
     * @return bytes reclaimed.
     */
    fun sweepOrphans(context: Context): Long {
        val root = SttModelRegistry.root(context)
        if (!root.isDirectory) return 0L
        var reclaimed = 0L
        for (child in root.listFiles().orEmpty()) {
            val suffix = when {
                child.name.endsWith(".tar.bz2") -> ".tar.bz2"
                child.name.endsWith(".incoming") -> ".incoming"
                // A SUPERSEDED INSTALL: a real family directory whose members no longer satisfy
                // the registry. Added 2026-08-23 with the moonshine-base .ort → .onnx move, which
                // is the first time this could happen — every existing base install has `.ort`
                // members the family no longer names, so `isInstalled` reports false, the loader
                // will never open it, and 141 MB sits in filesDir unusable and unmentioned.
                //
                // 📌 It is the SAME user this sweep's KDoc was already written for: the one who
                // does not come back. A user who re-downloads is fine either way (step 4 of
                // install() deletes `dest` wholesale before publishing), so the residue is
                // invisible precisely when nothing will ever clear it.
                //
                // ⚠️ Scoped to KNOWN family ids on purpose — "not installed" is only a safe
                // deletion signal for a directory the registry can actually make a claim about.
                child.isDirectory && SttModelRegistry.byId(child.name)
                    ?.let { !SttModelRegistry.isInstalled(context, it) } == true -> ""
                else -> continue
            }
            val familyId = child.name.removeSuffix(suffix)
            val running = synchronized(inFlight) { familyId in inFlight }
            if (running) {
                Log.i(TAG, "sweep: leaving ${child.name} — $familyId install is in flight")
                continue
            }
            val size = child.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (child.deleteRecursively()) {
                reclaimed += size
                // Loud on purpose: silently reclaiming 100+ MB reads as a disk-usage mystery
                // later. This line is the only place the reclaimed directory is ever named — and
                // it says WHICH of the two causes it was, because "killed mid-install" and
                // "superseded by a registry change" want completely different follow-up.
                val why =
                    if (suffix.isEmpty())
                        "its members no longer match the registry (superseded install — the " +
                            "family was unusable and would never have loaded)"
                    else "orphaned by an install that was killed before its cleanup could run"
                Log.w(TAG, "sweep: reclaimed ${child.name} ($size bytes) — $why")
            } else {
                Log.w(TAG, "DROP: sweep could not delete ${child.path} ($size bytes still held)")
            }
        }
        return reclaimed
    }

    /** Remove an installed family — the user reclaiming ~42–135 MB. */
    fun uninstall(context: Context, family: SttModelRegistry.Family): Boolean =
        SttModelRegistry.dir(context, family).deleteRecursively()

    private fun fail(reason: String): Result.Failed {
        Log.w(TAG, "DROP: $reason")
        // Field-visible too: a failed install is exactly what a diagnostics report needs to name.
        PersistentLog.warn("STT", "DROP: $reason")
        return Result.Failed(reason)
    }
}
