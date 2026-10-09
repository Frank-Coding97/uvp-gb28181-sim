package com.uvp.sim.domain.coord.manscdp

import com.uvp.sim.domain.DEFAULT_CRUISE_DWELL_SECONDS
import com.uvp.sim.domain.DeviceControlDispatcher
import com.uvp.sim.domain.SimEvent
import com.uvp.sim.domain.VirtualStorageCards
import com.uvp.sim.domain.toPtzPositionSnapshot
import com.uvp.sim.domain.withPose
import com.uvp.sim.domain.withStorageCardQuery
import com.uvp.sim.gb28181.ManscdpParser
import com.uvp.sim.gb28181.PresetQueryResponse
import com.uvp.sim.gb28181.PtzPreciseStatusResponse
import com.uvp.sim.gb28181.SignalingCharset
import com.uvp.sim.observability.LogLevel
import com.uvp.sim.observability.LogTag
import com.uvp.sim.observability.SystemLogger
import com.uvp.sim.recording.RecordSource
import com.uvp.sim.recording.RecordingService
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/**
 * 设备控制类 MANSCDP 子路由(Wave 4 PR-D / P2-1)。
 *
 * CmdType 范围:
 *  - DeviceControl          → PTZ / Preset / Aux / TeleBoot / SnapShotConfig / Record / DragZoom / DeviceUpgrade
 *                             (通过 [DeviceControlDispatcher] 解析与状态机改写)
 *  - PresetQuery            → PresetQueryResponse(当前预置位清单)
 *  - PTZPosition            → PtzPreciseStatusResponse(当前精确姿态六字段)
 *  - PTZPreciseStatusQuery  → 同上(⛔ **历史自造名**,两版标准 0 命中,仅为兼容保留)
 *  - HomePositionQuery      → 自构 XML(Enabled / ResetTime / PresetIndex)
 *  - SDCardStatus           → 自构 XML(随机 0~2 张卡,容量稳定 / 剩余与状态每次抖动)
 *  - StorageCardStatusQuery → 同上(⛔ **历史自造名**,标准是 SDCardStatus,仅为兼容保留)
 *  - CruiseTrackListQuery   → 自构 XML(从 deviceControlState.cruiseTracks 出索引清单)
 *  - CruiseTrackQuery       → 自构 XML(按 §A.2.4.12 的 `<Number>` 取该条轨迹的点位序列)
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
    /**
     * 设备侧虚拟存储卡 —— 由 `AppEngine` 装配的**同一实例**同时喂两处:
     * ① 本路由组装 A.2.6.16 应答报文;② 模拟中心「存储卡」卡片渲染。
     *
     * ⛔ 别改成本类自己 `new` 一个:那样卡片与报文各掷各的骰子,会出现"设备屏幕显示 2 张卡
     * 32G、平台收到 1 张卡 8G"这种两边都看着正常的偏差。见 [VirtualStorageCards] 的类注释。
     *
     * 默认值是给单测/无装配场景的兜底(生产路径由 `AppEngine` 注入)。
     *
     * ⛔ 位置刻意在 [alarmResetCallback] **之前**:后者是唯一的尾随 lambda 参数,
     *    放在它后面会让既有调用点 `DeviceControlSubRouter(...) { }` 整体编译失败。
     */
    private val storageCards: VirtualStorageCards = VirtualStorageCards(),
    /** AlarmCmd 复位时回调:广播 NOTIFY 给所有 Alarm 订阅者(实现在 [ManscdpRouterImpl])。 */
    private val alarmResetCallback: suspend (by: String?) -> Unit,
) : ManscdpSubRouter {

    override fun accepts(cmdType: String): Boolean = cmdType in ACCEPTED

    override suspend fun handle(cmdType: String, xml: String, fromUri: String?): Boolean {
        return when (cmdType) {
            "DeviceControl" -> { handleDeviceControl(xml, fromUri); true }
            // ⭐ A.2.3.2 设备配置（2026-09-18 补）：BasicParam / VideoParamAttribute 等
            //    走的 CmdType 是 **`DeviceConfig`**，不是 `DeviceControl`。
            //    ⛔ 原先 [ACCEPTED] 里没有它 → 整类下发在 accepts 就被挡掉，现象是
            //    「未识别 MANSCDP cmdType=DeviceConfig，已回 200 但不会处理」：
            //    **200 是回了的**，所以平台侧完全看不出异常，设备侧也只有这条 Warning；
            //    平台面板则永远停在 never_read / mismatch，极易被误判成"平台没发"。
            //    dispatch() 内部本来就按块名分流好了（BasicParam / VideoParamAttribute），
            //    这里只需把它放行过去。
            "DeviceConfig" -> { handleDeviceConfig(xml, fromUri); true }
            "PresetQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendPresetQueryResponse(sn, channelId); true
            }
            // ⛔ 标准 CmdType 是 **`PTZPosition`**（A.2.4.13 请求 / A.2.6.15 应答同名）。
            //    `PTZPreciseStatusQuery` 是本仓早期自造名，2022 全书与 2016 附录 A **都是
            //    0 命中** —— 平台按标准发的查询在这里 `accepts()` 就被挡掉，现象是
            //    「回了 200 但不处理」（与 `DeviceConfig` 踩过的是同一个坑）。
            //    保留旧名只为兼容按旧写法实现的客户端，标准路径排在前面。
            "PTZPosition", "PTZPreciseStatusQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendPtzPreciseStatusResponse(sn, channelId); true
            }
            "HomePositionQuery" -> {
                val sn = ManscdpParser.sn(xml) ?: "0"
                val channelId = ManscdpParser.deviceId(xml) ?: ""
                sendHomePositionQueryResponse(sn, channelId); true
            }
            "SDCardStatus", "StorageCardStatusQuery" -> {
                // ⛔ 标准的 CmdType 是 **`SDCardStatus`**(§A.2.4.14 fixed="SDCardStatus",
                //    请求与应答同名)。`StorageCardStatusQuery` 是本仓早期自造的写法,
                //    标准附录 A 里没有这个词 —— 保留它只为兼容按旧写法实现的客户端,
                //    标准路径排在前面。
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
                // ⛔ 标准的轨迹标识元素是 **`<Number>`**(附录 A.2.4.12:列表请求 + 一个
                //    `<Number>`「0-第一条轨迹;1-第二条轨迹」)。这里原来只找 `GroupID`/
                //    `TrackNum`,而平台按标准发的是 `<Number>` —— 于是**永远 fallback**,
                //    查第几条都回同一条轨迹。保留后两个只为容错别的客户端,标准路径在前。
                val trackNum = ManscdpParser.tagValue(xml, "Number")?.toIntOrNull()
                    ?: ManscdpParser.tagValue(xml, "GroupID")?.toIntOrNull()
                    ?: ManscdpParser.tagValue(xml, "TrackNum")?.toIntOrNull()
                if (trackNum == null) {
                    // 不带编号的 CruiseTrackQuery 不符合标准。回空表而不是猜一条 ——
                    // 猜错会静默查到别人的轨迹,比空表危险得多。
                    SystemLogger.emit(
                        LogLevel.Warning, LogTag.Network,
                        "CruiseTrackQuery 未携带 <Number>/<GroupID>,按空轨迹应答 sn=$sn"
                    )
                }
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
        // 有应答的 DeviceControl 命令不能因为没有 RecordCmd 就提前返回；布防、看守位等
        // 命令需要独立发送应用层 Response。云台/目标跟踪等标准无应答命令仍保留返回路径。
        val sn = ManscdpParser.sn(xml) ?: "0"
        val deviceId = ManscdpParser.deviceId(xml) ?: ctx.config.device.deviceId
        val recordCmd = ManscdpParser.recordCmd(xml)
        if (recordCmd == null) {
            val hasGuardCmd = ManscdpParser.tagValue(xml, "GuardCmd") != null
            val hasAlarmCmd = ManscdpParser.tagValue(xml, "AlarmCmd") != null
            if (!hasGuardCmd && !hasAlarmCmd && !xml.contains("<HomePosition>") && !xml.contains("<PresetCmd>")) return
            sendDeviceControlResponse(sn = sn, deviceId = deviceId, result = "OK")
            return
        }
        // ⭐ A.2.3.1.4 `StreamNumber`（**2022 新增**）：平台可以点名录哪一路码流
        //    （0-主码流 / 1-子码流1 / 2-子码流2…）。`null` = 平台没带这个元素 →
        //    按标准缺省 0。⛔ 原先完全不解析，平台"录子码流"的意图被静默丢弃，
        //    录出来的录像在 RecordInfo 里永远是主码流，平台按码流筛也筛不出来。
        val requestedStream = ManscdpParser.recordStreamNumber(xml)
        val streamNumber = requestedStream ?: 0
        var result = "OK"
        when {
            recordCmd.equals("Record", ignoreCase = true) -> {
                SystemLogger.emit(
                    LogLevel.Info, LogTag.Media,
                    "平台下发 Record → 启动录像 source=PlatformCmd 码流=$streamNumber" +
                        if (requestedStream == null) "（平台未指定，按缺省 0）" else "",
                )
                runCatching {
                    recordingService.start(
                        RecordSource.PlatformCmd,
                        ctx.config.device.videoChannelId,
                        streamNumber,
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
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
    }

    /**
     * 处理 `DeviceConfig`(A.2.3.2 设备配置类)并回 MANSCDP 应答。
     *
     * ⛔ **不能复用 [handleDeviceControl]**:那条路径在发应答之前会先取 `<RecordCmd>`,
     *    取不到就 `?: return` —— 而配置类报文里根本没有这个元素,于是**一个字节都不回**。
     *    PTZ 类只靠 SIP 200 就够,所以这个洞一直没暴露;`DeviceConfig` 不一样:
     *    平台侧 `applyDeviceConfigResponse` 明确等着解析 `<CmdType>DeviceConfig</CmdType>`
     *    的应答,收不到就把该 operation 置 timeout,**并且不会创建回读对账子 operation**
     *    (`createVideoParamReconcile` 只在 ack 成功路径里调用) —— 现象是
     *    「设备侧日志明明记了值、平台上永远是旧值」,而两侧都不报错。
     *
     * ⭐ 应答的 `DeviceID` 回**请求里的那个**,不是本机设备编码:平台用
     *    `ConfigDownloadExpectation{DeviceID: operationTargetCode(operation)}` 校验,
     *    而 `operationTargetCode` 在通道作用域下是**通道编码**。回设备编码 ⇒ 应答被
     *    判为不属于本次操作而丢弃,与 ConfigDownload 是同一个坑的第二个实例。
     *
     * `Result` 取 `ack.needSipResponse`:[DeviceControlDispatcher.dispatch] 落到 `else`
     * 分支时返回 `needSipResponse=false`,正是"这个配置块我不认识"。这时回 ERROR
     * 让平台**显式失败**,比回 OK 再等一个永远 type_absent 的回读好定位得多。
     */
    private suspend fun handleDeviceConfig(xml: String, fromUri: String?) {
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
        sendDeviceConfigResponse(
            sn = ManscdpParser.sn(xml) ?: "0",
            deviceId = ManscdpParser.deviceId(xml) ?: ctx.config.device.deviceId,
            result = if (ack.needSipResponse) "OK" else "ERROR",
        )
    }

    private suspend fun sendDeviceConfigResponse(sn: String, deviceId: String, result: String) {
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>DeviceConfig</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$deviceId</DeviceID>\r\n" +
            "<Result>$result</Result>\r\n" +
            "</Response>\r\n"
        ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "DeviceConfig Response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
    }

    private suspend fun sendPresetQueryResponse(sn: String, channelId: String) {
        val s = ctx.deviceControlState.value
        val presets = s.presets
        val xmlBody = PresetQueryResponse.build(
            config = ctx.config,
            sn = sn,
            channelId = channelId,
            presets = presets,
            // ⭐ 平台设置预置位时给过的名字（A.2.3.1.2 PTZCmdParams/PresetName）。
            //    不传的话回读永远是自己编的 `Preset N`，平台会以为"名字没保存"。
            presetNames = s.presetNames,
        )
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "PresetQuery response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
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

    /**
     * A.2.6.15 `PTZPosition` 应答（**六字段**）。
     *
     * 姿态取值优先级：平台下发过 `PTZPreciseCtrl` 就用它的三轴
     * （[com.uvp.sim.domain.DeviceControlModel.lastPreciseCtrl]），否则用 UI 积分出来的
     * `panAngle`/`tiltAngle`/`zoomLevel`。两种情况都从**同一个六字段快照**出发，
     * 光学三量（水平/竖直视场角、最远视距）随变焦倍数重算 ——
     * ⛔ 别只换 pan/tilt/zoom 而留着旧的角度：那会报出"3 倍变焦 + 1 倍视场角"的
     * 自相矛盾组合，平台据此算的视场范围整体偏掉。
     */
    private suspend fun sendPtzPreciseStatusResponse(sn: String, channelId: String) {
        val s = ctx.deviceControlState.value
        val base = s.toPtzPositionSnapshot()
        val position = s.lastPreciseCtrl?.let { base.withPose(it) } ?: base
        val xmlBody = PtzPreciseStatusResponse.build(
            config = ctx.config,
            sn = sn,
            channelId = channelId,
            position = position,
        )
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "PTZPosition response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 PTZPosition → 已应答(${position.pan},${position.tilt},${position.zoom}x " +
                "视场角 ${position.horizontalFieldAngle}) sn=$sn"
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
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "平台查询 HomePositionQuery → 已应答(Enabled=$enabled ResetTime=$resetTime PresetIndex=$presetIndex)sn=$sn"
        )
    }

    /**
     * §A.2.6.16 存储卡状态查询应答。
     *
     * ⛔ 报文骨架**必须**是
     *    `<SDCardStatusInfo><Item><ID/><HddName/><Status/><Capacity/><FreeSpace/>[<FormatProgress/>]</Item>…</SDCardStatusInfo>`。
     *    这里原来写的是 `<StorageList Num="1"><Item><CardNum/><Status>Normal</Status>
     *    <TotalCapacity/><RemainingSpace/></Item></StorageList>` —— 容器名(`StorageList`)、
     *    字段名(`CardNum` / `TotalCapacity` / `RemainingSpace`)、状态取值(`Normal`)、
     *    连 CmdType(`StorageCardStatusQuery`) **四处都不合标准**。平台按标准解析的结果是
     *    "一个有字段全都读不到的空列表",而不是报错 —— 最难查的那类错。
     *
     * 数据来自 [storageCards],它**与模拟中心那张卡片是同一个实例**(单一真源):
     *  - **物理属性**(张数 / 盘名 / 容量)进程内稳定 —— 真实设备的物理属性不会每次查询都变;
     *    每次重掷的话,平台连查两次会看到两张不同的卡,排障时无法区分"设备在变"与"模拟器在掷骰子"。
     *  - **读数**(状态 / 格式化进度 / 剩余空间)每次重新抖,模拟真实的写入与格式化过程。
     *
     * ⭐ **[read] 只调一次**,同一份读数同时喂报文与 UI。[read] 是随机函数:调两次会得到两组
     *    不同的值 —— 那正是"设备屏幕显示 24 GB 可用、平台收到 18 GB"的成因。
     *
     * ⭐ **先落状态、再发报文**:设备收到请求**当下**就已经读了盘,画面此刻就该亮。放在 `send`
     *    之后会让设备屏幕的反应无故依赖一次网络往返;而放在 `if (ok)` 里则会让"平台没收到"
     *    和"设备没读盘"混为一谈 —— 报文发失败时设备也确实读过盘,两件事不该绑在一起。
     */
    private suspend fun sendStorageCardStatusResponse(sn: String, channelId: String) {
        val responseDeviceId = channelId.ifBlank { ctx.config.device.deviceId }
        val cards = storageCards.cards
        val readings = storageCards.read()

        // ① 先让设备屏幕亮起来(DeviceControlModel → UI DTO 的单一出口)。
        ctx.deviceControlState.update { it.withStorageCardQuery(cards, readings, nowMs()) }

        // ② 同一份读数组装报文。`zip` 而不是按 id 查表:两边本就是同一次 read 的顺序产物,
        //    zip 让"每张卡都配到一条读数"在类型上成立,不会退化成"查不到就少写一个 Item"。
        val xmlBody = buildString {
            append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n")
            append("<Response>\r\n")
            append("<CmdType>SDCardStatus</CmdType>\r\n")
            append("<SN>$sn</SN>\r\n")
            append("<DeviceID>$responseDeviceId</DeviceID>\r\n")
            append("<SumNum>${cards.size}</SumNum>\r\n")
            // 0 张卡是合法结果：SumNum=0 且**不带** SDCardStatusInfo 节点。
            if (cards.isNotEmpty()) {
                append("<SDCardStatusInfo>\r\n")
                cards.zip(readings).forEach { (card, reading) ->
                    append("<Item>\r\n")
                    append("<ID>${card.id}</ID>\r\n")
                    append("<HddName>${card.name}</HddName>\r\n")
                    append("<Status>${reading.status.wireValue}</Status>\r\n")
                    // FormatProgress 是可选字段(标准 minOccurs=0)：只在格式化过程中才有意义,
                    // 其余状态**不能**补一个 0 —— 那会被平台读成"正在格式化且进度为 0"。
                    reading.progress?.let { append("<FormatProgress>$it</FormatProgress>\r\n") }
                    append("<Capacity>${card.capacityMb}</Capacity>\r\n")
                    append("<FreeSpace>${reading.freeMb}</FreeSpace>\r\n")
                    append("</Item>\r\n")
                }
                append("</SDCardStatusInfo>\r\n")
            }
            append("</Response>\r\n")
        }
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "SDCardStatus response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(
            LogLevel.Info, LogTag.Network,
            "SDCardStatus sn=$sn → 已应答(${cards.size} 张卡:${cards.joinToString { it.name }}" +
                "; 状态 ${readings.joinToString { it.status.wireValue }})"
        )
    }

    /** 墙上时间(ms)。与 `ManscdpRouterImpl.nowMs` 同口径,卡片上要显示"最近查询 20:31"。 */
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * §A.2.6.13 巡航轨迹列表查询应答。
     *
     * ⛔ 报文骨架**必须**是 `<CruiseTrackList Num><CruiseTrack><Number/><Name/></CruiseTrack>`。
     *    这里原来写的是 `<TrackList><Item><GroupID/>`,那是把 2016 版 `PresetQuery`
     *    (`PresetList`/`Item`/`PresetID`)的惯例套到了 2022 的新命令上 —— 平台按标准解析,
     *    结果是"0 条轨迹",而且 `expected = max(SumNum, …)` 拿设备的 `SumNum=1`,
     *    `0 >= 1` 恒假 → 该 operation 永不 finalize,前端巡航卡片永远停在"未验证"。
     */
    private suspend fun sendCruiseTrackListResponse(sn: String, channelId: String) {
        val s = ctx.deviceControlState.value
        val responseDeviceId = channelId.ifBlank { ctx.config.device.deviceId }
        val trackNums = s.cruiseTracks.keys.sorted()
        val sumNum = trackNums.size
        val items = trackNums.joinToString("\r\n") { trackNum ->
            "<CruiseTrack><Number>$trackNum</Number><Name>${cruiseTrackName(trackNum)}</Name></CruiseTrack>"
        }
        val itemsBlock = if (sumNum == 0) "<CruiseTrackList Num=\"0\"/>"
            else "<CruiseTrackList Num=\"$sumNum\">\r\n$items\r\n</CruiseTrackList>"
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
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) SystemLogger.emit(LogLevel.Info, LogTag.Network, "CruiseTrackList sn=$sn N=$sumNum → 已应答")
    }

    /**
     * §A.2.6.14 巡航轨迹查询应答(单条轨迹的详情)。
     *
     * ⛔ 点位集合的元素名是 `<CruisePointList Num><CruisePoint>`,点内是
     *    `<PresetIndex/>`(不是 `PresetID`)、`<StayTime/>`(不是 `DwellTime`)、`<Speed/>`。
     *    轨迹编号元素同样是 `<Number>`,不是 `GroupID`。
     *
     * `Speed`/`StayTime` 用**设备真实存的值**;平台没下发过(控制层 `0x86`/`0x87` 未发)时
     * 回设备默认值 —— 真实球机也是这么干的,它自己就带一套出厂巡航参数。
     */
    private suspend fun sendCruiseTrackResponse(sn: String, channelId: String, trackNum: Int?) {
        val s = ctx.deviceControlState.value
        val responseDeviceId = channelId.ifBlank { ctx.config.device.deviceId }
        // 编号缺失(非标准请求)时按「不存在的轨迹」应答:空点位表,不猜任何一条。
        val echoNumber = trackNum ?: 0
        val track = trackNum?.let { s.cruiseTracks[it] }
        val points = track?.points.orEmpty()
        val speed = track?.speed ?: DEFAULT_CRUISE_SPEED
        val dwell = track?.dwellTime ?: DEFAULT_CRUISE_DWELL_SECONDS
        val sumNum = points.size
        val items = points.joinToString("\r\n") { presetNum ->
            "<CruisePoint><PresetIndex>$presetNum</PresetIndex>" +
                "<StayTime>$dwell</StayTime><Speed>$speed</Speed></CruisePoint>"
        }
        val itemsBlock = if (sumNum == 0) "<CruisePointList Num=\"0\"/>"
            else "<CruisePointList Num=\"$sumNum\">\r\n$items\r\n</CruisePointList>"
        val xmlBody = "<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n" +
            "<Response>\r\n" +
            "<CmdType>CruiseTrackQuery</CmdType>\r\n" +
            "<SN>$sn</SN>\r\n" +
            "<DeviceID>$responseDeviceId</DeviceID>\r\n" +
            "<Number>$echoNumber</Number>\r\n" +
            "<Name>${cruiseTrackName(echoNumber)}</Name>\r\n" +
            "<SumNum>$sumNum</SumNum>\r\n" +
            "$itemsBlock\r\n" +
            "</Response>\r\n"
        val ok = ManscdpInternals.sendMansMessage(
            config = ctx.config, outbox = ctx.outbox, identityService = ctx.identityService,
            localIp = ctx.localIp, localPort = ctx.localPort,
            xmlBody = xmlBody,
            errorLabel = "CruiseTrack response",
            charset = SignalingCharset.of(ctx.effectiveGbVersion),
            simEventEmit = ctx.simEventEmit,
        )
        if (ok) {
            SystemLogger.emit(
                LogLevel.Info, LogTag.Network,
                "CruiseTrack #$echoNumber sn=$sn N=$sumNum 速度=$speed 停留=${dwell}s → 已应答"
            )
        }
    }

    /**
     * 轨迹名：**平台配过就用平台的**（A.2.3.1.2 `PTZCmdParams/CruiseTrackName`，2022 新增），
     * 没配过才由设备自造 `巡航 N`。
     *
     * ⛔ 顺序不可颠倒 —— 有平台名还发自造名，平台会以为"自己配的名字没保存"。
     * 原先根本没有"平台名"这个概念（`CruiseTrackName` 从未解析），所以恒发自造名。
     */
    private fun cruiseTrackName(trackNum: Int): String =
        ctx.deviceControlState.value.cruiseTracks[trackNum]?.name ?: "巡航 $trackNum"

    companion object {
        /**
         * 设备出厂巡航参数。平台**没**通过控制层 `0x86`/`0x87` 下发过时用它们应答 ——
         * 查询应答里 `Speed`/`StayTime` 是必填元素,不能空;真实球机这时回的也是自己
         * 那套出厂值,不是"无"。平台需要能区分"设备默认"和"我设过"时只能靠对账记录。
         *
         * 取值域跟控制层一致:`0x86`/`0x87` 的参数是 **12 位(1-4095)**,低 8 位在字节6、
         * 高 4 位在字节7 高半字节。所以这里给的不是 0-15 那种"档位"值。
         */
        private const val DEFAULT_CRUISE_SPEED = 128
        // 停留时间的出厂值住在 `domain/CruiseExecution.kt`:巡航**执行**那一侧也要用它
        // （平台没下发过时设备按它走）。写两份会演成「界面显示 30 秒、设备 5 秒跳一个点」。

        /**
         * `accepts()` 的受理清单。
         *
         * ⛔⛔ **这是与 [handle] 的 `when` 并列的第二道门禁，两处必须同时改。**
         * 2026-09-19 修 G-3 时踩到了：`handle` 的 `when` 加了 `"PTZPosition"`，
         * 但漏了这里 → 平台按标准发的查询仍然在 `accepts()` 就被挡掉，
         * 现象与 `DeviceConfig` 那次**一模一样**：「回了 200 但不处理」，
         * 两侧日志都看不出异常，只有平台面板一直停在 never_read。
         * 凡是往 [handle] 里加 case，**必须**同时往这里加名字；
         * `DeviceControlSubRouterTest` 里有一条"标准 CmdType 全覆盖"的用例专门盯这件事。
         */
        private val ACCEPTED = setOf(
            // ⭐ 标准名（A.2.4.13 请求 / A.2.6.15 应答同名）—— 标准路径排在前。
            "PTZPosition",
            // ⛔ 历史自造名（两版标准 0 命中），仅为兼容按旧写法实现的客户端保留。
            "PTZPreciseStatusQuery",
            "DeviceControl", "PresetQuery",
            "HomePositionQuery", "SDCardStatus", "StorageCardStatusQuery",
            "CruiseTrackListQuery", "CruiseTrackQuery",
            // ⛔ 2026-09-18 补：`DeviceConfig` 是 A.2.3.2 设备配置的 CmdType，
            //    与 `DeviceControl`(A.2.3.1 控制类)**是两个不同的值**。
            //    漏了它的后果不是"某条命令不生效"，而是**整类配置下发被静默吞掉**
            //    （只回 200 + 一条 Warning），包含它的 `SystemHandler.handleDeviceConfig`
            //    一并变成死代码。凡是"平台下发了、设备侧毫无反应"的配置类命令，
            //    第一个要查的就是这里。
            "DeviceConfig",
        )
    }
}
