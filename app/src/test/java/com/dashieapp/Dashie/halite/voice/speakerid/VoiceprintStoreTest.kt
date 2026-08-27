package com.dashieapp.Dashie.halite.voice.speakerid

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceprintStoreTest {

    private fun store() = VoiceprintStore(ApplicationProvider.getApplicationContext())

    private fun vec(seed: Int) = FloatArray(256) { (seed * 31 + it) % 17 / 17f }

    @Test
    fun persistenceRoundTrip() {
        val s1 = store()
        s1.addVector("m1", "John", vec(1), VoiceprintStore.Source.EXPLICIT, nowMs = 100)
        s1.addVector("m1", "John", vec(2), VoiceprintStore.Source.IMPLICIT, nowMs = 200)
        s1.addVector("m2", "Desiree", vec(3), VoiceprintStore.Source.EXPLICIT, nowMs = 300)

        // Fresh instance -> reads from disk
        val s2 = store()
        val profiles = s2.getProfiles()
        assertEquals(2, profiles.size)
        assertEquals("John", profiles["m1"]!!.memberName)
        assertEquals(2, profiles["m1"]!!.vectors.size)
        assertEquals(VoiceprintStore.Source.IMPLICIT, profiles["m1"]!!.vectors[1].source)
        assertEquals(200, profiles["m1"]!!.vectors[1].timestampMs)
        // vector content survives the base64 round trip exactly
        assertTrue(vec(3).contentEquals(profiles["m2"]!!.vectors[0].vector))
    }

    @Test
    fun evictionDropsOldestImplicitFirst() {
        val s = store()
        // 2 explicit seeds (oldest timestamps of all)
        s.addVector("m1", "John", vec(0), VoiceprintStore.Source.EXPLICIT, nowMs = 1)
        s.addVector("m1", "John", vec(1), VoiceprintStore.Source.EXPLICIT, nowMs = 2)
        // fill to MAX with implicit
        for (i in 2 until VoiceprintStore.MAX_VECTORS + 5) {
            s.addVector("m1", "John", vec(i), VoiceprintStore.Source.IMPLICIT, nowMs = 10L + i)
        }
        val profile = s.getProfiles()["m1"]!!
        assertEquals(VoiceprintStore.MAX_VECTORS, profile.vectors.size)
        // explicit seeds survive even though they're the oldest overall
        assertEquals(2, profile.vectors.count { it.source == VoiceprintStore.Source.EXPLICIT })
        // the evicted ones are the oldest implicits (ts 12..16)
        assertFalse(profile.vectors.any { it.timestampMs in 12..16 })
    }

    @Test
    fun deleteMemberRemovesProfileAndAudioDir() {
        val s = store()
        s.addVector("m1", "John", vec(1), VoiceprintStore.Source.EXPLICIT)
        val audioDir = s.enrollmentAudioDir("m1")
        audioDir.resolve("take1.wav").writeBytes(byteArrayOf(1, 2, 3))

        s.deleteMember("m1")
        assertTrue(s.getProfiles().isEmpty())
        assertFalse(audioDir.exists())
    }

    @Test
    fun vectorCodecIsExact() {
        val original = FloatArray(256) { it * 0.123f - 7.5f }
        val decoded = VoiceprintStore.decodeVector(VoiceprintStore.encodeVector(original))
        assertTrue(original.contentEquals(decoded))
    }
}
