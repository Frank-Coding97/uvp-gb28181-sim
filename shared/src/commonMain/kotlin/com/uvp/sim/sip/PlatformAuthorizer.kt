package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.SipEnvelope

/**
 * 平台来源授权统一 helper(Wave 7B P0-2 / P1-3,codex 第二轮 audit §2)。
 *
 * 引用 codex 原文:
 *   "不同 coordinator 各自判断身份,实时 INVITE、Playback、MANSCDP、Broadcast 的
 *    校验强度不一致。第二轮重构建议先做安全边界收敛,再拆 coordinator 内部细节。
 *    否则每个业务分支都会继续复制一小段不完整的 guard。"
 *
 * 本对象统一三个安全边界,所有 Coordinator 共用同一份判定逻辑:
 *  1. [isInviteFromAuthorizedPlatform]:实时 INVITE / Playback INVITE
 *     · SIP 层:From host 必须等于 config.server.domain(H-1,Wave 1 留下来的)
 *     · 网络层:envelope.sourceIp 必须命中 config.server.ip 或 allowList(P0-2 增强)
 *  2. [isManscdpFromAuthorizedPlatform]:MESSAGE / SUBSCRIBE 入口
 *     · 网络层:envelope.sourceIp 必须命中 server.ip + allowList(P1-3)
 *     · SIP 层:From URI userpart 提取 serverId 跟 config.server.serverId 比对
 *
 * **不发 403 / 不发其他 SIP 响应** — 调用方决定是 reject 还是 drop。
 */
internal object PlatformAuthorizer {

    /**
     * H-1 + P0-2:INVITE 必须来自登记的平台,网络层 + SIP 层双重校验。
     *
     * SIP 层(H-1,Wave 1 落地):From host = config.server.domain
     * 网络层(P0-2,Wave 7B 新增):envelope.sourceIp ∈ {config.server.ip} ∪ allowList
     *
     * 关键 codex 引用:
     *   "Playback INVITE 路径不复用 H-1 isInviteFromAuthorizedPlatform"
     *
     * 通过 = 同时满足两个条件;不通过 = 任一条件失败。
     *
     * @param envelope transport 上抛的 SipEnvelope(携带真实来源 IP/port)
     * @param expectedDomain config.server.domain
     * @param expectedServerIp config.server.ip
     * @param allowList config.server.allowList(M-6 Wave 6 已落,空 list = 不强制)
     */
    fun isInviteFromAuthorizedPlatform(
        envelope: SipEnvelope,
        expectedDomain: String,
        expectedServerIp: String,
        allowList: List<String> = emptyList(),
    ): Boolean {
        val request = envelope.message as? SipRequest ?: return false
        // ── SIP 层:From host ──
        val fromHeader = request.fromHeader() ?: return false
        val fromUri = SipHeaderHelpers.parseUri(fromHeader)
        val fromHost = parseUriHost(fromUri)
        if (fromHost != expectedDomain) return false
        // ── 网络层:envelope.sourceIp ──
        return isSourceIpAllowed(envelope.sourceIp, expectedServerIp, allowList)
    }

    /** [isInviteFromAuthorizedPlatform] 的 [SimConfig] 便捷重载。 */
    fun isInviteFromAuthorizedPlatform(envelope: SipEnvelope, config: SimConfig): Boolean =
        isInviteFromAuthorizedPlatform(
            envelope = envelope,
            expectedDomain = config.server.domain,
            expectedServerIp = config.server.ip,
            allowList = config.server.allowList,
        )

    /**
     * P1-3:MANSCDP MESSAGE / SUBSCRIBE 入口业务级来源授权。
     *
     * 关键 codex 引用:
     *   "未授权 MESSAGE/SUBSCRIBE 不应先回 200 再忽略,应返回 403 或直接丢弃"
     *
     * 本 helper 决定"是否授权",**调用方负责直接 drop(不发 200 / 403)**避免暴露
     * 设备存在(reconnaissance 防御)。
     *
     * 校验:
     *  - 网络层:envelope.sourceIp ∈ {server.ip} ∪ allowList
     *  - SIP 层:From URI 提取 user(serverId),必须等于 config.server.serverId
     */
    fun isManscdpFromAuthorizedPlatform(envelope: SipEnvelope, config: SimConfig): Boolean {
        val request = envelope.message as? SipRequest ?: return false
        // ── 网络层 ──
        if (!isSourceIpAllowed(envelope.sourceIp, config.server.ip, config.server.allowList)) {
            return false
        }
        // ── SIP 层:From URI 的 user 段 = serverId ──
        val fromHeader = request.fromHeader() ?: return false
        val fromUri = SipHeaderHelpers.parseUri(fromHeader)
        val fromUser = SipHeaderHelpers.parseUriUser(fromUri, fallback = "")
        return fromUser == config.server.serverId
    }

    /**
     * 通用 source IP 匹配:命中 expectedServerIp 或 allowList 任一条目即通过。
     *
     * 跟 [com.uvp.sim.network.ServerAllowList.enforce] 配对:那是出栈 connect 前校验,
     * 这里是入栈 envelope.sourceIp 校验。
     *
     * 空 allowList 时只校验 expectedServerIp(老配置兼容,跟 ServerAllowList 同语义)。
     */
    private fun isSourceIpAllowed(
        sourceIp: String,
        expectedServerIp: String,
        allowList: List<String>,
    ): Boolean {
        // 优先匹配 expectedServerIp 字面量(命中即过)
        if (sourceIp == expectedServerIp) return true
        // allowList 空 = 不强制网络层校验,仅靠 expectedServerIp 校验 → 已 fail
        if (allowList.isEmpty()) return false
        // allowList 非空 = 严格白名单模式,sourceIp 必须命中其中一项
        return allowList.any { it == sourceIp }
    }

    /** 从 `sip:user@host[:port][;params]` 提取 host 段。 */
    private fun parseUriHost(uri: String): String {
        val afterAt = uri.substringAfter("sip:", uri).substringAfter('@', "")
        return afterAt.substringBefore(':').substringBefore(';').substringBefore('>').trim()
    }
}
