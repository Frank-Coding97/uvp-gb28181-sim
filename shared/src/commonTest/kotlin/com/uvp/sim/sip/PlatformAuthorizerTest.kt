package com.uvp.sim.sip

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.SipEnvelope
import com.uvp.sim.network.TransportType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wave 7B P0-2 + P1-3:[PlatformAuthorizer] 单元测试。
 *
 * 验证三种 envelope 来源校验:
 *  - 实时 / Playback INVITE:SIP From host + network sourceIp 双重校验
 *  - MANSCDP MESSAGE / SUBSCRIBE:network sourceIp + From userpart 双重校验
 *
 * codex 第二轮 audit 关键引用:
 *   "不同 coordinator 各自判断身份,实时 INVITE、Playback、MANSCDP、Broadcast
 *    的校验强度不一致。"
 */
class PlatformAuthorizerTest {

    private val platformIp = "192.168.10.222"
    private val platformDomain = "3402000000"
    private val platformServerId = "34020000002000000001"

    private fun config(allowList: List<String> = emptyList()) = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = platformIp, port = 5060,
            serverId = platformServerId, domain = platformDomain,
            allowList = allowList,
        ),
        device = DeviceConfig(
            deviceId = "34020000001110000001",
            videoChannelId = "34020000001320000001",
            alarmChannelId = "34020000001340000001",
            username = "34020000001110000001",
            password = "p",
        ),
        transport = TransportType.UDP,
    )

    private fun buildInvite(fromDomain: String = platformDomain, fromUser: String = platformServerId): SipRequest =
        SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:34020000001320000001@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=z9hG4bK-inv"),
                SipMessage.Header(SipHeader.FROM, "<sip:$fromUser@$fromDomain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001320000001@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, "inv-1@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
            ),
        )

    private fun buildMessage(fromUser: String = platformServerId): SipRequest =
        SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=z9hG4bK-msg"),
                SipMessage.Header(SipHeader.FROM, "<sip:$fromUser@$platformDomain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:34020000001110000001@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, "msg-1@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
                SipMessage.Header("Content-Type", "application/MANSCDP+xml"),
            ),
        )

    private fun envelope(req: SipRequest, sourceIp: String = platformIp, sourcePort: Int = 5060): SipEnvelope =
        SipEnvelope(message = req, sourceIp = sourceIp, sourcePort = sourcePort, transport = TransportType.UDP)

    // ─────────────────────────── INVITE / Playback INVITE ───────────────────────────

    @Test fun invite_legit_platform_from_expected_ip_passes() {
        val env = envelope(buildInvite(), sourceIp = platformIp)
        assertTrue(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config()),
            "合法平台:From domain + sourceIp 都匹配,必须通过"
        )
    }

    @Test fun invite_legit_platform_in_allow_list_passes() {
        // sourceIp 不等于 config.server.ip,但在 allowList 里
        val altIp = "10.0.0.50"
        val env = envelope(buildInvite(), sourceIp = altIp)
        assertTrue(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(allowList = listOf(altIp, "10.0.0.51"))),
            "allowList 里的 IP 也应通过"
        )
    }

    @Test fun invite_forged_from_host_rejected() {
        // SIP From host 是攻击者伪造的域,即便 sourceIp 是合法平台 IP 也应拒绝
        val env = envelope(buildInvite(fromDomain = "evil.example.com"), sourceIp = platformIp)
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config()),
            "From host 错 → 拒绝(SIP 层防御)"
        )
    }

    @Test fun invite_forged_source_ip_rejected() {
        // SIP From host 合法,但 sourceIp 是攻击者 IP(没在 allowList 里)
        val env = envelope(buildInvite(), sourceIp = "10.99.99.99")
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config()),
            "sourceIp 错 → 拒绝(network 层防御)"
        )
    }

    @Test fun invite_allow_list_strictly_rejects_other_sources() {
        // allowList 非空 = 严格模式 — config.server.ip 不在 allowList 时,即便等于 sourceIp 也通过
        // (allow list 跟 ip 是 OR 关系,任一命中即过)
        val env = envelope(buildInvite(), sourceIp = "10.0.0.99")
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(allowList = listOf("10.0.0.50"))),
            "allowList 非空 + sourceIp 不命中 → 拒绝"
        )
    }

    @Test fun invite_no_from_header_rejected() {
        val req = SipRequest(
            method = SipMethod.INVITE,
            requestUri = "sip:dev@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=b"),
                SipMessage.Header(SipHeader.TO, "<sip:dev@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, "no-from@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
            ),
        )
        val env = envelope(req)
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config()),
            "缺 From 头 → 拒绝"
        )
    }

    // ─────────────────────────── MANSCDP MESSAGE / SUBSCRIBE ───────────────────────────

    @Test fun manscdp_legit_platform_passes() {
        val env = envelope(buildMessage(), sourceIp = platformIp)
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config()),
            "MANSCDP:合法平台 sourceIp + From serverId 都匹配,必须通过"
        )
    }

    @Test fun manscdp_forged_source_ip_rejected() {
        val env = envelope(buildMessage(), sourceIp = "10.99.99.99")
        assertFalse(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config()),
            "MANSCDP:sourceIp 不匹配 → 拒绝(直接 drop,不发 403)"
        )
    }

    @Test fun manscdp_forged_server_id_rejected() {
        // sourceIp 合法,但 From userpart 不是 server.serverId
        val env = envelope(buildMessage(fromUser = "99999999992000000000"), sourceIp = platformIp)
        assertFalse(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config()),
            "MANSCDP:From serverId 不匹配 → 拒绝"
        )
    }

    @Test fun manscdp_allow_list_entry_passes() {
        // server.ip = 192.168.10.222,sourceIp 在 allowList(代理 / 旁路)
        val env = envelope(buildMessage(), sourceIp = "10.0.0.50")
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(allowList = listOf("10.0.0.50"))),
            "MANSCDP:allowList 命中 → 通过"
        )
    }

    @Test fun manscdp_no_from_header_rejected() {
        val req = SipRequest(
            method = SipMethod.MESSAGE,
            requestUri = "sip:34020000001110000001@$platformDomain",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=b"),
                SipMessage.Header(SipHeader.TO, "<sip:dev@$platformDomain>"),
                SipMessage.Header(SipHeader.CALL_ID, "no-from@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 MESSAGE"),
            ),
        )
        val env = envelope(req)
        assertFalse(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config()),
            "MANSCDP:缺 From 头 → 拒绝"
        )
    }

    // ─────────────────────────── 边界 / response 类型 ───────────────────────────

    @Test fun response_envelope_is_rejected_for_invite() {
        val resp = SipResponse(
            statusCode = 200, reasonPhrase = "OK",
            headers = listOf(
                SipMessage.Header(SipHeader.VIA, "SIP/2.0/UDP $platformIp:5060;branch=b"),
                SipMessage.Header(SipHeader.FROM, "<sip:$platformServerId@$platformDomain>;tag=plat"),
                SipMessage.Header(SipHeader.TO, "<sip:dev@$platformDomain>;tag=device"),
                SipMessage.Header(SipHeader.CALL_ID, "resp-1@plat"),
                SipMessage.Header(SipHeader.CSEQ, "1 INVITE"),
            ),
        )
        val env = SipEnvelope(resp, platformIp, 5060, TransportType.UDP)
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config()),
            "Response 类型不该走 INVITE guard(本 helper 只校验 request)"
        )
    }
}
