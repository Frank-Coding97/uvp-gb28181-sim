package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.coord.BroadcastInvoker
import com.uvp.sim.gb28181.BroadcastQuery
import com.uvp.sim.gb28181.BroadcastResponse
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

/**
 * 语音广播 MANSCDP 子路由(Wave 4 PR-D / P2-1)。
 *
 * CmdType 范围:
 *  - Broadcast → 校验 TargetID 归属 + busy 检查 → 应答 OK/ERROR → 触发 [BroadcastInvoker] 主动 INVITE 平台
 *
 * 注:Broadcast 应答里发的是 MANSCDP MESSAGE,但后续 INVITE 起会由 BroadcastCoordinator 负责
 * (本路由通过 [BroadcastInvoker.fireBroadcastInvite] 反向调,跨 Coord 契约见 [CrossCoordinatorContracts])。
 *
 * triggerMediaStatusAbnormal / 媒体上传通知归 [ManscdpRouterImpl] 的主动发起路径(非 query 应答),
 * 不进入 SubRouter dispatch。
 */
internal class BroadcastSubRouter(
    private val ctx: ManscdpContext,
    private val broadcastInvoker: BroadcastInvoker,
    private val broadcastBusy: () -> Boolean,
) : ManscdpSubRouter {

    override fun accepts(cmdType: String): Boolean = cmdType == "Broadcast"

    override suspend fun handle(cmdType: String, xml: String, fromUri: String?): Boolean {
        if (cmdType != "Broadcast") return false
        handleBroadcast(xml, fromUri)
        return true
    }

    private suspend fun handleBroadcast(xml: String, fromUri: String?) {
        val query = BroadcastQuery.parse(xml)
        val sn = query.sn ?: "0"
        val myId = ctx.config.device.deviceId

        // 并发拒绝(spec Q1):已持有一路 broadcast → ERROR busy,不发 INVITE
        if (broadcastBusy()) {
            sendBroadcastResponseMessage(
                BroadcastResponse.build(
                    deviceId = myId, sn = sn,
                    result = BroadcastResponse.Result.ERROR,
                    reason = "busy",
                )
            )
            SystemLogger.emit(LogLevel.Warning, LogTag.Network, "已有语音广播进行中 → 拒绝第二路(busy)")
            return
        }

        val targetId = query.targetId
        if (targetId.isNullOrBlank() ||
            !ManscdpInternals.isOwnedBroadcastTarget(ctx.config, ctx.catalogTree.value, targetId)
        ) {
            sendBroadcastResponseMessage(
                BroadcastResponse.build(
                    deviceId = myId, sn = sn,
                    result = BroadcastResponse.Result.ERROR,
                    reason = "target mismatch",
                )
            )
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "语音广播 TargetID 不属于本设备: 收到 '$targetId'(deviceId=$myId)→ ERROR",
            )
            return
        }
        sendBroadcastResponseMessage(
            BroadcastResponse.build(
                deviceId = myId, sn = sn,
                result = BroadcastResponse.Result.OK,
            )
        )
        val sourceId = query.sourceId ?: ""
        ctx.simEventEmit(SimEvent.BroadcastReceived(sourceId = sourceId, targetId = targetId))
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "收到语音广播请求 source=$sourceId target=$targetId → 已回 OK,主动 INVITE 平台",
        )
        val platformUri = "sip:$sourceId@${ctx.config.server.domain}"
        broadcastInvoker.fireBroadcastInvite(sourceId, platformUri, targetId)
    }

    private suspend fun sendBroadcastResponseMessage(xmlBody: String) {
        ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "Broadcast Response",
            simEventEmit = ctx.simEventEmit,
        )
    }
}
