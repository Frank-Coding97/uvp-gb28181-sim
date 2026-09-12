package com.uvp.sim.sip

import com.uvp.sim.config.SimConfig
import com.uvp.sim.network.SipEnvelope
import com.uvp.sim.network.hostComparableForms

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
     * @param hostForms 地址形式展开函数。默认走 [hostComparableForms] —— 该函数
     *   **只读缓存、不做 DNS**,因此本对象可以安全地在主线程被调用(Android 上
     *   入向消息就在 `Dispatchers.Main` 处理)。缓存的填充由 transport 层的
     *   `primeHostForms` 在后台线程完成。单测注入替身(详见该函数 KDoc)。
     */
    fun isInviteFromAuthorizedPlatform(
        envelope: SipEnvelope,
        expectedDomain: String,
        expectedServerIp: String,
        allowList: List<String> = emptyList(),
        hostForms: (String) -> Set<String> = ::hostComparableForms,
    ): Boolean {
        val request = envelope.message as? SipRequest ?: return false
        // ── SIP 层:From host ──
        val fromHeader = request.fromHeader() ?: return false
        val fromUri = SipHeaderHelpers.parseUri(fromHeader)
        val fromHost = parseUriHost(fromUri)
        if (fromHost != expectedDomain) return false
        // ── 网络层:envelope.sourceIp ──
        return isSourceIpAllowed(envelope.sourceIp, expectedServerIp, allowList, hostForms)
    }

    /** [isInviteFromAuthorizedPlatform] 的 [SimConfig] 便捷重载。 */
    fun isInviteFromAuthorizedPlatform(
        envelope: SipEnvelope,
        config: SimConfig,
        hostForms: (String) -> Set<String> = ::hostComparableForms,
    ): Boolean =
        isInviteFromAuthorizedPlatform(
            envelope = envelope,
            expectedDomain = config.server.domain,
            expectedServerIp = config.server.ip,
            allowList = config.server.allowList,
            hostForms = hostForms,
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
    fun isManscdpFromAuthorizedPlatform(
        envelope: SipEnvelope,
        config: SimConfig,
        hostForms: (String) -> Set<String> = ::hostComparableForms,
    ): Boolean {
        val request = envelope.message as? SipRequest ?: return false
        // ── 网络层 ──
        if (!isSourceIpAllowed(envelope.sourceIp, config.server.ip, config.server.allowList, hostForms)) {
            return false
        }
        // ── SIP 层:From URI 的 user 段 = serverId ──
        val fromHeader = request.fromHeader() ?: return false
        val fromUri = SipHeaderHelpers.parseUri(fromHeader)
        val fromUser = SipHeaderHelpers.parseUriUser(fromUri, fallback = "")
        return fromUser == config.server.serverId
    }

    /**
     * cross-review R1 #1(CRITICAL/security):SIP 响应来源校验。
     *
     * 引用 codex 原文:
     *   "广播 INVITE 的 2xx 响应只按 Call-ID 匹配,没有在进入 handleBroadcastInviteResponse
     *    前校验来源 IP、远端 tag 或已知对端。攻击者只需伪造匹配 Call-ID 的 2xx,
     *    就能控制 SDP 中的 RTP 地址,触发 ACK、状态切换和到攻击者地址的 TCP/RTP 连接。"
     *
     * Response 里没有 From 头的对端身份保证(平台可能重写 host),只能靠传输层 sourceIp。
     * SIP 层的 dialog identity(To tag / CSeq)由调用方在拿到响应后再校验。
     */
    fun isResponseFromAuthorizedPlatform(
        envelope: SipEnvelope,
        config: SimConfig,
        hostForms: (String) -> Set<String> = ::hostComparableForms,
    ): Boolean {
        if (envelope.message !is SipResponse) return false
        return isSourceIpAllowed(envelope.sourceIp, config.server.ip, config.server.allowList, hostForms)
    }

    /**
     * 通用 source IP 匹配:命中 expectedServerIp 或 allowList 任一条目即通过。
     *
     * 跟 [com.uvp.sim.network.ServerAllowList.enforce] 配对:那是出栈 connect 前校验,
     * 这里是入栈 envelope.sourceIp 校验。
     *
     * 空 allowList 时只校验 expectedServerIp(老配置兼容,跟 ServerAllowList 同语义)。
     *
     * ⚠️ 2026-09-11/12:两侧都要先展开成 [hostComparableForms] 再比。
     * transport 上报的 `envelope.sourceIp` 可能是**反向 DNS 主机名**而非 IP 字面量
     * (Ktor `InetSocketAddress.hostname` 会查 PTR;现场路由器把 192.168.126.126 解成
     * `Mac`),直接字符串比对会永远不中,导致 MANSCDP 被静默丢弃、Catalog 全超时。
     *
     * 只看正向解析不够:`"Mac"` 这类单标签名在 Android App 进程内可能解析失败
     * (2026-09-12 实测,归一化静默失效、故障复现),所以比较的是**形式集合**
     * (原文 / 正向 IP / PTR 名,见 [hostComparableForms]),任一侧任一形式相等即通过。
     * 数字字面量仍保持精确比对语义 → 不放宽原有信任面。
     *
     * ⚠️ 形式展开会查 DNS(含阻塞式 PTR),因此本函数是**冷路径专用**:
     * 只用于入向 MESSAGE / SUBSCRIBE / INVITE / 响应校验,不要放进逐包处理。
     */
    private fun isSourceIpAllowed(
        sourceIp: String,
        expectedServerIp: String,
        allowList: List<String>,
        hostForms: (String) -> Set<String>,
    ): Boolean {
        // 快路径:字面量完全一致(含空白差异)→ 不触发任何 DNS
        if (sourceIp.trim() == expectedServerIp.trim()) return true
        val observed = hostForms(sourceIp)
        // 空串 / 无法形成任何可比形式 → fail-closed
        if (observed.isEmpty()) return false
        if (observed.any { it in hostForms(expectedServerIp) }) return true
        // allowList 空 = 不强制网络层校验,仅靠 expectedServerIp 校验 → 已 fail
        if (allowList.isEmpty()) return false
        // allowList 非空 = 严格白名单模式,sourceIp 必须命中其中一项
        return allowList.any { entry -> observed.any { it in hostForms(entry) } }
    }

    /** 从 `sip:user@host[:port][;params]` 提取 host 段。 */
    private fun parseUriHost(uri: String): String {
        val afterAt = uri.substringAfter("sip:", uri).substringAfter('@', "")
        return afterAt.substringBefore(':').substringBefore(';').substringBefore('>').trim()
    }
}
