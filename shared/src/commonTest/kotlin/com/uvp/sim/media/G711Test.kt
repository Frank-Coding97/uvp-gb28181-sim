package com.uvp.sim.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class G711Test {

    @Test fun outputSizeMatchesInputSamples() {
        val pcm = ShortArray(160) { (it * 200).toShort() }
        assertEquals(160, G711.encodeAlaw(pcm).size)
        assertEquals(160, G711.encodeUlaw(pcm).size)
    }

    @Test fun zeroSampleProducesAlawSilence() {
        // A-law silence is 0xD5 (= 0x55 XOR 0x80) per ITU-T G.711.
        val out = G711.encodeAlaw(shortArrayOf(0))
        assertEquals(0xD5.toByte(), out[0])
    }

    @Test fun zeroSampleProducesUlawSilence() {
        // μ-law silence is 0xFF per ITU-T G.711.
        val out = G711.encodeUlaw(shortArrayOf(0))
        assertEquals(0xFF.toByte(), out[0])
    }

    @Test fun positiveAndNegativeSamplesGiveDifferentBytes() {
        val pos = G711.encodeAlaw(shortArrayOf(1000))[0]
        val neg = G711.encodeAlaw(shortArrayOf(-1000))[0]
        assertNotEquals(pos, neg, "Sign bit should differ in A-law")

        val posU = G711.encodeUlaw(shortArrayOf(1000))[0]
        val negU = G711.encodeUlaw(shortArrayOf(-1000))[0]
        assertNotEquals(posU, negU, "Sign bit should differ in μ-law")
    }

    @Test fun encodingIsDeterministic() {
        val pcm = ShortArray(80) { ((it - 40) * 400).toShort() }
        assertTrue(G711.encodeAlaw(pcm).contentEquals(G711.encodeAlaw(pcm.copyOf())))
        assertTrue(G711.encodeUlaw(pcm).contentEquals(G711.encodeUlaw(pcm.copyOf())))
    }

    @Test fun extremeSamplesAreClipped() {
        // Should not throw / overflow at boundary values.
        val pcm = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, -1, 1)
        val a = G711.encodeAlaw(pcm)
        val u = G711.encodeUlaw(pcm)
        assertEquals(5, a.size)
        assertEquals(5, u.size)
    }
}
