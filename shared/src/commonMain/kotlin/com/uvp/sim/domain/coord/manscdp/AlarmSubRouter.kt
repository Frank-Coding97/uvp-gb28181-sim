package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.gb28181.AlarmStatusResponse
import com.uvp.sim.gb28181.AlarmStatusSnapshot
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger

/**
 * 告警链路 MANSCDP 子路由(Wave 4 PR-D / P2-1)。
 *
 * CmdType 范围:
 *  - AlarmStatus → AlarmStatusResponse(查询当前布撤防 + 报警状态)
 *
 * 注:Alarm 订阅(SUBSCRIBE Event: Alarm)和主动报警上报(reportAlarm / localResetAlarm /
 * pushAlarmResetNotify)是 [ManscdpRouterImpl] 的主动发起路径,不走 SubRouter dispatch;
 * SubRouter 只负责"平台 → 设备"的 query 应答路径,跟主动路径区分清晰。
 *
 * AlarmNotify 主动报警是设备发出,触发器在 [ManscdpRouterImpl.reportAlarm](事件驱动 / UI 触发);
 * AlarmSubscribe / AlarmNotify 订阅维护在 SubscriptionRegistry,不算 query 路径。
 */
internal class AlarmSubRouter(
    private val ctx: ManscdpContext,
) : ManscdpSubRouter {

    override fun accepts(cmdType: String): Boolean = cmdType == "AlarmStatus"

    override suspend fun handle(cmdType: String, xml: String, fromUri: String?): Boolean {
        if (cmdType != "AlarmStatus") return false
        val sn = ManscdpParser.sn(xml) ?: "0"
        sendAlarmStatusResponse(sn)
        return true
    }

    private suspend fun sendAlarmStatusResponse(sn: String) {
        val snapshot = AlarmStatusSnapshot(
            alarming = ctx.deviceControlState.value.isAlarming,
            alarmChannelId = ctx.config.device.alarmChannelId,
        )
        val xmlBody = AlarmStatusResponse.build(ctx.config, sn, snapshot)
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "AlarmStatus response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 AlarmStatus → 已应答 sn=$sn alarm=${snapshot.alarming}"
        )
    }
}
