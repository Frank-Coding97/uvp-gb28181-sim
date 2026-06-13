package com.uvp.sim.sip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdpPlaybackParserTest {

    private val playbackSdp = """v=0
o=34020000001320000001 0 0 IN IP4 192.0.2.10
s=Playback
u=34020000001320000001:0
c=IN IP4 192.0.2.20
t=1718208000 1718208600
m=video 9000 RTP/AVP 96
a=rtpmap:96 PS/90000
a=recvonly
y=0123456789
"""

    @Test fun parse_playbackSdp_extractsAllFields() {
        val o = SdpPlaybackParser.parse(playbackSdp.encodeToByteArray())
        assertTrue(o.isPlayback)
        assertEquals("192.0.2.20", o.remoteIp)
        assertEquals(9000, o.remotePort)
        assertEquals("0123456789", o.ssrc)
        assertEquals("34020000001320000001", o.channelId)
        assertEquals(1_718_208_000L, o.startEpochSec)
        assertEquals(1_718_208_600L, o.endEpochSec)
    }

    @Test fun parse_playSdp_returnsIsPlaybackFalse() {
        val playSdp = """v=0
o=dev 0 0 IN IP4 192.0.2.10
s=Play
c=IN IP4 192.0.2.20
t=0 0
m=video 9000 RTP/AVP 96
a=rtpmap:96 PS/90000
a=sendrecv
y=0100000123
"""
        val o = SdpPlaybackParser.parse(playSdp.encodeToByteArray())
        assertFalse(o.isPlayback)
        assertEquals(0L, o.startEpochSec)
        assertEquals(0L, o.endEpochSec)
    }

    @Test fun parse_missingC_throws() {
        val sdp = """v=0
s=Playback
t=1 2
m=video 9000 RTP/AVP 96
"""
        assertFails { SdpPlaybackParser.parse(sdp.encodeToByteArray()) }
    }

    @Test fun parse_t_epochSeconds_correctlyParsed() {
        val sdp = """v=0
s=Playback
c=IN IP4 1.2.3.4
t=1700000000 1700003600
m=video 9000 RTP/AVP 96
"""
        val o = SdpPlaybackParser.parse(sdp.encodeToByteArray())
        assertEquals(1_700_000_000L, o.startEpochSec)
        assertEquals(1_700_003_600L, o.endEpochSec)
    }

    @Test fun parse_uLineWithSsrc_extractsBoth() {
        val o = SdpPlaybackParser.parse(playbackSdp.encodeToByteArray())
        assertEquals("34020000001320000001", o.channelId)
        assertEquals("0123456789", o.ssrc)
    }

    @Test fun parse_noULine_channelNullSsrcOk() {
        val sdp = """v=0
s=Playback
c=IN IP4 1.2.3.4
t=1 2
m=video 9000 RTP/AVP 96
y=0123456789
"""
        val o = SdpPlaybackParser.parse(sdp.encodeToByteArray())
        assertNull(o.channelId)
        assertEquals("0123456789", o.ssrc)
    }
}
