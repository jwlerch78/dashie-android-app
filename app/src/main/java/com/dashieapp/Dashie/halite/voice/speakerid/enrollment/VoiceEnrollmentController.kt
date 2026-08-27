package com.dashieapp.Dashie.halite.voice.speakerid.enrollment

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import com.dashieapp.Dashie.BuildConfig
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.halite.voice.speakerid.KaldiFbank
import com.dashieapp.Dashie.halite.voice.speakerid.SpeakerEmbedder
import com.dashieapp.Dashie.halite.voice.speakerid.SpeakerIdentifier
import com.dashieapp.Dashie.halite.voice.speakerid.VoiceprintStore
import com.dashieapp.Dashie.halite.voice.speakerid.WavIo
import java.io.File
import java.util.concurrent.Executors

/**
 * Guided voice-enrollment wizard ("Hey Dashie — learn my voice").
 *
 * Flow: member select → intro → N recorded lines (quality-gated, retried) →
 * verification ("say anything and I'll guess") → done. Explicit-enrollment
 * vectors seed VoiceprintStore; retained WAVs land in the member's
 * enrollment dir for the dev-phase analysis pipeline (upload handled by a
 * separate component via [onEnrollmentComplete]).
 *
 * Dev-gated: no-ops unless BuildConfig.SPEAKER_ID_ENABLED and the model
 * asset is present (SpeakerEmbedder.create() != null). All dependencies
 * arrive as providers so wiring can hand us live components (overlay
 * container and mic ring buffer exist only while voice is running).
 */
class VoiceEnrollmentController(
    private val context: Context,
    private val store: VoiceprintStore,
    private val overlayContainer: () -> ViewGroup?,
    private val audioBuffer: () -> SharedAudioBuffer?,
    private val retainRecordings: () -> Boolean = { true },
) {
    /** Fired after a successful enrollment (memberId, retained WAV files). */
    var onEnrollmentComplete: ((memberId: String, wavFiles: List<File>) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val embedExecutor = Executors.newSingleThreadExecutor()

    private var view: VoiceEnrollmentView? = null
    private var embedder: SpeakerEmbedder? = null
    private var memberId: String? = null
    private var memberName: String? = null
    private var lineIndex = 0
    private var retriesForLine = 0
    private var goodTakes = 0
    private var wavFiles = mutableListOf<File>()
    private var sessionTs = 0L

    val isAvailable: Boolean
        get() = BuildConfig.SPEAKER_ID_ENABLED && SpeakerEmbedder.isAvailable(context)

    /** Entry point with a member picker (e.g. voice trigger with no name). */
    fun startEnrollment(members: List<Pair<String, String>>) {
        if (!ensureReady()) return
        val v = showView() ?: return
        v.renderMemberSelect(members)
    }

    /** Entry point when the member is already known (settings row, "it's John"). */
    fun startEnrollmentFor(id: String, name: String) {
        if (!ensureReady()) return
        showView() ?: return
        onMemberChosen(id, name)
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
        embedder?.close()
        embedder = null
    }

    val isShowing: Boolean get() = view != null

    // ── flow ─────────────────────────────────────────────────────────────────

    private fun ensureReady(): Boolean {
        if (!isAvailable) {
            Log.w(TAG, "Speaker-ID unavailable (flavor gate or missing model)")
            return false
        }
        if (embedder == null) embedder = SpeakerEmbedder.create(context)
        if (embedder == null) return false
        if (audioBuffer() == null) {
            Log.w(TAG, "No mic buffer — voice pipeline not running")
            return false
        }
        return true
    }

    private fun showView(): VoiceEnrollmentView? {
        val container = overlayContainer() ?: run {
            Log.w(TAG, "No overlay container available")
            return null
        }
        dismiss()
        val v = VoiceEnrollmentView(context)
        v.onCancel = { dismiss() }
        v.onMemberSelected = { id, name -> onMemberChosen(id, name) }
        view = v
        container.addView(v)
        v.requestFocus()
        return v
    }

    private fun onMemberChosen(id: String, name: String) {
        memberId = id
        memberName = name
        lineIndex = 0
        retriesForLine = 0
        goodTakes = 0
        wavFiles = mutableListOf()
        sessionTs = System.currentTimeMillis()
        val v = view ?: return
        v.renderIntro(name, EnrollmentScripts.LINES.size)
        v.onPrimaryAction = { runLine() }
    }

    private fun runLine() {
        val v = view ?: return
        val line = EnrollmentScripts.LINES[lineIndex]
        val total = EnrollmentScripts.LINES.size
        v.renderLine(line, lineIndex, total, VoiceEnrollmentView.LineStatus.GET_READY)

        handler.postDelayed({
            val buffer = audioBuffer() ?: return@postDelayed fail("Microphone unavailable.")
            val mark = buffer.markPosition()
            view?.renderLine(line, lineIndex, total, VoiceEnrollmentView.LineStatus.RECORDING)

            handler.postDelayed({
                view?.renderLine(line, lineIndex, total, VoiceEnrollmentView.LineStatus.PROCESSING)
                val pcm = buffer.readFromAsShorts(mark, RECORD_SAMPLES)
                processTake(pcm, line)
            }, RECORD_MS)
        }, LEAD_IN_MS)
    }

    private fun processTake(pcm: ShortArray, line: String) {
        embedExecutor.execute {
            val amplitude = KaldiFbank.meanAbsAmplitude(pcm)
            val embedding = if (amplitude >= MIN_AMPLITUDE) embedder?.embed(pcm) else null
            handler.post {
                val v = view ?: return@post
                val total = EnrollmentScripts.LINES.size
                if (embedding == null) {
                    if (retriesForLine < MAX_RETRIES_PER_LINE) {
                        retriesForLine++
                        v.renderLine(line, lineIndex, total, VoiceEnrollmentView.LineStatus.RETRY)
                        handler.postDelayed({ runLine() }, RETRY_PAUSE_MS)
                    } else {
                        advanceLine() // give up on this line, keep momentum
                    }
                    return@post
                }
                val id = memberId ?: return@post
                val name = memberName ?: return@post
                store.addVector(id, name, embedding, VoiceprintStore.Source.EXPLICIT)
                goodTakes++
                if (retainRecordings()) {
                    runCatching {
                        val f = File(store.enrollmentAudioDir(id),
                            "${sessionTs}_line${lineIndex}.wav")
                        WavIo.writeWav(f, pcm)
                        wavFiles.add(f)
                    }.onFailure { Log.w(TAG, "Failed to retain enrollment WAV", it) }
                }
                v.renderLine(line, lineIndex, total, VoiceEnrollmentView.LineStatus.GOOD_TAKE)
                handler.postDelayed({ advanceLine() }, GOOD_TAKE_PAUSE_MS)
            }
        }
    }

    private fun advanceLine() {
        retriesForLine = 0
        lineIndex++
        if (lineIndex < EnrollmentScripts.LINES.size) {
            runLine()
        } else if (goodTakes >= EnrollmentScripts.MIN_GOOD_TAKES) {
            runVerification()
        } else {
            fail("I only caught $goodTakes good takes — let's run through it again sometime.")
        }
    }

    private fun runVerification() {
        val v = view ?: return
        v.renderVerify()
        handler.postDelayed({
            val buffer = audioBuffer() ?: return@postDelayed finishWithoutVerify()
            val mark = buffer.markPosition()
            handler.postDelayed({
                val pcm = buffer.readFromAsShorts(mark, VERIFY_SAMPLES)
                embedExecutor.execute {
                    val embedding = embedder?.embed(pcm)
                    val result = embedding?.let { SpeakerIdentifier(store).identify(it) }
                    handler.post {
                        val msg = when {
                            result?.status == SpeakerIdentifier.Status.KNOWN &&
                                result.memberId == memberId ->
                                "You're ${result.memberName}! I've learned your voice."
                            result?.status == SpeakerIdentifier.Status.KNOWN ||
                                result?.status == SpeakerIdentifier.Status.UNCERTAIN ->
                                "I think you might be ${result.memberName} — " +
                                    "I'll get better as we talk more."
                            else ->
                                "Your voiceprint is saved — I'll keep learning as we talk."
                        }
                        finish(msg)
                    }
                }
            }, VERIFY_MS)
        }, LEAD_IN_MS)
    }

    private fun finishWithoutVerify() =
        finish("Your voiceprint is saved — I'll keep learning as we talk.")

    private fun finish(message: String) {
        val v = view ?: return
        v.renderDone(message)
        v.onPrimaryAction = { dismiss() }
        val id = memberId
        if (id != null) onEnrollmentComplete?.invoke(id, wavFiles.toList())
        Log.i(TAG, "Enrollment complete: $memberName, $goodTakes takes, " +
                "${wavFiles.size} WAVs retained")
    }

    private fun fail(message: String) {
        val v = view ?: return
        v.renderError(message)
        v.onPrimaryAction = { dismiss() }
    }

    companion object {
        private const val TAG = "VoiceEnrollment"
        private const val LEAD_IN_MS = 1200L
        private const val RECORD_MS = 4000L
        private const val RECORD_SAMPLES = 4 * KaldiFbank.SAMPLE_RATE  // < 5 s ring cap
        private const val VERIFY_MS = 3000L
        private const val VERIFY_SAMPLES = 3 * KaldiFbank.SAMPLE_RATE
        private const val RETRY_PAUSE_MS = 1500L
        private const val GOOD_TAKE_PAUSE_MS = 700L
        private const val MAX_RETRIES_PER_LINE = 2
        /** int16 mean-abs floor ≈ speech at conversational distance. */
        private const val MIN_AMPLITUDE = 100.0
    }
}
