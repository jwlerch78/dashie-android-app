package com.dashieapp.Dashie.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors

/**
 * ContinuousCaptureRecorder — dev-only continuous mic capture.
 *
 * Writes the EXACT audio stream the wake-word detector sees (the same samples
 * [AudioCaptureService] pushes into [SharedAudioBuffer], post-captureProcessor,
 * AudioSource.MIC, 16 kHz mono) to rolling WAV segments on device. Purpose:
 * harvest real mic-path VoIP/Zoom audio for training negatives — the digital
 * recordings mined into the model don't reproduce the speaker→room→mic path
 * that actually trips it (see WAKEWORD_2026-07_OUTCOMES.md §5a).
 *
 * NOT a synced setting: state is process-local, toggled from the dev
 * ContinuousCaptureActivity (staging/local flavors only). Files stay on device;
 * pull with:  adb pull /storage/emulated/0/Android/data/<pkg>/files/continuous-capture
 *
 * Threading: [onAudio] is called on the capture thread and only copies + hands
 * off; ALL file I/O runs on a single background executor (serialized, so no
 * locks), keeping the detection loop fast.
 */
object ContinuousCaptureRecorder {
    private const val TAG = "ContinuousCapture"
    private const val SAMPLE_RATE = 16000
    private const val SEGMENT_MS = 5 * 60 * 1000L         // 5-minute rolling segments
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB cap (drop oldest)
    private const val HEADER_BYTES = 44

    @Volatile var enabled: Boolean = false
        private set

    private val io = Executors.newSingleThreadExecutor()
    private var outDir: File? = null

    // Below fields are only touched on the io executor thread except the @Volatile
    // status mirrors, which the UI reads.
    private var raf: RandomAccessFile? = null
    private var segDataBytes: Long = 0
    private var segStartMs: Long = 0

    @Volatile private var startedAtMs: Long = 0
    @Volatile private var totalBytes: Long = 0
    @Volatile private var segmentCount: Int = 0
    @Volatile private var currentPath: String = ""

    fun start(context: Context) {
        if (enabled) return
        outDir = File(context.getExternalFilesDir(null), "continuous-capture").apply { mkdirs() }
        startedAtMs = System.currentTimeMillis()
        totalBytes = 0
        segmentCount = 0
        enabled = true
        io.execute { openSegment() }
        Log.i(TAG, "▶️ continuous capture started → ${outDir?.absolutePath}")
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        io.execute { closeSegment() }
        Log.i(TAG, "⏹️ continuous capture stopped ($segmentCount segments, ${mb(totalBytes)} MB)")
    }

    /** Called from the capture loop for every chunk written to SharedAudioBuffer. */
    fun onAudio(samples: ShortArray, count: Int) {
        if (!enabled || count <= 0) return
        val copy = samples.copyOf(count)
        io.execute { writeChunk(copy) }
    }

    fun statusLine(): String {
        if (!enabled) {
            return if (segmentCount > 0)
                "Idle — last run: $segmentCount segments, ${mb(totalBytes)} MB"
            else "Idle"
        }
        val secs = (System.currentTimeMillis() - startedAtMs) / 1000
        return String.format(
            Locale.US, "● Recording  %02d:%02d  •  %d segments  •  %.1f MB",
            secs / 60, secs % 60, segmentCount, totalBytes / 1_048_576.0
        )
    }

    fun outputDirPath(): String = outDir?.absolutePath ?: "(not started)"

    // ── io-thread internals ───────────────────────────────────────────────
    private fun openSegment() {
        try {
            enforceCap()
            val dir = outDir ?: return
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(java.util.Date())
            val f = File(dir, "capture_${ts}_seg${segmentCount + 1}.wav")
            val r = RandomAccessFile(f, "rw")
            r.write(wavHeader(0))            // placeholder sizes, patched on close
            raf = r
            segDataBytes = 0
            segStartMs = System.currentTimeMillis()
            segmentCount += 1
            currentPath = f.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "openSegment failed", t); raf = null
        }
    }

    private fun writeChunk(shorts: ShortArray) {
        if (raf == null) openSegment()
        val r = raf ?: return
        try {
            val bytes = ByteArray(shorts.size * 2)
            var j = 0
            for (s in shorts) {
                val v = s.toInt()
                bytes[j++] = (v and 0xFF).toByte()
                bytes[j++] = ((v shr 8) and 0xFF).toByte()
            }
            r.write(bytes)
            segDataBytes += bytes.size
            totalBytes += bytes.size
            if (System.currentTimeMillis() - segStartMs >= SEGMENT_MS) {
                closeSegment(); openSegment()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "writeChunk failed", t)
        }
    }

    private fun closeSegment() {
        val r = raf ?: return
        try {
            r.seek(4); r.write(leInt(36 + segDataBytes.toInt()))   // RIFF chunk size
            r.seek(40); r.write(leInt(segDataBytes.toInt()))       // data chunk size
            r.close()
        } catch (t: Throwable) {
            Log.e(TAG, "closeSegment failed", t)
        } finally {
            raf = null
        }
    }

    /** Delete oldest segments while the folder exceeds the total-size cap. */
    private fun enforceCap() {
        val dir = outDir ?: return
        val files = dir.listFiles { f -> f.name.endsWith(".wav") }?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        var i = 0
        while (total > MAX_TOTAL_BYTES && i < files.size) {
            total -= files[i].length()
            files[i].delete()
            i++
        }
        if (i > 0) Log.w(TAG, "cap: deleted $i oldest segment(s) to stay under ${mb(MAX_TOTAL_BYTES)} MB")
    }

    private fun wavHeader(dataSize: Int): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataSize); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(SAMPLE_RATE); bb.putInt(SAMPLE_RATE * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataSize)
        return bb.array()
    }

    private fun leInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun mb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1_048_576.0)
}
