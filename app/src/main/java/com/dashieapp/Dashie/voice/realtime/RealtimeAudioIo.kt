package com.dashieapp.Dashie.voice.realtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.dashieapp.Dashie.halite.preferences.VoicePreferences
import kotlin.concurrent.thread

/**
 * Realtime audio I/O for conversation mode — the mic→engine capture and
 * engine→speaker playback, extracted from the validated RtAudioTestActivity
 * spike so both engines (cloud S2S now, local cascade later) share it.
 *
 *  • Capture: AudioRecord 16 kHz mono PCM16 on VOICE_COMMUNICATION (engages the
 *    platform AEC/NS/AGC), plus belt-and-suspenders software AEC + NoiseSuppressor.
 *    Streams ~100 ms chunks via [onChunk] off a dedicated thread.
 *  • Playback: AudioTrack 24 kHz mono PCM16, MODE_STREAM, ~0.5 s buffer.
 *  • Barge-in: [flushPlayback] drops queued speech instantly when the model
 *    reports the user interrupted.
 *
 * Caller owns mic ownership/hand-off (the realtime session must not run while
 * the wake-word/STT pipeline holds the mic — coordinated in #3).
 */
class RealtimeAudioIo(private val context: Context) {

    companion object {
        private const val TAG = "RealtimeAudioIo"
        const val IN_RATE = 16000     // mic → model
        const val OUT_RATE = 24000    // model → speaker
        private const val CHUNK_BYTES = 3200   // ~100 ms @ 16k mono PCM16
        // Talk-over barge-in tuning knob (20260703_BARGEIN_ESCALATION_HANDOFF, option #2).
        // Attenuate TTS playback below the volume slider: a small speaker driven loud distorts
        // (non-linear), and AEC3 — a LINEAR canceller — can't remove that distortion, leaving
        // residual echo in the same band as the user's voice. Lowering the level shrinks the
        // non-linear residual so the double-talk detector (enr/snr) can separate the two.
        // 1.0 = full slider level; sweep down (0.7, 0.5) on device and watch the enr/snr gap.
        private const val PLAYBACK_GAIN = 0.7f
        // Spike diagnostic: dump raw mic + post-AEC cleaned mic to getExternalFilesDir for
        // offline analysis (clipping/level/intelligibility). MUST stay off in shipping builds —
        // it writes raw mic PCM of every Live conversation to disk (privacy/store-review). The
        // cascade twin (CascadeAecController) is already false; this is the Live path. WS-F P0.
        private const val DEBUG_DUMP = false
    }

    private var rawDump: java.io.OutputStream? = null
    private var cleanDump: java.io.OutputStream? = null

    private fun openDumps() {
        if (!DEBUG_DUMP) return
        try {
            val dir = context.getExternalFilesDir(null)
            rawDump = java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dir, "aec_raw.pcm")))
            cleanDump = java.io.BufferedOutputStream(java.io.FileOutputStream(java.io.File(dir, "aec_clean.pcm")))
            Log.i(TAG, "AEC debug dump → $dir/aec_{raw,clean}.pcm (16k mono PCM16)")
        } catch (e: Exception) { Log.w(TAG, "dump open failed", e) }
    }

    private fun closeDumps() {
        try { rawDump?.flush(); rawDump?.close() } catch (_: Exception) {}
        try { cleanDump?.flush(); cleanDump?.close() } catch (_: Exception) {}
        rawDump = null; cleanDump = null
    }

    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var captureThread: Thread? = null
    private var refThread: Thread? = null   // feeds the AEC playback reference on its own core

    // Software AEC — build plan 20260627_REALTIME_VOICE_AEC. Stacks on top of the platform
    // AEC: playback is fed as the echo reference and mic frames are cleaned before they
    // reach the model. AEC3 ([RealtimeAec3]) is primary (full-band, double-talk friendly);
    // AECM ([RealtimeAec]) is the fallback if AEC3's native lib can't load. Created once per
    // dialog (gated on the device-local realtimeAec pref); null when disabled or both fail
    // (→ raw pass-through).
    private var softwareAec: SoftwareAec? = null

    // Audio mode is switched to MODE_IN_COMMUNICATION ONLY for the duration of a live
    // dialog, so the platform AEC actually references the playback and cancels Dashie's
    // own voice (otherwise barge-in self-triggers on echo). Restored on stop() so the
    // wake-word/cascade path runs in the normal mode it expects.
    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedMode: Int? = null
    private var savedSpeaker: Boolean? = null

    /** True once the AEC for this session is AEC3 (decided in [initSoftwareAec]). */
    private val usingAec3 get() = softwareAec is RealtimeAec3

    @Suppress("DEPRECATION")
    private fun enterCommMode() {
        initSoftwareAec()   // decide AEC3 vs AECM FIRST — it gates the routing below
        // AEC3 owns echo cancellation, so we do NOT want MODE_IN_COMMUNICATION: comm mode
        // routes playback to the voice-call volume stream (loud, ignores the media volume
        // slider) which over-drives the speaker, clips the mic, and makes the echo non-linear
        // — exactly what AEC3 can't cancel. AEC3 plays via USAGE_MEDIA (see startPlayback) and
        // captures raw, doing all cancellation itself.
        if (usingAec3) return
        // AECM/none fallback: comm mode engages the platform AEC and pins output to the speaker.
        if (savedMode != null) return
        try {
            val am = audioManager
            savedMode = am.mode
            savedSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true   // tablets/TVs: keep output on the main speaker
            Log.i(TAG, "entered MODE_IN_COMMUNICATION (was $savedMode) for AECM platform AEC")
        } catch (e: Exception) { Log.w(TAG, "enterCommMode failed", e) }
    }

    /** Stand up the software AEC for this dialog, unless the device-local flag is off.
     *  AEC3 primary, AECM fallback. Idempotent; if both fail we degrade to platform-AEC-only
     *  pass-through. */
    private fun initSoftwareAec() {
        if (softwareAec != null) return
        if (!VoicePreferences(context).realtimeAecEnabled) {
            Log.i(TAG, "software AEC disabled by realtimeAec pref"); return
        }
        // Primary: AEC3 (full-band, preserves near-end during double-talk → real barge-in).
        val aec3 = RealtimeAec3(captureRate = IN_RATE, renderRate = OUT_RATE)
        if (aec3.init()) {
            // Reference is fed head-aligned (see pumpReference), so the residual render↔capture
            // delay is just acoustic + mic buffering. AEC3 also self-estimates from here.
            aec3.setStreamDelayMs(50)
            softwareAec = aec3
            Log.i(TAG, "software AEC = AEC3")
            return
        }
        // Fallback: AECM (fixed-point) if AEC3's native lib can't load on this device.
        val aecm = RealtimeAec()
        softwareAec = if (aecm.init()) {
            Log.i(TAG, "software AEC = AECM (AEC3 unavailable)"); aecm
        } else null
    }

    @Suppress("DEPRECATION")
    private fun exitCommMode() {
        val prev = savedMode ?: return
        try {
            val am = audioManager
            savedSpeaker?.let { am.isSpeakerphoneOn = it }
            am.mode = prev
            Log.i(TAG, "restored audio mode to $prev")
        } catch (e: Exception) { Log.w(TAG, "exitCommMode failed", e) }
        savedMode = null; savedSpeaker = null
    }

    @Volatile private var capturing = false

    /** True if the platform reports a hardware AEC was engaged (diagnostics). */
    var aecEngaged: Boolean = false
        private set

    /** AEC3 double-talk stats [residual_echo_likelihood, ERLE, ERL] when AEC3 is the active
     *  canceller, else null. Used by the engine to tell near-end speech from residual echo. */
    fun aecStats(): DoubleArray? = (softwareAec as? RealtimeAec3)?.stats()

    // ── playback (model → speaker) ───────────────────────────────────────

    fun startPlayback() {
        enterCommMode()   // dialog AEC: engage before the track so it's the echo reference
        val min = AudioTrack.getMinBufferSize(
            OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // AEC3 path plays as MEDIA so it follows the user's volume slider and doesn't blast at
        // the fixed voice-call volume (which clipped the mic and broke cancellation). The AECM
        // path keeps VOICE_COMMUNICATION to pair with its comm-mode platform AEC.
        val usage = if (usingAec3) AudioAttributes.USAGE_MEDIA
                    else AudioAttributes.USAGE_VOICE_COMMUNICATION
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(OUT_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min, OUT_RATE)) // ~0.5 s
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // New track → playback head restarts at 0, so the written-frame counter must
        // too. Without this it kept prior sessions' totals while head reset, inflating
        // playbackRemainingMs() (and thus the idle window) by every past conversation.
        framesWritten = 0
        resetReference()
        // Attenuate TTS below the slider (option #2) to cut non-linear speaker distortion that
        // AEC3 can't cancel — helps the enr/snr double-talk signal separate barge-in from echo.
        try { track?.setVolume(PLAYBACK_GAIN) } catch (_: Exception) {}
        track?.play()
    }

    // Frames written since the track started / last flush (PCM16 mono: 2 bytes/frame).
    @Volatile private var framesWritten = 0L

    // Echo-reference pacing. We must NOT feed the playback to the AEC when it's WRITTEN to
    // the AudioTrack (it then sits in the ~0.5 s buffer, so the reference would lead the
    // actual speaker echo by up to half a second — far outside AEC3's delay window, which
    // both leaks echo and over-suppresses the user's voice). Instead we queue the written
    // PCM here and feed the AEC paced to playbackHeadPosition (frames actually emitted),
    // from the capture thread — so the reference tracks what the mic is really hearing.
    // This is also robust to underruns: when the head stalls, reference feeding stalls too.
    private val refLock = Any()
    private val refQueue = ArrayDeque<ByteArray>()   // written-but-not-yet-played 24k PCM
    private var refHeadOffset = 0                     // bytes consumed from refQueue.first()
    @Volatile private var refFedFrames = 0L           // 24k frames already fed to the AEC

    private fun resetReference() {
        synchronized(refLock) { refQueue.clear(); refHeadOffset = 0 }
        refFedFrames = 0
    }

    fun writePlayback(pcm: ByteArray) {
        try {
            val n = track?.write(pcm, 0, pcm.size) ?: 0
            if (n > 0) framesWritten += n / 2
        } catch (_: Exception) { /* torn down */ }
        // Queue as the echo reference; actual feed happens in pumpReference(), paced to the
        // playback head. (GeminiLiveEngine hands us a fresh array per chunk, so retaining it
        // is safe.) Skip the copy/queue entirely when no software AEC is active.
        if (softwareAec != null) synchronized(refLock) { refQueue.addLast(pcm) }
    }

    /** Feed the software AEC the playback reference up to playbackHeadPosition — i.e. the
     *  audio the speaker has actually emitted — so it aligns with the echo now in the mic.
     *  Called from the capture thread each chunk. */
    private fun pumpReference() {
        val aecRef = softwareAec ?: return
        val t = track ?: return
        val head = try { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL } catch (_: Exception) { return }
        var needBytes = (head - refFedFrames) * 2
        if (needBytes <= 0) return
        val out = ArrayList<ByteArray>()
        synchronized(refLock) {
            while (needBytes > 0 && refQueue.isNotEmpty()) {
                val front = refQueue.first()
                val avail = (front.size - refHeadOffset).toLong()
                if (avail <= needBytes) {
                    out.add(if (refHeadOffset == 0) front else front.copyOfRange(refHeadOffset, front.size))
                    refQueue.removeFirst(); refHeadOffset = 0
                    needBytes -= avail
                } else {
                    val take = needBytes.toInt()              // even (frames*2), < avail
                    out.add(front.copyOfRange(refHeadOffset, refHeadOffset + take))
                    refHeadOffset += take
                    needBytes = 0
                }
            }
        }
        var fed = 0L
        for (b in out) { try { aecRef.processReverse(b) } catch (_: Exception) {}; fed += b.size }
        refFedFrames += fed / 2
    }

    /** Reference frames queued but not yet fed (diagnostic). Large + growing ⇒ playback
     *  outpacing the head (normal during a reply); the mic-lag signal is the cancel time. */
    private fun refDepthFrames(): Long {
        synchronized(refLock) {
            var bytes = -refHeadOffset.toLong()
            for (b in refQueue) bytes += b.size
            return bytes / 2
        }
    }

    /** Estimated ms of audio still queued in the AudioTrack but not yet played
     *  (0 when drained). Lets the engine start the idle countdown only AFTER
     *  Dashie has finished talking, not when the audio finishes arriving. */
    fun playbackRemainingMs(): Long {
        val t = track ?: return 0
        return try {
            val head = t.playbackHeadPosition.toLong()
            ((framesWritten - head).coerceAtLeast(0L)) * 1000L / OUT_RATE
        } catch (_: Exception) { 0 }
    }

    /** Barge-in: discard queued speech and resume ready for the next reply. */
    fun flushPlayback() {
        try { track?.pause(); track?.flush(); track?.play(); framesWritten = 0; resetReference() } catch (_: Exception) {}
    }

    // ── capture (mic → model) ────────────────────────────────────────────

    /** Begin streaming mic chunks. [onChunk] is called on a background thread
     *  with raw PCM16 bytes (~100 ms each). Returns false if the recorder
     *  couldn't initialize (mic busy / permission). */
    fun startCapture(onChunk: (ByteArray) -> Unit): Boolean {
        enterCommMode()   // idempotent — in case capture starts before playback
        val min = AudioRecord.getMinBufferSize(
            IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(min, IN_RATE) // ~0.5 s
        // AEC3 needs a RAW mic whose echo is a linear function of our reference. VOICE_COMMUNICATION
        // forces the platform AEC/AGC/NS, which pre-processes the mic non-linearly — that breaks
        // AEC3's model (leaked echo) and ducks near-end (no barge-in). So feed AEC3 from
        // VOICE_RECOGNITION (no platform AEC/AGC). AECM/none keep VOICE_COMMUNICATION + platform AEC.
        val usingAec3 = softwareAec is RealtimeAec3
        val source = if (usingAec3) MediaRecorder.AudioSource.VOICE_RECOGNITION
                     else MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val rec = try {
            AudioRecord(source, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        } catch (e: Exception) { Log.e(TAG, "AudioRecord init failed: ${e.message}"); return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized"); try { rec.release() } catch (_: Exception) {}; return false
        }
        recorder = rec
        if (usingAec3) {
            // AEC3 owns cancellation + NS. Stacking the platform AEC/NS here would feed it a
            // pre-mangled, non-linear mic and hurt both echo suppression and barge-in.
            Log.i(TAG, "AEC3: raw VOICE_RECOGNITION capture, platform AEC/NS off")
        } else {
            // AECM benefits from the platform AEC pre-reducing echo (measured on Samsung: yielding
            // it made barge-in WORSE). The two stack: platform first, AECM second.
            try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
        }
        aecEngaged = aec != null

        openDumps()
        rec.startRecording()
        capturing = true
        captureThread = thread(name = "rt-mic") {
            val buf = ByteArray(CHUNK_BYTES)
            var chunks = 0L; var procNanos = 0L
            while (capturing && !Thread.currentThread().isInterrupted) {
                val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { break }
                if (n > 0) {
                    val raw = if (n == buf.size) buf.copyOf() else buf.copyOf(n)
                    try { rawDump?.write(raw) } catch (_: Exception) {}
                    // Subtract Dashie's own voice before the mic reaches the model. On null
                    // (AEC off) fall back to the raw frame; an empty result is a held sub-frame.
                    val t0 = System.nanoTime()
                    val clean = (try { softwareAec?.processCapture(raw) } catch (_: Exception) { null }) ?: raw
                    procNanos += System.nanoTime() - t0
                    if (clean.isNotEmpty()) { try { cleanDump?.write(clean) } catch (_: Exception) {}; onChunk(clean) }
                    // Diagnostic every ~3 s: average cancel time per ~100 ms chunk. If it nears
                    // 100 ms the capture thread can't keep up and the mic lags real-time.
                    if (++chunks % 30 == 0L) {
                        Log.i(TAG, "capture: avg cancel %.1f ms/chunk, refDepth=%d frames"
                            .format(procNanos / 30.0 / 1_000_000.0, refDepthFrames()))
                        procNanos = 0
                    }
                }
            }
        }
        // Pump the playback reference on its OWN thread so AEC3's render stream runs on a
        // separate core, parallel to capture — the serial single-thread path saturated the
        // weak Echo Show and lagged the mic. Only needed when a software AEC is active.
        if (softwareAec != null) {
            refThread = thread(name = "rt-ref") {
                while (capturing && !Thread.currentThread().isInterrupted) {
                    pumpReference()
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
        }
        return true
    }

    // ── teardown ─────────────────────────────────────────────────────────

    fun stop() {
        capturing = false
        try { captureThread?.interrupt() } catch (_: Exception) {}
        captureThread = null
        try { refThread?.interrupt() } catch (_: Exception) {}
        refThread = null
        closeDumps()
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { aec?.release() } catch (_: Exception) {}; aec = null
        try { ns?.release() } catch (_: Exception) {}; ns = null
        try { softwareAec?.release() } catch (_: Exception) {}; softwareAec = null
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) {}
        track = null
        resetReference()
        exitCommMode()   // hand the audio mode back for wake-word/cascade
    }
}
