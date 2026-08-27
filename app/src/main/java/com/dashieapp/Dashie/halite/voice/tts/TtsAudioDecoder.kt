package com.dashieapp.Dashie.halite.voice.tts

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File

/**
 * Decodes a short TTS clip (HA tts_proxy MP3, system-TTS WAV) to mono PCM16 so it can be
 * played through [PcmTtsPlayer] and teed to the cascade AEC as the render reference
 * (WS-F.0b). MediaPlayer exposes no PCM — that's why the HA / device-TTS paths were
 * echo-blind; decode-then-PcmTtsPlayer is the same shape CloudTtsClient and LocalTtsClient
 * already use for their PCM/WAV responses.
 *
 * Whole-clip, in-memory: TTS replies are seconds long, and the AEC needs the true sample
 * rate BEFORE playback starts ([CascadeAecController.onTtsSessionStart] slices reference
 * frames by rate), which the decoded output format provides. [MAX_PCM_BYTES] guards a
 * mis-served giant file. Blocking — call off the main thread.
 */
object TtsAudioDecoder {
    private const val TAG = "TtsAudioDecoder"
    private const val MAX_PCM_BYTES = 32 * 1024 * 1024   // ~5.5 min mono @ 48 k — far past any TTS clip
    private const val CODEC_TIMEOUT_US = 10_000L

    class DecodedPcm(val pcm: ByteArray, val sampleRate: Int)

    /** Decode [file] to mono PCM16. Returns null on any failure — callers keep their
     *  legacy MediaPlayer route as the fallback (non-fatal, like every other AEC seam). */
    fun decodeToMonoPcm16(file: File): DecodedPcm? = try {
        parseWavPcm16(file) ?: decodeWithCodec(file)
    } catch (t: Throwable) {
        Log.w(TAG, "decode failed (${file.name}): ${t.message}")
        null
    }

    // ── WAV fast path (system TTS synthesizeToFile output) ──────────────────────────

    /** RIFF/WAVE with 16-bit PCM → pull the data chunk directly (no codec). Returns null
     *  when [file] isn't that (MP3 etc.) so the codec path takes over. */
    private fun parseWavPcm16(file: File): DecodedPcm? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        if (!bytes.sliceArray(0..3).contentEquals("RIFF".toByteArray()) ||
            !bytes.sliceArray(8..11).contentEquals("WAVE".toByteArray())) return null

        var pos = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = leInt(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size && id != "data") return null
            when (id) {
                "fmt " -> {
                    if (size < 16) return null
                    val audioFormat = leShort(bytes, pos + 8)
                    channels = leShort(bytes, pos + 10)
                    sampleRate = leInt(bytes, pos + 12)
                    bitsPerSample = leShort(bytes, pos + 22)
                    // 1 = PCM, 0xFFFE = extensible (system TTS engines emit plain PCM16)
                    if (audioFormat != 1 && audioFormat != 0xFFFE) return null
                }
                "data" -> {
                    if (sampleRate <= 0 || channels !in 1..2 || bitsPerSample != 16) return null
                    val dataLen = minOf(size, bytes.size - pos - 8)
                    val pcm = bytes.copyOfRange(pos + 8, pos + 8 + dataLen)
                    return DecodedPcm(if (channels == 2) downmixStereo(pcm, pcm.size) else pcm, sampleRate)
                }
            }
            pos += 8 + size + (size and 1)   // chunks are word-aligned
        }
        return null
    }

    // ── MediaCodec path (MP3 / anything else the platform can decode) ───────────────

    private fun decodeWithCodec(file: File): DecodedPcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var trackFormat: MediaFormat? = null
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) { extractor.selectTrack(i); trackFormat = f; mime = m; break }
            }
            val inFormat = trackFormat ?: return null

            codec = MediaCodec.createDecoderByType(mime).apply { configure(inFormat, null, null, 0); start() }
            val info = MediaCodec.BufferInfo()
            var out = ByteArray(256 * 1024)
            var outLen = 0
            var sampleRate = inFormat.getIntSafe(MediaFormat.KEY_SAMPLE_RATE, 0)
            var channels = inFormat.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: return null
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIdx = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getIntSafe(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = f.getIntSafe(MediaFormat.KEY_CHANNEL_COUNT, channels)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* spin */ }
                    else -> if (outIdx >= 0) {
                        if (info.size > 0) {
                            if (outLen + info.size > MAX_PCM_BYTES) { Log.w(TAG, "clip exceeds PCM cap"); return null }
                            if (outLen + info.size > out.size) {
                                var cap = out.size * 2
                                while (cap < outLen + info.size) cap *= 2
                                out = out.copyOf(cap)
                            }
                            val buf = codec.getOutputBuffer(outIdx) ?: return null
                            buf.position(info.offset)
                            buf.get(out, outLen, info.size)
                            outLen += info.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            if (outLen == 0 || sampleRate <= 0) return null
            val pcm = if (channels == 2) downmixStereo(out, outLen) else out.copyOf(outLen)
            return DecodedPcm(pcm, sampleRate)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    /** Average L+R PCM16 frames into mono (AEC + PcmTtsPlayer are mono-only). */
    private fun downmixStereo(pcm: ByteArray, len: Int): ByteArray {
        val frames = len / 4
        val out = ByteArray(frames * 2)
        var i = 0
        var j = 0
        repeat(frames) {
            val l = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            val r = ((pcm[i + 2].toInt() and 0xFF) or (pcm[i + 3].toInt() shl 8)).toShort().toInt()
            val m = (l + r) / 2
            out[j] = (m and 0xFF).toByte()
            out[j + 1] = ((m shr 8) and 0xFF).toByte()
            i += 4; j += 2
        }
        return out
    }

    private fun MediaFormat.getIntSafe(key: String, def: Int) =
        if (containsKey(key)) getInteger(key) else def

    private fun leShort(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun leInt(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
