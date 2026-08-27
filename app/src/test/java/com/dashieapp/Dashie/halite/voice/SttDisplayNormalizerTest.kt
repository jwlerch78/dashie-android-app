package com.dashieapp.Dashie.halite.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SttDisplayNormalizerTest {

    @Test
    fun `replaces all mishearing variants`() {
        assertEquals("hey dashie", SttDisplayNormalizer.normalize("hey dashi"))
        assertEquals("hey dashie", SttDisplayNormalizer.normalize("hey dashy"))
        assertEquals("hey dashie", SttDisplayNormalizer.normalize("hey dashee"))
        assertEquals("hey dashie", SttDisplayNormalizer.normalize("hey dashey"))
    }

    @Test
    fun `preserves leading capitalization`() {
        assertEquals("Dashie, what time is it", SttDisplayNormalizer.normalize("Dashy, what time is it"))
        assertEquals("tell dashie to stop", SttDisplayNormalizer.normalize("tell dashee to stop"))
        assertEquals("DASHIE turn on the lights", SttDisplayNormalizer.normalize("DASHY turn on the lights"))
    }

    @Test
    fun `word boundaries protect real words`() {
        assertEquals("a dashing outfit", SttDisplayNormalizer.normalize("a dashing outfit"))
        assertEquals("the haberdashery", SttDisplayNormalizer.normalize("the haberdashery"))
        assertEquals("dash cam footage", SttDisplayNormalizer.normalize("dash cam footage"))
    }

    @Test
    fun `already-correct text and multiple hits`() {
        assertEquals("hey dashie", SttDisplayNormalizer.normalize("hey dashie"))
        assertEquals("dashie, ask dashie about dashie", SttDisplayNormalizer.normalize("dashi, ask dashy about dashee"))
        assertEquals("", SttDisplayNormalizer.normalize(""))
    }
}
