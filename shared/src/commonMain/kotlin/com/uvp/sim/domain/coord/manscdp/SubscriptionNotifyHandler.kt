package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.config.CatalogChangeEvent
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.SubscriptionDialog
import com.uvp.sim.gb28181.AlarmNotify
import com.uvp.sim.gb28181.AlarmPayload
import com.uvp.sim.gb28181.CatalogNotifyBuilder
import com.uvp.sim.gb28181.MobilePositionNotify
import com.uvp.sim.sip.SipBuilders
import com.uvp.sim.sip.SipRequest

/**
 * MANSCDP NOTIFY 扇出 —— 从 [com.uvp.sim.domain.coord.ManscdpRouterImpl] 抽出(cross-review R1 #3)。
 *
 * SUBSCRIBE 链路上所有 NOTIFY(Catalog / MobilePosition / Alarm / MediaStatus)+ 抓拍 MESSAGE
 * NOTIFY 的构造与发送统一收口到本类,连同 3 个 NOTIFY SN 计数器。SubRouter 只发 Response,
 * 不发 NOTIFY;ManscdpRouterImpl 持有本 handler 并在主动上报 / SUBSCRIBE 处理路径调用。
 *
 * 所有依赖经 [ManscdpContext] 注入,跟 SubRouter 同款共享上下文,不再各自重复持有。
 */
internal class SubscriptionNotifyHandler(private val ctx: ManscdpContext) {

    private var notifySn = 0
    private var catalogNotifySn = 0
    private var alarmNotifySn = 0

    /**
     * 递增并返回 position/MediaStatus 共用的 NOTIFY SN。
     *
     * [com.uvp.sim.domain.coord.ManscdpRouterImpl.triggerMediaStatusAbnormal] 自己构造
     * MediaStatus MESSAGE,但 sn 跟 [sendPositionNotify] 共享同一序号空间,故暴露此方法
     * 让主类复用同一计数器,保持拆分前后序号行为一致。
     */
    fun nextNotifySn(): Int {
        notifySn++
        return notifySn
    }

    suspend fun sendSnapshotNotify(xml: String) {
        if (!ctx.stateRegisteredOrInCall()) {
            ctx.simEventEmit(SimEvent.TransportError("snapshot notify: not registered"))
            return
        }
        val id = ctx.identityService.nextMessageNotify()
        val msg = SipBuilders.buildMessage(
            config = ctx.config, cseq = id.cseq.toInt(), callId = id.callId,
            branch = SipBuilders.randomBranch(), fromTag = id.fromTag,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xml,
        )
        try {
            ctx.outbox.send(msg).getOrThrow()
        } catch (e: Throwable) {
            ctx.simEventEmit(SimEvent.TransportError("snapshot notify send: ${e.message}"))
        }
    }

    suspend fun sendAlarmNotify(dialog: SubscriptionDialog, body: String, sn: String) {
        alarmNotifySn++
        val notify = buildNotifyForDialog(dialog, body)
        try {
            ctx.outbox.send(notify).getOrThrow()
            ctx.simEventEmit(SimEvent.AlarmNotifySent(sn = sn, subscriber = dialog.subscriberUri))
        } catch (e: Throwable) {
            ctx.simEventEmit(SimEvent.TransportError("send Alarm NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    suspend fun sendMediaStatusNotifyToSubscriber(dialog: SubscriptionDialog, body: String) {
        val notify = buildNotifyForDialog(dialog, body)
        try {
            ctx.outbox.send(notify).getOrThrow()
        } catch (e: Throwable) {
            ctx.simEventEmit(SimEvent.TransportError("send MediaStatus NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    suspend fun sendCatalogNotify(dialog: SubscriptionDialog) {
        catalogNotifySn++
        val xml = CatalogNotifyBuilder.build(
            deviceId = ctx.config.device.deviceId,
            sn = catalogNotifySn,
            tree = ManscdpInternals.publishableCatalogNodes(ctx.catalogTree.value),
        )
        val notify = buildNotifyForDialog(dialog, xml, includeUserAgent = false)
        try {
            ctx.outbox.send(notify).getOrThrow()
            ctx.simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            ctx.simEventEmit(SimEvent.TransportError("send Catalog NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    suspend fun sendCatalogIncrementalNotify(dialog: SubscriptionDialog, events: List<CatalogChangeEvent>) {
        catalogNotifySn++
        val xml = CatalogNotifyBuilder.buildIncremental(
            deviceId = ctx.config.device.deviceId,
            sn = catalogNotifySn,
            events = events,
        )
        val notify = buildNotifyForDialog(dialog, xml, includeUserAgent = false)
        try {
            ctx.outbox.send(notify).getOrThrow()
            ctx.simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            ctx.simEventEmit(SimEvent.TransportError("send Catalog incremental NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    suspend fun sendCatalogStatusOnlyNotify(dialog: SubscriptionDialog, channelId: String, online: Boolean) {
        catalogNotifySn++
        val xml = CatalogNotifyBuilder.buildStatusOnly(
            deviceId = ctx.config.device.deviceId,
            sn = catalogNotifySn,
            channelId = channelId,
            online = online,
        )
        val notify = buildNotifyForDialog(dialog, xml, includeUserAgent = false)
        try {
            ctx.outbox.send(notify).getOrThrow()
            ctx.simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = catalogNotifySn))
        } catch (e: Throwable) {
            ctx.simEventEmit(SimEvent.TransportError("send Catalog status-only NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    suspend fun sendPositionNotify(dialog: SubscriptionDialog) {
        notifySn++
        val fix = ctx.mockGps.next()
        val xml = MobilePositionNotify.build(
            deviceId = ctx.config.device.deviceId,
            sn = notifySn,
            point = fix.point,
            speed = fix.speed,
            direction = fix.direction,
            altitude = fix.altitude,
            fixTimeMs = fix.fixTimeMs,
        )
        val notify = buildNotifyForDialog(dialog, xml)
        try {
            ctx.outbox.send(notify).getOrThrow()
            ctx.simEventEmit(SimEvent.NotifySent(kind = dialog.kind, sn = notifySn))
        } catch (e: Throwable) {
            ctx.simEventEmit(SimEvent.TransportError("send NOTIFY: ${e::class.simpleName}: ${e.message}"))
        }
    }

    /** SUBSCRIBE 路径上所有 NOTIFY 共用的报文构造,统一 subscription-state / Via / UA。 */
    private fun buildNotifyForDialog(
        dialog: SubscriptionDialog,
        xmlBody: String,
        includeUserAgent: Boolean = true,
    ): SipRequest {
        val notifyCseq = dialog.cseqNotify + 1
        val remaining = dialog.remainingSeconds
        val ssValue = if (remaining > 0) "active;expires=$remaining" else "terminated"
        return SipBuilders.buildNotify(
            subscriberUri = dialog.subscriberUri,
            callId = dialog.callId,
            fromTag = dialog.toTag,
            toTag = dialog.fromTag,
            event = "presence",
            subscriptionState = ssValue,
            cseq = notifyCseq,
            xmlBody = xmlBody,
            localIp = ctx.localIp,
            localPort = ctx.localPort,
            transport = ctx.config.transport.name,
            userAgent = if (includeUserAgent) ctx.config.userAgent else null,
        )
    }

    /** AlarmCmd 复位:把"复位"打包成 AlarmNotify body 给所有 Alarm 订阅者发 NOTIFY。 */
    suspend fun pushAlarmResetNotify(by: String?) {
        val dialogs = ctx.subscriptionRegistry.dialogsByKind("Alarm")
        if (dialogs.isEmpty()) return
        val sn = ctx.identityService.nextMessageNotify().cseq.toString()
        val resetPayload = AlarmPayload(
            deviceId = ctx.config.device.alarmChannelId,
            description = "报警已复位 (AlarmCmd by ${by ?: "platform"})",
        )
        val body = AlarmNotify.buildAlarm(ctx.config, sn, resetPayload)
        for (d in dialogs) {
            val updated = ctx.subscriptionRegistry.bumpNotify(d.callId) ?: continue
            sendAlarmNotify(updated, body, sn)
        }
    }
}
