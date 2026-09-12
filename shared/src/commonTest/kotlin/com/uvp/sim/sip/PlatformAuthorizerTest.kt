package com.uvp.sim.sip

import com.uvp.sim.config.DeviceConfig
import com.uvp.sim.config.GbVersion
import com.uvp.sim.config.ServerConfig
import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.SipEnvelope
import com.uvp.sim.network.TransportType
import com.uvp.sim.network.normalizeHostForm
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
 *
 * ⚠️ 2026-09-12:所有用例都通过 `hostForms` 参数注入[替身解析器][stubForms],
 * **不查真实 DNS**。原因:真实实现会做阻塞式 PTR 查询(`getCanonicalHostName()`),
 * 在 DNS 不可达的环境里会永久挂住(本机实测 Test worker 卡在 `getHostByAddr`)。
 * 真实 DNS 行为由设备实测覆盖,不放进单测。
 */
class PlatformAuthorizerTest {

    private val platformIp = "192.168.10.222"
    private val platformDomain = "3402000000"
    private val platformServerId = "34020000002000000001"

    private fun config(
        allowList: List<String> = emptyList(),
        ip: String = platformIp,
    ) = SimConfig(
        gbVersion = GbVersion.V2022,
        server = ServerConfig(
            ip = ip, port = 5060,
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

    /**
     * 测试替身:替代 [com.uvp.sim.network.hostComparableForms],不查 DNS。
     *
     * @param forward 正向解析结果:主机名 → 数字 IP
     * @param ptr 反向解析结果(PTR):数字 IP → 主机名
     *
     * 未命中的输入只保留**归一化后的原文**,与真实实现"解析失败退回原文"一致。
     * 两个方向都能单独喂,才能精确模拟现场"正向失败、只有 PTR 可用"。
     */
    private fun stubForms(
        forward: Map<String, String> = emptyMap(),
        ptr: Map<String, String> = emptyMap(),
    ): (String) -> Set<String> = { host ->
        val normalized = normalizeHostForm(host)
        if (normalized.isEmpty()) {
            emptySet()
        } else {
            val forms = linkedSetOf(normalized)
            forward[normalized]?.let { forms += normalizeHostForm(it) }
            ptr[normalized]?.let { forms += normalizeHostForm(it) }
            forms
        }
    }

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

    private val noDns = stubForms()

    // ─────────────────────────── INVITE / Playback INVITE ───────────────────────────

    @Test fun invite_legit_platform_from_expected_ip_passes() {
        val env = envelope(buildInvite(), sourceIp = platformIp)
        assertTrue(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(), noDns),
            "合法平台:From domain + sourceIp 都匹配,必须通过"
        )
    }

    @Test fun invite_legit_platform_in_allow_list_passes() {
        // sourceIp 不等于 config.server.ip,但在 allowList 里
        val altIp = "10.0.0.50"
        val env = envelope(buildInvite(), sourceIp = altIp)
        assertTrue(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(
                env, config(allowList = listOf(altIp, "10.0.0.51")), noDns
            ),
            "allowList 里的 IP 也应通过"
        )
    }

    @Test fun invite_forged_from_host_rejected() {
        // SIP From host 是攻击者伪造的域,即便 sourceIp 是合法平台 IP 也应拒绝
        val env = envelope(buildInvite(fromDomain = "evil.example.com"), sourceIp = platformIp)
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(), noDns),
            "From host 错 → 拒绝(SIP 层防御)"
        )
    }

    @Test fun invite_forged_source_ip_rejected() {
        // SIP From host 合法,但 sourceIp 是攻击者 IP(没在 allowList 里)
        val env = envelope(buildInvite(), sourceIp = "10.99.99.99")
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(), noDns),
            "sourceIp 错 → 拒绝(network 层防御)"
        )
    }

    @Test fun invite_allow_list_strictly_rejects_other_sources() {
        // allowList 非空 = 严格模式 — allow list 跟 ip 是 OR 关系,任一命中即过
        val env = envelope(buildInvite(), sourceIp = "10.0.0.99")
        assertFalse(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(allowList = listOf("10.0.0.50")), noDns),
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
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(), noDns),
            "缺 From 头 → 拒绝"
        )
    }

    // ─────────────────────────── MANSCDP MESSAGE / SUBSCRIBE ───────────────────────────

    @Test fun manscdp_legit_platform_passes() {
        val env = envelope(buildMessage(), sourceIp = platformIp)
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(), noDns),
            "MANSCDP:合法平台 sourceIp + From serverId 都匹配,必须通过"
        )
    }

    @Test fun manscdp_forged_source_ip_rejected() {
        val env = envelope(buildMessage(), sourceIp = "10.99.99.99")
        assertFalse(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(), noDns),
            "MANSCDP:sourceIp 不匹配 → 拒绝(直接 drop,不发 403)"
        )
    }

    @Test fun manscdp_forged_server_id_rejected() {
        // sourceIp 合法,但 From userpart 不是 server.serverId
        val env = envelope(buildMessage(fromUser = "99999999992000000000"), sourceIp = platformIp)
        assertFalse(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(), noDns),
            "MANSCDP:From serverId 不匹配 → 拒绝"
        )
    }

    @Test fun manscdp_allow_list_entry_passes() {
        // server.ip = 192.168.10.222,sourceIp 在 allowList(代理 / 旁路)
        val env = envelope(buildMessage(), sourceIp = "10.0.0.50")
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(allowList = listOf("10.0.0.50")), noDns),
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
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(), noDns),
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
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(env, config(), noDns),
            "Response 类型不该走 INVITE guard(本 helper 只校验 request)"
        )
    }

    // ───────────── 地址形式归一化(2026-09-11/12 Catalog 全超时线上故障回归) ─────────────
    //
    // 现场:transport 把 Ktor `InetSocketAddress.hostname` 当 sourceIp,而该值可能是
    // 路由器给平台 IP 的 PTR 名(现场 192.168.126.126 → "Mac"),导致精确字符串比对
    // 永远不中、MANSCDP 被静默丢弃。
    //
    // 用替身把两种 DNS 能力分开喂:
    //  - [ptr_only_source_passes]:正向解析拿不到、只有 PTR 可用 —— 这是 Android 现场
    //    的真实情形(2026-09-12 实测,只做正向归一化的修复无效,logcat 仍是
    //    `丢弃未授权 MESSAGE:sourceIp=Mac`)。
    //  - [forward_resolvable_hostname_passes]:正向可用的一般情形。

    @Test fun ptr_only_source_passes() {
        // 现场还原:平台 IP 192.168.126.126 的 PTR 是 "Mac";设备侧正向解析 "Mac" 失败。
        val env = envelope(buildMessage(), sourceIp = "Mac")
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(
                env,
                config(ip = "192.168.126.126"),
                stubForms(ptr = mapOf("192.168.126.126" to "Mac")),
            ),
            "只有 PTR 可用时也必须判定为同一主机(否则 Catalog 全超时)"
        )
    }

    @Test fun ptr_trailing_dot_and_case_folded() {
        // 路由器 PTR 应答可能是 "Mac."(FQDN 绝对名),Android 侧拿到的是 "Mac"
        val env = envelope(buildMessage(), sourceIp = "Mac")
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(
                env,
                config(ip = "192.168.126.126"),
                stubForms(ptr = mapOf("192.168.126.126" to "Mac.")),
            ),
            "PTR 名的结尾点 / 大小写差异必须折叠后可比"
        )
    }

    @Test fun forward_resolvable_hostname_passes() {
        val env = envelope(buildMessage(), sourceIp = "localhost")
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(
                env,
                config(ip = "127.0.0.1"),
                stubForms(forward = mapOf("localhost" to "127.0.0.1")),
            ),
            "sourceIp 是主机名但正向解析回配置 IP → 必须通过"
        )
    }

    @Test fun invite_uses_same_normalization() {
        val env = envelope(buildInvite(), sourceIp = "Mac")
        assertTrue(
            PlatformAuthorizer.isInviteFromAuthorizedPlatform(
                env,
                config(ip = "192.168.126.126"),
                stubForms(ptr = mapOf("192.168.126.126" to "Mac")),
            ),
            "INVITE 路径同样要按形式集合比对"
        )
    }

    @Test fun allow_list_hostname_entry_passes() {
        val env = envelope(buildMessage(), sourceIp = "Mac")
        assertTrue(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(
                env, config(allowList = listOf("Mac")), noDns
            ),
            "allowList 里写的若是主机名,也要能跟观测值比对成功"
        )
    }

    @Test fun unresolvable_source_hostname_still_rejected() {
        // 归一化绝不能变成"解析不了就放行":解析失败 → 只剩原文 → 仍不匹配 → 拒绝
        val env = envelope(buildMessage(), sourceIp = "no-such-host.invalid")
        assertFalse(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(), noDns),
            "主机名解析失败 → 仍按不匹配拒绝(fail-closed)"
        )
    }

    @Test fun numeric_literal_semantics_unchanged() {
        // 归一化不能放宽普通数字 IP 的语义:非白名单数字 IP 依旧拒绝
        val env = envelope(buildMessage(), sourceIp = "10.99.99.99")
        assertFalse(
            PlatformAuthorizer.isManscdpFromAuthorizedPlatform(env, config(), noDns),
            "非白名单数字 IP → 依旧拒绝(归一化不放大信任面)"
        )
    }
}
