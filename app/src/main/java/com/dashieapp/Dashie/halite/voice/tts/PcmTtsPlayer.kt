package com.dashieapp.Dashie.halite.voice.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Plays a PCM16 mono clip via AudioTrack, teeing the frames the speaker has ACTUALLY
 * emitted to an echo-cancellation reference sink (WS-A.2 cascade AEC). Replaces
 * MediaPlayer for ElevenLabsTtsClient's PCM responses — MediaPlayer exposes no PCM for
 * AEC3's processReverse.
 *
 * The audio source is an [InputStream] read progressively ([playStream]) so playback can
 * begin as bytes arrive off the network (~250 ms to first audio) instead of waiting for
 * full synthesis. [play] wraps a complete ByteArray in a stream so both share one path.
 *
 * Same audio attributes as the MediaPlayer path (USAGE_ASSISTANT / SPEECH → media volume
 * stream), and the same completion contract: onComplete fires exactly once at REAL
 * playback end — when the playback head drains all written frames — not at read/write end.
 * [stop] never fires onComplete (mirrors MediaPlayer release on supersede; the client's
 * generation guards own that flow).
 *
 * Two internal threads, mirroring RealtimeAudioIo's proven split:
 *  • writer — reads the source in ~100 ms chunks and blocking-writes AudioTrack (the track
 *    buffer paces both read and write via backpressure), appending each chunk to an
 *    accumulator the pacer slices for the AEC tee,
 *  • pacer  — 10 ms tick: feeds [onPlayedPcm] up to playbackHeadPosition (so the AEC
 *    reference tracks the speaker, not the ~0.5 s track buffer / the network arrival),
 *    detects completion (stream ended AND head drained).
 */
class PcmTtsPlayer(
    private val sampleRate: Int,
    private val onPlayedPcm: ((ByteArray) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "PcmTtsPlayer"
        private const val PACER_TICK_MS = 10L
    }

    @Volatile private var stopped = false
    private var track: AudioTrack? = null
    private var writerThread: Thread? = null
    private var pacerThread: Thread? = null
    private var source: InputStream? = null

    // Written-and-emittable PCM, grown by the writer and sliced by the pacer for the AEC
    // tee. Guarded by [accLock]; [writtenBytes] mirrors the committed length for the pacer
    // to read the count without contending on the lock. [streamEnded] set once the source
    // is drained so the pacer knows no more frames will arrive.
    private val accLock = Any()
    private var acc = ByteArray(32 * 1024)
    private var accLen = 0
    @Volatile private var writtenBytes = 0L
    @Volatile private var streamEnded = false

    /**
     * Start playback of a complete PCM clip. Convenience wrapper over [playStream].
     */
    fun play(pcm: ByteArray, onStart: () -> Unit, onComplete: () -> Unit): Boolean {
        if (pcm.isEmpty()) return false
        return playStream(ByteArrayInputStream(pcm), onStart, onComplete)
    }

    /**
     * Start progressive playback from [src]. Returns false (nothing scheduled, no
     * callbacks, source left open for the caller to close) if the AudioTrack can't
     * initialize. [onStart] fires from the writer thread just before the first write;
     * [onComplete] fires once from the pacer thread at playback end. The player OWNS
     * [src] once started: it is read to EOF and closed by the writer (or by [stop]).
     */
    fun playStream(src: InputStream, onStart: () -> Unit, onComplete: () -> Unit): Boolean {
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                // USAGE_MEDIA (→ STREAM_MUSIC, the media-volume rocker) — NOT USAGE_ASSISTANT.
                // STREAM_ASSISTANT is a separate stream the user can't adjust and defaults to
                // 0 on some devices (verified silent on Samsung SM-X200), so USAGE_ASSISTANT
                // playback is inaudible there. Matches the Live path + the AEC config table.
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, sampleRate)) // ~0.5 s
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack build failed: ${e.message}"); return false
        }
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioTrack not initialized"); runCatching { t.release() }; return false
        }
        track = t
        source = src
        t.play()

        val chunkBytes = sampleRate / 5   // ~100 ms of PCM16 mono (frames/10 * 2 bytes)
        writerThread = thread(name = "tts-pcm-writer") {
            // +1 slot so an odd trailing byte can be carried into the next read. AudioTrack
            // must only ever get WHOLE PCM16 frames — a network read can split a sample
            // across chunks (returns an odd count), and writing an odd byte count leaves the
            // frame partially accepted, which the old loop mistook for a write failure and
            // bailed on → intermittently cut-off replies.
            val buf = ByteArray(chunkBytes + 1)
            var carry = 0                 // 0 or 1 leftover byte parked at buf[0]
            var startedFired = false
            try {
                while (!stopped) {
                    val r = try { src.read(buf, carry, chunkBytes) } catch (_: Exception) { -1 }
                    if (r < 0) break                  // EOF or cancelled read
                    if (r == 0) continue
                    val total = carry + r
                    val writeLen = total - (total % 2)  // whole frames only
                    if (writeLen > 0) {
                        if (!startedFired) { startedFired = true; try { onStart() } catch (_: Exception) {} }
                        var off = 0
                        while (off < writeLen && !stopped) {
                            val n = try { t.write(buf, off, writeLen - off) } catch (_: Exception) { -1 }
                            if (n <= 0) break         // stopped/released mid-write
                            off += n
                        }
                        if (off > 0) appendAcc(buf, off)
                        if (off < writeLen) break     // write failed or stopped mid-chunk
                    }
                    // Carry the trailing odd byte (if any) to the front of the next read.
                    if (total % 2 == 1) { buf[0] = buf[writeLen]; carry = 1 } else { carry = 0 }
                }
            } finally {
                streamEnded = true
                runCatching { src.close() }
            }
        }

        pacerThread = thread(name = "tts-pcm-pacer") {
            var fedBytes = 0L
            var lastHead = -1L
            var stallSinceMs = 0L
            val tee = onPlayedPcm
            while (!stopped) {
                val head = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Exception) { break }
                // Feed the reference exactly up to what the speaker has emitted (never past
                // what's been written), so the AEC reference tracks the speaker.
                if (tee != null) {
                    val target = minOf(head * 2, writtenBytes)
                    if (target > fedBytes) {
                        try { tee(sliceAcc(fedBytes.toInt(), target.toInt())) } catch (_: Exception) {}
                        fedBytes = target
                    }
                }
                // Real playback end: the source is drained AND the head reached the last frame.
                if (streamEnded && head * 2 >= writtenBytes) break
                // Stall guard: some devices park the head a hair short of the total when the
                // stream drains. No head movement for 1.5 s after the source ended ⇒ done.
                if (head == lastHead) {
                    val now = SystemClock.uptimeMillis()
                    if (stallSinceMs == 0L) stallSinceMs = now
                    else if (now - stallSinceMs > 1500 && streamEnded) break
                } else {
                    lastHead = head; stallSinceMs = 0L
                }
                try { Thread.sleep(PACER_TICK_MS) } catch (_: InterruptedException) { break }
            }
            val completed = !stopped
            releaseTrack()
            if (completed) try { onComplete() } catch (_: Exception) {}
        }
        return true
    }

    /** Cut playback now (barge-in / supersede). onComplete will NOT fire. */
    fun stop() {
        stopped = true
        runCatching { source?.close() }   // unblock the writer's read()
        try { writerThread?.interrupt() } catch (_: Exception) {}
        try { pacerThread?.interrupt() } catch (_: Exception) {}
        releaseTrack()
    }

    /** Append `len` written bytes for the pacer's AEC tee (amortized-doubling growth). */
    private fun appendAcc(data: ByteArray, len: Int) {
        synchronized(accLock) {
            if (accLen + len > acc.size) {
                var cap = acc.size * 2
                while (cap < accLen + len) cap *= 2
                acc = acc.copyOf(cap)
            }
            System.arraycopy(data, 0, acc, accLen, len)
            accLen += len
            writtenBytes = accLen.toLong()
        }
    }

    /** Copy the emitted range [from, to) for the tee (small per-tick delta, not the whole clip). */
    private fun sliceAcc(from: Int, to: Int): ByteArray =
        synchronized(accLock) { acc.copyOfRange(from, to) }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        try { t.pause(); t.flush() } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
    }
}
