package com.uvp.sim.media

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T6 — G.711 A-law / μ-law 解码(语音广播 RX 侧)。 */
class G711DecodeTest {

    @Test
    fun alawReferenceVectors() {
        // 0xD5 ^ 0x55 = 0x80 → 接近 0(量化级 ±8)
        val pcm = G711.decodeAlaw(byteArrayOf(0xD5.toByte()))
        assertEquals(1, pcm.size)
        assertTrue(pcm[0] in -16..16, "0xD5 → ~0,实际 ${pcm[0]}")
        // 0x55 → 负小
        val pcm2 = G711.decodeAlaw(byteArrayOf(0x55))
        assertTrue(pcm2[0] in -16..0, "0x55 → 负小,实际 ${pcm2[0]}")
    }

    @Test
    fun ulawReferenceVectors() {
        // 0xFF μ-law 最小值 → 0
        val pcm = G711.decodeUlaw(byteArrayOf(0xFF.toByte()))
        assertEquals(0.toShort(), pcm[0])
        // 0x7F → 接近 0
        val pcm2 = G711.decodeUlaw(byteArrayOf(0x7F))
        assertTrue(pcm2[0] in -16..16, "0x7F → ~0,实际 ${pcm2[0]}")
    }

    @Test
    fun alawRoundTripErrorIsSmall() {
        val original = shortArrayOf(0, 100, -200, 1000, -5000, 10000, -20000, 30000)
        val decoded = G711.decodeAlaw(G711.encodeAlaw(original))
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            val err = abs(original[i].toInt() - decoded[i].toInt())
            assertTrue(err <= 512, "sample $i: orig=${original[i]} decoded=${decoded[i]} err=$err")
        }
    }

    @Test
    fun ulawRoundTripErrorIsSmall() {
        val original = shortArrayOf(0, 100, -200, 1000, -5000, 10000, -20000, 30000)
        val decoded = G711.decodeUlaw(G711.encodeUlaw(original))
        assertEquals(original.size, decoded.size)
        for (i in original.indices) {
            val err = abs(original[i].toInt() - decoded[i].toInt())
            assertTrue(err <= 1024, "sample $i: orig=${original[i]} decoded=${decoded[i]} err=$err")
        }
    }
}
