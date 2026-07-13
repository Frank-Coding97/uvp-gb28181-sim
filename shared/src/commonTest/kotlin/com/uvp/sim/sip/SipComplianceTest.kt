package com.uvp.sim.sip

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GB/T 28181 § 10 协议合规细节断言:
 *   10.2 User-Agent 头
 *   10.3 Subject 头(INVITE 200 OK)
 *   10.4 Date 头 RFC1123
 *   10.8 SDP f= 编码格式字段
 *
 * 这些字段一旦缺失或格式漂移,WVP / EasyCVR / LiveGBS 等平台对接会出现:
 *   - 平台日志缺设备厂商标识(运维认不出谁)
 *   - 部分平台拒绝无 Date 头的请求
 *   - INVITE 后流和信令对不上号
 *   - EasyCVR 拿不到编码信息从而推不出转码后的流
 *
 * 所以这层测试一旦绿,builder 层就锁死,真机抓包只是"复核"。
 */
class SipComplianceTest {

    private val sampleConfig = SimConfig(
        server = ServerConfig(
            ip = "192.168.10.50",
            port = 5060,
            serverId = "34020000002000000001",
            domain = "3402000000"
        ),
        device = DeviceConfig(
            deviceId = "34020000001320000001",
            videoChannelId = "34020000001310000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001320000001",
            password = "12345678"
        ),
        userAgent = "UVP-Sim/0.1"
    )

    // ---------- 10.4 Date RFC1123 ----------

    @Test fun rfc1123DateHasFixedShape() {
        // 1994-11-06 08:49:37 UTC — RFC 1123 经典样例
        val date = SipBuilders.rfc1123Date(Instant.fromEpochSeconds(784111777))
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", date)
    }

    @Test fun rfc1123DatePadsSingleDigitFields() {
        // 2026-01-02 03:04:05 UTC -> 全部要补零
        val date = SipBuilders.rfc1123Date(Instant.parse("2026-01-02T03:04:05Z"))
        assertEquals("Fri, 02 Jan 2026 03:04:05 GMT", date)
    }

    // ---------- 10.3 Subject ----------

    @Test fun subjectMatchesGB28181Format() {
        val s = SipBuilders.subject(
            senderId = "34020000001310000001",
            ssrc = "0100000001",
            receiverId = "34020000002000000001"
        )
        assertEquals("34020000001310000001:0100000001,34020000002000000001:0", s)
    }

    // ---------- 10.2 User-Agent ----------

    @Test fun registerCarriesUserAgentAndDate() {
        val req = SipBuilders.buildRegister(
            sampleConfig, cseq = 1,
            callId = "c1", branch = "z9hG4bK1", fromTag = "t1",
            localIp = "10.0.0.10", localPort = 5060
        )
        val text = req.toBytes().decodeToString()
        assertTrue("REGISTER 必须带 User-Agent") { text.contains("User-Agent: UVP-Sim/0.1\r\n") }
        assertTrue("REGISTER 必须带 Date") { text.contains(Regex("Date: \\w{3}, \\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT")) }
    }

    @Test fun keepaliveCarriesUserAgentAndDate() {
        val req = SipBuilders.buildKeepalive(
            sampleConfig, sn = 1, cseq = 1,
            callId = "c1", branch = "z9hG4bK1", fromTag = "t1",
            localIp = "10.0.0.10", localPort = 5060
        )
        val text = req.toBytes().decodeToString()
        assertTrue { text.contains("User-Agent: UVP-Sim/0.1\r\n") }
        assertTrue { text.contains(Regex("Date: \\w{3}, .* GMT")) }
    }

    @Test fun byeCarriesUserAgentAndDate() {
        val req = SipBuilders.buildBye(
            sampleConfig, callId = "c1", cseq = 2, branch = "z9hG4bK1",
            localUri = "sip:device@domain", localTag = "lt",
            remoteUri = "sip:server@domain", remoteTag = "rt",
            remoteTarget = "sip:server@1.2.3.4:5060",
            localIp = "10.0.0.10", localPort = 5060
        )
        val text = req.toBytes().decodeToString()
        assertTrue { text.contains("User-Agent: UVP-Sim/0.1\r\n") }
        assertTrue { text.contains(Regex("Date: \\w{3}, .* GMT")) }
    }

    @Test fun simpleResponseCarriesUserAgentAndDate() {
        val invite = SipParser.parse(SipSamples.inviteRealplay.encodeToByteArray()) as SipRequest
        val resp = SipBuilders.buildSimpleResponse(
            invite, statusCode = 486, reasonPhrase = "Busy Here",
            toTag = "tt", userAgent = "UVP-Sim/0.1"
        )
        val text = resp.toBytes().decodeToString()
        assertTrue { text.startsWith("SIP/2.0 486 Busy Here\r\n") }
        assertTrue { text.contains("User-Agent: UVP-Sim/0.1\r\n") }
        assertTrue { text.contains(Regex("Date: \\w{3}, .* GMT")) }
    }

    @Test fun subscribe200CarriesUserAgentAndDate() {
        val invite = SipParser.parse(SipSamples.inviteRealplay.encodeToByteArray()) as SipRequest
        // SUBSCRIBE 200 builder 复用任何请求的 routing headers 即可
        val resp = SipBuilders.buildSubscribe200(
            invite, toTag = "tt", expires = 600, userAgent = "UVP-Sim/0.1"
        )
        val text = resp.toBytes().decodeToString()
        assertTrue { text.contains("User-Agent: UVP-Sim/0.1\r\n") }
        assertTrue { text.contains(Regex("Date: \\w{3}, .* GMT")) }
        assertTrue { text.contains("Expires: 600\r\n") }
    }

    @Test fun notifyCarriesUserAgentAndDate() {
        val req = SipBuilders.buildNotify(
            subscriberUri = "sip:34020000002000000001@10.0.0.1:5060",
            callId = "c1", fromTag = "lt", toTag = "rt",
            event = "presence", subscriptionState = "active;expires=600",
            cseq = 1, xmlBody = "<x/>",
            localIp = "10.0.0.10", localPort = 5060,
            userAgent = "UVP-Sim/0.1"
        )
        val text = req.toBytes().decodeToString()
        assertTrue { text.contains("User-Agent: UVP-Sim/0.1\r\n") }
        assertTrue { text.contains(Regex("Date: \\w{3}, .* GMT")) }
    }

    // ---------- 10.3 + 10.2 + 10.4 在 INVITE 200 OK 一齐 ----------

    @Test fun invite200CarriesSubjectUserAgentDateContact() {
        val invite = SipParser.parse(SipSamples.inviteRealplay.encodeToByteArray()) as SipRequest
        val resp = SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = "<sip:34020000001320000001@10.0.0.10:5060>",
            toTag = "device-to-tag",
            sdpBody = "v=0\r\n",
            userAgent = "UVP-Sim/0.1",
            subject = SipBuilders.subject(
                senderId = "34020000001310000001",
                ssrc = "0100000001",
                receiverId = "34020000002000000001"
            )
        )
        val text = resp.toBytes().decodeToString()
        assertTrue("响应行") { text.startsWith("SIP/2.0 200 OK\r\n") }
        assertTrue("Subject 必须按 GB28181 § 9.2 格式") {
            text.contains("Subject: 34020000001310000001:0100000001,34020000002000000001:0\r\n")
        }
        assertTrue("UA") { text.contains("User-Agent: UVP-Sim/0.1\r\n") }
        assertTrue("Date") { text.contains(Regex("Date: \\w{3}, .* GMT")) }
        assertTrue("Contact 是 ACK/BYE 路由依据") {
            text.contains("Contact: <sip:34020000001320000001@10.0.0.10:5060>\r\n")
        }
        assertTrue("Content-Type") { text.contains("Content-Type: application/sdp\r\n") }
    }

    @Test fun invite200WithoutOptionalHeadersOmitsThem() {
        val invite = SipParser.parse(SipSamples.inviteRealplay.encodeToByteArray()) as SipRequest
        val resp = SipBuilders.buildInvite200WithSdp(
            invite = invite,
            deviceContact = "<sip:device@10.0.0.10:5060>",
            toTag = "tt",
            sdpBody = "v=0\r\n"
            // userAgent / subject 都不传
        )
        val text = resp.toBytes().decodeToString()
        assertTrue { !text.contains("User-Agent:") }
        assertTrue { !text.contains("Subject:") }
        // Date 是无条件加的
        assertTrue { text.contains(Regex("Date: \\w{3}, .* GMT")) }
    }

    // ---------- 10.8 SDP f= ----------

    @Test fun fLineHasH264And720pAndG711a() {
        val spec = SdpAnswer.MediaSpec(
            videoCodec = 2,           // H.264
            resolution = 5,           // 720p
            frameRate = 25,
            rateType = 2,             // VBR
            videoBitrateKbps = 2000,
            audioCodec = 1,           // G.711A
            audioBitrateKbps = 64,
            audioSampleRate = 1       // 8kHz
        )
        assertEquals("f=v/2/5/25/2/2000a/1/64/1", spec.toFLine())
    }

    @Test fun fLineH265And1080pAndAac() {
        val spec = SdpAnswer.MediaSpec(
            videoCodec = 5,           // H.265
            resolution = 6,           // 1080p
            frameRate = 30,
            rateType = 2,
            videoBitrateKbps = 4000,
            audioCodec = 11,          // AAC
            audioBitrateKbps = 32,
            audioSampleRate = 3       // 16kHz
        )
        assertEquals("f=v/5/6/30/2/4000a/11/32/3", spec.toFLine())
    }

    @Test fun fLineWithoutAudioFallsBackToEmptyAudioFields() {
        val spec = SdpAnswer.MediaSpec(
            videoCodec = 2, resolution = 5, frameRate = 25,
            videoBitrateKbps = 2000
            // no audio fields
        )
        // 整个音频段全空,GB28181 § C.2 约定填占位斜杠
        assertEquals("f=v/2/5/25/2/2000a///", spec.toFLine())
    }

    @Test fun playAnswerAppendsFLineWhenSpecProvided() {
        val sdp = SdpAnswer.buildPlayAnswer(
            deviceId = "34020000001320000001",
            localIp = "10.0.0.10",
            localRtpPort = 30000,
            ssrc = "0100000001",
            mediaSpec = SdpAnswer.MediaSpec(
                videoCodec = 2, resolution = 5, frameRate = 25,
                videoBitrateKbps = 2000,
                audioCodec = 1, audioBitrateKbps = 64, audioSampleRate = 1
            )
        )
        // y= 之后才是 f=,顺序按 GB28181 通行用法
        val yIdx = sdp.indexOf("y=0100000001")
        val fIdx = sdp.indexOf("f=v/2/5/25/2/2000a/1/64/1")
        assertTrue("y= 必须在 f= 之前") { yIdx in 0 until fIdx }
    }

    @Test fun playAnswerOmitsFLineWhenSpecNull() {
        val sdp = SdpAnswer.buildPlayAnswer(
            deviceId = "device", localIp = "1.1.1.1",
            localRtpPort = 30000, ssrc = "0100000001"
        )
        assertNull(Regex("f=").find(sdp), "未传 mediaSpec 时不应出现 f= 字段")
    }
}
