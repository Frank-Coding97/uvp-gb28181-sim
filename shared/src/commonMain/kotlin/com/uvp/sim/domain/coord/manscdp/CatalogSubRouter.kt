package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.CatalogTreeStore
import com.uvp.sim.domain.location.PositionFix
import com.uvp.sim.gb28181.CatalogResponse
import com.uvp.sim.gb28181.ConfigDownloadResponse
import com.uvp.sim.gb28181.DeviceConfigBlock
import com.uvp.sim.gb28181.DeviceInfoResponse
import com.uvp.sim.gb28181.DeviceStatusResponse
import com.uvp.sim.gb28181.DeviceStatusSnapshot
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.MobilePositionResponse
import com.uvp.sim.gb28181.RecordInfoNotify
import com.uvp.sim.gb28181.RecordInfoQuery
import com.uvp.sim.gb28181.RecordInfoQueryRequest
import com.uvp.sim.gb28181.SignalingCharset
import com.uvp.sim.gb28181.VideoParamAttribute
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordType
import com.uvp.sim.recording.RecordingFile
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
            "Catalog" -> {
                sendCatalogResponse(sn, ManscdpParser.deviceId(xml) ?: ctx.config.device.deviceId)
                true
            }
            "DeviceInfo" -> { sendDeviceInfoResponse(sn); true }
            "DeviceStatus" -> { sendDeviceStatusResponse(sn); true }
            "ConfigDownload" -> {
                val types = ConfigDownloadResponse.parseConfigTypes(xml)
                // ⭐ 应答的顶层 <DeviceID> 必须回**请求里的那个**：平台面板是按通道编码
                //    (channel_code)查的，与设备编码不是同一个值；回错会被平台侧
                //    ConfigDownloadExpectation 对账判定为"不属于本次操作"而丢弃，
                //    两侧都不报错（实测 2026-09-18 卡在 never_read）。口径同上 Catalog 分支。
                sendConfigDownloadResponse(
                    sn, ManscdpParser.deviceId(xml) ?: ctx.config.device.deviceId, types
                ); true
            }
            "MobilePosition" -> { sendMobilePositionResponse(sn); true }
            "RecordInfo" -> { handleRecordInfoQuery(xml); true }
            else -> false
        }
    }

    private suspend fun sendCatalogResponse(sn: String, targetId: String) {
        val nodes = selectCatalogNodes(ctx.catalogTree.value, targetId)
        val packets = CatalogResponse.buildAllFromTree(
            config = ctx.config,
            sn = sn,
            tree = nodes,
            version = ctx.effectiveGbVersion,
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 Catalog target=$targetId → ${nodes.size} 条 / 分 ${packets.size} 包 sn=$sn"
        )
        if (nodes.size > 10_000 && ctx.config.transport.name == "UDP") {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "Catalog 总记录超过 10000 条，GB/T 28181 建议改用 TCP 信令"
            )
        }
        for (xmlBody in packets) {
            val sent = ManscdpInternals.sendMansMessage(
                config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
                localIp = ctx.localIp, localPort = ctx.localPort,
                xmlBody = xmlBody,
                errorLabel = "Catalog response",
                charset = SignalingCharset.of(ctx.effectiveGbVersion),
                simEventEmit = ctx.simEventEmit,
            )
            if (!sent) break
        }
    }

    private fun selectCatalogNodes(tree: List<com.uvp.sim.config.CatalogNode>, targetId: String): List<com.uvp.sim.config.CatalogNode> {
        val publishable = ManscdpInternals.publishableCatalogNodes(tree)
        if (targetId.isBlank()) return publishable
        val target = tree.firstOrNull { it.id == targetId }
        if (target != null) {
            val byParent = tree.groupBy { it.parentId }
            val result = mutableListOf<com.uvp.sim.config.CatalogNode>()
            val visited = mutableSetOf<String>()
            fun visit(id: String) {
                if (!visited.add(id)) return
                tree.firstOrNull { it.id == id }?.let { if (it !in result) result += it }
                byParent[id].orEmpty().forEach { visit(it.id) }
            }
            visit(target.id)
            return result.filterNot { it.type == com.uvp.sim.config.CatalogNodeType.Device && it.parentId == it.id && it.id != targetId }
        }
        if (targetId.length in setOf(2, 4, 6, 8) && targetId.all { it in '0'..'9' }) {
            return publishable.filter { node ->
                node.id.startsWith(targetId) || node.fields["CivilCode"].orEmpty().startsWith(targetId)
            }
        }
        return emptyList()
    }

    private suspend fun sendDeviceInfoResponse(sn: String) {
        val xmlBody = DeviceInfoResponse.build(ctx.config, sn, ctx.effectiveGbVersion)
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "DeviceInfo response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
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
        val xmlBody = DeviceStatusResponse.build(ctx.config, sn, snapshot, ctx.effectiveGbVersion)
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "DeviceStatus response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 DeviceStatus → 已应答 sn=$sn online=${snapshot.online} record=${snapshot.recording} alarm=${snapshot.alarming}"
        )
    }

    private suspend fun sendConfigDownloadResponse(
        sn: String,
        requestedDeviceId: String,
        configTypes: List<String>,
    ) {
        val videoParamOverrides = ctx.deviceControlState.value.videoParams
        val xmlBody = ConfigDownloadResponse.build(
            config = ctx.config,
            sn = sn,
            requestedDeviceId = requestedDeviceId,
            configTypes = configTypes,
            // ⭐ 有效版本(附录 I 协商结果),不是本机声明档 —— 2022 新增类型在有效版本是 2016 时
            //    整块不回(见 ConfigDownloadResponse.build 的类注释 ②)。
            gbVersion = ctx.effectiveGbVersion,
            videoParamOverrides = videoParamOverrides,
            deviceConfigs = ctx.deviceControlState.value.deviceConfigs,
        )
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "ConfigDownload response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 ConfigDownload → 已应答 sn=$sn types=${configTypes.joinToString("/")} " +
                "有效版本=${ctx.effectiveGbVersion.label}" + configDownloadNote(configTypes)
        )
    }

    /**
     * 回读日志的注脚 —— 把「**为什么某个请求过的类型没出现在应答里**」当场说清楚。
     *
     * ⛔ 这条注脚不是为了好看。平台拿到"应答里没有该块"时只有一个观测结果
     * （`type_absent`），却对应**三种完全相反**的原因，处置方式各不相同：
     *
     * | 原因 | 日志里的样子 | 该怎么办 |
     * |---|---|---|
     * | 模拟器压根没实现（SVAC） | `模拟器未实现(设备不支持):…` | 换设备能力，别再查 |
     * | 有效版本不是 2022 | `按有效版本 … 未回:…` | 切设置页档位重注册 |
     * | 设备支持、没人配过 | **不会出现**（回读退回出厂默认） | 正常，值就是默认值 |
     *
     * 三种里前两种靠这一行区分。所有分支都插**真值**，不写死常量 ——
     * 写死的括号值比不写日志更有害（本仓为此踩过一次）。
     */
    private fun configDownloadNote(configTypes: List<String>): String = buildString {
        append(videoParamResponseNote(configTypes))
        val gated = ConfigDownloadResponse.requestedButVersionGated(configTypes, ctx.effectiveGbVersion)
        if (gated.isNotEmpty()) {
            append(" 按有效版本未回:${gated.joinToString("/")}")
        }
        val unimplemented = ConfigDownloadResponse.requestedButNotImplemented(configTypes)
        if (unimplemented.isNotEmpty()) {
            append(" 模拟器未实现(设备不支持):${unimplemented.joinToString("/")}")
        }
        val configured = ctx.deviceControlState.value.deviceConfigs.configuredBlocks
        val hit = configTypes.mapNotNull { DeviceConfigBlock.byConfigType(it) }
            .filter { it in configured }
        if (hit.isNotEmpty()) {
            append(" 平台配过:${hit.joinToString("/") { it.configType }}")
        }
    }

    /**
     * 回读日志里那条「VideoParamAttribute 到底回了几路 / 为什么不回」的注脚。
     *
     * ⭐ 回读为空时,现场第一个要问的就是「为什么不回」。把真正的原因(版本门禁 / 平台没点名要)
     * 直接写进这一行,免得有人去查「是不是设备不支持」。
     * 三个分支都插**真值**,不写死常量 —— 写死的括号值比不写日志更有害(本仓为此踩过一次)。
     */
    private fun videoParamResponseNote(configTypes: List<String>): String = when {
        !ConfigDownloadResponse.requestedVideoParamAttribute(configTypes) -> ""
        !ConfigDownloadResponse.emitsVideoParamAttribute(configTypes, ctx.effectiveGbVersion) ->
            " VideoParamAttribute=按有效版本 ${ctx.effectiveGbVersion.label} 不回(平台会判 type_absent)"
        else ->
            " VideoParamAttribute=" + VideoParamAttribute.effectiveParams(
                ctx.config, ctx.deviceControlState.value.videoParams
            ).size + " 路码流"
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
            deviceId = CatalogTreeStore.positionChannelId(ctx.config, ctx.catalogTree.value),
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
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 MobilePosition → 已应答 sn=$sn lng=${fix.point.longitude} lat=${fix.point.latitude}"
        )
    }

    /**
     * A.2.4.5 `StreamNumber` 过滤：平台点名的码流号要与录像自带的一致；没点名（null）不过滤。
     *
     * ⚠️ 本仓录像的 `streamNumber` 目前恒为 0（见 `RecordingFile.streamNumber` 的口径边界），
     * 所以平台筛子码流时会得到**空结果** —— 这是诚实的空结果（设备确实没有子码流录像），
     * 比"忽略条件返回主码流录像"好得多：后者会让平台把主码流录像当成子码流录像展示，
     * 而平台无法分辨。
     */
    private fun matchesStreamNumber(q: RecordInfoQueryRequest, f: RecordingFile): Boolean =
        q.streamNumber == null || f.streamNumber == q.streamNumber

    /**
     * A.2.4.5 `AlarmMethod` / `AlarmType` 过滤。
     *
     * ⚠️ 口径边界（刻意保守）：这两个维度是**报警录像**（`Type=alarm`）的细分，而本仓的
     * `RecordingFile` 只有 `type` 一列，没有"报警方式/报警类型"两列。所以规则是：
     * **平台只要按报警维度筛，就只回报警录像**；一个都没筛就不过滤。
     * ⛔ 绝不"忽略条件返回全部" —— 那正是本次要修的「静默返回超集」。
     * 等 `RecordingFile` 补上这两列后，把条件收紧成精确相等即可（届时删掉本条说明）。
     */
    private fun matchesAlarmFilter(q: RecordInfoQueryRequest, f: RecordingFile): Boolean {
        if (q.alarmMethod == null && q.alarmType == null) return true
        return f.type == RecordType.Alarm
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
        // ⭐ 2026-09-19：2022 新增的三个过滤条件**真参与命中**（见 RecordInfoQueryRequest 的
        //    KDoc）。忽略它们 = **静默返回超集** —— 平台按"视频报警录像 + 子码流"筛，
        //    设备把定时录像、主码流录像也一并回了，而平台无法分辨哪条是超发的。
        if (query.streamNumber != null || query.alarmMethod != null || query.alarmType != null) {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Media,
                "RecordInfo 2022 过滤条件: stream=${query.streamNumber} " +
                    "alarmMethod=${query.alarmMethod} alarmType=${query.alarmType}"
            )
        }
        val files = recordingService.files.value
        val hits = files.filter { f ->
            query.startMs <= f.endTimeMs && query.endMs >= f.startTimeMs &&
                (query.type == null || f.type == query.type) &&
                matchesStreamNumber(query, f) &&
                matchesAlarmFilter(query, f)
        }
        val packets = RecordInfoNotify.buildAll(
            sn = query.sn,
            deviceId = ctx.config.device.deviceId,
            deviceName = ctx.config.device.name,
            items = hits,
            // ⛔ 用**有效**版本（附录 I 协商结果），不是 config.gbVersion（本机声明）——
            //    2016 对端多收 `RecordLocation`/`StreamNumber` 会让严格校验判整条非法。
            gbVersion = ctx.effectiveGbVersion,
            timeZoneId = tz,
            pageSize = ctx.config.multiResponsePageSize.coerceIn(1, 10_000),
        )
        SystemLogger.emit(
            LogLevel.Info, LogTag.Media,
            "平台查询录像 → 命中 ${hits.size} 条 / 分 ${packets.size} 包"
        )
        if (hits.size > 10_000 && ctx.config.transport.name == "UDP") {
            SystemLogger.emit(
                LogLevel.Warning, LogTag.Network,
                "RecordInfo 总记录超过 10000 条，GB/T 28181 建议改用 TCP 信令"
            )
        }
        for (xmlBody in packets) {
            val sent = ManscdpInternals.sendMansMessage(
                config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
                localIp = ctx.localIp, localPort = ctx.localPort,
                xmlBody = xmlBody,
                errorLabel = "RecordInfo",
                charset = SignalingCharset.of(ctx.effectiveGbVersion),
                simEventEmit = ctx.simEventEmit,
            )
            if (!sent) break
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
