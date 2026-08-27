package com.dashieapp.Dashie.halite.voice

import android.os.Handler
import android.os.Looper
import com.dashieapp.Dashie.audio.SharedAudioBuffer
import com.dashieapp.Dashie.wakeword.WakeWordDetectorInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the ONE decision [SharedCaptureMicHandoff] is parameterized on: whether [restore] re-arms
 * the wake detector.
 *
 * 🔴 Why this is worth a test rather than a comment. The two pipelines want opposite answers, and
 * getting HA Voice Assist's wrong is not a crash or a failed turn — it is Dashie hearing its own
 * spoken reply and starting a turn about it, which looks like a wake-word sensitivity problem
 * three layers away from the cause. The cascade wants the re-arm because STT ending IS its turn
 * ending; HA Voice Assist must NOT, because its turn continues into HA's intent + TTS and
 * `HaVoiceService` re-arms on its own three exits.
 *
 * `capture` is supplied as `{ null }` throughout: [AudioCaptureService] would open a real
 * AudioRecord, and the decision under test is about the DETECTOR. That also exercises the
 * null-capture path, which must still complete the handoff rather than strand it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedCaptureMicHandoffTest {

    private class FakeDetector : WakeWordDetectorInterface {
        var starts = 0
        var stops = 0
        override fun initialize(): Boolean = true
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun close() {}
        override fun isDetecting(): Boolean = false
        override fun setSharedBuffer(buffer: SharedAudioBuffer) {}
        override fun setThreshold(threshold: Float) {}
        override fun getThreshold(): Float = 0.5f
        override var onWakeWordDetected: ((Float, Long) -> Unit)? = null
        override var onHeartbeat: ((Float, Float) -> Unit)? = null
        override var onError: ((String) -> Unit)? = null
        override fun getMemoryStatus(): String = ""
        override val engineName: String = "fake"
    }

    private val detector = FakeDetector()

    private fun handoff(
        reArm: (() -> Boolean)?,
        beforeRelease: (() -> Unit)? = null,
        canRestore: () -> Boolean = { true },
    ) = SharedCaptureMicHandoff(
        capture = { null },
        wakeWord = { detector },
        handler = Handler(Looper.getMainLooper()),
        reArmWakeWord = reArm,
        tag = "test",
        beforeRelease = beforeRelease,
        canRestore = canRestore,
    )

    /**
     * Run anything the handoff posted, INCLUDING the re-arm settle delay.
     *
     * ⚠️ `idle()` alone is not enough and it fails OPEN: Robolectric's looper is paused, so it
     * runs only tasks already due and a `postDelayed(400)` simply never fires. Three of the tests
     * below then pass by asserting "the detector did not start" against a world where nothing ran
     * at all. Advance the clock past the settle so a re-arm that SHOULD happen actually does —
     * `a satisfied guard re-arms after the settle` is the negative control that catches this.
     */
    private fun settle() =
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))

    // ── The HA Voice Assist contract ──────────────────────────────────────────

    @Test
    fun `a null re-arm guard never starts the wake detector`() {
        handoff(reArm = null).restore()
        settle()
        assertEquals("HA Voice Assist's turn continues past STT — re-arming here makes Dashie " +
            "hear its own reply; HaVoiceService owns the re-arm", 0, detector.starts)
    }

    // ── The cascade contract ──────────────────────────────────────────────────

    @Test
    fun `a satisfied guard re-arms after the settle, not before`() {
        handoff(reArm = { true }).restore()
        assertEquals("the recognizer's AudioRecord must release before ours re-opens, or the " +
            "wake word comes back deaf — the re-arm is delayed on purpose", 0, detector.starts)
        settle()
        assertEquals(1, detector.starts)
    }

    @Test
    fun `the guard is read AFTER the delay, so a turn that ends meanwhile does not re-arm`() {
        var enabled = true
        handoff(reArm = { enabled }).restore()
        enabled = false            // e.g. voice disabled, or a conversation started, mid-settle
        settle()
        assertEquals("the guard is a predicate rather than a captured Boolean precisely so this " +
            "case is decided late", 0, detector.starts)
    }

    @Test
    fun `canRestore false skips the whole restore`() {
        handoff(reArm = { true }, canRestore = { false }).restore()
        settle()
        assertEquals(0, detector.starts)
    }

    // ── Release: the mic must never be stranded ───────────────────────────────

    @Test
    fun `release stops the detector and completes even with no capture service`() {
        var released = false
        handoff(reArm = null).release { released = true }
        assertTrue("a missing capture service must still complete the handoff — otherwise the " +
            "recognizer waits on a callback that never comes and STT hangs", released)
        assertEquals(1, detector.stops)
    }

    @Test
    fun `beforeRelease runs before the mic is handed over`() {
        val order = mutableListOf<String>()
        handoff(reArm = null, beforeRelease = { order += "before" })
            .release { order += "released" }
        assertEquals("the cascade stops its streaming loop here; running it after the mic is " +
            "gone would pump a dead capture at the provider", listOf("before", "released"), order)
    }
}
