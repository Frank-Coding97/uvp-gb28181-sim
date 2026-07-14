package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.location.PositionFix
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
import kotlinx.coroutines.delay

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
        // cross-review R1 #1 修复 — 单次查询是独立于订阅的 GB28181 路径,不能只靠订阅路径启 provider。
        // 冷启动:如果 next() 无 fix 且没订阅,主动 start provider + poll 一段时间;完成后无论成功失败都
        // release(R1 verify-followup #1:成功路径也必须 release,否则一次单次查询会让 provider 常驻,
        // 造成电量/隐私回归)。
        val fix = ctx.mockGps.next() ?: run {
            val hadSubscription = ctx.subscriptionRegistry.dialogsByKind("MobilePosition").isNotEmpty()
            if (hadSubscription) {
                // 已订阅但还没首帧 fix:走原语义(不响应,让平台超时)
                null
            } else {
                // cold-start:主动 start + poll 拿首帧 fix
                ctx.ensureLocationProviderStarted()
                try {
                    pollFirstFix()
                } finally {
                    // R1 verify-followup #1 — 无论 poll 成功/失败都必须 release,避免单次查询 leak provider
                    ctx.releaseLocationProviderIfIdle()
                }
            }
        }
        if (fix == null) {
            // plan §3.3 Q4 单次查询与 NOTIFY 同路径:无 fix 时不响应,让平台超时
            // F4 P1-5 fix:单次查询是独立事件,不去重,每次 log 一次让联调 grep 得到
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Subscription,
                "MobilePosition 单次查询无 fix 数据 → 不响应 sn=$sn",
                detail = "LocationProvider.next() 返回 null。可能原因:未 start / 定位权限拒 / 定位服务关 / 尚无首帧 fix / fix 超过最大年龄。",
            )
            return
        }
        // cross-review R1 #5 修复 — timestamp 用 fix.fixTimeMs(采样时间)而非 currentLocalIso(响应时间),
        // 否则平台会把陈旧坐标看成"刚采集"而无法识别。 fixTimeMs = 0 时 fall back 到当前时间(测试 fixture 兼容)。
        val xmlBody = MobilePositionResponse.build(
            deviceId = ctx.config.device.deviceId,
            sn = sn,
            point = fix.point,
            speed = fix.speed,
            direction = fix.direction,
            altitude = fix.altitude,
            timestamp = if (fix.fixTimeMs > 0L) null else ManscdpInternals.currentLocalIso(ctx.clockOffsetProvider),
            fixTimeMs = fix.fixTimeMs,
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

    /**
     * cross-review R1 #1 修复 — 单次查询 cold-start poll:
     * 主动 start provider 后短暂等首帧 fix。每 [POLL_INTERVAL_MS] 检查一次,总共 [POLL_TOTAL_MS]。
     * 拿到即返回,超时返回 null 交给外层走"不响应"分支。
     */
    private suspend fun pollFirstFix(): PositionFix? {
        var elapsed = 0L
        while (elapsed < POLL_TOTAL_MS) {
            val fix = ctx.mockGps.next()
            if (fix != null) return fix
            delay(POLL_INTERVAL_MS)
            elapsed += POLL_INTERVAL_MS
        }
        return ctx.mockGps.next()
    }

    companion object {
        private val ACCEPTED = setOf(
            "Catalog", "DeviceInfo", "DeviceStatus", "ConfigDownload",
            "MobilePosition", "RecordInfo",
        )
        // cross-review R1 #1 常量:单次查询 cold-start poll 参数。3s 内拿不到就放弃,平台会重试。
        private const val POLL_INTERVAL_MS = 300L
        private const val POLL_TOTAL_MS = 3_000L
    }
}
