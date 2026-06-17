package com.uvp.sim.media

import kotlin.test.Test

/** T8 — AudioPlayback JVM 实现 smoke(start/write/stop 不抛,即便 headless 无音频设备)。 */
class AudioPlaybackJvmSmokeTest {

    @Test
    fun startWriteStopDoesNotThrow() {
        val ap = AudioPlayback(8000, 1)
        ap.start()
        ap.write(ShortArray(160) { (it * 100).toShort() })
        ap.stop()
    }
}
