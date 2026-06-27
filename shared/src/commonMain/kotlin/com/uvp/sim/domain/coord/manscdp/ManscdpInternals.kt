package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.config.CatalogNode
import com.uvp.sim.config.CatalogNodeType
import com.uvp.sim.config.SimConfig
import com.uvp.sim.domain.ClockOffset
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipDialogIdentityService
import com.uvp.sim.sip.SipOutbox
import kotlinx.datetime.Instant
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
     * 出栈一条 MANSCDP Response MESSAGE。
     *
     * 调用方只需提供:identity service + 出栈 outbox + 报文体 xmlBody。
     * 异常被 catch 并通过 [simEventEmit] 发 TransportError,不再向上抛。
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
            )
            outbox.send(msg).getOrThrow()
            true
        } catch (e: Throwable) {
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
