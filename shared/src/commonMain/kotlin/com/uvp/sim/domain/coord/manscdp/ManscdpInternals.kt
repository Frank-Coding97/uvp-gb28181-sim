package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.gb28181.SignalingCharset
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipDialogIdentityService
import com.uvp.sim.sip.SipOutbox
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * MANSCDP SubRouter 共用的 helper(Wave 4 PR-D / P2-1)。
 *
 * 装杂的工具方法:
 *  - [sendMansMessage]:构 outbound MESSAGE(取 identity → buildMessage → outbox.send)
 *  - [currentLocalIso]:DeviceStatus / MobilePosition 用的 ISO 8601 时间戳(走 clockOffset)
 *  - [publishableCatalogNodes]:过滤根 Device 自指节点
 *  - [isOwnedBroadcastTarget]:Broadcast TargetID 归属判断
 *
 * 抽出来避免 4 个 SubRouter 各自重复 `nextMessageNotify + buildMessage + outbox.send + 异常 emit`
 * 的 12 行模板。
 */
internal object ManscdpInternals {

    /**
     * UDP 上**整条报文**(SIP 头 + 正文)的字节上限。
     *
     * ⛔ 别按"我这台机器 MTU 1500 就能发 1400"来定:设备的 SIP 走 VPN / 移动网络，实际可用
     * 载荷经常只有 1200 上下，而且**偶发**——同一份 2.5KB 应答可能一次发得出去、下一次连
     * 第一个分片都上不了线(2026-10-24 EasyGBS 现场实测，平台侧 tcpdump 一个分片都看不到，
     * 而同一时刻 371 字节的 200 OK 次次正常)。
     * 1200 是 SIP 界通行的安全值，绝大多数内网 / VPN / 4G 链路都不用分片。
     * TCP 信令有流式重组，不需要切。
     */
    const val UDP_MAX_DATAGRAM_BYTES: Int = 1200

    /**
     * 预留的 SIP 头长度 —— ⛔ 这个容易漏(2026-10-25 踩过)。
     *
     * 分包预算如果只按"正文 ≤ N"来算,织出来的包是"N + SIP 头"那么大。实测一条 MANSCDP
     * MESSAGE 的头(Via / From / To / Call-ID / CSeq / Content-Type / Content-Length /
     * User-Agent / Max-Forwards …)约 420B。当时正文按 1200 切,实际发出去是 1369B;
     * 通道名再长一点正文吃到 1200,整包就 1620B 直接撞 IP 分片 —— 又回到原故障。
     * 取 480 留 60B 余量(设备 ID / Call-ID 位数变化)。
     */
    const val SIP_HEADER_RESERVE_BYTES: Int = 480

    /**
     * MANSCDP 正文可用的字节预算 = 整包上限 − SIP 头预留。
     *
     * 注意这是**目标值不是硬保证**:单个 Item 本身超过预算时只能独占一包(不能再拆),
     * 所以实际包长下限由单条通道的字段长度决定。
     */
    const val MANSCDP_BODY_MAX_BYTES: Int = UDP_MAX_DATAGRAM_BYTES - SIP_HEADER_RESERVE_BYTES

    /** 按传输方式给出正文分包的字节预算：UDP 有上限，TCP 不限(返回 null)。 */
    fun maxDatagramBytesFor(config: SimConfig): Int? =
        if (config.transport.name == "UDP") MANSCDP_BODY_MAX_BYTES else null

    /**
     * 出栈一条 MANSCDP Response MESSAGE。
     *
     * 调用方只需提供:identity service + 出栈 outbox + 报文体 xmlBody。
     * 异常被 catch 并通过 [simEventEmit] 发 TransportError,不再向上抛。
     *
     * [charset] **必传**,由调用方按 `ctx.effectiveGbVersion` 取(`SignalingCharset.of(...)`)——
     * 这里刻意不设默认值:有效版本 = min(本机, 平台),只有 SubRouter 层的 ctx 才知道,
     * 在本函数里兜底成 config.gbVersion 会把"对面是 2016"这一路悄悄漏掉。
     *
     * @return true=成功,false=发送失败(已 emit TransportError)
     */
    suspend fun sendMansMessage(
        config: SimConfig,
        outbox: SipOutbox,
        identityService: SipDialogIdentityService,
        localIp: String,
        localPort: Int,
        xmlBody: String,
        errorLabel: String,
        charset: SignalingCharset,
        simEventEmit: suspend (SimEvent) -> Unit,
    ): Boolean {
        return try {
            val id = identityService.nextMessageNotify()
            val msg = SipBuilders.buildMessage(
                config = config,
                cseq = id.cseq.toInt(),
                callId = id.callId,
                branch = SipBuilders.randomBranch(),
                fromTag = id.fromTag,
                localIp = localIp,
                localPort = localPort,
                xmlBody = xmlBody,
                charset = charset,
            )
            outbox.send(msg).getOrThrow()
            true
        } catch (e: Throwable) {
            // ⛔ 2026-10-24 现场:这里只发 SimEvent(UI 里一行 ⚠ ERR),**不进系统日志/Logcat**。
            //    结果是一条 MANSCDP 应答彻底发不出去,而设备侧看上去"已处理",排查全靠抓包。
            //    发送失败必须同时落到系统日志,并且把"多大、发给谁"带出来 ——
            //    UDP 上这类失败九成是报文超长(见 CatalogSubRouter.UDP_MAX_DATAGRAM_BYTES)。
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "发送 $errorLabel 失败: ${e::class.simpleName}: ${e.message}",
                detail = "报文 ${xmlBody.encodeToByteArray().size}B(字符 ${xmlBody.length}) " +
                    "→ ${config.server.ip}:${config.server.port} 传输=${config.transport.name};" +
                    "UDP 下超过链路上限会整体发不出去,需要按字节拆包",
            )
            simEventEmit(SimEvent.TransportError("send $errorLabel: ${e.message}"))
            false
        }
    }

    /** DeviceStatus / MobilePosition / Date 头用的 ISO 8601 本地时戳(走 clockOffset 校时)。 */
    fun currentLocalIso(clockOffsetProvider: () -> ClockOffset): String {
        val ms = clockOffsetProvider().adjustedNowMs()
        val now = Instant.fromEpochMilliseconds(ms)
        val tz = TimeZone.currentSystemDefault()
        val ldt = now.toLocalDateTime(tz)
        return buildString {
            append(ldt.year.toString().padStart(4, '0'))
            append('-')
            append(ldt.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(ldt.dayOfMonth.toString().padStart(2, '0'))
            append('T')
            append(ldt.hour.toString().padStart(2, '0'))
            append(':')
            append(ldt.minute.toString().padStart(2, '0'))
            append(':')
            append(ldt.second.toString().padStart(2, '0'))
        }
    }

    /** Catalog NOTIFY / Response 用的节点列表 — 滤掉根 Device 自指节点(parentId == id)。 */
    fun publishableCatalogNodes(tree: List<CatalogNode>): List<CatalogNode> =
        tree.filterNot { it.type == CatalogNodeType.Device && it.parentId == it.id }

    /** Broadcast TargetID 归属判断:是否属于本设备的 deviceId / 视频通道 / 前置通道 / 报警通道 / catalog 树。 */
    fun isOwnedBroadcastTarget(config: SimConfig, tree: List<CatalogNode>, targetId: String): Boolean {
        val d = config.device
        if (targetId == d.deviceId) return true
        if (targetId == d.videoChannelId || targetId == d.frontChannelId || targetId == d.alarmChannelId) return true
        return tree.any { it.id == targetId }
    }
}
