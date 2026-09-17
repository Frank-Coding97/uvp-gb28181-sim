package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.domain.PtzPose
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PresetQueryResponse
import com.uvp.sim.gb28181.PtzPreciseStatusResponse
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordingService

/**
 * 设备控制类 MANSCDP 子路由(Wave 4 PR-D / P2-1)。
 *
 * CmdType 范围:
 *  - DeviceControl          → PTZ / Preset / Aux / TeleBoot / SnapShotConfig / Record / DragZoom / DeviceUpgrade
 *                             (通过 [DeviceControlDispatcher] 解析与状态机改写)
 *  - PresetQuery            → PresetQueryResponse(当前预置位清单)
 *  - PTZPreciseStatusQuery  → PtzPreciseStatusResponse(当前精确角度 / 倍数)
 *  - HomePositionQuery      → 自构 XML(Enabled / ResetTime / PresetIndex)
 *  - StorageCardStatusQuery → 自构 XML(单 slot 32G / 24G mock)
 *  - CruiseTrackListQuery   → 自构 XML(从 deviceControlState.cruiseTracks 出索引清单)
 *  - CruiseTrackQuery       → 自构 XML(指定 TrackNum 的预置位序列)
 *
 * 注:Record / StopRecord 是 DeviceControl 内嵌的 RecordCmd,本路由也负责触发 recordingService。
 * AlarmCmd 复位通过 dispatcher 出口里的 alarmReset 标志反馈 → pushAlarmResetNotify 走 BroadcastSubRouter
 * 兜底的 Alarm NOTIFY 不通,只能在本路由组装 dialog 列表后调 Manscdp dispatcher 的回调
 * (经 [alarmResetCallback] 注入)。
 */
internal class DeviceControlSubRouter(
    private val ctx: ManscdpContext,
    private val recordingService: RecordingService,
    private val dispatcher: DeviceControlDispatcher,
    /** AlarmCmd 复位时回调:广播 NOTIFY 给所有 Alarm 订阅者(实现在 [ManscdpRouterImpl])。 */
    private val alarmResetCallback: suspend (by: String?) -> Unit,
) : ManscdpSubRouter {

    override fun accepts(cmdType: String): Boolean = cmdType in ACCEPTED

    override suspend fun handle(cmdType: String, xml: String, fromUri: String?): Boolean {
        return when (cmdType) {
            "DeviceControl" -> { handleDeviceControl(xml, fromUri); true }
            "PresetQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendPresetQueryResponse(sn, channelId); true
            }
            "PTZPreciseStatusQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendPtzPreciseStatusResponse(sn, channelId); true
            }
            "HomePositionQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendHomePositionQueryResponse(sn, channelId); true
            }
            "StorageCardStatusQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendStorageCardStatusResponse(sn, channelId); true
            }
            "CruiseTrackListQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendCruiseTrackListResponse(sn, channelId); true
            }
            "CruiseTrackQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                val trackNum = ManscdpParser.tagValue(xml, "GroupID")?.toIntOrNull()
                    ?: ManscdpParser.tagValue(xml, "TrackNum")?.toIntOrNull()
                    ?: 1
                sendCruiseTrackResponse(sn, channelId, trackNum); true
            }
            else -> false
        }
    }

    private suspend fun handleDeviceControl(xml: String, fromUri: String?) {
        val ack = dispatcher.dispatch(xml, fromUri = fromUri)
        val lastCmd = ctx.deviceControlState.value.lastCommand
        if (lastCmd != null) {
            ctx.simEventEmit(
                SimEvent.DeviceControlReceived(
                    commandType = lastCmd.type,
                    detail = lastCmd.rawHex,
                )
            )
        }
        if (ack.alarmReset) {
            ctx.simEventEmit(SimEvent.AlarmReset(SimEvent.ResetSource.Remote(ack.by ?: "platform")))
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "平台 AlarmCmd 复位报警 by=${ack.by ?: "platform"}",
            )
            alarmResetCallback(ack.by)
        }
        val recordCmd = ManscdpParser.recordCmd(xml) ?: return
        val sn = ManscdpParser.sn(xml) ?: "0"
        val deviceId = ManscdpParser.deviceId(xml) ?: ctx.config.device.deviceId
        var result = "OK"
        when {
            recordCmd.equals("Record", ignoreCase = true) -> {
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "平台下发 Record → 启动录像 source=PlatformCmd")
                runCatching {
                    recordingService.start(
                        RecordSource.PlatformCmd,
                        ctx.config.device.videoChannelId,
                    )
                }.onFailure {
                    SystemLogger.emit(LogLevel.Error, LogTag.Media, "RecordCmd 启动录像异常: ${it.message}")
                    result = "ERROR"
                }
            }
            recordCmd.equals("StopRecord", ignoreCase = true) -> {
                SystemLogger.emit(LogLevel.Info, LogTag.Media, "平台下发 StopRecord → 停止录像")
                runCatching { recordingService.stop() }
                    .onFailure {
                        SystemLogger.emit(LogLevel.Error, LogTag.Media, "RecordCmd 停止录像异常: ${it.message}")
                        result = "ERROR"
                    }
            }
            else -> {
                SystemLogger.emit(LogLevel.Warning, LogTag.Media, "平台 RecordCmd 未识别 → '$recordCmd'")
                result = "ERROR"
            }
        }
        sendDeviceControlResponse(sn = sn, deviceId = deviceId, result = result)
    }

    private suspend fun sendDeviceControlResponse(sn: String, deviceId: String, result: String) {
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>DeviceControl</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$deviceId</DeviceID>\r\n" +
            "<Result>$result</Result>\r\n" +
            "</Response>\r\n"
        ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "DeviceControl Response",
            simEventEmit = ctx.simEventEmit,
        )
    }

    private suspend fun sendPresetQueryResponse(sn: String, channelId: String) {
        val presets = ctx.deviceControlState.value.presets
        val xmlBody = PresetQueryResponse.build(
            config = ctx.config,
            sn = sn,
            channelId = channelId,
            presets = presets,
        )
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "PresetQuery response",
            simEventEmit = ctx.simEventEmit,
        )
        // ⛔ 这里原来写死 "(空清单)" —— 而且**不管实际发了什么都这么打**。
        //    2026-09-16 排查预置位存不进去时,就被这句骗了一轮:它看起来像"设备确认自己没有
        //    预置位",实际设备明明存了,是解码端把编号读错字节。日志里写死的括号值和
        //    真实载荷脱钩,比不写更有害。现在按真实条数打(与同文件其它查询应答一致)。
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 PresetQuery → 已应答(${presets.size} 个)sn=$sn"
        )
    }

    private suspend fun sendPtzPreciseStatusResponse(sn: String, channelId: String) {
        val s = ctx.deviceControlState.value
        val pose = s.lastPreciseCtrl ?: PtzPose(s.panAngle, s.tiltAngle, s.zoomLevel)
        val xmlBody = PtzPreciseStatusResponse.build(
            config = ctx.config,
            sn = sn,
            channelId = channelId,
            pose = pose,
        )
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "PTZPreciseStatusQuery response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 PTZPreciseStatusQuery → 已应答(${pose.pan},${pose.tilt},${pose.zoom}x)sn=$sn"
        )
    }

    /**
     * 应答 `HomePositionQuery`(GB/T 28181-2022 新增的**查询**;控制那一半 2016 就有)。
     *
     * 2026-09-16 按标准修正了三处偏差:
     *  1. **必须带 `<HomePosition>` 包裹层**。原来把 `Enabled`/`ResetTime`/`PresetIndex` 直接
     *     平铺在 `<Response>` 下;平台侧解析器找的是 `<HomePosition>` **子元素**,平铺会被读成
     *     "有应答但不带数据"(`ResponseHasData=false`),于是设备上真实的看守位配置永远传不回
     *     平台,平台只能一直显示"未确认"。这是"设备配好了、平台看不见"这一类问题的根因。
     *  2. `ResetTime` 原来写死 30,现在回真实配置值 —— 否则平台侧对账比对 `ResetTime` 必然
     *     mismatch,每次控制都会被标成对账失败。
     *  3. `PresetIndex` 原来是 `if (s.homePosition != null) 1 else 0`,凭空造了个 1。现在回真实值。
     *
     * 「平台从未下发过」用 `homePositionPresetIndex == null` 判定,而不是看 `homePositionEnabled`
     * —— 后者在 `DeviceControlModel` 里默认是 `true`,单看它会把"从没配过"误报成"已启用"。
     * 未配置时统一回 `Enabled=0 / ResetTime=0 / PresetIndex=0`:标准没有定义"查不到"的应答形态,
     * 全零是标准形式里最接近"本机没有看守位配置"的一档,也不会让平台误以为有个 P0 在守着。
     */
    private suspend fun sendHomePositionQueryResponse(sn: String, channelId: String) {
        val s = ctx.deviceControlState.value
        val responseDeviceId = channelId.ifBlank { ctx.config.device.deviceId }
        val configured = s.homePositionPresetIndex != null
        val enabled = if (configured && s.homePositionEnabled) 1 else 0
        val resetTime = if (configured) (s.homePositionResetTime ?: 0) else 0
        val presetIndex = s.homePositionPresetIndex ?: 0
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>HomePositionQuery</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
            "<HomePosition>\r\n" +
            "<Enabled>$enabled</Enabled>\r\n" +
            "<ResetTime>$resetTime</ResetTime>\r\n" +
            "<PresetIndex>$presetIndex</PresetIndex>\r\n" +
            "</HomePosition>\r\n" +
            "</Response>\r\n"
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "HomePositionQuery response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 HomePositionQuery → 已应答(Enabled=$enabled ResetTime=$resetTime PresetIndex=$presetIndex)sn=$sn"
        )
    }

    private suspend fun sendStorageCardStatusResponse(sn: String, channelId: String) {
        val responseDeviceId = channelId.ifBlank { ctx.config.device.deviceId }
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>StorageCardStatusQuery</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
            "<SumNum>1</SumNum>\r\n" +
            "<StorageList Num=\"1\">\r\n" +
            "<Item><CardNum>0</CardNum><Status>Normal</Status><TotalCapacity>32768</TotalCapacity><RemainingSpace>24576</RemainingSpace></Item>\r\n" +
            "</StorageList>\r\n" +
            "</Response>\r\n"
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "StorageCardStatus response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(LogLevel.Info, LogTag.Network, "StorageCardStatusQuery sn=$sn → 已应答")
    }

    private suspend fun sendCruiseTrackListResponse(sn: String, channelId: String) {
        val s = ctx.deviceControlState.value
        val responseDeviceId = channelId.ifBlank { ctx.config.device.deviceId }
        val sumNum = s.cruiseTracks.size
        val items = s.cruiseTracks.keys.sorted().joinToString("\r\n") { trackNum ->
            "<Item><GroupID>$trackNum</GroupID><Name>巡航 $trackNum</Name></Item>"
        }
        val itemsBlock = if (sumNum == 0) "<TrackList Num=\"0\"/>"
            else "<TrackList Num=\"$sumNum\">\r\n$items\r\n</TrackList>"
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>CruiseTrackListQuery</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
            "<SumNum>$sumNum</SumNum>\r\n" +
            "$itemsBlock\r\n" +
            "</Response>\r\n"
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "CruiseTrackList response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(LogLevel.Info, LogTag.Network, "CruiseTrackList sn=$sn N=$sumNum → 已应答")
    }

    private suspend fun sendCruiseTrackResponse(sn: String, channelId: String, trackNum: Int) {
        val s = ctx.deviceControlState.value
        val responseDeviceId = channelId.ifBlank { ctx.config.device.deviceId }
        val track = s.cruiseTracks[trackNum] ?: emptyList()
        val sumNum = track.size
        val items = track.joinToString("\r\n") { presetNum ->
            "<Item><PresetID>$presetNum</PresetID><Speed>5</Speed><DwellTime>3</DwellTime></Item>"
        }
        val itemsBlock = if (sumNum == 0) "<PresetList Num=\"0\"/>"
            else "<PresetList Num=\"$sumNum\">\r\n$items\r\n</PresetList>"
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>CruiseTrackQuery</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
            "<GroupID>$trackNum</GroupID>\r\n" +
            "<SumNum>$sumNum</SumNum>\r\n" +
            "$itemsBlock\r\n" +
            "</Response>\r\n"
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "CruiseTrack response",
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(LogLevel.Info, LogTag.Network, "CruiseTrack #$trackNum sn=$sn → 已应答")
    }

    companion object {
        private val ACCEPTED = setOf(
            "DeviceControl", "PresetQuery", "PTZPreciseStatusQuery",
            "HomePositionQuery", "StorageCardStatusQuery",
            "CruiseTrackListQuery", "CruiseTrackQuery",
        )
    }
}
