package com.dashieapp.Dashie.halite.voice.speakerid

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeakerIdentifierTest {

    private val store =
        VoiceprintStore(ApplicationProvider.getApplicationContext())

    /** Unit vector along axis [axis], optionally blended toward another. */
    private fun unit(axis: Int, blend: Int = -1, blendWeight: Float = 0f): FloatArray {
        val v = FloatArray(256)
        v[axis] = 1f - blendWeight
        if (blend >= 0) v[blend] = blendWeight
        val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        for (i in v.indices) v[i] /= norm
        return v
    }

    private fun identifier() = SpeakerIdentifier(store)

    @Test
    fun noProfilesStatus() {
        assertEquals(
            SpeakerIdentifier.Status.NO_PROFILES,
            identifier().identify(unit(0)).status
        )
    }

    @Test
    fun knownSpeakerMatchesRightMember() {
        store.addVector("john", "John", unit(0), VoiceprintStore.Source.EXPLICIT)
        store.addVector("des", "Desiree", unit(10), VoiceprintStore.Source.EXPLICIT)

        // probe close to John's axis (cos ≈ 0.97)
        val result = identifier().identify(unit(0, blend = 1, blendWeight = 0.25f))
        assertEquals(SpeakerIdentifier.Status.KNOWN, result.status)
        assertEquals("john", result.memberId)
        assertEquals("des", result.secondMemberId)
    }

    @Test
    fun uncertainBandTriggersConfirmFlow() {
        store.addVector("john", "John", unit(0), VoiceprintStore.Source.EXPLICIT)
        // engineered probe: cos with John's axis ≈ 0.30 (between 0.25 and 0.40)
        val probe = FloatArray(256)
        probe[0] = 0.30f
        probe[50] = sqrt(1f - 0.30f * 0.30f)
        val result = identifier().identify(probe)
        assertEquals(SpeakerIdentifier.Status.UNCERTAIN, result.status)
        assertEquals("john", result.memberId)
        assertEquals(0.30f, result.score, 1e-4f)
    }

    @Test
    fun strangerIsUnknownWithNoMemberLeaked() {
        store.addVector("john", "John", unit(0), VoiceprintStore.Source.EXPLICIT)
        val result = identifier().identify(unit(200)) // orthogonal
        assertEquals(SpeakerIdentifier.Status.UNKNOWN, result.status)
        assertNull(result.memberId)
        assertNull(result.memberName)
    }

    @Test
    fun maxOverVectorSetBeatsCentroid() {
        // John has two distinct "modes" (near mic / far field) — max-over-set
        // recognizes a probe near EITHER mode.
        store.addVector("john", "John", unit(0), VoiceprintStore.Source.EXPLICIT)
        store.addVector("john", "John", unit(30), VoiceprintStore.Source.IMPLICIT)
        val result = identifier().identify(unit(30, blend = 31, blendWeight = 0.2f))
        assertEquals(SpeakerIdentifier.Status.KNOWN, result.status)
        assertEquals("john", result.memberId)
    }
}
