package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.gb28181.CatalogResponse
import com.uvp.sim.gb28181.ConfigDownloadResponse
import com.uvp.sim.gb28181.DeviceInfoResponse
import com.uvp.sim.gb28181.DeviceStatusResponse
import com.uvp.sim.gb28181.DeviceStatusSnapshot
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.MobilePositionResponse
import com.uvp.sim.gb28181.RecordInfoNotify
import com.uvp.sim.gb28181.RecordInfoQuery
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordingService

/**
 * Catalog / 设备查询类 MANSCDP 子路由(Wave 4 PR-D / P2-1)。
 *
 * CmdType 范围:
 *  - Catalog          → CatalogResponse(树结构 + 通道清单)
 *  - DeviceInfo       → DeviceInfoResponse
 *  - DeviceStatus     → DeviceStatusResponse(online/recording/alarming/guarded snapshot)
 *  - ConfigDownload   → ConfigDownloadResponse(BasicParam / VideoParamOpt / SVACEncodeConfig)
 *  - MobilePosition   → MobilePositionResponse(单次拉取,跟 Position 订阅的 NOTIFY 走不同路径)
 *  - RecordInfo       → RecordInfoNotify(分包,每包一条 MESSAGE)
 *
 * 不在本路由的查询类:AlarmStatus → [AlarmSubRouter],PresetQuery/CruiseTrackQuery 等 →
 * [DeviceControlSubRouter](都跟 PTZ/Preset 状态强相关)。
 */
internal class CatalogSubRouter(
    private val ctx: ManscdpContext,
    private val recordingService: RecordingService,
) : ManscdpSubRouter {

    override fun accepts(cmdType: String): Boolean = cmdType in ACCEPTED

    override suspend fun handle(cmdType: String, xml: String, fromUri: String?): Boolean {
        val sn = ManscdpParser.sn(xml) ?: "0"
        return when (cmdType) {
            "Catalog" -> { sendCatalogResponse(sn); true }
            "DeviceInfo" -> { sendDeviceInfoResponse(sn); true }
            "DeviceStatus" -> { sendDeviceStatusResponse(sn); true }
            "ConfigDownload" -> {
                val types = ConfigDownloadResponse.parseConfigTypes(xml)
                sendConfigDownloadResponse(sn, types); true
            }
            "MobilePosition" -> { sendMobilePositionResponse(sn); true }
            "RecordInfo" -> { handleRecordInfoQuery(xml); true }
            else -> false
        }
    }

    private suspend fun sendCatalogResponse(sn: String) {
        val xmlBody = CatalogResponse.buildFromTree(
            config = ctx.config,
            sn = sn,
            tree = ManscdpInternals.publishableCatalogNodes(ctx.catalogTree.value),
        )
        ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "Catalog response",
            simEventEmit = ctx.simEventEmit,
        )
    }

    private suspend fun sendDeviceInfoResponse(sn: String) {
        val xmlBody = DeviceInfoResponse.build(ctx.config, sn)
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "DeviceInfo response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(LogLevel.Info, LogTag.Network, "平台查询 DeviceInfo → 已应答 sn=$sn")
    }

    private suspend fun sendDeviceStatusResponse(sn: String) {
        val ctrl = ctx.deviceControlState.value
        val snapshot = DeviceStatusSnapshot(
            online = ctx.stateRegisteredOrInCall(),
            deviceTime = ManscdpInternals.currentLocalIso(ctx.clockOffsetProvider),
            recording = ctrl.isRecording,
            alarming = ctrl.isAlarming,
            guarded = ctrl.isGuarded,
        )
        val xmlBody = DeviceStatusResponse.build(ctx.config, sn, snapshot)
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "DeviceStatus response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 DeviceStatus → 已应答 sn=$sn online=${snapshot.online} record=${snapshot.recording} alarm=${snapshot.alarming}"
        )
    }

    private suspend fun sendConfigDownloadResponse(sn: String, configTypes: List<String>) {
        val xmlBody = ConfigDownloadResponse.build(ctx.config, sn, configTypes)
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "ConfigDownload response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 ConfigDownload → 已应答 sn=$sn types=${configTypes.joinToString("/")}"
        )
    }

    private suspend fun sendMobilePositionResponse(sn: String) {
        val fix = ctx.mockGps.next()
        if (fix == null) {
            // plan §3.3 Q4 单次查询与 NOTIFY 同路径:无 fix 时不响应,让平台超时
            // F4 P1-5 fix:单次查询是独立事件,不去重,每次 log 一次让联调 grep 得到
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Subscription,
                "MobilePosition 单次查询无 fix 数据 → 不响应 sn=$sn",
                detail = "LocationProvider.next() 返回 null。可能原因:未 start / 定位权限拒 / 定位服务关 / 尚无首帧 fix。",
            )
            return
        }
        val xmlBody = MobilePositionResponse.build(
            deviceId = ctx.config.device.deviceId,
            sn = sn,
            point = fix.point,
            speed = fix.speed,
            direction = fix.direction,
            altitude = fix.altitude,
            timestamp = ManscdpInternals.currentLocalIso(ctx.clockOffsetProvider),
        )
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "MobilePosition response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 MobilePosition → 已应答 sn=$sn lng=${fix.point.longitude} lat=${fix.point.latitude}"
        )
    }

    private suspend fun handleRecordInfoQuery(xml: String) {
        val tz = "Asia/Shanghai"
        val query = RecordInfoQuery.parse(xml, tz) ?: run {
            SystemLogger.emit(LogLevel.Warning, LogTag.Media, "RecordInfo 查询解析失败")
            return
        }
        if (query.indistinctQuery == 1 || query.filePath != null ||
            query.address != null || query.recorderId != null
        ) {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "RecordInfo 高级过滤(已解析,sim 单通道 mock 不参与命中): " +
                    "indistinct=${query.indistinctQuery} path=${query.filePath} " +
                    "addr=${query.address} recId=${query.recorderId}"
            )
        }
        val files = recordingService.files.value
        val hits = files.filter {
            query.startMs <= it.endTimeMs && query.endMs >= it.startTimeMs &&
                (query.type == null || it.type == query.type)
        }
        val packets = RecordInfoNotify.buildAll(
            sn = query.sn,
            deviceId = ctx.config.device.deviceId,
            deviceName = ctx.config.device.name,
            items = hits,
            timeZoneId = tz,
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "平台查询录像 → 命中 ${hits.size} 条 / 分 ${packets.size} 包"
        )
        for (xmlBody in packets) {
            ManscdpInternals.sendMansMessage(
                config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
                localIp = ctx.localIp, localPort = ctx.localPort,
                xmlBody = xmlBody,
                errorLabel = "RecordInfo",
                simEventEmit = ctx.simEventEmit,
            )
        }
    }

    companion object {
        private val ACCEPTED = setOf(
            "Catalog", "DeviceInfo", "DeviceStatus", "ConfigDownload",
            "MobilePosition", "RecordInfo",
        )
    }
}
