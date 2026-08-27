package com.dashieapp.Dashie.download

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The one place a downloaded model becomes an installed model.
 *
 * ## Why this is shared rather than copied (O's v1 seam ruling, 2026-08-04)
 *
 * On-device STT needs an OTA model download, and [com.dashieapp.Dashie.wakeword.models
 * .WakeWordModelManager] already had one. Writing a second copy of *download → verify → install*
 * is the seam rule's exact target, and the rule bites **at authorship** — a follow-up "unify
 * later" is how two downloaders drift into disagreeing about what "installed" means.
 *
 * ## 🔴 The invariant this type exists to enforce: a partial file is NEVER loadable
 *
 * Every download lands in `<dest>.part`, is verified there, and only then is **renamed** into
 * place. So the destination path's existence IS the "installed and verified" signal, by
 * construction rather than by a check a caller could forget. That matters because the STT lane
 * gates on presence: if a half-written model could occupy the real path, the picker would offer a
 * broken engine — John's *present-but-broken* state, which his standing principle says must not
 * exist. The option does not exist, or it works.
 *
 * ## ⚠️ [expectedSha256] is nullable, and a null is LOUD
 *
 * Measured 2026-08-04 before adopting: `WakeWordModelManager` had **no checksum at all** — its
 * entire integrity check was `tempFile.length() < 1000` — and its manifest has no digest field to
 * supply one (confirmed on the live wire: `{"manifest_version":8,…}`, no hash). So requiring a
 * digest here would have blocked the wake-word path on a *server-side* manifest change.
 *
 * Rather than weaken the contract silently or block on someone else's schema, a caller that
 * supplies no digest gets a **loud `DROP:` naming itself**. The STT caller always supplies one.
 * The wake-word gap becomes *visible* instead of inherited — it converts an existing unmeasured
 * hole into a logged one, and the manifest can gain a `sha256` later with no change here.
 */
object ModelInstaller {

    private const val TAG = "ModelInstaller"

    /**
     * Log sink, injectable so this stays unit-testable without a device.
     *
     * `android.util.Log` throws "not mocked" on the plain-JVM test path (`isReturnDefaultValues`
     * is not set), and the invariant here — *an unverified file never reaches the destination* —
     * is exactly the kind that must be pinned by tests rather than by a device run.
     *
     * ⚠️ Defaults to the REAL logger, deliberately. `LeaseMarkers` took the same shape with a
     * **no-op** default and production silently emitted nothing until a device run caught it —
     * its own KDoc records that miss. Defaulting to real means a caller who wires nothing still
     * gets the loud `DROP:` this class promises.
     */
    @JvmStatic
    var logger: (Char, String) -> Unit = { level, msg ->
        if (level == 'W') Log.w(TAG, msg) else Log.i(TAG, msg)
    }

    /** Bytes below which a "download" is certainly an error page, not a model. */
    private const val MIN_PLAUSIBLE_BYTES = 1000L

    sealed class Result {
        /** Verified and renamed into place. */
        object Installed : Result()
        /**
         * Nothing was installed and the destination is untouched.
         * @param reason short, greppable, already logged as a `DROP:`.
         */
        data class Failed(val reason: String) : Result()
    }

    private val http = OkHttpClient.Builder().build()

    /**
     * Download [url] and install it at [destination], atomically.
     *
     * @param expectedSha256 lowercase hex digest. **Null means unverified** and logs a loud DROP —
     *   see the class KDoc; it is tolerated only because the wake-word manifest cannot supply one.
     * @param tag caller identity, so a failure names who was downloading what.
     * @param onProgress optional `(bytesSoFar, totalBytes)`; totalBytes is -1 when unknown.
     * @param shouldAbort polled once per 64 KB chunk; returning true abandons the transfer as a
     *   [Result.Failed] ("aborted by caller") with the `.part` file deleted. This is how a user
     *   cancel reaches a transfer already in flight — nothing here interrupts a thread.
     */
    fun install(
        url: String,
        destination: File,
        expectedSha256: String?,
        tag: String,
        onProgress: ((Long, Long) -> Unit)? = null,
        shouldAbort: (() -> Boolean)? = null,
    ): Result {
        if (url.isBlank()) return fail(tag, "empty url — nothing to download")

        if (expectedSha256 == null) {
            // [unexpected] on purpose: every NEW caller should pin a digest. This fires for the
            // wake-word path until its manifest carries one, which is the point — the gap is
            // visible in the log rather than invisible in the code.
            logger('W', "DROP: [unexpected] $tag supplied NO sha256 — installing UNVERIFIED from " +
                "$url. A substituted or truncated file would be accepted. Pin a digest.")
        }

        destination.parentFile?.mkdirs()
        val part = File(destination.parentFile, destination.name + ".part")
        part.delete()

        try {
            val response = http.newCall(Request.Builder().url(url).build()).execute()
            response.use { r ->
                if (!r.isSuccessful) return fail(tag, "HTTP ${r.code} from $url")
                val body = r.body ?: return fail(tag, "empty body from $url")
                val total = body.contentLength()
                var soFar = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (shouldAbort?.invoke() == true) {
                                part.delete()
                                return fail(tag, "aborted by caller after $soFar bytes")
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            soFar += n
                            onProgress?.invoke(soFar, total)
                        }
                    }
                }
            }

            if (part.length() < MIN_PLAUSIBLE_BYTES) {
                part.delete()
                return fail(tag, "downloaded only ${part.length()} bytes — an error page, not a model")
            }

            if (expectedSha256 != null) {
                val got = sha256(part)
                if (!got.equals(expectedSha256, ignoreCase = true)) {
                    part.delete()
                    // 🔴 NOT a retryable network error. A digest mismatch means the bytes are not
                    // the artefact we pinned — a truncation, a captive portal, or a substitution.
                    // Retrying would just re-fetch the same wrong file, so it says so.
                    return fail(tag, "sha256 MISMATCH — expected $expectedSha256 got $got. " +
                        "This is a wrong/substituted file, not a transient failure; not retrying")
                }
            }

            // Atomic-ish: the rename is what makes it installed. Nothing before this line is
            // reachable by a reader of [destination].
            destination.delete()
            if (!part.renameTo(destination)) {
                part.delete()
                return fail(tag, "could not rename .part into place at ${destination.name}")
            }

            logger('I', "$tag installed ${destination.name} (${destination.length()} bytes)" +
                if (expectedSha256 == null) " [UNVERIFIED]" else " [sha256 ok]")
            return Result.Installed

        } catch (e: Exception) {
            part.delete()
            return fail(tag, "download failed: ${e.message}")
        }
    }

    private fun fail(tag: String, reason: String): Result.Failed {
        logger('W', "DROP: $tag — $reason")
        return Result.Failed(reason)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
