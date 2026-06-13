package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T02: MansRtspParser 单测(spec §A.3 / B.1 / C.1)。
 * 12 case 覆盖 PLAY/PAUSE/TEARDOWN + Range/Scale + CRLF + 大小写 + 异常。
 */
class MansRtspParserTest {

    @Test fun playWithRangeStart() {
        val body = "PLAY rtsp://x RTSP/1.0\nCSeq: 1\nRange: npt=120.0-\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertEquals(1, cmd.cseq)
        assertEquals(120_000L, cmd.rangeStartMs)
        assertNull(cmd.scale)
    }

    @Test fun playWithRangeAndScale() {
        val body = "PLAY rtsp://x RTSP/1.0\nCSeq: 1\nRange: npt=120.0-\nScale: 2.0\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertEquals(120_000L, cmd.rangeStartMs)
        assertEquals(2.0, cmd.scale)
    }

    @Test fun playWithRangeNow() {
        val body = "PLAY rtsp://x RTSP/1.0\nCSeq: 1\nRange: npt=now-\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertNull(cmd.rangeStartMs)
        assertNull(cmd.scale)
    }

    @Test fun playWithoutRangeOrScale() {
        val body = "PLAY rtsp://x RTSP/1.0\nCSeq: 1\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertEquals(1, cmd.cseq)
        assertNull(cmd.rangeStartMs)
        assertNull(cmd.scale)
    }

    @Test fun pauseCommand() {
        val body = "PAUSE rtsp://x RTSP/1.0\nCSeq: 2\nPauseTime: now\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Pause)
        assertEquals(2, cmd.cseq)
    }

    @Test fun teardownCommand() {
        val body = "TEARDOWN rtsp://x RTSP/1.0\nCSeq: 5\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Teardown)
        assertEquals(5, cmd.cseq)
    }

    @Test fun crlfCompatible() {
        val body = "PLAY rtsp://x RTSP/1.0\r\nCSeq: 1\r\nScale: 2.0\r\n\r\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertEquals(2.0, cmd.scale)
    }

    @Test fun caseInsensitive() {
        val body = "play rtsp://x RTSP/1.0\nCSEQ: 1\nrange: npt=120.0-\nSCALE: 0.5\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertEquals(1, cmd.cseq)
        assertEquals(120_000L, cmd.rangeStartMs)
        assertEquals(0.5, cmd.scale)
    }

    @Test fun missingCSeqThrows() {
        val body = "PLAY rtsp://x RTSP/1.0\n\n"
        assertFailsWith<MansRtspParseException> { MansRtspParser.parse(body) }
    }

    @Test fun illegalScaleStillParses() {
        // parser 不过滤合规,3.0 能解出来,合规检查放 handler
        val body = "PLAY rtsp://x RTSP/1.0\nCSeq: 1\nScale: 3.0\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertEquals(3.0, cmd.scale)
    }

    @Test fun rangeIntervalIgnoresEnd() {
        val body = "PLAY rtsp://x RTSP/1.0\nCSeq: 1\nRange: npt=120.0-180.0\n\n"
        val cmd = MansRtspParser.parse(body)
        assertTrue(cmd is MansRtspCommand.Play)
        assertEquals(120_000L, cmd.rangeStartMs)
    }

    @Test fun emptyBodyThrows() {
        assertFailsWith<MansRtspParseException> { MansRtspParser.parse("") }
    }
}
