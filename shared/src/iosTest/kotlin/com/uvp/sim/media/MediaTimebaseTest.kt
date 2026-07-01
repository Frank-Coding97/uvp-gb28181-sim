package com.uvp.sim.media

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMedia.CMTimeMake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class MediaTimebaseTest {

    @Test
    fun nowUs_monotonically_increases() {
        val t1 = MediaTimebase.nowUs()
        var t2 = t1
        repeat(1000) { t2 = MediaTimebase.nowUs() }
        assertTrue(t2 >= t1, "expected $t2 >= $t1")
    }

    @Test
    fun cmTimeToMicros_1_5_seconds() {
        val cmTime = CMTimeMake(value = 3, timescale = 2)
        assertEquals(1_500_000L, MediaTimebase.cmTimeToMicros(cmTime))
    }

    @Test
    fun cmTimeToMicros_44100_timescale_one_second() {
        val cmTime = CMTimeMake(value = 44100, timescale = 44100)
        assertEquals(1_000_000L, MediaTimebase.cmTimeToMicros(cmTime))
    }

    @Test
    fun cmTimeToMicros_zero_timescale_returns_zero() {
        val cmTime = CMTimeMake(value = 100, timescale = 0)
        assertEquals(0L, MediaTimebase.cmTimeToMicros(cmTime))
    }
}
