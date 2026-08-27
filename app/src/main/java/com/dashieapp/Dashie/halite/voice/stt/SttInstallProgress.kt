package com.dashieapp.Dashie.halite.voice.stt

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide observable state for the STT model install that is currently in flight.
 *
 * ## Why this exists as its own seam
 *
 * An install outlives every UI that can show it: the download dialog can be hidden, the settings
 * activity destroyed, the Control Center opened minutes later — and the John-measured reality is
 * that EXTRACTION (~132 s on the Fire) dwarfs the download (~15 s), so "the dialog was open for
 * the download" covers the shorter phase. Every surface that wants to render progress (the
 * download dialog, the Control Center strip, anything later) reads THIS, and the installer writes
 * THIS — one publisher, N readers, no per-UI plumbing. Second copies of "what is installing" are
 * exactly what the seam rule forbids.
 *
 * ## Threading
 *
 * The installer publishes from its worker thread; listeners are always invoked on the MAIN
 * thread, so UI code can touch views directly. [snapshot] may be read from any thread.
 *
 * ## Cancel
 *
 * [requestCancel] only raises a flag; the installer polls it between chunks and unwinds through
 * its own cleanup (`finally` + scratch-dir discipline), so a cancel can never leave a
 * half-installed family. There is nothing here that kills a thread.
 */
object SttInstallProgress {

    enum class Phase { DOWNLOADING, INSTALLING }

    /**
     * @param bytesTotal -1 when unknown (a server that sent no Content-Length). For INSTALLING
     *   this is `Family.extractedBytes` — an exact constant measured from the sha-pinned archive,
     *   so the phase renders a real "X of Y MB", not an estimate.
     */
    data class Snapshot(
        val familyId: String,
        val label: String,
        val phase: Phase,
        val bytesSoFar: Long,
        val bytesTotal: Long,
        val filesDone: Int,
        val filesTotal: Int,
    )

    fun interface Listener {
        /** null = no install in flight (finished, failed, or cancelled). Main thread. */
        fun onInstallProgress(snapshot: Snapshot?)
    }

    @Volatile
    private var current: Snapshot? = null

    @Volatile
    private var cancelRequestedFor: String? = null

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())

    /** The install in flight right now, or null. Any thread. */
    fun snapshot(): Snapshot? = current

    /**
     * One-line human rendering, shared by every surface (download dialog, Control Center strip)
     * so the two never phrase the same install differently.
     */
    fun render(snap: Snapshot): String = when (snap.phase) {
        Phase.DOWNLOADING -> {
            val mb = snap.bytesSoFar / 1_000_000
            if (snap.bytesTotal > 0) {
                val pct = (snap.bytesSoFar * 100 / snap.bytesTotal).toInt()
                "$pct%  ·  $mb of ${snap.bytesTotal / 1_000_000} MB"
            } else "Downloading…  ·  $mb MB"
        }
        // The total is Family.extractedBytes — exact, because the archive is sha-pinned — so
        // this phase gets a real target ("10 of 44 MB"), John's 2026-08-18 follow-up ask. The
        // no-total fallback stays for safety, not because any current family lacks the constant.
        Phase.INSTALLING -> {
            val mb = snap.bytesSoFar / 1_000_000
            if (snap.bytesTotal > 0) {
                val pct = (snap.bytesSoFar * 100 / snap.bytesTotal).toInt()
                "Installing…  ·  $pct%  ·  $mb of ${snap.bytesTotal / 1_000_000} MB"
            } else {
                "Installing…  ·  file ${snap.filesDone + 1} of ${snap.filesTotal}  ·  $mb MB"
            }
        }
    }

    /**
     * Register [listener] and immediately deliver the current state to it (on main), so a UI
     * that opens mid-install renders without waiting for the next chunk.
     */
    fun addListener(listener: Listener) {
        listeners.add(listener)
        val now = current
        main.post { listener.onInstallProgress(now) }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Ask the in-flight install of [familyId] to stop. No-op if nothing matches. */
    fun requestCancel(familyId: String) {
        cancelRequestedFor = familyId
    }

    // ── Installer side ───────────────────────────────────────────────────

    /** Polled by the installer between chunks. */
    internal fun isCancelRequested(familyId: String): Boolean = cancelRequestedFor == familyId

    /** The installer beginning a phase or reporting progress. Any thread. */
    internal fun publish(snapshot: Snapshot) {
        current = snapshot
        main.post { for (l in listeners) l.onInstallProgress(snapshot) }
    }

    /** The installer is done with this family — success, failure, or cancel. */
    internal fun clear(familyId: String) {
        if (current?.familyId == familyId) current = null
        if (cancelRequestedFor == familyId) cancelRequestedFor = null
        main.post { for (l in listeners) l.onInstallProgress(current) }
    }
}
